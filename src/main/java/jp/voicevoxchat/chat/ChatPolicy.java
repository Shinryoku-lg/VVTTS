/* 通常のプレイヤーチャットを読み上げ対象として選別し、本文と話者IDを取り出す。 */
package jp.voicevoxchat.chat;

import jp.voicevoxchat.config.ConfigStore.Preferences;

import java.util.Optional;
import java.util.UUID;

/** 表示用のチャット行ではなく、受信メタデータと装飾前の本文から読み上げ可否を判定する。 */
public final class ChatPolicy {
    /** 静的な判定処理だけを利用するため、インスタンス生成を禁止する。 */
    private ChatPolicy() {}

    public record Utterance(String text, int speakerId) {}

    /** 無効・通常チャット以外・非表示・情報不足の発言を除外し、本文と設定上の話者IDを返す。 */
    public static Optional<Utterance> select(Preferences preferences, UUID self, UUID sender,
            String playerName, String body, boolean normalChat, boolean filtered) {
        if (!preferences.enabled() || !normalChat || filtered || self == null || sender == null
                || playerName == null || playerName.isBlank()
                || body == null || body.isBlank()) return Optional.empty();
        return Optional.of(new Utterance(body, preferences.speakerFor(playerName)));
    }
}
