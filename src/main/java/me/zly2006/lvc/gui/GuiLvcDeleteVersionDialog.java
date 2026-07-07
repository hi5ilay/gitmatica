package me.zly2006.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

final class GuiLvcDeleteVersionDialog extends GuiDialogBase
{
    private static final int DIALOG_WIDTH = 340;
    private static final int MIN_DIALOG_HEIGHT = 92;
    private static final int PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_MIN_WIDTH = 48;
    private static final int BUTTON_TEXT_PADDING = 12;
    private static final int LINE_HEIGHT = 12;
    private static final int BACKGROUND_MOUSE = Integer.MIN_VALUE;
    private static final int MESSAGE_COLOR = 0xFFAAAAAA;

    private final Runnable keepChangesAction;
    private final Runnable deleteChangesAction;
    private final Runnable cancelAction;
    private List<String> lines = List.of();
    private boolean handled;

    GuiLvcDeleteVersionDialog(Screen parent, Runnable keepChangesAction, Runnable deleteChangesAction, Runnable cancelAction)
    {
        this.keepChangesAction = keepChangesAction;
        this.deleteChangesAction = deleteChangesAction;
        this.cancelAction = cancelAction;
        this.setParent(parent);
        this.title = StringUtils.translate("litematica.gui.title.lvc_project.delete_version");
        this.useTitleHierarchy = false;
        this.setWidthAndHeight(DIALOG_WIDTH, MIN_DIALOG_HEIGHT);
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.lines = this.wrapTextToWidth(
                StringUtils.translate("litematica.gui.message.lvc_project.delete_version_note"),
                DIALOG_WIDTH - PADDING * 2
        );
        int textHeight = this.lines.size() * LINE_HEIGHT;
        int dialogHeight = Math.max(MIN_DIALOG_HEIGHT, PADDING + 16 + textHeight + 14 + BUTTON_HEIGHT + PADDING);
        this.setWidthAndHeight(DIALOG_WIDTH, dialogHeight);
        this.centerOnScreen();
        this.createButtons();
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (input.key() == KeyCodes.KEY_ESCAPE)
        {
            this.chooseAndClose(this.cancelAction);
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

        RenderUtils.drawOutlinedBox(ctx, this.dialogLeft, this.dialogTop, this.dialogWidth, this.dialogHeight,
                0xE0000000, COLOR_HORIZONTAL_BAR);
        this.drawStringWithShadow(ctx, this.getTitleString(), this.dialogLeft + PADDING, this.dialogTop + 6, COLOR_WHITE);

        int y = this.dialogTop + 28;

        for (String line : this.lines)
        {
            this.drawStringWithShadow(ctx, line, this.dialogLeft + PADDING, y, MESSAGE_COLOR);
            y += LINE_HEIGHT;
        }

        super.drawButtons(ctx, mouseX, mouseY, partialTicks);
    }

    private List<String> wrapTextToWidth(String text, int maxWidth)
    {
        List<String> wrapped = new ArrayList<>();

        for (String paragraph : text.split("\\R", -1))
        {
            this.wrapParagraphToWidth(paragraph, maxWidth, wrapped);
        }

        return wrapped.isEmpty() ? List.of("") : wrapped;
    }

    private void wrapParagraphToWidth(String paragraph, int maxWidth, List<String> lines)
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
                line = this.wrapLongWordToWidth(word, maxWidth, lines);
            }
        }

        if (!line.isEmpty())
        {
            lines.add(line);
        }
    }

    private String wrapLongWordToWidth(String word, int maxWidth, List<String> lines)
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

    private void createButtons()
    {
        String keepLabel = StringUtils.translate("litematica.gui.button.lvc_project.keep_changes");
        String deleteLabel = StringUtils.translate("litematica.gui.button.lvc_project.delete_changes");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int keepWidth = this.buttonWidth(keepLabel);
        int deleteWidth = this.buttonWidth(deleteLabel);
        int cancelWidth = this.buttonWidth(cancelLabel);
        int x = this.dialogLeft + PADDING;
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;

        this.addButton(new ButtonGeneric(x, y, keepWidth, BUTTON_HEIGHT, keepLabel), (button, mouseButton) -> this.chooseAndClose(this.keepChangesAction));
        x += keepWidth + BUTTON_GAP;
        this.addButton(new ButtonGeneric(x, y, deleteWidth, BUTTON_HEIGHT, deleteLabel), (button, mouseButton) -> this.chooseAndClose(this.deleteChangesAction));
        x += deleteWidth + BUTTON_GAP;
        this.addButton(new ButtonGeneric(x, y, cancelWidth, BUTTON_HEIGHT, cancelLabel), (button, mouseButton) -> this.chooseAndClose(this.cancelAction));
    }

    private int buttonWidth(String label)
    {
        return Math.max(BUTTON_MIN_WIDTH, this.getStringWidth(label) + BUTTON_TEXT_PADDING);
    }

    private void chooseAndClose(Runnable action)
    {
        if (this.handled)
        {
            return;
        }

        this.handled = true;
        this.closeGui(true);
        action.run();
    }
}
