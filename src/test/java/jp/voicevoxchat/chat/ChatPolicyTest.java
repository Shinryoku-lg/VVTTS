/**
 * 通常チャットの読み上げ対象判定、本文の抽出、プレイヤー別の話者選択を検証する。
 */
package jp.voicevoxchat.chat;

import jp.voicevoxchat.config.ConfigStore.Preferences;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatPolicyTest {
    private final UUID self = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final Preferences preferences = new Preferences(true, 8, Map.of("Alice", 3));

    /** 割り当て済みの話者を使用し、プレイヤー名を含めず本文のみを読み上げることを検証する。 */
    @Test
    void aliceUsesAssignedSpeakerAndBodyOnly() {
        var utterance = ChatPolicy.select(preferences, self, alice, "Alice", "こんにちは", true, false).orElseThrow();
        assertEquals("こんにちは", utterance.text());
        assertEquals(3, utterance.speakerId());
    }

    /** 本文中のコロンを保持し、未登録プレイヤーには初期話者を使用することを検証する。 */
    @Test
    void preservesColonTypedInsideBodyAndUsesFallbackSpeaker() {
        var utterance = ChatPolicy.select(preferences, self, alice, "Bob", "時刻: 12:30", true, false).orElseThrow();
        assertEquals("時刻: 12:30", utterance.text());
        assertEquals(8, utterance.speakerId());
    }

    /** 自分の発言も対象に含め、個別設定または初期話者で本文を読み上げることを検証する。 */
    @Test
    void ownChatUsesItsPlayerAssignmentAndReadsTheBody() {
        var ownPreferences = new Preferences(true, 8, Map.of("A_leafgreen", 3));
        var utterance = ChatPolicy.select(ownPreferences, self, self, "A_leafgreen", "あああ", true, false).orElseThrow();
        assertEquals("あああ", utterance.text());
        assertEquals(3, utterance.speakerId());
        var fallback = ChatPolicy.select(preferences, self, self, "A_leafgreen", "こんにちは", true, false).orElseThrow();
        assertEquals(8, fallback.speakerId());
    }

    /** 通常チャット以外、フィルタ済み本文、空白本文、送信者情報不足を対象外にすることを検証する。 */
    @Test
    void rejectsNonChatFilteredTextAndMissingMetadata() {
        assertTrue(ChatPolicy.select(preferences, self, alice, "Alice", "こんにちは", false, false).isEmpty());
        assertTrue(ChatPolicy.select(preferences, self, alice, "Alice", "こんにちは", true, true).isEmpty());
        assertTrue(ChatPolicy.select(preferences, self, null, "Alice", "こんにちは", true, false).isEmpty());
        assertTrue(ChatPolicy.select(preferences, null, alice, "Alice", "こんにちは", true, false).isEmpty());
        assertTrue(ChatPolicy.select(preferences, self, alice, "Alice", "  ", true, false).isEmpty());
    }

    /** 読み上げが無効な間はチャットを受け付けないことを検証する。 */
    @Test
    void disabledSpeechDoesNotAcceptMessages() {
        var disabled = new Preferences(false, 3, Map.of());
        assertTrue(ChatPolicy.select(disabled, self, alice, "Alice", "こんにちは", true, false).isEmpty());
    }
}
