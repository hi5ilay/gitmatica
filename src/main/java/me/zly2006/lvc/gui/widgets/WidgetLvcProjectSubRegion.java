package me.zly2006.lvc.gui.widgets;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.input.MouseButtonEvent;
import me.zly2006.lvc.model.LvcManifest;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetLvcProjectSubRegion extends WidgetListEntryBase<LvcManifest.Region>
{
    private final WidgetLvcProjectSubRegionList parent;
    private final boolean isOdd;
    private final int buttonsStartX;

    public WidgetLvcProjectSubRegion(int x, int y, int width, int height, boolean isOdd,
                                     LvcManifest.Region entry, int listIndex,
                                     WidgetLvcProjectSubRegionList parent)
    {
        super(x, y, width, height, entry, listIndex);

        this.isOdd = isOdd;
        this.parent = parent;

        int posX = x + width - 2;
        int posY = y + 1;

        posX = this.createButton(posX, posY, ButtonType.REMOVE);
        posX = this.createButton(posX, posY, ButtonType.RENAME);
        posX = this.createButton(posX, posY, ButtonType.CONFIGURE);

        this.buttonsStartX = posX;
    }

    private int createButton(int x, int y, ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, type.getDisplayName());
        button.setEnabled(false);
        return this.addButton(button, new ButtonListener()).getX() - 1;
    }

    @Override
    public boolean canSelectAt(MouseButtonEvent click)
    {
        return click.x() < this.buttonsStartX && super.canSelectAt(click);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        selected = this.entry != null && this.entry.id().equals(this.parent.getEditorGui().getSelectedRegionId());

        if (selected || this.isMouseOver(mouseX, mouseY))
        {
            RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, 0xA0707070);
        }
        else if (this.isOdd)
        {
            RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, 0xA0101010);
        }
        else
        {
            RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, 0xA0303030);
        }

        if (selected)
        {
            RenderUtils.drawOutline(ctx, this.x, this.y, this.width, this.height, 0xFFE0E0E0);
        }

        if (this.entry != null)
        {
            String name = this.ellipsizeToWidth(this.entry.name(), Math.max(24, this.buttonsStartX - this.x - 8));
            this.drawString(ctx, this.x + 2, this.y + 7, 0xFFFFFFFF, name);
        }

        super.render(ctx, mouseX, mouseY, selected);
    }

    @Override
    public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        if (this.entry != null && GuiBase.isMouseOver(mouseX, mouseY, this.x, this.y, this.buttonsStartX - this.x - 10, this.height))
        {
            List<String> text = new ArrayList<>();
            text.add(StringUtils.translate("litematica.gui.label.lvc_project_editor.region_min") + ": " + this.vectorText(this.entry.min()));
            text.add(StringUtils.translate("litematica.gui.label.lvc_project_editor.region_size") + ": " + this.vectorText(this.entry.size()));
            RenderUtils.drawHoverText(ctx, mouseX, mouseY, text);
        }

        super.postRenderHovered(ctx, mouseX, mouseY, selected);
    }

    private String vectorText(List<Integer> vector)
    {
        return vector.get(0) + ", " + vector.get(1) + ", " + vector.get(2);
    }

    private String ellipsizeToWidth(String text, int maxWidth)
    {
        if (this.getStringWidth(text) <= maxWidth)
        {
            return text;
        }

        String suffix = "...";
        int suffixWidth = this.getStringWidth(suffix);

        for (int length = text.length(); length > 0; length--)
        {
            String candidate = text.substring(0, length);

            if (this.getStringWidth(candidate) + suffixWidth <= maxWidth)
            {
                return candidate + suffix;
            }
        }

        return suffix;
    }

    private enum ButtonType
    {
        RENAME("litematica.gui.button.rename"),
        CONFIGURE("litematica.gui.button.configure"),
        REMOVE(GuiBase.TXT_RED + "-");

        private final String labelKey;

        ButtonType(String labelKey)
        {
            this.labelKey = labelKey;
        }

        private String getDisplayName()
        {
            return StringUtils.translate(this.labelKey);
        }
    }

    private static class ButtonListener implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
        }
    }
}
