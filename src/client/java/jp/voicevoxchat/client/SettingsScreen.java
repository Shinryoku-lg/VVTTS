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
import net.minecraft.network.chat.Component;

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
        super(mod, parent, "screen.voicevoxchat.title");
    }

    /** 現在の設定と再読込状態に合わせて、設定項目と各操作ボタンを配置する。 */
    @Override
    protected void init() {
        layout();
        displayedReloading = mod.reloading();
        displayedConfig = mod.config();
        button("screen.voicevoxchat.done", left, height - 26, contentWidth, this::onClose);
        int half = (contentWidth - 4) / 2;
        reloadButton = button("screen.voicevoxchat.config_reload", left + half + 4, 32, contentWidth - half - 4, () -> {
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
        defaultSpeaker = input("screen.voicevoxchat.default_speaker", left + 46, 58, 52, 10,
                defaultSpeaker == null ? Integer.toString(preferences.defaultSpeakerId()) : defaultSpeaker.getValue());
        applySpeakerButton = button("screen.voicevoxchat.apply", left + 102, 58, 44, () -> {
            try {
                int id = Integer.parseInt(defaultSpeaker.getValue());
                var current = mod.config().preferences();
                mod.save(new Preferences(current.enabled(), id, current.playerSpeakers(),
                        current.randomDefaultSpeaker(), current.chatNotifications()));
            } catch (IllegalArgumentException exception) {
                mod.reportError(tr("message.voicevoxchat.invalid_speaker_id"));
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
        startButton = button("screen.voicevoxchat.engine_start", left + half + 4, 84, quarter,
                () -> mod.startEngine(selectedNemo));
        stopButton = button("screen.voicevoxchat.engine_stop", left + half + quarter + 8, 84,
                contentWidth - half - quarter - 8,
                () -> mod.stopEngine(selectedNemo));
        button("screen.voicevoxchat.players", left, 122, half,
                () -> minecraft.setScreenAndShow(new PlayersScreen(mod, this)));
        notificationsButton = button("", left + half + 4, 122, contentWidth - half - 4, () -> {
            var current = mod.config().preferences();
            mod.save(new Preferences(current.enabled(), current.defaultSpeakerId(), current.playerSpeakers(),
                    current.randomDefaultSpeaker(), !current.chatNotifications()));
            updateButtons();
        });
        button("screen.voicevoxchat.speaker_list", left, 148, contentWidth,
                () -> minecraft.setScreenAndShow(new SpeakerListScreen(mod, this)));
        button("screen.voicevoxchat.cancel_speech", left, 174, contentWidth, mod::cancelSpeech);
        updateButtons();
    }

    /** 画面で選択されている通常版またはNemoのEngine制御オブジェクトを返す。 */
    private EngineController selectedEngine() {
        return selectedNemo ? mod.nemoEngine() : mod.engine();
    }

    /** 現在選択中のEngine名を表示用データとして返す。 */
    private Component engineName() {
        return Component.literal(selectedNemo ? "Nemo" : "VOICEVOX");
    }

    /** 設定値とEngineの状態を各ボタンの表示名・入力可否・押下可否へ反映する。 */
    private void updateButtons() {
        if (!mod.ready() || mod.reloading()) return;
        enabledButton.setMessage(tr("screen.voicevoxchat.enabled",
                tr(mod.config().preferences().enabled() ? "common.voicevoxchat.on" : "common.voicevoxchat.off")));
        notificationsButton.setMessage(tr("screen.voicevoxchat.chat_notifications",
                tr(mod.config().preferences().chatNotifications()
                        ? "common.voicevoxchat.on" : "common.voicevoxchat.off")));
        boolean random = mod.config().preferences().randomDefaultSpeaker();
        randomButton.setMessage(tr("screen.voicevoxchat.random",
                tr(random ? "common.voicevoxchat.on" : "common.voicevoxchat.off")));
        defaultSpeaker.active = !random;
        applySpeakerButton.active = !random;
        engineButton.setMessage(tr("screen.voicevoxchat.engine_selector", engineName()));
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
            graphics.textWithWordWrap(font, mod.error(), left, 64, contentWidth, 0xFFFF8888);
            return;
        }
        label(graphics, tr("screen.voicevoxchat.default_id"), left, 64, 0xFFFFFFFF);
        Component state = switch (selectedEngine().state()) {
            case STOPPED -> tr("engine.voicevoxchat.state.disconnected");
            case STARTING -> tr("engine.voicevoxchat.state.starting");
            case OWNED_RUNNING -> tr("engine.voicevoxchat.state.running");
            case EXTERNAL_RUNNING -> tr("engine.voicevoxchat.state.connected");
            case STOPPING -> tr("engine.voicevoxchat.state.stopping");
            case ERROR -> tr("engine.voicevoxchat.state.error", selectedEngine().detail());
        };
        if (mod.preparingEngine(selectedEngine())) state = tr("engine.voicevoxchat.state.preparing_speakers");
        line(graphics, tr("screen.voicevoxchat.engine_status", engineName(), state), 108, 0xFFDDDDDD);
        Component notice = mod.hasError() ? mod.error() : !mod.reloadStatus().getString().isBlank()
                ? mod.reloadStatus() : tr("screen.voicevoxchat.queue_volume", mod.pendingCount());
        line(graphics, notice, 198, mod.hasError() ? 0xFFFF8888 : 0xFFAAAAAA);
    }
}
