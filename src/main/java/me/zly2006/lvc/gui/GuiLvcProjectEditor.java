package me.zly2006.lvc.gui;

import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.gui.widgets.WidgetLvcProjectSubRegion;
import me.zly2006.lvc.gui.widgets.WidgetLvcProjectSubRegionList;
import me.zly2006.lvc.model.LvcManifest;

import fi.dy.masa.litematica.Reference;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiLvcProjectEditor extends GuiListBase<LvcManifest.Region, WidgetLvcProjectSubRegion, WidgetLvcProjectSubRegionList>
        implements ISelectionListener<LvcManifest.Region>
{
    private static final int MARGIN = 10;
    private static final int SUB_REGION_LIST_X = MARGIN - 2;
    private static final int TOP_BUTTON_X = LEFT - 9;
    private static final int FORM_X = TOP_BUTTON_X + 3;
    private static final int TOP_Y = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GROUP_GAP = 4;
    private static final int TEXT_FIELD_HEIGHT = 16;
    private static final int COORDINATE_FIELD_X_OFFSET = 12;
    private static final int COORDINATE_FIELD_WIDTH = 68;
    private static final int COORDINATE_BUTTON_GAP = 4;
    private static final int COORDINATE_NUDGE_BUTTON_WIDTH = 16;
    private static final int COORDINATE_ROW_SPACING = 20;
    private static final int COORDINATE_TITLE_TEXT_OFFSET = 5;
    private static final int COORDINATE_HEADER_HEIGHT = 14;
    private static final int COORDINATE_MOVE_OFFSET_FROM_FIELDS = COORDINATE_ROW_SPACING * 2 + 22;
    private static final int COORDINATE_TOP_Y = TOP - COORDINATE_TITLE_TEXT_OFFSET;
    private static final int COORDINATE_GROUP_WIDTH = 168;
    private static final int COORDINATE_GROUP_LEFT_GAP = 16;
    private static final int PROJECT_NAME_FIELD_WIDTH = 286;
    private static final int PROJECT_NAME_TOP_GAP = 4;
    private static final int ACTION_BUTTON_TOP_GAP = 4;
    private static final int STATUS_COLOR = 0xFFAAAAAA;
    private static final int ERROR_COLOR = 0xFFFF5555;
    private static final int SUCCESS_COLOR = 0xFF55FF55;
    private static final int INPUT_FILL_COLOR = 0xE0000000;
    private static final int INPUT_OUTLINE_COLOR = 0xFF808080;
    private static final int READ_ONLY_TEXT_COLOR = 0xFFFFFFFF;
    private static final String PLUS_MINUS_HOVER = "litematica.gui.button.hover.plus_minus_tip_ctrl_alt_shift";

    private final Path repositoryDirectory;
    private String projectName;
    @Nullable private LvcProjectService.ProjectEditorState state;
    @Nullable private String selectedRegionId;
    private String statusText = "";
    private int statusColor = STATUS_COLOR;

    public GuiLvcProjectEditor(Path repositoryDirectory, String projectName)
    {
        super(MARGIN, TOP_Y + 128);
        this.repositoryDirectory = repositoryDirectory;
        this.projectName = projectName;
        this.title = StringUtils.translate("litematica.gui.title.lvc_project_editor", Reference.MOD_VERSION, projectName);
    }

    @Override
    public void initGui()
    {
        this.refreshState();
        this.setListPosition(SUB_REGION_LIST_X, this.getSubRegionListY());
        super.initGui();

        if (this.state != null)
        {
            this.createTopButtons();
            this.createOriginFields();
        }

        this.createBottomButtons();
    }

    @Override
    protected WidgetLvcProjectSubRegionList createListWidget(int listX, int listY)
    {
        List<LvcManifest.Region> regions = this.state != null ? this.state.regions() : List.of();
        return new WidgetLvcProjectSubRegionList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), regions, this, this);
    }

    @Override
    protected int getBrowserWidth()
    {
        return Math.max(120, this.getScreenWidth() - SUB_REGION_LIST_X - MARGIN);
    }

    @Override
    protected int getBrowserHeight()
    {
        return Math.max(44, this.getBottomButtonY() - this.getSubRegionListY() - 8);
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        if (this.state == null)
        {
            ctx.drawString(ctx.fontRenderer(), this.ellipsizeToWidth(this.statusText, this.getScreenWidth() - MARGIN * 2), MARGIN, TOP_Y, this.statusColor, false);
            return;
        }

        this.drawProjectInfo(ctx);
        this.drawCoordinateGroups(ctx);
        this.drawSubRegionLabel(ctx);
        this.drawEmptySubRegionMessage(ctx);
        super.drawContents(ctx, mouseX, mouseY, partialTicks);
        this.drawStatus(ctx);
    }

    private void refreshState()
    {
        try
        {
            this.state = LvcProjectService.readSemanticProjectEditorState(this.repositoryDirectory);
            this.projectName = this.state.projectName();
            this.title = StringUtils.translate("litematica.gui.title.lvc_project_editor", Reference.MOD_VERSION, this.projectName);
            this.ensureSelectedRegionExists();
            LvcDiagnostics.debug("GuiLvcProjectEditor: ui state loaded repo='{}' project='{}' regions={} localOrigin='{}'",
                    this.repositoryDirectory, this.projectName, this.state.regions().size(), this.state.localOrigin());
        }
        catch (Exception e)
        {
            this.state = null;
            this.setErrorStatus(e.getMessage());
            LvcDiagnostics.warn("GuiLvcProjectEditor: failed to load ui state repo='{}' error='{}'",
                    this.repositoryDirectory, e.getMessage());
        }
    }

    private void ensureSelectedRegionExists()
    {
        if (this.state == null)
        {
            this.selectedRegionId = null;
            return;
        }

        if (this.selectedRegionId != null)
        {
            for (LvcManifest.Region region : this.state.regions())
            {
                if (region.id().equals(this.selectedRegionId))
                {
                    return;
                }
            }
        }

        this.selectedRegionId = this.state.regions().isEmpty() ? null : this.state.regions().get(0).id();
    }

    private void createTopButtons()
    {
        int x = TOP_BUTTON_X;
        int y = TOP_Y;
        int maxX = this.getTopControlsMaxX();

        x = this.createWrappedButton(x, y, TOP_BUTTON_X, maxX, ButtonType.CHANGE_SELECTION_MODE);
        this.createWrappedButton(x + BUTTON_GROUP_GAP, y, TOP_BUTTON_X, maxX, ButtonType.CHANGE_CORNER_MODE);

        y = this.getActionButtonY();
        x = TOP_BUTTON_X;
        x = this.createWrappedButton(x, y, TOP_BUTTON_X, this.getScreenWidth() - MARGIN, ButtonType.NEW_SUB_REGION);
        x = this.createWrappedButton(x + BUTTON_GROUP_GAP, y, TOP_BUTTON_X, this.getScreenWidth() - MARGIN, ButtonType.SAVE_VERSION);
        this.createManualOriginButton(x + BUTTON_GROUP_GAP, y);
    }

    private int createWrappedButton(int x, int y, int minX, int maxX, ButtonType type)
    {
        String label = type.getDisplayName();
        int width = this.getStringWidth(label) + 10;

        if (x > minX && x + width > maxX)
        {
            x = minX;
            y += BUTTON_HEIGHT + 2;
        }

        return x + this.createButton(x, y, width, type);
    }

    private void createManualOriginButton(int x, int y)
    {
        ButtonOnOff button = new ButtonOnOff(x, y, -1, false, "litematica.gui.button.lvc_project_editor.manual_origin", false);
        button.setEnabled(false);
        this.addButton(button, new ButtonListener(this, ButtonType.MANUAL_ORIGIN));
    }

    private void createOriginFields()
    {
        int x = this.getOriginGroupX();
        int y = this.getCoordinateFieldY();
        BlockPos origin = this.state.localOrigin();

        this.createCoordinateField(x, y, FieldKind.ORIGIN_X, origin.getX());
        y += COORDINATE_ROW_SPACING;
        this.createCoordinateField(x, y, FieldKind.ORIGIN_Y, origin.getY());
        y += COORDINATE_ROW_SPACING;
        this.createCoordinateField(x, y, FieldKind.ORIGIN_Z, origin.getZ());
        this.createButton(x, this.getOriginMoveButtonY(), this.getOriginMoveButtonWidth(), ButtonType.SET_ORIGIN_TO_PLAYER);
    }

    private void createCoordinateField(int x, int y, FieldKind kind, int value)
    {
        GuiTextFieldInteger textField = new GuiTextFieldInteger(x + COORDINATE_FIELD_X_OFFSET, y + 2, COORDINATE_FIELD_WIDTH, TEXT_FIELD_HEIGHT, this.font);
        textField.setValueWrapper(String.valueOf(value));
        this.addTextField(textField, new IntegerFieldListener(this, kind));
        this.addButton(new ButtonGeneric(x + COORDINATE_FIELD_X_OFFSET + COORDINATE_FIELD_WIDTH + COORDINATE_BUTTON_GAP, y + 2, Icons.BUTTON_PLUS_MINUS_16, StringUtils.translate(PLUS_MINUS_HOVER)), new CoordinateButtonListener(this, kind));
    }

    private void createBottomButtons()
    {
        this.createButton(MARGIN, this.getBottomButtonY(), ButtonType.ANALYZE_AREA);

        int width = this.getLitematicaMenuButtonWidth();
        String label = StringUtils.translate("litematica.gui.button.lvc_project.litematica_menu");
        this.addButton(new ButtonGeneric(this.getScreenWidth() - width - MARGIN, this.getBottomButtonY(), width, BUTTON_HEIGHT, label), (button, mouseButton) -> GuiBase.openGui(new GuiMainMenu()));
    }

    private int createButton(int x, int y, ButtonType type)
    {
        return this.createButton(x, y, -1, type);
    }

    private int createButton(int x, int y, int width, ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, width, BUTTON_HEIGHT, type.getDisplayName());
        button.setEnabled(type.isEnabled());
        this.addButton(button, new ButtonListener(this, type));
        return button.getWidth();
    }

    private void drawProjectInfo(GuiContext ctx)
    {
        int labelY = this.getProjectNameLabelY();
        int fieldY = this.getProjectNameFieldY();
        int fieldWidth = this.getProjectNameFieldWidth();

        ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.lvc_project_editor.project_name"), FORM_X, labelY, 0xFFFFFFFF, false);
        RenderUtils.drawOutlinedBox(ctx, FORM_X, fieldY, fieldWidth, TEXT_FIELD_HEIGHT, INPUT_FILL_COLOR, INPUT_OUTLINE_COLOR);
        ctx.drawString(ctx.fontRenderer(), this.ellipsizeToWidth(this.projectName, fieldWidth - 6), FORM_X + 3, fieldY + 4, READ_ONLY_TEXT_COLOR, false);
    }

    private void drawCoordinateGroups(GuiContext ctx)
    {
        int y = this.getCoordinateGroupY();

        this.drawCoordinateGroup(ctx, this.getOriginGroupX(), y,
                StringUtils.translate("litematica.gui.label.lvc_project_editor.local_site_origin"),
                this.state.localOrigin(), true);

        String currentDimension = this.currentWorldDimension();

        if (currentDimension != null && !currentDimension.equals(this.state.localDimension()))
        {
            String message = StringUtils.translate("litematica.error.lvc_project_editor.dimension_mismatch", this.state.localDimension(), currentDimension);
            ctx.drawString(ctx.fontRenderer(), this.ellipsizeToWidth(message, this.getScreenWidth() - MARGIN * 2), MARGIN, this.getCoordinateWarningY(), ERROR_COLOR, false);
        }
        else if (!this.state.localDimension().equals(this.state.siteDimension()))
        {
            String message = StringUtils.translate("litematica.gui.label.lvc_project_editor.dimension_mismatch", this.state.siteDimension());
            ctx.drawString(ctx.fontRenderer(), this.ellipsizeToWidth(message, this.getScreenWidth() - MARGIN * 2), MARGIN, this.getCoordinateWarningY(), ERROR_COLOR, false);
        }
    }

    private void drawCoordinateGroup(GuiContext ctx, int x, int y, String title, BlockPos pos, boolean editable)
    {
        ctx.drawString(ctx.fontRenderer(), title, x, y + COORDINATE_TITLE_TEXT_OFFSET, editable ? 0xFFFFFFFF : 0xFFAAAAAA, false);

        int fieldY = this.getCoordinateFieldY();
        this.drawCoordinateLabel(ctx, x, fieldY, "X:");
        this.drawCoordinateLabel(ctx, x, fieldY + COORDINATE_ROW_SPACING, "Y:");
        this.drawCoordinateLabel(ctx, x, fieldY + COORDINATE_ROW_SPACING * 2, "Z:");

        if (!editable)
        {
            this.drawReadOnlyCoordinateField(ctx, x + COORDINATE_FIELD_X_OFFSET, fieldY + 2, pos.getX());
            this.drawReadOnlyCoordinateField(ctx, x + COORDINATE_FIELD_X_OFFSET, fieldY + COORDINATE_ROW_SPACING + 2, pos.getY());
            this.drawReadOnlyCoordinateField(ctx, x + COORDINATE_FIELD_X_OFFSET, fieldY + COORDINATE_ROW_SPACING * 2 + 2, pos.getZ());
        }
    }

    private void drawReadOnlyCoordinateField(GuiContext ctx, int x, int y, int value)
    {
        RenderUtils.drawOutlinedBox(ctx, x, y, COORDINATE_FIELD_WIDTH, TEXT_FIELD_HEIGHT, INPUT_FILL_COLOR, INPUT_OUTLINE_COLOR);
        ctx.drawString(ctx.fontRenderer(), String.valueOf(value), x + 4, y + 4, 0xFFAAAAAA, false);
    }

    private void drawCoordinateLabel(GuiContext ctx, int x, int y, String label)
    {
        ctx.drawString(ctx.fontRenderer(), label, x, y + 5, 0xFFFFFFFF, false);
    }

    private void drawSubRegionLabel(GuiContext ctx)
    {
        int y = this.getSubRegionLabelY();
        String count = this.state == null ? "0" : String.valueOf(this.state.regions().size());
        ctx.drawString(ctx.fontRenderer(), GuiBase.TXT_BOLD + StringUtils.translate("litematica.gui.label.lvc_project_editor.sub_regions", count),
                MARGIN, y, 0xFFFFFFFF, false);
    }

    private void drawEmptySubRegionMessage(GuiContext ctx)
    {
        if (this.state != null && this.state.regions().isEmpty())
        {
            int y = this.getSubRegionListY() + 24;
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.lvc_project_editor.no_sub_regions"),
                    MARGIN + 6, y, STATUS_COLOR, false);
        }
    }

    private void drawStatus(GuiContext ctx)
    {
        if (!this.statusText.isBlank())
        {
            int maxWidth = Math.max(40, this.getScreenWidth() - this.getLitematicaMenuButtonWidth() - MARGIN * 3 - this.getStringWidth(StringUtils.translate("litematica.gui.button.area_editor.analyze_area")));
            ctx.drawString(ctx.fontRenderer(), this.ellipsizeToWidth(this.statusText, maxWidth), MARGIN, this.getBottomButtonY() + 6, this.statusColor, false);
        }
    }

    @Nullable
    private String currentWorldDimension()
    {
        Level world = Minecraft.getInstance().level;
        return world == null ? null : LvcMinecraftWorldReader.dimensionId(world);
    }

    private void updateIntegerField(FieldKind kind, String value)
    {
        try
        {
            this.applyIntegerValue(kind, Integer.parseInt(value.trim()), false);
        }
        catch (NumberFormatException e)
        {
            this.setErrorStatus(StringUtils.translate("litematica.error.lvc_project_editor.invalid_integer"));
        }
    }

    private void applyIntegerValue(FieldKind kind, int value, boolean reloadGui)
    {
        if (this.state == null)
        {
            return;
        }

        try
        {
            BlockPos origin = this.updatedOrigin(kind, value);
            boolean overlayUpdated = LvcProjectService.updateSemanticPlacementOrigin(this.repositoryDirectory, origin);
            this.refreshState();
            this.clearStatus();
            LvcDiagnostics.debug("GuiLvcProjectEditor: placement origin field saved repo='{}' kind='{}' origin='{}' overlayUpdated={}",
                    this.repositoryDirectory, kind, origin, overlayUpdated);

            if (reloadGui)
            {
                this.initGui();
            }
        }
        catch (Exception e)
        {
            this.setErrorStatus(e.getMessage());
        }
    }

    private BlockPos updatedOrigin(FieldKind kind, int value)
    {
        BlockPos origin = this.state.localOrigin();

        return switch (kind)
        {
            case ORIGIN_X -> new BlockPos(value, origin.getY(), origin.getZ());
            case ORIGIN_Y -> new BlockPos(origin.getX(), value, origin.getZ());
            case ORIGIN_Z -> new BlockPos(origin.getX(), origin.getY(), value);
        };
    }

    private void nudgeIntegerField(FieldKind kind, int mouseButton)
    {
        int amount = mouseButton == 1 ? -1 : 1;

        if (GuiBase.isCtrlDown())
        {
            amount *= 100;
        }

        if (GuiBase.isShiftDown())
        {
            amount *= 10;
        }

        if (GuiBase.isAltDown())
        {
            amount *= 5;
        }

        Integer currentValue = this.currentValue(kind);

        if (currentValue != null)
        {
            this.applyIntegerValue(kind, currentValue + amount, true);
        }
    }

    @Nullable
    private Integer currentValue(FieldKind kind)
    {
        if (this.state == null)
        {
            return null;
        }

        BlockPos origin = this.state.localOrigin();
        return switch (kind)
        {
            case ORIGIN_X -> origin.getX();
            case ORIGIN_Y -> origin.getY();
            case ORIGIN_Z -> origin.getZ();
        };
    }

    private void setOriginToPlayer()
    {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level world = minecraft.level;

        if (player == null)
        {
            this.addMessage(MessageType.ERROR, "litematica.error.lvc_project.no_player");
            return;
        }

        if (world == null)
        {
            this.addMessage(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        String dimension = LvcMinecraftWorldReader.dimensionId(world);

        if (this.state != null && !dimension.equals(this.state.localDimension()))
        {
            this.addMessage(MessageType.ERROR, "litematica.error.lvc_project_editor.dimension_mismatch", this.state.localDimension(), dimension);
            return;
        }

        try
        {
            BlockPos origin = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(player);
            boolean overlayUpdated = LvcProjectService.updateSemanticPlacementOrigin(this.repositoryDirectory, origin);
            this.clearStatus();
            this.initGui();
            LvcDiagnostics.debug("GuiLvcProjectEditor: placement origin moved to player repo='{}' origin='{}' overlayUpdated={}",
                    this.repositoryDirectory, origin, overlayUpdated);
        }
        catch (Exception e)
        {
            this.addMessage(MessageType.ERROR, "litematica.error.lvc_project_editor.save_failed", e.getMessage());
        }
    }

    private void setSavedStatus(String key)
    {
        this.statusText = StringUtils.translate(key);
        this.statusColor = SUCCESS_COLOR;
    }

    private void clearStatus()
    {
        this.statusText = "";
        this.statusColor = STATUS_COLOR;
    }

    private void setErrorStatus(@Nullable String message)
    {
        this.statusText = message == null || message.isBlank() ? StringUtils.translate("litematica.error.lvc_project_editor.unknown_error") : message;
        this.statusColor = ERROR_COLOR;
    }

    private int getBottomButtonY()
    {
        return this.getScreenHeight() - 24;
    }

    private int getTopControlsMaxX()
    {
        return this.getScreenWidth() - MARGIN;
    }

    private boolean worldOriginFitsProjectRow()
    {
        return this.getLeftControlsRightForOrigin(this.getProjectNameFieldBaseWidth()) + COORDINATE_GROUP_LEFT_GAP + COORDINATE_GROUP_WIDTH + MARGIN <= this.getScreenWidth();
    }

    private int getCoordinateGroupY()
    {
        if (this.worldOriginFitsProjectRow())
        {
            return COORDINATE_TOP_Y;
        }

        return this.getActionButtonY() + BUTTON_HEIGHT + 8;
    }

    private int getCoordinateFieldY()
    {
        return this.getCoordinateGroupY() + COORDINATE_HEADER_HEIGHT;
    }

    private int getOriginGroupX()
    {
        if (!this.worldOriginFitsProjectRow())
        {
            return FORM_X;
        }

        return this.getLeftControlsRightForOrigin(this.getProjectNameFieldWidth()) + COORDINATE_GROUP_LEFT_GAP;
    }

    private int getOriginMoveButtonY()
    {
        return this.getCoordinateFieldY() + COORDINATE_MOVE_OFFSET_FROM_FIELDS;
    }

    private int getOriginMoveButtonWidth()
    {
        return COORDINATE_FIELD_X_OFFSET + COORDINATE_FIELD_WIDTH + COORDINATE_BUTTON_GAP + COORDINATE_NUDGE_BUTTON_WIDTH;
    }

    private int getCoordinateGroupsBottomY()
    {
        return Math.max(this.getCoordinateFieldY() + COORDINATE_ROW_SPACING * 2 + TEXT_FIELD_HEIGHT + 2, this.getOriginMoveButtonY() + BUTTON_HEIGHT);
    }

    private int getCoordinateWarningY()
    {
        return Math.min(this.getCoordinateGroupsBottomY() + 3, this.getBottomButtonY() - 14);
    }

    private int getSubRegionLabelY()
    {
        return Math.max(this.getActionButtonY() + BUTTON_HEIGHT + 8, this.getCoordinateGroupsBottomY() + 12);
    }

    private int getSubRegionListY()
    {
        return this.getSubRegionLabelY() + 9;
    }

    private int getLitematicaMenuButtonWidth()
    {
        return this.getStringWidth(StringUtils.translate("litematica.gui.button.lvc_project.litematica_menu")) + 20;
    }

    private int getProjectNameFieldWidth()
    {
        int maxWidth = this.getProjectNameFieldBaseWidth();

        if (!this.worldOriginFitsProjectRow())
        {
            return maxWidth;
        }

        int availableRight = this.getScreenWidth() - MARGIN - COORDINATE_GROUP_LEFT_GAP - COORDINATE_GROUP_WIDTH;
        return Math.max(140, Math.min(maxWidth, availableRight - FORM_X));
    }

    private int getProjectNameFieldBaseWidth()
    {
        int targetWidth = this.getProjectNameTargetRightX() - FORM_X;
        int availableWidth = this.getScreenWidth() - FORM_X - MARGIN;
        return Math.min(Math.min(PROJECT_NAME_FIELD_WIDTH, Math.max(80, targetWidth)), Math.max(80, availableWidth));
    }

    private int getProjectNameTargetRightX()
    {
        return TOP_BUTTON_X +
                this.getButtonWidth(ButtonType.NEW_SUB_REGION) +
                BUTTON_GROUP_GAP +
                this.getButtonWidth(ButtonType.SAVE_VERSION) +
                BUTTON_GROUP_GAP +
                this.getButtonWidth(ButtonType.MANUAL_ORIGIN) / 2;
    }

    private int getLeftControlsRightForOrigin(int projectNameFieldWidth)
    {
        int right = FORM_X + projectNameFieldWidth;
        right = Math.max(right, TOP_BUTTON_X + this.getActionButtonGroupWidth());
        right = Math.max(right, TOP_BUTTON_X + this.getSelectionButtonGroupWidth());

        return right;
    }

    private int getActionButtonGroupWidth()
    {
        return this.getButtonWidth(ButtonType.NEW_SUB_REGION) + BUTTON_GROUP_GAP + this.getButtonWidth(ButtonType.SAVE_VERSION) + BUTTON_GROUP_GAP + this.getButtonWidth(ButtonType.MANUAL_ORIGIN);
    }

    private int getSelectionButtonGroupWidth()
    {
        return this.getButtonWidth(ButtonType.CHANGE_SELECTION_MODE) + BUTTON_GROUP_GAP + this.getButtonWidth(ButtonType.CHANGE_CORNER_MODE);
    }

    private int getButtonWidth(ButtonType type)
    {
        return this.getStringWidth(type.getDisplayName()) + 10;
    }

    private int getProjectNameLabelY()
    {
        return TOP_Y + BUTTON_HEIGHT + PROJECT_NAME_TOP_GAP;
    }

    private int getProjectNameFieldY()
    {
        return this.getProjectNameLabelY() + 13;
    }

    private int getActionButtonY()
    {
        return this.getProjectNameFieldY() + TEXT_FIELD_HEIGHT + ACTION_BUTTON_TOP_GAP;
    }

    @Nullable
    public String getSelectedRegionId()
    {
        return this.selectedRegionId;
    }

    @Override
    public void onSelectionChange(LvcManifest.Region entry)
    {
        this.selectedRegionId = entry.id();
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
        CHANGE_SELECTION_MODE("litematica.gui.button.area_editor.change_selection_mode"),
        CHANGE_CORNER_MODE("litematica.gui.button.area_editor.change_corner_mode"),
        NEW_SUB_REGION("litematica.gui.button.lvc_project_editor.new_sub_region"),
        SAVE_VERSION("litematica.gui.button.lvc_project.save_version"),
        MANUAL_ORIGIN("litematica.gui.button.lvc_project_editor.manual_origin"),
        SET_ORIGIN_TO_PLAYER("litematica.gui.button.move_to_player"),
        ANALYZE_AREA("litematica.gui.button.area_editor.analyze_area");

        private final String translationKey;

        ButtonType(String translationKey)
        {
            this.translationKey = translationKey;
        }

        private String getDisplayName()
        {
            if (this == CHANGE_SELECTION_MODE)
            {
                return StringUtils.translate(this.translationKey, SelectionMode.NORMAL.getDisplayName());
            }

            if (this == CHANGE_CORNER_MODE)
            {
                return StringUtils.translate(this.translationKey, Configs.Generic.SELECTION_CORNERS_MODE.getOptionListValue().getDisplayName());
            }

            if (this == MANUAL_ORIGIN)
            {
                return StringUtils.translate(this.translationKey, StringUtils.translate("malilib.gui.label_colored.off"));
            }

            return StringUtils.translate(this.translationKey);
        }

        private boolean isEnabled()
        {
            return this != CHANGE_SELECTION_MODE && this != CHANGE_CORNER_MODE && this != NEW_SUB_REGION;
        }
    }

    private enum FieldKind
    {
        ORIGIN_X,
        ORIGIN_Y,
        ORIGIN_Z
    }

    private record ButtonListener(GuiLvcProjectEditor gui, ButtonType type) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.type == ButtonType.SET_ORIGIN_TO_PLAYER)
            {
                this.gui.setOriginToPlayer();
            }
            else if (this.type == ButtonType.MANUAL_ORIGIN)
            {
                this.gui.setSavedStatus("litematica.message.lvc_project_editor.metadata_locked");
            }
        }
    }

    private record CoordinateButtonListener(GuiLvcProjectEditor gui, FieldKind kind) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.gui.nudgeIntegerField(this.kind, mouseButton);
        }
    }

    private record IntegerFieldListener(GuiLvcProjectEditor gui, FieldKind kind) implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            this.gui.updateIntegerField(this.kind, textField.getValueWrapper());
            return false;
        }
    }
}
