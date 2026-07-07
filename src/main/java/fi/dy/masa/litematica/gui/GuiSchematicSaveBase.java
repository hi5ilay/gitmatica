package fi.dy.masa.litematica.gui;

import javax.annotation.Nullable;
import java.nio.file.FileAlreadyExistsException;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetCheckBox;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntryType;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.FileNameUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import me.zly2006.rvc.GuiRvcProjectManager;
import me.zly2006.rvc.RvcPlayerIdentity;
import me.zly2006.rvc.RvcProjectService;

public abstract class GuiSchematicSaveBase extends GuiSchematicBrowserBase implements ISelectionListener<DirectoryEntry>
{
    protected GuiTextFieldGeneric textField;
    protected WidgetCheckBox checkboxIgnoreEntities;
    protected WidgetCheckBox checkboxVisibleOnly;
    protected WidgetCheckBox checkboxIncludeSupportBlocks;
    protected final WidgetCheckBox checkboxSaveFromSchematicWorld;
    protected String lastText = "";
    protected String defaultText = "";
    @Nullable protected final LitematicaSchematic schematic;

    public GuiSchematicSaveBase(@Nullable LitematicaSchematic schematic)
    {
        super(10, 80);

        this.schematic = schematic;

        this.textField = new GuiTextFieldGeneric(10, 32, 160, 20, this.font);
        this.textField.setMaxLengthWrapper(256);
        this.textField.setFocusedWrapper(true);

        this.checkboxSaveFromSchematicWorld = new WidgetCheckBox(0, 0, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, StringUtils.translate("litematica.gui.label.schematic_save.checkbox.save_from_schematic_world"), StringUtils.translate("litematica.gui.label.schematic_save.hover_info.save_from_schematic_world"));
    }

    @Override
    public int getBrowserHeight()
    {
        return this.getScreenHeight() - 80;
    }

    @Override
    public void initGui()
    {
        super.initGui();

        boolean focused = this.textField.isFocusedWrapper();
        String text = this.textField.getValueWrapper();
        this.textField = new GuiTextFieldGeneric(10, 32, this.getScreenWidth() - 260, 18, this.font);
        this.textField.setValueWrapper(text);
        this.textField.setFocusedWrapper(focused);

        DirectoryEntry entry = this.getListWidget().getLastSelectedEntry();

        // Only set the text field contents if it hasn't been set already.
        // This prevents overwriting any user input text when switching to a newly created directory.
        if (this.lastText.isEmpty())
        {
            if (entry != null && entry.type() != DirectoryEntryType.DIRECTORY && entry.type() != DirectoryEntryType.INVALID)
            {
                this.setTextFieldText(FileNameUtils.getFileNameWithoutExtension(entry.name()));
            }
            else if (this.schematic != null)
            {
                this.setTextFieldText(this.schematic.getMetadata().getName());
            }
            else
            {
                this.setTextFieldText(this.defaultText);
            }
        }

        int x = this.textField.getXWrapper() + this.textField.getWidthWrapper() + 4;
        int y = 28;

        String str = StringUtils.translate("litematica.gui.label.schematic_save.checkbox.ignore_entities");
        this.checkboxIgnoreEntities = new WidgetCheckBox(x, y, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, str);
        this.addWidget(this.checkboxIgnoreEntities);

        this.checkboxSaveFromSchematicWorld.setPosition(x, y + 12);
        this.addWidget(this.checkboxSaveFromSchematicWorld);

        this.checkboxVisibleOnly = new WidgetCheckBox(x, y + 24, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, StringUtils.translate("litematica.gui.label.schematic_save.checkbox.visible_blocks_only"));
        this.addWidget(this.checkboxVisibleOnly);

        this.checkboxIncludeSupportBlocks = new WidgetCheckBox(x, y + 36, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, StringUtils.translate("litematica.gui.label.schematic_save.checkbox.support_blocks"), StringUtils.translate("litematica.gui.label.schematic_save.hover_info.support_blocks"));
        this.addWidget(this.checkboxIncludeSupportBlocks);

        int buttonX = this.createButton(10, 54, ButtonType.SAVE);

        if (this.shouldShowCreateRvcProjectButton())
        {
            this.createButton(buttonX, 54, ButtonType.CREATE_RVC_PROJECT);
        }
    }

    protected void setTextFieldText(String text)
    {
        this.lastText = text;
        this.textField.setValueWrapper(text);
    }

