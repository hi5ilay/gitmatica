package me.zly2006.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import me.zly2006.lvc.LvcProjectService;

import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

final class LvcCommitHistoryPanel
{
    static final int SEARCH_HEIGHT = 18;

    private static final int ROW_HEIGHT = 18;
    private static final int TITLE_MAX_WIDTH = 132;
    private static final int AUTHOR_MAX_WIDTH = 72;
    private static final int HASH_SCROLLBAR_GAP = 2;
    private static final int SCROLLBAR_TRACK_RIGHT_OFFSET = 5;
    private static final int SCROLLBAR_TRACK_WIDTH = 4;
    private static final int UNDO_BUTTON_SIZE = 16;
    private static final int UNDO_ICON_SIZE = 12;
    private static final int UNDO_BUTTON_TEXT_GAP = 4;
    private static final int SCROLL_ROWS = 3;

    private final GuiLvcProjectManager gui;
    @Nullable private String pendingFocusedCommitId;
    private String searchQuery = "";
    private int scrollOffset;

    LvcCommitHistoryPanel(GuiLvcProjectManager gui)
    {
        this.gui = gui;
    }

    void draw(GuiContext ctx, int mouseX, int mouseY)
    {
        int panelX = this.gui.getHistoryPanelX();
        int panelY = this.gui.getContentTopY();
        int panelWidth = this.gui.getHistoryPanelWidth();
        int panelHeight = Math.max(0, this.gui.getContentBottomY() - panelY);
        int searchX = panelX + 4;
        int searchY = panelY + 4;
        int searchWidth = panelWidth - 8;
        int x = panelX + 6;
        int y = this.gui.getHistoryStartY();

        RenderUtils.drawOutlinedBox(ctx, panelX, panelY, panelWidth, panelHeight, 0xB0000000, GuiLvcProjectManager.PANEL_OUTLINE_COLOR);
        RenderUtils.drawOutlinedBox(ctx, searchX, searchY, searchWidth, SEARCH_HEIGHT, 0xA0000000, GuiLvcProjectManager.PANEL_OUTLINE_COLOR);
        Icons.FILE_ICON_SEARCH.renderAt(ctx, searchX + 4, searchY + 3, 0, true, false);

        List<LvcProjectService.CommitInfo> visibleHistory = this.filteredHistory();

        if (this.gui.history.isEmpty())
        {
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.lvc_project.history_empty"), x, y, 0xFFAAAAAA, false);
            return;
        }

        if (visibleHistory.isEmpty())
        {
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.lvc_project.history_no_matches"), x, y, 0xFFAAAAAA, false);
            return;
        }

        int maxY = this.gui.getContentBottomY() - ROW_HEIGHT;
        this.clampScroll(visibleHistory);
        int visibleRowCount = this.getVisibleRowCount();
        int hashRightPadding = this.getHashRightPadding();
        int endIndex = Math.min(visibleHistory.size(), this.scrollOffset + visibleRowCount);

        for (int index = this.scrollOffset; index < endIndex; index++)
        {
            if (y > maxY)
            {
                break;
            }

            LvcProjectService.CommitInfo commit = visibleHistory.get(index);
            int rowColor = index % 2 == 0 ? 0xA0303030 : 0xA0101010;
            boolean selected = this.isSelectedCommit(commit);
            int rowX = panelX + 2;
            int rowWidth = panelWidth - 4;

            if (selected || GuiBase.isMouseOver(mouseX, mouseY, rowX, y, rowWidth, ROW_HEIGHT))
            {
                rowColor = 0xA0707070;
            }

            RenderUtils.drawRect(ctx, rowX, y, rowWidth, ROW_HEIGHT, rowColor);

            if (selected)
            {
                RenderUtils.drawOutline(ctx, rowX, y, rowWidth, ROW_HEIGHT, 0xFFE0E0E0);
            }

            int hashRightX = panelX + panelWidth - hashRightPadding;

            if (this.canUndoCommit(commit))
            {
                int buttonX = this.undoButtonX(panelX, panelWidth);
                int buttonY = this.undoButtonY(y);
                this.drawUndoButton(ctx, mouseX, mouseY, buttonX, buttonY);
                hashRightX = buttonX - UNDO_BUTTON_TEXT_GAP;
            }

            this.drawRowText(ctx, commit, x, y + 5, hashRightX);
            y += ROW_HEIGHT;
        }

        this.drawScrollbar(ctx, visibleHistory);
        this.drawUndoTooltip(ctx, mouseX, mouseY);
    }

    boolean onMouseScrolled(double mouseX, double mouseY, double verticalAmount)
    {
        if (!this.isMouseOverList((int) mouseX, (int) mouseY))
        {
            return false;
        }

        int oldOffset = this.scrollOffset;
        this.scroll(verticalAmount);
        return oldOffset != this.scrollOffset;
    }

