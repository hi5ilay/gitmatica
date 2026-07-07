package me.zly2006.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcNoticeDialog extends GuiDialogBase
{
    private static final int DIALOG_WIDTH = 360;
    private static final int MIN_DIALOG_HEIGHT = 92;
    private static final int PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int LINE_HEIGHT = 12;
    private static final int BACKGROUND_MOUSE = Integer.MIN_VALUE;

    private final String message;
    private final int textColor;
    private List<String> lines = List.of();

    GuiLvcNoticeDialog(Screen parent, String titleKey, String messageKey, int textColor, Object... args)
    {
        this.message = StringUtils.translate(messageKey, args);
        this.textColor = textColor;
        this.setParent(parent);
        this.title = StringUtils.translate(titleKey);
        this.useTitleHierarchy = false;
        this.setWidthAndHeight(DIALOG_WIDTH, MIN_DIALOG_HEIGHT);
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.lines = this.wrapTextToWidth(this.message, DIALOG_WIDTH - PADDING * 2);
        int textHeight = this.lines.size() * LINE_HEIGHT;
        int dialogHeight = Math.max(MIN_DIALOG_HEIGHT, PADDING + 16 + textHeight + 14 + BUTTON_HEIGHT + PADDING);
        this.setWidthAndHeight(DIALOG_WIDTH, dialogHeight);
        this.centerOnScreen();

        String okLabel = GuiBase.TXT_GREEN + StringUtils.translate("malilib.gui.button.ok") + GuiBase.TXT_RST;
        int okWidth = Math.max(50, this.getStringWidth(okLabel) + 18);
        int x = this.dialogLeft + PADDING;
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        this.addButton(new ButtonGeneric(x, y, okWidth, BUTTON_HEIGHT, okLabel), (button, mouseButton) -> this.closeGui(true));
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (input.key() == KeyCodes.KEY_ESCAPE)
        {
            this.closeGui(true);
            return true;
        }

        return super.onKeyTyped(input);
    }

    @Override
    protected void drawWidgets(GuiContext ctx, int mouseX, int mouseY)
    {
    }

    @Override
    protected void drawButtons(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        Screen parent = this.getParent();

        if (parent != null)
        {
            parent.extractRenderState(ctx.getGuiGraphics(), BACKGROUND_MOUSE, BACKGROUND_MOUSE, partialTicks);
        }

        RenderUtils.drawOutlinedBox(ctx, this.dialogLeft, this.dialogTop, this.dialogWidth, this.dialogHeight, 0xE0000000, COLOR_HORIZONTAL_BAR);
        this.drawStringWithShadow(ctx, this.getTitleString(), this.dialogLeft + PADDING, this.dialogTop + 6, COLOR_WHITE);

        int y = this.dialogTop + 28;

        for (String line : this.lines)
        {
            this.drawStringWithShadow(ctx, line, this.dialogLeft + PADDING, y, this.textColor);
            y += LINE_HEIGHT;
        }

        super.drawButtons(ctx, mouseX, mouseY, partialTicks);
    }

    private List<String> wrapTextToWidth(String text, int maxWidth)
    {
        List<String> wrapped = new ArrayList<>();
        String line = "";

        for (String word : text.trim().split("\\s+"))
        {
            String candidate = line.isEmpty() ? word : line + " " + word;

            if (this.getStringWidth(candidate) <= maxWidth)
            {
                line = candidate;
                continue;
            }

            if (!line.isEmpty())
            {
                wrapped.add(line);
            }

            line = word;
        }

        if (!line.isEmpty())
        {
            wrapped.add(line);
        }

        return wrapped.isEmpty() ? List.of("") : wrapped;
    }
}
