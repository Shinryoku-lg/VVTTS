/**
 * 設定ファイルの初期生成、永続化、互換性、入力検証と保存失敗時の保護を検証する。
 */
package jp.voicevoxchat.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    @TempDir Path directory;

    /** 実行ファイルのパスを空欄で初期生成し、保存した読み上げ設定を再読み込みできることを検証する。 */
    @Test
    void createsDefaultsAndPersistsPlayerPreferences() throws Exception {
        Path file = directory.resolve("config/voicevoxchat.json");
        ConfigStore store = new ConfigStore(file);
        assertEquals("", store.environment().engineExecutable());
        assertEquals("http://127.0.0.1:50021", store.environment().engineUri().toString());
        assertEquals("", store.environment().nemoEngineExecutable());
        assertEquals("http://127.0.0.1:50121", store.environment().nemoEngineUri().toString());
        assertFalse(store.preferences().randomDefaultSpeaker());
        assertTrue(store.preferences().chatNotifications());
        var generated = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertFalse(generated.get("randomDefaultSpeaker").getAsBoolean());
        assertTrue(generated.get("chatNotifications").getAsBoolean());
        assertEquals("", generated.get("engineExecutable").getAsString());
        assertEquals("", generated.get("nemoEngineExecutable").getAsString());
        store.save(new ConfigStore.Preferences(false, 8, Map.of("Alice", 3)));
        ConfigStore loaded = new ConfigStore(file);
        assertFalse(loaded.preferences().enabled());
        assertTrue(loaded.preferences().chatNotifications());
        assertEquals(3, loaded.preferences().speakerFor("ALICE"));
        assertEquals(8, loaded.preferences().speakerFor("Bob"));
    }

    /** ランダムモードを保存・再読込しても初期話者と個別の固定話者が維持されることを検証する。 */
    @Test
    void randomDefaultModeRoundTripsWithoutChangingFixedSpeakerPreferences() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        ConfigStore store = new ConfigStore(file);
        store.save(new ConfigStore.Preferences(true, 8, Map.of("Alice", 10007), true));
        assertTrue(store.preferences().randomDefaultSpeaker());
        var loaded = new ConfigStore(file);
        assertTrue(loaded.preferences().randomDefaultSpeaker());
        assertTrue(loaded.preferences().chatNotifications());
        assertEquals(8, loaded.preferences().defaultSpeakerId());
        assertEquals(10007, loaded.preferences().speakerFor("Alice"));
        assertEquals(Map.of("Alice", 10007), loaded.preferences().playerSpeakers());

        loaded.save(new ConfigStore.Preferences(true, 8, loaded.preferences().playerSpeakers(), false));
        assertFalse(new ConfigStore(file).preferences().randomDefaultSpeaker());
    }

    /** 新しい設定項目を持たない既存ファイルではランダムモードを無効、通知を有効として扱うことを検証する。 */
    @Test
    void existingConfigWithoutRandomModeKeepsFixedDefault() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        Files.writeString(file, """
                {"engineExecutable":"","engineUrl":"http://127.0.0.1:50021",
                 "nemoEngineExecutable":"","nemoEngineUrl":"http://127.0.0.1:50121",
                 "enabled":true,"defaultSpeakerId":4,"playerSpeakers":{"Alice":10007}}
                """);
        var loaded = new ConfigStore(file);
        assertFalse(loaded.preferences().randomDefaultSpeaker());
        assertTrue(loaded.preferences().chatNotifications());
        assertEquals(4, loaded.preferences().speakerFor("Bob"));
        assertEquals(10007, loaded.preferences().speakerFor("Alice"));
    }

    /** 初回のランダム割り当てを保存し、他の設定とともに次回起動でも利用できることを検証する。 */
    @Test
    void firstRandomAssignmentIsSavedForFutureSessions() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        ConfigStore store = new ConfigStore(file);
        store.save(new ConfigStore.Preferences(false, 4, Map.of("Bob", 3), true, false));

        assertEquals(10007, store.assignRandomSpeakerIfNeeded("Alice", 10007));
        assertEquals(10007, store.preferences().speakerFor("Alice"));
        assertFalse(store.preferences().chatNotifications());
        var loaded = new ConfigStore(file);
        assertEquals(Map.of("Alice", 10007, "Bob", 3), loaded.preferences().playerSpeakers());
        assertFalse(loaded.preferences().enabled());
        assertEquals(4, loaded.preferences().defaultSpeakerId());
        assertTrue(loaded.preferences().randomDefaultSpeaker());
        assertFalse(loaded.preferences().chatNotifications());
    }

    /** 通知を無効にして保存しても環境設定、未知の項目、他の読み上げ設定が維持されることを検証する。 */
    @Test
    void disabledChatNotificationsRoundTripAndPreservePreferencesEnvironmentAndUnknownKeys() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        ConfigStore store = new ConfigStore(file);
        var edited = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        edited.addProperty("engineExecutable", "D:/VOICEVOX/vv-engine/run.exe");
        edited.addProperty("engineUrl", "http://127.0.0.1:50022");
        edited.addProperty("nemoEngineExecutable", "D:/VOICEVOX Nemo/run.exe");
        edited.addProperty("nemoEngineUrl", "http://127.0.0.1:50122");
        edited.addProperty("customSetting", "keep this");
        Files.writeString(file, edited.toString());

        var preferences = new ConfigStore.Preferences(false, 4, Map.of("Alice", 10007, "Bob", 3), true, false);
        store.save(preferences);
        assertFalse(store.preferences().chatNotifications());
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertFalse(saved.get("chatNotifications").getAsBoolean());
        for (String key : new String[]{"engineExecutable", "engineUrl", "nemoEngineExecutable",
                "nemoEngineUrl", "customSetting"}) {
            assertEquals(edited.get(key), saved.get(key), key);
        }
        var loaded = new ConfigStore(file);
        assertEquals(preferences, loaded.preferences());

        loaded.save(new ConfigStore.Preferences(false, 4, preferences.playerSpeakers(), true, true));
        assertTrue(new ConfigStore(file).preferences().chatNotifications());
    }

    /** プレイヤー名の大文字・小文字を区別せず、割り当て済みの話者と保存内容を維持することを検証する。 */
    @Test
    void repeatedRandomAssignmentKeepsFirstIdIgnoringPlayerNameCase() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        ConfigStore store = new ConfigStore(file);
        store.save(new ConfigStore.Preferences(true, 4, Map.of(), true));
        assertEquals(10007, store.assignRandomSpeakerIfNeeded("Alice", 10007));
        String saved = Files.readString(file);

        assertEquals(10007, store.assignRandomSpeakerIfNeeded("Alice", 3));
        assertEquals(10007, store.assignRandomSpeakerIfNeeded("ALICE", 8));
        assertEquals(Map.of("Alice", 10007), store.preferences().playerSpeakers());
        assertEquals(saved, Files.readString(file));
    }

    /** ランダムモードが無効な場合は新規登録せず、固定または初期話者を返すことを検証する。 */
    @Test
    void randomModeOffReturnsExistingOrDefaultIdWithoutAssigning() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        ConfigStore store = new ConfigStore(file);
        store.save(new ConfigStore.Preferences(true, 4, Map.of("Bob", 3), false));
        String saved = Files.readString(file);

        assertEquals(4, store.assignRandomSpeakerIfNeeded("Alice", 10007));
        assertEquals(3, store.assignRandomSpeakerIfNeeded("BOB", 10007));
        assertEquals(Map.of("Bob", 3), store.preferences().playerSpeakers());
        assertEquals(saved, Files.readString(file));
    }

    /** ランダム割り当ての保存時に手動編集した環境設定と未知の項目を保持することを検証する。 */
    @Test
    void randomAssignmentPreservesManualEnvironmentEditsAndUnknownKeys() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        ConfigStore store = new ConfigStore(file);
        store.save(new ConfigStore.Preferences(true, 4, Map.of("Bob", 3), true));
        var edited = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        edited.addProperty("engineExecutable", "D:/VOICEVOX/vv-engine/run.exe");
        edited.addProperty("engineUrl", "http://127.0.0.1:50022");
        edited.addProperty("nemoEngineExecutable", "D:/VOICEVOX Nemo/run.exe");
        edited.addProperty("nemoEngineUrl", "http://127.0.0.1:50122");
        edited.addProperty("customSetting", "keep this");
        Files.writeString(file, edited.toString());

        assertEquals(10007, store.assignRandomSpeakerIfNeeded("Alice", 10007));
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for (String key : new String[]{"engineExecutable", "engineUrl", "nemoEngineExecutable",
                "nemoEngineUrl", "customSetting"}) {
            assertEquals(edited.get(key), saved.get(key), key);
        }
        assertEquals(Map.of("Alice", 10007, "Bob", 3),
                new ConfigStore(file).preferences().playerSpeakers());
    }

    /** 保存に失敗したランダム割り当てを採用せず、現在の設定と壊れたファイルを保持することを検証する。 */
    @Test
    void failedRandomAssignmentKeepsPreviousPreferencesAndDoesNotPublishCandidate() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        ConfigStore store = new ConfigStore(file);
        store.save(new ConfigStore.Preferences(true, 4, Map.of("Bob", 3), true));
        ConfigStore.Preferences before = store.preferences();
        Files.writeString(file, "[");

        assertThrows(IOException.class, () -> store.assignRandomSpeakerIfNeeded("Alice", 10007));
        assertSame(before, store.preferences());
        assertFalse(store.preferences().playerSpeakers().containsKey("Alice"));
        assertEquals(Map.of("Bob", 3), store.preferences().playerSpeakers());
        assertEquals("[", Files.readString(file));
    }

    /** ゲーム内での保存が手動編集内容を保持し、環境設定の変更は明示的な再読込で反映されることを検証する。 */
    @Test
    void inGameSavePreservesManuallyEditedEnvironmentAndUnknownKeys() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        ConfigStore store = new ConfigStore(file);
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.addProperty("engineExecutable", "C:/VOICEVOX 日本語/run.exe");
        json.addProperty("engineUrl", "http://127.0.0.1:50022");
        json.addProperty("nemoEngineExecutable", "C:/VOICEVOX Nemo/run.exe");
        json.addProperty("nemoEngineUrl", "http://127.0.0.1:50122");
        json.addProperty("customSetting", "keep this");
        Files.writeString(file, json.toString());
        store.save(new ConfigStore.Preferences(true, 3, Map.of("Alice", 3), true));
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("C:/VOICEVOX 日本語/run.exe", saved.get("engineExecutable").getAsString());
        assertEquals("http://127.0.0.1:50022", saved.get("engineUrl").getAsString());
        assertEquals("C:/VOICEVOX Nemo/run.exe", saved.get("nemoEngineExecutable").getAsString());
        assertEquals("http://127.0.0.1:50122", saved.get("nemoEngineUrl").getAsString());
        assertEquals("keep this", saved.get("customSetting").getAsString());
        assertTrue(saved.get("randomDefaultSpeaker").getAsBoolean());
        // A live store is a snapshot; explicit reload reads the edited environment.
        assertEquals("http://127.0.0.1:50021", store.environment().engineUri().toString());
        var reloaded = new ConfigStore(file);
        assertEquals("http://127.0.0.1:50022", reloaded.environment().engineUri().toString());
        assertEquals("C:/VOICEVOX Nemo/run.exe", reloaded.environment().nemoEngineExecutable());
        assertEquals("http://127.0.0.1:50122", reloaded.environment().nemoEngineUri().toString());
        assertTrue(reloaded.preferences().randomDefaultSpeaker());
    }

    /** 旧形式の設定に不足項目を補っても、既存の設定や未知の項目を失わないことを検証する。 */
    @Test
    void upgradesExistingConfigWithoutLosingSettings() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        Files.writeString(file, """
                {"engineExecutable":"D:/VOICEVOX/vv-engine/run.exe",
                 "engineUrl":"http://127.0.0.1:50021","enabled":false,
                 "defaultSpeakerId":10007,"playerSpeakers":{"A_leafgreen":10007},"customSetting":42}
                """);
        var loaded = new ConfigStore(file);
        assertFalse(loaded.preferences().enabled());
        assertEquals(10007, loaded.preferences().speakerFor("A_leafgreen"));
        assertEquals("D:/VOICEVOX/vv-engine/run.exe", loaded.environment().engineExecutable());
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(42, saved.get("customSetting").getAsInt());
        assertEquals("", saved.get("nemoEngineExecutable").getAsString());
        assertEquals("http://127.0.0.1:50121", saved.get("nemoEngineUrl").getAsString());
    }

    /** 不正なJSONの読込を拒否し、その内容を上書きしないことを検証する。 */
    @Test
    void malformedConfigIsNeverOverwritten() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        Files.writeString(file, "this is not JSON");
        assertThrows(IOException.class, () -> new ConfigStore(file));
        assertEquals("this is not JSON", Files.readString(file));
    }

    /** 設定保存の失敗時にメモリ上の設定と壊れたファイルを維持することを検証する。 */
    @Test
    void failedSaveKeepsPreviousLivePreferencesAndBrokenFile() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        ConfigStore store = new ConfigStore(file);
        Files.writeString(file, "[");
        assertThrows(IOException.class, () -> store.save(new ConfigStore.Preferences(false, 9, Map.of())));
        assertTrue(store.preferences().enabled());
        assertEquals(3, store.preferences().defaultSpeakerId());
        assertEquals("[", Files.readString(file));
    }

    /** 話者ID、値の型、重複プレイヤー名、接続URLの不正を拒否し、入力ファイルを保護することを検証する。 */
    @Test
    void rejectsInvalidIdsTypesDuplicateNamesAndNonBaseUrls() throws Exception {
        Path file = directory.resolve("voicevoxchat.json");
        for (String invalid : new String[]{
                "{\"defaultSpeakerId\": -1}", "{\"defaultSpeakerId\": 3.5}",
                "{\"defaultSpeakerId\": 2147483648}", "{\"enabled\": \"true\"}",
                "{\"randomDefaultSpeaker\": \"true\"}", "{\"randomDefaultSpeaker\": 1}",
                "{\"randomDefaultSpeaker\": null}", "{\"randomDefaultSpeaker\": []}",
                "{\"chatNotifications\": \"true\"}", "{\"chatNotifications\": 1}",
                "{\"chatNotifications\": null}", "{\"chatNotifications\": []}",
                "{\"chatNotifications\": {}}",
                "{\"playerSpeakers\": {\"Alice\": 3, \"alice\": 4}}",
                "{\"playerSpeakers\": {\"Alice\": null}}",
                "{\"engineUrl\": \"file:///run.exe\"}",
                "{\"engineUrl\": \"http://localhost:50021/api\"}",
                "{\"nemoEngineExecutable\": 42}",
                "{\"nemoEngineUrl\": \"http://localhost:50121/api\"}",
                "{\"nemoEngineUrl\": \"http://user:password@localhost:50121\"}"
        }) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> new ConfigStore(file), invalid);
            assertEquals(invalid, Files.readString(file));
        }
    }
}
