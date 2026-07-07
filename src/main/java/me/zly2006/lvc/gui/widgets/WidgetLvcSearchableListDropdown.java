package me.zly2006.lvc.gui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import me.zly2006.lvc.util.LvcGuiTextFields;

import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.KeyCodes;

public abstract class WidgetLvcSearchableListDropdown<T> extends WidgetBase
{
    private static final int ROW_HEIGHT = 18;
    private static final int SCROLLBAR_WIDTH = 4;
    private static final int ICON_PADDING = 2;
    private static final int ICON_TEXT_GAP = 4;
    private static final int ARROW_PADDING = 5;
    private static final int TOOLTIP_MAX_WIDTH = 220;
    private static final int TOOLTIP_SCREEN_PADDING = 32;

    private final List<T> items = new ArrayList<>();
    private final List<T> filteredItems = new ArrayList<>();
    private final GuiTextFieldGeneric searchField;
    private final Consumer<T> selectionConsumer;
    private final int maxVisibleRows;
    @Nullable private T selectedItem;
    private boolean open;
    private boolean draggingScrollbar;
    private int scrollOffset;

    protected WidgetLvcSearchableListDropdown(int x, int y, int width, int height, int maxVisibleRows,
                                              List<T> items, @Nullable T selectedItem, Consumer<T> selectionConsumer)
    {
        super(x, y, width, height);

        this.maxVisibleRows = Math.max(1, maxVisibleRows);
        this.selectionConsumer = selectionConsumer;
        this.searchField = new GuiTextFieldGeneric(this.getSearchTextX(), this.getSearchTextY(), this.getSearchTextWidth(), 14, this.textRenderer);
        this.searchField.setBordered(false);
        this.searchField.setMaxLength(128);
        this.searchField.setTextColor(0xFFFFFFFF);
        this.searchField.setTextColorUneditable(0xFFAAAAAA);
        this.setItems(items);
        this.setSelectedItem(selectedItem);
    }

    public void setItems(List<T> items)
    {
        this.items.clear();

        for (T item : items)
        {
            if (this.isValidItem(item) && !this.items.contains(item))
            {
                this.items.add(item);
            }
        }

        this.updateFilteredItems();
    }

    public void setSelectedItem(@Nullable T selectedItem)
    {
        this.selectedItem = selectedItem;
    }

    @Nullable
    protected T selectedItem()
    {
        return this.selectedItem;
    }

    public boolean isOpen()
    {
        return this.open;
    }

    public boolean isDraggingScrollbar()
    {
        return this.draggingScrollbar;
    }

    public void close()
    {
        this.setOpen(false);
    }

    @Override
    public void setPosition(int x, int y)
    {
        super.setPosition(x, y);
        LvcGuiTextFields.setPosition(this.searchField, this.getSearchTextX(), this.getSearchTextY());
    }

    @Override
    public boolean isMouseOver(int mouseX, int mouseY)
    {
        int bottom = this.open ? this.y + this.height + this.getOpenHeight() : this.y + this.height;
        return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < bottom;
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        int mouseX = (int) click.x();
        int mouseY = (int) click.y();

        if (this.open && this.isMouseOverSearch(mouseX, mouseY))
        {
            this.searchField.mouseClicked(click, doubleClick);
            return true;
        }

        if (this.isMouseOverButton(mouseX, mouseY))
        {
            this.setOpen(!this.open);
            return true;
        }

        if (!this.open)
        {
            return false;
        }

        if (click.input() == 0 && this.isMouseOverScrollbar(mouseX, mouseY))
        {
            this.scrollToMouseY(mouseY);
            this.draggingScrollbar = true;
            return true;
        }

        int itemIndex = this.getItemIndexAt(mouseY);

        if (itemIndex >= 0)
        {
            T item = this.filteredItems.get(itemIndex);
            this.selectedItem = item;
            this.setOpen(false);
            this.selectionConsumer.accept(item);
            return true;
        }

        return true;
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragXAmount, double dragYAmount)
    {
        if (this.draggingScrollbar)
        {
            this.scrollToMouseY((int) click.y());
            return true;
        }

        return super.onMouseDragged(click, dragXAmount, dragYAmount);
    }

