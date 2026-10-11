/*
 * Mod Menuの設定ボタンから、このMODの設定画面を開けるように連携する。
 */
package jp.voicevoxchat.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.class_437;

/** Mod Menuがエントリポイントを検出したときに読み込む任意の連携機能。 */
public final class VoicevoxModMenu implements ModMenuApi {
    /** 呼び出し元画面を戻り先にした、MOD設定画面の生成処理を返す。 */
    @Override
    public ConfigScreenFactory<class_437> getModConfigScreenFactory() {
        return parent -> VoicevoxChatClient.instance().createSettingsScreen(parent);
    }
}
