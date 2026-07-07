package me.zly2006.lvc.gui;

import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcFriendlyErrors.Operation;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.task.LvcOperationHandle;
import me.zly2006.lvc.task.LvcSemanticCheckoutTask;
import me.zly2006.lvc.task.LvcTaskCallbacks;
import me.zly2006.lvc.task.LvcTaskRegistry;
import me.zly2006.lvc.task.LvcTaskScheduling;
import me.zly2006.lvc.world.LvcWorldAccess;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;

final class LvcCheckoutWorkflow
{
    private LvcCheckoutWorkflow()
    {
    }

    static void promptCheckoutCommit(GuiLvcProjectController controller, LvcProjectService.CommitInfo commit)
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Checkout");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            if (LvcProjectService.headMatchesCommit(controller.gui.repositoryDirectory, commit.id()))
            {
                LvcTaskRegistry.release(handle.get());
                LvcGuiMessages.show(MessageType.ERROR, "litematica.message.lvc_project.already_at_version", commit.shortId());
                controller.refreshRepositoryState();
                return;
            }

            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(minecraft.level);
            scheduleCheckoutPreflight(controller, handle.get(), restoreWorld, CheckoutTarget.commit(commit));
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.CHECKOUT, "litematica.error.lvc_project.checkout_failed", e);
        }
    }

    static void checkoutCommit(GuiLvcProjectController controller, LvcProjectService.CommitInfo commit)
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Checkout");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(minecraft.level);
            scheduleCheckoutPreflight(controller, handle.get(), restoreWorld, CheckoutTarget.commit(commit));
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.CHECKOUT, "litematica.error.lvc_project.checkout_failed", e);
        }
    }

    static void promptCheckoutBranch(GuiLvcProjectController controller, String branchName)
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            controller.gui.syncBranchDropdownSelection();
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        if (branchName == null || branchName.isBlank())
        {
            controller.gui.syncBranchDropdownSelection();
            return;
        }

        String trimmedBranchName = branchName.trim();

        try
        {
            String targetCommitId = LvcProjectService.localBranchTipCommitId(controller.gui.repositoryDirectory, trimmedBranchName);

            if (LvcProjectService.headMatchesCommit(controller.gui.repositoryDirectory, targetCommitId))
            {
                checkoutBranchWithoutRestore(controller, trimmedBranchName);
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft.level == null)
            {
                controller.gui.syncBranchDropdownSelection();
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
                return;
            }

            Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Checkout Branch");

            if (handle.isEmpty())
            {
                controller.gui.syncBranchDropdownSelection();
                return;
            }

            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(minecraft.level);
            scheduleCheckoutPreflight(controller, handle.get(), restoreWorld, CheckoutTarget.branch(trimmedBranchName, targetCommitId));
        }
        catch (Exception e)
        {
            controller.gui.syncBranchDropdownSelection();
            LvcGuiMessages.showTaskError(Operation.CHECKOUT_BRANCH, "litematica.error.lvc_project.checkout_failed", e);
            LvcDiagnostics.debug("LVC branch checkout failed before preflight branch='{}' error='{}'",
                    trimmedBranchName, e.getMessage());
        }
    }

    private static void checkoutBranchWithoutRestore(GuiLvcProjectController controller, String branchName)
    {
        try
        {
            LvcProjectService.checkoutBranchToWorkingTree(controller.gui.repositoryDirectory, branchName);
            controller.focusTrackingOverlay();
            controller.refreshRepositoryState();
            controller.gui.refreshHistory();
            controller.gui.initGui();
            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.switched_branch", branchName);
            LvcDiagnostics.debug("LVC branch checkout completed without restore repo='{}' branch='{}'",
                    controller.gui.repositoryDirectory, branchName);
        }
        catch (Exception e)
        {
            controller.gui.syncBranchDropdownSelection();
            LvcGuiMessages.showTaskError(Operation.CHECKOUT_BRANCH, "litematica.error.lvc_project.checkout_failed", e);
            LvcDiagnostics.debug("LVC branch checkout without restore failed repo='{}' branch='{}' error='{}'",
                    controller.gui.repositoryDirectory, branchName, e.getMessage());
        }
    }

    private static void scheduleCheckoutPreflight(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                  Level restoreWorld, CheckoutTarget target)
    {
        LvcSemanticCheckoutTask.Preflight task = new LvcSemanticCheckoutTask.Preflight(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                target.commitId(),
                LvcTaskCallbacks.of(
                        result -> handleCheckoutPreflight(controller, handle, target, result),
                        e ->
                        {
                            target.syncBranchDropdownIfNeeded(controller);
                            LvcGuiMessages.showTaskError(target.operation(), "litematica.error.lvc_project.checkout_failed", e);
                        },
                        () ->
                        {
                            target.syncBranchDropdownIfNeeded(controller);
                            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", target.taskName());
                        }
                )
        );
        LvcOperationCoordinator.scheduleStarted(restoreWorld, task, target.taskName());
    }

    private static void handleCheckoutPreflight(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                CheckoutTarget target,
                                                LvcSemanticCheckoutTask.PreflightResult result)
    {
        LvcDiagnostics.debug("LVC Checkout preflight result commit={} branch='{}' gitDirty={} currentChangedBlocks={} currentChangedChunks={} targetChangedBlocks={} targetChangedChunks={}",
                target.commitId(), target.branchName() == null ? "<none>" : target.branchName(),
                result.gitChanges(), result.currentDiff().changedBlocks(), result.currentDiff().changedChunks(),
                result.targetDiff().changedBlocks(), result.targetDiff().changedChunks());

        if (result.hasUncommittedChanges())
        {
            openPreparedCheckoutConfirm(controller, handle, result.prepared(), target, result.gitChanges());
            return;
        }

        schedulePreparedCheckout(controller, handle, result.prepared(), target);
    }

    private static void schedulePreparedCheckout(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                 LvcSemanticCheckoutTask.PreparedCheckout prepared,
                                                 CheckoutTarget target)
    {
        try
        {
            controller.removeTrackingOverlay();
            LvcSemanticCheckoutTask.Apply task = new LvcSemanticCheckoutTask.Apply(
                    handle,
                    prepared,
                    target.branchName(),
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                controller.loadTrackingOverlay();
                                if (!target.reattachIfNeeded(controller))
                                {
                                    return;
                                }

                                controller.gui.initGui();
                                target.showSuccess(result.regionCount());
                            },
                            e ->
                            {
                                target.syncBranchDropdownIfNeeded(controller);
                                LvcGuiMessages.showTaskError(target.operation(), "litematica.error.lvc_project.checkout_failed", e, true);
                            },
                            () ->
                            {
                                target.syncBranchDropdownIfNeeded(controller);
                                LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", target.taskName());
                            }
                    )
            );
            LvcTaskScheduling.scheduleForWorld(prepared.world(), task);
        }
        catch (Exception e)
        {
            prepared.close();
            LvcTaskRegistry.release(handle);
            target.syncBranchDropdownIfNeeded(controller);
            LvcGuiMessages.showTaskError(target.operation(), "litematica.error.lvc_project.checkout_failed", e);
        }
    }

    private static void openPreparedCheckoutConfirm(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                    LvcSemanticCheckoutTask.PreparedCheckout prepared,
                                                    CheckoutTarget target, boolean resetFirst)
    {
        GuiBase.openGui(new GuiLvcConfirmAction(
                340,
                target.confirmTitleKey(resetFirst),
                new LvcOperationCoordinator.HeldPreparedCheckoutConfirmListener(
                        handle,
                        prepared,
                        () -> schedulePreparedCheckout(controller, handle, prepared, target),
                        () -> target.syncBranchDropdownIfNeeded(controller)
                ),
                LvcOperationCoordinator.confirmParent(controller),
                target.confirmMessageKey(resetFirst),
                target.displayName()
        ));
    }

    private record CheckoutTarget(String commitId, String displayName, @Nullable String branchName, boolean branchSwitch)
    {
        private static CheckoutTarget commit(LvcProjectService.CommitInfo commit)
        {
            return new CheckoutTarget(commit.id(), commit.shortId(), null, false);
        }

        private static CheckoutTarget branch(String branchName, String commitId)
        {
            return new CheckoutTarget(commitId, branchName, branchName, true);
        }

        private String taskName()
        {
            return this.branchSwitch ? "LVC Checkout Branch" : "LVC Checkout";
        }

        private Operation operation()
        {
            return this.branchSwitch ? Operation.CHECKOUT_BRANCH : Operation.CHECKOUT;
        }

        private String confirmTitleKey(boolean resetFirst)
        {
            if (this.branchSwitch)
            {
                return "litematica.gui.title.lvc_project.confirm_switch_branch";
            }

            return resetFirst ? "litematica.gui.title.lvc_project.confirm_reset_checkout" :
                    "litematica.gui.title.lvc_project.confirm_checkout";
        }

        private String confirmMessageKey(boolean resetFirst)
        {
            if (this.branchSwitch)
            {
                return "litematica.gui.message.lvc_project.confirm_switch_branch_dirty";
            }

            return resetFirst ? "litematica.gui.message.lvc_project.confirm_reset_checkout" :
                    "litematica.gui.message.lvc_project.confirm_checkout_semantic";
        }

        private boolean reattachIfNeeded(GuiLvcProjectController controller)
        {
            if (this.branchSwitch)
            {
                return true;
            }

            try
            {
                LvcProjectService.reattachHeadToBranchIfAtTip(controller.gui.repositoryDirectory,
                        controller.gui.checkoutBranchName);
                return true;
            }
            catch (Exception e)
            {
                LvcGuiMessages.showTaskError(this.operation(), "litematica.error.lvc_project.checkout_failed", e);
                return false;
            }
        }

        private void showSuccess(int regionCount)
        {
            if (this.branchSwitch)
            {
                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.checked_out_branch",
                        this.displayName, LvcOperationCoordinator.regionCountText(regionCount));
                return;
            }

            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.checked_out",
                    this.displayName, LvcOperationCoordinator.regionCountText(regionCount));
        }

        private void syncBranchDropdownIfNeeded(GuiLvcProjectController controller)
        {
            if (this.branchSwitch)
            {
                controller.gui.syncBranchDropdownSelection();
            }
        }
    }
}
