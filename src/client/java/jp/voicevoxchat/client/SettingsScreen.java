/*
 * 読み上げ設定、Engineの起動・停止、config再読込、および各設定画面への入口を提供する。
 */
package jp.voicevoxchat.client;

import jp.voicevoxchat.config.ConfigStore.Preferences;
import jp.voicevoxchat.config.ConfigStore;
import jp.voicevoxchat.engine.EngineController;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

final class SettingsScreen extends BaseScreen {
    private EditBox defaultSpeaker;
    private Button enabledButton;
    private Button notificationsButton;
    private Button randomButton;
    private Button applySpeakerButton;
    private Button startButton;
    private Button stopButton;
    private Button reloadButton;
    private Button engineButton;
    private boolean selectedNemo;
    private boolean displayedReloading;
    private ConfigStore displayedConfig;

    /** MODの表示名をタイトルに設定し、戻り先の画面を保持する。 */
    SettingsScreen(VoicevoxChatClient mod, Screen parent) {
        super(mod, parent, "VVTTS - VOICEVOX Chat TTS");
    }

    /** 現在の設定と再読込状態に合わせて、設定項目と各操作ボタンを配置する。 */
    @Override
    protected void init() {
        layout();
        displayedReloading = mod.reloading();
        displayedConfig = mod.config();
        button("完了", left, height - 26, contentWidth, this::onClose);
        int half = (contentWidth - 4) / 2;
        reloadButton = button("config再読込", left + half + 4, 32, contentWidth - half - 4, () -> {
            mod.reloadConfig(() -> {
                defaultSpeaker = null;
                if (ActiveScreenTracker.currentScreen() == this) rebuildWidgets();
            });
            rebuildWidgets();
        });
        reloadButton.active = !mod.reloading();
        if (mod.reloading()) return;
        if (!mod.ready()) return;
        var preferences = mod.config().preferences();
        enabledButton = button("", left, 32, half, () -> {
            var current = mod.config().preferences();
            mod.save(new Preferences(!current.enabled(), current.defaultSpeakerId(), current.playerSpeakers(),
                    current.randomDefaultSpeaker(), current.chatNotifications()));
            updateButtons();
        });
        defaultSpeaker = input("初期話者ID", left + 46, 58, 52, 10,
                defaultSpeaker == null ? Integer.toString(preferences.defaultSpeakerId()) : defaultSpeaker.getValue());
        applySpeakerButton = button("適用", left + 102, 58, 44, () -> {
            try {
                int id = Integer.parseInt(defaultSpeaker.getValue());
                var current = mod.config().preferences();
                mod.save(new Preferences(current.enabled(), id, current.playerSpeakers(),
                        current.randomDefaultSpeaker(), current.chatNotifications()));
            } catch (IllegalArgumentException exception) {
                mod.reportError("話者IDは0以上の整数にしてください。");
            }
        });
        randomButton = button("", left + 150, 58, contentWidth - 150, () -> {
            var current = mod.config().preferences();
            mod.save(new Preferences(current.enabled(), current.defaultSpeakerId(), current.playerSpeakers(),
                    !current.randomDefaultSpeaker(), current.chatNotifications()));
            updateButtons();
        });
        engineButton = button("", left, 84, half, () -> {
            selectedNemo = !selectedNemo;
            updateButtons();
        });
        int quarter = (contentWidth - half - 8) / 2;
        startButton = button("起動", left + half + 4, 84, quarter, () -> mod.startEngine(selectedNemo));
        stopButton = button("停止", left + half + quarter + 8, 84, contentWidth - half - quarter - 8,
                () -> mod.stopEngine(selectedNemo));
        button("プレイヤー別の話者ID", left, 122, half,
                () -> minecraft.setScreenAndShow(new PlayersScreen(mod, this)));
        notificationsButton = button("", left + half + 4, 122, contentWidth - half - 4, () -> {
            var current = mod.config().preferences();
            mod.save(new Preferences(current.enabled(), current.defaultSpeakerId(), current.playerSpeakers(),
                    current.randomDefaultSpeaker(), !current.chatNotifications()));
            updateButtons();
        });
        button("話者ID・名前の一覧", left, 148, contentWidth,
                () -> minecraft.setScreenAndShow(new SpeakerListScreen(mod, this)));
        button("再生を中止・キューを空にする", left, 174, contentWidth, mod::cancelSpeech);
        updateButtons();
    }

    /** 画面で選択されている通常版またはNemoのEngine制御オブジェクトを返す。 */
    private EngineController selectedEngine() {
        return selectedNemo ? mod.nemoEngine() : mod.engine();
    }

    /** 設定値とEngineの状態を各ボタンの表示名・入力可否・押下可否へ反映する。 */
    private void updateButtons() {
        if (!mod.ready() || mod.reloading()) return;
        enabledButton.setMessage(text("読み上げ: " + (mod.config().preferences().enabled() ? "ON" : "OFF")));
        notificationsButton.setMessage(text("チャット通知: "
                + (mod.config().preferences().chatNotifications() ? "ON" : "OFF")));
        boolean random = mod.config().preferences().randomDefaultSpeaker();
        randomButton.setMessage(text("ランダム: " + (random ? "ON" : "OFF")));
        defaultSpeaker.active = !random;
        applySpeakerButton.active = !random;
        engineButton.setMessage(text("Engine: " + (selectedNemo ? "Nemo" : "VOICEVOX")));
        var state = selectedEngine().state();
        startButton.active = state == EngineController.State.STOPPED || state == EngineController.State.ERROR;
        stopButton.active = selectedEngine().isOwnedRunning() && state != EngineController.State.STOPPING;
    }

    /** configの差し替えや再読込状態の変化に追従し、画面とボタン状態を更新する。 */
    @Override
    public void tick() {
        // Also handles leaving this screen during reload and reopening it before completion.
        if (displayedReloading != mod.reloading() || displayedConfig != mod.config()) {
            if (displayedConfig != mod.config()) defaultSpeaker = null;
            rebuildWidgets();
            return;
        }
        updateButtons();
    }

    /** 設定欄の見出し、Engineの状態、再読込結果、エラーまたはキュー件数を描画する。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (mod.reloading()) {
            line(graphics, mod.reloadStatus(), 64, 0xFFDDDDDD);
            return;
        }
        if (!mod.ready()) {
            graphics.textWithWordWrap(font, text(mod.error()), left, 64, contentWidth, 0xFFFF8888);
            return;
        }
        label(graphics, "初期ID", left, 64, 0xFFFFFFFF);
        String state = switch (selectedEngine().state()) {
            case STOPPED -> "未接続";
            case STARTING -> "起動中…";
            case OWNED_RUNNING -> "稼働中";
            case EXTERNAL_RUNNING -> "接続済み";
            case STOPPING -> "停止中…";
            case ERROR -> "エラー: " + selectedEngine().detail();
        };
        if (mod.preparingEngine(selectedEngine())) state = "話者を準備中…";
        line(graphics, (selectedNemo ? "Nemo: " : "VOICEVOX: ") + state, 108, 0xFFDDDDDD);
        String notice = !mod.error().isBlank() ? mod.error() : !mod.reloadStatus().isBlank()
                ? mod.reloadStatus() : "キュー: " + mod.pendingCount() + " / 音量: 「音声読み上げ／声」で調整可能";
        line(graphics, notice, 198, mod.error().isBlank() ? 0xFFAAAAAA : 0xFFFF8888);
    }
}
