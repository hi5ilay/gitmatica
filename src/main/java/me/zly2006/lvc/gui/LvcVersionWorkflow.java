package me.zly2006.lvc.gui;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.eclipse.jgit.revwalk.RevCommit;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcFriendlyErrors.Operation;
import me.zly2006.lvc.LvcPlayerIdentity;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.task.LvcAuthoritativeScanSync;
import me.zly2006.lvc.task.LvcOperationHandle;
import me.zly2006.lvc.task.LvcSemanticCommitTask;
import me.zly2006.lvc.task.LvcTaskCallbacks;
import me.zly2006.lvc.task.LvcTaskRegistry;
import me.zly2006.lvc.world.LvcWorldAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.StringUtils;

final class LvcVersionWorkflow
{
    private LvcVersionWorkflow()
    {
    }

    static void scanChanges(GuiLvcProjectController controller)
    {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel world = minecraft.level;

        if (world == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        ServerLevel serverWorld;

        try
        {
            Level authoritativeWorld = LvcWorldAccess.resolveSemanticRestoreWorld(world);

            if (!(authoritativeWorld instanceof ServerLevel resolvedServerWorld))
            {
                throw new IllegalStateException("Scan Changes requires a server-authoritative world");
            }

            serverWorld = resolvedServerWorld;
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.SCAN_CHANGES, "litematica.error.lvc_project.scan_failed", e);
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Scan Changes");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            LvcAuthoritativeScanSync.schedule(
                    handle.get(),
                    controller.gui.repositoryDirectory,
                    serverWorld,
                    world,
                    LvcTaskCallbacks.of(
                            result -> reportScanResult(controller, result.scanResult()),
                            e -> LvcGuiMessages.showTaskError(Operation.SCAN_CHANGES, "litematica.error.lvc_project.scan_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Scan Changes")
                    )
            );
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_started", "LVC Scan Changes");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.SCAN_CHANGES, "litematica.error.lvc_project.scan_failed", e);
        }
    }

    static void commitStoredSelectionWithCurrentSelectionFallback(GuiLvcProjectController controller, String message)
    {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level world = minecraft.level;

        if (player == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_player");
            return;
        }

        if (world == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Save Version");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            if (LvcProjectService.isDetachedHead(controller.gui.repositoryDirectory))
            {
                LvcTaskRegistry.release(handle.get());
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.detached_head_save_version");
                return;
            }

            Level captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(world);
            LvcPlayerIdentity identity = new LvcPlayerIdentity(player.getName().getString(), player.getUUID());
            LvcSemanticCommitTask task = new LvcSemanticCommitTask(
                    handle.get(),
                    controller.gui.repositoryDirectory,
                    captureWorld,
                    identity,
                    message,
                    LvcSemanticCommitTask.Mode.SAVE_VERSION,
                    List.of(),
                    LvcTaskCallbacks.of(
                            result -> handleCommitResult(controller, result.commit()),
                            e -> LvcGuiMessages.showTaskError(Operation.SAVE_VERSION, "litematica.error.lvc_project.commit_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Save Version")
                    )
            );
            LvcOperationCoordinator.scheduleStarted(captureWorld, task, "LVC Save Version");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.SAVE_VERSION, "litematica.error.lvc_project.commit_failed", e);
        }
    }

    static void updateAreasFromCurrentSelection(GuiLvcProjectController controller)
    {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level world = minecraft.level;
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();

        if (player == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_player");
            return;
        }

        if (world == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        if (selection == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.update_areas_failed",
                    StringUtils.translate("litematica.error.lvc_project.no_selection"));
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Update Areas");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            if (LvcProjectService.isDetachedHead(controller.gui.repositoryDirectory))
            {
                LvcTaskRegistry.release(handle.get());
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.update_areas_failed",
                        StringUtils.translate("litematica.error.lvc_project.detached_head_commit"));
                return;
            }

            Level captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(world);
            LvcPlayerIdentity identity = new LvcPlayerIdentity(player.getName().getString(), player.getUUID());
            var state = LvcProjectService.readSemanticProjectEditorState(controller.gui.repositoryDirectory);
            List<LvcManifest.Region> updatedRegions = LvcProjectService.createRegionsFromSelection(selection,
                    state.localOrigin(), state.regions());
            LvcSemanticCommitTask task = new LvcSemanticCommitTask(
                    handle.get(),
                    controller.gui.repositoryDirectory,
                    captureWorld,
                    identity,
                    "update areas",
                    LvcSemanticCommitTask.Mode.UPDATE_AREAS,
                    updatedRegions,
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                if (result.commit() == null)
                                {
                                    controller.focusTrackingOverlay();
                                    controller.gui.initGui();
                                    LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.nothing_to_commit");
                                    return;
                                }

                                controller.loadTrackingOverlay();
                                controller.gui.initGui();
                                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.update_areas_updated",
                                        LvcOperationCoordinator.regionCountText(result.regionCount()));
                            },
                            e -> LvcGuiMessages.showTaskError(Operation.UPDATE_AREAS, "litematica.error.lvc_project.update_areas_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Update Areas")
                    )
            );
            LvcOperationCoordinator.scheduleStarted(captureWorld, task, "LVC Update Areas");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.UPDATE_AREAS, "litematica.error.lvc_project.update_areas_failed", e);
        }
    }

    private static void handleCommitResult(GuiLvcProjectController controller, RevCommit commit)
    {
        if (commit == null)
        {
            controller.focusTrackingOverlay();
            controller.gui.initGui();
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.nothing_to_commit");
            return;
        }

        controller.loadTrackingOverlay();
        controller.gui.focusHistoryCommitAfterNextRefresh(commit.getName());
        controller.gui.initGui();
        String shortCommitId = shortCommitId(commit);
        LvcDiagnostics.debug("LVC Save Version completed commit={}", shortCommitId);
        LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.committed", shortCommitId);
    }

    private static String shortCommitId(RevCommit commit)
    {
        String commitId = commit.getName();
        return commitId.substring(0, Math.min(8, commitId.length()));
    }

    private static void reportScanResult(GuiLvcProjectController controller, LvcProjectService.SemanticScanResult result)
    {
        if (result.unknownChunks() > 0)
        {
            controller.gui.trackingStatus = StringUtils.translate(
                    "litematica.gui.label.lvc_project.semantic_scan_unknown",
                    result.changedChunks(),
                    result.addedChunks(),
                    result.removedChunks(),
                    result.unknownChunks()
            );
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.scan_unknown",
                    result.unknownChunks(), result.dirtyChunks());
        }
        else if (result.clean())
        {
            controller.gui.trackingStatus = StringUtils.translate(
                    "litematica.gui.label.lvc_project.semantic_scan_clean", result.unchangedChunks());
            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.scan_clean",
                    result.unchangedChunks());
        }
        else
        {
            controller.gui.trackingStatus = StringUtils.translate(
                    "litematica.gui.label.lvc_project.semantic_scan_dirty",
                    result.changedChunks(),
                    result.addedChunks(),
                    result.removedChunks()
            );
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.scan_dirty",
                    result.changedChunks(), result.addedChunks(), result.removedChunks());
        }
    }
}
