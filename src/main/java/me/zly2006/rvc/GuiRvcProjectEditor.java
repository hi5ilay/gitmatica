package me.zly2006.rvc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import fi.dy.masa.litematica.Reference;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.selection.CornerSelectionMode;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiRvcProjectEditor extends GuiBase
{
    private static final int MARGIN = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int TEXT_FIELD_HEIGHT = 16;
    private static final int COORDINATE_FIELD_WIDTH = 72;
    private static final int SEARCH_HEIGHT = 18;
    private static final int REGION_ROW_HEIGHT = 22;
    private static final int REGION_SCROLLBAR_TRACK_RIGHT_OFFSET = 7;
    private static final int REGION_SCROLLBAR_TRACK_WIDTH = 4;
    private static final int REGION_ROW_HORIZONTAL_INSET = 4;
    private static final int REGION_ROW_ACTION_RIGHT_PADDING = 11;
    private static final int REGION_SCROLL_ROWS = 3;
    private static final int TOP_Y = 28;
    private static final int CORNER_MODE_BUTTON_Y = TOP + 14;
    private static final int PROJECT_LABEL_LEFT_INSET = 2;
    private static final int PROJECT_LABEL_Y = CORNER_MODE_BUTTON_Y + BUTTON_HEIGHT + 4;
    private static final int PROJECT_FIELD_Y = PROJECT_LABEL_Y + 12;
    private static final int TOP_BUTTON_Y = PROJECT_FIELD_Y + TEXT_FIELD_HEIGHT + 10;
    private static final int PROJECT_ORIGIN_GAP = 16;
    private static final int STACKED_ORIGIN_Y = 112;
    private static final int STATUS_COLOR = 0xFFAAAAAA;
    private static final int ERROR_COLOR = 0xFFFF5555;
    private static final int SUCCESS_COLOR = 0xFF55FF55;
    private static final int PROJECT_FIELD_WIDTH = 202;
    private static final int STACKED_LAYOUT_MAX_WIDTH = 430;
    private static final String PLUS_MINUS_HOVER = "litematica.gui.button.hover.plus_minus_tip_ctrl_alt_shift";

    private final Path repositoryDirectory;
    private String projectName;
    @Nullable private RvcProjectService.ProjectEditorState state;
    @Nullable private String selectedRegionId;
    private String regionSearchQuery = "";
    private int regionScrollOffset;
    private String statusText = "";
    private int statusColor = STATUS_COLOR;
    private CornerSelectionMode cornerMode = CornerSelectionMode.CORNERS;

    public GuiRvcProjectEditor(Path repositoryDirectory, String projectName)
    {
        this.repositoryDirectory = repositoryDirectory;
        this.projectName = projectName;
        this.title = StringUtils.translate("litematica.gui.title.rvc_project_editor", Reference.MOD_VERSION, projectName);
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.refreshState();

        if (this.state == null)
        {
            return;
        }

        this.createProjectNameField();
        this.createOriginFields();
        this.createRegionSearchField();
        this.createEditorButtons();
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);

        if (this.state == null)
        {
            ctx.drawString(ctx.fontRenderer(), this.statusText, MARGIN, TOP_Y, this.statusColor, false);
            return;
        }

        this.drawProjectFields(ctx);
        this.drawOriginFields(ctx);
        this.drawStatus(ctx);
    }

    @Override
    protected void drawButtons(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        if (this.state != null)
        {
            this.drawRegionList(ctx, mouseX, mouseY);
        }

        super.drawButtons(ctx, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (super.onMouseClicked(click, doubleClick))
        {
            return true;
        }

        RvcManifest.Region region = this.getRegionAt((int) click.x(), (int) click.y());

        if (region != null)
        {
            this.selectedRegionId = region.id();
            this.initGui();
            return true;
        }

        return false;
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (this.isMouseOverRegionList((int) mouseX, (int) mouseY))
        {
            int oldOffset = this.regionScrollOffset;
            this.scrollRegions(verticalAmount);

            if (oldOffset != this.regionScrollOffset)
            {
                this.initGui();
                return true;
            }
        }

        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void refreshState()
    {
        try
        {
            this.state = RvcProjectService.readSemanticProjectEditorState(this.repositoryDirectory);
            this.projectName = this.state.projectName();
            this.title = StringUtils.translate("litematica.gui.title.rvc_project_editor", Reference.MOD_VERSION, this.projectName);
            this.ensureSelectedRegion();
            this.clampRegionScroll();
        }
        catch (Exception e)
        {
            this.state = null;
            this.setErrorStatus(e.getMessage());
        }
    }

    private void ensureSelectedRegion()
    {
        if (this.state == null || this.state.regions().isEmpty())
        {
            this.selectedRegionId = null;
            this.regionScrollOffset = 0;
            return;
        }

        if (this.getSelectedRegion() != null)
        {
            return;
        }

        this.selectedRegionId = this.state.regions().get(0).id();
    }

    private void createProjectNameField()
    {
        GuiTextFieldGeneric textField = new GuiTextFieldGeneric(this.getProjectFieldX(), PROJECT_FIELD_Y, this.getProjectFieldWidth(), TEXT_FIELD_HEIGHT, this.font);
        textField.setMaxLength(128);
        textField.setValueWrapper(this.state.projectName());
        this.addTextField(textField, new ProjectNameListener(this));
    }

    private void createTopButtons()
    {
        int x = this.getProjectControlX();
        this.createButton(x, CORNER_MODE_BUTTON_Y, ButtonType.CORNER_MODE);

        x += this.createButton(x, TOP_BUTTON_Y, ButtonType.NEW_SUB_REGION) + 2;
        this.createButton(x, TOP_BUTTON_Y, ButtonType.PROJECT_PAGE);
    }

    private void createEditorButtons()
    {
        this.createTopButtons();
        this.createOriginButtons();
        this.createRegionRowButtons();
    }

    private void rebuildEditorButtons()
    {
        this.clearButtons();

        if (this.state != null)
        {
            this.createEditorButtons();
        }
    }

    private void createRegionSearchField()
    {
        int searchY = this.getRegionSearchY();
        int x = this.getRegionListX() + 22;
        int y = searchY + (SEARCH_HEIGHT - this.font.lineHeight) / 2;
        int width = this.getRegionListWidth() - 30;

        if (width <= 0)
        {
            return;
        }

        GuiTextFieldGeneric textField = new GuiTextFieldGeneric(x, y, width, 14, this.font);
        textField.setBordered(false);
        textField.setTextColor(0xFFFFFFFF);
        textField.setTextColorUneditable(0xFFAAAAAA);
        textField.setMaxLength(128);
        textField.setValueWrapper(this.regionSearchQuery);
        this.addTextField(textField, new RegionSearchListener(this));
    }

    private void createOriginFields()
    {
        int x = this.getOriginX();
        int y = this.getOriginFieldsY();
        BlockPos origin = this.state.localOrigin();

        this.createCoordinateField(x, y, FieldKind.ORIGIN_X, origin.getX());
        y += 20;
        this.createCoordinateField(x, y, FieldKind.ORIGIN_Y, origin.getY());
        y += 20;
        this.createCoordinateField(x, y, FieldKind.ORIGIN_Z, origin.getZ());
    }

    private void createOriginButtons()
    {
        int x = this.getOriginX();
        int y = this.getOriginFieldsY();

        this.createCoordinateButton(x, y, FieldKind.ORIGIN_X);
        y += 20;
        this.createCoordinateButton(x, y, FieldKind.ORIGIN_Y);
        y += 20;
        this.createCoordinateButton(x, y, FieldKind.ORIGIN_Z);
        this.createButton(x, TOP_BUTTON_Y, ButtonType.SET_ORIGIN_TO_PLAYER);
    }

    private void createCoordinateField(int x, int y, FieldKind kind, int value)
    {
        GuiTextFieldInteger textField = new GuiTextFieldInteger(x + 16, y + 2, COORDINATE_FIELD_WIDTH, TEXT_FIELD_HEIGHT, this.font);
        textField.setValueWrapper(String.valueOf(value));
        this.addTextField(textField, new IntegerFieldListener(this, kind));
    }

    private void createCoordinateButton(int x, int y, FieldKind kind)
    {
        this.addButton(new ButtonGeneric(x + 16 + COORDINATE_FIELD_WIDTH + 4, y + 2, Icons.BUTTON_PLUS_MINUS_16, StringUtils.translate(PLUS_MINUS_HOVER)), new CoordinateButtonListener(this, kind));
    }

    private void createRegionRowButtons()
    {
        int y = this.getRegionRowsY() + 1;
        List<RvcManifest.Region> visibleRegions = this.filteredRegions();
        int endIndex = Math.min(visibleRegions.size(), this.regionScrollOffset + this.getVisibleRegionRows());

        for (int index = this.regionScrollOffset; index < endIndex; index++)
        {
            RvcManifest.Region region = visibleRegions.get(index);
            int rightX = this.getRegionListX() + this.getRegionListWidth() - REGION_ROW_ACTION_RIGHT_PADDING;

            rightX = this.createRightRegionButton(rightX, y, RegionButtonType.REMOVE, region);
            rightX = this.createRightRegionButton(rightX, y, RegionButtonType.RENAME, region);
            this.createRightRegionButton(rightX, y, RegionButtonType.CONFIGURE, region);
            y += REGION_ROW_HEIGHT;
        }
    }

    private int createRightRegionButton(int rightX, int y, RegionButtonType type, RvcManifest.Region region)
    {
        ButtonGeneric button = new ButtonGeneric(rightX, y, -1, true, type.getDisplayName());
        this.addButton(button, new RegionButtonListener(this, type, region.id()));
        return button.getX() - 1;
    }

    private int createButton(int x, int y, ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, BUTTON_HEIGHT, type.getDisplayName(this));
        this.addButton(button, new ButtonListener(this, type));
        return button.getWidth();
    }

    private void drawProjectFields(GuiContext ctx)
    {
        String label = StringUtils.translate("litematica.gui.label.rvc_project_editor.project_name");
        int x = this.getProjectFieldX();
        ctx.drawString(ctx.fontRenderer(), label, x, PROJECT_LABEL_Y, 0xFFFFFFFF, false);
        ctx.drawString(ctx.fontRenderer(), " (" + StringUtils.translate("litematica.gui.label.rvc_project_editor.dimension", this.shortDimensionId(this.state.localDimension())) + ")", x + this.getStringWidth(label), PROJECT_LABEL_Y, 0xFFAAAAAA, false);
    }

    private void drawOriginFields(GuiContext ctx)
    {
        int x = this.getOriginX();
        int y = this.getOriginY();

        ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.rvc_project_editor.local_site_origin"), x, y, 0xFFFFFFFF, false);

        int fieldY = this.getOriginFieldsY();
        this.drawCoordinateLabel(ctx, x, fieldY, "X:");
        this.drawCoordinateLabel(ctx, x, fieldY + 20, "Y:");
        this.drawCoordinateLabel(ctx, x, fieldY + 40, "Z:");

        if (!this.state.localDimension().equals(this.state.siteDimension()))
        {
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.rvc_project_editor.dimension_mismatch", this.state.siteDimension()), x, y + 108, ERROR_COLOR, false);
        }
    }

    private void drawCoordinateLabel(GuiContext ctx, int x, int y, String label)
    {
        ctx.drawString(ctx.fontRenderer(), label, x, y + 5, 0xFFFFFFFF, false);
    }

    private void drawRegionList(GuiContext ctx, int mouseX, int mouseY)
    {
        int x = this.getRegionListX();
        int y = this.getRegionListY();
        int width = this.getRegionListWidth();

        ctx.drawString(ctx.fontRenderer(), GuiBase.TXT_BOLD + StringUtils.translate("litematica.gui.label.rvc_project_editor.sub_regions", this.state.regions().size()), x, y - 12, 0xFFFFFFFF, false);
        this.drawRegionSearchBox(ctx);

        if (this.state.regions().isEmpty())
        {
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.rvc_project_editor.no_sub_regions"), this.getRegionRowX() + 2, this.getRegionRowsY() + 6, 0xFFAAAAAA, false);
            return;
        }

        this.clampRegionScroll();
        ctx.pushScissor(new ScreenRectangle(this.getRegionRowX(), this.getRegionRowsY(), this.getRegionRowWidth(), this.getRegionRowsHeight()));
        this.drawVisibleRegionRows(ctx, mouseX, mouseY);
        ctx.popScissor();
        this.drawRegionScrollbar(ctx);
    }

    private void drawRegionSearchBox(GuiContext ctx)
    {
        int searchX = this.getRegionRowX() + 1;
        int searchY = this.getRegionSearchY();
        int searchWidth = this.getRegionRowWidth() - 2;

        RenderUtils.drawOutlinedBox(ctx, searchX, searchY, searchWidth, SEARCH_HEIGHT, 0xA0000000, COLOR_HORIZONTAL_BAR);
        Icons.FILE_ICON_SEARCH.renderAt(ctx, this.getRegionRowX() + 4, searchY + 2, 0, true, false);
    }

    private void drawVisibleRegionRows(GuiContext ctx, int mouseX, int mouseY)
    {
        int x = this.getRegionRowX();
        int width = this.getRegionRowWidth();
        int y = this.getRegionRowsY();
        List<RvcManifest.Region> visibleRegions = this.filteredRegions();
        int endIndex = Math.min(visibleRegions.size(), this.regionScrollOffset + this.getVisibleRegionRows());

        for (int index = this.regionScrollOffset; index < endIndex; index++)
        {
            RvcManifest.Region region = visibleRegions.get(index);
            boolean selected = region.id().equals(this.selectedRegionId);
            int rowColor = index % 2 == 0 ? 0xA0303030 : 0xA0101010;

            if (selected || GuiBase.isMouseOver(mouseX, mouseY, x, y, width, REGION_ROW_HEIGHT))
            {
                rowColor = 0xA0707070;
            }

            RenderUtils.drawRect(ctx, x, y, width, REGION_ROW_HEIGHT, rowColor);

            if (selected)
            {
                RenderUtils.drawOutline(ctx, x, y, width, REGION_ROW_HEIGHT, 0xFFE0E0E0);
            }

            this.drawRegionRowText(ctx, region, x + 2, y + 7);
            y += REGION_ROW_HEIGHT;
        }
    }

    private void drawRegionRowText(GuiContext ctx, RvcManifest.Region region, int x, int y)
    {
        String name = this.ellipsizeToWidth(region.name(), Math.max(40, this.getRegionListWidth() - 190));

        ctx.drawString(ctx.fontRenderer(), name, x, y, 0xFFFFFFFF, false);
    }

    private void drawRegionScrollbar(GuiContext ctx)
    {
        int maxScroll = this.getRegionMaxScroll();

        if (maxScroll <= 0)
        {
            return;
        }

        int trackX = this.getRegionListX() + this.getRegionListWidth() - REGION_SCROLLBAR_TRACK_RIGHT_OFFSET;
        int trackY = this.getRegionRowsY();
        int trackHeight = this.getRegionRowsHeight();
        int visibleRows = this.getVisibleRegionRows();
        int thumbHeight = Math.max(14, trackHeight * visibleRows / Math.max(visibleRows, this.filteredRegions().size()));
        int thumbY = trackY + (trackHeight - thumbHeight) * this.regionScrollOffset / maxScroll;

        RenderUtils.drawRect(ctx, trackX, trackY, REGION_SCROLLBAR_TRACK_WIDTH, trackHeight, 0xA0202020);
        RenderUtils.drawRect(ctx, trackX, thumbY, REGION_SCROLLBAR_TRACK_WIDTH, thumbHeight, 0xFFE0E0E0);
    }

    private void drawStatus(GuiContext ctx)
    {
        if (!this.statusText.isBlank())
        {
            ctx.drawString(ctx.fontRenderer(), this.statusText, MARGIN, this.getBottomContentY() + 6, this.statusColor, false);
        }
    }

    private void updateProjectName(String value)
    {
        if (value == null || value.isBlank())
        {
            this.setErrorStatus(StringUtils.translate("litematica.error.rvc_project_editor.project_name_required"));
            return;
        }

        try
        {
            RvcProjectService.updateSemanticProjectName(this.repositoryDirectory, value);
            this.refreshState();
            this.setSavedStatus("litematica.message.rvc_project_editor.project_name_updated");
        }
        catch (Exception e)
        {
            this.setErrorStatus(e.getMessage());
        }
    }

    private void updateIntegerField(FieldKind kind, String value)
    {
        try
        {
            this.applyIntegerValue(kind, Integer.parseInt(value.trim()));
        }
        catch (NumberFormatException e)
        {
            this.setErrorStatus(StringUtils.translate("litematica.error.rvc_project_editor.invalid_integer"));
        }
    }

    private void applyIntegerValue(FieldKind kind, int value)
    {
        RvcProjectService.ProjectEditorState state = this.state;

        if (state == null)
        {
            return;
        }

        try
        {
            RvcProjectService.updateSemanticLocalOrigin(this.repositoryDirectory, this.updatedOrigin(state.localOrigin(), kind, value));
            this.refreshState();
            this.setSavedStatus("litematica.message.rvc_project_editor.local_origin_updated");
        }
        catch (Exception e)
        {
            this.setErrorStatus(e.getMessage());
        }
    }

    private BlockPos updatedOrigin(BlockPos origin, FieldKind kind, int value)
    {
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
            int newValue = currentValue + amount;
            this.applyIntegerValue(kind, newValue);
            this.initGui();
        }
    }

    @Nullable
    private Integer currentValue(FieldKind kind)
    {
        RvcProjectService.ProjectEditorState state = this.state;

        if (state == null)
        {
            return null;
        }

        BlockPos origin = state.localOrigin();
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
            this.addMessage(MessageType.ERROR, "litematica.error.rvc_project.no_player");
            return;
        }

        if (world == null)
        {
            this.addMessage(MessageType.ERROR, "litematica.error.rvc_project.no_world");
            return;
        }

        String dimension = RvcMinecraftWorldReader.dimensionId(world);

        if (this.state != null && !dimension.equals(this.state.localDimension()))
        {
            this.addMessage(MessageType.ERROR, "litematica.error.rvc_project_editor.dimension_mismatch", this.state.localDimension(), dimension);
            return;
        }

        try
        {
            RvcProjectService.updateSemanticLocalOrigin(this.repositoryDirectory, fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(player));
            this.initGui();
            this.addMessage(MessageType.SUCCESS, "litematica.message.rvc_project_editor.local_origin_updated");
        }
        catch (Exception e)
        {
            this.addMessage(MessageType.ERROR, "litematica.error.rvc_project_editor.save_failed", e.getMessage());
        }
    }

    private void cycleCornerMode()
    {
        this.cornerMode = (CornerSelectionMode) this.cornerMode.cycle(true);
        this.initGui();
    }

    private void promptNewRegion()
    {
        GuiBase.openGui(new GuiTextInput(128, "litematica.gui.title.rvc_project_editor.new_sub_region", "", this, new NewRegionCreator(this)));
    }

    private void createRegion(String name)
    {
        try
        {
            RvcManifest.Region region = RvcProjectService.createSemanticRegion(this.repositoryDirectory, name, this.suggestedNewRegionMin(), new BlockPos(1, 1, 1));
            this.selectedRegionId = region.id();
            this.initGui();
        }
        catch (Exception e)
        {
            this.addMessage(MessageType.ERROR, "litematica.error.rvc_project_editor.save_failed", e.getMessage());
        }
    }

    private BlockPos suggestedNewRegionMin()
    {
        if (this.state == null || this.state.regions().isEmpty())
        {
            return BlockPos.ZERO;
        }

        int nextX = 0;

        for (RvcManifest.Region region : this.state.regions())
        {
            BlockPos min = this.blockPosFromList(region.min());
            BlockPos size = this.blockPosFromList(region.size());
            nextX = Math.max(nextX, min.getX() + size.getX() + 1);
        }

        return new BlockPos(nextX, 0, 0);
    }

    private void promptRenameRegion(String regionId)
    {
        RvcManifest.Region region = this.regionById(regionId);

        if (region != null)
        {
            GuiBase.openGui(new GuiTextInput(128, "litematica.gui.title.rvc_project_editor.rename_sub_region", region.name(), this, new RegionRenamer(this, region.id())));
        }
    }

    private void renameRegion(String regionId, String name)
    {
        RvcManifest.Region region = this.regionById(regionId);

        if (region == null)
        {
            return;
        }

        try
        {
            RvcProjectService.updateSemanticRegion(this.repositoryDirectory, region.id(), name, this.blockPosFromList(region.min()), this.blockPosFromList(region.size()));
            this.initGui();
            this.addMessage(MessageType.SUCCESS, "litematica.message.rvc_project_editor.region_renamed", name.trim());
        }
        catch (Exception e)
        {
            this.addMessage(MessageType.ERROR, "litematica.error.rvc_project_editor.save_failed", e.getMessage());
        }
    }

    private void deleteRegion(String regionId)
    {
        try
        {
            RvcProjectService.deleteSemanticRegion(this.repositoryDirectory, regionId);
            this.selectedRegionId = null;
            this.initGui();
        }
        catch (Exception e)
        {
            this.addMessage(MessageType.ERROR, "litematica.error.rvc_project_editor.save_failed", e.getMessage());
        }
    }

    private void openProjectPage()
    {
        GuiBase.openGui(new GuiRvcProject(this.repositoryDirectory, this.projectName));
    }

    private void scrollRegions(double verticalAmount)
    {
        if (verticalAmount == 0 || this.state == null)
        {
            return;
        }

        int rows = Math.max(1, (int) Math.ceil(Math.abs(verticalAmount))) * REGION_SCROLL_ROWS;
        this.regionScrollOffset += verticalAmount > 0 ? -rows : rows;
        this.clampRegionScroll();
    }

    private void clampRegionScroll()
    {
        this.regionScrollOffset = Math.clamp(this.regionScrollOffset, 0, this.getRegionMaxScroll());
    }

    private int getRegionMaxScroll()
    {
        if (this.state == null)
        {
            return 0;
        }

        return Math.max(0, this.filteredRegions().size() - this.getVisibleRegionRows());
    }

    private int getVisibleRegionRows()
    {
        return Math.max(1, this.getRegionRowsHeight() / REGION_ROW_HEIGHT);
    }

    private int getRegionRowsHeight()
    {
        return Math.max(REGION_ROW_HEIGHT, this.getRegionListHeight() - (this.getRegionRowsY() - this.getRegionListY()));
    }

    private int getRegionRowsY()
    {
        return this.getRegionListY() + SEARCH_HEIGHT + 9;
    }

    private int getRegionSearchY()
    {
        return this.getRegionListY() + 4;
    }

    private boolean isMouseOverRegionList(int mouseX, int mouseY)
    {
        return GuiBase.isMouseOver(mouseX, mouseY, this.getRegionRowX(), this.getRegionRowsY(), this.getRegionRowWidth(), this.getRegionRowsHeight());
    }

    @Nullable
    private RvcManifest.Region getRegionAt(int mouseX, int mouseY)
    {
        if (this.state == null || !this.isMouseOverRegionList(mouseX, mouseY))
        {
            return null;
        }

        List<RvcManifest.Region> visibleRegions = this.filteredRegions();
        int index = this.regionScrollOffset + (mouseY - this.getRegionRowsY()) / REGION_ROW_HEIGHT;
        return index >= 0 && index < visibleRegions.size() ? visibleRegions.get(index) : null;
    }

    @Nullable
    private RvcManifest.Region getSelectedRegion()
    {
        return this.selectedRegionId == null ? null : this.regionById(this.selectedRegionId);
    }

    @Nullable
    private RvcManifest.Region regionById(String regionId)
    {
        if (this.state == null)
        {
            return null;
        }

        for (RvcManifest.Region region : this.state.regions())
        {
            if (region.id().equals(regionId))
            {
                return region;
            }
        }

        return null;
    }

    private BlockPos blockPosFromList(List<Integer> values)
    {
        return new BlockPos(values.get(0), values.get(1), values.get(2));
    }

    private String shortDimensionId(String dimensionId)
    {
        int namespaceSeparator = dimensionId.indexOf(':');
        return namespaceSeparator >= 0 ? dimensionId.substring(namespaceSeparator + 1) : dimensionId;
    }

    private List<RvcManifest.Region> filteredRegions()
    {
        if (this.state == null)
        {
            return List.of();
        }

        String query = this.regionSearchQuery.trim().toLowerCase(Locale.ROOT);

        if (query.isEmpty())
        {
            return this.state.regions();
        }

        String[] tokens = query.split("\\s+");
        List<RvcManifest.Region> regions = new ArrayList<>();

        for (RvcManifest.Region region : this.state.regions())
        {
            if (this.regionMatchesSearch(region, tokens))
            {
                regions.add(region);
            }
        }

        return regions;
    }

    private boolean regionMatchesSearch(RvcManifest.Region region, String[] tokens)
    {
        String haystack = region.name().toLowerCase(Locale.ROOT);

        for (String token : tokens)
        {
            if (!haystack.contains(token))
            {
                return false;
            }
        }

        return true;
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

    private void setSavedStatus(String key)
    {
        this.statusText = StringUtils.translate(key);
        this.statusColor = SUCCESS_COLOR;
    }

    private void setErrorStatus(@Nullable String message)
    {
        this.statusText = message == null || message.isBlank() ? StringUtils.translate("litematica.error.rvc_project_editor.unknown_error") : message;
        this.statusColor = ERROR_COLOR;
    }

    private boolean isStackedLayout()
    {
        return this.getScreenWidth() < STACKED_LAYOUT_MAX_WIDTH;
    }

    private int getProjectFieldWidth()
    {
        if (this.isStackedLayout())
        {
            return Math.min(PROJECT_FIELD_WIDTH, this.getScreenWidth() - this.getProjectFieldX() - MARGIN);
        }

        return PROJECT_FIELD_WIDTH;
    }

    private int getProjectControlX()
    {
        return MARGIN - 1;
    }

    private int getProjectFieldX()
    {
        return this.getProjectControlX() + PROJECT_LABEL_LEFT_INSET;
    }

    private int getOriginX()
    {
        return this.isStackedLayout() ? MARGIN : this.getProjectFieldX() + this.getProjectFieldWidth() + PROJECT_ORIGIN_GAP;
    }

    private int getOriginY()
    {
        return this.isStackedLayout() ? STACKED_ORIGIN_Y : TOP;
    }

    private int getOriginFieldsY()
    {
        return this.isStackedLayout() ? this.getOriginY() + 24 : TOP_BUTTON_Y - 62;
    }

    private int getRegionListX()
    {
        return 12;
    }

    private int getRegionListY()
    {
        return TOP_BUTTON_Y + BUTTON_HEIGHT + 22;
    }

    private int getRegionListWidth()
    {
        return this.getScreenWidth() - 20;
    }

    private int getRegionRowX()
    {
        return this.getRegionListX() + REGION_ROW_HORIZONTAL_INSET;
    }

    private int getRegionRowWidth()
    {
        return this.getRegionListWidth() - REGION_ROW_HORIZONTAL_INSET * 2;
    }

    private int getRegionListHeight()
    {
        return Math.max(0, this.getBottomContentY() - this.getRegionListY());
    }

    private int getBottomContentY()
    {
        return this.getScreenHeight() - 16;
    }

    private enum ButtonType
    {
        CORNER_MODE("litematica.gui.button.area_editor.change_corner_mode"),
        NEW_SUB_REGION("litematica.gui.button.rvc_project_editor.new_sub_region"),
        PROJECT_PAGE("litematica.gui.button.rvc_project.project_page"),
        SET_ORIGIN_TO_PLAYER("litematica.gui.button.move_to_player");

        private final String translationKey;

        ButtonType(String translationKey)
        {
            this.translationKey = translationKey;
        }

        private String getDisplayName(GuiRvcProjectEditor gui)
        {
            if (this == CORNER_MODE)
            {
                return StringUtils.translate(this.translationKey, gui.cornerMode.getDisplayName());
            }

            return StringUtils.translate(this.translationKey);
        }
    }

    private enum RegionButtonType
    {
        CONFIGURE("litematica.gui.button.configure"),
        RENAME("litematica.gui.button.rename"),
        REMOVE(GuiBase.TXT_RED + "-");

        private final String translationKey;

        RegionButtonType(String translationKey)
        {
            this.translationKey = translationKey;
        }

        private String getDisplayName()
        {
            return StringUtils.translate(this.translationKey);
        }
    }

    private enum FieldKind
    {
        ORIGIN_X,
        ORIGIN_Y,
        ORIGIN_Z
    }

    private record ButtonListener(GuiRvcProjectEditor gui, ButtonType type) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case CORNER_MODE -> this.gui.cycleCornerMode();
                case NEW_SUB_REGION -> this.gui.promptNewRegion();
                case PROJECT_PAGE -> this.gui.openProjectPage();
                case SET_ORIGIN_TO_PLAYER -> this.gui.setOriginToPlayer();
            }
        }
    }

    private record RegionButtonListener(GuiRvcProjectEditor gui, RegionButtonType type, String regionId) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case CONFIGURE ->
                {
                    this.gui.selectedRegionId = this.regionId;
                    this.gui.initGui();
                }
                case RENAME -> this.gui.promptRenameRegion(this.regionId);
                case REMOVE -> this.gui.deleteRegion(this.regionId);
            }
        }
    }

    private record CoordinateButtonListener(GuiRvcProjectEditor gui, FieldKind kind) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.gui.nudgeIntegerField(this.kind, mouseButton);
        }
    }

    private record ProjectNameListener(GuiRvcProjectEditor gui) implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            this.gui.updateProjectName(textField.getValueWrapper());
            return false;
        }
    }

    private record RegionSearchListener(GuiRvcProjectEditor gui) implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            this.gui.regionSearchQuery = textField.getValueWrapper();
            this.gui.regionScrollOffset = 0;
            this.gui.rebuildEditorButtons();
            return false;
        }
    }

    private record IntegerFieldListener(GuiRvcProjectEditor gui, FieldKind kind) implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            this.gui.updateIntegerField(this.kind, textField.getValueWrapper());
            return false;
        }
    }

    private record NewRegionCreator(GuiRvcProjectEditor gui) implements IStringConsumerFeedback
    {
        @Override
        public boolean setString(String string)
        {
            if (string == null || string.isBlank())
            {
                this.gui.addMessage(MessageType.ERROR, "litematica.error.rvc_project_editor.region_name_required");
                return false;
            }

            this.gui.createRegion(string);
            return true;
        }
    }

    private record RegionRenamer(GuiRvcProjectEditor gui, String regionId) implements IStringConsumerFeedback
    {
        @Override
        public boolean setString(String string)
        {
            if (string == null || string.isBlank())
            {
                this.gui.addMessage(MessageType.ERROR, "litematica.error.rvc_project_editor.region_name_required");
                return false;
            }

            this.gui.renameRegion(this.regionId, string);
            return true;
        }
    }

}
