/* チャットの読み上げキューを管理し、話者の決定・HTTP音声合成・再生・取り消しを順番に処理する。 */
package jp.voicevoxchat.speech;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** チャット本文を音声合成し、渡された再生機能へ受信順に送る。 */
public final class SpeechService implements AutoCloseable {
    public static final int MAX_PENDING_MESSAGES = 64;
    private static final int MAX_TEXT_LENGTH = 4096;
    private static final int MAX_QUERY_BYTES = 1024 * 1024;
    private static final int MAX_WAV_BYTES = 32 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final Object lock = new Object();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final EngineResolver engineResolver;
    private final Consumer<String> errorHandler;
    private final AudioPlayback playback;
    private final HttpClient client;
    private final Thread worker;
    private Job active;
    private volatile boolean closed;

    /** すべての話者に同じエンジンを使用する読み上げサービスを作成する。 */
    public SpeechService(URI engineUri, Consumer<String> errorHandler, AudioPlayback playback) {
        this(fixedEngine(engineUri), errorHandler, playback);
    }

    /** 話者別の接続先解決・エラー通知・再生機能を受け取り、HTTPクライアントと処理スレッドを起動する。 */
    public SpeechService(EngineResolver engineResolver, Consumer<String> errorHandler, AudioPlayback playback) {
        this.engineResolver = Objects.requireNonNull(engineResolver, "engineResolver");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.playback = Objects.requireNonNull(playback, "playback");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        worker = new Thread(this::runWorker, "voicevox-chat-speech");
        worker.setDaemon(true);
        worker.start();
    }

    /** 接続先URLを検証し、どの話者IDにも同じ接続先を返す処理を作成する。 */
    private static EngineResolver fixedEngine(URI uri) {
        baseUrl(uri);
        return speakerId -> uri;
    }

    /** HTTPまたはHTTPSの接続先として検証し、APIのパスを連結できる末尾スラッシュ付きURLを返す。 */
    static String baseUrl(URI engineUri) {
        Objects.requireNonNull(engineUri, "engineUri");
        if (!("http".equalsIgnoreCase(engineUri.getScheme())
                || "https".equalsIgnoreCase(engineUri.getScheme()))
                || engineUri.getHost() == null || engineUri.getRawQuery() != null
                || engineUri.getRawFragment() != null || engineUri.getUserInfo() != null) {
            throw new IllegalArgumentException("VOICEVOX Engine URL must be an HTTP(S) base URL");
        }
        String url = engineUri.toASCIIString();
        return url.endsWith("/") ? url : url + "/";
    }

    /** 本文と固定話者IDをキューへ追加し、入力不正・終了済み・キュー満杯の場合はfalseを返す。 */
    public boolean enqueue(String text, int speakerId) {
        return speakerId >= 0 && enqueue(text, () -> speakerId);
    }

