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
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ChatType;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
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

/** Minecraftへの参照はMojang名前空間を使用するクライアント側の入口。 */
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
    private volatile Component error = Component.empty();
    private final ExecutorService configWorker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("voicevox-config").factory());
    private boolean reloading;
    private boolean closing;
    private Component reloadStatus = Component.empty();
    // Client-thread-only; retain startup notices until a world is available.
    private final Map<EngineController, EngineNotice> engineNotices = new LinkedHashMap<>();
    private final Set<EngineController> preparingEngines = new HashSet<>();
    private final Map<EngineController, Long> engineOperations = new LinkedHashMap<>();
    private long engineOperationSequence;
    private long speechGeneration;

    /** 通知作成時のカタログと表示文を保持し、設定変更前の古い通知を判別する。 */
    private record EngineNotice(SpeakerCatalog catalog, Component text) {}

    /** 設定と各サービスを作成し、チャット・停止キー・終了処理を登録してEngineの自動起動を始める。 */
    @Override
    public void onInitializeClient() {
        instance = this;
        ActiveScreenTracker.register();
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
            reportError(Component.translatable("message.voicevoxchat.config_load_failed"));
            LOGGER.error("Could not initialize VOICEVOX Chat", exception);
        }

        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("voicevoxchat", "controls"));
        var stopSpeech = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.voicevoxchat.stop", InputConstants.KEY_O, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (playback != null) playback.tick(client);
            displayEngineNotices(client);
            while (stopSpeech.consumeClick()) { // KeyBinding.wasPressed
                if (ActiveScreenTracker.currentScreen() == null) cancelSpeech();
            }
        });

        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> {
            var client = Minecraft.getInstance();
            if (!ready() || reloading || client.player == null || signed == null || sender == null) return;
            // minecraft:chat only: /say, /me, /msg, team chat and system messages are excluded.
            boolean normalChat = params.chatType().is(ChatType.CHAT);
            boolean filtered = !signed.filterMask().isEmpty();
            if (!sender.id().equals(signed.sender())) return;
            var receivedConfig = config;
            var receivedCatalog = catalog;
            long receivedGeneration = speechGeneration;
            var preferences = receivedConfig.preferences();
            ChatPolicy.select(preferences, client.player.getUUID(), sender.id(),
                    sender.name(), signed.signedContent(), normalChat, filtered).ifPresent(utterance -> {
                boolean accepted = preferences.randomDefaultSpeaker()
                        && !preferences.playerSpeakers().containsKey(sender.name())
                        ? speech.enqueue(utterance.text(),
                            () -> assignRandomSpeaker(receivedConfig, receivedCatalog, sender.name(), receivedGeneration))
                        : speech.enqueue(utterance.text(), utterance.speakerId());
                if (!accepted) {
                    reportError(Component.translatable("message.voicevoxchat.queue_full"));
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
    public Screen createSettingsScreen(Screen parent) {
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
    /** 選択された話者の表示名を含むサンプル文を、通常の読み上げキューへ追加する。 */
    public boolean enqueueSpeakerPreview(SpeakerCatalog.Voice voice) {
        if (speech == null || reloading || closing || voice == null) return false;
        String name = voice.styleName().isBlank() ? voice.speakerName()
                : Component.translatable("screen.voicevoxchat.speaker_entry_name_style",
                        voice.speakerName(), voice.styleName()).getString();
        return speech.enqueue(voice.id() + " " + name + " です", voice.id());
    }
    /** 指定Engineで起動後の話者準備処理が続いているかを返す。 */
    public boolean preparingEngine(EngineController controller) { return preparingEngines.contains(controller); }
    /** 処理中の音声を含む読み上げ待ち件数を返し、未初期化なら0を返す。 */
    public int pendingCount() { return speech == null ? 0 : speech.pendingCount(); }
    /** 設定画面に表示する最新のエラーメッセージを返す。 */
    public Component error() { return error; }
    /** 画面に表示するエラーが現在設定されているかを返す。 */
    public boolean hasError() { return !error.getString().isBlank(); }
    /** 設定画面に表示するエラーメッセージを消去する。 */
    public void clearError() { error = Component.empty(); }
    /** 遅延中の話者割り当てを無効化し、通知を出さずに再生と読み上げキューを取り消す。 */
    public void clearSpeech() {
        // Invalidate pending main-thread assignments before interrupting the speech worker.
        speechGeneration++;
        if (speech != null) speech.clear();
    }
    /** ユーザー操作による再生中止とキュー消去を実行し、通知が有効ならチャットに知らせる。 */
    public void cancelSpeech() {
        clearSpeech();
        displayChatNotice(Minecraft.getInstance(), Component.translatable("chat.voicevoxchat.queue_cleared"));
    }

    /** 設定が読み込まれており、MODのチャット通知が有効になっているかを返す。 */
    private boolean chatNotificationsEnabled() {
        return config != null && config.preferences().chatNotifications();
    }

    /** 終了状態と通知設定を確認し、ワールド内の自分のクライアントだけに通知を表示する。 */
    private void displayChatNotice(Minecraft client, Component message) {
        if (!closing && chatNotificationsEnabled() && client.player != null) {
            client.player.sendSystemMessage(message);
        }
    }
    /** configファイルの再読込処理が進行中かを返す。 */
    public boolean reloading() { return reloading; }
    /** config再読込の進行状況または結果を表す表示文を返す。 */
    public Component reloadStatus() { return reloadStatus; }

    /** 設定済みの両Engineをバックグラウンドで起動し、接続先が同じ場合は起動処理を順番に行う。 */
    private void startConfiguredEngines() {
        var primary = engine;
        var nemo = nemoEngine;
        var primaryStart = primary.startConfigured();
        observeEngineOperation(primary, "VOICEVOX", true, primaryStart);
        // If both entries point at one endpoint, let the first launch finish before probing the second.
        var environment = config.environment();
        var nemoStart = environment.engineUri().resolve("/").equals(environment.nemoEngineUri().resolve("/"))
                ? primaryStart.handle((ignored, failure) -> null).thenCompose(ignored -> nemo.startConfigured())
                : nemo.startConfigured();
        observeEngineOperation(nemo, "VOICEVOX Nemo", true, nemoStart);
    }

    /** 選択されたEngineを手動起動し、過去の操作と話者準備の状態を更新する。 */
    public void startEngine(boolean nemo) {
        if (!ready() || reloading || closing) return;
        clearError();
        var controller = nemo ? nemoEngine : engine;
        engineOperations.remove(controller);
        warmup.reset(nemo ? config.environment().nemoEngineUri() : config.environment().engineUri());
        observeEngineOperation(controller, nemo ? "VOICEVOX Nemo" : "VOICEVOX", true, controller.start());
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
        observeEngineOperation(controller, nemo ? "VOICEVOX Nemo" : "VOICEVOX", false, controller.stop());
    }

    /** Engine操作の完了を監視し、有効な結果だけで話者一覧の更新・話者準備・通知を進める。 */
    private void observeEngineOperation(EngineController controller, String name, boolean starting,
                                        CompletableFuture<Void> operation) {
        SpeakerCatalog observedCatalog = catalog;
        long operationId = ++engineOperationSequence;
        engineOperations.put(controller, operationId);
        operation.whenComplete((ignored, failure) -> Minecraft.getInstance().execute(() -> {
            // Results from a previous config must not overwrite the current screen or catalog.
            if (!currentEngineOperation(controller, operationId, observedCatalog)) return;
            if (failure != null) {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof CancellationException) return;
                Component action = Component.translatable(starting
                        ? "engine.voicevoxchat.action.start" : "engine.voicevoxchat.action.stop");
                reportError(Component.translatable("message.voicevoxchat.engine_operation_failed",
                        name, action, controller.detail()));
                LOGGER.warn("{} engine {} failed", name, starting ? "start" : "stop", cause);
                return;
            }
            var state = controller.state();
            boolean playerWasAvailable = Minecraft.getInstance().player != null;
            LOGGER.info("{} engine: {}", name, state);
            if (starting && (state == EngineController.State.OWNED_RUNNING
                    || state == EngineController.State.EXTERNAL_RUNNING)) {
                preparingEngines.add(controller);
                URI endpoint = controller == nemoEngine ? config.environment().nemoEngineUri() : config.environment().engineUri();
                var observedWarmup = warmup;
                var observedPreferences = config.preferences();
                observedCatalog.refreshAfterCurrent().thenCompose(snapshot ->
                        warmSpeakers(observedWarmup, observedPreferences, snapshot, endpoint))
                        .whenComplete((prepared, preparationFailure) -> Minecraft.getInstance().execute(() -> {
                    if (!currentEngineOperation(controller, operationId, observedCatalog)) return;
                    preparingEngines.remove(controller);
                    if (controller.state() != EngineController.State.OWNED_RUNNING
                            && controller.state() != EngineController.State.EXTERNAL_RUNNING) return;
                    Component notice = switch (state) {
                        case OWNED_RUNNING -> Component.translatable("chat.voicevoxchat.engine_started", name);
                        case EXTERNAL_RUNNING -> Component.translatable("chat.voicevoxchat.engine_connected", name);
                        default -> null;
                    };
                    if (notice != null && playerWasAvailable && chatNotificationsEnabled()) engineNotices.put(controller,
                            new EngineNotice(observedCatalog, notice));
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
        Minecraft.getInstance().execute(() -> {
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
    private void displayEngineNotices(Minecraft client) {
        if (!chatNotificationsEnabled()) {
            engineNotices.clear();
            return;
        }
        if (closing || reloading || client.player == null) return;
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
        reloadStatus = Component.translatable("screen.voicevoxchat.config_loading");
        clearError();
        CompletableFuture.supplyAsync(() -> {
            try {
                return new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("voicevoxchat.json"));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, configWorker).whenComplete((loaded, failure) -> Minecraft.getInstance().execute(() -> {
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
                    Minecraft.getInstance().execute(() -> {
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
            reloadStatus = Component.translatable("message.voicevoxchat.config_reloaded");
        } else {
            reloadStatus = Component.empty();
            LOGGER.warn("Could not reload VOICEVOX Chat config; retaining previous configuration", failure);
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            reportError(Component.translatable("message.voicevoxchat.config_reload_failed",
                    cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
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
            reportError(Component.translatable("message.voicevoxchat.config_save_failed"));
            return false;
        }
    }

    /** 最新エラーを画面表示用に保持し、同じ内容を警告ログへ記録する。 */
    public void reportError(String message) {
        reportError(Component.literal(message));
    }

    /** 翻訳済みComponentを画面表示用に保持し、解決後の文字列を警告ログへ記録する。 */
    public void reportError(Component message) {
        error = message;
        LOGGER.warn("{}", message.getString());
    }
}
