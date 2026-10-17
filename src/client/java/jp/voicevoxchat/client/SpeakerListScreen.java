/*
 * 接続先Engineから取得した話者ID・話者名・スタイルを検索し、ページごとに表示する画面を提供する。
 */
package jp.voicevoxchat.client;

import jp.voicevoxchat.speech.SpeakerCatalog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
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
    private final List<PreviewNameWidget> previewWidgets = new ArrayList<>();
    private String query = "";
    private Component refreshError = Component.empty();
    private Component previewStatus = Component.empty();
    private boolean previewFailed;
    private int previewErrorWaitTicks;
    private boolean requested;
    private int page;
    private int pages = 1;

    /** 戻り先と画面タイトルを設定し、共有カタログにある現在の一覧を取得する。 */
    SpeakerListScreen(VoicevoxChatClient mod, Screen parent) {
        super(mod, parent, "screen.voicevoxchat.speaker_list");
        snapshot = mod.catalog().snapshot();
    }

    /** 検索欄とページ操作を配置し、初回表示時には話者一覧の再取得を開始する。 */
    @Override
    protected void init() {
        layout();
        var search = input("screen.voicevoxchat.speaker_search", left, 43, contentWidth - 70, 128, query);
        search.setResponder(value -> {
            query = value;
            page = 0;
            filterVoices();
        });
        refreshButton = button("screen.voicevoxchat.refresh", left + contentWidth - 64, 43, 64, this::refresh);
        previousButton = button("screen.voicevoxchat.previous", left, height - 52, 60, () -> {
            page--;
            updateButtons();
        });
        nextButton = button("screen.voicevoxchat.next", left + contentWidth - 60, height - 52, 60, () -> {
            page++;
            updateButtons();
        });
        button("screen.voicevoxchat.back", left, height - 26, contentWidth, this::onClose);
        filterVoices();
        if (!requested) refresh();
    }

    /** 重複した要求を避けて話者一覧を非同期に再取得し、通信中のボタン状態を更新する。 */
    private void refresh() {
        if (pending != null) return;
        requested = true;
        refreshError = Component.empty();
        previewStatus = Component.empty();
        previewFailed = false;
        try {
            pending = mod.catalog().refresh();
        } catch (RuntimeException exception) {
            refreshError = tr("message.voicevoxchat.speaker_list_connection_failed");
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
        updatePreviewWidgets();
    }

    /** 現在ページの話者名を、Minecraft標準のクリック可能な文字ウィジェットとして登録し直す。 */
    private void updatePreviewWidgets() {
        for (PreviewNameWidget widget : previewWidgets) removeWidget(widget);
        previewWidgets.clear();
        int columnWidth = (contentWidth - 8) / COLUMN_COUNT;
        int pageStart = page * PAGE_SIZE;
        for (int offset = 0; offset < PAGE_SIZE && pageStart + offset < matches.size(); offset++) {
            SpeakerCatalog.Voice voice = matches.get(pageStart + offset);
            int column = offset % COLUMN_COUNT;
            int row = offset / COLUMN_COUNT;
            int x = left + column * (columnWidth + 8);
            int y = LIST_TOP + row * ROW_HEIGHT + 11;
            Component name = voiceName(voice);
            String visibleName = font.plainSubstrByWidth(name.getString(), columnWidth);
            int textWidth = font.width(visibleName);
            if (textWidth <= 0) continue;
            PreviewNameWidget widget = new PreviewNameWidget(x, y, textWidth, font.lineHeight, name, voice);
            previewWidgets.add(addRenderableWidget(widget));
        }
    }

    /** 完了した一覧取得や共有カタログの更新を取り込み、検索結果を再計算する。 */
    @Override
    public void tick() {
        if (previewErrorWaitTicks > 0) {
            if (mod.hasError()) {
                previewStatus = mod.error();
                previewFailed = true;
                previewErrorWaitTicks = 0;
            } else {
                previewErrorWaitTicks--;
            }
        }
        if (pending != null && pending.isDone()) {
            try {
                snapshot = pending.join();
            } catch (CompletionException | java.util.concurrent.CancellationException exception) {
                refreshError = tr("message.voicevoxchat.speaker_list_retry");
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
        line(graphics, tr("screen.voicevoxchat.speaker_search"), 31, 0xFFFFFFFF);
        line(graphics, tr("screen.voicevoxchat.speaker_list_columns"), 70, 0xFFBBBBBB);
        int columnWidth = (contentWidth - 8) / COLUMN_COUNT;
        int pageStart = page * PAGE_SIZE;
        for (int offset = 0; offset < PAGE_SIZE && pageStart + offset < matches.size(); offset++) {
            var voice = matches.get(pageStart + offset);
            int column = offset % COLUMN_COUNT;
            int row = offset / COLUMN_COUNT;
            int x = left + column * (columnWidth + 8);
            int y = LIST_TOP + row * ROW_HEIGHT;
            columnLine(graphics, tr("screen.voicevoxchat.speaker_entry_id", voice.id(), voice.engineName()),
                    x, columnWidth, y, 0xFFBBBBBB);
        }
        if (matches.isEmpty()) {
            Component empty = pending != null ? tr("screen.voicevoxchat.speaker_list_loading")
                    : snapshot.voices().isEmpty() ? tr("screen.voicevoxchat.speaker_list_empty")
                    : tr("screen.voicevoxchat.speaker_search_empty");
            line(graphics, empty, LIST_TOP + 11, 0xFFBBBBBB);
        }

        boolean hasError = previewFailed || !refreshError.getString().isBlank()
                || !snapshot.errors().isEmpty();
        Component status = pending != null ? tr("screen.voicevoxchat.speaker_list_refreshing")
                : !previewStatus.getString().isBlank() ? previewStatus
                : !refreshError.getString().isBlank() ? refreshError
                : !snapshot.errors().isEmpty() ? tr("message.voicevoxchat.speaker_list_errors",
                        Component.literal(String.join(" / ", snapshot.errors())))
                : Component.empty();
        if (!status.getString().isBlank()) line(graphics, status, height - 78, hasError ? 0xFFFFCC88 : 0xFFAAAAAA);
        line(graphics, tr("screen.voicevoxchat.speaker_count", matches.size(), snapshot.voices().size()),
                height - 66, 0xFFAAAAAA);
        graphics.centeredText(font, tr("screen.voicevoxchat.page", page + 1, pages),
                width / 2, height - 46, 0xFFFFFFFF);
    }

    /** 話者名とスタイル名を画面の言語設定に合わせて整形する。 */
    private Component voiceName(SpeakerCatalog.Voice voice) {
        return voice.styleName().isBlank() ? Component.literal(voice.speakerName())
                : tr("screen.voicevoxchat.speaker_entry_name_style", voice.speakerName(), voice.styleName());
    }

    /** 名前の外観を通常テキストに保ちながら、MinecraftのGUIクリックイベントから試聴を開始する。 */
    private final class PreviewNameWidget extends AbstractWidget {
        private final SpeakerCatalog.Voice voice;

        /** 表示位置・幅・話者情報を保持し、クリック可能な名前ウィジェットを作る。 */
        private PreviewNameWidget(int x, int y, int width, int height, Component name,
                                  SpeakerCatalog.Voice voice) {
            super(x, y, width, height, name);
            this.voice = voice;
        }

        /** 文字だけを描画し、マウスオーバー時にはリンクを示す色へ変える。 */
        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            String visibleName = font.plainSubstrByWidth(message.getString(), width);
            graphics.text(font, visibleName, getX(), getY(), isHovered() ? 0xFFFFFF55 : 0xFFFFFFFF);
        }

        /** クリックされた話者のサンプルをキューへ追加し、失敗理由の表示を準備する。 */
        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            mod.clearError();
            boolean queued = mod.enqueueSpeakerPreview(voice);
            previewStatus = queued ? tr("screen.voicevoxchat.preview_queued")
                    : tr("screen.voicevoxchat.preview_unavailable");
            previewFailed = !queued;
            previewErrorWaitTicks = queued ? 2000 : 0;
        }

        /** スクリーンリーダーへ話者名を通常のボタン項目として伝える。 */
        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** 1列の幅で文字列を切り詰め、指定した列と行に描画する。 */
    private void columnLine(GuiGraphicsExtractor graphics, Component value, int x, int columnWidth, int y, int color) {
        graphics.text(font, font.plainSubstrByWidth(value.getString(), columnWidth), x, y, color);
    }
}
