package me.zly2006.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import me.zly2006.lvc.LvcProjectService;

import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcMergeConflictDialog extends GuiDialogBase
{
    private static final String TITLE_KEY = "litematica.gui.title.lvc_project.merge_conflict";
    private static final String MESSAGE_KEY = "litematica.gui.message.lvc_project.merge_conflict_choose";
    private static final int DIALOG_WIDTH = 300;
    private static final int MIN_DIALOG_HEIGHT = 92;
    private static final int PADDING = 10;
    private static final int TITLE_Y = 6;
    private static final int MESSAGE_Y = 30;
    private static final int BUTTON_TOP_GAP = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_MIN_WIDTH = 44;
    private static final int BUTTON_TEXT_PADDING = 14;
    private static final int BACKGROUND_MOUSE = Integer.MIN_VALUE;
    private static final int MESSAGE_COLOR = 0xFFAAAAAA;
    private static final int LINE_HEIGHT = 12;

    private final Consumer<LvcProjectService.BranchMergeConflictResolution> resolutionConsumer;
    private final Runnable cancelAction;
    private List<String> messageLines = List.of();
    private boolean handled;

    GuiLvcMergeConflictDialog(Screen parent,
                              Consumer<LvcProjectService.BranchMergeConflictResolution> resolutionConsumer,
                              Runnable cancelAction)
    {
        this.resolutionConsumer = resolutionConsumer;
        this.cancelAction = cancelAction;
        this.setParent(parent);
        this.title = "";
        this.useTitleHierarchy = false;
        this.setWidthAndHeight(DIALOG_WIDTH, MIN_DIALOG_HEIGHT);
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.messageLines = this.wrapMessage(StringUtils.translate(MESSAGE_KEY), DIALOG_WIDTH - PADDING * 2);
        int messageHeight = Math.max(LINE_HEIGHT, this.messageLines.size() * LINE_HEIGHT);
        int dialogHeight = Math.max(MIN_DIALOG_HEIGHT, MESSAGE_Y + messageHeight + BUTTON_TOP_GAP + BUTTON_HEIGHT + PADDING);
        this.setWidthAndHeight(DIALOG_WIDTH, dialogHeight);
        this.centerOnScreen();
        this.createButtons();
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (input.key() == KeyCodes.KEY_ESCAPE)
        {
            this.cancelAndClose();
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
        this.drawStringWithShadow(ctx, StringUtils.translate(TITLE_KEY), this.dialogLeft + PADDING, this.dialogTop + TITLE_Y, COLOR_WHITE);
        this.drawMessageLines(ctx, this.dialogTop + MESSAGE_Y);

        super.drawButtons(ctx, mouseX, mouseY, partialTicks);
    }

    private void drawMessageLines(GuiContext ctx, int y)
    {
        int x = this.dialogLeft + PADDING;

        for (String line : this.messageLines)
        {
            this.drawStringWithShadow(ctx, line, x, y, MESSAGE_COLOR);
            y += LINE_HEIGHT;
        }
    }

    private List<String> wrapMessage(String message, int maxWidth)
    {
        List<String> lines = new ArrayList<>();
        String line = "";

        for (String word : message.split("\\s+"))
        {
            String candidate = line.isEmpty() ? word : line + " " + word;

            if (this.getStringWidth(candidate) <= maxWidth)
            {
                line = candidate;
            }
            else
            {
                if (!line.isEmpty())
                {
                    lines.add(line);
                }

                line = word;
            }
        }

        if (!line.isEmpty())
        {
            lines.add(line);
        }

        return lines.isEmpty() ? List.of("") : lines;
    }

    private void createButtons()
    {
        String baseLabel = StringUtils.translate("litematica.gui.button.lvc_project.merge_accept_base");
        String incomingLabel = StringUtils.translate("litematica.gui.button.lvc_project.merge_accept_incoming");
        String yoursLabel = StringUtils.translate("litematica.gui.button.lvc_project.merge_accept_yours");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int baseWidth = this.buttonWidth(baseLabel);
        int incomingWidth = this.buttonWidth(incomingLabel);
        int yoursWidth = this.buttonWidth(yoursLabel);
        int cancelWidth = this.buttonWidth(cancelLabel);
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        int x = this.dialogLeft + PADDING;

        this.addButton(new ButtonGeneric(x, y, baseWidth, BUTTON_HEIGHT, baseLabel),
                (button, mouseButton) -> this.chooseAndClose(LvcProjectService.BranchMergeConflictResolution.BASE));
        x += baseWidth + BUTTON_GAP;
        this.addButton(new ButtonGeneric(x, y, incomingWidth, BUTTON_HEIGHT, incomingLabel),
                (button, mouseButton) -> this.chooseAndClose(LvcProjectService.BranchMergeConflictResolution.INCOMING));
        x += incomingWidth + BUTTON_GAP;
        this.addButton(new ButtonGeneric(x, y, yoursWidth, BUTTON_HEIGHT, yoursLabel),
                (button, mouseButton) -> this.chooseAndClose(LvcProjectService.BranchMergeConflictResolution.YOURS));
        x += yoursWidth + BUTTON_GAP;
        this.addButton(new ButtonGeneric(x, y, cancelWidth, BUTTON_HEIGHT, cancelLabel), (button, mouseButton) -> this.cancelAndClose());
    }

    private int buttonWidth(String label)
    {
        return Math.max(BUTTON_MIN_WIDTH, this.getStringWidth(label) + BUTTON_TEXT_PADDING);
    }

    private void chooseAndClose(LvcProjectService.BranchMergeConflictResolution resolution)
    {
        if (this.handled)
        {
            return;
        }

        this.handled = true;
        this.closeGui(true);
        this.resolutionConsumer.accept(resolution);
    }

    private void cancelAndClose()
    {
        if (this.handled)
        {
            return;
        }

        this.handled = true;
        this.closeGui(true);
        this.cancelAction.run();
    }
}
