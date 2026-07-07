package me.zly2006.rvc;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import fi.dy.masa.litematica.Reference;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfirmAction;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.GuiTextInputBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntryType;
import fi.dy.masa.malilib.interfaces.IConfirmationListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiRvcProjectManager extends GuiListBase<DirectoryEntry, WidgetDirectoryEntry, WidgetRvcProjectBrowser>
        implements ISelectionListener<DirectoryEntry>
{
    private static final int CREATE_PROJECT_ERROR_VERTICAL_SPACE = 14;
    private static final int CREATE_PROJECT_ERROR_BOX_HEIGHT = 12;
    private static final int CREATE_PROJECT_ERROR_HORIZONTAL_INSET = 1;
    private static final int CREATE_PROJECT_ERROR_COLOR = 0xFFFF5555;

    public GuiRvcProjectManager()
    {
        super(10, 30);
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("litematica.gui.title.rvc_project_browser", Reference.MOD_VERSION);
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight()
    {
        return this.getScreenHeight() - 58;
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.createElements();
    }

    private void createElements()
    {
        int x = 10;
        int y = this.getScreenHeight() - 24;

        x += this.createButton(x, y, false, ButtonListener.Type.CREATE_PROJECT);
        x += this.createButton(x, y, false, ButtonListener.Type.CLONE_PROJECT);

        DirectoryEntry selected = this.getSelectedProjectEntry();

        if (selected != null)
        {
            x += this.createButton(x, y, false, ButtonListener.Type.OPEN_PROJECT);
            x += this.createButton(x, y, false, ButtonListener.Type.PROJECT_SETTINGS);
            x += this.createButton(x, y, false, ButtonListener.Type.DELETE_PROJECT);
        }

        String label = StringUtils.translate("litematica.gui.button.rvc_project.litematica_menu");
        int buttonWidth = this.getStringWidth(label) + 20;
        this.addButton(new ButtonGeneric(this.getScreenWidth() - buttonWidth - 10, y, buttonWidth, 20, label), (button, mouseButton) -> GuiBase.openGui(new GuiMainMenu()));
    }

    private int createButton(int x, int y, boolean rightAlign, ButtonListener.Type type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, rightAlign, type.translationKey);
        this.addButton(button, new ButtonListener(type, this));
        return button.getWidth() + 2;
    }

    private void reCreateGuiElements()
    {
        this.clearButtons();
        this.clearWidgets();
        this.createElements();
    }

    @Override
    @Nullable
    protected ISelectionListener<DirectoryEntry> getSelectionListener()
    {
        return this;
    }

    @Override
    public void onSelectionChange(@Nullable DirectoryEntry entry)
    {
        this.reCreateGuiElements();
    }

    @Override
    protected WidgetRvcProjectBrowser createListWidget(int listX, int listY)
    {
        return new WidgetRvcProjectBrowser(listX, listY, 100, 100, this.getSelectionListener());
    }

    @Nullable
    private DirectoryEntry getSelectedProjectEntry()
    {
        if (this.getListWidget() == null)
        {
            return null;
        }

        DirectoryEntry entry = this.getListWidget().getLastSelectedEntry();
        return entry != null && entry.type() == DirectoryEntryType.FILE ? entry : null;
    }

    private void openSelectedProject()
    {
        DirectoryEntry entry = this.getSelectedProjectEntry();

        if (entry == null)
        {
            this.addMessage(MessageType.INFO, "litematica.message.rvc_project_manager.no_project_selected");
            return;
        }

        GuiBase.openGui(new GuiRvcProject(entry.getFullPath(), entry.name()));
    }

    private void openSelectedProjectSettings()
    {
        DirectoryEntry entry = this.getSelectedProjectEntry();

        if (entry == null)
        {
            this.addMessage(MessageType.INFO, "litematica.message.rvc_project_manager.no_project_selected");
            return;
        }

        GuiBase.openGui(new GuiRvcProjectSettings(entry.getFullPath(), entry.name(), true));
    }

    private void promptCreateProject()
    {
        GuiBase.openGui(new CreateProjectDialog(this));
    }

    private void createEmptyProject(String projectName) throws Exception
    {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        BlockPos origin = player != null ? fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(player) : BlockPos.ZERO;
        String dimensionId = minecraft.level != null ? RvcMinecraftWorldReader.dimensionId(minecraft.level) : "minecraft:overworld";
        RvcProjectService.EmptyProjectResult result = RvcProjectService.createEmptyProject(minecraft.gameDirectory.toPath(), projectName, origin, dimensionId);
        WidgetRvcProjectBrowser listWidget = this.getListWidget();

        if (listWidget != null)
        {
            listWidget.refreshEntries();
        }

        this.reCreateGuiElements();
        this.addMessage(MessageType.SUCCESS, "litematica.message.rvc_project_manager.project_created", result.projectName());
    }

    private void confirmDeleteSelectedProject()
    {
        DirectoryEntry entry = this.getSelectedProjectEntry();

        if (entry == null)
        {
            this.addMessage(MessageType.INFO, "litematica.message.rvc_project_manager.no_project_selected");
            return;
        }

        ProjectDeleter deleter = new ProjectDeleter(entry.getFullPath(), entry.name(), this);
        GuiBase.openGui(new GuiConfirmAction(
                420,
                "litematica.gui.title.rvc_project_manager.confirm_delete_project",
                deleter,
                this,
                "litematica.gui.message.rvc_project_manager.confirm_delete_project",
                entry.name()
        ));
    }

    private void showNotImplemented(String key)
    {
        this.addMessage(MessageType.INFO, key);
    }

    private record ProjectDeleter(Path repositoryDirectory, String projectName, GuiRvcProjectManager gui) implements IConfirmationListener
    {
        @Override
        public boolean onActionConfirmed()
        {
            try
            {
                RvcProjectService.deleteProjectRepository(Minecraft.getInstance().gameDirectory.toPath(), this.repositoryDirectory);
                WidgetRvcProjectBrowser listWidget = this.gui.getListWidget();

                if (listWidget != null)
                {
                    listWidget.clearSelection();
                    listWidget.refreshEntries();
                }

                this.gui.reCreateGuiElements();
                this.gui.addMessage(MessageType.SUCCESS, "litematica.message.rvc_project_manager.project_deleted", this.projectName);
                return true;
            }
            catch (IOException | RuntimeException e)
            {
                this.gui.addMessage(MessageType.ERROR, "litematica.error.rvc_project_manager.delete_failed", e.getMessage());
                return false;
            }
        }

        @Override
        public boolean onActionCancelled()
        {
            return false;
        }
    }

    private static class CreateProjectDialog extends GuiTextInputBase
    {
        private final GuiRvcProjectManager gui;
        @Nullable private String errorMessage;
        private boolean errorSpaceVisible;

        private CreateProjectDialog(GuiRvcProjectManager gui)
        {
            super(128, "litematica.gui.title.rvc_project_manager.create_project", "", gui);
            this.gui = gui;
        }

        @Override
        public void initGui()
        {
            this.clearElements();

            int x = this.dialogLeft + 10;
            int y = this.getButtonY();

            x += this.createButton(x, y, ButtonType.OK) + 2;
            x += this.createButton(x, y, ButtonType.RESET) + 2;
            this.createButton(x, y, ButtonType.CANCEL);
        }

        @Override
        public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
        {
            super.drawContents(ctx, mouseX, mouseY, partialTicks);

            if (this.errorMessage != null)
            {
                int errorTop = this.getErrorTopY();
                int errorLeft = this.textField.getX() + CREATE_PROJECT_ERROR_HORIZONTAL_INSET;
                int errorWidth = this.textField.getWidth() - CREATE_PROJECT_ERROR_HORIZONTAL_INSET * 2;
                RenderUtils.drawOutlinedBox(ctx, errorLeft, errorTop, errorWidth, CREATE_PROJECT_ERROR_BOX_HEIGHT, 0x80300000, CREATE_PROJECT_ERROR_COLOR);
                ctx.drawString(ctx.fontRenderer(), this.errorMessage, errorLeft + 4, errorTop + 2, CREATE_PROJECT_ERROR_COLOR, false);
            }
        }

        private int getButtonY()
        {
            int errorSpace = this.errorSpaceVisible ? CREATE_PROJECT_ERROR_VERTICAL_SPACE : 0;
            return this.dialogTop + this.totalHeight + this.buttonHeight + 10 + errorSpace;
        }

        private int getErrorTopY()
        {
            int inputBottom = this.textField.getY() + this.textField.getHeight();
            int availableSpace = Math.max(0, this.getButtonY() - inputBottom - CREATE_PROJECT_ERROR_BOX_HEIGHT);
            return inputBottom + availableSpace / 2;
        }

        @Override
        protected boolean applyValue(String projectName)
        {
            if (projectName == null || projectName.isBlank())
            {
                this.showError(StringUtils.translate("litematica.error.rvc_project_editor.project_name_required"));
                return false;
            }

            try
            {
                this.gui.createEmptyProject(projectName.trim());
                this.errorMessage = null;
                return true;
            }
            catch (FileAlreadyExistsException e)
            {
                this.showError(StringUtils.translate("litematica.error.rvc_project_manager.project_name_used"));
                return false;
            }
            catch (Exception e)
            {
                this.showError(StringUtils.translate("litematica.error.rvc_project.create_failed", e.getMessage()));
                return false;
            }
        }

        private void showError(String message)
        {
            this.errorMessage = message;
            this.textField.setFocused(true);

            if (this.errorSpaceVisible)
            {
                return;
            }

            this.errorSpaceVisible = true;
            this.setWidthAndHeight(this.dialogWidth, this.dialogHeight + CREATE_PROJECT_ERROR_VERTICAL_SPACE);
            this.initGui();
        }
    }

    private record ButtonListener(Type type, GuiRvcProjectManager gui) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case CREATE_PROJECT -> this.gui.promptCreateProject();
                case CLONE_PROJECT -> this.gui.showNotImplemented("litematica.message.rvc_project_manager.clone_project_not_implemented");
                case OPEN_PROJECT -> this.gui.openSelectedProject();
                case PROJECT_SETTINGS -> this.gui.openSelectedProjectSettings();
                case DELETE_PROJECT -> this.gui.confirmDeleteSelectedProject();
            }
        }

        private enum Type
        {
            CREATE_PROJECT("litematica.gui.button.rvc_project.create_short"),
            CLONE_PROJECT("litematica.gui.button.rvc_project.clone"),
            OPEN_PROJECT("litematica.gui.button.rvc_project.open_project"),
            PROJECT_SETTINGS("litematica.gui.button.rvc_project.project_settings"),
            DELETE_PROJECT("litematica.gui.button.rvc_project.delete");

            private final String translationKey;

            Type(String translationKey)
            {
                this.translationKey = translationKey;
            }
        }
    }
}
