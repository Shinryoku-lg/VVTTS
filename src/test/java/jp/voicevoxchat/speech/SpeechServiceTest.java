/**
 * HTTP音声合成と逐次再生キューについて、要求内容、話者選択、取消、障害からの継続を検証する。
 */
package jp.voicevoxchat.speech;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeechServiceTest {
    private static final byte[] QUERY = "{\"speedScale\":1.0,\"accent_phrases\":[]}".getBytes(StandardCharsets.UTF_8);
    private final List<SpeechService> services = new ArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<Throwable> serverFailures = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private volatile HttpHandler handler;
    private URI uri;

    /** 差し替え可能な応答処理とサーバー内エラーの記録を備えた模擬HTTPサーバーを起動する。 */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        server.setExecutor(serverExecutor);
        handler = this::normalResponse;
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException ignored) {
                // Cancelling an in-flight request intentionally closes its response stream.
            } catch (Throwable failure) {
                serverFailures.add(failure);
            } finally {
                exchange.close();
            }
        });
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** 音声処理と模擬サーバーを終了し、サーバー側に未処理の失敗がないことを確認する。 */
    @AfterEach
    void stopServer() {
        services.forEach(SpeechService::close);
        server.stop(0);
        serverExecutor.shutdownNow();
        assertTrue(serverFailures.isEmpty(), () -> "Mock server failures: " + serverFailures);
    }

    /** 日本語本文と指定話者のみをAPIへ送り、生成されたクエリJSONを合成APIへ渡すことを検証する。 */
    @Test
    void sendsOnlyJapaneseMessageBodyAndAssignedSpeakerAndForwardsQueryJson() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        RecordingPlayback audio = new RecordingPlayback();
        handler = exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            Map<String, String> params = queryParams(exchange);
            assertEquals("3", params.get("speaker"));
            requests.add(exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().equals("/audio_query")) {
                assertEquals("こんにちは & よろしく +！", params.get("text"));
                assertEquals(0, exchange.getRequestBody().readAllBytes().length);
                respond(exchange, 200, QUERY);
            } else {
                assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
                assertArrayEquals(QUERY, exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, new byte[]{3});
            }
        };
        SpeechService service = service(audio);
        assertTrue(service.enqueue("こんにちは & よろしく +！", 3));
        await(() -> service.pendingCount() == 0);
        assertEquals(List.of("/audio_query", "/synthesis"), requests);
        assertEquals(List.of(3), audio.played);
        assertTrue(errors.isEmpty());
    }

    /** 合成から再生終了までを一件ずつ完了させ、キューの順序を維持することを検証する。 */
    @Test
    void synthesizesAndPlaysOneMessageAtATimeInFifoOrder() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstPlayback = new CountDownLatch(1);
        CountDownLatch releasePlayback = new CountDownLatch(1);
        handler = exchange -> {
            int speaker = Integer.parseInt(queryParams(exchange).get("speaker"));
            events.add(exchange.getRequestURI().getPath() + ":" + speaker);
            normalResponse(exchange);
        };
        SpeechService service = service(new SpeechService.AudioPlayback() {
            /** 再生開始・終了を記録し、最初の音声を待機させて処理順序を確認する。 */
            @Override
            public void play(byte[] wav, BooleanSupplier cancelled) throws InterruptedException {
                events.add("play:" + wav[0]);
                if (wav[0] == 3) {
                    firstPlayback.countDown();
                    assertTrue(releasePlayback.await(5, TimeUnit.SECONDS));
                }
                events.add("end:" + wav[0]);
            }

            /** 再生順序の確認用に待機している処理を解除する。 */
            @Override
            public void stop() {
                releasePlayback.countDown();
            }
        });
        assertTrue(service.enqueue("最初", 3));
        assertTrue(service.enqueue("次", 8));
        assertTrue(firstPlayback.await(5, TimeUnit.SECONDS));
        assertEquals(2, service.pendingCount());
        assertEquals(List.of("/audio_query:3", "/synthesis:3", "play:3"), events);
        releasePlayback.countDown();
        await(() -> service.pendingCount() == 0);
        assertEquals(List.of("/audio_query:3", "/synthesis:3", "play:3", "end:3",
                "/audio_query:8", "/synthesis:8", "play:8", "end:8"), events);
    }

    /** HTTP要求または再生が失敗しても、エラーを記録して次のメッセージを処理することを検証する。 */
    @Test
    void recoversAfterHttpFailureAndPlaybackFailure() throws Exception {
        List<Integer> played = new CopyOnWriteArrayList<>();
        handler = exchange -> {
            if (queryParams(exchange).get("speaker").equals("1")) {
                respond(exchange, 500, "error".getBytes(StandardCharsets.UTF_8));
            } else {
                normalResponse(exchange);
            }
        };
        SpeechService service = service(new SpeechService.AudioPlayback() {
            /** 話者ID 2で再生障害を模擬し、それ以外の再生履歴を記録する。 */
            @Override
            public void play(byte[] wav, BooleanSupplier cancelled) throws IOException {
                if (wav[0] == 2) {
                    throw new IOException("Sound device unavailable");
                }
                played.add((int) wav[0]);
            }

            /** 待機しない障害再現用の再生処理なので、停止処理は行わない。 */
            @Override
            public void stop() {
            }
        });
        service.enqueue("HTTP error", 1);
        service.enqueue("Playback error", 2);
        service.enqueue("最後まで続く", 3);
        await(() -> service.pendingCount() == 0);
        assertEquals(List.of(3), played);
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).contains("500"));
        assertTrue(errors.get(1).contains("Sound device unavailable"));
    }

    /** HTTPエラーが対象APIと話者を示し、チャット本文をエラー文へ露出させないことを検証する。 */
    @Test
    void httpErrorsIdentifyEndpointAndStyleWithoutEchoingChatText() throws Exception {
        JsonObject detail = new JsonObject();
        detail.addProperty("errorName", "invalidStyleId");
        detail.addProperty("message", "秘密のチャット");
        JsonObject error = new JsonObject();
        error.addProperty("detail", detail.toString());
        handler = exchange -> respond(exchange, 500, error.toString().getBytes(StandardCharsets.UTF_8));
        SpeechService service = service(new RecordingPlayback());
        service.enqueue("秘密のチャット", 10007);
        await(() -> service.pendingCount() == 0);
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("/audio_query"));
        assertTrue(errors.getFirst().contains("10007"));
        assertTrue(errors.getFirst().contains("500"));
        assertTrue(errors.getFirst().contains("invalidStyleId"));
        assertFalse(errors.getFirst().contains("秘密のチャット"));
    }

    /** キューのクリアでEngine解決待ちを中断し、次のメッセージを速やかに再生できることを検証する。 */
    @Test
    void clearInterruptsEngineDiscoveryWaitAndNextMessageCanPlayImmediately() throws Exception {
        CountDownLatch resolutionStarted = new CountDownLatch(1);
        CountDownLatch resolutionInterrupted = new CountDownLatch(1);
        RecordingPlayback audio = new RecordingPlayback();
        SpeechService service = new SpeechService(id -> {
            if (id == 1) {
                resolutionStarted.countDown();
                try {
                    new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    resolutionInterrupted.countDown();
                    throw e;
                }
            }
            return uri;
        }, errors::add, audio);
        services.add(service);
        assertTrue(service.enqueue("待機中", 1));
        assertTrue(resolutionStarted.await(5, TimeUnit.SECONDS));

        service.clear();
        assertTrue(resolutionInterrupted.await(1, TimeUnit.SECONDS));
        assertTrue(service.enqueue("次のチャット", 3));
        await(() -> service.pendingCount() == 0);

        assertEquals(List.of(3), audio.played);
        assertTrue(errors.isEmpty());
    }

    /** 順番が来たメッセージの話者をワーカースレッドで一度だけ選択することを検証する。 */
    @Test
    void selectsSpeakerOnlyOnceOnWorkerWhenItsQueuedMessageStarts() throws Exception {
        CountDownLatch firstPlayback = new CountDownLatch(1);
        CountDownLatch releasePlayback = new CountDownLatch(1);
        AtomicInteger selections = new AtomicInteger();
        AtomicReference<Thread> selectionThread = new AtomicReference<>();
        List<Integer> resolved = new CopyOnWriteArrayList<>();
        List<String> requests = new CopyOnWriteArrayList<>();
        List<Integer> played = new CopyOnWriteArrayList<>();
        handler = exchange -> {
            requests.add(exchange.getRequestURI().getPath() + ":" + queryParams(exchange).get("speaker"));
            normalResponse(exchange);
        };
        SpeechService service = new SpeechService(id -> {
            resolved.add(id);
            return uri;
        }, errors::add, new SpeechService.AudioPlayback() {
            /** 再生話者を記録し、先行する話者ID 3の音声を待機させて選択タイミングを確認する。 */
            @Override
            public void play(byte[] wav, BooleanSupplier cancelled) throws InterruptedException {
                played.add((int) wav[0]);
                if (wav[0] == 3) {
                    firstPlayback.countDown();
                    assertTrue(releasePlayback.await(5, TimeUnit.SECONDS));
                }
            }

            /** 話者選択の検証で待機中の先行再生を解除する。 */
            @Override
            public void stop() { releasePlayback.countDown(); }
        });
        services.add(service);
        service.enqueue("先に再生", 3);
        assertTrue(firstPlayback.await(5, TimeUnit.SECONDS));
        try {
            assertTrue(service.enqueue("話者を選択", () -> {
                selectionThread.set(Thread.currentThread());
                return 6 + selections.incrementAndGet();
            }));
            assertEquals(0, selections.get(), "Selection must wait behind the preceding playback");
        } finally {
            releasePlayback.countDown();
        }
        await(() -> service.pendingCount() == 0);

        assertEquals(1, selections.get());
        assertNotSame(Thread.currentThread(), selectionThread.get());
        assertEquals(List.of(3, 7), resolved);
        assertEquals(List.of(3, 7), played);
        assertEquals(List.of("/audio_query:3", "/synthesis:3", "/audio_query:7", "/synthesis:7"), requests);
        assertTrue(errors.isEmpty());
    }

    /** クリアが話者選択待ちを中断し、待機中メッセージの選択処理を実行せず破棄することを検証する。 */
    @Test
    void clearInterruptsSpeakerSelectionAndDropsQueuedSelectionWithoutResolvingIt() throws Exception {
        CountDownLatch selectionStarted = new CountDownLatch(1);
        CountDownLatch selectionInterrupted = new CountDownLatch(1);
        AtomicInteger droppedSelections = new AtomicInteger();
        List<Integer> resolved = new CopyOnWriteArrayList<>();
        RecordingPlayback audio = new RecordingPlayback();
        SpeechService service = new SpeechService(id -> {
            resolved.add(id);
            return uri;
        }, errors::add, audio);
        services.add(service);
        assertTrue(service.enqueue("話者一覧の取得中", () -> {
            selectionStarted.countDown();
            try {
                new CountDownLatch(1).await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                selectionInterrupted.countDown();
                throw e;
            }
            return 1;
        }));
        assertTrue(selectionStarted.await(5, TimeUnit.SECONDS));
        assertTrue(service.enqueue("破棄される選択", droppedSelections::incrementAndGet));

        service.clear();
        assertTrue(selectionInterrupted.await(1, TimeUnit.SECONDS));
        assertTrue(service.enqueue("次のチャット", 3));
        await(() -> service.pendingCount() == 0);

        assertEquals(0, droppedSelections.get());
        assertEquals(List.of(3), resolved);
        assertEquals(List.of(3), audio.played);
        assertTrue(errors.isEmpty());
    }

    /** 話者選択失敗や無効なIDではEngineへ要求せず、後続メッセージを再生できることを検証する。 */
    @Test
    void failedOrInvalidSelectionCannotReachEngineAndNextMessageStillPlays() throws Exception {
        List<Integer> resolved = new CopyOnWriteArrayList<>();
        RecordingPlayback audio = new RecordingPlayback();
        SpeechService service = new SpeechService(id -> {
            resolved.add(id);
            return uri;
        }, errors::add, audio);
        services.add(service);
        assertFalse(service.enqueue("無効な入力", (SpeechService.SpeakerSelection) null));
        assertTrue(service.enqueue("選択に失敗", () -> { throw new IOException("話者一覧を取得できません"); }));
        assertTrue(service.enqueue("無効な話者", () -> -1));
        assertTrue(service.enqueue("回復", () -> 3));
        await(() -> service.pendingCount() == 0);

        assertEquals(List.of(3), resolved);
        assertEquals(List.of(3), audio.played);
        assertEquals(2, errors.size());
        assertTrue(errors.getFirst().contains("話者一覧"));
        assertTrue(errors.getLast().contains("-1"));
    }

    /** クリアで再生中の音声と待機列を取り消し、その後の新しいメッセージは受け付けることを検証する。 */
    @Test
    void clearStopsActivePlaybackDropsQueueAndAllowsNewMessages() throws Exception {
        CountDownLatch firstPlayback = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        List<Integer> played = new CopyOnWriteArrayList<>();
        SpeechService service = service(new SpeechService.AudioPlayback() {
            /** 最初の再生を停止指示まで待機させ、取消フラグが立っていることを確認する。 */
            @Override
            public void play(byte[] wav, BooleanSupplier cancelled) throws InterruptedException {
                played.add((int) wav[0]);
                if (wav[0] == 1) {
                    firstPlayback.countDown();
                    assertTrue(stopped.await(5, TimeUnit.SECONDS));
                    assertTrue(cancelled.getAsBoolean());
                }
            }

            /** クリア操作からの停止指示をラッチで再生処理へ伝える。 */
            @Override
            public void stop() {
                stopped.countDown();
            }
        });
        service.enqueue("再生中", 1);
        service.enqueue("破棄", 2);
        assertTrue(firstPlayback.await(5, TimeUnit.SECONDS));
        service.clear();
        assertEquals(0, service.pendingCount());
        assertTrue(service.enqueue("新しいメッセージ", 3));
        await(() -> service.pendingCount() == 0);
        assertEquals(List.of(1, 3), played);
        assertTrue(errors.isEmpty());
    }

    /** 応答本文の受信が止まっていてもクリアで中断し、サーバーの応答を待たず次へ進めることを検証する。 */
    @Test
    void clearCancelsStalledResponseBodyAndContinuesWithoutWaitingForServer() throws Exception {
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch finishStalledResponse = new CountDownLatch(1);
        RecordingPlayback audio = new RecordingPlayback();
        handler = exchange -> {
            if (queryParams(exchange).get("speaker").equals("1")) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                responseStarted.countDown();
                try {
                    finishStalledResponse.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                normalResponse(exchange);
            }
        };
        SpeechService service = service(audio);
        service.enqueue("途中で中止", 1);
        assertTrue(responseStarted.await(5, TimeUnit.SECONDS));
        service.clear();
        assertTrue(service.enqueue("次のメッセージ", 3));
        try {
            await(() -> service.pendingCount() == 0);
            assertEquals(List.of(3), audio.played);
            assertTrue(errors.isEmpty());
        } finally {
            finishStalledResponse.countDown();
        }
    }

    /** キュー件数の上限、不正入力の拒否、終了時の即時停止と新規受付拒否を検証する。 */
    @Test
    void queueIsBoundedAndCloseRejectsFurtherMessages() throws Exception {
        CountDownLatch playbackStarted = new CountDownLatch(1);
        CountDownLatch playbackStopped = new CountDownLatch(1);
        SpeechService service = service(new SpeechService.AudioPlayback() {
            /** 再生開始を通知して停止まで待機し、キューが上限まで蓄積する状態を作る。 */
            @Override
            public void play(byte[] wav, BooleanSupplier cancelled) throws InterruptedException {
                playbackStarted.countDown();
                playbackStopped.await(5, TimeUnit.SECONDS);
            }

            /** 終了処理からの停止要求を伝え、キュー上限確認用の再生待機を解除する。 */
            @Override
            public void stop() {
                playbackStopped.countDown();
            }
        });
        assertFalse(service.enqueue(" ", 3));
        assertFalse(service.enqueue("こんにちは", -1));
        assertTrue(service.enqueue("再生中", 3));
        assertTrue(playbackStarted.await(5, TimeUnit.SECONDS));
        for (int i = 1; i < SpeechService.MAX_PENDING_MESSAGES; i++) {
            assertTrue(service.enqueue("待機 " + i, 3));
        }
        assertFalse(service.enqueue("満杯", 3));
        assertEquals(SpeechService.MAX_PENDING_MESSAGES, service.pendingCount());
        assertTimeout(Duration.ofSeconds(1), service::close);
        assertTrue(playbackStopped.await(1, TimeUnit.SECONDS));
        assertEquals(0, service.pendingCount());
        assertFalse(service.enqueue("終了後", 3));
        assertDoesNotThrow(service::close);
    }

    /** 容量上限を超えたクエリJSONを拒否し、後続メッセージの読み上げを継続することを検証する。 */
    @Test
    void oversizedAudioQueryIsRejectedAndNextMessageStillPlays() throws Exception {
        AtomicInteger queryCount = new AtomicInteger();
        handler = exchange -> {
            if (exchange.getRequestURI().getPath().equals("/audio_query")
                    && queryCount.getAndIncrement() == 0) {
                respond(exchange, 200, new byte[1024 * 1024 + 1]);
            } else {
                normalResponse(exchange);
            }
        };
        RecordingPlayback audio = new RecordingPlayback();
        SpeechService service = service(audio);
        service.enqueue("大きすぎる応答", 1);
        service.enqueue("回復", 3);
        await(() -> service.pendingCount() == 0);
        assertEquals(List.of(3), audio.played);
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("exceeds"));
    }

    /** 模擬Engineと指定した再生処理を使う音声サービスを作成し、解放対象へ登録する。 */
    private SpeechService service(SpeechService.AudioPlayback playback) {
        SpeechService service = new SpeechService(uri, errors::add, playback);
        services.add(service);
        return service;
    }

    /** クエリAPIには固定JSONを、合成APIには話者IDを含むテスト用音声データを返す。 */
    private void normalResponse(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getPath().equals("/audio_query")) {
            respond(exchange, 200, QUERY);
        } else {
            respond(exchange, 200, new byte[]{(byte) Integer.parseInt(queryParams(exchange).get("speaker"))});
        }
    }

    /** HTTP要求のクエリ文字列をUTF-8で復号し、名前と値の対応表へ変換する。 */
    private static Map<String, String> queryParams(HttpExchange exchange) {
        return Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(part -> part[0],
                        part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
    }

    /** 指定したHTTPステータスとバイト列を応答として返す。 */
    private static void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** 条件成立を最大5秒待ち、期限までに成立しなければテストを失敗させる。 */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Condition not satisfied within 5 seconds");
    }

    private static final class RecordingPlayback implements SpeechService.AudioPlayback {
        private final List<Integer> played = new CopyOnWriteArrayList<>();

        /** 取消されていなければ、受け取ったデータ内の話者IDを再生履歴へ追加する。 */
        @Override
        public void play(byte[] wav, BooleanSupplier cancelled) {
            if (!cancelled.getAsBoolean()) {
                played.add((int) wav[0]);
            }
        }

        /** 履歴記録だけで再生が即時に終わるため、停止時には何もしない。 */
        @Override
        public void stop() {
        }
    }
}
