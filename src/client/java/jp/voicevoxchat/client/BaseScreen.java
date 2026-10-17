/*
 * 設定画面群で共有する部品の生成、配置、描画と、親画面へ戻る処理をまとめる。
 */
package jp.voicevoxchat.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Mojang名の画面APIを共通化し、各設定画面の配置処理を簡潔にする。 */
abstract class BaseScreen extends Screen {
    protected final VoicevoxChatClient mod;
    private final Screen parent;
    protected int left;
    protected int contentWidth;

    /** MOD本体と戻り先を保持し、画面タイトルを設定する。 */
    BaseScreen(VoicevoxChatClient mod, Screen parent, String titleKey) {
        super(tr(titleKey));
        this.mod = mod;
        this.parent = parent;
    }

    /** 画面幅に収まる内容領域の幅と、中央配置するための左端を計算する。 */
    protected void layout() {
        contentWidth = Math.min(360, width - 24);
        left = (width - contentWidth) / 2;
    }

    /** 指定位置にボタンを登録し、押下時に指定された処理を実行する。 */
    protected Button button(String translationKey, int x, int y, int width, Runnable action) {
        return button(tr(translationKey), x, y, width, action);
    }

    /** 翻訳済みComponentを使うボタンを指定位置に登録する。 */
    protected Button button(Component message, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(message, button -> action.run())
                .bounds(x, y, width, 20).build());
    }

    /** 最大文字数と初期値を設定した入力欄を画面に登録する。 */
    protected EditBox input(String translationKey, int x, int y, int width, int maxLength, String value) {
        var field = new EditBox(font, x, y, width, 20, tr(translationKey));
        field.setMaxLength(maxLength);
        field.setValue(value);
        return addRenderableWidget(field);
    }

    /** 指定座標と色で文字列を描画する。 */
    protected void label(GuiGraphicsExtractor graphics, Component value, int x, int y, int color) {
        graphics.text(font, value, x, y, color);
    }

    /** 内容領域の幅に収まる長さへ文字列を切り詰め、左端から描画する。 */
    protected void line(GuiGraphicsExtractor graphics, Component value, int y, int color) {
        label(graphics, Component.literal(font.plainSubstrByWidth(value.getString(), contentWidth)), left, y, color);
    }

    /** 翻訳キーと引数から、表示時に現在の言語で解決されるComponentを作る。 */
    protected static Component tr(String key, Object... arguments) {
        return Component.translatable(key, arguments);
    }

    /** ゲーム中はアクセシビリティ設定の強さで背景をぼかし、メニュー中は標準背景を表示する。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (minecraft.level != null && !isInGameUi()) {
            extractBlurredBackground(graphics);
        } else {
            super.extractBackground(graphics, mouseX, mouseY, delta);
        }
    }

    /** 登録済み部品を描画した後、画面上部にタイトルを中央表示する。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
    }

    /** この設定画面を開いてもゲームを一時停止しないことを示す。 */
    @Override
    public boolean isPauseScreen() { return false; }

    /** 現在の画面を閉じ、生成時に指定された親画面へ戻る。 */
    @Override
    public void onClose() { minecraft.setScreenAndShow(parent); }
}
