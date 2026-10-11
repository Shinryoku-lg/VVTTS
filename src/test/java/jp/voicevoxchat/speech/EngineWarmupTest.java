/**
 * 話者モデルの事前初期化、重複排除、Engine再起動時の再準備と非同期処理の取消を検証する。
 */
package jp.voicevoxchat.speech;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EngineWarmupTest {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    /** 各テストで登録した準備処理と模擬Engineを、生成とは逆の順番で終了する。 */
    @AfterEach
    void closeResources() throws Exception {
        for (AutoCloseable resource : resources.reversed()) resource.close();
    }

    /** 指定された話者だけを対応するEngineで一度ずつ初期化し、合成や再生を行わないことを検証する。 */
    @Test
    void initializesOnlyRequestedStylesOnTheirOwnEnginesWithoutSynthesizingOrPlaying() throws Exception {
        MockEngine normal = engine();
        MockEngine nemo = engine();
        EngineWarmup warmup = warmup(Duration.ofSeconds(3));
        var normalVoice = voice(normal, 4);
        var nemoVoice = voice(nemo, 10007);

        warmup.warm(List.of(normalVoice, nemoVoice, normalVoice)).get(5, TimeUnit.SECONDS);
        warmup.warm(List.of(normalVoice, nemoVoice)).get(5, TimeUnit.SECONDS);

        assertEquals(List.of("POST /initialize_speaker?speaker=4&skip_reinit=true"), normal.requests);
        assertEquals(List.of("POST /initialize_speaker?speaker=10007&skip_reinit=true"), nemo.requests);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** 同一話者の準備を共有しても、一方の呼出元による取消が他方へ影響しないことを検証する。 */
    @Test
    void sharesPendingPreparationWithoutLettingOneCallerCancelAnother() throws Exception {
        MockEngine normal = engine();
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        normal.handler = exchange -> {
            received.countDown();
            await(release);
            respond(exchange, 204);
        };
        EngineWarmup warmup = warmup(Duration.ofSeconds(3));
        var callerOne = warmup.warm(List.of(voice(normal, 3)));
        try {
            assertTrue(received.await(3, TimeUnit.SECONDS));
            var callerTwo = warmup.warm(List.of(voice(normal, 3)));
            assertTrue(callerOne.cancel(true));
            assertFalse(callerTwo.isDone());
            release.countDown();

            callerTwo.get(3, TimeUnit.SECONDS);
            assertEquals(1, normal.requests.size());
            assertTrue(warnings.isEmpty(), warnings.toString());
        } finally {
            release.countDown();
        }
    }

    /** 再起動したEngineのみ準備状態を破棄し、他方のEngineの初期化結果は保持することを検証する。 */
    @Test
    void restartingOneEngineForgetsItsModelsAndPreservesOtherEnginePreparation() throws Exception {
        MockEngine normal = engine();
        MockEngine nemo = engine();
        EngineWarmup warmup = warmup(Duration.ofSeconds(3));
        var voices = List.of(voice(normal, 3), voice(nemo, 10007));
        warmup.warm(voices).get(5, TimeUnit.SECONDS);

        warmup.reset(URI.create(normal.uri + "/"));
        warmup.warm(voices).get(5, TimeUnit.SECONDS);

        assertEquals(2, normal.requests.size());
        assertEquals(1, nemo.requests.size());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** 初期化APIが未対応でも準備完了として扱い、警告を記録して後で再試行できることを検証する。 */
    @Test
    void unsupportedInitializationDoesNotFailStartupAndCanBeRetriedLater() throws Exception {
        MockEngine normal = engine();
        normal.handler = exchange -> respond(exchange, 404);
        EngineWarmup warmup = warmup(Duration.ofSeconds(3));

        warmup.warm(List.of(voice(normal, 3))).get(5, TimeUnit.SECONDS);
        // Completion is deliberately successful so the caller still permits normal synthesis.
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("HTTP 404"));
        assertTrue(warnings.getFirst().contains("話者ID 3"));
        normal.handler = exchange -> respond(exchange, 204);
        warmup.warm(List.of(voice(normal, 3))).get(5, TimeUnit.SECONDS);
        assertEquals(2, normal.requests.size());
    }

    /** 初期化応答が途切れた場合にタイムアウトし、後続の準備を妨げないことを検証する。 */
    @Test
    void stalledInitializationBodyTimesOutWithoutBlockingSubsequentPreparation() throws Exception {
        MockEngine normal = engine();
        CountDownLatch release = new CountDownLatch(1);
        normal.handler = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(' ');
            exchange.getResponseBody().flush();
            await(release);
        };
        EngineWarmup warmup = warmup(Duration.ofMillis(400));
        try {
            warmup.warm(List.of(voice(normal, 3))).get(3, TimeUnit.SECONDS);
            assertEquals(1, warnings.size());
            normal.handler = exchange -> respond(exchange, 204);
            warmup.warm(List.of(voice(normal, 3))).get(3, TimeUnit.SECONDS);
            assertEquals(2, normal.requests.size());
        } finally {
            release.countDown();
        }
    }

    /** 初期化の同時実行数を制限し、終了処理で実行中と待機中の処理を取り消すことを検証する。 */
    @Test
    void boundsInitializationConcurrencyAndCloseCancelsBothActiveAndQueuedWork() throws Exception {
        MockEngine normal = engine();
        CountDownLatch received = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        normal.handler = exchange -> {
            int active = concurrent.incrementAndGet();
            maximum.accumulateAndGet(active, Math::max);
            received.countDown();
            try {
                await(release);
                respond(exchange, 204);
            } finally {
                concurrent.decrementAndGet();
            }
        };
        EngineWarmup warmup = warmup(Duration.ofSeconds(3));
        var pending = warmup.warm(List.of(voice(normal, 3), voice(normal, 4), voice(normal, 5)));
        try {
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertEquals(2, normal.requests.size());
            assertEquals(2, maximum.get());
            assertTimeout(Duration.ofSeconds(1), warmup::close);
            assertTrue(pending.isCompletedExceptionally());
            assertTrue(warmup.warm(List.of(voice(normal, 6))).isCompletedExceptionally());
            assertTrue(warnings.isEmpty(), warnings.toString());
        } finally {
            release.countDown();
        }
    }

    /** 準備状態のリセットで進行中の古い要求を取り消し、新たに初期化できることを検証する。 */
    @Test
    void resetCancelsOldPendingRequestAndAllowsFreshInitialization() throws Exception {
        MockEngine normal = engine();
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        normal.handler = exchange -> {
            received.countDown();
            await(release);
            respond(exchange, 204);
        };
        EngineWarmup warmup = warmup(Duration.ofSeconds(3));
        var oldRequest = warmup.warm(List.of(voice(normal, 3)));
        try {
            assertTrue(received.await(3, TimeUnit.SECONDS));
            warmup.reset(normal.uri);
            assertTrue(oldRequest.isCompletedExceptionally());
            normal.handler = exchange -> respond(exchange, 204);
            warmup.warm(List.of(voice(normal, 3))).get(3, TimeUnit.SECONDS);
            assertEquals(2, normal.requests.size());
            assertTrue(warnings.isEmpty(), warnings.toString());
        } finally {
            release.countDown();
        }
    }

    /** 模擬Engineを起動し、テスト終了時の解放対象へ登録する。 */
    private MockEngine engine() throws IOException {
        MockEngine engine = new MockEngine();
        resources.add(engine);
        return engine;
    }

    /** 警告の収集先と要求タイムアウトを指定し、解放対象の準備処理を作成する。 */
    private EngineWarmup warmup(Duration timeout) {
        EngineWarmup warmup = new EngineWarmup(warnings::add, timeout);
        resources.add(warmup);
        return warmup;
    }

    /** 指定した模擬Engineと話者IDに対応する一覧項目を作成する。 */
    private static SpeakerCatalog.Voice voice(MockEngine engine, int id) {
        return new SpeakerCatalog.Voice(id, "話者", "ノーマル", "VOICEVOX", engine.uri);
    }

    /** テスト用ラッチの解除を最大5秒待ち、期限切れや割り込みを通信失敗として通知する。 */
    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Test request was not released");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }

    /** 指定ステータスで本文のないHTTP応答を返す。 */
    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static final class MockEngine implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final URI uri;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private volatile HttpHandler handler = exchange -> respond(exchange, 204);

        /** 要求内容を記録して差し替え可能な応答処理へ渡すHTTPサーバーを起動する。 */
        private MockEngine() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                try {
                    handler.handle(exchange);
                } catch (IOException ignored) {
                    // Reset, timeout and shutdown intentionally abandon a request before it returns.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        /** 模擬Engineとその要求処理用スレッドを終了する。 */
        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
