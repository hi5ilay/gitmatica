package me.zly2006.lvc.gui;

import java.util.Optional;
import java.nio.file.Path;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcFriendlyErrors.Operation;
import org.eclipse.jgit.lib.ObjectId;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.task.LvcOperationHandle;
import me.zly2006.lvc.task.LvcOperationJournal;
import me.zly2006.lvc.task.LvcSemanticCheckoutTask;
import me.zly2006.lvc.task.LvcSemanticDiscardTask;
import me.zly2006.lvc.task.LvcTaskCallbacks;
import me.zly2006.lvc.task.LvcTaskRegistry;
import me.zly2006.lvc.task.LvcTaskScheduling;
import me.zly2006.lvc.world.LvcWorldAccess;
import me.zly2006.lvc.storage.LvcChunkStagingStore;
import me.zly2006.lvc.storage.LvcRepository;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.StringUtils;

final class GuiLvcProjectTaskActions
{
    private GuiLvcProjectTaskActions()
    {
    }

    static void scanChanges(GuiLvcProjectController controller)
    {
        LvcVersionWorkflow.scanChanges(controller);
    }

    static void mergeBranch(GuiLvcProjectController controller, String sourceBranch)
    {
        LvcMergeWorkflow.mergeBranch(controller, sourceBranch);
    }

