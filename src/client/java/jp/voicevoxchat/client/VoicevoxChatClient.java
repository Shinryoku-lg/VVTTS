/*
 * MODの初期化とMinecraftのイベントを結び付け、チャット読み上げ・Engine・設定・通知を統括する。
 */
package jp.voicevoxchat.client;

import jp.voicevoxchat.chat.ChatPolicy;
import jp.voicevoxchat.config.ConfigStore;
import jp.voicevoxchat.engine.EngineController;
import jp.voicevoxchat.speech.SpeakerCatalog;
import jp.voicevoxchat.speech.SpeechService;
import jp.voicevoxchat.speech.EngineWarmup;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.class_2556;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_304;
import net.minecraft.class_310;
import net.minecraft.class_437;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Minecraftへの参照はIntermediary名前空間を使用するクライアント側の入口。 */
public final class VoicevoxChatClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("voicevoxchat");
    private static VoicevoxChatClient instance;
    private ConfigStore config;
    private EngineController engine;
    private EngineController nemoEngine;
    private SpeakerCatalog catalog;
    private EngineWarmup warmup;
    private SpeechService speech;
    private MinecraftSoundPlayback playback;
    private volatile String error = "";
    private final ExecutorService configWorker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("voicevox-config").factory());
    private boolean reloading;
    private boolean closing;
    private String reloadStatus = "";
    // Client-thread-only; retain startup notices until a world is available.
    private final Map<EngineController, EngineNotice> engineNotices = new LinkedHashMap<>();
    private final Set<EngineController> preparingEngines = new HashSet<>();
    private final Map<EngineController, Long> engineOperations = new LinkedHashMap<>();
    private long engineOperationSequence;
    private long speechGeneration;

    /** 通知作成時のカタログと表示文を保持し、設定変更前の古い通知を判別する。 */
    private record EngineNotice(SpeakerCatalog catalog, String text) {}

    /** 設定と各サービスを作成し、チャット・停止キー・終了処理を登録してEngineの自動起動を始める。 */
    @Override
    public void onInitializeClient() {
        instance = this;
        try {
            config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("voicevoxchat.json"));
            var environment = config.environment();
            engine = new EngineController(environment.engineExecutable(), environment.engineUri(),
                    FabricLoader.getInstance().getGameDir().resolve("logs/voicevoxchat-engine.log"));
            nemoEngine = new EngineController(environment.nemoEngineExecutable(), environment.nemoEngineUri(),
                    FabricLoader.getInstance().getGameDir().resolve("logs/voicevoxchat-nemo-engine.log"));
            catalog = new SpeakerCatalog(environment.engineUri(), environment.nemoEngineUri());
            warmup = new EngineWarmup(message -> LOGGER.warn("{}", message));
            playback = new MinecraftSoundPlayback();
            speech = new SpeechService(catalog::resolve, this::reportError, playback);
        } catch (IOException | RuntimeException exception) {
            reportError("設定を読み込めません。config/voicevoxchat.json を修正して「config再読込」を押してください。");
            LOGGER.error("Could not initialize VOICEVOX Chat", exception);
        }

        var category = class_304.class_11900.method_74698(class_2960.method_60655("voicevoxchat", "controls"));
        var stopSpeech = KeyBindingHelper.registerKeyBinding(
                new class_304("key.voicevoxchat.stop", GLFW.GLFW_KEY_O, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (playback != null) playback.tick(client);
            displayEngineNotices(client);
            while (stopSpeech.method_1436()) { // KeyBinding.wasPressed
                if (client.field_1755 == null) cancelSpeech();
            }
        });

        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> {
            var client = class_310.method_1551();
            if (!ready() || reloading || client.field_1724 == null || signed == null || sender == null) return;
            // minecraft:chat only: /say, /me, /msg, team chat and system messages are excluded.
            boolean normalChat = params.comp_919().method_40225(class_2556.field_11737);
            boolean filtered = !signed.comp_981().method_45087();
            if (!sender.id().equals(signed.method_46292())) return;
            var receivedConfig = config;
            var receivedCatalog = catalog;
            long receivedGeneration = speechGeneration;
            var preferences = receivedConfig.preferences();
            ChatPolicy.select(preferences, client.field_1724.method_5667(), sender.id(),
                    sender.name(), signed.method_44862(), normalChat, filtered).ifPresent(utterance -> {
                boolean accepted = preferences.randomDefaultSpeaker()
                        && !preferences.playerSpeakers().containsKey(sender.name())
                        ? speech.enqueue(utterance.text(),
                            () -> assignRandomSpeaker(receivedConfig, receivedCatalog, sender.name(), receivedGeneration))
                        : speech.enqueue(utterance.text(), utterance.speakerId());
                if (!accepted) {
                    reportError("読み上げキューが満杯のため、新しいメッセージをスキップしました。");
                }
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearSpeech());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            closing = true;
            engineNotices.clear();
            preparingEngines.clear();
            engineOperations.clear();
            configWorker.shutdownNow();
            if (speech != null) speech.close();
            if (warmup != null) warmup.close();
            if (catalog != null) catalog.close();
            if (engine != null) engine.close();
            if (nemoEngine != null) nemoEngine.close();
        });
        LOGGER.info("VVTTS - VOICEVOX Chat TTS initialized. Settings: Mod Menu. Stop speech and clear queue: O key.");
        if (ready()) startConfiguredEngines();
    }

    /** 初期化済みのMOD本体を返し、初期化前のアクセスは例外にする。 */
    public static VoicevoxChatClient instance() {
        if (instance == null) throw new IllegalStateException("VOICEVOX Chat has not initialized yet");
        return instance;
    }

    /** 指定した画面を戻り先として、MODの設定画面を生成する。 */
    public class_437 createSettingsScreen(class_437 parent) {
        return new SettingsScreen(this, parent);
    }

    /** 読み上げに必要な設定・Engine制御・話者カタログ・音声サービスが揃っているかを返す。 */
    public boolean ready() {
        return config != null && engine != null && nemoEngine != null && catalog != null && speech != null;
    }
    /** 現在利用中の設定ストアを返す。 */
    public ConfigStore config() { return config; }
    /** 通常版VOICEVOX Engineの制御オブジェクトを返す。 */
    public EngineController engine() { return engine; }
    /** VOICEVOX Nemo Engineの制御オブジェクトを返す。 */
    public EngineController nemoEngine() { return nemoEngine; }
    /** 両Engineで利用できる話者を管理する共有カタログを返す。 */
    public SpeakerCatalog catalog() { return catalog; }
    /** 指定Engineで起動後の話者準備処理が続いているかを返す。 */
    public boolean preparingEngine(EngineController controller) { return preparingEngines.contains(controller); }
    /** 処理中の音声を含む読み上げ待ち件数を返し、未初期化なら0を返す。 */
    public int pendingCount() { return speech == null ? 0 : speech.pendingCount(); }
    /** 設定画面に表示する最新のエラーメッセージを返す。 */
    public String error() { return error; }
    /** 設定画面に表示するエラーメッセージを消去する。 */
    public void clearError() { error = ""; }
    /** 遅延中の話者割り当てを無効化し、通知を出さずに再生と読み上げキューを取り消す。 */
    public void clearSpeech() {
        // Invalidate pending main-thread assignments before interrupting the speech worker.
        speechGeneration++;
        if (speech != null) speech.clear();
    }
    /** ユーザー操作による再生中止とキュー消去を実行し、通知が有効ならチャットに知らせる。 */
    public void cancelSpeech() {
        clearSpeech();
        displayChatNotice(class_310.method_1551(), "[VVVTS] 読み上げキューをクリアしました。");
    }

    /** 設定が読み込まれており、MODのチャット通知が有効になっているかを返す。 */
    private boolean chatNotificationsEnabled() {
        return config != null && config.preferences().chatNotifications();
    }

    /** 終了状態と通知設定を確認し、ワールド内の自分のクライアントだけに通知を表示する。 */
    private void displayChatNotice(class_310 client, String message) {
        if (!closing && chatNotificationsEnabled() && client.field_1724 != null) {
            client.field_1724.method_7353(class_2561.method_43470(message), false);
        }
    }
    /** configファイルの再読込処理が進行中かを返す。 */
    public boolean reloading() { return reloading; }
    /** config再読込の進行状況または結果を表す表示文を返す。 */
    public String reloadStatus() { return reloadStatus; }

    /** 設定済みの両Engineをバックグラウンドで起動し、接続先が同じ場合は起動処理を順番に行う。 */
    private void startConfiguredEngines() {
        var primary = engine;
        var nemo = nemoEngine;
        var primaryStart = primary.startConfigured();
        observeEngineOperation(primary, "VOICEVOX", "起動", primaryStart);
        // If both entries point at one endpoint, let the first launch finish before probing the second.
        var environment = config.environment();
        var nemoStart = environment.engineUri().resolve("/").equals(environment.nemoEngineUri().resolve("/"))
                ? primaryStart.handle((ignored, failure) -> null).thenCompose(ignored -> nemo.startConfigured())
                : nemo.startConfigured();
        observeEngineOperation(nemo, "VOICEVOX Nemo", "起動", nemoStart);
    }

    /** 選択されたEngineを手動起動し、過去の操作と話者準備の状態を更新する。 */
    public void startEngine(boolean nemo) {
        if (!ready() || reloading || closing) return;
        clearError();
        var controller = nemo ? nemoEngine : engine;
        engineOperations.remove(controller);
        warmup.reset(nemo ? config.environment().nemoEngineUri() : config.environment().engineUri());
        observeEngineOperation(controller, nemo ? "VOICEVOX Nemo" : "VOICEVOX", "起動", controller.start());
    }

    /** 読み上げを消去し、選択されたEngineのうちMODが所有するプロセスの停止を要求する。 */
    public void stopEngine(boolean nemo) {
        if (!ready() || reloading || closing) return;
        clearError();
        clearSpeech();
        var controller = nemo ? nemoEngine : engine;
        engineNotices.remove(controller);
        preparingEngines.remove(controller);
        engineOperations.remove(controller);
        warmup.reset(nemo ? config.environment().nemoEngineUri() : config.environment().engineUri());
        observeEngineOperation(controller, nemo ? "VOICEVOX Nemo" : "VOICEVOX", "停止", controller.stop());
    }

    /** Engine操作の完了を監視し、有効な結果だけで話者一覧の更新・話者準備・通知を進める。 */
    private void observeEngineOperation(EngineController controller, String name, String action,
                                        CompletableFuture<Void> operation) {
        SpeakerCatalog observedCatalog = catalog;
        long operationId = ++engineOperationSequence;
        engineOperations.put(controller, operationId);
        operation.whenComplete((ignored, failure) -> class_310.method_1551().execute(() -> {
            // Results from a previous config must not overwrite the current screen or catalog.
            if (!currentEngineOperation(controller, operationId, observedCatalog)) return;
            if (failure != null) {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof CancellationException) return;
                reportError(name + " の" + action + "に失敗しました: " + controller.detail());
                LOGGER.warn("{} engine {} failed", name, action, cause);
                return;
            }
            var state = controller.state();
            LOGGER.info("{} engine: {}", name, state);
            if (action.equals("起動") && (state == EngineController.State.OWNED_RUNNING
                    || state == EngineController.State.EXTERNAL_RUNNING)) {
                preparingEngines.add(controller);
                URI endpoint = controller == nemoEngine ? config.environment().nemoEngineUri() : config.environment().engineUri();
                var observedWarmup = warmup;
                var observedPreferences = config.preferences();
                observedCatalog.refreshAfterCurrent().thenCompose(snapshot ->
                        warmSpeakers(observedWarmup, observedPreferences, snapshot, endpoint))
                        .whenComplete((prepared, preparationFailure) -> class_310.method_1551().execute(() -> {
                    if (!currentEngineOperation(controller, operationId, observedCatalog)) return;
                    preparingEngines.remove(controller);
                    if (controller.state() != EngineController.State.OWNED_RUNNING
                            && controller.state() != EngineController.State.EXTERNAL_RUNNING) return;
                    String notice = switch (state) {
                        case OWNED_RUNNING -> name + " Engine の起動が完了しました。";
                        case EXTERNAL_RUNNING -> name + " Engine に接続しました（起動済み）。";
                        default -> null;
                    };
                    if (notice != null && chatNotificationsEnabled()) engineNotices.put(controller,
                            new EngineNotice(observedCatalog, "[VVVTS] " + notice));
                }));
            } else {
                observedCatalog.refreshAfterCurrent();
            }
        }));
    }

    /** コールバックが現在の設定と最新のEngine操作に対応し、結果を反映できるかを判定する。 */
    private boolean currentEngineOperation(EngineController controller, long id, SpeakerCatalog expectedCatalog) {
        return !closing && !reloading && catalog == expectedCatalog
                && Long.valueOf(id).equals(engineOperations.get(controller));
    }

    /** 現在の設定から、指定Engineで利用予定の話者を事前に初期化する。 */
    private CompletableFuture<Void> warmConfiguredSpeakers(SpeakerCatalog.Snapshot snapshot, URI endpoint) {
        return warmSpeakers(warmup, config.preferences(), snapshot, endpoint);
    }

    /** 登録済み話者と必要な初期話者を抽出し、Engineの指定があれば絞り込んで事前初期化する。 */
    private static CompletableFuture<Void> warmSpeakers(EngineWarmup currentWarmup,
            ConfigStore.Preferences preferences, SpeakerCatalog.Snapshot snapshot, URI endpoint) {
        // Initializing unused random candidates would load every model.
        Set<Integer> ids = new HashSet<>(preferences.playerSpeakers().values());
        if (!preferences.randomDefaultSpeaker()) ids.add(preferences.defaultSpeakerId());
        return currentWarmup.warm(snapshot.voices().stream()
                .filter(voice -> ids.contains(voice.id()))
                .filter(voice -> endpoint == null || voice.engineUri().resolve("/").equals(endpoint.resolve("/")))
                .toList());
    }

    /** 読み上げ順にランダム候補を選び、有効な要求だけをクライアント側でconfigへ保存してIDを返す。 */
    private int assignRandomSpeaker(ConfigStore receivedConfig, SpeakerCatalog receivedCatalog,
                                    String playerName, long receivedGeneration) throws Exception {
        var current = receivedConfig.preferences();
        if (!current.randomDefaultSpeaker() || current.playerSpeakers().containsKey(playerName)) {
            return current.speakerFor(playerName);
        }
        int candidate = receivedCatalog.randomSpeakerId();
        CompletableFuture<Integer> assigned = new CompletableFuture<>();
        class_310.method_1551().execute(() -> {
            if (assigned.isDone()) return;
            if (closing || reloading || config != receivedConfig || catalog != receivedCatalog
                    || speechGeneration != receivedGeneration) {
                assigned.completeExceptionally(new CancellationException("Config changed during speaker assignment"));
                return;
            }
            try {
                assigned.complete(receivedConfig.assignRandomSpeakerIfNeeded(playerName, candidate));
            } catch (IOException | RuntimeException exception) {
                assigned.completeExceptionally(exception);
            }
        });
        try {
            return assigned.get(10, TimeUnit.SECONDS);
        } finally {
            // If this utterance was cancelled, discard a not-yet-executed assignment too.
            assigned.cancel(false);
        }
    }

    /** 通知が有効でワールドに入っていれば、現在も有効なEngine起動・接続通知を取り出して表示する。 */
    private void displayEngineNotices(class_310 client) {
        if (!chatNotificationsEnabled()) {
            engineNotices.clear();
            return;
        }
        if (closing || reloading || client.field_1724 == null) return;
        var iterator = engineNotices.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
            var state = entry.getKey().state();
            if (entry.getValue().catalog() != catalog
                    || (state != EngineController.State.OWNED_RUNNING
                        && state != EngineController.State.EXTERNAL_RUNNING)) continue;
            // Local system chat only; never send this notice to the server or the speech queue.
            displayChatNotice(client, entry.getValue().text());
        }
    }

    /** configを非同期に検証し、変更対象の所有Engineを停止できた後で各サービスを差し替える。 */
    public void reloadConfig(Runnable finished) {
        if (reloading || closing) return;
        reloading = true;
        reloadStatus = "configを読み込み中…";
        clearError();
        CompletableFuture.supplyAsync(() -> {
            try {
                return new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("voicevoxchat.json"));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, configWorker).whenComplete((loaded, failure) -> class_310.method_1551().execute(() -> {
            if (closing) return;
            if (failure != null) {
                finishReload(failure, finished);
                return;
            }
            var next = loaded.environment();
            var current = config == null ? null : config.environment();
            boolean replaceEngine = engine == null || current == null
                    || !current.engineExecutable().equals(next.engineExecutable())
                    || !current.engineUri().equals(next.engineUri());
            boolean replaceNemo = nemoEngine == null || current == null
                    || !current.nemoEngineExecutable().equals(next.nemoEngineExecutable())
                    || !current.nemoEngineUri().equals(next.nemoEngineUri());
            clearSpeech();
            // stop() only targets owned processes and leaves the controller usable on failure.
            var stopEngine = replaceEngine && engine != null ? engine.stop() : CompletableFuture.<Void>completedFuture(null);
            var stopNemo = replaceNemo && nemoEngine != null ? nemoEngine.stop() : CompletableFuture.<Void>completedFuture(null);
            CompletableFuture.allOf(stopEngine, stopNemo).whenComplete((ignored, stopFailure) ->
                    class_310.method_1551().execute(() -> {
                        if (closing) return;
                        if (stopFailure != null) {
                            finishReload(stopFailure, finished);
                            return;
                        }
                        if (speech != null) speech.close();
                        if (warmup != null) warmup.close();
                        if (catalog != null) catalog.close();
                        if (replaceEngine) {
                            if (engine != null) engine.close();
                            engine = new EngineController(next.engineExecutable(), next.engineUri(),
                                    FabricLoader.getInstance().getGameDir().resolve("logs/voicevoxchat-engine.log"));
                        }
                        if (replaceNemo) {
                            if (nemoEngine != null) nemoEngine.close();
                            nemoEngine = new EngineController(next.nemoEngineExecutable(), next.nemoEngineUri(),
                                    FabricLoader.getInstance().getGameDir().resolve("logs/voicevoxchat-nemo-engine.log"));
                        }
                        config = loaded;
                        engineNotices.clear();
                        preparingEngines.clear();
                        engineOperations.clear();
                        catalog = new SpeakerCatalog(next.engineUri(), next.nemoEngineUri());
                        warmup = new EngineWarmup(message -> LOGGER.warn("{}", message));
                        playback = new MinecraftSoundPlayback();
                        speech = new SpeechService(catalog::resolve, this::reportError, playback);
                        finishReload(null, finished);
                        startConfiguredEngines();
                    }));
        }));
    }

    /** 再読込中の状態を解除して成功または失敗を表示し、呼び出し元の完了処理を実行する。 */
    private void finishReload(Throwable failure, Runnable finished) {
        reloading = false;
        if (failure == null) {
            clearError();
            reloadStatus = "configを反映しました。設定済みEngineを自動起動します。";
        } else {
            reloadStatus = "";
            LOGGER.warn("Could not reload VOICEVOX Chat config; retaining previous configuration", failure);
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            reportError("configの反映に失敗しました。前の設定を維持します: " + cause.getMessage());
        }
        finished.run();
    }

    /** 設定を保存し、通知・読み上げの無効化や話者の事前準備を反映して保存結果を返す。 */
    public boolean save(ConfigStore.Preferences preferences) {
        if (reloading || closing || config == null) return false;
        try {
            config.save(preferences);
            if (!preferences.chatNotifications()) engineNotices.clear();
            if (!preferences.enabled()) clearSpeech();
            clearError();
            warmConfiguredSpeakers(catalog.snapshot(), null);
            return true;
        } catch (IOException exception) {
            LOGGER.error("Could not save VOICEVOX Chat preferences", exception);
            reportError("設定を保存できません。ログとconfigファイルを確認してください。");
            return false;
        }
    }

    /** 最新エラーを画面表示用に保持し、同じ内容を警告ログへ記録する。 */
    public void reportError(String message) {
        error = message;
        LOGGER.warn("{}", message);
    }
}