    boolean isMouseOverScrollbar(int mouseX, int mouseY)
    {
        return this.getMaxScroll(this.filteredHistory()) > 0 &&
                GuiBase.isMouseOver(mouseX, mouseY, this.getScrollbarTrackX(), this.getScrollbarTrackY(),
                        SCROLLBAR_TRACK_WIDTH, this.getScrollbarTrackHeight());
    }

    void scrollToMouseY(int mouseY)
    {
        List<LvcProjectService.CommitInfo> visibleHistory = this.filteredHistory();
        int maxScroll = this.getMaxScroll(visibleHistory);
        int trackY = this.getScrollbarTrackY();
        int trackHeight = this.getScrollbarTrackHeight();
        int thumbHeight = this.getScrollbarThumbHeight(visibleHistory, trackHeight);

        this.scrollOffset = LvcScrollbarMath.offsetFromMouseY(mouseY, trackY, trackHeight, thumbHeight, maxScroll);
        this.clampScroll(visibleHistory);
    }

    @Nullable
    LvcProjectService.CommitInfo getCommitAt(int mouseX, int mouseY)
    {
        int panelX = this.gui.getHistoryPanelX();
        int panelWidth = this.gui.getHistoryPanelWidth();
        int y = this.gui.getHistoryStartY();
        List<LvcProjectService.CommitInfo> visibleHistory = this.filteredHistory();
        this.clampScroll(visibleHistory);

        if (!GuiBase.isMouseOver(mouseX, mouseY, panelX + 2, y, panelWidth - 4, this.getVisibleRowsHeight()))
        {
            return null;
        }

        int index = this.scrollOffset + (mouseY - y) / ROW_HEIGHT;

        if (index < 0 || index >= visibleHistory.size())
        {
            return null;
        }

        return visibleHistory.get(index);
    }

    @Nullable
    LvcProjectService.CommitInfo getUndoCommitAt(int mouseX, int mouseY)
    {
        int panelX = this.gui.getHistoryPanelX();
        int panelWidth = this.gui.getHistoryPanelWidth();
        int y = this.gui.getHistoryStartY();
        List<LvcProjectService.CommitInfo> visibleHistory = this.filteredHistory();
        this.clampScroll(visibleHistory);

        if (!GuiBase.isMouseOver(mouseX, mouseY, panelX + 2, y, panelWidth - 4, this.getVisibleRowsHeight()))
        {
            return null;
        }

        int index = this.scrollOffset + (mouseY - y) / ROW_HEIGHT;

        if (index < 0 || index >= visibleHistory.size())
        {
            return null;
        }

        LvcProjectService.CommitInfo commit = visibleHistory.get(index);

        if (!this.canUndoCommit(commit))
        {
            return null;
        }

        int buttonX = this.undoButtonX(panelX, panelWidth);
        int buttonY = this.undoButtonY(y + (index - this.scrollOffset) * ROW_HEIGHT);
        return GuiBase.isMouseOver(mouseX, mouseY, buttonX, buttonY, UNDO_BUTTON_SIZE, UNDO_BUTTON_SIZE) ? commit : null;
    }

    void focusCommitAfterNextRefresh(String commitId)
    {
        this.pendingFocusedCommitId = commitId;
    }

    boolean focusPendingCommit()
    {
        String commitId = this.pendingFocusedCommitId;
        this.pendingFocusedCommitId = null;

        if (commitId == null)
        {
            return false;
        }

        if (this.selectVisibleCommit(commitId))
        {
            return true;
        }

        String previousSearchQuery = this.searchQuery;
        this.searchQuery = "";

        if (this.selectVisibleCommit(commitId))
        {
            return true;
        }

        this.searchQuery = previousSearchQuery;
        return false;
    }

    void retainSelectedVisibleCommit()
    {
        List<LvcProjectService.CommitInfo> visibleHistory = this.filteredHistory();

        if (visibleHistory.isEmpty())
        {
            this.gui.selectedCommit = null;
            this.scrollOffset = 0;
            this.gui.commitMetadataPanel.resetScroll();
            return;
        }

        if (this.gui.selectedCommit != null)
        {
            for (int index = 0; index < visibleHistory.size(); index++)
            {
                LvcProjectService.CommitInfo commit = visibleHistory.get(index);

                if (commit.id().equals(this.gui.selectedCommit.id()))
                {
                    this.gui.selectedCommit = commit;
                    this.ensureRowVisible(index, visibleHistory);
                    this.gui.commitMetadataPanel.clampScroll();
                    return;
                }
            }
        }

        this.gui.selectedCommit = visibleHistory.get(0);
        this.scrollOffset = 0;
        this.gui.commitMetadataPanel.resetScroll();
    }

