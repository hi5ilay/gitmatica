package me.zly2006.lvc.gui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.task.LvcOperationJournal;

import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcRecoveryDialog extends GuiDialogBase
{
    private static final int DIALOG_WIDTH = 360;
    private static final int MIN_DIALOG_HEIGHT = 132;
    private static final int PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_MIN_WIDTH = 40;
    private static final int BUTTON_TEXT_PADDING = 6;
    private static final int LINE_HEIGHT = 12;
    private static final int BACKGROUND_MOUSE = Integer.MIN_VALUE;
    private static final int METADATA_LABEL_COLOR = 0xFFAAAAAA;

    private final LvcOperationJournal.Entry entry;
    private final LvcOperationJournal.Operation operation;
    private final Runnable restartAction;
    private final Runnable abortAction;
    private final Runnable cancelAction;
    private List<RecoveryLine> lines = List.of();
    private boolean handled;

    GuiLvcRecoveryDialog(Screen parent, LvcOperationJournal.Entry entry, LvcOperationJournal.Operation operation,
                         Runnable restartAction, Runnable abortAction, Runnable cancelAction)
    {
        this.entry = entry;
        this.operation = operation;
        this.restartAction = restartAction;
        this.abortAction = abortAction;
        this.cancelAction = cancelAction;
        this.setParent(parent);
        this.title = StringUtils.translate("litematica.gui.title.lvc_project.recovery_required");
        this.useTitleHierarchy = false;
        this.setWidthAndHeight(DIALOG_WIDTH, MIN_DIALOG_HEIGHT);
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.lines = this.createLines();
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

        RenderUtils.drawOutlinedBox(ctx, this.dialogLeft, this.dialogTop, this.dialogWidth, this.dialogHeight, 0xE0000000, COLOR_HORIZONTAL_BAR);
        this.drawStringWithShadow(ctx, this.getTitleString(), this.dialogLeft + PADDING, this.dialogTop + 6, COLOR_WHITE);

        int y = this.dialogTop + 28;

        for (RecoveryLine line : this.lines)
        {
            int x = this.dialogLeft + PADDING;
            int labelColor = line.value() == null ? COLOR_WHITE : METADATA_LABEL_COLOR;
            this.drawStringWithShadow(ctx, line.label(), x, y, labelColor);

            if (line.value() != null)
            {
                this.drawStringWithShadow(ctx, line.value(), x + this.getStringWidth(line.label()), y, COLOR_WHITE);
            }

            y += LINE_HEIGHT;
        }

        super.drawButtons(ctx, mouseX, mouseY, partialTicks);
    }

    private List<RecoveryLine> createLines()
    {
        List<RecoveryLine> result = new ArrayList<>();
        int maxWidth = DIALOG_WIDTH - PADDING * 2;
        this.addWrappedLine(result, StringUtils.translate("litematica.gui.message.lvc_project.recovery_required"), maxWidth);

        result.add(RecoveryLine.metadata(
                StringUtils.translate("litematica.gui.label.lvc_project.recovery_name"),
                displayOperation(this.operation)
        ));

        if (this.entry.targetCommit() != null && !this.entry.targetCommit().isBlank())
        {
            result.add(RecoveryLine.metadata(
                    StringUtils.translate("litematica.gui.label.lvc_project.recovery_commit"),
                    shortCommit(this.entry.targetCommit())
            ));
        }

        if (this.entry.targetBranch() != null && !this.entry.targetBranch().isBlank())
        {
            result.add(RecoveryLine.metadata(
                    StringUtils.translate("litematica.gui.label.lvc_project.recovery_branch"),
                    this.entry.targetBranch()
            ));
        }

        if (this.entry.startedAt() != null && !this.entry.startedAt().isBlank())
        {
            result.add(RecoveryLine.metadata(
                    StringUtils.translate("litematica.gui.label.lvc_project.recovery_started_at"),
                    formatStartedAt(this.entry.startedAt())
            ));
        }

        return result;
    }

    private void addWrappedLine(List<RecoveryLine> lines, String text, int maxWidth)
    {
        for (String line : this.wrapTextToWidth(text, maxWidth))
        {
            lines.add(RecoveryLine.text(line));
        }
    }

    private List<String> wrapTextToWidth(String text, int maxWidth)
    {
        List<String> wrapped = new ArrayList<>();
        String[] paragraphs = text.split("\\R", -1);

        for (String paragraph : paragraphs)
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
        String restartLabel = StringUtils.translate("litematica.gui.button.lvc_project.restart_operation");
        String abortLabel = StringUtils.translate("litematica.gui.button.lvc_project.abort_operation");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int restartWidth = this.buttonWidth(restartLabel);
        int abortWidth = this.buttonWidth(abortLabel);
        int cancelWidth = this.buttonWidth(cancelLabel);
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        int x = this.dialogLeft + PADDING;

        this.addButton(new ButtonGeneric(x, y, restartWidth, BUTTON_HEIGHT, restartLabel), (button, mouseButton) -> this.chooseAndClose(this.restartAction));
        x += restartWidth + BUTTON_GAP;
        this.addButton(new ButtonGeneric(x, y, abortWidth, BUTTON_HEIGHT, abortLabel), (button, mouseButton) -> this.chooseAndClose(this.abortAction));
        x += abortWidth + BUTTON_GAP;
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

    static String displayOperation(LvcOperationJournal.Operation operation)
    {
        return switch (operation)
        {
            case INIT -> StringUtils.translate("litematica.gui.button.lvc_project.create_short");
            case SAVE -> StringUtils.translate("litematica.gui.button.lvc_project.save_version");
            case UPDATE_AREAS -> StringUtils.translate("litematica.gui.button.lvc_project.update_areas");
            case CHECKOUT -> StringUtils.translate("litematica.gui.button.lvc_project.checkout_version");
            case DISCARD -> StringUtils.translate("litematica.gui.button.lvc_project.discard_changes");
            case CLEAR -> StringUtils.translate("litematica.gui.button.lvc_project.clear_area");
            case MERGE -> StringUtils.translate("litematica.gui.button.lvc_project.branch_action_merge");
            case DELETE_VERSION -> StringUtils.translate("litematica.gui.label.lvc_project.delete_latest_version");
        };
    }

    private static String shortCommit(String commit)
    {
        return commit.length() <= 8 ? commit : commit.substring(0, 8);
    }

    private static String formatStartedAt(String startedAt)
    {
        try
        {
            return LvcProjectGitOps.formatCommitTime(Instant.parse(startedAt));
        }
        catch (Exception e)
        {
            return startedAt;
        }
    }

    private record RecoveryLine(String label, String value)
    {
        private static RecoveryLine text(String text)
        {
            return new RecoveryLine(text, null);
        }

        private static RecoveryLine metadata(String label, String value)
        {
            return new RecoveryLine(label, value);
        }
    }
}
