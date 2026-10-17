/*
 * プレイヤー名ごとの話者IDを一覧表示し、追加・更新・削除してconfigへ保存する画面を提供する。
 */
package jp.voicevoxchat.client;

import jp.voicevoxchat.config.ConfigStore.Preferences;
import jp.voicevoxchat.config.ConfigStore.ValidationException;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

final class PlayersScreen extends BaseScreen {
    private EditBox playerName;
    private EditBox speakerId;
    private int page;
    private int pages;
    private Map<String, Integer> displayedSpeakers = Map.of();

    /** プレイヤー別設定画面のタイトルと戻り先を設定する。 */
    PlayersScreen(VoicevoxChatClient mod, Screen parent) {
        super(mod, parent, "screen.voicevoxchat.players");
    }

    /** 入力値を維持して編集欄を作り、登録済み話者のページと操作ボタンを配置する。 */
    @Override
    protected void init() {
        layout();
        playerName = input("screen.voicevoxchat.player_name", left, 46, contentWidth - 80, 16,
                playerName == null ? "" : playerName.getValue());
        speakerId = input("screen.voicevoxchat.speaker_id", left + contentWidth - 74, 46, 74, 10,
                speakerId == null ? "3" : speakerId.getValue());
        button("screen.voicevoxchat.save_player", left, 72, contentWidth, this::savePlayer);

        displayedSpeakers = mod.config().preferences().playerSpeakers();
        var entries = new ArrayList<>(displayedSpeakers.entrySet());
        int rows = Math.max(1, (height - 188) / 22);
        pages = Math.max(1, (entries.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int row = 0; row < rows && page * rows + row < entries.size(); row++) {
            var entry = entries.get(page * rows + row);
            int y = 112 + row * 22;
            button(tr("screen.voicevoxchat.player_entry", entry.getKey(), entry.getValue()),
                    left, y, contentWidth - 54, () -> {
                playerName.setValue(entry.getKey());
                speakerId.setValue(Integer.toString(entry.getValue()));
            });
            button("screen.voicevoxchat.delete", left + contentWidth - 50, y, 50, () -> {
                Map<String, Integer> updated = copySpeakers();
                updated.remove(entry.getKey());
                if (saveSpeakers(updated)) rebuildWidgets();
            });
        }
        var previous = button("screen.voicevoxchat.previous", left, height - 52, 60,
                () -> { page--; rebuildWidgets(); });
        previous.active = page > 0;
        var next = button("screen.voicevoxchat.next", left + contentWidth - 60, height - 52, 60,
                () -> { page++; rebuildWidgets(); });
        next.active = page + 1 < pages;
        button("screen.voicevoxchat.done", left, height - 26, contentWidth, this::onClose);
    }

    /** 大文字・小文字を区別せず編集できるよう、現在の割り当てを可変マップへ複製する。 */
    private Map<String, Integer> copySpeakers() {
        Map<String, Integer> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(mod.config().preferences().playerSpeakers());
        return copy;
    }

    /** 入力したプレイヤー名と話者IDを保存し、不正な入力は画面にエラーとして知らせる。 */
    private void savePlayer() {
        try {
            Map<String, Integer> updated = copySpeakers();
            updated.put(playerName.getValue().strip(), Integer.parseInt(speakerId.getValue()));
            if (saveSpeakers(updated)) rebuildWidgets();
        } catch (NumberFormatException exception) {
            mod.reportError(tr("message.voicevoxchat.invalid_speaker_id"));
        } catch (ValidationException exception) {
            mod.reportError(tr(exception.translationKey(), exception.arguments()));
        } catch (IllegalArgumentException exception) {
            mod.reportError(exception.getMessage());
        }
    }

    /** 他の設定値を維持したままプレイヤー別割り当てを保存し、成功したかを返す。 */
    private boolean saveSpeakers(Map<String, Integer> speakers) {
        var current = mod.config().preferences();
        return mod.save(new Preferences(current.enabled(), current.defaultSpeakerId(), speakers,
                current.randomDefaultSpeaker(), current.chatNotifications()));
    }

    /** ランダム割り当てなどで登録内容が更新された場合、表示部品を作り直す。 */
    @Override
    public void tick() {
        if (displayedSpeakers != mod.config().preferences().playerSpeakers()) rebuildWidgets();
    }

    /** 入力欄の見出し、操作案内またはエラー、および現在のページ番号を描画する。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        label(graphics, tr("screen.voicevoxchat.player_name_example"), left, 33, 0xFFFFFFFF);
        label(graphics, tr("screen.voicevoxchat.speaker_id"), left + contentWidth - 74, 33, 0xFFFFFFFF);
        line(graphics, tr("screen.voicevoxchat.registered_hint"), 100, 0xFFBBBBBB);
        var notice = mod.hasError() ? mod.error() : tr("screen.voicevoxchat.case_insensitive_hint");
        line(graphics, notice, height - 68, mod.hasError() ? 0xFFFF8888 : 0xFFAAAAAA);
        graphics.centeredText(font, tr("screen.voicevoxchat.page", page + 1, pages),
                width / 2, height - 46, 0xFFFFFFFF);
    }
}
