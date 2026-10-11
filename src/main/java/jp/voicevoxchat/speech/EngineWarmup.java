/* 選択済み話者をエンジンへ事前に読み込ませ、初回読み上げ時のモデル初期化待ちを軽減する。 */
package jp.voicevoxchat.speech;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** 明示的に選択された話者だけを事前準備し、準備のための音声合成や再生は行わない。 */
public final class EngineWarmup implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RESPONSE_BYTES = 8 * 1024;

    private final Consumer<String> warnings;
    private final Duration timeout;
    private final HttpClient client;
    private final ExecutorService executor = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("voicevox-warmup-", 0).factory());
    // Successful tasks are retained until the associated engine restarts. Access under this monitor.
    private final Map<Key, Task> tasks = new LinkedHashMap<>();
    private boolean closed;

    /** 警告の通知先を受け取り、既定の60秒を準備リクエストの待機時間として使用する。 */
    public EngineWarmup(Consumer<String> warnings) {
        this(warnings, REQUEST_TIMEOUT);
    }

    /** 警告の通知先と待機時間を検証し、エンジン事前準備用のHTTPクライアントを作成する。 */
    EngineWarmup(Consumer<String> warnings, Duration timeout) {
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive timeout required");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * 設定済みの初期話者・個別話者を非同期に準備し、同じエンジンとIDの処理を共有する。
     * 起動時間とメモリ消費を抑えるため、ランダム候補全体ではなく使用予定の話者だけを渡す。
     * 初期化APIの非対応や失敗は警告に留め、通常の読み上げを妨げない。
     */
    public synchronized CompletableFuture<Void> warm(Collection<SpeakerCatalog.Voice> voices) {
        if (closed) return CompletableFuture.failedFuture(new CancellationException("Engine warmup is closed"));
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (SpeakerCatalog.Voice voice : voices) {
            if (voice.id() < 0) throw new IllegalArgumentException("Invalid speaker ID");
            Key key = new Key(SpeechService.baseUrl(voice.engineUri()), voice.id());
            Task task = tasks.get(key);
            if (task == null) {
                task = new Task(key, voice.engineName());
                tasks.put(key, task);
                Task submitted = task;
                executor.execute(() -> initialize(submitted));
            }
            pending.add(task.finished);
        }
        // Cancelling one observer must not cancel another caller's preparation or startup notice.
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    /** 指定エンジンの準備済み記録を削除し、その接続先で進行中の準備も取り消す。 */
    public synchronized void reset(URI engineUri) {
        String baseUrl = SpeechService.baseUrl(engineUri);
        List<Task> invalidated = tasks.values().stream()
                .filter(task -> task.key.baseUrl.equals(baseUrl)).toList();
        for (Task task : invalidated) tasks.remove(task.key);
        // Completion callbacks can reenter this object, so cancel after the map traversal ends.
        for (Task task : invalidated) task.cancel();
    }

    /** すべての準備処理を取り消し、準備用スレッドとHTTPクライアントを終了する。 */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        List<Task> cancelled = List.copyOf(tasks.values());
        tasks.clear();
        for (Task task : cancelled) task.cancel();
        executor.shutdownNow();
        client.shutdownNow();
    }

    /** initialize_speaker APIでモデルを準備し、失敗は警告として通知して後の再試行を可能にする。 */
    private void initialize(Task task) {
        CompletableFuture<HttpResponse<byte[]>> request = null;
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(task.key.baseUrl
                            + "initialize_speaker?speaker=" + task.key.speakerId + "&skip_reinit=true"))
                    .timeout(timeout).POST(HttpRequest.BodyPublishers.noBody()).build();
            synchronized (this) {
                if (!current(task)) return;
                request = client.sendAsync(httpRequest,
                        ignored -> new SpeechService.LimitedBodySubscriber(MAX_RESPONSE_BYTES));
                task.request = request;
            }
            // Also bound reading the complete body; a server may send headers and then stall.
            HttpResponse<byte[]> response = request.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode());
            }
            synchronized (this) {
                if (current(task)) task.finished.complete(null);
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            String warning;
            synchronized (this) {
                if (!current(task)) return;
                Throwable cause = exception instanceof ExecutionException && exception.getCause() != null
                        ? exception.getCause() : exception;
                String detail = cause instanceof TimeoutException ? "応答がタイムアウトしました"
                        : cause instanceof IOException && cause.getMessage() != null ? cause.getMessage()
                        : cause.getClass().getSimpleName();
                if (detail.length() > 160) detail = detail.substring(0, 160);
                warning = task.engineName + " の話者ID " + task.key.speakerId
                        + " を事前準備できませんでした（" + detail + "）。読み上げ時に通常の音声合成を試みます。";
            }
            try {
                warnings.accept(warning);
            } catch (RuntimeException ignored) {
                // A logging/UI callback cannot retain an unfinished background task.
            } finally {
                synchronized (this) {
                    if (current(task)) {
                        tasks.remove(task.key);
                        // A failed optimization must not turn startup or speech into a fatal error.
                        task.finished.complete(null);
                    }
                }
            }
        } finally {
            if (request != null && !request.isDone()) request.cancel(true);
            synchronized (this) { task.request = null; }
        }
    }

    /** 対象タスクが現在も登録されており、終了・取り消し・置き換えがされていないかを確認する。 */
    private boolean current(Task task) {
        return !closed && tasks.get(task.key) == task && !task.finished.isDone();
    }

    private record Key(String baseUrl, int speakerId) {}

    private static final class Task {
        private final Key key;
        private final String engineName;
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private CompletableFuture<HttpResponse<byte[]>> request;

        /** エンジンと話者IDのキー、および警告に表示するエンジン名を保持する。 */
        private Task(Key key, String engineName) {
            this.key = key;
            this.engineName = engineName;
        }

        /** 完了待ちのFutureを取り消し、送信済みのHTTPリクエストがあればそれも中止する。 */
        private void cancel() {
            finished.cancel(true);
            if (request != null) request.cancel(true);
        }
    }
}
