package me.zly2006.lvc.gui;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcFriendlyErrors.Operation;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.gui.widgets.WidgetLvcProjectBrowser;
import me.zly2006.lvc.project.LvcProjectPaths;
import me.zly2006.lvc.project.LvcProjectSelectionStorage;

import fi.dy.masa.litematica.Reference;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntryType;
import fi.dy.masa.malilib.interfaces.IConfirmationListener;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiLvcProjectBrowser extends GuiListBase<DirectoryEntry, WidgetDirectoryEntry, WidgetLvcProjectBrowser>
        implements ISelectionListener<DirectoryEntry>
{
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_ROW_GAP = 4;
    private static final int BUTTON_BOTTOM_MARGIN = 4;
    private static final int BUTTON_TEXTURE_HORIZONTAL_INSET = 2;
    private int bottomActionRows = 1;

    public GuiLvcProjectBrowser()
    {
        super(10, 30);
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("litematica.gui.title.lvc_project_browser", Reference.MOD_VERSION);
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight()
    {
        return Math.max(0, this.getBottomButtonY() - BUTTON_ROW_GAP - this.getListY());
    }

    @Override
    public void initGui()
    {
        this.bottomActionRows = this.calculateButtonRows(this.currentButtonTypes());
        super.initGui();
        this.createElements();
    }

    private void createElements()
    {
        this.createWrappedButtonRow(this.getBottomButtonY(), this.currentButtonTypes());
    }

    private ButtonListener.Type[] currentButtonTypes()
    {
        DirectoryEntry selected = this.getSelectedProjectEntry();

        if (selected != null)
        {
            return new ButtonListener.Type[] {
                    ButtonListener.Type.CREATE_PROJECT,
                    ButtonListener.Type.CLONE_PROJECT,
                    ButtonListener.Type.OPEN_PROJECT,
                    ButtonListener.Type.DELETE_PROJECT,
                    ButtonListener.Type.LITEMATICA_MENU
            };
        }

        return new ButtonListener.Type[] {
                ButtonListener.Type.CREATE_PROJECT,
                ButtonListener.Type.CLONE_PROJECT,
                ButtonListener.Type.LITEMATICA_MENU
        };
    }

    private int createButton(int x, int y, ButtonListener.Type type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, false, type.translationKey);
        this.addButton(button, new ButtonListener(type, this));
        return button.getWidth() + 2;
    }

    private int calculateButtonRows(ButtonListener.Type[] types)
    {
        int startX = this.getButtonRowLeftX();
        int maxRightX = this.getLeftButtonMaxRightX(types);
        int x = startX;
        int rows = 1;

        for (ButtonListener.Type type : types)
        {
            if (this.shouldAnchorRight(type, types))
            {
                continue;
            }

            int width = this.getButtonWidth(type);

            if (x > startX && x + width > maxRightX)
            {
                rows++;
                x = startX;
            }

            x += width + 2;
        }

        return rows;
    }

    private void createWrappedButtonRow(int startY, ButtonListener.Type[] types)
    {
        int startX = this.getButtonRowLeftX();
        int maxRightX = this.getLeftButtonMaxRightX(types);
        int x = startX;
        int y = startY;

        if (this.shouldAnchorLitematicaMenu(types))
        {
            ButtonListener.Type menu = ButtonListener.Type.LITEMATICA_MENU;
            this.createButton(this.getMetadataButtonRightX() - this.getButtonWidth(menu), startY, menu);
        }

        for (ButtonListener.Type type : types)
        {
            if (this.shouldAnchorRight(type, types))
            {
                continue;
            }

            int width = this.getButtonWidth(type);

            if (x > startX && x + width > maxRightX)
            {
                x = startX;
                y += BUTTON_HEIGHT + BUTTON_ROW_GAP;
            }

            x += this.createButton(x, y, type);
        }
    }

    private int getButtonWidth(ButtonListener.Type type)
    {
        return new ButtonGeneric(0, 0, -1, false, type.translationKey).getWidth();
    }

    private int getLeftButtonMaxRightX(ButtonListener.Type[] types)
    {
        if (this.shouldAnchorLitematicaMenu(types))
        {
            return this.getMetadataButtonRightX() - this.getButtonWidth(ButtonListener.Type.LITEMATICA_MENU) - BUTTON_ROW_GAP;
        }

        return this.getBrowserButtonRightX();
    }

    private int getBrowserPanelRightX()
    {
        return this.getPanelLeftX() + WidgetLvcProjectBrowser.getBrowserPanelWidthForTotalWidth(this.getBrowserWidth());
    }

    private int getMetadataPanelRightX()
    {
        return this.getPanelLeftX() + this.getBrowserWidth();
    }

    private int getPanelLeftX()
    {
        return this.getListX();
    }

    private int getButtonRowLeftX()
    {
        return this.getPanelLeftX() - BUTTON_TEXTURE_HORIZONTAL_INSET;
    }

    private int getBrowserButtonRightX()
    {
        return this.getBrowserPanelRightX() + BUTTON_TEXTURE_HORIZONTAL_INSET;
    }

    private int getMetadataButtonRightX()
    {
        return this.getMetadataPanelRightX() + BUTTON_TEXTURE_HORIZONTAL_INSET;
    }

    private boolean shouldAnchorRight(ButtonListener.Type type, ButtonListener.Type[] types)
    {
        return type == ButtonListener.Type.LITEMATICA_MENU && this.shouldAnchorLitematicaMenu(types);
    }

    private boolean shouldAnchorLitematicaMenu(ButtonListener.Type[] types)
    {
        return this.getBrowserWidth() >= 340 && this.hasButton(types, ButtonListener.Type.LITEMATICA_MENU);
    }

    private boolean hasButton(ButtonListener.Type[] types, ButtonListener.Type target)
    {
        for (ButtonListener.Type type : types)
        {
            if (type == target)
            {
                return true;
            }
        }

        return false;
    }

    private int getBottomButtonY()
    {
        return this.getScreenHeight() - BUTTON_BOTTOM_MARGIN - this.getButtonRowsHeight(this.bottomActionRows);
    }

    private int getButtonRowsHeight(int rows)
    {
        return rows * BUTTON_HEIGHT + Math.max(0, rows - 1) * BUTTON_ROW_GAP;
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
    protected WidgetLvcProjectBrowser createListWidget(int listX, int listY)
    {
        return new WidgetLvcProjectBrowser(listX, listY, 100, 100, this.getSelectionListener());
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
            this.addMessage(MessageType.INFO, "litematica.message.lvc_project_manager.no_project_selected");
            return;
        }

        GuiBase.openGui(new GuiLvcProjectManager(entry.getFullPath(), entry.name()));
    }

    private void confirmDeleteSelectedProject()
    {
        DirectoryEntry entry = this.getSelectedProjectEntry();

        if (entry == null)
        {
            this.addMessage(MessageType.INFO, "litematica.message.lvc_project_manager.no_project_selected");
            return;
        }

        ProjectDeleter deleter = new ProjectDeleter(entry.getFullPath(), entry.name(), this);
        GuiBase.openGui(new GuiLvcConfirmAction(
                420,
                "litematica.gui.title.lvc_project_manager.confirm_delete_project",
                deleter,
                this,
                "litematica.gui.message.lvc_project_manager.confirm_delete_project",
                entry.name()
        ));
    }

    private void promptCreateProject()
    {
        ProjectCreator creator = new ProjectCreator(this);
        GuiBase.openGui(new GuiLvcTextInputDialog(
                256,
                "litematica.gui.title.lvc_project_manager.create_project",
                "",
                this,
                creator::validateProjectName,
                (java.util.function.Consumer<String>) creator::createProject
        ));
    }

    private void showNotImplemented(String key)
    {
        this.addMessage(MessageType.INFO, key);
    }

    private record ProjectDeleter(Path repositoryDirectory, String projectName, GuiLvcProjectBrowser gui) implements IConfirmationListener
    {
        @Override
        public boolean onActionConfirmed()
        {
            try
            {
                LvcProjectService.deleteProjectRepository(Minecraft.getInstance().gameDirectory.toPath(), this.repositoryDirectory);
                WidgetLvcProjectBrowser listWidget = this.gui.getListWidget();

                if (listWidget != null)
                {
                    listWidget.clearSelection();
                    listWidget.refreshEntries();
                }

                this.gui.reCreateGuiElements();
                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project_manager.project_deleted", this.projectName);
                return true;
            }
            catch (IOException | RuntimeException e)
            {
                LvcGuiMessages.showTaskError(Operation.PROJECT_BROWSER, "litematica.error.lvc_project_manager.delete_failed", e);
                return false;
            }
        }

        @Override
        public boolean onActionCancelled()
        {
            return false;
        }
    }

    private record ProjectCreator(GuiLvcProjectBrowser gui)
    {
        @Nullable
        private String validateProjectName(String projectName)
        {
            try
            {
                if (projectName == null || projectName.isBlank())
                {
                    LvcDiagnostics.debug("GuiLvcProjectBrowser: create project validation failed input='{}' error='missing name'",
                            projectName);
                    return StringUtils.translate("litematica.error.lvc_project_editor.project_name_required");
                }

                LvcProjectSelectionStorage.validateProjectName(projectName);
                Path repositoryDirectory = LvcProjectPaths.repositoryDirectory(
                        Minecraft.getInstance().gameDirectory.toPath(),
                        projectName
                );

                if (Files.exists(repositoryDirectory))
                {
                    LvcDiagnostics.debug("GuiLvcProjectBrowser: create project validation failed input='{}' path='{}' error='exists'",
                            projectName, repositoryDirectory);
                    return StringUtils.translate("litematica.error.lvc_project_manager.project_name_used");
                }

                return null;
            }
            catch (IllegalArgumentException e)
            {
                LvcDiagnostics.debug("GuiLvcProjectBrowser: create project validation failed input='{}' error='{}'",
                        projectName, e.getMessage());
                return e.getMessage();
            }
        }

        private void createProject(String projectName)
        {
            try
            {
                LvcProjectService.EmptyProjectResult result = LvcProjectService.createEmptyProject(
                        Minecraft.getInstance().gameDirectory.toPath(),
                        projectName,
                        this.defaultOrigin(),
                        this.defaultDimension()
                );

                WidgetLvcProjectBrowser listWidget = this.gui.getListWidget();

                if (listWidget != null)
                {
                    listWidget.refreshEntries();
                }

                this.gui.reCreateGuiElements();
                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project_manager.project_created", result.projectName());
            }
            catch (FileAlreadyExistsException e)
            {
                LvcDiagnostics.debug("GuiLvcProjectBrowser: create project failed after popup input='{}' error='exists'",
                        projectName);
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project_manager.project_name_used");
            }
            catch (Exception e)
            {
                LvcDiagnostics.debug("GuiLvcProjectBrowser: create project failed after popup input='{}' error='{}'",
                        projectName, e.getMessage());
                LvcGuiMessages.showTaskError(Operation.PROJECT_BROWSER, "litematica.error.lvc_project.create_failed", e);
            }
        }

        private BlockPos defaultOrigin()
        {
            if (Minecraft.getInstance().player != null)
            {
                return fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(Minecraft.getInstance().player);
            }

            return BlockPos.ZERO;
        }

        private String defaultDimension()
        {
            if (Minecraft.getInstance().level != null)
            {
                return LvcMinecraftWorldReader.dimensionId(Minecraft.getInstance().level);
            }

            return "minecraft:overworld";
        }
    }

    private record ButtonListener(Type type, GuiLvcProjectBrowser gui) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case CREATE_PROJECT -> this.gui.showNotImplemented("litematica.message.lvc_project_manager.create_project_not_implemented");
                case CLONE_PROJECT -> this.gui.showNotImplemented("litematica.message.lvc_project_manager.clone_project_not_implemented");
                case OPEN_PROJECT -> this.gui.openSelectedProject();
                case DELETE_PROJECT -> this.gui.confirmDeleteSelectedProject();
                case LITEMATICA_MENU -> GuiBase.openGui(new GuiMainMenu());
            }
        }

        private enum Type
        {
            CREATE_PROJECT("litematica.gui.button.lvc_project.create_short"),
            CLONE_PROJECT("litematica.gui.button.lvc_project.clone"),
            OPEN_PROJECT("litematica.gui.button.lvc_project.open_project"),
            DELETE_PROJECT("litematica.gui.button.lvc_project.delete"),
            LITEMATICA_MENU("litematica.gui.button.lvc_project.litematica_menu");

            private final String translationKey;

            Type(String translationKey)
            {
                this.translationKey = translationKey;
            }
        }
    }
}