    @Override
    public void onMouseReleasedImpl(MouseButtonEvent click)
    {
        if (click.input() == 0)
        {
            this.draggingScrollbar = false;
        }
    }

    @Override
    public boolean onMouseScrolledImpl(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (!this.open)
        {
            return false;
        }

        if (verticalAmount > 0)
        {
            this.scrollOffset--;
        }
        else if (verticalAmount < 0)
        {
            this.scrollOffset++;
        }

        this.clampScrollOffset();
        return true;
    }

    @Override
    protected boolean onKeyTypedImpl(KeyEvent input)
    {
        if (!this.open)
        {
            return false;
        }

        if (input.key() == KeyCodes.KEY_ESCAPE)
        {
            this.setOpen(false);
            return true;
        }

        String previousValue = this.searchField.getValueWrapper();
        boolean handled = this.searchField.keyPressed(input);

        if (!this.searchField.getValueWrapper().equals(previousValue))
        {
            this.updateFilteredItems();
        }

        return handled;
    }

    @Override
    protected boolean onCharTypedImpl(CharacterEvent input)
    {
        if (!this.open)
        {
            return false;
        }

        String previousValue = this.searchField.getValueWrapper();
        boolean handled = this.searchField.charTyped(input);

        if (!this.searchField.getValueWrapper().equals(previousValue))
        {
            this.updateFilteredItems();
        }

        return handled;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        super.render(ctx, mouseX, mouseY, selected);
        this.renderClosedValue(ctx);

        if (this.open)
        {
            this.renderOpenPanel(ctx, mouseX, mouseY);
        }
    }

    @Override
    public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        List<String> lines = this.closedTooltipLines();