    void clearAfterHistoryLoadFailure()
    {
        this.pendingFocusedCommitId = null;
        this.scrollOffset = 0;
    }

    String searchQuery()
    {
        return this.searchQuery;
    }

    void setSearchQuery(String searchQuery)
    {
        this.searchQuery = searchQuery == null ? "" : searchQuery;
    }

    private void drawRowText(GuiContext ctx, LvcProjectService.CommitInfo commit, int x, int y, int hashRightX)
    {
        int hashWidth = this.gui.textWidth(commit.shortId());
        int hashX = hashRightX - hashWidth;
        int availableTextWidth = Math.max(20, hashX - x - 6);
        String title = commit.message();
        String author = " " + commit.author();
        int authorMaxWidth = Math.min(AUTHOR_MAX_WIDTH, availableTextWidth);
        String clippedAuthor = LvcGuiText.ellipsizeToWidth(author, authorMaxWidth, this.gui::textWidth);
        int clippedAuthorWidth = this.gui.textWidth(clippedAuthor);
        int titleMaxWidth = Math.min(TITLE_MAX_WIDTH, Math.max(0, availableTextWidth - clippedAuthorWidth));
        String clippedTitle = titleMaxWidth > 0 ? LvcGuiText.ellipsizeToWidth(title, titleMaxWidth, this.gui::textWidth) : "";
        int clippedTitleWidth = this.gui.textWidth(clippedTitle);

        ctx.drawString(ctx.fontRenderer(), clippedTitle, x, y, 0xFFFFFFFF, false);
        ctx.drawString(ctx.fontRenderer(), clippedAuthor, x + clippedTitleWidth, y, 0xFF7A7A7A, false);
        ctx.drawString(ctx.fontRenderer(), commit.shortId(), hashX, y, 0xFFFFD36A, false);
    }

    private List<LvcProjectService.CommitInfo> filteredHistory()
    {
        String query = this.searchQuery.trim().toLowerCase(Locale.ROOT);

        if (query.isEmpty())
        {
            return this.gui.history;
        }

        String[] tokens = query.split("\\s+");
        List<LvcProjectService.CommitInfo> commits = new ArrayList<>();

        for (LvcProjectService.CommitInfo commit : this.gui.history)
        {
            if (this.commitMatchesSearch(commit, tokens))
            {
                commits.add(commit);
            }
        }

        return commits;
    }

    private boolean commitMatchesSearch(LvcProjectService.CommitInfo commit, String[] tokens)
    {
        String textHaystack = (commit.message() + "\n" +
                commit.description() + "\n" +
                commit.author()).toLowerCase(Locale.ROOT);
        String fullHash = commit.id().toLowerCase(Locale.ROOT);
        String shortHash = commit.shortId().toLowerCase(Locale.ROOT);

        for (String token : tokens)
        {
            if (!textHaystack.contains(token) && !this.commitHashMatchesSearchToken(token, shortHash, fullHash))
            {
                return false;
            }
        }

        return true;
    }

    private boolean commitHashMatchesSearchToken(String token, String shortHash, String fullHash)
    {
        return shortHash.startsWith(token) || fullHash.startsWith(token);
    }

    private void scroll(double verticalAmount)
    {
        if (verticalAmount == 0)
        {
            return;
        }

        int rows = Math.max(1, (int) Math.ceil(Math.abs(verticalAmount))) * SCROLL_ROWS;
        this.scrollOffset += verticalAmount > 0 ? -rows : rows;
        this.clampScroll(this.filteredHistory());
    }

    private void drawScrollbar(GuiContext ctx, List<LvcProjectService.CommitInfo> visibleHistory)
    {
        int maxScroll = this.getMaxScroll(visibleHistory);

        if (maxScroll <= 0)
        {
            return;
        }

        int trackX = this.getScrollbarTrackX();
        int trackY = this.getScrollbarTrackY();
        int trackHeight = this.getScrollbarTrackHeight();
        int thumbHeight = this.getScrollbarThumbHeight(visibleHistory, trackHeight);
        int thumbY = trackY + (trackHeight - thumbHeight) * this.scrollOffset / maxScroll;

        RenderUtils.drawRect(ctx, trackX, trackY, SCROLLBAR_TRACK_WIDTH, trackHeight, 0xA0202020);
        RenderUtils.drawRect(ctx, trackX, thumbY, SCROLLBAR_TRACK_WIDTH, thumbHeight, 0xFFE0E0E0);
    }

    private int getScrollbarTrackX()
    {
        return this.gui.getHistoryPanelX() + this.gui.getHistoryPanelWidth() - SCROLLBAR_TRACK_RIGHT_OFFSET;
    }

    private int getScrollbarTrackY()
    {
        return this.gui.getHistoryStartY();
    }

