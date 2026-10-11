/*
 * 接続先Engineから取得した話者ID・話者名・スタイルを検索し、ページごとに表示する画面を提供する。
 */
package jp.voicevoxchat.client;

import jp.voicevoxchat.speech.SpeakerCatalog;
import net.minecraft.class_332;
import net.minecraft.class_4185;
import net.minecraft.class_437;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 通信を話者カタログに任せ、クライアントの更新時に取得済みの結果だけを画面へ反映する。 */
final class SpeakerListScreen extends BaseScreen {
    private static final int LIST_TOP = 84;
    private static final int ROW_HEIGHT = 24;

    private SpeakerCatalog.Snapshot snapshot;
    private List<SpeakerCatalog.Voice> matches = List.of();
    private CompletableFuture<SpeakerCatalog.Snapshot> pending;
    private class_4185 refreshButton;
    private class_4185 previousButton;
    private class_4185 nextButton;
    private String query = "";
    private String refreshError = "";
    private boolean requested;
    private int rows = 1;
    private int page;
    private int pages = 1;

    /** 戻り先と画面タイトルを設定し、共有カタログにある現在の一覧を取得する。 */
    SpeakerListScreen(VoicevoxChatClient mod, class_437 parent) {
        super(mod, parent, "話者ID・名前の一覧");
        snapshot = mod.catalog().snapshot();
    }

    /** 検索欄とページ操作を配置し、初回表示時には話者一覧の再取得を開始する。 */
    @Override
    protected void method_25426() {
        layout();
        rows = Math.max(1, (field_22790 - 166) / ROW_HEIGHT);
        var search = input("ID・名前・Engineで検索", left, 43, contentWidth - 70, 128, query);
        search.method_1863(value -> {
            query = value;
            page = 0;
            filterVoices();
        });
        refreshButton = button("再取得", left + contentWidth - 64, 43, 64, this::refresh);
        previousButton = button("前へ", left, field_22790 - 52, 60, () -> {
            page--;
            updateButtons();
        });
        nextButton = button("次へ", left + contentWidth - 60, field_22790 - 52, 60, () -> {
            page++;
            updateButtons();
        });
        button("戻る", left, field_22790 - 26, contentWidth, this::method_25419);
        filterVoices();
        if (!requested) refresh();
    }

    /** 重複した要求を避けて話者一覧を非同期に再取得し、通信中のボタン状態を更新する。 */
    private void refresh() {
        if (pending != null) return;
        requested = true;
        refreshError = "";
        try {
            pending = mod.catalog().refresh();
        } catch (RuntimeException exception) {
            refreshError = "話者一覧を取得できませんでした。Engineの接続を確認してください。";
        }
        updateButtons();
    }

    /** ID・話者名・スタイル・Engine名で絞り込み、表示順とページ数を計算し直す。 */
    private void filterVoices() {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        matches = snapshot.voices().stream()
                .filter(voice -> needle.isEmpty() || Integer.toString(voice.id()).contains(needle)
                        || voice.speakerName().toLowerCase(Locale.ROOT).contains(needle)
                        || voice.styleName().toLowerCase(Locale.ROOT).contains(needle)
                        || voice.engineName().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(SpeakerCatalog.Voice::engineName)
                        .thenComparingInt(SpeakerCatalog.Voice::id))
                .toList();
        pages = Math.max(1, (matches.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        updateButtons();
    }

    /** 一覧の取得状況と現在ページに合わせ、再取得・前へ・次へボタンを有効化する。 */
    private void updateButtons() {
        if (refreshButton == null) return;
        refreshButton.field_22763 = pending == null;
        previousButton.field_22763 = page > 0;
        nextButton.field_22763 = page + 1 < pages;
    }

    /** 完了した一覧取得や共有カタログの更新を取り込み、検索結果を再計算する。 */
    @Override
    public void method_25393() {
        if (pending != null && pending.isDone()) {
            try {
                snapshot = pending.join();
            } catch (CompletionException | java.util.concurrent.CancellationException exception) {
                refreshError = "話者一覧を取得できませんでした。再取得してください。";
            } finally {
                pending = null;
            }
            filterVoices();
        } else if (pending == null && snapshot != mod.catalog().snapshot()) {
            // Speech or the settings screen can also refresh the shared catalog.
            snapshot = mod.catalog().snapshot();
            filterVoices();
        }
    }

    /** 現在ページの話者情報、件数、取得状況、エラー詳細とページ番号を描画する。 */
    @Override
    public void method_25394(class_332 graphics, int mouseX, int mouseY, float delta) {
        super.method_25394(graphics, mouseX, mouseY, delta);
        line(graphics, "検索（ID・名前・Engine）", 31, 0xFFFFFFFF);
        line(graphics, "話者ID・Engine / 話者名（スタイル）", 70, 0xFFBBBBBB);
        for (int row = 0; row < rows && page * rows + row < matches.size(); row++) {
            var voice = matches.get(page * rows + row);
            int y = LIST_TOP + row * ROW_HEIGHT;
            line(graphics, "ID: " + voice.id() + "  " + voice.engineName(), y, 0xFFBBBBBB);
            String name = voice.speakerName();
            if (!voice.styleName().isBlank()) name += "（" + voice.styleName() + "）";
            line(graphics, name, y + 11, 0xFFFFFFFF);
        }
        if (matches.isEmpty()) {
            String empty = pending != null ? "話者一覧を取得中…"
                    : snapshot.voices().isEmpty() ? "話者を取得できていません。Engineを起動してください。"
                    : "検索条件に一致する話者はいません。";
            line(graphics, empty, LIST_TOP + 11, 0xFFBBBBBB);
        }

        String count = matches.size() + "件 / 取得済み " + snapshot.voices().size() + "件";
        boolean hasError = !refreshError.isBlank() || !snapshot.errors().isEmpty();
        String status = pending != null ? count + " / 更新中…"
                : hasError ? count + " / 接続できないEngineがあります" : count;
        line(graphics, status, field_22790 - 78, hasError ? 0xFFFFCC88 : 0xFFAAAAAA);
        String detail = !refreshError.isBlank() ? refreshError
                : !snapshot.errors().isEmpty() ? String.join(" / ", snapshot.errors())
                : "名前は接続先Engineの情報を表示しています。";
        line(graphics, detail, field_22790 - 66, hasError ? 0xFFFFCC88 : 0xFFAAAAAA);
        graphics.method_25300(field_22793, (page + 1) + " / " + pages,
                field_22789 / 2, field_22790 - 46, 0xFFFFFFFF);
    }
}