    static boolean recoverInterruptedOperation(GuiLvcProjectController controller)
    {
        try
        {
            LvcOperationJournal.Entry entry = LvcOperationJournal.read(controller.gui.repositoryDirectory);

            if (entry == null)
            {
                return false;
            }

            LvcOperationJournal.Operation operation = LvcOperationJournal.Operation.valueOf(entry.operation());
            LvcDiagnostics.debug("LVC recovery journal detected repo='{}' operation='{}' phase='{}' targetCommit='{}' targetBranch='{}' startedAt='{}'",
                    controller.gui.repositoryDirectory, entry.operation(), entry.phase(), entry.targetCommit(), entry.targetBranch(), entry.startedAt());

            if (isWorldMutatingRecoveryOperation(operation))
            {
                controller.setPendingInterruptedOperation(entry, operation);
                return false;
            }

            cleanupInterruptedOperationData(controller.gui.repositoryDirectory);
            openInterruptedOperationCancelledDialog(controller, operation);
            LvcDiagnostics.debug("LVC interrupted non-world operation cancelled repo='{}' operation='{}' phase='{}'",
                    controller.gui.repositoryDirectory, entry.operation(), entry.phase());
            return false;
        }
        catch (LvcOperationJournal.CorruptJournalException e)
        {
            try
            {
                LvcOperationJournal.quarantineCorruptJournals(controller.gui.repositoryDirectory, e.corruptPaths());
            }
            catch (Exception quarantineFailure)
            {
                LvcDiagnostics.warn("Failed to quarantine corrupt LVC operation journal repo='{}' error='{}'",
                        controller.gui.repositoryDirectory, quarantineFailure.getMessage());
            }

            openRecoveryDataUnreadableDialog(controller);
            LvcDiagnostics.warn("Corrupt LVC operation journal quarantined repo='{}' paths={}",
                    controller.gui.repositoryDirectory, e.corruptPaths());
            return false;
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e);
            return true;
        }
    }

    static void promptPendingInterruptedOperation(GuiLvcProjectController controller, LvcOperationJournal.Entry entry,
                                                  LvcOperationJournal.Operation operation)
    {
        GuiBase.openGui(new GuiLvcRecoveryDialog(
                LvcOperationCoordinator.confirmParent(controller),
                entry,
                operation,
                () -> restartInterruptedOperation(controller, entry, operation),
                () -> abortInterruptedOperation(controller, entry, operation),
                () -> cancelInterruptedOperationPrompt(controller, entry, operation)
        ));
    }

    private static boolean isWorldMutatingRecoveryOperation(LvcOperationJournal.Operation operation)
    {
        return operation == LvcOperationJournal.Operation.CHECKOUT ||
                operation == LvcOperationJournal.Operation.DISCARD ||
                operation == LvcOperationJournal.Operation.CLEAR ||
                operation == LvcOperationJournal.Operation.MERGE ||
                operation == LvcOperationJournal.Operation.DELETE_VERSION;
    }

    private static void restartInterruptedOperation(GuiLvcProjectController controller, LvcOperationJournal.Entry entry,
                                                    LvcOperationJournal.Operation operation)
    {
        LvcDiagnostics.debug("LVC interrupted operation restart selected repo='{}' operation='{}' phase='{}' targetCommit='{}' targetBranch='{}' previousHead='{}' previousBranch='{}'",
                controller.gui.repositoryDirectory, entry.operation(), entry.phase(), entry.targetCommit(), entry.targetBranch(),
                entry.previousHead(), entry.previousBranch());
        Minecraft minecraft = Minecraft.getInstance();

        if (operation == LvcOperationJournal.Operation.MERGE && restartInterruptedMergeGitIfNeeded(controller, entry))
        {
            return;
        }

        if ((operation == LvcOperationJournal.Operation.CHECKOUT ||
                operation == LvcOperationJournal.Operation.DELETE_VERSION) &&
                (entry.targetCommit() == null || entry.targetCommit().isBlank()))
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_failed", "Missing recovery target commit");
            return;
        }

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_requires_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Recovery");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(minecraft.level);
            String mergeRestoreCommit = operation == LvcOperationJournal.Operation.MERGE ?
                    mergeRecoveryRestoreCommit(controller, entry) : entry.targetCommit();
            cleanupInterruptedOperationData(controller.gui.repositoryDirectory);
            controller.clearPendingInterruptedOperation();

            if (operation == LvcOperationJournal.Operation.CLEAR)
            {
                LvcOperationJournal.write(controller.gui.repositoryDirectory, LvcOperationJournal.Operation.CLEAR, null, "clear");
                LvcAreaWorkflow.scheduleClear(controller, handle.get(), restoreWorld);
            }
            else if (operation == LvcOperationJournal.Operation.CHECKOUT)
            {
                String targetBranch = recoveryBranchForCommit(controller.gui.repositoryDirectory, entry.targetCommit(), entry.targetBranch());
                LvcOperationJournal.writeCheckout(controller.gui.repositoryDirectory, entry.targetCommit(), targetBranch,
                        entry.previousHead(), entry.previousBranch(), "restore");
                scheduleRecoveryCheckout(controller, handle.get(), restoreWorld, entry.targetCommit(), targetBranch,
                        entry.previousHead(), entry.previousBranch(), "litematica.message.lvc_project.recovery_completed");
            }
            else if (operation == LvcOperationJournal.Operation.DELETE_VERSION)
            {
                if (entry.targetBranch() == null || entry.targetBranch().isBlank())
                {
                    throw new IllegalStateException("Missing delete-version target branch");
                }

                LvcOperationJournal.writeDeleteVersion(controller.gui.repositoryDirectory, entry.targetCommit(), entry.targetBranch(),
                        entry.previousHead(), entry.previousBranch(), "restore");
                scheduleRecoveryCheckout(controller, handle.get(), restoreWorld, entry.targetCommit(), entry.targetBranch(),
                        entry.previousHead(), entry.previousBranch(), true, LvcOperationJournal.Operation.DELETE_VERSION,
                        "LVC Delete Version", "litematica.message.lvc_project.recovery_completed");
            }
            else if (operation == LvcOperationJournal.Operation.DISCARD)
            {
                LvcOperationJournal.write(controller.gui.repositoryDirectory, LvcOperationJournal.Operation.DISCARD,
                        entry.targetCommit(), "discard");
                scheduleRecoveryDiscard(controller, handle.get(), restoreWorld, entry.targetCommit());
            }
            else if (operation == LvcOperationJournal.Operation.MERGE)
            {
                boolean rollbackMerge = isMergeRollbackJournal(entry, mergeRestoreCommit);

                LvcOperationJournal.write(controller.gui.repositoryDirectory, LvcOperationJournal.Operation.MERGE,
                        mergeRestoreCommit, entry.targetBranch(), entry.sourceBranch(), entry.previousHead(), "restore");

                if (rollbackMerge)
                {
                    resetMergeTargetBranchForRollback(controller.gui.repositoryDirectory, entry.targetBranch(), mergeRestoreCommit);
                }

                scheduleRecoveryMergeRestore(controller, handle.get(), restoreWorld, mergeRestoreCommit,
                        entry.targetBranch(), entry.sourceBranch(), entry.previousHead(),
                        "litematica.message.lvc_project.recovery_completed");
            }
            else
            {
                LvcTaskRegistry.release(handle.get());
                return;
            }

            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.recovery_started",
                    GuiLvcRecoveryDialog.displayOperation(operation));
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e);
        }
    }

    private static void abortInterruptedOperation(GuiLvcProjectController controller, LvcOperationJournal.Entry entry,
                                                  LvcOperationJournal.Operation operation)
    {
        if (operation == LvcOperationJournal.Operation.CHECKOUT)
        {
            abortInterruptedCheckout(controller, entry, operation);
            return;
        }

        if (operation == LvcOperationJournal.Operation.MERGE)
        {
            abortInterruptedMerge(controller, entry, operation);
            return;
        }

        if (operation == LvcOperationJournal.Operation.DELETE_VERSION)
        {
            abortInterruptedDeleteVersion(controller, entry, operation);
            return;
        }

        try
        {
            LvcDiagnostics.debug("LVC interrupted operation abort selected repo='{}' operation='{}' phase='{}' targetCommit='{}' targetBranch='{}' previousHead='{}' previousBranch='{}'",
                    controller.gui.repositoryDirectory, entry.operation(), entry.phase(), entry.targetCommit(), entry.targetBranch(),
                    entry.previousHead(), entry.previousBranch());
            cleanupInterruptedMergeGitState(controller.gui.repositoryDirectory, entry);
            cleanupInterruptedOperationData(controller.gui.repositoryDirectory);
            controller.clearPendingInterruptedOperation();
            controller.focusTrackingOverlay();
            openInterruptedRestorelessOperationAbortedDialog(controller, operation);
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e);
        }
    }

    private static void abortInterruptedCheckout(GuiLvcProjectController controller, LvcOperationJournal.Entry entry,
                                                 LvcOperationJournal.Operation operation)
    {
        LvcDiagnostics.debug("LVC interrupted checkout abort selected repo='{}' phase='{}' targetCommit='{}' targetBranch='{}' previousHead='{}' previousBranch='{}'",
                controller.gui.repositoryDirectory, entry.phase(), entry.targetCommit(), entry.targetBranch(),
                entry.previousHead(), entry.previousBranch());

        if (entry.previousHead() == null || entry.previousHead().isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_failed",
                    "Missing checkout rollback version; restart recovery to finish checkout");
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_requires_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Recovery");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(minecraft.level);
            String rollbackCommit = entry.previousHead();
            String rollbackBranch = recoveryBranchForCommit(controller.gui.repositoryDirectory, rollbackCommit, entry.previousBranch());
            cleanupInterruptedOperationData(controller.gui.repositoryDirectory);
            controller.clearPendingInterruptedOperation();

            LvcOperationJournal.writeCheckout(controller.gui.repositoryDirectory, rollbackCommit, rollbackBranch,
                    rollbackCommit, rollbackBranch, "restore");
            scheduleRecoveryCheckout(controller, handle.get(), restoreWorld, rollbackCommit, rollbackBranch,
                    rollbackCommit, rollbackBranch, "litematica.message.lvc_project.recovery_aborted",
                    GuiLvcRecoveryDialog.displayOperation(operation));
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_started", "LVC Recovery");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e);
        }
    }

    private static void abortInterruptedDeleteVersion(GuiLvcProjectController controller, LvcOperationJournal.Entry entry,
                                                      LvcOperationJournal.Operation operation)
    {
        LvcDiagnostics.debug("LVC interrupted delete-version abort selected repo='{}' phase='{}' targetCommit='{}' targetBranch='{}' previousHead='{}' previousBranch='{}'",
                controller.gui.repositoryDirectory, entry.phase(), entry.targetCommit(), entry.targetBranch(),
                entry.previousHead(), entry.previousBranch());

        if (entry.previousHead() == null || entry.previousHead().isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_failed",
                    "Missing delete-version rollback version; restart recovery to finish deleting the version");
            return;
        }

        if (entry.previousBranch() == null || entry.previousBranch().isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_failed",
                    "Missing delete-version rollback branch; restart recovery to finish deleting the version");
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_requires_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Recovery");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(minecraft.level);
            String rollbackCommit = entry.previousHead().trim();
            String rollbackBranch = entry.previousBranch().trim();
            cleanupInterruptedOperationData(controller.gui.repositoryDirectory);
            controller.clearPendingInterruptedOperation();

            LvcOperationJournal.writeDeleteVersion(controller.gui.repositoryDirectory, rollbackCommit, rollbackBranch,
                    rollbackCommit, rollbackBranch, "restore");
            scheduleRecoveryCheckout(controller, handle.get(), restoreWorld, rollbackCommit, rollbackBranch,
                    rollbackCommit, rollbackBranch, true, LvcOperationJournal.Operation.DELETE_VERSION,
                    "LVC Delete Version", "litematica.message.lvc_project.recovery_aborted",
                    GuiLvcRecoveryDialog.displayOperation(operation));
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_started", "LVC Recovery");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e);
        }
    }

    private static void abortInterruptedMerge(GuiLvcProjectController controller, LvcOperationJournal.Entry entry,
                                              LvcOperationJournal.Operation operation)
    {
        LvcDiagnostics.debug("LVC interrupted merge abort selected repo='{}' phase='{}' targetCommit='{}' targetBranch='{}' sourceBranch='{}' previousHead='{}'",
                controller.gui.repositoryDirectory, entry.phase(), entry.targetCommit(), entry.targetBranch(),
                entry.sourceBranch(), entry.previousHead());

        try
        {
            if (LvcOperationJournal.PHASE_MERGE_GIT.equals(entry.phase()) && mergeHeadStillAtPreviousHead(controller.gui.repositoryDirectory, entry))
            {
                cleanupInterruptedMergeGitState(controller.gui.repositoryDirectory, entry);
                cleanupInterruptedOperationData(controller.gui.repositoryDirectory);
                controller.clearPendingInterruptedOperation();
                controller.focusTrackingOverlay();
                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.recovery_aborted",
                        GuiLvcRecoveryDialog.displayOperation(operation));
                return;
            }
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e);
            return;
        }

        if (entry.previousHead() == null || entry.previousHead().isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_failed",
                    "Missing merge rollback version; restart recovery to finish merge");
            return;
        }

        if (entry.targetBranch() == null || entry.targetBranch().isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_failed",
                    "Missing merge target branch; restart recovery to finish merge");
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_requires_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Recovery");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(minecraft.level);
            String rollbackCommit = entry.previousHead().trim();
            String targetBranch = entry.targetBranch().trim();
            String sourceBranch = entry.sourceBranch() == null || entry.sourceBranch().isBlank() ? null : entry.sourceBranch().trim();
            cleanupInterruptedOperationData(controller.gui.repositoryDirectory);
            controller.clearPendingInterruptedOperation();

            LvcOperationJournal.write(controller.gui.repositoryDirectory, LvcOperationJournal.Operation.MERGE,
                    rollbackCommit, targetBranch, sourceBranch, rollbackCommit, "restore");
            resetMergeTargetBranchForRollback(controller.gui.repositoryDirectory, targetBranch, rollbackCommit);
            scheduleRecoveryMergeRestore(controller, handle.get(), restoreWorld, rollbackCommit,
                    targetBranch, sourceBranch, rollbackCommit, "litematica.message.lvc_project.recovery_aborted",
                    GuiLvcRecoveryDialog.displayOperation(operation));
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_started", "LVC Recovery");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e);
        }
    }

    private static void cancelInterruptedOperationPrompt(GuiLvcProjectController controller, LvcOperationJournal.Entry entry,
                                                        LvcOperationJournal.Operation operation)
    {
        LvcDiagnostics.debug("LVC interrupted operation prompt cancelled repo='{}' operation='{}' phase='{}' targetCommit='{}' targetBranch='{}'",
                controller.gui.repositoryDirectory, entry.operation(), entry.phase(), entry.targetCommit(), entry.targetBranch());
    }

    private static void cleanupInterruptedOperationData(Path repositoryDirectory) throws Exception
    {
        LvcChunkStagingStore.deleteRecursivelyIfExists(
                LvcOperationJournal.gitLocalDirectory(repositoryDirectory).resolve(LvcOperationJournal.STAGING_DIRECTORY)
        );
        LvcOperationJournal.delete(repositoryDirectory);
    }

    private static boolean restartInterruptedMergeGitIfNeeded(GuiLvcProjectController controller,
                                                              LvcOperationJournal.Entry entry)
    {
        if (!LvcOperationJournal.PHASE_MERGE_GIT.equals(entry.phase()))
        {
            return false;
        }

        try
        {
            if (!mergeHeadStillAtPreviousHead(controller.gui.repositoryDirectory, entry))
            {
                return false;
            }

            if (entry.sourceBranch() == null || entry.sourceBranch().isBlank())
            {
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_failed", "Missing merge source branch");
                return true;
            }

            cleanupInterruptedMergeGitState(controller.gui.repositoryDirectory, entry);
            cleanupInterruptedOperationData(controller.gui.repositoryDirectory);
            controller.clearPendingInterruptedOperation();
            LvcMergeWorkflow.mergeBranch(controller, entry.sourceBranch());
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.recovery_started",
                    GuiLvcRecoveryDialog.displayOperation(LvcOperationJournal.Operation.MERGE));
            return true;
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e);
            return true;
        }
    }

    private static String mergeRecoveryRestoreCommit(GuiLvcProjectController controller, LvcOperationJournal.Entry entry) throws Exception
    {
        if (entry.targetCommit() != null && !entry.targetCommit().isBlank())
        {
            return entry.targetCommit();
        }

        ObjectId head = LvcRepository.resolveHead(controller.gui.repositoryDirectory);

        if (head == null)
        {
            throw new IllegalStateException("Missing merge recovery target commit");
        }

        if (mergeHeadStillAtPreviousHead(controller.gui.repositoryDirectory, entry))
        {
            throw new IllegalStateException("Merge did not finish moving HEAD; restart the interrupted merge instead");
        }

        return head.name();
    }

    @Nullable
    private static String recoveryBranchForCommit(Path repositoryDirectory, @Nullable String commitId, @Nullable String branchName)
    {
        if (commitId == null || commitId.isBlank() || branchName == null || branchName.isBlank())
        {
            return null;
        }

        String normalizedBranchName = branchName.trim();

        try
        {
            String branchTip = LvcProjectService.localBranchTipCommitId(repositoryDirectory, normalizedBranchName);

            if (commitIdsMatch(branchTip, commitId.trim()))
            {
                return normalizedBranchName;
            }

            LvcDiagnostics.warn("LVC recovery ignored branch because tip differs from journal commit repo='{}' branch='{}' branchTip='{}' journalCommit='{}'",
                    repositoryDirectory, normalizedBranchName, branchTip, commitId);
        }
        catch (Exception e)
        {
            LvcDiagnostics.warn("LVC recovery ignored unavailable branch repo='{}' branch='{}' commit='{}' error='{}'",
                    repositoryDirectory, normalizedBranchName, commitId, e.getMessage());
        }

        return null;
    }

    private static boolean isMergeRollbackJournal(LvcOperationJournal.Entry entry, String restoreCommit)
    {
        return entry.previousHead() != null &&
                !entry.previousHead().isBlank() &&
                commitIdsMatch(restoreCommit, entry.previousHead().trim());
    }

    private static void resetMergeTargetBranchForRollback(Path repositoryDirectory, @Nullable String targetBranch,
                                                          String rollbackCommit) throws Exception
    {
        if (targetBranch == null || targetBranch.isBlank())
        {
            throw new IllegalStateException("Missing merge target branch; restart recovery to finish merge");
        }

        LvcProjectService.checkoutBranchAndResetToCommit(repositoryDirectory, targetBranch.trim(), rollbackCommit);
    }

    private static boolean commitIdsMatch(String left, String right)
    {
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    private static void cleanupInterruptedMergeGitState(Path repositoryDirectory, LvcOperationJournal.Entry entry) throws Exception
    {
        if (!LvcOperationJournal.PHASE_MERGE_GIT.equals(entry.phase()) || !mergeHeadStillAtPreviousHead(repositoryDirectory, entry))
        {
            return;
        }

        if (LvcProjectService.hasUncommittedChanges(repositoryDirectory))
        {
            LvcProjectService.resetWorkingTreeToHead(repositoryDirectory);
            LvcDiagnostics.debug("LVC interrupted merge git state reset repo='{}' previousHead='{}'",
                    repositoryDirectory, entry.previousHead());
        }
    }

    private static boolean mergeHeadStillAtPreviousHead(Path repositoryDirectory, LvcOperationJournal.Entry entry) throws Exception
    {
        if (entry.previousHead() == null || entry.previousHead().isBlank())
        {
            return false;
        }

        ObjectId head = LvcRepository.resolveHead(repositoryDirectory);
        return head != null && entry.previousHead().equals(head.name());
    }

    private static void openInterruptedOperationCancelledDialog(GuiLvcProjectController controller,
                                                                LvcOperationJournal.Operation operation)
    {
        GuiBase.openGui(new GuiLvcNoticeDialog(
                LvcOperationCoordinator.confirmParent(controller),
                "litematica.gui.title.lvc_project.operation_cancelled",
                "litematica.gui.message.lvc_project.interrupted_operation_cancelled",
                0xFFFF5555,
                GuiLvcRecoveryDialog.displayOperation(operation)
        ));
    }

    private static void openInterruptedRestorelessOperationAbortedDialog(GuiLvcProjectController controller,
                                                                         LvcOperationJournal.Operation operation)
    {
        GuiBase.openGui(new GuiLvcNoticeDialog(
                LvcOperationCoordinator.confirmParent(controller),
                "litematica.gui.title.lvc_project.operation_cancelled",
                "litematica.gui.message.lvc_project.recovery_aborted_no_world_changes",
                0xFFFFAA00,
                GuiLvcRecoveryDialog.displayOperation(operation),
                GuiLvcRecoveryDialog.displayOperation(operation),
                shortOperationName(operation)
        ));
    }

    private static String shortOperationName(LvcOperationJournal.Operation operation)
    {
        return switch (operation)
        {
            case CLEAR -> StringUtils.translate("litematica.gui.label.lvc_project.operation_short_clear");
            case DISCARD -> StringUtils.translate("litematica.gui.label.lvc_project.operation_short_discard");
            default -> GuiLvcRecoveryDialog.displayOperation(operation);
        };
    }

    private static void openRecoveryDataUnreadableDialog(GuiLvcProjectController controller)
    {
        GuiBase.openGui(new GuiLvcNoticeDialog(
                LvcOperationCoordinator.confirmParent(controller),
                "litematica.gui.title.lvc_project.operation_cancelled",
                "litematica.gui.message.lvc_project.recovery_data_unreadable",
                0xFFFF5555
        ));
    }

    static void promptClearArea(GuiLvcProjectController controller)
    {
        LvcAreaWorkflow.promptClearArea(controller);
    }

    static void discardChanges(GuiLvcProjectController controller)
    {
        LvcAreaWorkflow.discardChanges(controller);
    }

    static void commitStoredSelectionWithCurrentSelectionFallback(GuiLvcProjectController controller, String message)
    {
        LvcVersionWorkflow.commitStoredSelectionWithCurrentSelectionFallback(controller, message);
    }

    static void updateAreasFromCurrentSelection(GuiLvcProjectController controller)
    {
        LvcVersionWorkflow.updateAreasFromCurrentSelection(controller);
    }

    static void promptCheckoutCommit(GuiLvcProjectController controller, LvcProjectService.CommitInfo commit)
    {
        LvcCheckoutWorkflow.promptCheckoutCommit(controller, commit);
    }

    static void checkoutCommit(GuiLvcProjectController controller, LvcProjectService.CommitInfo commit)
    {
        LvcCheckoutWorkflow.checkoutCommit(controller, commit);
    }

    static void promptCheckoutBranch(GuiLvcProjectController controller, String branchName)
    {
        LvcCheckoutWorkflow.promptCheckoutBranch(controller, branchName);
    }

    private static void scheduleRecoveryCheckout(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                 Level restoreWorld, String targetCommit, @Nullable String targetBranch)
    {
        scheduleRecoveryCheckout(controller, handle, restoreWorld, targetCommit, targetBranch,
                null, null, "litematica.message.lvc_project.recovery_completed");
    }

    private static void scheduleRecoveryCheckout(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                 Level restoreWorld, String targetCommit, @Nullable String targetBranch,
                                                 @Nullable String previousHead, @Nullable String previousBranch,
                                                 String successMessageKey, Object... successMessageArgs)
    {
        scheduleRecoveryCheckout(controller, handle, restoreWorld, targetCommit, targetBranch,
                previousHead, previousBranch, false, LvcOperationJournal.Operation.CHECKOUT, "LVC Checkout",
                successMessageKey, successMessageArgs);
    }

    private static void scheduleRecoveryCheckout(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                 Level restoreWorld, String targetCommit, @Nullable String targetBranch,
                                                 @Nullable String previousHead, @Nullable String previousBranch,
                                                 boolean resetTargetBranchToCommit,
                                                 LvcOperationJournal.Operation journalOperation,
                                                 String displayName, String successMessageKey,
                                                 Object... successMessageArgs)
    {
        if (targetCommit == null || targetCommit.isBlank())
        {
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.recovery_failed", "Missing checkout target commit");
            return;
        }

        LvcSemanticCheckoutTask.Preflight task = new LvcSemanticCheckoutTask.Preflight(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                targetCommit,
                LvcTaskCallbacks.of(
                        result -> schedulePreparedRecoveryCheckout(controller, handle, result.prepared(), targetBranch,
                                previousHead, previousBranch, resetTargetBranchToCommit, journalOperation, displayName,
                                successMessageKey, successMessageArgs),
                        e -> LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Recovery")
                )
        );
        LvcTaskScheduling.scheduleForWorld(restoreWorld, task);
    }

    private static void schedulePreparedRecoveryCheckout(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                         LvcSemanticCheckoutTask.PreparedCheckout prepared,
                                                         @Nullable String targetBranch,
                                                         @Nullable String previousHead, @Nullable String previousBranch,
                                                         boolean resetTargetBranchToCommit,
                                                         LvcOperationJournal.Operation journalOperation,
                                                         String displayName,
                                                         String successMessageKey, Object... successMessageArgs)
    {
        try
        {
            controller.removeTrackingOverlay();
            LvcSemanticCheckoutTask.Apply task = new LvcSemanticCheckoutTask.Apply(
                    handle,
                    prepared,
                    targetBranch,
                    previousHead,
                    previousBranch,
                    resetTargetBranchToCommit,
                    journalOperation,
                    displayName,
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                controller.loadTrackingOverlay();
                                controller.gui.initGui();
                                LvcGuiMessages.show(MessageType.SUCCESS, successMessageKey, successMessageArgs);
                            },
                            e -> LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e, true),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Recovery")
                    )
            );
            LvcTaskScheduling.scheduleForWorld(prepared.world(), task);
        }
        catch (Exception e)
        {
            prepared.close();
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e);
        }
    }

    private static void scheduleRecoveryDiscard(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                Level restoreWorld, String targetCommit)
    {
        scheduleRecoveryDiscard(controller, handle, restoreWorld, targetCommit,
                "litematica.message.lvc_project.recovery_completed");
    }

    private static void scheduleRecoveryDiscard(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                Level restoreWorld, String targetCommit,
                                                String successMessageKey, Object... successMessageArgs)
    {
        controller.removeTrackingOverlay();
        LvcSemanticDiscardTask task = new LvcSemanticDiscardTask(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                targetCommit,
                LvcTaskCallbacks.of(
                        result ->
                        {
                            controller.loadTrackingOverlay();
                            controller.gui.initGui();
                            LvcGuiMessages.show(MessageType.SUCCESS, successMessageKey, successMessageArgs);
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Recovery")
                )
        );
        LvcTaskScheduling.scheduleForWorld(restoreWorld, task);
    }

    private static void scheduleRecoveryMergeRestore(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                     Level restoreWorld, String targetCommit,
                                                     @Nullable String targetBranch, @Nullable String sourceBranch,
                                                     @Nullable String previousHead, String successMessageKey,
                                                     Object... successMessageArgs)
    {
        controller.removeTrackingOverlay();
        LvcSemanticDiscardTask task = new LvcSemanticDiscardTask(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                targetCommit,
                LvcTaskCallbacks.of(
                        result ->
                        {
                            controller.loadTrackingOverlay();
                            controller.gui.initGui();
                            LvcGuiMessages.show(MessageType.SUCCESS, successMessageKey, successMessageArgs);
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.RECOVERY, "litematica.error.lvc_project.recovery_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Recovery")
                ),
                "LVC Recovery",
                LvcOperationJournal.Operation.MERGE,
                targetBranch,
                sourceBranch,
                previousHead
        );
        LvcTaskScheduling.scheduleForWorld(restoreWorld, task);
    }

}