    private int getScrollbarTrackHeight()
    {
        return this.getViewportHeight();
    }

    private int getScrollbarThumbHeight(List<LvcProjectService.CommitInfo> visibleHistory, int trackHeight)
    {
        int visibleRows = this.getVisibleRowCount();
        return Math.max(14, trackHeight * visibleRows / Math.max(visibleRows, visibleHistory.size()));
    }

    private int getHashRightPadding()
    {
        return SCROLLBAR_TRACK_RIGHT_OFFSET + SCROLLBAR_TRACK_WIDTH + HASH_SCROLLBAR_GAP;
    }

    private int undoButtonX(int panelX, int panelWidth)
    {
        return panelX + panelWidth - this.getHashRightPadding() - UNDO_BUTTON_SIZE;
    }

    private int undoButtonY(int rowY)
    {
        return rowY + (ROW_HEIGHT - UNDO_BUTTON_SIZE) / 2;
    }

    private void drawUndoButton(GuiContext ctx, int mouseX, int mouseY, int buttonX, int buttonY)
    {
        ButtonGeneric button = new ButtonGeneric(buttonX, buttonY, UNDO_BUTTON_SIZE, UNDO_BUTTON_SIZE, "");
        button.render(ctx, mouseX, mouseY, false);

        int iconX = buttonX + (UNDO_BUTTON_SIZE - UNDO_ICON_SIZE) / 2;
        int iconY = buttonY + (UNDO_BUTTON_SIZE - UNDO_ICON_SIZE) / 2;
        Icons.GITMATICA_UNDO.renderScaledAt(ctx, iconX, iconY, UNDO_ICON_SIZE, UNDO_ICON_SIZE, 0);
    }

    private void drawUndoTooltip(GuiContext ctx, int mouseX, int mouseY)
    {
        if (this.getUndoCommitAt(mouseX, mouseY) != null)
        {
            RenderUtils.drawHoverText(ctx, mouseX, mouseY,
                    List.of(StringUtils.translate("litematica.gui.label.lvc_project.delete_latest_version")));
        }
    }

    private boolean isMouseOverList(int mouseX, int mouseY)
    {
        return GuiBase.isMouseOver(mouseX, mouseY, this.gui.getHistoryPanelX() + 2, this.gui.getHistoryStartY(),
                this.gui.getHistoryPanelWidth() - 4, this.getViewportHeight());
    }

    private void clampScroll(List<LvcProjectService.CommitInfo> visibleHistory)
    {
        int maxScroll = this.getMaxScroll(visibleHistory);
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, maxScroll));
    }

    private int getMaxScroll(List<LvcProjectService.CommitInfo> visibleHistory)
    {
        int visibleRows = this.getVisibleRowCount();
        return visibleRows <= 0 ? 0 : Math.max(0, visibleHistory.size() - visibleRows);
    }

    private int getVisibleRowCount()
    {
        return this.getViewportHeight() / ROW_HEIGHT;
    }

    private int getVisibleRowsHeight()
    {
        return this.getVisibleRowCount() * ROW_HEIGHT;
    }

    private int getViewportHeight()
    {
        return Math.max(0, this.gui.getContentBottomY() - this.gui.getHistoryStartY());
    }

    private boolean isSelectedCommit(LvcProjectService.CommitInfo commit)
    {
        return this.gui.selectedCommit != null && this.gui.selectedCommit.id().equals(commit.id());
    }

    private boolean canUndoCommit(LvcProjectService.CommitInfo commit)
    {
        return !this.gui.detachedHead &&
                this.gui.history.size() > 1 &&
                !this.gui.history.isEmpty() &&
                this.gui.history.get(0).id().equals(commit.id());
    }

    private boolean selectVisibleCommit(String commitId)
    {
        List<LvcProjectService.CommitInfo> visibleHistory = this.filteredHistory();

        for (int index = 0; index < visibleHistory.size(); index++)
        {
            LvcProjectService.CommitInfo commit = visibleHistory.get(index);

            if (commit.id().equals(commitId))
            {
                this.gui.selectedCommit = commit;
                this.ensureRowVisible(index, visibleHistory);
                this.gui.commitMetadataPanel.resetScroll();
                return true;
            }
        }

        return false;
    }

    private void ensureRowVisible(int rowIndex, List<LvcProjectService.CommitInfo> visibleHistory)
    {
        int visibleRows = this.getVisibleRowCount();

        if (visibleRows <= 0)
        {
            this.scrollOffset = 0;
            return;
        }

        if (rowIndex < this.scrollOffset)
        {
            this.scrollOffset = rowIndex;
        }
        else if (rowIndex >= this.scrollOffset + visibleRows)
        {
            this.scrollOffset = rowIndex - visibleRows + 1;
        }

        this.clampScroll(visibleHistory);
    }
}
