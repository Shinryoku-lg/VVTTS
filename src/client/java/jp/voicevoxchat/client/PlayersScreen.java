/*
 * プレイヤー名ごとの話者IDを一覧表示し、追加・更新・削除してconfigへ保存する画面を提供する。
 */
package jp.voicevoxchat.client;

import jp.voicevoxchat.config.ConfigStore.Preferences;
import net.minecraft.class_332;
import net.minecraft.class_342;
import net.minecraft.class_437;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

final class PlayersScreen extends BaseScreen {
    private class_342 playerName;
    private class_342 speakerId;
    private int page;
    private int pages;
    private Map<String, Integer> displayedSpeakers = Map.of();

    /** プレイヤー別設定画面のタイトルと戻り先を設定する。 */
    PlayersScreen(VoicevoxChatClient mod, class_437 parent) {
        super(mod, parent, "プレイヤー別の話者ID");
    }

    /** 入力値を維持して編集欄を作り、登録済み話者のページと操作ボタンを配置する。 */
    @Override
    protected void method_25426() {
        layout();
        playerName = input("プレイヤー名", left, 46, contentWidth - 80, 16,
                playerName == null ? "" : playerName.method_1882());
        speakerId = input("話者ID", left + contentWidth - 74, 46, 74, 10,
                speakerId == null ? "3" : speakerId.method_1882());
        button("追加・更新して保存", left, 72, contentWidth, this::savePlayer);

        displayedSpeakers = mod.config().preferences().playerSpeakers();
        var entries = new ArrayList<>(displayedSpeakers.entrySet());
        int rows = Math.max(1, (field_22790 - 188) / 22);
        pages = Math.max(1, (entries.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int row = 0; row < rows && page * rows + row < entries.size(); row++) {
            var entry = entries.get(page * rows + row);
            int y = 112 + row * 22;
            button(entry.getKey() + " → " + entry.getValue(), left, y, contentWidth - 54, () -> {
                playerName.method_1852(entry.getKey());
                speakerId.method_1852(Integer.toString(entry.getValue()));
            });
            button("削除", left + contentWidth - 50, y, 50, () -> {
                Map<String, Integer> updated = copySpeakers();
                updated.remove(entry.getKey());
                if (saveSpeakers(updated)) method_41843();
            });
        }
        var previous = button("前へ", left, field_22790 - 52, 60, () -> { page--; method_41843(); });
        previous.field_22763 = page > 0;
        var next = button("次へ", left + contentWidth - 60, field_22790 - 52, 60, () -> { page++; method_41843(); });
        next.field_22763 = page + 1 < pages;
        button("完了", left, field_22790 - 26, contentWidth, this::method_25419);
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
            updated.put(playerName.method_1882().strip(), Integer.parseInt(speakerId.method_1882()));
            if (saveSpeakers(updated)) method_41843();
        } catch (NumberFormatException exception) {
            mod.reportError("話者IDは0以上の整数にしてください。");
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
    public void method_25393() {
        if (displayedSpeakers != mod.config().preferences().playerSpeakers()) method_41843();
    }

    /** 入力欄の見出し、操作案内またはエラー、および現在のページ番号を描画する。 */
    @Override
    public void method_25394(class_332 graphics, int mouseX, int mouseY, float delta) {
        super.method_25394(graphics, mouseX, mouseY, delta);
        label(graphics, "プレイヤー名（例: Alice）", left, 33, 0xFFFFFFFF);
        label(graphics, "話者ID", left + contentWidth - 74, 33, 0xFFFFFFFF);
        line(graphics, "登録済み（選択すると編集できます）", 100, 0xFFBBBBBB);
        String notice = mod.error().isBlank() ? "大文字・小文字は区別しません" : mod.error();
        line(graphics, notice, field_22790 - 68, mod.error().isBlank() ? 0xFFAAAAAA : 0xFFFF8888);
        graphics.method_25300(field_22793, (page + 1) + " / " + pages,
                field_22789 / 2, field_22790 - 46, 0xFFFFFFFF);
    }
}
