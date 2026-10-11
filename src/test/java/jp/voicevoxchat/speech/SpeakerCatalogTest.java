/**
 * 通常版とNemoの話者一覧の統合、接続先の解決、ランダム選択、障害時の継続と取消を検証する。
 */
package jp.voicevoxchat.speech;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeakerCatalogTest {
    private static final String NORMAL = """
            [{"name":"ずんだもん","styles":[
              {"name":"ノーマル","id":3,"type":"talk"},
              {"name":"歌唱","id":6000,"type":"sing"},
              {"name":"旧APIスタイル","id":4}]}]
            """;
    private static final String NEMO = """
            [{"name":"女性1","styles":[{"name":"ノーマル","id":10007,"type":"talk"}]}]
            """;
    private final List<AutoCloseable> resources = new ArrayList<>();

    /** 各テストで作成した音声処理、一覧、模擬Engineを生成とは逆の順番で終了する。 */
    @AfterEach
    void closeResources() throws Exception {
        for (AutoCloseable resource : resources.reversed()) resource.close();
    }

    /** 両Engineから和名と話者IDを読み取り、歌唱スタイルを除外して適切な接続先を解決することを検証する。 */
    @Test
    void readsJapaneseNamesAndStyleIdsFromBothEnginesAndExcludesSinging() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        SpeakerCatalog catalog = catalog(normal, nemo);

        SpeakerCatalog.Snapshot snapshot = catalog.refresh().get(5, TimeUnit.SECONDS);

        assertTrue(snapshot.errors().isEmpty(), snapshot.errors().toString());
        assertEquals(List.of(3, 4, 10007), snapshot.voices().stream().map(SpeakerCatalog.Voice::id).toList());
        assertEquals("ずんだもん", snapshot.voices().getFirst().speakerName());
        SpeakerCatalog.Voice nemoVoice = snapshot.voices().getLast();
        assertEquals("女性1", nemoVoice.speakerName());
        assertEquals("ノーマル", nemoVoice.styleName());
        assertEquals("VOICEVOX Nemo", nemoVoice.engineName());
        assertEquals(normal.uri, catalog.resolve(3));
        assertEquals(nemo.uri, catalog.resolve(10007));
        assertThrows(IOException.class, () -> catalog.resolve(6000));
        assertEquals(1, normal.catalogRequests.get());
        assertEquals(1, nemo.catalogRequests.get());
    }

    /** 話者一覧を一度取得して再利用し、各トークスタイルを等確率の選択対象にすることを検証する。 */
    @Test
    void randomSelectionDiscoversOnceAndEveryTalkStyleHasOneUniformIndex() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        SpeakerCatalog catalog = catalog(normal, nemo);
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int index = i;
            selected.add(catalog.randomSpeakerId(bound -> {
                assertEquals(3, bound, "Select across talk styles from both engines");
                return index;
            }));
        }

        assertEquals(List.of(3, 4, 10007), selected);
        assertTrue(selected.contains(catalog.randomSpeakerId()));
        assertEquals(1, normal.catalogRequests.get(), "Cached selection must not refetch speakers");
        assertEquals(1, nemo.catalogRequests.get());
    }

    /** 一部Engineの障害後は現在取得できた話者だけをランダム選択に使用することを検証する。 */
    @Test
    void randomSelectionUsesCurrentPartialListAndDoesNotRetainUnavailableVoices() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        SpeakerCatalog catalog = catalog(normal, nemo);
        catalog.refresh().get(5, TimeUnit.SECONDS);
        normal.catalogStatus = 503;
        catalog.refresh().get(5, TimeUnit.SECONDS);

        assertEquals(10007, catalog.randomSpeakerId(bound -> {
            assertEquals(1, bound);
            return 0;
        }));
        assertEquals(nemo.uri, catalog.resolve(10007));
        assertEquals(2, normal.catalogRequests.get());
        assertEquals(2, nemo.catalogRequests.get());
    }

    /** 話者が一人も取得できなければ代替IDを選ばず、接続失敗を報告して再要求を抑制することを検証する。 */
    @Test
    void randomSelectionWithNoVoicesReportsConnectionFailureWithoutChoosingFallback() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        normal.catalogStatus = 503;
        nemo.catalogStatus = 500;
        SpeakerCatalog catalog = catalog(normal, nemo);
        AtomicInteger choices = new AtomicInteger();

        IOException first = assertThrows(IOException.class,
                () -> catalog.randomSpeakerId(bound -> choices.incrementAndGet()));
        assertTrue(first.getMessage().contains("ランダムに選択できる話者がありません"));
        assertTrue(first.getMessage().contains("503"));
        assertTrue(first.getMessage().contains("nemoEngineUrl"));
        assertThrows(IOException.class, catalog::randomSpeakerId);
        assertEquals(0, choices.get());
        assertEquals(1, normal.catalogRequests.get(), "Negative caching bounds repeated failed lookups");
        assertEquals(1, nemo.catalogRequests.get());
    }

    /** 一方のEngineが停止しても他方を利用でき、古い話者の接続先を破棄することを検証する。 */
    @Test
    void endpointFailureKeepsOtherEngineUsableAndDiscardsStaleRoutes() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        SpeakerCatalog catalog = catalog(normal, nemo);
        catalog.refresh().get(5, TimeUnit.SECONDS);
        assertEquals(nemo.uri, catalog.resolve(10007));
        nemo.catalogStatus = 503;

        SpeakerCatalog.Snapshot snapshot = catalog.refresh().get(5, TimeUnit.SECONDS);

        assertEquals(List.of(3, 4), snapshot.voices().stream().map(SpeakerCatalog.Voice::id).toList());
        assertEquals(normal.uri, catalog.resolve(3));
        IOException failure = assertThrows(IOException.class, () -> catalog.resolve(10007));
        assertTrue(failure.getMessage().contains("10007"));
        assertTrue(failure.getMessage().contains("503"));
        assertTrue(failure.getMessage().contains("nemoEngineUrl"));
        assertTrue(failure.getMessage().contains("50121"));
        nemo.catalogStatus = 200;
        catalog.refresh().get(5, TimeUnit.SECONDS);
        assertEquals(nemo.uri, catalog.resolve(10007));
    }

    /** Engine起動後の更新が先行要求の完了後に再取得し、起動前の取得失敗を置き換えることを検証する。 */
    @Test
    void engineStartupRefreshWaitsForOlderRequestAndReplacesItsUnavailableResult() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        CountDownLatch oldRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseOldRequest = new CountDownLatch(1);
        nemo.customCatalog = exchange -> {
            if (nemo.catalogRequests.get() == 1) {
                // This request observed the engine before startup completed.
                oldRequestStarted.countDown();
                try {
                    if (!releaseOldRequest.await(5, TimeUnit.SECONDS)) throw new IOException("Test request was not released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                MockEngine.respond(exchange, 503, "Engine was starting");
            } else {
                MockEngine.respond(exchange, 200, NEMO);
            }
        };
        SpeakerCatalog catalog = catalog(normal, nemo);
        var oldRefresh = catalog.refresh();
        try {
            assertTrue(oldRequestStarted.await(5, TimeUnit.SECONDS));
            var afterStartup = catalog.refreshAfterCurrent();
            assertFalse(afterStartup.isDone());
            assertEquals(1, nemo.catalogRequests.get());
            releaseOldRequest.countDown();

            var oldResult = oldRefresh.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(3, 4), oldResult.voices().stream().map(SpeakerCatalog.Voice::id).toList());
            assertEquals(1, oldResult.errors().size());
            var freshResult = afterStartup.get(5, TimeUnit.SECONDS);
            assertTrue(freshResult.errors().isEmpty(), freshResult.errors().toString());
            assertEquals(List.of(3, 4, 10007), freshResult.voices().stream().map(SpeakerCatalog.Voice::id).toList());
            assertEquals(nemo.uri, catalog.resolve(10007));
            assertEquals(2, normal.catalogRequests.get());
            assertEquals(2, nemo.catalogRequests.get());
        } finally {
            releaseOldRequest.countDown();
        }
    }

    /** 更新を待つ呼出元の取消が共有取得処理やその後の再取得を中断しないことを検証する。 */
    @Test
    void cancellingStartupRefreshCallerDoesNotCancelSharedRequestOrItsFollowUp() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        CountDownLatch oldRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseOldRequest = new CountDownLatch(1);
        CountDownLatch newRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseNewRequest = new CountDownLatch(1);
        nemo.customCatalog = exchange -> {
            boolean firstRequest = nemo.catalogRequests.get() == 1;
            (firstRequest ? oldRequestStarted : newRequestStarted).countDown();
            try {
                if (!(firstRequest ? releaseOldRequest : releaseNewRequest).await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Test request was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            MockEngine.respond(exchange, 200, firstRequest ? "[]" : NEMO);
        };
        SpeakerCatalog catalog = catalog(normal, nemo);
        var oldRefresh = catalog.refresh();
        try {
            assertTrue(oldRequestStarted.await(5, TimeUnit.SECONDS));
            var cancelledCaller = catalog.refreshAfterCurrent();
            assertTrue(cancelledCaller.cancel(true));
            releaseOldRequest.countDown();
            assertTrue(oldRefresh.get(5, TimeUnit.SECONDS).errors().isEmpty());
            assertTrue(newRequestStarted.await(5, TimeUnit.SECONDS));
            var sharedFollowUp = catalog.refresh();
            releaseNewRequest.countDown();
            assertTrue(sharedFollowUp.get(5, TimeUnit.SECONDS).errors().isEmpty());
            assertEquals(nemo.uri, catalog.resolve(10007));
            assertEquals(2, nemo.catalogRequests.get());
        } finally {
            releaseOldRequest.countDown();
            releaseNewRequest.countDown();
        }
    }

    /** 通常版Engineが利用不可でもNemoの話者を解決できることを検証する。 */
    @Test
    void nemoCanWorkWhenPrimaryEngineIsUnavailable() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        normal.catalogStatus = 500;
        SpeakerCatalog catalog = catalog(normal, nemo);

        assertEquals(nemo.uri, catalog.resolve(10007));
        assertEquals(1, catalog.snapshot().errors().size());
        assertEquals(List.of(10007), catalog.snapshot().voices().stream().map(SpeakerCatalog.Voice::id).toList());
    }

    /** 重複IDでは通常版を優先し、同じ接続先への取得を重複させないことを検証する。 */
    @Test
    void duplicateIdsPreferConfiguredPrimaryAndIdenticalEndpointsAreFetchedOnce() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NORMAL);
        SpeakerCatalog catalog = catalog(normal, nemo);
        catalog.refresh().get(5, TimeUnit.SECONDS);
        assertEquals(normal.uri, catalog.resolve(3));
        assertEquals(2, catalog.snapshot().voices().size());

        SpeakerCatalog identical = new SpeakerCatalog(normal.uri, URI.create(normal.uri + "/"));
        resources.add(identical);
        identical.refresh().get(5, TimeUnit.SECONDS);
        assertEquals(2, normal.catalogRequests.get());
        assertEquals(2, identical.snapshot().voices().size());
    }

    /** 未知の話者IDでは合成要求を送らず、短時間の同じ失敗では一覧の再取得を抑えることを検証する。 */
    @Test
    void unknownIdNeverReachesSynthesisAndRepeatedFailuresUseNegativeCache() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        SpeakerCatalog catalog = catalog(normal, nemo);
        List<String> errors = new CopyOnWriteArrayList<>();
        List<Integer> played = new CopyOnWriteArrayList<>();
        SpeechService speech = new SpeechService(catalog::resolve, errors::add, playback(played));
        resources.add(speech);

        speech.enqueue("存在しない話者", 90000);
        speech.enqueue("同じ設定ミス", 90000);
        speech.enqueue("通常の話者", 3);
        await(() -> speech.pendingCount() == 0);

        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(error -> error.contains("90000") && !error.contains("HTTP 500")));
        assertEquals(List.of(3), played);
        assertEquals(List.of("/audio_query:3", "/synthesis:3"), normal.speechRequests);
        assertTrue(nemo.speechRequests.isEmpty());
        assertEquals(1, normal.catalogRequests.get());
        assertEquals(1, nemo.catalogRequests.get());
    }

    /** Engineが交互に切り替わっても、合成と再生を共通キューで受信順に処理することを検証する。 */
    @Test
    void playbackRemainsOneGlobalFifoWhenMessagesSwitchEngines() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        SpeakerCatalog catalog = catalog(normal, nemo);
        List<String> errors = new CopyOnWriteArrayList<>();
        List<Integer> played = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        SpeechService speech = new SpeechService(id -> {
            resolutions.incrementAndGet();
            return catalog.resolve(id);
        }, errors::add, new SpeechService.AudioPlayback() {
            /** 再生したIDを記録し、最初の再生のみラッチで止めて後続処理の順序を確認する。 */
            @Override
            public void play(byte[] wav, BooleanSupplier cancelled) throws InterruptedException {
                int id = Integer.parseInt(new String(wav, StandardCharsets.UTF_8));
                played.add(id);
                if (played.size() == 1) {
                    firstStarted.countDown();
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                }
            }

            /** 最初の再生を待機させているラッチを解除する。 */
            @Override
            public void stop() { releaseFirst.countDown(); }
        });
        resources.add(speech);

        speech.enqueue("ずんだもん", 3);
        speech.enqueue("Nemo", 10007);
        speech.enqueue("ずんだもんに戻る", 3);
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        assertTrue(nemo.speechRequests.isEmpty(), "Nemo synthesis must wait until previous playback finishes");
        assertEquals(1, resolutions.get());
        releaseFirst.countDown();
        await(() -> speech.pendingCount() == 0);

        assertEquals(List.of(3, 10007, 3), played);
        assertEquals(List.of("/audio_query:10007", "/synthesis:10007"), nemo.speechRequests);
        assertEquals(3, resolutions.get(), "Resolve only once per utterance");
        assertTrue(errors.isEmpty(), errors.toString());
    }

    /** 一方の一覧応答が途切れても期限内に終了し、他方の取得結果を保持することを検証する。 */
    @Test
    void stalledCatalogBodyTimesOutWithoutLosingOtherEngineResults() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        CountDownLatch release = new CountDownLatch(1);
        nemo.customCatalog = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('[');
            exchange.getResponseBody().flush();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        SpeakerCatalog catalog = catalog(normal, nemo);
        try {
            SpeakerCatalog.Snapshot result = assertTimeout(Duration.ofSeconds(5),
                    () -> catalog.refresh().get(5, TimeUnit.SECONDS));
            assertEquals(2, result.voices().size());
            assertEquals(1, result.errors().size());
            assertEquals(normal.uri, catalog.resolve(3));
        } finally {
            release.countDown();
        }
    }

    /** 終了処理が一覧取得を速やかに取り消し、以降の取得・解決・選択を拒否することを検証する。 */
    @Test
    void closeCancelsActiveRefreshAndRejectsNewWorkPromptly() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        nemo.customCatalog = exchange -> {
            requestStarted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        SpeakerCatalog catalog = catalog(normal, nemo);
        var refresh = catalog.refresh();
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
        try {
            assertTimeout(Duration.ofSeconds(1), catalog::close);
            assertTrue(refresh.isCompletedExceptionally());
            assertTrue(catalog.refresh().isCompletedExceptionally());
            assertTrue(catalog.refreshAfterCurrent().isCompletedExceptionally());
            assertThrows(java.util.concurrent.CancellationException.class, () -> catalog.resolve(3));
            assertThrows(java.util.concurrent.CancellationException.class, catalog::randomSpeakerId);
        } finally {
            release.countDown();
        }
    }

    /** 過大な応答や不正な話者JSONを拒否しつつ、正常なEngineの話者は利用できることを検証する。 */
    @Test
    void oversizedAndMalformedCatalogsAreRejectedWithUsefulPartialResults() throws Exception {
        MockEngine normal = engine(NORMAL);
        MockEngine nemo = engine(NEMO);
        nemo.catalogJson = " ".repeat(2 * 1024 * 1024 + 1);
        SpeakerCatalog catalog = catalog(normal, nemo);
        SpeakerCatalog.Snapshot oversized = catalog.refresh().get(5, TimeUnit.SECONDS);
        assertEquals(2, oversized.voices().size());
        assertTrue(oversized.errors().getFirst().contains("exceeds"));

        nemo.catalogJson = "[{\"name\":\"壊れた話者\",\"styles\":[{\"name\":\"不正\",\"id\":1.2}]}]";
        SpeakerCatalog.Snapshot malformed = catalog.refresh().get(5, TimeUnit.SECONDS);
        assertEquals(2, malformed.voices().size());
        assertTrue(malformed.errors().getFirst().contains("JSON"));
    }

    /** 指定した話者一覧を返す模擬Engineを起動し、解放対象へ登録する。 */
    private MockEngine engine(String catalogJson) throws IOException {
        MockEngine engine = new MockEngine(catalogJson);
        resources.add(engine);
        return engine;
    }

    /** 二つの模擬Engineを参照する話者一覧を作成し、解放対象へ登録する。 */
    private SpeakerCatalog catalog(MockEngine normal, MockEngine nemo) {
        SpeakerCatalog catalog = new SpeakerCatalog(normal.uri, nemo.uri);
        resources.add(catalog);
        return catalog;
    }

    /** 再生対象となった話者IDだけを記録する模擬再生処理を作成する。 */
    private static SpeechService.AudioPlayback playback(List<Integer> played) {
        return new SpeechService.AudioPlayback() {
            /** 取り消されていない音声から話者IDを読み取り、再生履歴へ追加する。 */
            @Override
            public void play(byte[] wav, BooleanSupplier cancelled) {
                if (!cancelled.getAsBoolean()) played.add(Integer.parseInt(new String(wav, StandardCharsets.UTF_8)));
            }

            /** 即時に完了する模擬再生のため、停止時の処理は行わない。 */
            @Override
            public void stop() {}
        };
    }

    /** 条件成立を最大5秒待ち、期限までに成立しなければテストを失敗させる。 */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition not satisfied within 5 seconds");
    }

    private static final class MockEngine implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final URI uri;
        private final AtomicInteger catalogRequests = new AtomicInteger();
        private final List<String> speechRequests = new CopyOnWriteArrayList<>();
        private volatile String catalogJson;
        private volatile int catalogStatus = 200;
        private volatile HttpHandler customCatalog;

        /** 話者一覧と簡易な音声合成APIを提供し、要求履歴を記録するHTTPサーバーを起動する。 */
        private MockEngine(String catalogJson) throws IOException {
            this.catalogJson = catalogJson;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath();
                    if (path.equals("/speakers")) {
                        catalogRequests.incrementAndGet();
                        if (customCatalog != null) {
                            customCatalog.handle(exchange);
                        } else {
                            respond(exchange, catalogStatus, this.catalogJson);
                        }
                    } else {
                        String speaker = java.util.Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                                .filter(part -> part.startsWith("speaker=")).findFirst().orElseThrow().substring(8);
                        speechRequests.add(path + ":" + speaker);
                        respond(exchange, 200, path.equals("/audio_query") ? "{}" : speaker);
                    }
                } catch (IOException ignored) {
                    // Cancellation and the oversized-response check intentionally close streams early.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        /** 指定ステータスとUTF-8本文をHTTP応答として返す。 */
        private static void respond(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        /** 模擬Engineとその要求処理用スレッドを終了する。 */
        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
