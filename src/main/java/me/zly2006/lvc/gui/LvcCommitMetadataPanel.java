package me.zly2006.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import me.zly2006.lvc.LvcProjectService;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

final class LvcCommitMetadataPanel
{
    private static final int SCROLL_STEP = 12;
    private static final int TEXT_PADDING = 6;
    private static final int SCROLLBAR_GUTTER_WIDTH = 16;
    private static final int SCROLLBAR_TRACK_RIGHT_OFFSET = 9;
    private static final int SCROLLBAR_TRACK_WIDTH = 4;
    private static final int TEXT_COLOR = 0xFFB0B0B0;
    private static final int VALUE_COLOR = 0xFFFFFFFF;

    private final GuiLvcProjectManager gui;
    private int scrollOffset;
    private int contentHeight;

    LvcCommitMetadataPanel(GuiLvcProjectManager gui)
    {
        this.gui = gui;
    }

    void draw(GuiContext ctx)
    {
        int x = this.gui.getSidebarX();
        int y = this.gui.getContentTopY();
        int width = this.gui.getSidebarWidth();
        int height = this.gui.getInfoPanelHeight();
        int textX = x + TEXT_PADDING;
        int textY = y + TEXT_PADDING;
        int textWidth = width - TEXT_PADDING - SCROLLBAR_GUTTER_WIDTH;
        int viewportHeight = height - TEXT_PADDING * 2;

        if (width <= 0 || height <= 0)
        {
            return;
        }

        RenderUtils.drawOutlinedBox(ctx, x, y, width, height, 0xA0000000, GuiLvcProjectManager.PANEL_OUTLINE_COLOR);

        if (this.gui.selectedCommit == null)
        {
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.lvc_project.no_commit_selected"), textX, textY, 0xFFAAAAAA, false);
            return;
        }

        List<CommitMetadataLine> lines = this.createLines(textWidth);
        this.contentHeight = this.getContentHeight(lines.size());
        this.clampScroll();

        ctx.pushScissor(new ScreenRectangle(textX, textY, textWidth + 1, viewportHeight));

        int drawY = textY - this.scrollOffset;

        for (CommitMetadataLine line : lines)
        {
            int lineX = textX + line.indent();
            ctx.drawString(ctx.fontRenderer(), line.label(), lineX, drawY, TEXT_COLOR, false);

            if (line.value() != null)
            {
                ctx.drawString(ctx.fontRenderer(), line.value(), lineX + line.valueOffset(), drawY, VALUE_COLOR, false);
            }

            drawY += SCROLL_STEP;
        }

        ctx.popScissor();
        this.drawScrollbar(ctx, x, y, width, height, viewportHeight);
    }

    boolean onMouseScrolled(double mouseX, double mouseY, double verticalAmount)
    {
        if (!this.isMouseOverPanel((int) mouseX, (int) mouseY))
        {
            return false;
        }

        int oldOffset = this.scrollOffset;
        this.scrollOffset -= (int) (verticalAmount * SCROLL_STEP);
        this.clampScroll();
        return oldOffset != this.scrollOffset;
    }

    boolean isMouseOverScrollbar(int mouseX, int mouseY)
    {
        this.refreshContentHeight();
        return this.getMaxScroll(this.getViewportHeight()) > 0 &&
                GuiBase.isMouseOver(mouseX, mouseY, this.getScrollbarTrackX(), this.getScrollbarTrackY(),
                        SCROLLBAR_TRACK_WIDTH, this.getScrollbarTrackHeight());
    }

    void scrollToMouseY(int mouseY)
    {
        this.refreshContentHeight();
        int viewportHeight = this.getViewportHeight();
        int maxScroll = this.getMaxScroll(viewportHeight);
        int trackY = this.getScrollbarTrackY();
        int trackHeight = this.getScrollbarTrackHeight();
        int thumbHeight = this.getScrollbarThumbHeight(viewportHeight, trackHeight);

        this.scrollOffset = LvcScrollbarMath.offsetFromMouseY(mouseY, trackY, trackHeight, thumbHeight, maxScroll);
        this.clampScroll();
    }

    void resetScroll()
    {
        this.scrollOffset = 0;
    }

    void clampScroll()
    {
        int maxScroll = this.getMaxScroll(this.gui.getInfoPanelHeight() - TEXT_PADDING * 2);
        this.scrollOffset = Math.clamp(this.scrollOffset, 0, maxScroll);
    }

    private List<CommitMetadataLine> createLines(int maxWidth)
    {
        LvcProjectService.CommitInfo selectedCommit = this.gui.selectedCommit;
        List<CommitMetadataLine> lines = new ArrayList<>();

        this.addWrappedInlineLine(lines, "litematica.gui.label.lvc_project.info_title", selectedCommit.message(), maxWidth);
        this.addInlineLine(lines, "litematica.gui.label.lvc_project.info_author", selectedCommit.author(), maxWidth);
        this.addInlineLine(lines, "litematica.gui.label.lvc_project.info_date", selectedCommit.time(), maxWidth);
        this.addInlineLine(lines, "litematica.gui.label.lvc_project.info_version", selectedCommit.shortId(), maxWidth);
        this.addDescriptionLines(lines, selectedCommit, maxWidth);
        this.addBlockLines(lines, "litematica.gui.label.lvc_project.info_changes", this.getChangesDisplayText(selectedCommit), maxWidth);

        return lines;
    }

