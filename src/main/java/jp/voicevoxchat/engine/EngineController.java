/* VOICEVOX Engineの接続状態を確認し、このMODが起動したプロセスだけを非同期に起動・停止する。 */
package jp.voicevoxchat.engine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** このコントローラーが起動したプロセスだけを所有し、外部で起動されたエンジンは停止しない。 */
public final class EngineController implements AutoCloseable {
    public enum State { STOPPED, STARTING, OWNED_RUNNING, EXTERNAL_RUNNING, STOPPING, ERROR }

    private final String executablePath;
    private final URI baseUri;
    private final Path logFile;
    private final Probe probe;
    private final Launcher launcher;
    private final Duration startupTimeout;
    private final Duration pollInterval;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "voicevox-engine-control");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong stopVersion = new AtomicLong();
    private final Object submissionLock = new Object();
    private final Thread shutdownHook;
    private volatile State state = State.STOPPED;
    private volatile String detail = "停止中";
    private volatile ManagedProcess owned;
    private volatile boolean closed;
    private CompletableFuture<Void> closeFuture;

    /** HTTPでの接続確認と標準のプロセス起動処理を使い、JVM終了時の停止処理も登録する。 */
    public EngineController(String executablePath, URI baseUri, Path logFile) {
        this(executablePath, baseUri, logFile, new HttpProbe(), EngineController::launch,
                Duration.ofSeconds(90), Duration.ofMillis(250), true);
    }

    /** 接続確認・起動方法・待機時間を受け取って初期化し、実エンジンを使わない検証にも対応する。 */
    EngineController(String executablePath, URI baseUri, Path logFile, Probe probe, Launcher launcher,
                     Duration startupTimeout, Duration pollInterval, boolean installShutdownHook) {
        this.executablePath = Objects.requireNonNull(executablePath);
        this.baseUri = Objects.requireNonNull(baseUri);
        this.logFile = Objects.requireNonNull(logFile).toAbsolutePath().normalize();
        this.probe = Objects.requireNonNull(probe);
        this.launcher = Objects.requireNonNull(launcher);
        this.startupTimeout = startupTimeout;
        this.pollInterval = pollInterval;
        if (installShutdownHook) {
            shutdownHook = new Thread(() -> {
                close();
                try {
                    closeFuture.get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // A final best effort, still restricted to explicitly owned process handles.
                    ManagedProcess remaining = owned;
                    if (remaining != null) remaining.forceStop();
                }
            }, "voicevox-engine-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } else {
            shutdownHook = null;
        }
    }

    /** 最新のエンジン接続・起動状態を返す。 */
    public State state() { return state; }
    /** 現在の状態を設定画面へ表示するための説明文を返す。 */
    public String detail() { return detail; }

    /** 起動途中も含め、このMODが所有するプロセスが生存しているかを返す。 */
    public boolean isOwnedRunning() {
        ManagedProcess process = owned;
        return process != null && process.isAlive();
    }

    /** 実行ファイルが設定済みなら起動し、未設定なら外部エンジンの接続状態だけを確認する。 */
    public CompletableFuture<Void> startConfigured() {
        return executablePath.isBlank() ? refresh() : start();
    }

    /** 現時点の停止世代を記録し、エンジンの起動処理を専用スレッドへ登録する。 */
    public CompletableFuture<Void> start() {
        synchronized (submissionLock) {
            long version = stopVersion.get();
            return submit(() -> startOnWorker(version));
        }
    }

    /** 進行中の起動を無効化し、所有するエンジンの停止処理を専用スレッドへ登録する。 */
    public CompletableFuture<Void> stop() {
        synchronized (submissionLock) {
            stopVersion.incrementAndGet();
            return submit(this::stopOnWorker);
        }
    }

    /** プロセスの生存とHTTP応答に基づく状態更新を専用スレッドへ登録する。 */
    public CompletableFuture<Void> refresh() {
        synchronized (submissionLock) {
            return submit(this::refreshOnWorker);
        }
    }

    /** 終了済みなら失敗結果を返し、それ以外は指定処理を実行キューへ渡す。 */
    private CompletableFuture<Void> submit(CheckedAction action) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Controller is closed"));
        return enqueue(action);
    }

    /** 処理を直列に実行し、成功・中止・エラーをFutureと表示用の状態へ反映する。 */
    private CompletableFuture<Void> enqueue(CheckedAction action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                action.run();
                result.complete(null);
            } catch (CancellationException exception) {
                result.completeExceptionally(exception);
            } catch (Exception exception) {
                setState(State.ERROR, errorMessage(exception));
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** 外部エンジンを優先して接続し、必要なら設定された実行ファイルを起動して応答開始まで待つ。 */
    private void startOnWorker(long version) throws Exception {
        checkCancelled(version);
        if (owned != null && !owned.isAlive()) stopOwned();
        if (owned == null && isReady()) {
            checkCancelled(version);
            setState(State.EXTERNAL_RUNNING, "外部で起動されたエンジンに接続中（この Mod からは停止しません）");
            return;
        }
        try {
            if (owned == null) {
                String host = managedHost(baseUri);
                if (executablePath.isBlank()) throw new IOException("config に run.exe のパスを設定してください");
                Path executable = Path.of(executablePath).toAbsolutePath().normalize();
                if (!Files.isRegularFile(executable)) throw new IOException("実行ファイルが見つかりません: " + executable);
                checkCancelled(version);
                setState(State.STARTING, "エンジン起動中…");
                owned = launcher.launch(executable, host, effectivePort(baseUri), logFile);
            }
            long deadline = System.nanoTime() + startupTimeout.toNanos();
            while (System.nanoTime() < deadline) {
                checkCancelled(version);
                owned.rememberDescendants();
                if (!owned.isAlive()) throw new IOException("エンジンが終了しました。ログを確認してください: " + logFile);
                if (isReady()) {
                    checkCancelled(version);
                    if (!owned.isAlive()) throw new IOException("エンジンが起動中に終了しました: " + logFile);
                    setState(State.OWNED_RUNNING, "この Mod が起動したエンジンに接続中");
                    return;
                }
                TimeUnit.NANOSECONDS.sleep(pollInterval.toNanos());
            }
            throw new IOException("エンジンの起動がタイムアウトしました。ログを確認してください: " + logFile);
        } catch (Exception exception) {
            try {
                stopOwned();
            } catch (Exception cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            if (exception instanceof CancellationException) setState(State.STOPPED, "起動を中止しました");
            throw exception;
        }
    }

    /** 所有プロセスだけを停止し、外部エンジンの応答が残る場合は外部接続中の状態にする。 */
    private void stopOnWorker() throws Exception {
        if (owned != null) {
            setState(State.STOPPING, "エンジン停止中…");
            stopOwned();
        }
        if (!closed && isReady()) {
            setState(State.EXTERNAL_RUNNING, "外部で起動されたエンジンは停止しません");
        } else {
            setState(State.STOPPED, "停止中");
        }
    }

    /** 所有プロセスの生存とAPI応答を確認し、所有・外部接続・停止・エラーの状態を更新する。 */
    private void refreshOnWorker() throws Exception {
        if (closed) return;
        if (owned != null) {
            owned.rememberDescendants();
            if (!owned.isAlive()) {
                stopOwned();
            } else {
                boolean ready = isReady();
                setState(ready ? State.OWNED_RUNNING : State.ERROR,
                        ready ? "この Mod が起動したエンジンに接続中" : "起動したエンジンが応答しません。停止して再起動できます");
                return;
            }
        }
        boolean ready = isReady();
        setState(ready ? State.EXTERNAL_RUNNING : State.STOPPED,
                ready ? "外部で起動されたエンジンに接続中（この Mod からは停止しません）" : "停止中");
    }

    /** version APIへの接続可否を調べ、割り込み以外の通信失敗は未準備として扱う。 */
    private boolean isReady() throws InterruptedException {
        try {
            return probe.ready(baseUri.resolve("version"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 所有するプロセスツリーを停止し、停止成功後に所有情報を破棄する。 */
    private void stopOwned() throws Exception {
        if (owned != null) {
            owned.stop();
            owned = null;
        }
    }

    /** コントローラーの終了または停止世代の変更を検出し、進行中の起動を中止する。 */
    private void checkCancelled(long version) {
        if (closed || stopVersion.get() != version) throw new CancellationException("Engine startup cancelled");
    }

    /** 設定画面などが参照する状態の説明文と状態値を更新する。 */
    private void setState(State next, String message) {
        detail = message;
        state = next;
    }

    /** 例外のメッセージを取得し、空の場合は例外クラス名を説明として返す。 */
    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /** 起動を中止し、所有プロセスと接続確認機能の終了を専用スレッドへ登録する。 */
    @Override
    public void close() {
        synchronized (submissionLock) {
            if (closed) return;
            closed = true;
            stopVersion.incrementAndGet();
            closeFuture = enqueue(() -> {
                try {
                    stopOnWorker();
                } finally {
                    probe.close();
                }
                if (shutdownHook != null) {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    } catch (IllegalStateException ignored) {
                        // The JVM is already executing its hooks.
                    }
                }
            });
            worker.shutdown();
        }
    }

    /** 管理対象として起動できるループバックHTTP URLかを検証し、起動引数に使うホストを返す。 */
    static String managedHost(URI uri) {
        String host = uri.getHost();
        if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("エンジン起動にはループバックの HTTP URL（例: http://127.0.0.1:50021/）が必要です");
        }
        effectivePort(uri);
        if ("localhost".equalsIgnoreCase(host)) return "127.0.0.1";
        if ("[::1]".equals(host) || "::1".equals(host)) return "::1";
        String[] parts = host.split("\\.");
        if (parts.length == 4 && "127".equals(parts[0])) {
            try {
                for (String part : parts) {
                    if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) throw new NumberFormatException();
                }
                return host;
            } catch (NumberFormatException ignored) {
                // Never resolve arbitrary names to decide whether a managed launch is safe.
            }
        }
        throw new IllegalArgumentException("エンジン起動は localhost / 127.x.x.x / ::1 のみ対応しています");
    }

    /** URLのポートを検証して返し、ポートが省略されている場合はHTTPの既定値80を返す。 */
    private static int effectivePort(URI uri) {
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("エンジンのポート番号が不正です");
        return port;
    }

    /** 指定ホスト・ポートでエンジンを起動し、出力をログへ保存して所有プロセスとして管理する。 */
    private static ManagedProcess launch(Path executable, String host, int port, Path logFile) throws IOException {
        if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
        // Java 21 on Windows uses CREATE_NO_WINDOW with redirected I/O. No shell or inherited console.
        Process process = new ProcessBuilder(executable.toString(), "--host", host, "--port", Integer.toString(port))
                .directory(executable.getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();
        // The engine does not read stdin; closing it also releases the unused pipe.
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The engine may have exited already; retain ownership for cleanup regardless.
        }
        return new ProcessTree(process);
    }

    @FunctionalInterface
    interface CheckedAction {
        /** 専用スレッド上で実行する処理を行い、失敗時は例外を呼び出し元へ伝える。 */
        void run() throws Exception;
    }

    interface Probe extends AutoCloseable {
        /** 指定されたversion APIが利用可能なエンジンの応答を返すかを確認する。 */
        boolean ready(URI versionUri) throws Exception;
        /** 接続確認に使った資源を解放する。資源を持たない実装では何もしない。 */
        @Override default void close() { }
    }

    @FunctionalInterface
    interface Launcher {
        /** 指定の実行ファイル・接続先・ログ出力先を使用して管理対象プロセスを起動する。 */
        ManagedProcess launch(Path executable, String host, int port, Path logFile) throws Exception;
    }

    interface ManagedProcess {
        /** 管理対象の親プロセスが生存しているかを返す。 */
        boolean isAlive();
        /** 起動した親プロセスから派生した子孫プロセスを、後の停止に備えて記録する。 */
        void rememberDescendants();
        /** 管理対象の親と記録済みの子孫プロセスを停止する。 */
        void stop() throws Exception;
        /** 管理対象のプロセスを終了待機せず強制停止する。 */
        void forceStop();
    }

    private static final class HttpProbe implements Probe {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();

        /** version APIの応答本文まで2秒以内に受信し、成功応答と短いバージョン文字列を確認する。 */
        @Override
        public boolean ready(URI versionUri) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(versionUri).timeout(Duration.ofSeconds(2)).GET().build();
            CompletableFuture<HttpResponse<String>> pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                // Bound the complete response body too, so stop/config reload cannot hang after headers arrive.
                HttpResponse<String> response = pending.get(2, TimeUnit.SECONDS);
                String body = response.body().strip();
                return response.statusCode() == 200 && body.matches("\"[^\"\\r\\n]{1,128}\"");
            } catch (TimeoutException exception) {
                throw new IOException("VOICEVOX /version response timed out", exception);
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof IOException cause) throw cause;
                throw new IOException("VOICEVOX /version request failed", exception.getCause());
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
        }

        /** 接続確認用HTTPクライアントの処理と接続を終了する。 */
        @Override public void close() { client.shutdownNow(); }
    }

    /** 起動結果とその子孫から取得したプロセスハンドルだけを保持する。 */
    private static final class ProcessTree implements ManagedProcess {
        private final Process process;
        private final Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        private final ScheduledExecutorService observer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voicevox-engine-process-tree");
            thread.setDaemon(true);
            return thread;
        });

        /** 起動した親プロセスを保持し、停止時に子孫を取りこぼさないよう定期的な記録を始める。 */
        private ProcessTree(Process process) {
            this.process = process;
            // Retain worker handles while the engine is idle too, before they can be reparented.
            observer.scheduleWithFixedDelay(() -> {
                rememberDescendants();
                if (!process.isAlive()) observer.shutdown();
            }, 0, 250, TimeUnit.MILLISECONDS);
        }
        /** 起動した親プロセスが生存しているかを返す。 */
        @Override public boolean isAlive() { return process.isAlive(); }

        /** 親が生存している間に見つかった子孫プロセスを、プロセスIDごとに記録する。 */
        @Override
        public synchronized void rememberDescendants() {
            if (process.isAlive()) process.descendants().forEach(child -> descendants.put(child.pid(), child));
        }

        /** 最新の子孫を記録し、停止対象となる子孫と親のハンドル一覧を返す。 */
        private synchronized List<ProcessHandle> handles() {
            rememberDescendants();
            List<ProcessHandle> handles = new ArrayList<>(descendants.values());
            handles.add(process.toHandle());
            return handles;
        }

        /** 親から順に通常停止を試み、残存プロセスを強制停止して終了を確認する。 */
        @Override
        public void stop() throws Exception {
            List<ProcessHandle> handles = handles();
            // Stop the parent first so it cannot deliberately start replacement workers.
            process.destroy();
            for (ProcessHandle handle : handles) if (handle.isAlive()) handle.destroy();
            awaitExit(handles, Duration.ofSeconds(2));
            for (ProcessHandle handle : handles) if (handle.isAlive()) handle.destroyForcibly();
            awaitExit(handles, Duration.ofSeconds(2));
            if (handles.stream().anyMatch(ProcessHandle::isAlive)) throw new IOException("起動したエンジンのプロセスを終了できませんでした");
            observer.shutdown();
        }

        /** 親と記録済みの子孫を強制停止し、子孫監視スレッドも終了する。 */
        @Override
        public void forceStop() {
            for (ProcessHandle handle : handles()) if (handle.isAlive()) handle.destroyForcibly();
            observer.shutdown();
        }

        /** 対象の全プロセスが終了するか、指定された待機期限に達するまで短い間隔で確認する。 */
        private static void awaitExit(List<ProcessHandle> handles, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
        }
    }
}