    /** 本文と話者選択処理をキューへ追加し、話者一覧の取得を伴う選択は専用スレッドまで遅延する。 */
    public boolean enqueue(String text, SpeakerSelection speakerSelection) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH || speakerSelection == null) {
            return false;
        }
        synchronized (lock) {
            if (closed || queue.size() + (active == null ? 0 : 1) >= MAX_PENDING_MESSAGES) {
                return false;
            }
            queue.addLast(new Job(text, speakerSelection));
            lock.notifyAll();
            return true;
        }
    }

    /** 待機中の発言と、取り消されていない合成・再生中の発言を合計した件数を返す。 */
    public int pendingCount() {
        synchronized (lock) {
            return queue.size() + (active == null || active.cancelled ? 0 : 1);
        }
    }

    /** 待機キューを空にし、進行中のHTTPリクエスト・話者解決・音声再生を中止する。 */
    public void clear() {
        synchronized (lock) {
            queue.clear();
            cancelActive();
        }
    }

    /** 新規受付を終了して待機・実行中の読み上げを取り消し、処理スレッドとHTTP通信を終了する。 */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            queue.clear();
            cancelActive();
            lock.notifyAll();
        }
        worker.interrupt();
        client.shutdownNow();
    }

    /** 実行中の発言を取り消し済みにし、そのHTTP通信・話者解決の待機・音声再生を止める。 */
    private void cancelActive() {
        if (active != null) {
            active.cancelled = true;
            if (active.request != null) {
                active.request.cancel(true);
            }
            if (active.resolving) {
                // Cancels this worker's wait without cancelling discovery shared with the UI.
                worker.interrupt();
            }
            playback.stop();
        }
    }

    /** キューから1件ずつ取り出して話者とエンジンを決定し、音声クエリ生成・合成・再生を行う。 */
    private void runWorker() {
        while (true) {
            Job job;
            synchronized (lock) {
                while (!closed && queue.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        if (closed) {
                            return;
                        }
                    }
                }
                if (closed) {
                    return;
                }
                job = queue.removeFirst();
                active = job;
            }
            try {
                checkCancelled(job);
                // Select and resolve only once so query and synthesis share one voice and engine.
                String engineUrl = resolveEngine(job);
                byte[] query = post(job, engineUrl, "audio_query?text="
                        + URLEncoder.encode(job.text, StandardCharsets.UTF_8)
                        + "&speaker=" + job.speakerId, null, MAX_QUERY_BYTES);
                byte[] wav = post(job, engineUrl, "synthesis?speaker=" + job.speakerId, query, MAX_WAV_BYTES);
                checkCancelled(job);
                playback.play(wav, () -> job.cancelled || closed);
            } catch (Exception e) {
                if (!job.cancelled && !closed) {
                    reportFailure(e);
                }
            } finally {
                synchronized (lock) {
                    active = null;
                }
            }
        }
    }

    /** 発言の話者IDを一度だけ選択して対応エンジンを解決し、待機中の取り消しにも対応する。 */
    private String resolveEngine(Job job) throws Exception {
        synchronized (lock) {
            checkCancelled(job);
            job.resolving = true;
        }
        try {
            job.speakerId = job.speakerSelection.select();
            checkCancelled(job);
            if (job.speakerId < 0) throw new IOException("選択された話者IDが不正です: " + job.speakerId);
            return baseUrl(engineResolver.resolve(job.speakerId));
        } finally {
            synchronized (lock) {
                job.resolving = false;
                // A cancellation of this job must not interrupt the next queued message.
                Thread.interrupted();
            }
        }
    }

    /** 合成用APIへPOSTし、取り消し・応答期限・受信サイズ・HTTPステータスを確認して本文を返す。 */
    private byte[] post(Job job, String baseUrl, String path, byte[] json, int maxBytes) throws Exception {
        checkCancelled(job);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", json == null ? "application/json" : "audio/wav");
        if (json == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json));
        }
        CompletableFuture<HttpResponse<byte[]>> request = client.sendAsync(builder.build(),
                ignored -> new LimitedBodySubscriber(maxBytes));
        synchronized (lock) {
            job.request = request;
            if (job.cancelled || closed) {
                request.cancel(true);
            }
        }
        try {
            // This additional deadline also bounds stalled response bodies.
            HttpResponse<byte[]> response = request.get(REQUEST_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
            checkCancelled(job);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String endpoint = path.split("\\?", 2)[0];
                throw new IOException("VOICEVOX /" + endpoint + " (話者ID: " + job.speakerId
                        + ") HTTP " + response.statusCode() + errorCode(response.body()));
            }
            return response.body();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        } catch (InterruptedException | TimeoutException e) {
            request.cancel(true);
            throw e;
        } finally {
            synchronized (lock) {
                job.request = null;
            }
        }
    }

    /** 応答JSONから短い機械用エラーコードだけを抽出し、サーバーが返したチャット本文は記録しない。 */
    private static String errorCode(byte[] body) {
        if (body.length > 64 * 1024) return "";
        try {
            JsonElement element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            for (int depth = 0; depth < 3 && element.isJsonObject(); depth++) {
                JsonObject object = element.getAsJsonObject();
                for (String key : List.of("errorName", "code")) {
                    if (object.has(key) && object.get(key).isJsonPrimitive()) {
                        String code = object.get(key).getAsString();
                        if (code.matches("[A-Za-z][A-Za-z0-9_]{0,79}")) return ": " + code;
                    }
                }
                element = object.get("detail");
                if (element == null) break;
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    element = JsonParser.parseString(element.getAsString());
                }
            }
        } catch (RuntimeException ignored) {
            // The status, endpoint and style ID still identify non-JSON errors.
        }
        return "";
    }

    /** 発言が取り消されているかサービスが終了している場合、処理を中止する例外を投げる。 */
    private void checkCancelled(Job job) {
        if (job.cancelled || closed) {
            throw new CancellationException();
        }
    }

    /** 読み上げ失敗の説明を通知し、通知先の例外によってキュー処理が終了することを防ぐ。 */
    private void reportFailure(Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = error.getClass().getSimpleName();
        }
        try {
            errorHandler.accept("VOICEVOX の読み上げに失敗しました: " + detail);
        } catch (RuntimeException ignored) {
            // A notification callback must not terminate the queue worker.
        }
    }

    @FunctionalInterface
    public interface SpeakerSelection {
        /** 発言ごとに読み上げスレッドで一度実行し、必要なら一覧の取得を待って話者IDを返す。 */
        int select() throws Exception;
    }

    @FunctionalInterface
    public interface EngineResolver {
        /** 読み上げスレッドで、指定した読み上げ用話者IDを提供するエンジンの接続先を返す。 */
        URI resolve(int speakerId) throws Exception;
    }

    /** Minecraftへの依存をこのクラスへ持ち込まず、クライアント側の再生機能へ処理を委譲する。 */
    public interface AudioPlayback {
        /** 読み上げスレッドから音声を再生し、再生完了または取り消しまで待って戻る。 */
        void play(byte[] wav, BooleanSupplier cancelled) throws Exception;

        /** クライアントスレッドからも呼び出せるよう、再生終了を待たずに中止を通知する。 */
        void stop();
    }

    private static final class Job {
        private final String text;
        private final SpeakerSelection speakerSelection;
        // Written and read only by the speech worker after selection completes.
        private int speakerId = -1;
        private volatile boolean cancelled;
        // Accessed only while holding lock.
        private CompletableFuture<?> request;
        private boolean resolving;

        /** 1件分の本文と、読み上げ開始時に呼び出す話者選択処理を保持する。 */
        private Job(String text, SpeakerSelection speakerSelection) {
            this.text = text;
            this.speakerSelection = speakerSelection;
        }
    }

    /** HTTP通信の非同期キャンセルに対応しながら、上限サイズまで応答本文を受信する。 */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;

        /** 受信するHTTP応答本文の最大バイト数を設定する。 */
        LimitedBodySubscriber(int limit) {
            this.limit = limit;
        }

        /** 応答本文の受信完了または失敗を通知するFutureを返す。 */
        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        /** 受信購読を保持し、最初のデータ群を1回分要求する。 */
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        /** 受信データを蓄積して次を要求し、上限を超える場合は通信を取り消して失敗を通知する。 */
        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer buffer : items) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("VOICEVOX response exceeds " + limit + " bytes"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        /** HTTP本文の受信失敗を、本文取得を待つ呼び出し元へ伝える。 */
        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        /** 蓄積した全バイト列を使って応答本文の受信完了を通知する。 */
        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
