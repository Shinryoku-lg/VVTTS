/*
 * Fabricの画面イベントを使って、現在表示中の画面を追跡する。
 */
package jp.voicevoxchat.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.Screen;

/** Minecraftの画面遷移イベントから表示中の画面を管理する。 */
final class ActiveScreenTracker {
    private static volatile Screen currentScreen;

    private ActiveScreenTracker() {}

    /** 画面生成と除去のイベントを登録し、現在画面の追跡を開始する。 */
    static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            currentScreen = screen;
            ScreenEvents.remove(screen).register(removed -> {
                if (currentScreen == removed) currentScreen = null;
            });
        });
    }

    /** 現在表示中として追跡している画面を返す。 */
    static Screen currentScreen() {
        return currentScreen;
    }
}