        if (!this.open && !lines.isEmpty() && this.isMouseOverButton(mouseX, mouseY))
        {
            RenderUtils.drawHoverText(ctx, mouseX, mouseY, lines);
        }
    }

    protected boolean isValidItem(@Nullable T item)
    {
        return item != null;
    }

    protected boolean itemMatchesFilter(T item, String filter)
    {
        return filter.isEmpty() || this.itemLabel(item).toLowerCase(Locale.ROOT).contains(filter);
    }

    protected abstract String itemLabel(T item);

    protected abstract String emptyListText();

    protected abstract String noSelectionText();

    protected abstract void renderClosedIcon(GuiContext ctx, int x, int y);

    protected abstract void renderRowIcon(GuiContext ctx, T item, int x, int y);

    protected List<String> closedTooltipLines()
    {
        return List.of();
    }

    protected int iconSize()
    {
        return 14;
    }

    private void renderClosedValue(GuiContext ctx)
    {
        RenderUtils.drawOutlinedBox(ctx, this.x, this.y, this.width, this.height, 0xFF101010, 0xFFC0C0C0);

        T selected = this.selectedItem;
        String label = selected == null ? this.noSelectionText() : this.itemLabel(selected);
        int textX = this.getClosedTextX();
        int textWidth = Math.max(8, this.getArrowX() - textX - ICON_TEXT_GAP);
        int textY = this.y + (this.height - this.fontHeight) / 2;

        if (this.open)
        {
            Icons.FILE_ICON_SEARCH.renderAt(ctx, this.x + ICON_PADDING + 1,
                    this.y + (this.height - Icons.FILE_ICON_SEARCH.getHeight()) / 2, 0, true, false);
            this.searchField.extractRenderState(ctx.getGuiGraphics(), 0, 0, 0f);
            Icons.ARROW_UP.renderAt(ctx, this.getArrowX(), this.y + (this.height - Icons.ARROW_UP.getHeight()) / 2, 0, true, false);
        }
        else
        {
            this.renderClosedIcon(ctx, this.x + ICON_PADDING, this.y + (this.height - this.iconSize()) / 2);
            this.drawString(ctx, textX, textY, 0xFFE0E0E0, this.ellipsizeToWidth(label, textWidth));
            Icons.ARROW_DOWN.renderAt(ctx, this.getArrowX(), this.y + (this.height - Icons.ARROW_DOWN.getHeight()) / 2, 0, true, false);
        }
    }

    private void renderOpenPanel(GuiContext ctx, int mouseX, int mouseY)
    {
        int panelY = this.y + this.height;
        int panelHeight = this.getOpenHeight();

        RenderUtils.drawOutlinedBox(ctx, this.x, panelY, this.width, panelHeight, 0xF0101010, 0xFFE0E0E0);
        this.renderRows(ctx, mouseX, mouseY);
        this.renderScrollbar(ctx);
        this.renderHoveredItemTooltip(ctx, mouseX, mouseY);
    }

    private void renderRows(GuiContext ctx, int mouseX, int mouseY)
    {
        int y = this.getListY();
        int visibleRows = this.getVisibleRowCount();

        if (this.filteredItems.isEmpty())
        {
            this.drawString(ctx, this.getRowTextX(), y + 5, 0xFFAAAAAA, this.emptyListText());
            return;
        }

        int endIndex = Math.min(this.filteredItems.size(), this.scrollOffset + visibleRows);

        for (int index = this.scrollOffset; index < endIndex; index++)
        {
            T item = this.filteredItems.get(index);
            boolean selected = item.equals(this.selectedItem);
            boolean hovered = this.isMouseOverRow(mouseX, mouseY, y);
            int rowColor = (index & 1) == 0 ? 0xA0303030 : 0xA0101010;
            int rowX = this.x + 1;
            int rowWidth = Math.max(1, this.width - 2);

            if (selected || hovered)
            {
                rowColor = 0xA0707070;
            }

            RenderUtils.drawRect(ctx, rowX, y, rowWidth, ROW_HEIGHT, rowColor);

            int textX = this.getRowTextX(rowX);
            int textWidth = Math.max(8, this.getRowTextRightX(rowX, rowWidth) - textX);

            this.renderRowIcon(ctx, item, rowX + ICON_PADDING, y + (ROW_HEIGHT - this.iconSize()) / 2);
            this.drawString(ctx, textX, y + 5, 0xFFFFFFFF, this.ellipsizeToWidth(this.itemLabel(item), textWidth));
            y += ROW_HEIGHT;
        }
    }

    private void renderScrollbar(GuiContext ctx)
    {
        int maxScroll = this.getMaxScroll();

        if (maxScroll <= 0)
        {
            return;
        }

        int trackX = this.getScrollbarTrackX();
        int trackY = this.getScrollbarTrackY();
        int trackHeight = this.getScrollbarTrackHeight();
        int thumbHeight = this.getScrollbarThumbHeight(trackHeight);
        int thumbY = trackY + (trackHeight - thumbHeight) * this.scrollOffset / maxScroll;

        RenderUtils.drawRect(ctx, trackX, trackY, SCROLLBAR_WIDTH, trackHeight, 0xA0202020);
        RenderUtils.drawRect(ctx, trackX, thumbY, SCROLLBAR_WIDTH, thumbHeight, 0xFFE0E0E0);
    }

    private void renderHoveredItemTooltip(GuiContext ctx, int mouseX, int mouseY)
    {
        int itemIndex = this.getItemIndexAt(mouseY);

        if (itemIndex < 0)
        {
            return;
        }

        int rowY = this.getListY() + (itemIndex - this.scrollOffset) * ROW_HEIGHT;

        if (!this.isMouseOverRow(mouseX, mouseY, rowY))
        {
            return;
        }

        T item = this.filteredItems.get(itemIndex);
        String label = this.itemLabel(item);
        int rowX = this.x + 1;
        int rowWidth = Math.max(1, this.width - 2);
        int textX = this.getRowTextX(rowX);
        int textWidth = Math.max(8, this.getRowTextRightX(rowX, rowWidth) - textX);

        if (this.fitTextToWidth(label, textWidth).truncated())
        {
            RenderUtils.drawHoverText(ctx, mouseX, mouseY, this.wrapTooltipText(label));
        }
    }

    private void setOpen(boolean open)
    {
        this.open = open;

        if (open)
        {
            this.searchField.setValueWrapper("");
            this.searchField.setFocused(true);
        }
        else
        {
            this.searchField.setFocused(false);
            this.searchField.setValueWrapper("");
            this.draggingScrollbar = false;
        }

        this.updateFilteredItems();
    }

    private void updateFilteredItems()
    {
        String filter = this.searchField.getValueWrapper().trim().toLowerCase(Locale.ROOT);
        this.filteredItems.clear();

        for (T item : this.items)
        {
            if (this.itemMatchesFilter(item, filter))
            {
                this.filteredItems.add(item);
            }
        }

        this.clampScrollOffset();
    }

    private int getItemIndexAt(int mouseY)
    {
        int relativeY = mouseY - this.getListY();

        if (relativeY < 0)
        {
            return -1;
        }

        int row = relativeY / ROW_HEIGHT;

        if (row < 0 || row >= this.getVisibleRowCount())
        {
            return -1;
        }

        int index = this.scrollOffset + row;
        return index >= 0 && index < this.filteredItems.size() ? index : -1;
    }

    private boolean isMouseOverScrollbar(int mouseX, int mouseY)
    {
        return this.getMaxScroll() > 0 &&
                mouseX >= this.getScrollbarTrackX() && mouseX < this.getScrollbarTrackX() + SCROLLBAR_WIDTH &&
                mouseY >= this.getScrollbarTrackY() && mouseY < this.getScrollbarTrackY() + this.getScrollbarTrackHeight();
    }

    private boolean isMouseOverRow(int mouseX, int mouseY, int rowY)
    {
        int rightX = this.getMaxScroll() > 0 ? this.getScrollbarTrackX() : this.x + this.width - 1;
        return mouseX >= this.x && mouseX < rightX && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
    }

    private void scrollToMouseY(int mouseY)
    {
        int maxScroll = this.getMaxScroll();
        int trackY = this.getScrollbarTrackY();
        int trackHeight = this.getScrollbarTrackHeight();
        int thumbHeight = this.getScrollbarThumbHeight(trackHeight);
        int travel = trackHeight - thumbHeight;

        if (maxScroll <= 0 || travel <= 0)
        {
            return;
        }

        int relativeY = Math.clamp(mouseY - trackY - thumbHeight / 2, 0, travel);
        this.scrollOffset = Math.round(relativeY * maxScroll / (float) travel);
        this.clampScrollOffset();
    }

    private int getScrollbarTrackX()
    {
        return this.x + this.width - SCROLLBAR_WIDTH - 1;
    }

    private int getScrollbarTrackY()
    {
        return this.getListY();
    }

    private int getScrollbarTrackHeight()
    {
        return this.getVisibleRowCount() * ROW_HEIGHT;
    }

    private int getScrollbarThumbHeight(int trackHeight)
    {
        int visibleRows = this.getVisibleRowCount();
        return Math.max(12, trackHeight * visibleRows / Math.max(visibleRows, this.filteredItems.size()));
    }

    private boolean isMouseOverButton(int mouseX, int mouseY)
    {
        return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
    }

    private boolean isMouseOverSearch(int mouseX, int mouseY)
    {
        return mouseX >= this.getSearchTextX() && mouseX < this.getSearchTextX() + this.getSearchTextWidth() &&
                mouseY >= this.y && mouseY < this.y + this.height;
    }

    private int getSearchTextX()
    {
        return this.getClosedTextX();
    }

    private int getSearchTextY()
    {
        return this.y + (this.height - 14) / 2 + 1;
    }

    private int getSearchTextWidth()
    {
        return Math.max(20, this.getArrowX() - this.getSearchTextX() - ICON_TEXT_GAP);
    }

    private int getListY()
    {
        return this.y + this.height + 1;
    }

    private int getOpenHeight()
    {
        return this.getVisibleRowCount() * ROW_HEIGHT + 2;
    }

    private int getVisibleRowCount()
    {
        if (this.filteredItems.isEmpty())
        {
            return 1;
        }

        return Math.min(this.getVisibleRowLimit(), this.filteredItems.size());
    }

    private int getMaxScroll()
    {
        return Math.max(0, this.filteredItems.size() - this.getVisibleRowLimit());
    }

    private int getVisibleRowLimit()
    {
        int availableHeight = Math.max(ROW_HEIGHT, GuiUtils.getScaledWindowHeight() - this.getListY() - 2);
        return Math.max(1, Math.min(this.maxVisibleRows, availableHeight / ROW_HEIGHT));
    }

    private void clampScrollOffset()
    {
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.getMaxScroll()));
    }

    private int getClosedTextX()
    {
        return this.x + ICON_PADDING + this.iconSize() + ICON_TEXT_GAP;
    }

    private int getRowTextX()
    {
        return this.getRowTextX(this.x + 1);
    }

    private int getRowTextX(int rowX)
    {
        return rowX + ICON_PADDING + this.iconSize() + ICON_TEXT_GAP;
    }

    private int getRowTextRightX(int rowX, int rowWidth)
    {
        int rightX = rowX + rowWidth - ICON_PADDING;

        if (this.getMaxScroll() > 0)
        {
            rightX = Math.min(rightX, this.getScrollbarTrackX() - 2);
        }

        return rightX;
    }

    private int getArrowX()
    {
        return this.x + this.width - ARROW_PADDING - Icons.ARROW_DOWN.getWidth();
    }

    private String ellipsizeToWidth(String text, int maxWidth)
    {
        return this.fitTextToWidth(text, maxWidth).text();
    }

    private List<String> wrapTooltipText(String text)
    {
        int availableWidth = GuiUtils.getScaledWindowWidth() - TOOLTIP_SCREEN_PADDING;
        int maxWidth = Math.max(80, Math.min(TOOLTIP_MAX_WIDTH, availableWidth));
        List<String> lines = new ArrayList<>();

        for (String paragraph : text.split("\\R", -1))
        {
            this.wrapTooltipParagraph(paragraph, maxWidth, lines);
        }

        return lines.isEmpty() ? List.of("") : lines;
    }

    private void wrapTooltipParagraph(String paragraph, int maxWidth, List<String> lines)
    {
        if (paragraph.isBlank())
        {
            lines.add("");
            return;
        }

        String line = "";

        for (String word : paragraph.trim().split("\\s+"))
        {
            String candidate = line.isEmpty() ? word : line + " " + word;

            if (this.getStringWidth(candidate) <= maxWidth)
            {
                line = candidate;
                continue;
            }

            if (!line.isEmpty())
            {
                lines.add(line);
                line = "";
            }

            if (this.getStringWidth(word) <= maxWidth)
            {
                line = word;
            }
            else
            {
                line = this.wrapLongTooltipWord(word, maxWidth, lines);
            }
        }

        if (!line.isEmpty())
        {
            lines.add(line);
        }
    }

    private String wrapLongTooltipWord(String word, int maxWidth, List<String> lines)
    {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < word.length(); i++)
        {
            String candidate = builder.toString() + word.charAt(i);

            if (this.getStringWidth(candidate) > maxWidth && !builder.isEmpty())
            {
                lines.add(builder.toString());
                builder.setLength(0);
            }

            builder.append(word.charAt(i));
        }

        return builder.toString();
    }

    private TextFit fitTextToWidth(String text, int maxWidth)
    {
        if (maxWidth <= 0)
        {
            return new TextFit("", true);
        }

        if (this.getStringWidth(text) <= maxWidth)
        {
            return new TextFit(text, false);
        }

        String suffix = "...";
        int suffixWidth = this.getStringWidth(suffix);

        if (suffixWidth > maxWidth)
        {
            for (int length = suffix.length(); length > 0; length--)
            {
                String candidate = suffix.substring(0, length);

                if (this.getStringWidth(candidate) <= maxWidth)
                {
                    return new TextFit(candidate, true);
                }
            }

            return new TextFit("", true);
        }

        for (int length = text.length(); length > 0; length--)
        {
            String candidate = text.substring(0, length);

            if (this.getStringWidth(candidate) + suffixWidth <= maxWidth)
            {
                return new TextFit(candidate + suffix, true);
            }
        }

        return new TextFit(suffix, true);
    }

    private record TextFit(String text, boolean truncated)
    {
    }
}
