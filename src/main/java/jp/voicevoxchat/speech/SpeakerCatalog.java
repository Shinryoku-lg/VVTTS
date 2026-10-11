/* 各VOICEVOX Engineの話者一覧を取得し、名前の表示・ランダム選択・話者IDに対応する接続先の解決に使う。 */
package jp.voicevoxchat.speech;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntUnaryOperator;

/** IDの数値範囲を決め打ちせず、各エンジンが公開する読み上げ用スタイルを取得する。 */
public final class SpeakerCatalog implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final long NEGATIVE_CACHE_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final int MAX_SPEAKERS_BYTES = 2 * 1024 * 1024;

    private final List<Endpoint> endpoints;
    private final HttpClient client;
    private final ExecutorService executor;
    private volatile State state = new State(new Snapshot(List.of(), List.of()), Map.of());
    // Protected by this monitor. A refresh is shared by the UI and speech worker.
    private CompletableFuture<Snapshot> refreshing;
    private long lastRefreshNanos;
    private boolean closed;

    public record Voice(int id, String speakerName, String styleName, String engineName, URI engineUri) {}

    public record Snapshot(List<Voice> voices, List<String> errors) {
        /** 話者一覧と取得エラーを変更不可のリストへコピーして、一貫した取得結果を保持する。 */
        public Snapshot {
            voices = List.copyOf(voices);
            errors = List.copyOf(errors);
        }
    }

    /** 通常版とNemoの接続先を重複なく登録し、話者一覧を並行取得するための通信処理を用意する。 */
    public SpeakerCatalog(URI engineUri, URI nemoEngineUri) {
        List<Endpoint> configured = new ArrayList<>();
        String primary = SpeechService.baseUrl(engineUri);
        configured.add(new Endpoint("VOICEVOX", engineUri, primary));
        if (nemoEngineUri != null) {
            String nemo = SpeechService.baseUrl(nemoEngineUri);
            if (!primary.equals(nemo)) configured.add(new Endpoint("VOICEVOX Nemo", nemoEngineUri, nemo));
        }
        endpoints = List.copyOf(configured);
        executor = Executors.newFixedThreadPool(endpoints.size(),
                Thread.ofPlatform().daemon().name("voicevox-speakers-", 0).factory());
        client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 各エンジンの一覧を並行取得し、一部の取得が失敗しても成功した話者とエラーを公開する。 */
    public synchronized CompletableFuture<Snapshot> refresh() {
        if (closed) return CompletableFuture.failedFuture(new CancellationException("Speaker catalog is closed"));
        if (refreshing != null && !refreshing.isDone()) return refreshing.copy();
        List<CompletableFuture<EndpointResult>> requests = endpoints.stream()
                .map(endpoint -> CompletableFuture.supplyAsync(() -> fetch(endpoint), executor)).toList();
        refreshing = CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> publish(requests.stream().map(CompletableFuture::join).toList()));
        return refreshing.copy();
    }

    /** 取得中の処理があれば終了後に再取得し、直前に起動したエンジンの情報を確実に取得し直す。 */
    public synchronized CompletableFuture<Snapshot> refreshAfterCurrent() {
        if (closed) return CompletableFuture.failedFuture(new CancellationException("Speaker catalog is closed"));
        if (refreshing != null && !refreshing.isDone()) {
            // A caller cancelling its returned future must not cancel discovery or prevent this follow-up.
            return refreshing.handle((snapshot, failure) -> null).thenCompose(ignored -> refresh()).copy();
        }
        return refresh();
    }

    /** 最後に取得を完了した話者一覧とエラーのスナップショットを返す。 */
    public Snapshot snapshot() {
        return state.snapshot;
    }

    /** バックグラウンド処理から呼び出し、取得済みの読み上げ用話者IDを等確率で1つ選ぶ。 */
    public int randomSpeakerId() throws Exception {
        return randomSpeakerId(bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    /** 一覧が空なら必要に応じて再取得し、指定のインデックス選択処理で話者IDを決定する。 */
    int randomSpeakerId(IntUnaryOperator randomIndex) throws Exception {
        CompletableFuture<Snapshot> pending = null;
        synchronized (this) {
            if (closed) throw new CancellationException("Speaker catalog is closed");
            if (state.snapshot.voices.isEmpty()) {
                pending = refreshing != null && !refreshing.isDone() ? refreshing.copy() : null;
                if (pending == null && (lastRefreshNanos == 0
                        || System.nanoTime() - lastRefreshNanos >= NEGATIVE_CACHE_NANOS)) {
                    pending = refresh();
                }
            }
        }
        awaitRefresh(pending);
        Snapshot current = state.snapshot;
        if (current.voices.isEmpty()) {
            String hint = "ランダムに選択できる話者がありません。Engineを起動し、話者一覧を更新してください。";
            if (!current.errors.isEmpty()) hint += " " + String.join(" / ", current.errors);
            throw new IOException(hint);
        }
        return current.voices.get(randomIndex.applyAsInt(current.voices.size())).id;
    }

    /** 読み上げ用スレッドで話者IDの接続先を解決し、未登録なら直近3秒以内の再取得を抑制して一覧を更新する。 */
    public URI resolve(int speakerId) throws Exception {
        CompletableFuture<Snapshot> pending;
        synchronized (this) {
            if (closed) throw new CancellationException("Speaker catalog is closed");
            URI known = state.routes.get(speakerId);
            if (known != null) return known;
            pending = refreshing != null && !refreshing.isDone() ? refreshing.copy() : null;
            if (pending == null && (lastRefreshNanos == 0
                    || System.nanoTime() - lastRefreshNanos >= NEGATIVE_CACHE_NANOS)) {
                pending = refresh();
            }
        }
        awaitRefresh(pending);
        State current = state;
        URI known = current.routes.get(speakerId);
        if (known != null) return known;
        String hint = "話者ID " + speakerId + " は接続できたEngineの話者一覧にありません。話者一覧を更新し、IDを確認してください。";
        if (!current.snapshot.errors.isEmpty()) {
            hint += " " + String.join(" / ", current.snapshot.errors);
        }
        throw new IOException(hint);
    }

    /** 一覧の再取得が必要な場合だけ期限付きで完了を待ち、取得失敗の原因を呼び出し元へ伝える。 */
    private static void awaitRefresh(CompletableFuture<Snapshot> pending) throws Exception {
        if (pending == null) return;
        try {
            pending.get(REQUEST_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    /** 進行中の一覧取得を取り消し、取得用スレッドとHTTPクライアントを終了する。 */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (refreshing != null) refreshing.cancel(true);
        executor.shutdownNow();
        client.shutdownNow();
    }

    /** 1つのエンジンのspeakers APIを期限・サイズ制限付きで取得し、話者一覧またはエラーを返す。 */
    private EndpointResult fetch(Endpoint endpoint) {
        CompletableFuture<HttpResponse<byte[]>> request = null;
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint.baseUrl + "speakers"))
                    .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
            request = client.sendAsync(httpRequest,
                    ignored -> new SpeechService.LimitedBodySubscriber(MAX_SPEAKERS_BYTES));
            // HttpRequest.timeout alone may not bound a stalled response body.
            HttpResponse<byte[]> response = request.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode());
            }
            List<Voice> voices = parse(response.body(), endpoint);
            return new EndpointResult(voices, null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            String detail = cause instanceof TimeoutException ? "応答がタイムアウトしました"
                    : cause instanceof IOException && cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            // Exceptions can contain arbitrarily long malformed JSON, so keep the UI message bounded.
            if (detail.length() > 160) detail = detail.substring(0, 160);
            String hint = endpoint.name.equals("VOICEVOX Nemo")
                    ? "。Nemo Engineを起動し、configのnemoEngineUrl（通常 http://127.0.0.1:50121）を確認してください"
                    : "。Engineの起動とconfigのengineUrlを確認してください";
            return new EndpointResult(List.of(), endpoint.name + " /speakers: " + detail + hint);
        } finally {
            if (request != null && !request.isDone()) request.cancel(true);
        }
    }

    /** speakers応答の名前とIDを検証し、読み上げ用スタイルだけをエンジン情報付きで抽出する。 */
    private static List<Voice> parse(byte[] json, Endpoint endpoint) throws IOException {
        try {
            List<Voice> voices = new ArrayList<>();
            for (JsonElement speakerElement : JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject speaker = speakerElement.getAsJsonObject();
                String speakerName = string(speaker, "name");
                for (JsonElement styleElement : speaker.getAsJsonArray("styles")) {
                    JsonObject style = styleElement.getAsJsonObject();
                    if (style.has("type") && !string(style, "type").equals("talk")) continue;
                    JsonElement idElement = style.get("id");
                    if (idElement == null || !idElement.isJsonPrimitive()
                            || !idElement.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid style ID");
                    int id = idElement.getAsBigDecimal().intValueExact();
                    if (id < 0) throw new IllegalArgumentException("Invalid style ID");
                    voices.add(new Voice(id, speakerName, string(style, "name"), endpoint.name, endpoint.uri));
                }
            }
            return List.copyOf(voices);
        } catch (RuntimeException e) {
            throw new IOException("話者一覧のJSON形式が不正です", e);
        }
    }

    /** 話者情報の必須文字列項目を取得し、欠落または型の不一致をエラーにする。 */
    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Invalid speaker field: " + key);
        }
        return value.getAsString();
    }

    /** 通常版を優先して重複IDを統合し、話者一覧・取得エラー・接続先対応表をまとめて更新する。 */
    private synchronized Snapshot publish(List<EndpointResult> results) {
        if (closed) throw new CancellationException("Speaker catalog is closed");
        Map<Integer, Voice> byId = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (EndpointResult result : results) {
            if (result.error != null) errors.add(result.error);
            // Configured primary engine wins duplicate style IDs, independent of request completion order.
            for (Voice voice : result.voices) byId.putIfAbsent(voice.id, voice);
        }
        Map<Integer, URI> routes = new LinkedHashMap<>();
        byId.forEach((id, voice) -> routes.put(id, voice.engineUri));
        Snapshot updated = new Snapshot(new ArrayList<>(byId.values()), errors);
        // A failed endpoint loses its old entries: never route a newly selected ID using stale data.
        state = new State(updated, Map.copyOf(routes));
        lastRefreshNanos = System.nanoTime();
        return updated;
    }

    private record Endpoint(String name, URI uri, String baseUrl) {}
    private record EndpointResult(List<Voice> voices, String error) {}
    private record State(Snapshot snapshot, Map<Integer, URI> routes) {}
}
