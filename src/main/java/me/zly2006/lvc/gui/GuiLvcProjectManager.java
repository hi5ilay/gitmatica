package me.zly2006.lvc.gui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.gui.widgets.WidgetLvcBranchActionMenu;
import me.zly2006.lvc.gui.widgets.WidgetLvcBranchDropdown;

import fi.dy.masa.litematica.Reference;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiLvcProjectManager extends GuiBase implements ICompletionListener
{
    private static final GuiLvcProjectButtonType[] TOP_ACTIONS = {
            GuiLvcProjectButtonType.SAVE_VERSION,
            GuiLvcProjectButtonType.DISCARD_CHANGES,
            GuiLvcProjectButtonType.CLEAR_AREA,
            GuiLvcProjectButtonType.PUSH,
            GuiLvcProjectButtonType.PULL,
            GuiLvcProjectButtonType.BRANCH_SELECTOR
    };
    private static final GuiLvcProjectButtonType[] BOTTOM_ACTIONS = {
            GuiLvcProjectButtonType.CHECKOUT_VERSION,
            GuiLvcProjectButtonType.VIEW_CHANGES,
            GuiLvcProjectButtonType.REVERT_CHANGES,
            GuiLvcProjectButtonType.EXPORT,
            GuiLvcProjectButtonType.LITEMATICA_MENU
    };
    private static final int RIGHT_MOUSE_BUTTON = 1;
    private static final int PANEL_MARGIN = 10;
    private static final int TOP_BUTTON_Y = 28;
    private static final int BUTTON_ROW_GAP = 4;
    private static final int CONTENT_BUTTON_GAP = 8;
    private static final int SIDEBAR_METADATA_BUTTON_GAP = 8;
    private static final int SIDEBAR_BUTTON_HEIGHT = 20;
    private static final int SIDEBAR_BUTTON_STEP = 24;
    private static final int BRANCH_DROPDOWN_LEFT_GAP = 8;
    private static final int BRANCH_DROPDOWN_WIDTH = 90;
    private static final int BRANCH_DROPDOWN_VISIBLE_ROWS = 8;
    static final int PANEL_OUTLINE_COLOR = COLOR_HORIZONTAL_BAR;

    final Path repositoryDirectory;
    final String projectName;
    List<LvcProjectService.CommitInfo> history = List.of();
    @Nullable LvcProjectService.CommitInfo selectedCommit;
    @Nullable LvcProjectService.TrackingOverlay trackingOverlay;
    @Nullable String remoteUrl;
    List<String> branchNames = List.of(LvcProjectService.DEFAULT_BRANCH);
    @Nullable private WidgetLvcBranchActionMenu branchActionMenu;
    @Nullable private WidgetLvcBranchDropdown branchDropdown;
    @Nullable private GuiTextFieldGeneric historySearchField;
    private ScrollbarDragTarget draggingScrollbar = ScrollbarDragTarget.NONE;
    String trackingStatus = "";
    boolean detachedHead;
    boolean initialOverlayAttempted;
    String checkoutBranchName = LvcProjectService.DEFAULT_BRANCH;
    String headPointerName = LvcProjectService.DEFAULT_BRANCH;
    private int topActionRows = 1;
    private int bottomActionRows = 1;
    final LvcCommitHistoryPanel historyPanel = new LvcCommitHistoryPanel(this);
    final LvcCommitMetadataPanel commitMetadataPanel = new LvcCommitMetadataPanel(this);
    private final GuiLvcProjectController controller = new GuiLvcProjectController(this);

    public GuiLvcProjectManager(Path repositoryDirectory, String projectName)
    {
        this.repositoryDirectory = repositoryDirectory;
        this.projectName = projectName;
        this.updateTitle();
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.controller.refreshRepositoryState();
        this.refreshHistory();
        this.topActionRows = this.calculateButtonRows(TOP_ACTIONS);
        this.bottomActionRows = this.calculateButtonRows(BOTTOM_ACTIONS);
        this.branchActionMenu = null;
        this.branchDropdown = null;
        this.historySearchField = null;
        this.draggingScrollbar = ScrollbarDragTarget.NONE;
        this.createHistorySearchField();
        this.createWrappedButtonRow(TOP_BUTTON_Y, TOP_ACTIONS);
        this.createWrappedButtonRow(this.getBottomButtonY(), BOTTOM_ACTIONS);

        this.createSidebarButtons();
        this.controller.loadInitialTrackingOverlay();
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);

        this.historyPanel.draw(ctx, mouseX, mouseY);
        this.commitMetadataPanel.draw(ctx);
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.isBranchDropdownRightClick(click))
        {
            this.openBranchActionMenuFromDropdown();
            return true;
        }

        if (this.branchActionMenu != null)
        {
            if (this.branchActionMenu.isMouseOver((int) click.x(), (int) click.y()))
            {
                return this.branchActionMenu.onMouseClicked(click, doubleClick);
            }

            if (!this.isMouseOverBranchDropdownButton((int) click.x(), (int) click.y()))
            {
                this.closeBranchActionMenu();
                return true;
            }

            this.closeBranchActionMenu();
        }

        if (this.branchDropdown != null && this.branchDropdown.isOpen() && !this.branchDropdown.isMouseOver((int) click.x(), (int) click.y()))
        {
            this.branchDropdown.close();
            return true;
        }

        if (super.onMouseClicked(click, doubleClick))
        {
            return true;
        }

        if (click.input() == 0 && this.beginScrollbarDrag((int) click.x(), (int) click.y()))
        {
            return true;
        }

        if (click.input() == 0)
        {
            LvcProjectService.CommitInfo undoCommit = this.historyPanel.getUndoCommitAt((int) click.x(), (int) click.y());

            if (undoCommit != null)
            {
                this.controller.promptDeleteLatestVersion(undoCommit);
                return true;
            }
        }

        LvcProjectService.CommitInfo clickedCommit = this.historyPanel.getCommitAt((int) click.x(), (int) click.y());

        if (clickedCommit != null)
        {
            if (this.selectedCommit == null || !this.selectedCommit.id().equals(clickedCommit.id()))
            {
                this.commitMetadataPanel.resetScroll();
            }

            this.selectedCommit = clickedCommit;
            return true;
        }

        return false;
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click)
    {
        boolean handled = false;

        if (this.branchDropdown != null && this.branchDropdown.isOpen())
        {
            boolean wasDraggingDropdownScrollbar = this.branchDropdown.isDraggingScrollbar();
            this.branchDropdown.onMouseReleased(click);
            handled = wasDraggingDropdownScrollbar;
        }

        if (click.input() == 0 && this.draggingScrollbar != ScrollbarDragTarget.NONE)
        {
            this.draggingScrollbar = ScrollbarDragTarget.NONE;
            handled = true;
        }

        return super.onMouseReleased(click) || handled;
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragXAmount, double dragYAmount)
    {
        if (this.branchDropdown != null && this.branchDropdown.isOpen() &&
                this.branchDropdown.onMouseDragged(click, dragXAmount, dragYAmount))
        {
            return true;
        }

        if (this.draggingScrollbar == ScrollbarDragTarget.HISTORY)
        {
            this.historyPanel.scrollToMouseY((int) click.y());
            return true;
        }

        if (this.draggingScrollbar == ScrollbarDragTarget.COMMIT_METADATA)
        {
            this.commitMetadataPanel.scrollToMouseY((int) click.y());
            return true;
        }

        return super.onMouseDragged(click, dragXAmount, dragYAmount);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (this.branchDropdown != null && this.branchDropdown.isOpen() &&
                this.branchDropdown.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount))
        {
            return true;
        }

        if (this.commitMetadataPanel.onMouseScrolled(mouseX, mouseY, verticalAmount))
        {
            return true;
        }

        if (this.historyPanel.onMouseScrolled(mouseX, mouseY, verticalAmount))
        {
            return true;
        }

        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
    protected void drawButtons(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        if (this.branchActionMenu != null)
        {
            super.drawButtons(ctx, Integer.MIN_VALUE, Integer.MIN_VALUE, partialTicks);
            return;
        }

        super.drawButtons(ctx, mouseX, mouseY, partialTicks);
    }

    @Override
    protected void drawTextFields(GuiContext ctx, int mouseX, int mouseY)
    {
        super.drawTextFields(ctx, mouseX, mouseY);

        if (this.branchDropdown != null && this.branchDropdown.isOpen())
        {
            this.branchDropdown.render(ctx, mouseX, mouseY, false);
        }

        if (this.branchActionMenu != null)
        {
            this.branchActionMenu.render(ctx, mouseX, mouseY, false);
        }
    }

    private boolean beginScrollbarDrag(int mouseX, int mouseY)
    {
        if (this.historyPanel.isMouseOverScrollbar(mouseX, mouseY))
        {
            this.historyPanel.scrollToMouseY(mouseY);
            this.draggingScrollbar = ScrollbarDragTarget.HISTORY;
            return true;
        }

        if (this.commitMetadataPanel.isMouseOverScrollbar(mouseX, mouseY))
        {
            this.commitMetadataPanel.scrollToMouseY(mouseY);
            this.draggingScrollbar = ScrollbarDragTarget.COMMIT_METADATA;
            return true;
        }

        return false;
    }

    private int createButton(int x, int y, GuiLvcProjectButtonType type)
    {
        if (type == GuiLvcProjectButtonType.BRANCH_SELECTOR)
        {
            return this.createBranchDropdown(x, y);
        }

        ButtonGeneric button = this.createButtonWidget(x, y, -1, type);
        this.addButton(button, this.controller.buttonListener(type));

        return button.getWidth() + BUTTON_ROW_GAP;
    }

    private void openBranchActionMenuFromDropdown()
    {
        if (this.branchActionMenu != null)
        {
            this.closeBranchActionMenu();
        }

        WidgetLvcBranchDropdown dropdown = this.branchDropdown;

        if (dropdown == null)
        {
            return;
        }

        this.blurBranchOverlayInputs();
        this.branchActionMenu = new WidgetLvcBranchActionMenu(
                dropdown.getX(),
                dropdown.getY(),
                dropdown.getWidth(),
                dropdown.getHeight(),
                this.getScreenWidth(),
                this.getScreenHeight(),
                this.controller::handleBranchMenuAction
        );
        this.closeBranchDropdown();
    }

    void closeBranchActionMenu()
    {
        this.branchActionMenu = null;
    }

    private void closeBranchDropdown()
    {
        if (this.branchDropdown != null)
        {
            this.branchDropdown.close();
        }
    }

    void blurBranchOverlayInputs()
    {
        if (this.historySearchField != null)
        {
            this.historySearchField.setFocused(false);
        }
    }

    private boolean isBranchDropdownRightClick(MouseButtonEvent click)
    {
        return click.input() == RIGHT_MOUSE_BUTTON && this.isMouseOverBranchDropdownButton((int) click.x(), (int) click.y());
    }

    private boolean isMouseOverBranchDropdownButton(int mouseX, int mouseY)
    {
        WidgetLvcBranchDropdown dropdown = this.branchDropdown;
        return dropdown != null && mouseX >= dropdown.getX() && mouseX < dropdown.getX() + dropdown.getWidth() &&
                mouseY >= dropdown.getY() && mouseY < dropdown.getY() + dropdown.getHeight();
    }

    private int createRightButton(int rightX, int y, GuiLvcProjectButtonType type)
    {
        ButtonGeneric button = this.createButtonWidget(0, y, -1, type);
        button.setX(rightX - button.getWidth());
        this.addButton(button, this.controller.buttonListener(type));
        return button.getWidth();
    }

    private ButtonGeneric createButtonWidget(int x, int y, int width, GuiLvcProjectButtonType type)
    {
        String label = type.getLabel(this.checkoutBranchName);
        IGuiIcon icon = type.icon();
        ButtonGeneric button = icon != null ?
                new ButtonGeneric(x, y, width, SIDEBAR_BUTTON_HEIGHT, label, icon) :
                new ButtonGeneric(x, y, width, SIDEBAR_BUTTON_HEIGHT, label);

        if (type == GuiLvcProjectButtonType.CLOSE_PROJECT)
        {
            button.setTextCentered(false);
        }

        if (this.isDulledAction(type))
        {
            button.setEnabled(false);
        }

        return button;
    }

    private int calculateButtonRows(GuiLvcProjectButtonType[] types)
    {
        int startX = this.getHistoryPanelVisibleLeftX();
        int maxRightX = this.getScreenWidth() - PANEL_MARGIN;
        int x = startX;
        int rows = 1;

        for (int index = 0; index < types.length; index++)
        {
            GuiLvcProjectButtonType type = types[index];

            if (this.isBranchControlGroupStart(types, index))
            {
                int width = this.getBranchControlGroupWidth();
                int alignedX = this.getRightAlignedActionX(width);

                if (x > startX && x + BRANCH_DROPDOWN_LEFT_GAP > alignedX)
                {
                    rows++;
                    x = startX;
                }

                x = alignedX + width + BUTTON_ROW_GAP;
                index += 2;
                continue;
            }

            int width = this.getButtonWidth(type);

            if (this.isRightAlignedAction(type))
            {
                int alignedX = this.getRightAlignedActionX(width);

                if (x > startX && x + BRANCH_DROPDOWN_LEFT_GAP > alignedX)
                {
                    rows++;
                    x = startX;
                }

                x = alignedX + width + BUTTON_ROW_GAP;
                continue;
            }

            if (x > startX && x + width > maxRightX)
            {
                rows++;
                x = startX;
            }

            x += width + BUTTON_ROW_GAP;
        }

        return rows;
    }

    private void createWrappedButtonRow(int startY, GuiLvcProjectButtonType[] types)
    {
        int startX = this.getHistoryPanelVisibleLeftX();
        int maxRightX = this.getScreenWidth() - PANEL_MARGIN;
        int x = startX;
        int y = startY;

        for (int index = 0; index < types.length; index++)
        {
            GuiLvcProjectButtonType type = types[index];

            if (this.isBranchControlGroupStart(types, index))
            {
                int width = this.getBranchControlGroupWidth();
                int alignedX = this.getRightAlignedActionX(width);

                if (x > startX && x + BRANCH_DROPDOWN_LEFT_GAP > alignedX)
                {
                    x = startX;
                    y += SIDEBAR_BUTTON_HEIGHT + BUTTON_ROW_GAP;
                }

                this.createBranchControlGroup(alignedX, y);
                x = alignedX + width + BUTTON_ROW_GAP;
                index += 2;
                continue;
            }

            int width = this.getButtonWidth(type);

            if (this.isRightAlignedAction(type))
            {
                int alignedX = this.getRightAlignedActionX(width);

                if (x > startX && x + BRANCH_DROPDOWN_LEFT_GAP > alignedX)
                {
                    x = startX;
                    y += SIDEBAR_BUTTON_HEIGHT + BUTTON_ROW_GAP;
                }

                if (type == GuiLvcProjectButtonType.BRANCH_SELECTOR)
                {
                    this.createBranchDropdown(alignedX, y);
                }
                else
                {
                    this.createRightButton(alignedX + width, y, type);
                }

                x = alignedX + width + BUTTON_ROW_GAP;
                continue;
            }

            if (x > startX && x + width > maxRightX)
            {
                x = startX;
                y += SIDEBAR_BUTTON_HEIGHT + BUTTON_ROW_GAP;
            }

            x += this.createButton(x, y, type);
        }
    }

    private int getButtonWidth(GuiLvcProjectButtonType type)
    {
        if (type == GuiLvcProjectButtonType.BRANCH_SELECTOR)
        {
            return this.getBranchDropdownWidth();
        }

        return this.createButtonWidget(0, 0, -1, type).getWidth();
    }

    private boolean isBranchControlGroupStart(GuiLvcProjectButtonType[] types, int index)
    {
        return index + 2 < types.length &&
                types[index] == GuiLvcProjectButtonType.PUSH &&
                types[index + 1] == GuiLvcProjectButtonType.PULL &&
                types[index + 2] == GuiLvcProjectButtonType.BRANCH_SELECTOR;
    }

    private int getBranchControlGroupWidth()
    {
        return this.getButtonWidth(GuiLvcProjectButtonType.PUSH) + BUTTON_ROW_GAP +
                this.getButtonWidth(GuiLvcProjectButtonType.PULL) + BUTTON_ROW_GAP +
                this.getBranchDropdownWidth();
    }

    private int createBranchControlGroup(int x, int y)
    {
        int startX = x;
        x += this.createButton(x, y, GuiLvcProjectButtonType.PUSH);
        x += this.createButton(x, y, GuiLvcProjectButtonType.PULL);
        x += this.createBranchDropdown(x, y);
        return x - startX;
    }

    private int createBranchDropdown(int x, int y)
    {
        this.branchDropdown = this.addWidget(new WidgetLvcBranchDropdown(
                x,
                y,
                this.getBranchDropdownWidth(),
                SIDEBAR_BUTTON_HEIGHT,
                BRANCH_DROPDOWN_VISIBLE_ROWS,
                this.branchNames,
                this.currentBranchDropdownName(),
                this.currentHeadBranchName(),
                this.controller::handleBranchDropdownSelection
        ));

        return this.branchDropdown.getWidth() + BUTTON_ROW_GAP;
    }

    private boolean isDulledAction(GuiLvcProjectButtonType type)
    {
        return type == GuiLvcProjectButtonType.PUSH ||
                type == GuiLvcProjectButtonType.PULL ||
                type == GuiLvcProjectButtonType.REVERT_CHANGES;
    }

    private int getBranchDropdownWidth()
    {
        return BRANCH_DROPDOWN_WIDTH;
    }

    private boolean isRightAlignedAction(GuiLvcProjectButtonType type)
    {
        return type == GuiLvcProjectButtonType.BRANCH_SELECTOR || type == GuiLvcProjectButtonType.LITEMATICA_MENU;
    }

    private int getRightAlignedActionX(int width)
    {
        int rightX = this.getSidebarX() + this.getSidebarWidth();
        return Math.max(this.getHistoryPanelVisibleLeftX(), rightX - width);
    }

    private void createHistorySearchField()
    {
        int x = this.getHistoryPanelX() + 22;
        int searchY = this.getContentTopY() + 4;
        int y = searchY + (LvcCommitHistoryPanel.SEARCH_HEIGHT - this.font.lineHeight) / 2;
        int width = Math.max(20, this.getHistoryPanelWidth() - 30);
        GuiTextFieldGeneric textField = new GuiTextFieldGeneric(x, y, width, 14, this.font);

        textField.setBordered(false);
        textField.setTextColor(0xFFFFFFFF);
        textField.setTextColorUneditable(0xFFAAAAAA);
        textField.setMaxLength(128);
        textField.setValueWrapper(this.historyPanel.searchQuery());
        this.historySearchField = textField;
        this.addTextField(textField, new HistorySearchListener(this));
    }

    private void createSidebarButtons()
    {
        int y = this.getSidebarActionsY();
        int width = this.createButtonWidget(0, 0, -1, GuiLvcProjectButtonType.PROJECT_SETTINGS).getWidth();
        int x = this.getSidebarX() - 1;

        this.addButton(this.createButtonWidget(x, y, width, GuiLvcProjectButtonType.PROJECT_EDITOR), this.controller.buttonListener(GuiLvcProjectButtonType.PROJECT_EDITOR));
        y += SIDEBAR_BUTTON_STEP;
        this.addButton(this.createButtonWidget(x, y, width, GuiLvcProjectButtonType.PROJECT_SETTINGS), this.controller.buttonListener(GuiLvcProjectButtonType.PROJECT_SETTINGS));
        y += SIDEBAR_BUTTON_STEP;
        this.addButton(this.createButtonWidget(x, y, width, GuiLvcProjectButtonType.CLOSE_PROJECT), this.controller.buttonListener(GuiLvcProjectButtonType.CLOSE_PROJECT));
    }

    private int getBottomButtonY()
    {
        return this.getScreenHeight() - CONTENT_BUTTON_GAP / 2 - this.getButtonRowsHeight(this.bottomActionRows);
    }

    int getContentBottomY()
    {
        return this.getBottomButtonY() - 8;
    }

    int getContentTopY()
    {
        return TOP_BUTTON_Y + this.getButtonRowsHeight(this.topActionRows) + CONTENT_BUTTON_GAP;
    }

    private int getButtonRowsHeight(int rows)
    {
        return rows * SIDEBAR_BUTTON_HEIGHT + Math.max(0, rows - 1) * BUTTON_ROW_GAP;
    }

    int getHistoryPanelX()
    {
        return PANEL_MARGIN;
    }

    private int getHistoryPanelVisibleLeftX()
    {
        return this.getHistoryPanelX() - 1;
    }

    int getHistoryPanelWidth()
    {
        return Math.max(40, this.getSidebarX() - this.getHistoryPanelX() - 8);
    }

    int getSidebarWidth()
    {
        int availableWidth = Math.max(0, this.getScreenWidth() - PANEL_MARGIN * 2);

        if (availableWidth < 380)
        {
            return Math.max(96, availableWidth / 2);
        }

        return Math.clamp(this.getScreenWidth() / 3, 190, 260);
    }

    int getSidebarX()
    {
        return this.getScreenWidth() - this.getSidebarWidth() - PANEL_MARGIN;
    }

    private int getSidebarActionsY()
    {
        int actionY = this.getContentBottomY() - SIDEBAR_BUTTON_HEIGHT - SIDEBAR_BUTTON_STEP * 2 + 1;
        return Math.max(this.getContentTopY(), actionY);
    }

    int getInfoPanelHeight()
    {
        return Math.max(0, this.getSidebarActionsY() - this.getContentTopY() - SIDEBAR_METADATA_BUTTON_GAP);
    }

    int getHistoryStartY()
    {
        return this.getContentTopY() + LvcCommitHistoryPanel.SEARCH_HEIGHT + 9;
    }

    int textWidth(String text)
    {
        return this.getStringWidth(text);
    }

    int fontLineHeight()
    {
        return this.font.lineHeight;
    }

    private void refreshRepositoryState()
    {
        this.detachedHead = false;
        this.checkoutBranchName = LvcProjectService.DEFAULT_BRANCH;
        this.headPointerName = LvcProjectService.DEFAULT_BRANCH;
        this.remoteUrl = null;
        this.branchNames = List.of(LvcProjectService.DEFAULT_BRANCH);

        try
        {
            this.detachedHead = LvcProjectService.isDetachedHead(this.repositoryDirectory);
            this.remoteUrl = LvcProjectService.remoteOriginUrl(this.repositoryDirectory);
            this.checkoutBranchName = LvcProjectService.preferredCheckoutBranchName(this.repositoryDirectory);
            this.headPointerName = LvcProjectService.headPointerName(this.repositoryDirectory);
            this.setBranchNames(LvcProjectService.listLocalBranches(this.repositoryDirectory));
        }
        catch (Exception e)
        {
            this.addMessage(MessageType.ERROR, "litematica.error.lvc_project.history_failed", e.getMessage());
        }

        this.updateTitle();
    }

    void setBranchNames(List<String> branchNames)
    {
        List<String> normalizedBranches = new ArrayList<>();

        for (String branchName : branchNames)
        {
            if (branchName != null && !branchName.isBlank() && !normalizedBranches.contains(branchName))
            {
                normalizedBranches.add(branchName);
            }
        }

        if (!this.detachedHead)
        {
            this.addFallbackBranchName(normalizedBranches, this.headPointerName);
        }

        this.addFallbackBranchName(normalizedBranches, this.checkoutBranchName);

        if (normalizedBranches.isEmpty())
        {
            normalizedBranches.add(LvcProjectService.DEFAULT_BRANCH);
        }

        this.branchNames = List.copyOf(normalizedBranches);
        this.syncBranchDropdownSelection();
    }

    private void addFallbackBranchName(List<String> branchNames, String branchName)
    {
        if (branchName != null && !branchName.isBlank() && !"unknown".equals(branchName) && !branchNames.contains(branchName))
        {
            branchNames.add(branchName);
        }
    }

    String currentBranchDropdownName()
    {
        if (!this.detachedHead && this.branchNames.contains(this.headPointerName))
        {
            return this.headPointerName;
        }

        if (this.branchNames.contains(this.checkoutBranchName))
        {
            return this.checkoutBranchName;
        }

        return this.branchNames.isEmpty() ? LvcProjectService.DEFAULT_BRANCH : this.branchNames.get(0);
    }

    @Nullable
    private String currentHeadBranchName()
    {
        return !this.detachedHead && this.branchNames.contains(this.headPointerName) ? this.headPointerName : null;
    }

    void syncBranchDropdownSelection()
    {
        if (this.branchDropdown != null)
        {
            this.branchDropdown.setBranches(this.branchNames);
            this.branchDropdown.setSelectedBranch(this.currentBranchDropdownName());
            this.branchDropdown.setHeadBranch(this.currentHeadBranchName());
        }
    }

    void updateTitle()
    {
        String headName = this.headPointerName == null ? "" : this.headPointerName;
        String displayedHeadName = this.ellipsizeTitleHeadName(headName);
        this.title = StringUtils.translate("litematica.gui.title.lvc_project", Reference.MOD_VERSION, this.projectName, displayedHeadName);

        if (!displayedHeadName.equals(headName))
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: clipped title head branch repo='{}' branch='{}' displayed='{}'",
                    this.repositoryDirectory, headName, displayedHeadName);
        }
    }

    private String ellipsizeTitleHeadName(String headName)
    {
        int maxTitleWidth = this.getScreenWidth() - PANEL_MARGIN * 2;

        if (maxTitleWidth <= 0 || this.getStringWidth(this.projectTitle(headName)) <= maxTitleWidth)
        {
            return headName;
        }

        String suffix = "...";
        int suffixWidth = this.getStringWidth(suffix);

        if (suffixWidth > maxTitleWidth)
        {
            return "";
        }

        for (int length = headName.length(); length > 0; length--)
        {
            String candidate = headName.substring(0, length) + suffix;

            if (this.getStringWidth(this.projectTitle(candidate)) <= maxTitleWidth)
            {
                return candidate;
            }
        }

        return suffix;
    }

    private String projectTitle(String headName)
    {
        return StringUtils.translate("litematica.gui.title.lvc_project", Reference.MOD_VERSION, this.projectName, headName);
    }

    void refreshHistory()
    {
        try
        {
            this.history = LvcProjectService.listCommits(this.repositoryDirectory);

            if (!this.historyPanel.focusPendingCommit())
            {
                this.historyPanel.retainSelectedVisibleCommit();
            }
        }
        catch (Exception e)
        {
            this.history = List.of();
            this.selectedCommit = null;
            this.historyPanel.clearAfterHistoryLoadFailure();
            this.commitMetadataPanel.resetScroll();
            this.addMessage(MessageType.ERROR, "litematica.error.lvc_project.history_failed", e.getMessage());
        }
    }

    void focusHistoryCommitAfterNextRefresh(String commitId)
    {
        this.historyPanel.focusCommitAfterNextRefresh(commitId);
    }

    boolean hasHistory()
    {
        return !this.history.isEmpty();
    }

    @Override
    public void onTaskCompleted()
    {
        if (this.trackingOverlay == null)
        {
            return;
        }

        int errors = this.trackingOverlay.verifier().getTotalErrors();
        this.trackingStatus = errors == 0 ?
                StringUtils.translate("litematica.gui.label.lvc_project.tracking_clean") :
                StringUtils.translate("litematica.gui.label.lvc_project.tracking_dirty", errors);
    }

    @Override
    public void onTaskAborted()
    {
        this.trackingStatus = StringUtils.translate("litematica.gui.label.lvc_project.tracking_aborted");
    }

    private enum ScrollbarDragTarget
    {
        NONE,
        HISTORY,
        COMMIT_METADATA
    }

    private record HistorySearchListener(GuiLvcProjectManager gui) implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            this.gui.historyPanel.setSearchQuery(textField.getValueWrapper());
            this.gui.historyPanel.retainSelectedVisibleCommit();
            return false;
        }
    }

}
