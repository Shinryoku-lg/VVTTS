/* JSON設定を検証して読み書きし、環境設定・読み上げ設定・プレイヤー別話者IDを管理する。 */
package jp.voicevoxchat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** 検証済みの設定を保持する。再読み込み時は新しいインスタンスを作成してから実行環境へ反映する。 */
public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path path;
    private final Environment environment;
    private volatile Preferences preferences;

    public record Environment(String engineExecutable, URI engineUri,
                              String nemoEngineExecutable, URI nemoEngineUri) {}

    public record Preferences(boolean enabled, int defaultSpeakerId, Map<String, Integer> playerSpeakers,
                              boolean randomDefaultSpeaker, boolean chatNotifications) {
        /** ランダム割り当てを無効、チャット通知を有効として読み上げ設定を作成する。 */
        public Preferences(boolean enabled, int defaultSpeakerId, Map<String, Integer> playerSpeakers) {
            this(enabled, defaultSpeakerId, playerSpeakers, false, true);
        }

        /** 指定したランダム設定と、有効なチャット通知を持つ読み上げ設定を作成する。 */
        public Preferences(boolean enabled, int defaultSpeakerId, Map<String, Integer> playerSpeakers,
                           boolean randomDefaultSpeaker) {
            this(enabled, defaultSpeakerId, playerSpeakers, randomDefaultSpeaker, true);
        }

        /** 話者ID・名前・重複を検証し、大文字小文字を区別しない変更不可の割り当て表を作成する。 */
        public Preferences {
            if (defaultSpeakerId < 0) throw new IllegalArgumentException("話者IDは0以上の整数にしてください。");
            TreeMap<String, Integer> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            playerSpeakers.forEach((name, id) -> {
                if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
                    throw new IllegalArgumentException("プレイヤー名は英数字・_の1〜16文字にしてください。");
                }
                if (id == null || id < 0) throw new IllegalArgumentException("話者IDは0以上の整数にしてください。");
                if (copy.put(name, id) != null) throw new IllegalArgumentException("プレイヤー名が重複しています: " + name);
            });
            playerSpeakers = Collections.unmodifiableMap(copy);
        }

        /** プレイヤー別の固定話者IDを返し、未登録または名前がない場合は初期話者IDを返す。 */
        public int speakerFor(String name) {
            return name == null ? defaultSpeakerId : playerSpeakers.getOrDefault(name, defaultSpeakerId);
        }
    }

    /** 設定を読み込み、未作成なら初期設定を生成し、既存設定に不足するNemoの接続項目を追加する。 */
    public ConfigStore(Path path) throws IOException {
        this.path = path.toAbsolutePath();
        if (!Files.exists(this.path)) write(defaultDocument());
        JsonObject json = read();
        try {
            environment = parseEnvironment(json);
            preferences = parsePreferences(json);
        } catch (RuntimeException exception) {
            throw new IOException("voicevoxchat.json の設定が不正です: " + exception.getMessage(), exception);
        }
        // Make the new config-only settings discoverable without replacing existing values.
        if (!json.has("nemoEngineExecutable") || !json.has("nemoEngineUrl")) {
            if (!json.has("nemoEngineExecutable")) json.addProperty("nemoEngineExecutable", "");
            if (!json.has("nemoEngineUrl")) json.addProperty("nemoEngineUrl", "http://127.0.0.1:50121");
            write(json);
        }
    }

    /** 読み込み時に検証したエンジンの実行ファイルと接続先設定を返す。 */
    public Environment environment() { return environment; }
    /** 現在有効な読み上げ設定を返す。 */
    public Preferences preferences() { return preferences; }

    /** 手動編集された環境設定と未知の項目を残して読み上げ設定を保存し、成功後にメモリへ反映する。 */
    public synchronized void save(Preferences updated) throws IOException {
        // Re-read so manual environment edits and unknown keys survive an in-game save.
        JsonObject json = read();
        json.addProperty("enabled", updated.enabled());
        json.addProperty("defaultSpeakerId", updated.defaultSpeakerId());
        json.addProperty("randomDefaultSpeaker", updated.randomDefaultSpeaker());
        json.addProperty("chatNotifications", updated.chatNotifications());
        json.add("playerSpeakers", GSON.toJsonTree(updated.playerSpeakers()));
        write(json);
        preferences = updated;
    }

    /** 未登録かつランダム有効時のみ候補IDを保存する。既存の割り当てを優先し、保存成功後にIDを返す。 */
    public synchronized int assignRandomSpeakerIfNeeded(String playerName, int candidateId) throws IOException {
        Preferences current = preferences;
        Integer assignedId = current.playerSpeakers().get(playerName);
        if (assignedId != null) return assignedId;
        if (!current.randomDefaultSpeaker()) return current.defaultSpeakerId();

        Map<String, Integer> speakers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        speakers.putAll(current.playerSpeakers());
        speakers.put(playerName, candidateId);
        save(new Preferences(current.enabled(), current.defaultSpeakerId(), speakers,
                current.randomDefaultSpeaker(), current.chatNotifications()));
        return candidateId;
    }

    /** UTF-8の設定JSONを読み込み、形式が不正な場合は説明付きの入出力例外を返す。 */
    private JsonObject read() throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("voicevoxchat.json を読み込めません。JSONを確認してください。", exception);
        }
    }

    /** 一時ファイルにUTF-8で書き込み、可能ならアトミックに設定ファイルを置き換える。 */
    private void write(JsonObject json) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "voicevoxchat-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** JSONから通常版とNemoの実行ファイル・接続先を取り出し、省略時は既定値を使用する。 */
    private static Environment parseEnvironment(JsonObject json) {
        return new Environment(string(json, "engineExecutable", ""),
                baseUri(json, "engineUrl", "http://127.0.0.1:50021"),
                string(json, "nemoEngineExecutable", ""),
                baseUri(json, "nemoEngineUrl", "http://127.0.0.1:50121"));
    }

    /** 接続先が認証情報・追加パス・クエリを含まないHTTPまたはHTTPSのURLか検証する。 */
    private static URI baseUri(JsonObject json, String key, String fallback) {
        URI uri = URI.create(string(json, key, fallback));
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException(key + " は http(s)://ホスト:ポート の形式で指定してください。");
        }
        return uri;
    }

    /** JSONから読み上げの有効状態・初期話者・ランダム設定・通知設定・個別割り当てを復元する。 */
    private static Preferences parsePreferences(JsonObject json) {
        boolean enabled = bool(json, "enabled", true);
        boolean randomDefaultSpeaker = bool(json, "randomDefaultSpeaker", false);
        boolean chatNotifications = bool(json, "chatNotifications", true);
        int defaultId = json.has("defaultSpeakerId") ? integer(json.get("defaultSpeakerId")) : 3;
        Map<String, Integer> speakers = new TreeMap<>();
        if (json.has("playerSpeakers")) {
            json.getAsJsonObject("playerSpeakers").entrySet().forEach(entry ->
                    speakers.put(entry.getKey(), integer(entry.getValue())));
        }
        return new Preferences(enabled, defaultId, speakers, randomDefaultSpeaker, chatNotifications);
    }

    /** 真偽値の項目を型検証して取得し、項目がない場合だけ既定値を返す。 */
    private static boolean bool(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) return fallback;
        if (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException(key + " は true または false にしてください。");
        }
        return json.get(key).getAsBoolean();
    }

    /** JSONの数値を、小数部分や整数範囲からのはみ出しを許可せず整数に変換する。 */
    private static int integer(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("話者IDは0以上の整数にしてください。");
        }
        return value.getAsBigDecimal().intValueExact();
    }

    /** 文字列の項目を型検証して取得し、項目がない場合だけ既定値を返す。 */
    private static String string(JsonObject json, String key, String fallback) {
        if (!json.has(key)) return fallback;
        if (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException(key + " は文字列にしてください。");
        }
        return json.get(key).getAsString();
    }

    /** エンジン実行ファイルを空欄とし、既定の接続先・読み上げ設定を含む新規JSONを作成する。 */
    private static JsonObject defaultDocument() {
        JsonObject json = new JsonObject();
        json.addProperty("engineExecutable", "");
        json.addProperty("engineUrl", "http://127.0.0.1:50021");
        json.addProperty("nemoEngineExecutable", "");
        json.addProperty("nemoEngineUrl", "http://127.0.0.1:50121");
        json.addProperty("enabled", true);
        json.addProperty("defaultSpeakerId", 3);
        json.addProperty("randomDefaultSpeaker", false);
        json.addProperty("chatNotifications", true);
        json.add("playerSpeakers", new JsonObject());
        return json;
    }
}
