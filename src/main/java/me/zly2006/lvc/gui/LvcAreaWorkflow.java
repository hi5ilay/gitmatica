package me.zly2006.lvc.gui;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcFriendlyErrors.Operation;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.task.LvcOperationHandle;
import me.zly2006.lvc.task.LvcSemanticClearTask;
import me.zly2006.lvc.task.LvcSemanticDiscardTask;
import me.zly2006.lvc.task.LvcSemanticScanTask;
import me.zly2006.lvc.task.LvcTaskCallbacks;
import me.zly2006.lvc.task.LvcTaskRegistry;
import me.zly2006.lvc.task.LvcTaskScheduling;
import me.zly2006.lvc.world.LvcWorldAccess;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.StringUtils;

final class LvcAreaWorkflow
{
    private LvcAreaWorkflow()
    {
    }

    static void promptClearArea(GuiLvcProjectController controller)
    {
        Minecraft minecraft = Minecraft.getInstance();
        Level world = minecraft.level;

        if (world == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Clear Area");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(world);
            LvcSemanticScanTask task = new LvcSemanticScanTask(
                    handle.get(),
                    controller.gui.repositoryDirectory,
                    restoreWorld,
                    LvcTaskCallbacks.of(
                            result -> handleClearPreflight(controller, handle.get(), restoreWorld, result),
                            e -> LvcGuiMessages.showTaskError(Operation.CLEAR_AREA, "litematica.error.lvc_project.clear_area_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Clear Area")
                    ),
                    false
            );
            LvcOperationCoordinator.scheduleStarted(restoreWorld, task, "LVC Clear Area");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.CLEAR_AREA, "litematica.error.lvc_project.clear_area_failed", e);
        }
    }

    static void discardChanges(GuiLvcProjectController controller)
    {
        Minecraft minecraft = Minecraft.getInstance();
        Level world = minecraft.level;

        if (world == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        try
        {
            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(world);
            openDiscardConfirm(controller, () -> scheduleDiscard(controller, restoreWorld));
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.DISCARD_CHANGES, "litematica.error.lvc_project.discard_failed", e);
        }
    }

    static void scheduleClear(GuiLvcProjectController controller, LvcOperationHandle handle, Level restoreWorld)
    {
        LvcSemanticClearTask task = new LvcSemanticClearTask(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                LvcTaskCallbacks.of(
                        result ->
                        {
                            controller.refreshTrackingOverlayAfterWorldMutation();
                            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.clear_area_cleared",
                                    result.clearedBlocks(), LvcOperationCoordinator.regionCountText(result.regionCount()));
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.CLEAR_AREA, "litematica.error.lvc_project.clear_area_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Clear Area")
                )
        );
        LvcTaskScheduling.scheduleForWorld(restoreWorld, task);
    }

    private static void handleClearPreflight(GuiLvcProjectController controller, LvcOperationHandle handle,
                                             Level restoreWorld, LvcProjectService.SemanticScanResult scan)
    {
        if (scan.unknownChunks() > 0)
        {
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.showUnloadedTrackedChunks(Operation.CLEAR_AREA,
                    "litematica.error.lvc_project.clear_area_failed", scan.unknownChunks());
            return;
        }

        if (scan.clean())
        {
            scheduleClear(controller, handle, restoreWorld);
            return;
        }

        controller.gui.trackingStatus = StringUtils.translate(
                "litematica.gui.label.lvc_project.semantic_scan_dirty",
                scan.changedChunks(),
                scan.addedChunks(),
                scan.removedChunks()
        );
        logClearPreflightDirty(controller, scan);
        GuiBase.openGui(new GuiLvcConfirmAction(
                340,
                "litematica.gui.title.lvc_project.confirm_clear_area",
                new LvcOperationCoordinator.HeldLockConfirmListener(
                        handle,
                        () -> scheduleClear(controller, handle, restoreWorld)
                ),
                LvcOperationCoordinator.confirmParent(controller),
                "litematica.gui.message.lvc_project.confirm_clear_area",
                scan.changedChunks(),
                scan.addedChunks(),
                scan.removedChunks()
        ));
    }

    private static void logClearPreflightDirty(GuiLvcProjectController controller, LvcProjectService.SemanticScanResult scan)
    {
        LvcDiagnostics.debug("LVC Clear Area preflight dirty repo='{}' changedChunks={} addedChunks={} removedChunks={}",
                controller.gui.repositoryDirectory, scan.changedChunks(), scan.addedChunks(), scan.removedChunks());

        if (scan.samples().isEmpty())
        {
            return;
        }

        for (LvcProjectService.SemanticScanMismatch sample : scan.samples())
        {
            LvcDiagnostics.info("LVC Clear Area preflight mismatch: {}", sample.summary());
        }
    }

    private static void scheduleDiscard(GuiLvcProjectController controller, Level restoreWorld)
    {
        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Discard Changes");

        if (handle.isEmpty())
        {
            return;
        }

        scheduleDiscard(controller, handle.get(), restoreWorld, null);
    }

    private static void scheduleDiscard(GuiLvcProjectController controller, LvcOperationHandle handle,
                                        Level restoreWorld, String targetCommit)
    {
        LvcSemanticDiscardTask task = new LvcSemanticDiscardTask(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                targetCommit,
                LvcTaskCallbacks.of(
                        result ->
                        {
                            controller.refreshTrackingOverlayAfterWorldMutation();

                            if (!result.discarded())
                            {
                                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_changes_to_discard");
                                return;
                            }

                            controller.gui.initGui();

                            if (result.restoredRegionCount() > 0)
                            {
                                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.discarded_changes",
                                        LvcOperationCoordinator.regionCountText(result.restoredRegionCount()));
                            }
                            else
                            {
                                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.discarded_changes_no_regions");
                            }
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.DISCARD_CHANGES, "litematica.error.lvc_project.discard_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Discard Changes")
                )
        );
        LvcOperationCoordinator.scheduleStarted(restoreWorld, task, "LVC Discard Changes");
    }

    private static void openDiscardConfirm(GuiLvcProjectController controller, Runnable confirmed)
    {
        GuiBase.openGui(new GuiLvcConfirmAction(
                300,
                "litematica.gui.title.lvc_project.confirm_discard_changes",
                new LvcOperationCoordinator.ConfirmListener(confirmed),
                controller.gui,
                "litematica.gui.message.lvc_project.confirm_discard_changes"
        ));
    }
}
