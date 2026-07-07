package me.zly2006.lvc.gui;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcFriendlyErrors.Operation;
import me.zly2006.lvc.LvcPlayerIdentity;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.git.LvcMergeConflictException;
import me.zly2006.lvc.task.LvcOperationHandle;
import me.zly2006.lvc.task.LvcOperationJournal;
import me.zly2006.lvc.task.LvcSemanticDiscardTask;
import me.zly2006.lvc.task.LvcSemanticScanTask;
import me.zly2006.lvc.task.LvcTaskCallbacks;
import me.zly2006.lvc.task.LvcTaskRegistry;
import me.zly2006.lvc.task.LvcTaskScheduling;
import me.zly2006.lvc.world.LvcWorldAccess;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;

final class LvcMergeWorkflow
{
    private LvcMergeWorkflow()
    {
    }

    static void mergeBranch(GuiLvcProjectController controller, String sourceBranch)
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        if (sourceBranch == null || sourceBranch.isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_required");
            return;
        }

        if (controller.gui.detachedHead)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_detached");
            return;
        }

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

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Merge Branch");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            Level scanWorld = LvcWorldAccess.resolveSemanticCaptureWorld(world);
            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(world);
            LvcSemanticScanTask task = new LvcSemanticScanTask(
                    handle.get(),
                    controller.gui.repositoryDirectory,
                    scanWorld,
                    LvcTaskCallbacks.of(
                            scan -> handleMergePreflight(controller, handle.get(), restoreWorld,
                                    sourceBranch.trim(), player, scan),
                            e -> LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH,
                                    "litematica.error.lvc_project.merge_branch_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO,
                                    "litematica.message.lvc_project.task_aborted", "LVC Merge Branch")
                    ),
                    false
            );
            LvcOperationCoordinator.scheduleStarted(scanWorld, task, "LVC Merge Branch");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH, "litematica.error.lvc_project.merge_branch_failed", e);
        }
    }

    private static void handleMergePreflight(GuiLvcProjectController controller, LvcOperationHandle handle,
                                             Level restoreWorld, String sourceBranch, Player player,
                                             LvcProjectService.SemanticScanResult scan)
    {
        try
        {
            if (scan.unknownChunks() > 0)
            {
                LvcTaskRegistry.release(handle);
                LvcGuiMessages.showUnloadedTrackedChunks(Operation.MERGE_BRANCH,
                        "litematica.error.lvc_project.merge_branch_failed", scan.unknownChunks());
                return;
            }

            if (!scan.clean() || LvcProjectService.hasUncommittedChanges(controller.gui.repositoryDirectory))
            {
                LvcTaskRegistry.release(handle);
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_dirty");
                return;
            }

            LvcPlayerIdentity identity = new LvcPlayerIdentity(player.getName().getString(), player.getUUID());
            LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(
                    controller.gui.repositoryDirectory, sourceBranch, identity);

            if (result.status() == LvcProjectService.BranchMergeStatus.UP_TO_DATE)
            {
                LvcTaskRegistry.release(handle);
                controller.focusTrackingOverlay();
                controller.refreshRepositoryState();
                controller.gui.refreshHistory();
                controller.gui.initGui();
                LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.merge_branch_up_to_date",
                        result.sourceBranch(), result.targetBranch());
                return;
            }

            scheduleMergeRestore(controller, handle, restoreWorld, result);
        }
        catch (LvcMergeConflictException e)
        {
            openMergeConflictDialog(controller, handle, restoreWorld, sourceBranch, player, e);
            LvcDiagnostics.debug("LVC merge conflict detected repo='{}' source='{}' error='{}'",
                    controller.gui.repositoryDirectory, sourceBranch, e.getMessage());
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH, "litematica.error.lvc_project.merge_branch_failed", e);
            LvcDiagnostics.debug("LVC merge failed repo='{}' source='{}' error='{}'",
                    controller.gui.repositoryDirectory, sourceBranch, e.getMessage());
        }
    }

    private static void openMergeConflictDialog(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                Level restoreWorld, String sourceBranch, Player player,
                                                LvcMergeConflictException conflict)
    {
        LvcDiagnostics.debug("LVC merge conflict requires user choice repo='{}' source='{}' conflict='{}'",
                controller.gui.repositoryDirectory, sourceBranch, conflict.getMessage());
        GuiBase.openGui(new GuiLvcMergeConflictDialog(
                LvcOperationCoordinator.confirmParent(controller),
                resolution -> resolveMergeConflict(controller, handle, restoreWorld, sourceBranch, player, resolution),
                () ->
                {
                    LvcTaskRegistry.release(handle);
                    LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_conflicts");
                }
        ));
    }

    private static void resolveMergeConflict(GuiLvcProjectController controller, LvcOperationHandle handle,
                                             Level restoreWorld, String sourceBranch, Player player,
                                             LvcProjectService.BranchMergeConflictResolution resolution)
    {
        try
        {
            if (LvcProjectService.hasUncommittedChanges(controller.gui.repositoryDirectory))
            {
                LvcTaskRegistry.release(handle);
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_dirty");
                return;
            }

            LvcPlayerIdentity identity = new LvcPlayerIdentity(player.getName().getString(), player.getUUID());
            LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(
                    controller.gui.repositoryDirectory, sourceBranch, identity, resolution);

            if (result.status() == LvcProjectService.BranchMergeStatus.UP_TO_DATE)
            {
                LvcTaskRegistry.release(handle);
                controller.focusTrackingOverlay();
                controller.refreshRepositoryState();
                controller.gui.refreshHistory();
                controller.gui.initGui();
                LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.merge_branch_up_to_date",
                        result.sourceBranch(), result.targetBranch());
                return;
            }

            LvcDiagnostics.debug("LVC merge conflict resolved repo='{}' source='{}' resolution='{}' commit='{}'",
                    controller.gui.repositoryDirectory, sourceBranch, resolution, result.commitId());
            scheduleMergeRestore(controller, handle, restoreWorld, result);
        }
        catch (LvcMergeConflictException e)
        {
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_conflicts");
            LvcDiagnostics.debug("LVC merge conflict resolution failed repo='{}' source='{}' resolution='{}' error='{}'",
                    controller.gui.repositoryDirectory, sourceBranch, resolution, e.getMessage());
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH, "litematica.error.lvc_project.merge_branch_failed", e);
            LvcDiagnostics.debug("LVC merge conflict resolution failed repo='{}' source='{}' resolution='{}' error='{}'",
                    controller.gui.repositoryDirectory, sourceBranch, resolution, e.getMessage());
        }
    }

    private static void scheduleMergeRestore(GuiLvcProjectController controller, LvcOperationHandle handle,
                                             Level restoreWorld, LvcProjectService.BranchMergeResult merge)
    {
        controller.removeTrackingOverlay();
        LvcSemanticDiscardTask task = new LvcSemanticDiscardTask(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                merge.commitId(),
                LvcTaskCallbacks.of(
                        result ->
                        {
                            controller.loadTrackingOverlay();
                            controller.refreshRepositoryState();
                            controller.gui.focusHistoryCommitAfterNextRefresh(merge.commitId());
                            controller.gui.refreshHistory();
                            controller.gui.initGui();
                            LvcGuiMessages.show(MessageType.SUCCESS,
                                    merge.status() == LvcProjectService.BranchMergeStatus.FAST_FORWARD ?
                                            "litematica.message.lvc_project.merge_branch_fast_forward" :
                                            "litematica.message.lvc_project.merge_branch_merged",
                                    merge.sourceBranch(), merge.targetBranch(),
                                    LvcOperationCoordinator.regionCountText(result.restoredRegionCount()));
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH,
                                "litematica.error.lvc_project.merge_branch_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO,
                                "litematica.message.lvc_project.task_aborted", "LVC Merge Branch")
                ),
                "LVC Merge Branch",
                LvcOperationJournal.Operation.MERGE
        );
        LvcTaskScheduling.scheduleForWorld(restoreWorld, task);
    }

}
