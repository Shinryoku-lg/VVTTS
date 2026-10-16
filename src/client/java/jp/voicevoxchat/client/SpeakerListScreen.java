/*
 * 接続先Engineから取得した話者ID・話者名・スタイルを検索し、ページごとに表示する画面を提供する。
 */
package jp.voicevoxchat.client;

import jp.voicevoxchat.speech.SpeakerCatalog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 通信を話者カタログに任せ、クライアントの更新時に取得済みの結果だけを画面へ反映する。 */
final class SpeakerListScreen extends BaseScreen {
    private static final int LIST_TOP = 84;
    private static final int ROW_HEIGHT = 22;
    private static final int COLUMN_COUNT = 2;
    private static final int ROWS_PER_COLUMN = 4;
    private static final int PAGE_SIZE = COLUMN_COUNT * ROWS_PER_COLUMN;

    private SpeakerCatalog.Snapshot snapshot;
    private List<SpeakerCatalog.Voice> matches = List.of();
    private CompletableFuture<SpeakerCatalog.Snapshot> pending;
    private Button refreshButton;
    private Button previousButton;
    private Button nextButton;
    private String query = "";
    private String refreshError = "";
    private boolean requested;
    private int page;
    private int pages = 1;

    /** 戻り先と画面タイトルを設定し、共有カタログにある現在の一覧を取得する。 */
    SpeakerListScreen(VoicevoxChatClient mod, Screen parent) {
        super(mod, parent, "話者ID・名前の一覧");
        snapshot = mod.catalog().snapshot();
    }

    /** 検索欄とページ操作を配置し、初回表示時には話者一覧の再取得を開始する。 */
    @Override
    protected void init() {
        layout();
        var search = input("ID・名前・Engineで検索", left, 43, contentWidth - 70, 128, query);
        search.setResponder(value -> {
            query = value;
            page = 0;
            filterVoices();
        });
        refreshButton = button("再取得", left + contentWidth - 64, 43, 64, this::refresh);
        previousButton = button("前へ", left, height - 52, 60, () -> {
            page--;
            updateButtons();
        });
        nextButton = button("次へ", left + contentWidth - 60, height - 52, 60, () -> {
            page++;
            updateButtons();
        });
        button("戻る", left, height - 26, contentWidth, this::onClose);
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
        pages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        updateButtons();
    }

    /** 一覧の取得状況と現在ページに合わせ、再取得・前へ・次へボタンを有効化する。 */
    private void updateButtons() {
        if (refreshButton == null) return;
        refreshButton.active = pending == null;
        previousButton.active = page > 0;
        nextButton.active = page + 1 < pages;
    }

    /** 完了した一覧取得や共有カタログの更新を取り込み、検索結果を再計算する。 */
    @Override
    public void tick() {
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        line(graphics, "検索（ID・名前・Engine）", 31, 0xFFFFFFFF);
        line(graphics, "話者ID・Engine / 話者名（スタイル）", 70, 0xFFBBBBBB);
        int columnWidth = (contentWidth - 8) / COLUMN_COUNT;
        int pageStart = page * PAGE_SIZE;
        for (int offset = 0; offset < PAGE_SIZE && pageStart + offset < matches.size(); offset++) {
            var voice = matches.get(pageStart + offset);
            int column = offset % COLUMN_COUNT;
            int row = offset / COLUMN_COUNT;
            int x = left + column * (columnWidth + 8);
            int y = LIST_TOP + row * ROW_HEIGHT;
            columnLine(graphics, "ID: " + voice.id() + "  " + voice.engineName(), x, columnWidth, y, 0xFFBBBBBB);
            String name = voice.speakerName();
            if (!voice.styleName().isBlank()) name += "（" + voice.styleName() + "）";
            columnLine(graphics, name, x, columnWidth, y + 11, 0xFFFFFFFF);
        }
        if (matches.isEmpty()) {
            String empty = pending != null ? "話者一覧を取得中…"
                    : snapshot.voices().isEmpty() ? "話者を取得できていません。Engineを起動してください。"
                    : "検索条件に一致する話者はいません。";
            line(graphics, empty, LIST_TOP + 11, 0xFFBBBBBB);
        }

        boolean hasError = !refreshError.isBlank() || !snapshot.errors().isEmpty();
        String status = pending != null ? "話者一覧を更新中…"
                : !refreshError.isBlank() ? refreshError
                : !snapshot.errors().isEmpty() ? String.join(" / ", snapshot.errors()) : "";
        if (!status.isBlank()) line(graphics, status, height - 78, hasError ? 0xFFFFCC88 : 0xFFAAAAAA);
        String count = matches.size() + "件 / 取得済み " + snapshot.voices().size() + "件";
        line(graphics, count, height - 66, 0xFFAAAAAA);
        graphics.centeredText(font, (page + 1) + " / " + pages,
                width / 2, height - 46, 0xFFFFFFFF);
    }

    /** 1列の幅で文字列を切り詰め、指定した列と行に描画する。 */
    private void columnLine(GuiGraphicsExtractor graphics, String value, int x, int columnWidth, int y, int color) {
        graphics.text(font, font.plainSubstrByWidth(value, columnWidth), x, y, color);
    }
}
