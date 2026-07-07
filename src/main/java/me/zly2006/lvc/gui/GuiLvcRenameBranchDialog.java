package me.zly2006.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import me.zly2006.lvc.gui.widgets.WidgetLvcBranchDropdown;

import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcRenameBranchDialog extends GuiDialogBase
{
    private static final int DIALOG_WIDTH = 260;
    private static final int DIALOG_HEIGHT = 142;
    private static final int PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_TEXTURE_LEFT_INSET = 1;
    private static final int DROPDOWN_HEIGHT = 20;
    private static final int DROPDOWN_VISIBLE_ROWS = 6;
    private static final int TEXT_FIELD_HEIGHT = 20;
    private static final int ERROR_VERTICAL_SPACE = 14;
    private static final int ERROR_BOX_HEIGHT = 12;
    private static final int ERROR_HORIZONTAL_INSET = 1;
    private static final int BACKGROUND_MOUSE = Integer.MIN_VALUE;

    private final GuiLvcProjectController controller;
    private final List<String> branches;
    @Nullable private final String headBranch;
    @Nullable private WidgetLvcBranchDropdown branchDropdown;
    @Nullable private EditBox nameField;
    @Nullable private String selectedBranch;
    @Nullable private String errorMessage;
    private boolean errorSpaceVisible;

    GuiLvcRenameBranchDialog(GuiLvcProjectController controller, List<String> branches, @Nullable String headBranch)
    {
        this.controller = controller;
        this.branches = List.copyOf(this.normalizeBranches(branches));
        this.headBranch = headBranch;
        this.selectedBranch = this.branches.isEmpty() ? null : this.branches.get(0);
        this.setParent(controller.gui);
        this.title = StringUtils.translate("litematica.gui.title.lvc_project.rename_branch");
        this.useTitleHierarchy = false;
        this.setWidthAndHeight(DIALOG_WIDTH, DIALOG_HEIGHT);
    }

    @Override
    public void initGui()
    {
        String currentName = this.getNewBranchName();
        this.clearElements();
        this.setWidthAndHeight(DIALOG_WIDTH, this.getDialogHeight());
        this.centerOnScreen();

        int fieldX = this.dialogLeft + PADDING;
        int fieldWidth = this.dialogWidth - PADDING * 2;
        int dropdownY = this.dialogTop + 38;
        this.branchDropdown = this.addWidget(new WidgetLvcBranchDropdown(
                fieldX,
                dropdownY,
                fieldWidth,
                DROPDOWN_HEIGHT,
                DROPDOWN_VISIBLE_ROWS,
                this.branches,
                this.selectedBranch,
                this.headBranch,
                branchName -> this.selectedBranch = branchName
        ));

        this.nameField = new EditBox(this.font, fieldX, this.dialogTop + 81, fieldWidth, TEXT_FIELD_HEIGHT, CommonComponents.EMPTY);
        this.nameField.setMaxLength(128);
        this.nameField.setTextColor(0xFFFFFFFF);
        this.nameField.setTextColorUneditable(0xFFAAAAAA);
        this.nameField.setValue(currentName);
        this.nameField.setFocused(true);

        this.createButtons();
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        int mouseX = (int) click.x();
        int mouseY = (int) click.y();

        if (this.branchDropdown != null && this.branchDropdown.isOpen())
        {
            if (this.branchDropdown.isMouseOver(mouseX, mouseY))
            {
                return this.branchDropdown.onMouseClicked(click, doubleClick);
            }

            this.branchDropdown.close();
            return true;
        }

        if (this.branchDropdown != null && this.branchDropdown.isMouseOver(mouseX, mouseY))
        {
            this.setNameFieldFocused(false);
            return this.branchDropdown.onMouseClicked(click, doubleClick);
        }

        if (this.nameField != null && this.nameField.isMouseOver(mouseX, mouseY))
        {
            this.nameField.mouseClicked(click, doubleClick);
            this.setNameFieldFocused(true);
            return true;
        }

        this.setNameFieldFocused(false);
        return super.onMouseClicked(click, doubleClick);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (this.branchDropdown != null && this.branchDropdown.isOpen() &&
                this.branchDropdown.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount))
        {
            return true;
        }

        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragXAmount, double dragYAmount)
    {
        if (this.branchDropdown != null && this.branchDropdown.isOpen() &&
                this.branchDropdown.onMouseDragged(click, dragXAmount, dragYAmount))
        {
            return true;
        }

        return super.onMouseDragged(click, dragXAmount, dragYAmount);
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click)
    {
        if (this.branchDropdown != null && this.branchDropdown.isOpen())
        {
            boolean wasDraggingDropdownScrollbar = this.branchDropdown.isDraggingScrollbar();
            this.branchDropdown.onMouseReleased(click);

            if (wasDraggingDropdownScrollbar)
            {
                return true;
            }
        }

        return super.onMouseReleased(click);
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (this.branchDropdown != null && this.branchDropdown.isOpen() && this.branchDropdown.onKeyTyped(input))
        {
            return true;
        }

        if (input.key() == KeyCodes.KEY_ENTER || input.key() == KeyCodes.KEY_KP_ENTER)
        {
            this.renameSelectedBranch();
            return true;
        }

        if (this.nameField != null && this.nameField.isFocused() && input.key() != KeyCodes.KEY_ESCAPE)
        {
            return this.nameField.keyPressed(input);
        }

        return super.onKeyTyped(input);
    }

    @Override
    public boolean onCharTyped(CharacterEvent input)
    {
        if (this.branchDropdown != null && this.branchDropdown.isOpen() && this.branchDropdown.onCharTyped(input))
        {
            return true;
        }

        if (this.nameField != null && this.nameField.isFocused())
        {
            return this.nameField.charTyped(input);
        }

        return super.onCharTyped(input);
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
        this.drawStringWithShadow(ctx, StringUtils.translate("litematica.gui.label.lvc_project.rename_branch_select"),
                this.dialogLeft + PADDING, this.dialogTop + 24, COLOR_WHITE);
        this.drawStringWithShadow(ctx, StringUtils.translate("litematica.gui.label.lvc_project.rename_branch_new_name"),
                this.dialogLeft + PADDING, this.dialogTop + 67, COLOR_WHITE);

        super.drawWidgets(ctx, mouseX, mouseY);

        if (this.nameField != null)
        {
            this.nameField.extractRenderState(ctx.getGuiGraphics(), mouseX, mouseY, partialTicks);
        }

        if (this.errorMessage != null && this.nameField != null)
        {
            int errorTop = this.getErrorTopY();
            int errorLeft = this.nameField.getX() + ERROR_HORIZONTAL_INSET;
            int errorWidth = this.nameField.getWidth() - ERROR_HORIZONTAL_INSET * 2;
            RenderUtils.drawOutlinedBox(ctx, errorLeft, errorTop, errorWidth, ERROR_BOX_HEIGHT, 0x80300000, 0xFFFF5555);
            ctx.drawString(ctx.fontRenderer(), this.errorMessage, errorLeft + 4, errorTop + 2, 0xFFFF5555, false);
        }

        super.drawButtons(ctx, this.isDropdownOpen() ? BACKGROUND_MOUSE : mouseX, this.isDropdownOpen() ? BACKGROUND_MOUSE : mouseY, partialTicks);

        if (this.branchDropdown != null && this.branchDropdown.isOpen())
        {
            this.branchDropdown.render(ctx, mouseX, mouseY, false);
        }
    }

    private void createButtons()
    {
        String renameLabel = StringUtils.translate("litematica.gui.button.lvc_project.branch_action_rename");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int renameWidth = Math.max(72, this.getStringWidth(renameLabel) + 18);
        int cancelWidth = Math.max(60, this.getStringWidth(cancelLabel) + 18);
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        int renameX = this.getDropdownAlignedButtonX();
        int cancelX = renameX + renameWidth + BUTTON_GAP;

        this.addButton(new ButtonGeneric(renameX, y, renameWidth, BUTTON_HEIGHT, renameLabel),
                (button, mouseButton) -> this.renameSelectedBranch());
        this.addButton(new ButtonGeneric(cancelX, y, cancelWidth, BUTTON_HEIGHT, cancelLabel),
                (button, mouseButton) -> this.closeGui(true));
    }

    private int getDropdownAlignedButtonX()
    {
        int dropdownX = this.branchDropdown != null ? this.branchDropdown.getX() : this.dialogLeft + PADDING;
        return dropdownX - BUTTON_TEXTURE_LEFT_INSET;
    }

    private void renameSelectedBranch()
    {
        String newBranchName = this.getNewBranchName();
        String validationError = this.controller.validateRenameBranchInputs(this.selectedBranch, newBranchName);

        if (validationError != null && !validationError.isBlank())
        {
            this.errorMessage = validationError;
            this.showErrorSpace();
            this.setNameFieldFocused(true);
            return;
        }

        this.errorMessage = null;
        this.closeGui(true);
        this.controller.renameBranch(this.selectedBranch, newBranchName);
    }

    private String getNewBranchName()
    {
        return this.nameField == null ? "" : this.nameField.getValue();
    }

    private void setNameFieldFocused(boolean focused)
    {
        if (this.nameField != null)
        {
            this.nameField.setFocused(focused);
        }
    }

    private boolean isDropdownOpen()
    {
        return this.branchDropdown != null && this.branchDropdown.isOpen();
    }

    private int getDialogHeight()
    {
        return DIALOG_HEIGHT + (this.errorSpaceVisible ? ERROR_VERTICAL_SPACE : 0);
    }

    private int getErrorTopY()
    {
        if (this.nameField == null)
        {
            return this.dialogTop + DIALOG_HEIGHT - PADDING - BUTTON_HEIGHT - ERROR_BOX_HEIGHT;
        }

        int nameBottom = this.nameField.getY() + this.nameField.getHeight();
        int buttonY = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        int availableSpace = Math.max(0, buttonY - nameBottom - ERROR_BOX_HEIGHT);
        return nameBottom + availableSpace / 2;
    }

    private void showErrorSpace()
    {
        if (this.errorSpaceVisible)
        {
            return;
        }

        this.errorSpaceVisible = true;
        this.initGui();
    }

    private List<String> normalizeBranches(List<String> branches)
    {
        List<String> normalizedBranches = new ArrayList<>();

        for (String branch : branches)
        {
            if (branch != null && !branch.isBlank() && !normalizedBranches.contains(branch))
            {
                normalizedBranches.add(branch);
            }
        }

        return normalizedBranches;
    }
}
