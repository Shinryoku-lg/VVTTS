/*
 * 設定画面群で共有する部品の生成、配置、描画と、親画面へ戻る処理をまとめる。
 */
package jp.voicevoxchat.client;

import net.minecraft.class_2561;
import net.minecraft.class_332;
import net.minecraft.class_342;
import net.minecraft.class_4185;
import net.minecraft.class_437;

/** Intermediary名の画面APIを共通化し、各設定画面の配置処理を簡潔にする。 */
abstract class BaseScreen extends class_437 {
    protected final VoicevoxChatClient mod;
    private final class_437 parent;
    protected int left;
    protected int contentWidth;

    /** MOD本体と戻り先を保持し、画面タイトルを設定する。 */
    BaseScreen(VoicevoxChatClient mod, class_437 parent, String title) {
        super(text(title));
        this.mod = mod;
        this.parent = parent;
    }

    /** 画面幅に収まる内容領域の幅と、中央配置するための左端を計算する。 */
    protected void layout() {
        contentWidth = Math.min(360, field_22789 - 24);
        left = (field_22789 - contentWidth) / 2;
    }

    /** 指定位置にボタンを登録し、押下時に指定された処理を実行する。 */
    protected class_4185 button(String label, int x, int y, int width, Runnable action) {
        return method_37063(class_4185.method_46430(text(label), button -> action.run())
                .method_46434(x, y, width, 20).method_46431());
    }

    /** 最大文字数と初期値を設定した入力欄を画面に登録する。 */
    protected class_342 input(String label, int x, int y, int width, int maxLength, String value) {
        var field = new class_342(field_22793, x, y, width, 20, text(label));
        field.method_1880(maxLength);
        field.method_1852(value);
        return method_37063(field);
    }

    /** 指定座標と色で文字列を描画する。 */
    protected void label(class_332 graphics, String value, int x, int y, int color) {
        graphics.method_25303(field_22793, value, x, y, color);
    }

    /** 内容領域の幅に収まる長さへ文字列を切り詰め、左端から描画する。 */
    protected void line(class_332 graphics, String value, int y, int color) {
        label(graphics, field_22793.method_27523(value, contentWidth), left, y, color);
    }

    /** 文字列を翻訳キーとして扱わず、表示用のリテラルテキストへ変換する。 */
    protected static class_2561 text(String value) { return class_2561.method_43470(value); }

    /** 背景と登録済み部品を描画した後、画面上部にタイトルを中央表示する。 */
    @Override
    public void method_25394(class_332 graphics, int mouseX, int mouseY, float delta) {
        method_25420(graphics, mouseX, mouseY, delta);
        super.method_25394(graphics, mouseX, mouseY, delta);
        graphics.method_27534(field_22793, field_22785, field_22789 / 2, 12, 0xFFFFFFFF);
    }

    /** この設定画面を開いてもゲームを一時停止しないことを示す。 */
    @Override
    public boolean method_25421() { return false; }

    /** 現在の画面を閉じ、生成時に指定された親画面へ戻る。 */
    @Override
    public void method_25419() { field_22787.method_1507(parent); }
}
