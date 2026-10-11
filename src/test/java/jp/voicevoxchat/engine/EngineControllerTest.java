/**
 * Engineの自動起動、外部プロセスとの区別、停止・再起動・タイムアウト時の制御を検証する。
 */
package jp.voicevoxchat.engine;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static jp.voicevoxchat.engine.EngineController.State.*;
import static org.junit.jupiter.api.Assertions.*;

class EngineControllerTest {
    @TempDir Path temporary;

    /** 実行ファイル未設定でも外部Engineを検出し、停止操作でそのEngineを終了しないことを検証する。 */
    @Test
    void detectsExternalHttpEngineWithoutRequiringAnExecutableAndNeverStopsIt() throws Exception {
        HttpServer external = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        external.createContext("/version", exchange -> {
            requests.incrementAndGet();
            byte[] version = "\"0.25.0\"".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, version.length);
            try (var body = exchange.getResponseBody()) { body.write(version); }
        });
        external.start();
        URI uri = URI.create("http://127.0.0.1:" + external.getAddress().getPort() + "/");
        try (EngineController controller = new EngineController("", uri, temporary.resolve("engine.log"))) {
            controller.startConfigured().get(5, TimeUnit.SECONDS);
            assertEquals(EXTERNAL_RUNNING, controller.state());
            controller.start().get(5, TimeUnit.SECONDS);
            assertFalse(controller.isOwnedRunning());
            controller.stop().get(5, TimeUnit.SECONDS);
            assertEquals(EXTERNAL_RUNNING, controller.state());
            assertTrue(requests.get() >= 3);
        } finally {
            external.stop(0);
        }
    }

    /** バージョン応答が途中で止まっても停止処理が期限内に終わり、再確認を続行できることを検証する。 */
    @Test
    void stopTimesOutStalledVersionBodyAndControllerRemainsUsableForReload() throws Exception {
        HttpServer external = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        external.setExecutor(executor);
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        external.createContext("/version", exchange -> {
            try {
                if (requests.incrementAndGet() == 1) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('"');
                    exchange.getResponseBody().flush();
                    bodyStarted.countDown();
                    try {
                        releaseBody.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    byte[] version = "\"0.25.0\"".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, version.length);
                    exchange.getResponseBody().write(version);
                }
            } finally {
                exchange.close();
            }
        });
        external.start();
        URI uri = URI.create("http://127.0.0.1:" + external.getAddress().getPort() + "/");
        try (EngineController controller = new EngineController("", uri, temporary.resolve("engine.log"))) {
            var stopping = controller.stop();
            assertTrue(bodyStarted.await(3, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(4), () -> stopping.get(4, TimeUnit.SECONDS));
            assertEquals(STOPPED, controller.state());
            assertEquals(1, releaseBody.getCount(), "Stop must finish before the server releases its stalled body");
            controller.refresh().get(3, TimeUnit.SECONDS);
            assertEquals(EXTERNAL_RUNNING, controller.state());
            assertFalse(controller.isOwnedRunning());
        } finally {
            releaseBody.countDown();
            external.stop(0);
            executor.shutdownNow();
        }
    }

    /** 設定したパスと接続先でバックグラウンド起動し、自分で起動したプロセスのみを停止することを検証する。 */
    @Test
    void launchesWithConfiguredHostPortAndPathOnWorkerAndStopsOnlyItsOwnProcess() throws Exception {
        Fixture fixture = new Fixture(URI.create("http://127.0.0.2:50100/"));
        fixture.readyAfterLaunch.set(true);
        try (EngineController controller = fixture.controller(Duration.ofSeconds(1))) {
            controller.startConfigured().get(2, TimeUnit.SECONDS);
            assertEquals(OWNED_RUNNING, controller.state());
            assertTrue(controller.isOwnedRunning());
            assertEquals("127.0.0.2", fixture.host.get());
            assertEquals(50100, fixture.port.get());
            assertEquals(fixture.executable, fixture.launchedPath.get());
            assertEquals(temporary.resolve("engine.log"), fixture.logPath.get());
            assertEquals("voicevox-engine-control", fixture.launchThread.get());
            controller.startConfigured().get(2, TimeUnit.SECONDS);
            assertEquals(1, fixture.launchCount.get());
            assertEquals(OWNED_RUNNING, controller.state());
            controller.stop().get(2, TimeUnit.SECONDS);
            assertEquals(STOPPED, controller.state());
            assertEquals(1, fixture.process.stopCount.get());
            assertFalse(controller.isOwnedRunning());
        }
    }

    /** 実行ファイルのパスが空欄なら自動起動せず、停止状態を保つことを検証する。 */
    @Test
    void autoStartWithoutConfiguredExecutableRemainsStoppedWithoutLaunching() throws Exception {
        Fixture fixture = new Fixture(URI.create("http://127.0.0.1:50121/"));
        try (EngineController controller = fixture.controller("  ", Duration.ofSeconds(1))) {
            controller.startConfigured().get(2, TimeUnit.SECONDS);
            assertEquals(STOPPED, controller.state());
            assertEquals(0, fixture.launchCount.get());
            assertFalse(controller.isOwnedRunning());
        }
    }

    /** 設定された実行ファイルが存在しなくても、稼働中の外部Engineを利用できることを検証する。 */
    @Test
    void autoStartUsesExistingExternalEngineEvenWithInvalidConfiguredExecutable() throws Exception {
        Fixture fixture = new Fixture(URI.create("http://127.0.0.1:50121/"));
        fixture.externalReady.set(true);
        String missingExecutable = temporary.resolve("missing-run.exe").toString();
        try (EngineController controller = fixture.controller(missingExecutable, Duration.ofSeconds(1))) {
            controller.startConfigured().get(2, TimeUnit.SECONDS);
            assertEquals(EXTERNAL_RUNNING, controller.state());
            assertEquals(0, fixture.launchCount.get());
            assertFalse(controller.isOwnedRunning());
            controller.stop().get(2, TimeUnit.SECONDS);
            assertEquals(EXTERNAL_RUNNING, controller.state());
            assertEquals(0, fixture.process.stopCount.get());
        }
    }

    /** 不正な実行ファイルのパスをエラーとして報告し、起動を試みないことを検証する。 */
    @Test
    void autoStartReportsInvalidConfiguredExecutableWithoutLaunching() throws Exception {
        Fixture fixture = new Fixture(URI.create("http://127.0.0.1:50121/"));
        String missingExecutable = temporary.resolve("missing-run.exe").toString();
        try (EngineController controller = fixture.controller(missingExecutable, Duration.ofSeconds(1))) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> controller.startConfigured().get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.io.IOException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains(missingExecutable));
            assertEquals(ERROR, controller.state());
            assertEquals(0, fixture.launchCount.get());
            assertFalse(controller.isOwnedRunning());
        }
    }

    /** 停止操作が起動待ちを即座に取り消し、起動タイムアウトを待たずにプロセスを終了することを検証する。 */
    @Test
    void stopCancelsStartupWithoutWaitingForStartupTimeout() throws Exception {
        Fixture fixture = new Fixture(URI.create("http://localhost:50021/"));
        try (EngineController controller = fixture.controller(Duration.ofSeconds(90))) {
            CompletableFuture<Void> start = controller.start();
            assertTrue(fixture.launched.await(2, TimeUnit.SECONDS));
            controller.stop().get(2, TimeUnit.SECONDS);
            assertThrows(CancellationException.class, start::join);
            assertEquals(STOPPED, controller.state());
            assertEquals(1, fixture.process.stopCount.get());
        }
    }

    /** 終了処理が起動を取り消してプロセスを停止し、その後の操作を拒否することを検証する。 */
    @Test
    void closeCancelsStartupAndRejectsNewOperations() throws Exception {
        Fixture fixture = new Fixture(URI.create("http://localhost:50021/"));
        EngineController controller = fixture.controller(Duration.ofSeconds(90));
        CompletableFuture<Void> start = controller.start();
        assertTrue(fixture.launched.await(2, TimeUnit.SECONDS));
        controller.close();
        assertTrue(fixture.process.stopped.await(2, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, start::join);
        assertThrows(ExecutionException.class, () -> controller.start().get(2, TimeUnit.SECONDS));
        assertFalse(controller.isOwnedRunning());
        controller.close();
        assertEquals(1, fixture.process.stopCount.get());
    }

    /** 停止後の再起動が新しいプロセスを使用し、取り消した起動処理を復活させないことを検証する。 */
    @Test
    void restartQueuedAfterStopUsesANewProcessAndDoesNotUndoCancellation() throws Exception {
        Fixture fixture = new Fixture(URI.create("http://127.0.0.1:50021/"));
        try (EngineController controller = fixture.controller(Duration.ofSeconds(90))) {
            CompletableFuture<Void> firstStart = controller.start();
            assertTrue(fixture.launched.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> stop = controller.stop();
            fixture.readyAfterLaunch.set(true);
            CompletableFuture<Void> restart = controller.start();
            stop.get(2, TimeUnit.SECONDS);
            restart.get(2, TimeUnit.SECONDS);
            assertThrows(CancellationException.class, firstStart::join);
            assertEquals(2, fixture.launchCount.get());
            assertEquals(1, fixture.process.stopCount.get());
            assertEquals(OWNED_RUNNING, controller.state());
        }
    }

    /** 起動がタイムアウトした場合に所有プロセスを停止し、エラー状態へ移ることを検証する。 */
    @Test
    void startupTimeoutCleansOwnedProcessAndReportsError() throws Exception {
        Fixture fixture = new Fixture(URI.create("http://127.0.0.1:50021/"));
        try (EngineController controller = fixture.controller(Duration.ofMillis(40))) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> controller.start().get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("タイムアウト"));
            assertEquals(ERROR, controller.state());
            assertEquals(1, fixture.process.stopCount.get());
            assertFalse(controller.isOwnedRunning());
        }
    }

    /** 停止に失敗してもプロセスの所有情報を保持し、停止を再試行できることを検証する。 */
    @Test
    void failedStopRetainsOwnershipForRetry() throws Exception {
        Fixture fixture = new Fixture(URI.create("http://127.0.0.1:50021/"));
        fixture.readyAfterLaunch.set(true);
        try (EngineController controller = fixture.controller(Duration.ofSeconds(1))) {
            controller.start().get(2, TimeUnit.SECONDS);
            fixture.process.failStop.set(true);
            assertThrows(ExecutionException.class, () -> controller.stop().get(2, TimeUnit.SECONDS));
            assertEquals(ERROR, controller.state());
            assertTrue(controller.isOwnedRunning());
            fixture.process.failStop.set(false);
            controller.stop().get(2, TimeUnit.SECONDS);
            assertFalse(controller.isOwnedRunning());
            assertEquals(STOPPED, controller.state());
        }
    }

    /** 管理起動に不適切な接続先を拒否し、許可されたループバックホストを正規化することを検証する。 */
    @Test
    void rejectsRemoteOrUnsupportedManagedLaunches() throws Exception {
        for (String uri : new String[]{"http://192.168.0.1:50021/", "https://127.0.0.1:50021/",
                "http://127.0.0.1:50021/api/", "http://127.0.0.1:0/", "http://user@127.0.0.1:50021/"}) {
            Fixture fixture = new Fixture(URI.create(uri));
            try (EngineController controller = fixture.controller(Duration.ofSeconds(1))) {
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> controller.start().get(2, TimeUnit.SECONDS), uri);
                assertInstanceOf(IllegalArgumentException.class, error.getCause());
                assertEquals(0, fixture.launchCount.get());
            }
        }
        assertEquals("::1", EngineController.managedHost(URI.create("http://[::1]:50021/")));
        assertEquals("127.0.0.1", EngineController.managedHost(URI.create("http://localhost:50021/")));
    }

    private final class Fixture {
        final URI uri;
        final Path executable;
        final FakeProcess process = new FakeProcess();
        final AtomicBoolean externalReady = new AtomicBoolean();
        final AtomicBoolean readyAfterLaunch = new AtomicBoolean();
        final AtomicInteger launchCount = new AtomicInteger();
        final CountDownLatch launched = new CountDownLatch(1);
        final AtomicReference<String> host = new AtomicReference<>();
        final AtomicInteger port = new AtomicInteger();
        final AtomicReference<Path> launchedPath = new AtomicReference<>();
        final AtomicReference<Path> logPath = new AtomicReference<>();
        final AtomicReference<String> launchThread = new AtomicReference<>();

        /** 指定接続先と、実際には実行しないテスト用実行ファイルを用意する。 */
        Fixture(URI uri) throws Exception {
            this.uri = uri;
            executable = temporary.resolve("run.exe");
            Files.writeString(executable, "test fixture, never executed");
        }

        /** テスト用実行ファイルと指定タイムアウトを使う制御オブジェクトを作成する。 */
        EngineController controller(Duration startupTimeout) {
            return controller(executable.toString(), startupTimeout);
        }

        /** 起動情報を記録する偽プロセスと応答判定を注入し、指定条件で制御オブジェクトを作成する。 */
        EngineController controller(String configuredExecutable, Duration startupTimeout) {
            return new EngineController(configuredExecutable, uri, temporary.resolve("engine.log"),
                    versionUri -> externalReady.get() || (readyAfterLaunch.get() && process.alive.get()),
                    (path, bindHost, bindPort, log) -> {
                        launchCount.incrementAndGet();
                        launchedPath.set(path);
                        host.set(bindHost);
                        port.set(bindPort);
                        logPath.set(log);
                        launchThread.set(Thread.currentThread().getName());
                        process.alive.set(true);
                        launched.countDown();
                        return process;
                    }, startupTimeout, Duration.ofMillis(5), false);
        }
    }

    private static final class FakeProcess implements EngineController.ManagedProcess {
        final AtomicBoolean alive = new AtomicBoolean();
        final AtomicBoolean failStop = new AtomicBoolean();
        final AtomicInteger stopCount = new AtomicInteger();
        final CountDownLatch stopped = new CountDownLatch(1);
        /** テスト側で設定したプロセスの稼働状態を返す。 */
        @Override public boolean isAlive() { return alive.get(); }
        /** 子プロセスを生成しない偽プロセスのため、追跡処理は行わない。 */
        @Override public void rememberDescendants() { }
        /** 失敗設定を確認したうえで停止回数と状態を更新し、停止待ちを解除する。 */
        @Override public void stop() {
            if (failStop.get()) throw new IllegalStateException("stop failed");
            stopCount.incrementAndGet();
            alive.set(false);
            stopped.countDown();
        }
        /** 通常停止と同じ処理を使用して強制停止を模擬する。 */
        @Override public void forceStop() { stop(); }
    }
}
