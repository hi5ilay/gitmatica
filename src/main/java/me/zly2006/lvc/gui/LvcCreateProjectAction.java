package me.zly2006.lvc.gui;

import java.nio.file.FileAlreadyExistsException;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcPlayerIdentity;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.task.LvcOperationHandle;
import me.zly2006.lvc.task.LvcSemanticInitProjectTask;
import me.zly2006.lvc.task.LvcTaskCallbacks;
import me.zly2006.lvc.task.LvcTaskRegistry;
import me.zly2006.lvc.task.LvcTaskScheduling;
import me.zly2006.lvc.world.LvcWorldAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiSchematicSaveBase;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;

public final class LvcCreateProjectAction
{
    private LvcCreateProjectAction()
    {
    }

    public static void createFromSaveGui(GuiSchematicSaveBase gui, @Nullable String repositoryName)
    {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;

        if (player == null)
        {
            gui.addMessage(MessageType.ERROR, "litematica.error.lvc_project.no_player");
            return;
        }

        if (minecraft.level == null)
        {
            gui.addMessage(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();

        if (selection == null || selection.getAllSubRegionBoxes().isEmpty())
        {
            gui.addMessage(MessageType.ERROR, "litematica.message.error.schematic_save_no_area_selected");
            return;
        }

        int validRegionCount = LvcProjectService.countValidSelectionRegions(selection);

        if (validRegionCount <= 0)
        {
            gui.addMessage(MessageType.ERROR, "litematica.error.lvc_project.simple_selection_required");
            return;
        }

        if (repositoryName == null || repositoryName.isBlank())
        {
            gui.addMessage(MessageType.ERROR, "litematica.error.schematic_save.invalid_schematic_name", repositoryName);
            return;
        }

        try
        {
            LvcPlayerIdentity identity = new LvcPlayerIdentity(player.getName().getString(), player.getUUID());
            Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquire("LVC Create Project",
                    minecraft.gameDirectory.toPath());

            if (handle.isEmpty())
            {
                gui.addMessage(MessageType.ERROR, "litematica.error.lvc_project.operation_running",
                        LvcTaskRegistry.activeOperationName());
                return;
            }

            LvcDiagnostics.debug("LvcCreateProjectAction: scheduling create project name='{}' regions={} player='{}'",
                    repositoryName, validRegionCount, identity.name());
            var captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(minecraft.level);
            LvcSemanticInitProjectTask task = new LvcSemanticInitProjectTask(
                    handle.get(),
                    minecraft.gameDirectory.toPath(),
                    repositoryName,
                    identity,
                    captureWorld,
                    selection,
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                String projectName = result.repositoryDirectory().getFileName().toString();
                                GuiLvcProjectManager projectGui = new GuiLvcProjectManager(result.repositoryDirectory(), projectName);
                                GuiBase.openGui(projectGui);
                                projectGui.addMessage(MessageType.SUCCESS, "litematica.message.lvc_project.created",
                                        result.repositoryDirectory(), result.commitId());
                            },
                            e ->
                            {
                                if (e instanceof FileAlreadyExistsException)
                                {
                                    LvcGuiMessages.show(MessageType.ERROR,
                                            "litematica.error.lvc_project_manager.project_name_used");
                                }
                                else
                                {
                                    LvcGuiMessages.show(MessageType.ERROR,
                                            "litematica.error.lvc_project.create_failed", e.getMessage());
                                }
                            },
                            () -> LvcGuiMessages.show(MessageType.INFO,
                                    "litematica.message.lvc_project.task_aborted", "LVC Create Project")
                    )
            );
            LvcTaskScheduling.scheduleForWorld(captureWorld, task);
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_started", "LVC Create Project");
        }
        catch (Exception e)
        {
            gui.addMessage(MessageType.ERROR, "litematica.error.lvc_project.create_failed", e.getMessage());
        }
    }
}