    private void addInlineLine(List<CommitMetadataLine> lines, String labelKey, String value, int maxWidth)
    {
        String label = StringUtils.translate(labelKey) + ": ";
        int labelWidth = this.gui.textWidth(label);
        int valueWidth = Math.max(20, maxWidth - labelWidth);
        lines.add(new CommitMetadataLine(label, LvcGuiText.ellipsizeToWidth(value, valueWidth, this.gui::textWidth), 0, labelWidth));
    }

    private void addWrappedInlineLine(List<CommitMetadataLine> lines, String labelKey, String value, int maxWidth)
    {
        String label = StringUtils.translate(labelKey) + ": ";
        int labelWidth = this.gui.textWidth(label);
        int valueWidth = Math.max(20, maxWidth - labelWidth);
        List<String> wrappedLines = LvcGuiText.wrapTextToWidth(value, valueWidth, this.gui::textWidth);

        if (wrappedLines.isEmpty())
        {
            lines.add(new CommitMetadataLine(label, "", 0, labelWidth));
            return;
        }

        lines.add(new CommitMetadataLine(label, wrappedLines.get(0), 0, labelWidth));

        for (int i = 1; i < wrappedLines.size(); i++)
        {
            lines.add(new CommitMetadataLine("", wrappedLines.get(i), labelWidth, 0));
        }
    }

    private void addBlockLines(List<CommitMetadataLine> lines, String labelKey, String value, int maxWidth)
    {
        int indent = 12;
        lines.add(new CommitMetadataLine(StringUtils.translate(labelKey) + ":", null, 0, 0));

        for (String line : LvcGuiText.wrapTextToWidth(value, Math.max(20, maxWidth - indent), this.gui::textWidth))
        {
            lines.add(new CommitMetadataLine("", line, indent, 0));
        }
    }

    private void addDescriptionLines(List<CommitMetadataLine> lines, LvcProjectService.CommitInfo selectedCommit, int maxWidth)
    {
        String description = selectedCommit.description();

        if (description != null && !description.isBlank())
        {
            this.addBlockLines(lines, "litematica.gui.label.lvc_project.info_description", description, maxWidth);
        }
    }

    private String getChangesDisplayText(LvcProjectService.CommitInfo selectedCommit)
    {
        String changes = selectedCommit.changes();
        return changes == null || changes.isBlank() ? StringUtils.translate("litematica.gui.label.lvc_project.changes_unavailable") : changes;
    }

    private void drawScrollbar(GuiContext ctx, int panelX, int panelY, int panelWidth, int panelHeight, int viewportHeight)
    {
        int maxScroll = this.getMaxScroll(viewportHeight);
        int trackHeight = panelHeight - TEXT_PADDING * 2;

        if (maxScroll <= 0 || viewportHeight <= 0 || trackHeight <= 0)
        {
            return;
        }

        int trackX = this.getScrollbarTrackX();
        int trackY = this.getScrollbarTrackY();
        int thumbHeight = this.getScrollbarThumbHeight(viewportHeight, trackHeight);
        int thumbY = trackY + (trackHeight - thumbHeight) * this.scrollOffset / maxScroll;

        RenderUtils.drawRect(ctx, trackX, trackY, SCROLLBAR_TRACK_WIDTH, trackHeight, 0xA0202020);
        RenderUtils.drawRect(ctx, trackX, thumbY, SCROLLBAR_TRACK_WIDTH, thumbHeight, 0xFFE0E0E0);
    }

    private boolean isMouseOverPanel(int mouseX, int mouseY)
    {
        return GuiBase.isMouseOver(mouseX, mouseY, this.gui.getSidebarX(), this.gui.getContentTopY(), this.gui.getSidebarWidth(), this.gui.getInfoPanelHeight());
    }

    private void refreshContentHeight()
    {
        if (this.gui.selectedCommit == null)
        {
            this.contentHeight = 0;
            return;
        }

        int textWidth = this.gui.getSidebarWidth() - TEXT_PADDING - SCROLLBAR_GUTTER_WIDTH;
        this.contentHeight = this.getContentHeight(this.createLines(textWidth).size());
    }

    private int getViewportHeight()
    {
        return this.gui.getInfoPanelHeight() - TEXT_PADDING * 2;
    }

    private int getScrollbarTrackX()
    {
        return this.gui.getSidebarX() + this.gui.getSidebarWidth() - SCROLLBAR_TRACK_RIGHT_OFFSET;
    }

    private int getScrollbarTrackY()
    {
        return this.gui.getContentTopY() + TEXT_PADDING;
    }

    private int getScrollbarTrackHeight()
    {
        return this.getViewportHeight();
    }

    private int getScrollbarThumbHeight(int viewportHeight, int trackHeight)
    {
        return Math.max(14, trackHeight * viewportHeight / Math.max(viewportHeight, this.contentHeight));
    }

    private int getContentHeight(int lineCount)
    {
        if (lineCount <= 0)
        {
            return 0;
        }

        return (lineCount - 1) * SCROLL_STEP + Math.max(0, this.gui.fontLineHeight() - 1);
    }

    private int getMaxScroll(int viewportHeight)
    {
        return Math.max(0, this.contentHeight - Math.max(0, viewportHeight));
    }

    private record CommitMetadataLine(String label, @Nullable String value, int indent, int valueOffset)
    {
    }
}
