package me.zly2006.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import me.zly2006.lvc.gui.widgets.WidgetLvcBranchDropdown;

import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcMergeBranchDialog extends GuiDialogBase
{
    private static final int DIALOG_WIDTH = 244;
    private static final int DIALOG_HEIGHT = 98;
    private static final int PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_TEXTURE_LEFT_INSET = 2;
    private static final int DROPDOWN_HEIGHT = 20;
    private static final int DROPDOWN_VISIBLE_ROWS = 6;
    private static final int BACKGROUND_MOUSE = Integer.MIN_VALUE;

    private final GuiLvcProjectController controller;
    private final List<String> branches;
    @Nullable private WidgetLvcBranchDropdown branchDropdown;
    @Nullable private String selectedBranch;

    GuiLvcMergeBranchDialog(GuiLvcProjectController controller, List<String> branches)
    {
        this.controller = controller;
        this.branches = List.copyOf(this.normalizeBranches(branches));
        this.selectedBranch = this.branches.isEmpty() ? null : this.branches.get(0);
        this.setParent(controller.gui);
        this.title = StringUtils.translate("litematica.gui.title.lvc_project.merge_branch");
        this.useTitleHierarchy = false;
        this.setWidthAndHeight(DIALOG_WIDTH, DIALOG_HEIGHT);
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.setWidthAndHeight(DIALOG_WIDTH, DIALOG_HEIGHT);
        this.centerOnScreen();

        int dropdownX = this.dialogLeft + PADDING;
        int dropdownY = this.dialogTop + 38;
        int dropdownWidth = this.dialogWidth - PADDING * 2;
        this.branchDropdown = this.addWidget(new WidgetLvcBranchDropdown(
                dropdownX,
                dropdownY,
                dropdownWidth,
                DROPDOWN_HEIGHT,
                DROPDOWN_VISIBLE_ROWS,
                this.branches,
                this.selectedBranch,
                null,
                branchName -> this.selectedBranch = branchName
        ));

        this.createButtons();
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.branchDropdown != null && this.branchDropdown.isOpen())
        {
            if (this.branchDropdown.isMouseOver((int) click.x(), (int) click.y()))
            {
                return this.branchDropdown.onMouseClicked(click, doubleClick);
            }

            this.branchDropdown.close();
            return true;
        }

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

        return super.onKeyTyped(input);
    }

    @Override
    public boolean onCharTyped(CharacterEvent input)
    {
        if (this.branchDropdown != null && this.branchDropdown.isOpen() && this.branchDropdown.onCharTyped(input))
        {
            return true;
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
        this.drawStringWithShadow(ctx, StringUtils.translate("litematica.gui.label.lvc_project.merge_branch_select"),
                this.dialogLeft + PADDING, this.dialogTop + 24, COLOR_WHITE);

        super.drawWidgets(ctx, mouseX, mouseY);
        super.drawButtons(ctx, this.isDropdownOpen() ? BACKGROUND_MOUSE : mouseX, this.isDropdownOpen() ? BACKGROUND_MOUSE : mouseY, partialTicks);

        if (this.branchDropdown != null && this.branchDropdown.isOpen())
        {
            this.branchDropdown.render(ctx, mouseX, mouseY, false);
        }
    }

    private void createButtons()
    {
        String mergeLabel = StringUtils.translate("litematica.gui.button.lvc_project.branch_action_merge");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int mergeWidth = Math.max(72, this.getStringWidth(mergeLabel) + 18);
        int cancelWidth = Math.max(60, this.getStringWidth(cancelLabel) + 18);
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        int mergeX = this.getDropdownAlignedButtonX();
        int cancelX = mergeX + mergeWidth + BUTTON_GAP;

        this.addButton(new ButtonGeneric(mergeX, y, mergeWidth, BUTTON_HEIGHT, mergeLabel),
                (button, mouseButton) -> this.mergeSelectedBranch());
        this.addButton(new ButtonGeneric(cancelX, y, cancelWidth, BUTTON_HEIGHT, cancelLabel),
                (button, mouseButton) -> this.closeGui(true));
    }

    private int getDropdownAlignedButtonX()
    {
        int dropdownX = this.branchDropdown != null ? this.branchDropdown.getX() : this.dialogLeft + PADDING;
        return dropdownX - BUTTON_TEXTURE_LEFT_INSET;
    }

    private void mergeSelectedBranch()
    {
        if (this.controller.mergeBranch(this.selectedBranch))
        {
            this.closeGui(true);
        }
    }

    private boolean isDropdownOpen()
    {
        return this.branchDropdown != null && this.branchDropdown.isOpen();
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