    protected String getTextFieldText()
    {
        return this.textField.getValueWrapper();
    }

    protected abstract IButtonActionListener createButtonListener(ButtonType type);

    protected boolean shouldShowCreateRvcProjectButton()
    {
        return false;
    }

    private int createButton(int x, int y, ButtonType type)
    {
        String label = StringUtils.translate(type.getLabelKey());
        int width = this.getStringWidth(label) + 10;

        ButtonGeneric button;

        if (type == ButtonType.SAVE)
        {
            button = new ButtonGeneric(x, y, width, 20, label, "litematica.gui.label.schematic_save.hover_info.hold_shift_to_overwrite");
        }
        else
        {
            button = new ButtonGeneric(x, y, width, 20, label);
        }

        this.addButton(button, type == ButtonType.CREATE_RVC_PROJECT ? new ButtonListenerCreateRvcProject(this) : this.createButtonListener(type));

        return x + width + 4;
    }

    @Override
    public void setString(String string)
    {
        this.setNextMessageType(MessageType.ERROR);
        super.setString(string);
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);

        this.textField.renderWrapper(ctx, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onSelectionChange(@Nullable DirectoryEntry entry)
    {
        if (entry != null && entry.type() != DirectoryEntryType.DIRECTORY && entry.type() != DirectoryEntryType.INVALID)
        {
            this.setTextFieldText(FileNameUtils.getFileNameWithoutExtension(entry.name()));
        }
    }

    @Override
    protected ISelectionListener<DirectoryEntry> getSelectionListener()
    {
        return this;
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.textField.mouseClickedWrapper(click, doubleClick))
        {
            return true;
        }

        return super.onMouseClicked(click, doubleClick);
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (this.textField.keyPressedWrapper(input))
        {
            this.getListWidget().clearSelection();
            return true;
        }
        else if (input.key() == KeyCodes.KEY_TAB)
        {
            this.textField.setFocusedWrapper(! this.textField.isFocusedWrapper());
            return true;
        }

        return super.onKeyTyped(input);
    }

    @Override
    public boolean onCharTyped(CharacterEvent input)
    {
        if (this.textField.charTypedWrapper(input))
        {
            this.getListWidget().clearSelection();
            return true;
        }

        return super.onCharTyped(input);
    }

    public enum ButtonType
    {
        SAVE ("litematica.gui.button.save_schematic"),
        CREATE_RVC_PROJECT ("litematica.gui.button.rvc_project.create");

        private final String labelKey;

        ButtonType(String labelKey)
        {
            this.labelKey = labelKey;
        }

        public String getLabelKey()
        {
            return this.labelKey;
        }
    }

    private record ButtonListenerCreateRvcProject(GuiSchematicSaveBase gui) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            Minecraft minecraft = Minecraft.getInstance();
            Player player = minecraft.player;

            if (player == null)
            {
                this.gui.addMessage(MessageType.ERROR, "litematica.error.rvc_project.no_player");
                return;
            }

            if (minecraft.level == null)
            {
                this.gui.addMessage(MessageType.ERROR, "litematica.error.rvc_project.no_world");
                return;
            }

            AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();

            if (selection == null || selection.getAllSubRegionBoxes().isEmpty())
            {
                this.gui.addMessage(MessageType.ERROR, "litematica.message.error.schematic_save_no_area_selected");
                return;
            }

            String repositoryName = this.gui.getTextFieldText();

            if (repositoryName == null || repositoryName.isBlank())
            {
                this.gui.addMessage(MessageType.ERROR, "litematica.error.schematic_save.invalid_schematic_name", repositoryName);
                return;
            }

            try
            {
                RvcPlayerIdentity identity = new RvcPlayerIdentity(player.getName().getString(), player.getUUID());
                RvcProjectService.Result result = RvcProjectService.createProject(
                        minecraft.gameDirectory.toPath(),
                        repositoryName,
                        identity,
                        minecraft.level,
                        selection
                );
                this.gui.addMessage(MessageType.SUCCESS, "litematica.message.rvc_project.created", result.repositoryDirectory(), result.commitId());
                GuiBase.openGui(new GuiRvcProjectManager());
            }
            catch (FileAlreadyExistsException e)
            {
                this.gui.addMessage(MessageType.ERROR, "litematica.error.rvc_project_manager.project_name_used");
            }
            catch (Exception e)
            {
                this.gui.addMessage(MessageType.ERROR, "litematica.error.rvc_project.create_failed", e.getMessage());
            }
        }
    }
}
