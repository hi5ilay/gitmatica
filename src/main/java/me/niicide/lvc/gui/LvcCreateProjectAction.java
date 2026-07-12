package me.niicide.lvc.gui;

import java.nio.file.FileAlreadyExistsException;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcPlayerIdentity;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcSemanticInitProjectTask;
import me.niicide.lvc.task.LvcTaskCallbacks;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.task.LvcTaskScheduling;
import me.niicide.lvc.world.LvcWorldAccess;
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

    public static void createFromAreaEditor(net.minecraft.client.gui.screens.Screen parent)
    {
        GuiLvcTextInputDialog inputGui = new GuiLvcTextInputDialog(
                512,
                "litematica.gui.title.lvc_project_manager.create_project",
                "",
                parent,
                value -> null,
                name ->
                {
                    if (name == null || name.isBlank()) return false;
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null || mc.level == null) return false;
                    AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
                    if (selection == null || selection.getAllSubRegionBoxes().isEmpty()) return false;
                    try
                    {
                        LvcPlayerIdentity identity = new LvcPlayerIdentity(mc.player.getName().getString(), mc.player.getUUID());
                        Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquire("LVC Create Project", mc.gameDirectory.toPath());
                        if (handle.isEmpty()) return false;
                        var captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(mc.level);
                        LvcSemanticInitProjectTask task = new LvcSemanticInitProjectTask(
                                handle.get(), mc.gameDirectory.toPath(), name.trim(), identity, captureWorld, selection,
                                LvcTaskCallbacks.of(
                                        result -> GuiBase.openGui(new GuiLvcProjectManager(result.repositoryDirectory(), result.repositoryDirectory().getFileName().toString())),
                                        e -> LvcGuiMessages.show(fi.dy.masa.malilib.gui.Message.MessageType.ERROR, "litematica.error.lvc_project.create_failed", e.getMessage()),
                                        () -> { }
                                )
                        );
                        LvcTaskScheduling.scheduleForWorld(captureWorld, task);
                        return true;
                    }
                    catch (Exception e) { return false; }
                }
        );
        GuiBase.openGui(inputGui);
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
