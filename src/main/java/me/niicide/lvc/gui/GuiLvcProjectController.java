package me.niicide.lvc.gui;

import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcFriendlyErrors.Operation;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.gui.widgets.WidgetLvcBranchActionMenu;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcOperationJournal;
import me.niicide.lvc.task.LvcRemoteServerApplyTask;
import me.niicide.lvc.task.LvcSemanticCheckoutTask;
import me.niicide.lvc.task.LvcRefreshMarker;
import me.niicide.lvc.task.LvcSemanticExportTask;
import me.niicide.lvc.task.LvcSemanticOverlayTask;
import me.niicide.lvc.task.LvcTaskCallbacks;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.task.LvcTaskScheduling;
import me.niicide.lvc.world.LvcWorldAccess;
import me.niicide.lvc.world.LvcWorldBackend;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.scheduler.ITask;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;

final class GuiLvcProjectController
{
    private static final int COMMIT_DESCRIPTION_DISPLAY_LINES = 4;
    private static final int COMMIT_DESCRIPTION_MAX_LINES = 8;
    private static final String LOAD_OVERLAY_OPERATION = "LVC Load Overlay";
    private static final String SEMANTIC_CHECKOUT_UNSUPPORTED_KEY = "litematica.error.lvc_project.semantic_checkout_restore_unimplemented";
    private static final String SEMANTIC_PULL_UNSUPPORTED_KEY = "litematica.error.lvc_project.semantic_pull_restore_unimplemented";

    final GuiLvcProjectManager gui;
    private boolean recoveryAttempted;
    @Nullable private LvcOperationJournal.Entry pendingInterruptedOperation;
    @Nullable private LvcOperationJournal.Operation pendingInterruptedOperationType;

    GuiLvcProjectController(GuiLvcProjectManager gui)
    {
        this.gui = gui;
    }

    IButtonActionListener buttonListener(GuiLvcProjectButtonType type)
    {
        return new GuiLvcProjectCommandListeners.ButtonListener(type, this);
    }

    void refreshRepositoryState()
    {
        this.gui.detachedHead = false;
        this.gui.checkoutBranchName = LvcProjectService.DEFAULT_BRANCH;
        this.gui.headPointerName = LvcProjectService.DEFAULT_BRANCH;
        this.gui.remoteUrl = null;
        this.gui.setBranchNames(List.of(LvcProjectService.DEFAULT_BRANCH));

        try
        {
            this.gui.detachedHead = LvcProjectService.isDetachedHead(this.gui.repositoryDirectory);
            this.gui.remoteUrl = LvcProjectService.remoteOriginUrl(this.gui.repositoryDirectory);
            this.gui.checkoutBranchName = LvcProjectService.preferredCheckoutBranchName(this.gui.repositoryDirectory);
            this.gui.headPointerName = LvcProjectService.headPointerName(this.gui.repositoryDirectory);
            this.gui.setBranchNames(LvcProjectService.listLocalBranches(this.gui.repositoryDirectory));
        }
        catch (Exception e)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.history_failed", e.getMessage());
        }

        this.gui.updateTitle();
    }

    void loadInitialTrackingOverlay()
    {
        if (!this.recoveryAttempted)
        {
            this.recoveryAttempted = true;

            if (GuiLvcProjectTaskActions.recoverInterruptedOperation(this))
            {
                return;
            }
        }

        if (LvcRefreshMarker.exists(this.gui.repositoryDirectory))
        {
            if (this.isOverlayLoadActiveForThisRepo())
            {
                LvcDiagnostics.debug("GuiLvcProjectManager: refresh marker found but overlay load already active repo='{}'",
                        this.gui.repositoryDirectory);
                return;
            }

            this.gui.initialOverlayAttempted = true;
            this.scheduleSemanticTrackingOverlay(true, true);
            return;
        }

        if (this.gui.initialOverlayAttempted || !this.gui.hasHistory())
        {
            this.focusTrackingOverlay();
            return;
        }

        this.gui.initialOverlayAttempted = true;
        this.scheduleSemanticTrackingOverlay(false, false);
    }

    void setPendingInterruptedOperation(LvcOperationJournal.Entry entry, LvcOperationJournal.Operation operation)
    {
        this.pendingInterruptedOperation = entry;
        this.pendingInterruptedOperationType = operation;
        LvcDiagnostics.debug("GuiLvcProjectManager: pending interrupted operation loaded repo='{}' operation='{}' phase='{}'",
                this.gui.repositoryDirectory, entry.operation(), entry.phase());
    }

    void clearPendingInterruptedOperation()
    {
        this.pendingInterruptedOperation = null;
        this.pendingInterruptedOperationType = null;
    }

    void promptCommitMessage()
    {
        try
        {
            if (LvcProjectService.isDetachedHead(this.gui.repositoryDirectory))
            {
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.detached_head_save_version");
                return;
            }
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.SAVE_VERSION, "litematica.error.lvc_project.commit_failed", e);
            return;
        }

        this.openCommitMessageDialog();
    }

    void promptCheckoutSelectedCommit()
    {
        LvcProjectService.CommitInfo commit = this.requireSelectedCommit();

        if (commit != null)
        {
            this.promptCheckoutCommit(commit);
        }
    }

    void promptDeleteLatestVersion(LvcProjectService.CommitInfo commit)
    {
        if (this.promptPendingInterruptedOperationIfNeeded())
        {
            return;
        }

        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return;
        }

        if (!this.isLatestDeletableCommit(commit))
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.delete_version_not_latest");
            return;
        }

        LvcDiagnostics.debug("GuiLvcProjectManager: opening delete latest version dialog repo='{}' commit='{}'",
                this.gui.repositoryDirectory, commit.id());
        this.gui.blurBranchOverlayInputs();
        GuiBase.openGui(new GuiLvcDeleteVersionDialog(
                LvcOperationCoordinator.confirmParent(this),
                () -> this.deleteLatestVersionKeepChanges(commit),
                () -> this.deleteLatestVersionDeleteChanges(commit),
                () -> LvcDiagnostics.debug("GuiLvcProjectManager: delete latest version cancelled repo='{}' commit='{}'",
                        this.gui.repositoryDirectory, commit.id())
        ));
    }

    private void deleteLatestVersionKeepChanges(LvcProjectService.CommitInfo commit)
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return;
        }

        try
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: delete latest version keep changes selected repo='{}' commit='{}'",
                    this.gui.repositoryDirectory, commit.id());
            LvcProjectService.LatestCommitUndoResult result = LvcProjectService.undoLatestCommitKeepChanges(this.gui.repositoryDirectory);

            this.refreshAfterLatestVersionDelete();
            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.deleted_version_keep_changes",
                    result.shortCommitId());
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.DELETE_VERSION, "litematica.error.lvc_project.delete_version_failed", e);
        }
    }

    private void deleteLatestVersionDeleteChanges(LvcProjectService.CommitInfo commit)
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(this, "LVC Delete Version");

        if (handle.isEmpty())
        {
            return;
        }

        LvcSemanticCheckoutTask.PreparedCheckout prepared = null;

        try
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: delete latest version delete changes selected repo='{}' commit='{}'",
                    this.gui.repositoryDirectory, commit.id());
            Level restoreWorld = minecraft.level;

            if (LvcWorldBackend.resolve(restoreWorld) != LvcWorldBackend.DIRECT)
            {
                this.scheduleRemoteLatestVersionDelete(handle.get(), restoreWorld, commit);
                return;
            }

            restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(restoreWorld);
            prepared = LvcSemanticCheckoutTask.prepareLatestCommitDelete(handle.get(), this.gui.repositoryDirectory, restoreWorld);
            String targetBranch = prepared.currentBranchName();

            if (targetBranch == null || targetBranch.isBlank())
            {
                throw new IllegalStateException("LVC repository is not on a local branch");
            }

            this.removeTrackingOverlay();
            LvcSemanticCheckoutTask.Apply task = new LvcSemanticCheckoutTask.Apply(
                    handle.get(),
                    prepared,
                    targetBranch,
                    prepared.currentCommitId(),
                    targetBranch,
                    true,
                    LvcOperationJournal.Operation.DELETE_VERSION,
                    "LVC Delete Version",
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                this.refreshAfterLatestVersionDelete();
                                if (result.postOperationDiffs().detected())
                                {
                                    LvcOperationCoordinator.showPostOperationDiffsNotice(this, "LVC Delete Version",
                                            result.postOperationDiffs());
                                }
                                else
                                {
                                    LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.deleted_version_delete_changes",
                                            commit.shortId(), LvcOperationCoordinator.regionCountText(result.regionCount()));
                                }
                            },
                            e -> LvcGuiMessages.showTaskError(Operation.DELETE_VERSION,
                                    "litematica.error.lvc_project.delete_version_failed", e, true),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Delete Version")
                    )
            );
            prepared = null;
            LvcOperationCoordinator.scheduleStarted(restoreWorld, task, "LVC Delete Version");
        }
        catch (Exception e)
        {
            if (prepared != null)
            {
                prepared.close();
            }

            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.DELETE_VERSION, "litematica.error.lvc_project.delete_version_failed", e);
        }
    }

    private void scheduleRemoteLatestVersionDelete(LvcOperationHandle handle, Level restoreWorld,
                                                   LvcProjectService.CommitInfo commit) throws Exception
    {
        LvcProjectService.LatestCommitUndoTarget target = LvcProjectService.latestCommitUndoTarget(this.gui.repositoryDirectory);
        String targetBranch = target.branchName();

        if (targetBranch == null || targetBranch.isBlank())
        {
            throw new IllegalStateException("LVC repository is not on a local branch");
        }

        this.removeTrackingOverlay();
        LvcRemoteServerApplyTask task = LvcRemoteServerApplyTask.deleteVersion(
                handle,
                this.gui.repositoryDirectory,
                restoreWorld,
                target.parentCommitId(),
                targetBranch,
                LvcTaskCallbacks.of(
                        result ->
                        {
                            this.refreshAfterLatestVersionDelete();
                            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.deleted_version_delete_changes",
                                    commit.shortId(), LvcOperationCoordinator.regionCountText(result.regionCount()));
                            this.showLossyRemoteApplyWarning(result);
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.DELETE_VERSION,
                                "litematica.error.lvc_project.delete_version_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Delete Version")
                )
        );
        LvcOperationCoordinator.scheduleStarted(restoreWorld, task, "LVC Delete Version");
        LvcDiagnostics.debug("GuiLvcProjectManager: remote delete latest version scheduled repo='{}' branch='{}' commit='{}' parent='{}'",
                this.gui.repositoryDirectory, targetBranch, target.commitId(), target.parentCommitId());
    }

    private void showLossyRemoteApplyWarning(LvcRemoteServerApplyTask.Result result)
    {
        if (result.lossy())
        {
            LvcGuiMessages.show(MessageType.WARNING, "litematica.message.lvc_project.lossy_command_apply");
        }
    }

    void exportSelectedCommit()
    {
        LvcProjectService.CommitInfo commit = this.requireSelectedCommit();

        if (commit == null)
        {
            return;
        }

        Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquire("LVC Export", this.gui.repositoryDirectory);

        if (handle.isEmpty())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return;
        }

        LvcSemanticExportTask task = new LvcSemanticExportTask(
                handle.get(),
                this.gui.repositoryDirectory,
                commit.id(),
                DataManager.getSchematicsBaseDirectory(),
                LvcTaskCallbacks.of(
                        result -> LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.exported", result.fileName()),
                        e -> LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.export_failed", e.getMessage()),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Export")
                )
        );
        LvcTaskScheduling.scheduleClient(task);
        LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_started", "LVC Export");
    }

    void push()
    {
        try
        {
            if (!LvcProjectService.hasRemote(this.gui.repositoryDirectory))
            {
                this.promptRemoteEdit(true);
                return;
            }

            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_started", "LVC Push");
            List<String> statuses = LvcProjectService.push(this.gui.repositoryDirectory);
            String statusSummary = statuses.isEmpty() ? "OK" : String.join(", ", statuses);
            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.pushed", statusSummary);
        }
        catch (Exception e)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.push_failed", LvcProjectService.describeRemoteFailure(e));
        }
    }

    void promptPull()
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        try
        {
            if (!LvcProjectService.hasRemote(this.gui.repositoryDirectory))
            {
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.pull_failed",
                        StringUtils.translate("litematica.error.lvc_project.no_remote_configured"));
                return;
            }

            boolean dirty = LvcProjectService.hasUncommittedChanges(this.gui.repositoryDirectory);

            if (dirty)
            {
                GuiLvcProjectController self = this;
                GuiBase.openGui(new GuiLvcConfirmAction(
                        420,
                        "litematica.gui.title.lvc_project.confirm_reset_pull",
                        new fi.dy.masa.malilib.interfaces.IConfirmationListener()
                        {
                            @Override public boolean onActionConfirmed() { self.executePull(minecraft.level); return true; }
                            @Override public boolean onActionCancelled() { return true; }
                        },
                        this.gui,
                        "litematica.gui.message.lvc_project.confirm_reset_pull"
                ));
                return;
            }
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.PULL, "litematica.error.lvc_project.pull_failed", e);
            return;
        }

        this.executePull(minecraft.level);
    }

    private void executePull(Level level)
    {
        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(this, "LVC Pull");
        if (handle.isEmpty()) return;

        try
        {
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_started", "LVC Pull");
            me.niicide.lvc.git.LvcGitRemoteOps.PullOutcome outcome = LvcProjectService.pullWithOutcome(this.gui.repositoryDirectory);

            if (!outcome.succeeded())
            {
                LvcTaskRegistry.release(handle.get());
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.pull_failed", outcome.status());
                return;
            }

            if (!outcome.headMoved())
            {
                LvcTaskRegistry.release(handle.get());
                this.gui.initGui();
                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.pull_up_to_date");
                return;
            }

            this.schedulePullRestore(handle.get(), level, outcome.headBefore(), outcome.headAfter());
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.pull_failed", LvcProjectService.describeRemoteFailure(e));
        }
    }

    private void schedulePullRestore(LvcOperationHandle handle, Level level, String headBefore, String headAfter)
    {
        try
        {
            LvcWorldBackend backend = LvcWorldBackend.resolve(level);

            if (backend != LvcWorldBackend.DIRECT)
            {
                this.removeTrackingOverlay();
                LvcRemoteServerApplyTask task = LvcRemoteServerApplyTask.checkout(
                        handle, this.gui.repositoryDirectory, level, headAfter, this.gui.checkoutBranchName,
                        LvcTaskCallbacks.of(
                                result -> { this.loadTrackingOverlay(); this.gui.initGui(); LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.pulled", "OK"); },
                                e -> LvcGuiMessages.showTaskError(Operation.PULL, "litematica.error.lvc_project.pull_failed", e, true),
                                () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Pull")
                        )
                );
                LvcTaskScheduling.scheduleForWorld(level, task);
                return;
            }

            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(level);
            LvcSemanticCheckoutTask.Preflight preflight = new LvcSemanticCheckoutTask.Preflight(
                    handle, this.gui.repositoryDirectory, restoreWorld, headAfter,
                    LvcTaskCallbacks.of(
                            result -> this.schedulePullApply(handle, result.prepared(), headBefore),
                            e -> LvcGuiMessages.showTaskError(Operation.PULL, "litematica.error.lvc_project.pull_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Pull")
                    )
            );
            LvcOperationCoordinator.scheduleStarted(restoreWorld, preflight, "LVC Pull");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.showTaskError(Operation.PULL, "litematica.error.lvc_project.pull_failed", e);
        }
    }

    private void schedulePullApply(LvcOperationHandle handle, LvcSemanticCheckoutTask.PreparedCheckout prepared, String previousHead)
    {
        try
        {
            this.removeTrackingOverlay();
            LvcSemanticCheckoutTask.Apply task = new LvcSemanticCheckoutTask.Apply(
                    handle, prepared, this.gui.checkoutBranchName, previousHead, null,
                    LvcTaskCallbacks.of(
                            result -> { this.loadTrackingOverlay(); this.gui.initGui();
                                if (result.postOperationDiffs().detected()) LvcOperationCoordinator.showPostOperationDiffsNotice(this, "LVC Pull", result.postOperationDiffs());
                                else LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.pulled", "OK"); },
                            e -> LvcGuiMessages.showTaskError(Operation.PULL, "litematica.error.lvc_project.pull_failed", e, true),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Pull")
                    )
            );
            LvcTaskScheduling.scheduleForWorld(prepared.world(), task);
        }
        catch (Exception e)
        {
            prepared.close();
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.showTaskError(Operation.PULL, "litematica.error.lvc_project.pull_failed", e);
        }
    }

    void updateAreas()
    {
        Minecraft minecraft = Minecraft.getInstance();
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();

        if (minecraft.player == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_player");
            return;
        }

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        if (selection == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.update_areas_failed", StringUtils.translate("litematica.error.lvc_project.no_selection"));
            return;
        }

        try
        {
            if (LvcProjectService.isDetachedHead(this.gui.repositoryDirectory))
            {
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.update_areas_failed", StringUtils.translate("litematica.error.lvc_project.detached_head_commit"));
                return;
            }

            int regionCount = LvcProjectService.countValidSelectionRegions(selection);

            if (regionCount <= 0)
            {
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.update_areas_failed", StringUtils.translate("litematica.error.lvc_project.no_selection"));
                return;
            }

            GuiBase.openGui(new GuiLvcConfirmAction(
                    420,
                    "litematica.gui.title.lvc_project.confirm_update_areas",
                    new GuiLvcProjectCommandListeners.UpdateAreasConfirmListener(this),
                    this.gui,
                    "litematica.gui.message.lvc_project.confirm_update_areas",
                    LvcOperationCoordinator.regionCountText(regionCount)
            ));
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.UPDATE_AREAS, "litematica.error.lvc_project.update_areas_failed", e);
        }
    }

    void scanChanges()
    {
        GuiLvcProjectTaskActions.scanChanges(this);
    }

    void viewChanges()
    {
        LvcProjectService.TrackingOverlay overlay = this.gui.trackingOverlay;

        if (overlay == null)
        {
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.open_project_first");
            return;
        }

        GuiLvcDiffViewer diffViewer = new GuiLvcDiffViewer(overlay.placement());
        diffViewer.setParent(this.gui);
        GuiBase.openGui(diffViewer);
        LvcVerifierStartWorkflow.startIfGitMatica(overlay.placement(), diffViewer, diffViewer::initGui);
    }

    void promptClearArea()
    {
        GuiLvcProjectTaskActions.promptClearArea(this);
    }

    void openBranchSelector()
    {
        if (this.gui.detachedHead)
        {
            this.promptCheckoutBranch();
            return;
        }

        this.showNotImplemented("litematica.message.lvc_project.branch_selector_not_implemented");
    }

    void handleBranchDropdownSelection(String branchName)
    {
        if (branchName == null || branchName.isBlank())
        {
            this.gui.syncBranchDropdownSelection();
            return;
        }

        if (this.promptPendingInterruptedOperationIfNeeded())
        {
            this.gui.syncBranchDropdownSelection();
            return;
        }

        LvcDiagnostics.debug(
                "GuiLvcProjectManager: branch dropdown selected repo='{}' selected='{}' current='{}' detached={}",
                this.gui.repositoryDirectory,
                branchName,
                this.gui.currentBranchDropdownName(),
                this.gui.detachedHead
        );

        if (!this.gui.detachedHead && branchName.equals(this.gui.headPointerName))
        {
            this.gui.syncBranchDropdownSelection();
            return;
        }

        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            this.gui.syncBranchDropdownSelection();
            return;
        }

        GuiLvcProjectTaskActions.promptCheckoutBranch(this, branchName);
    }

    void handleBranchMenuAction(WidgetLvcBranchActionMenu.Action action)
    {
        this.gui.closeBranchActionMenu();

        if (this.promptPendingInterruptedOperationIfNeeded())
        {
            return;
        }

        LvcDiagnostics.debug("GuiLvcProjectManager: branch action selected repo='{}' action='{}'", this.gui.repositoryDirectory, action);

        switch (action)
        {
            case CREATE -> this.promptCreateBranch();
            case DELETE -> this.promptDeleteBranch();
            case RENAME -> this.promptRenameBranch();
            case MERGE -> this.promptMergeBranch();
        }
    }

    private void promptCreateBranch()
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return;
        }

        this.gui.blurBranchOverlayInputs();
        GuiBase.openGui(new GuiLvcTextInputDialog(
                256,
                "litematica.gui.title.lvc_project.create_branch",
                "",
                this.gui,
                this::validateCreateBranchName,
                (java.util.function.Consumer<String>) this::createBranch,
                -1
        ));
    }

    private void promptDeleteBranch()
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return;
        }

        List<String> branches = this.deletableBranchNames();

        if (branches.isEmpty())
        {
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.delete_branch_no_candidates");
            return;
        }

        LvcDiagnostics.debug("GuiLvcProjectManager: opening delete branch dialog repo='{}' candidates={}", this.gui.repositoryDirectory, branches.size());
        this.gui.blurBranchOverlayInputs();
        GuiBase.openGui(new GuiLvcDeleteBranchDialog(this, branches));
    }

    private void promptRenameBranch()
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return;
        }

        List<String> branches = this.renameableBranchNames();

        if (branches.isEmpty())
        {
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.rename_branch_no_candidates");
            return;
        }

        LvcDiagnostics.debug("GuiLvcProjectManager: opening rename branch dialog repo='{}' candidates={}", this.gui.repositoryDirectory, branches.size());
        this.gui.blurBranchOverlayInputs();
        GuiBase.openGui(new GuiLvcRenameBranchDialog(this, branches, this.attachedHeadBranchName()));
    }

    private void promptMergeBranch()
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return;
        }

        if (this.gui.detachedHead)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_detached");
            return;
        }

        List<String> branches = this.mergeableBranchNames();

        if (branches.isEmpty())
        {
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.merge_branch_no_candidates");
            return;
        }

        LvcDiagnostics.debug("GuiLvcProjectManager: opening merge branch dialog repo='{}' candidates={}", this.gui.repositoryDirectory, branches.size());
        this.gui.blurBranchOverlayInputs();
        GuiBase.openGui(new GuiLvcMergeBranchDialog(this, branches));
    }

    boolean createBranch(String branchName)
    {
        try
        {
            String normalizedBranchName = LvcProjectService.createAndCheckoutBranch(this.gui.repositoryDirectory, branchName);

            this.refreshRepositoryState();
            this.gui.refreshHistory();
            this.gui.initGui();
            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.branch_created", normalizedBranchName);
            LvcDiagnostics.debug("GuiLvcProjectManager: created branch repo='{}' branch='{}'", this.gui.repositoryDirectory, normalizedBranchName);
            return true;
        }
        catch (Exception e)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.create_branch_failed", e.getMessage());
            LvcDiagnostics.debug("GuiLvcProjectManager: create branch failed repo='{}' input='{}' error='{}'", this.gui.repositoryDirectory, branchName, e.getMessage());
            return false;
        }
    }

    @Nullable
    String validateCreateBranchName(String branchName)
    {
        if (branchName == null || branchName.isBlank())
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: create branch validation failed repo='{}' input='{}' error='missing name'",
                    this.gui.repositoryDirectory, branchName);
            return StringUtils.translate("litematica.error.lvc_project.branch_name_required");
        }

        try
        {
            LvcProjectService.validateNewBranchName(this.gui.repositoryDirectory, branchName);
            return null;
        }
        catch (IllegalArgumentException e)
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: create branch validation failed repo='{}' input='{}' error='{}'",
                    this.gui.repositoryDirectory, branchName, e.getMessage());
            return e.getMessage();
        }
        catch (Exception e)
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: create branch validation deferred to runtime repo='{}' input='{}' error='{}'",
                    this.gui.repositoryDirectory, branchName, e.getMessage());
            return null;
        }
    }

    @Nullable
    String deleteBranch(@Nullable String branchName)
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return null;
        }

        if (branchName == null || branchName.isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.delete_branch_required");
            return null;
        }

        try
        {
            String deletedBranchName = LvcProjectService.deleteBranch(this.gui.repositoryDirectory, branchName);

            this.refreshRepositoryState();
            this.gui.refreshHistory();
            this.gui.initGui();
            LvcDiagnostics.debug("GuiLvcProjectManager: deleted branch repo='{}' branch='{}'", this.gui.repositoryDirectory, deletedBranchName);
            return deletedBranchName;
        }
        catch (Exception e)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.delete_branch_failed", e.getMessage());
            LvcDiagnostics.debug("GuiLvcProjectManager: delete branch failed repo='{}' input='{}' error='{}'", this.gui.repositoryDirectory, branchName, e.getMessage());
            return null;
        }
    }

    boolean renameBranch(@Nullable String oldBranchName, String newBranchName)
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return false;
        }

        if (oldBranchName == null || oldBranchName.isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.rename_branch_required");
            return false;
        }

        if (newBranchName == null || newBranchName.isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.rename_branch_name_required");
            return false;
        }

        try
        {
            String renamedBranchName = LvcProjectService.renameBranch(this.gui.repositoryDirectory, oldBranchName, newBranchName);

            this.refreshRepositoryState();
            this.gui.refreshHistory();
            this.gui.initGui();
            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.branch_renamed", oldBranchName, renamedBranchName);
            LvcDiagnostics.debug("GuiLvcProjectManager: renamed branch repo='{}' old='{}' new='{}'", this.gui.repositoryDirectory, oldBranchName, renamedBranchName);
            return true;
        }
        catch (Exception e)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.rename_branch_failed", e.getMessage());
            LvcDiagnostics.debug("GuiLvcProjectManager: rename branch failed repo='{}' old='{}' new='{}' error='{}'", this.gui.repositoryDirectory, oldBranchName, newBranchName, e.getMessage());
            return false;
        }
    }

    @Nullable
    String validateRenameBranchInputs(@Nullable String oldBranchName, String newBranchName)
    {
        if (oldBranchName == null || oldBranchName.isBlank())
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: rename branch validation failed repo='{}' old='{}' new='{}' error='missing source'",
                    this.gui.repositoryDirectory, oldBranchName, newBranchName);
            return StringUtils.translate("litematica.error.lvc_project.rename_branch_required");
        }

        if (newBranchName == null || newBranchName.isBlank())
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: rename branch validation failed repo='{}' old='{}' new='{}' error='missing target'",
                    this.gui.repositoryDirectory, oldBranchName, newBranchName);
            return StringUtils.translate("litematica.error.lvc_project.rename_branch_name_required");
        }

        if (oldBranchName.trim().equals(newBranchName.trim()))
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: rename branch validation failed repo='{}' old='{}' new='{}' error='same name'",
                    this.gui.repositoryDirectory, oldBranchName, newBranchName);
            return StringUtils.translate("litematica.error.lvc_project.rename_branch_same_name");
        }

        try
        {
            LvcProjectService.validateNewBranchName(this.gui.repositoryDirectory, newBranchName);
            return null;
        }
        catch (IllegalArgumentException e)
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: rename branch validation failed repo='{}' old='{}' new='{}' error='{}'",
                    this.gui.repositoryDirectory, oldBranchName, newBranchName, e.getMessage());
            return e.getMessage();
        }
        catch (Exception e)
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: rename branch validation deferred to runtime repo='{}' old='{}' new='{}' error='{}'",
                    this.gui.repositoryDirectory, oldBranchName, newBranchName, e.getMessage());
            return null;
        }
    }

    boolean mergeBranch(@Nullable String sourceBranchName)
    {
        if (sourceBranchName == null || sourceBranchName.isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_required");
            return false;
        }

        GuiLvcProjectTaskActions.mergeBranch(this, sourceBranchName);
        return true;
    }

    private List<String> deletableBranchNames()
    {
        String protectedBranch = this.gui.detachedHead ? null : this.gui.headPointerName;
        List<String> branches = new java.util.ArrayList<>();

        for (String branchName : this.gui.branchNames)
        {
            if (branchName != null && !branchName.isBlank() && !branchName.equals(protectedBranch) && !branches.contains(branchName))
            {
                branches.add(branchName);
            }
        }

        return List.copyOf(branches);
    }

    private List<String> renameableBranchNames()
    {
        List<String> branches = new java.util.ArrayList<>();

        for (String branchName : this.gui.branchNames)
        {
            if (branchName != null && !branchName.isBlank() && !branches.contains(branchName))
            {
                branches.add(branchName);
            }
        }

        return List.copyOf(branches);
    }

    private List<String> mergeableBranchNames()
    {
        String protectedBranch = this.gui.detachedHead ? null : this.gui.headPointerName;
        List<String> branches = new java.util.ArrayList<>();

        for (String branchName : this.gui.branchNames)
        {
            if (branchName != null && !branchName.isBlank() && !branchName.equals(protectedBranch) && !branches.contains(branchName))
            {
                branches.add(branchName);
            }
        }

        return List.copyOf(branches);
    }

    private boolean isLatestDeletableCommit(LvcProjectService.CommitInfo commit)
    {
        return commit != null &&
                !this.gui.detachedHead &&
                this.gui.history.size() > 1 &&
                !this.gui.history.isEmpty() &&
                this.gui.history.get(0).id().equals(commit.id());
    }

    private void refreshAfterLatestVersionDelete()
    {
        this.refreshRepositoryState();
        this.gui.refreshHistory();
        this.gui.syncBranchDropdownSelection();
        this.gui.updateTitle();

        if (Minecraft.getInstance().level != null)
        {
            this.loadTrackingOverlay();
        }
        else
        {
            this.focusTrackingOverlay();
        }
    }

    @Nullable
    private String attachedHeadBranchName()
    {
        if (this.gui.detachedHead || this.gui.headPointerName == null || this.gui.headPointerName.isBlank())
        {
            return null;
        }

        return this.gui.headPointerName;
    }

    void showNotImplemented(String key)
    {
        LvcGuiMessages.show(MessageType.INFO, key);
    }

    void showFullReleaseFeature()
    {
        LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.full_release_feature");
    }

    void removeTrackingOverlay()
    {
        LvcProjectService.removeTrackingOverlay(this.gui.repositoryDirectory);
        this.gui.trackingOverlay = null;
    }

    void closeTrackingOverlay()
    {
        LvcProjectService.closeTrackingOverlay(this.gui.repositoryDirectory);
        this.gui.trackingOverlay = null;
    }

    void focusTrackingOverlay()
    {
        LvcProjectService.focusTrackingOverlay(this.gui.repositoryDirectory);
    }

    private void promptCheckoutBranch()
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        this.reportUnsupported("litematica.error.lvc_project.checkout_failed", SEMANTIC_CHECKOUT_UNSUPPORTED_KEY);
    }

    private void openCommitMessageDialog()
    {
        GuiBase.openGui(new GuiLvcCommitMessageDialog(
                256,
                COMMIT_DESCRIPTION_DISPLAY_LINES,
                COMMIT_DESCRIPTION_MAX_LINES,
                "litematica.gui.title.lvc_project.commit_message",
                "",
                "",
                this.gui,
                new GuiLvcProjectCommandListeners.CommitMessageSetter(this)
        ));
    }

    void commitStoredSelectionWithCurrentSelectionFallback(String message)
    {
        if (this.promptPendingInterruptedOperationIfNeeded())
        {
            return;
        }

        GuiLvcProjectTaskActions.commitStoredSelectionWithCurrentSelectionFallback(this, message);
    }

    private void promptRemoteEdit(boolean pushAfterSave)
    {
        try
        {
            String currentRemoteUrl = LvcProjectService.remoteOriginUrl(this.gui.repositoryDirectory);
            String inputValue = currentRemoteUrl != null ? currentRemoteUrl : "";
            GuiBase.openGui(new GuiLvcTextInputDialog(
                    512,
                    "litematica.gui.title.lvc_project.remote_url",
                    inputValue,
                    this.gui,
                    value -> null,
                    new GuiLvcProjectCommandListeners.RemoteUrlSetter(this, pushAfterSave)
            ));
        }
        catch (Exception e)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.remote_failed", e.getMessage());
        }
    }

    private void reportUnsupported(String errorKey, String detailKey)
    {
        LvcGuiMessages.show(MessageType.ERROR, errorKey, StringUtils.translate(detailKey));
    }

    void updateAreasFromCurrentSelection()
    {
        if (this.promptPendingInterruptedOperationIfNeeded())
        {
            return;
        }

        GuiLvcProjectTaskActions.updateAreasFromCurrentSelection(this);
    }

    void loadTrackingOverlay()
    {
        this.scheduleSemanticTrackingOverlay(true, true);
    }

    void refreshTrackingOverlayAfterWorldMutation()
    {
        ClientLevel clientLevel = Minecraft.getInstance().level;

        if (clientLevel == null)
        {
            this.focusTrackingOverlay();
            return;
        }

        try
        {
            LvcProjectService.TrackingOverlay overlay = LvcProjectService.refreshReusableTrackingOverlayVerifier(
                    this.gui.repositoryDirectory,
                    clientLevel,
                    this.gui
            );

            if (overlay != null)
            {
                this.gui.trackingOverlay = overlay;
                this.updateTrackingStatusAfterRestore();
                this.clearRefreshMarkerAfterOverlay();
                return;
            }
        }
        catch (Exception e)
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: reusable overlay verifier refresh failed repo='{}' error='{}'",
                    this.gui.repositoryDirectory, e.getMessage());
        }

        this.loadTrackingOverlay();
    }

    private void scheduleSemanticTrackingOverlay(boolean forceReload, boolean startVerifier)
    {
        ClientLevel clientLevel = Minecraft.getInstance().level;

        if (clientLevel == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        if (!forceReload)
        {
            try
            {
                LvcProjectService.TrackingOverlay reusableOverlay = LvcProjectService.getReusableSemanticTrackingOverlay(
                        this.gui.repositoryDirectory,
                        clientLevel,
                        this.gui
                );

                if (reusableOverlay != null)
                {
                    this.gui.trackingOverlay = reusableOverlay;
                    this.updateTrackingStatusAfterRestore();
                    this.clearRefreshMarkerAfterOverlay();
                    return;
                }
            }
            catch (Exception e)
            {
                LvcGuiMessages.showTaskError(Operation.LOAD_OVERLAY, "litematica.error.lvc_project.tracking_failed", e);
                return;
            }
        }

        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: deferred overlay load while foreground operation is active repo='{}' active='{}'",
                    this.gui.repositoryDirectory, LvcTaskRegistry.activeOperationName());
            return;
        }

        Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquireBackground(LOAD_OVERLAY_OPERATION, this.gui.repositoryDirectory);

        if (handle.isEmpty())
        {
            if (this.isOverlayLoadActiveForThisRepo())
            {
                LvcDiagnostics.debug("GuiLvcProjectManager: overlay load already active repo='{}'", this.gui.repositoryDirectory);
                return;
            }

            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return;
        }

        this.gui.trackingStatus = StringUtils.translate("litematica.gui.label.lvc_project.tracking_loading");
        LvcSemanticOverlayTask task = new LvcSemanticOverlayTask(
                handle.get(),
                this.gui.repositoryDirectory,
                this.gui.projectName,
                clientLevel,
                this.gui,
                startVerifier,
                forceReload,
                LvcTaskCallbacks.of(
                        overlay ->
                        {
                            this.gui.trackingOverlay = overlay;
                            this.updateTrackingStatusAfterRestore();
                            this.clearRefreshMarkerAfterOverlay();
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.LOAD_OVERLAY, "litematica.error.lvc_project.tracking_failed", e),
                        () -> LvcDiagnostics.debug("GuiLvcProjectManager: overlay load aborted repo='{}'", this.gui.repositoryDirectory)
                )
        );
        LvcTaskScheduling.scheduleForWorld(clientLevel, task);
    }

    private boolean isOverlayLoadActiveForThisRepo()
    {
        return LvcTaskRegistry.hasActiveBackgroundOperation(LOAD_OVERLAY_OPERATION, this.gui.repositoryDirectory);
    }

    void abortOverlayLoadForForegroundOperation(String reason)
    {
        if (this.isOverlayLoadActiveForThisRepo() == false)
        {
            return;
        }

        boolean removedClientTask = TaskScheduler.getInstanceClient().removeTasksIf(this::isOverlayLoadTaskForThisRepo);
        boolean removedServerTask = TaskScheduler.getInstanceServer().removeTasksIf(this::isOverlayLoadTaskForThisRepo);

        if (removedClientTask || removedServerTask)
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: aborted overlay load before foreground operation repo='{}' reason='{}'",
                    this.gui.repositoryDirectory, reason);
        }
    }

    private boolean isOverlayLoadTaskForThisRepo(ITask task)
    {
        return task instanceof LvcSemanticOverlayTask overlayTask && overlayTask.isForRepository(this.gui.repositoryDirectory);
    }

    private void clearRefreshMarkerAfterOverlay()
    {
        try
        {
            LvcRefreshMarker.delete(this.gui.repositoryDirectory);
        }
        catch (Exception e)
        {
            LvcDiagnostics.warn("Failed to clear LVC refresh marker repo='{}' error='{}'",
                    this.gui.repositoryDirectory, e.getMessage());
        }
    }

    void discardChanges()
    {
        if (this.promptPendingInterruptedOperationIfNeeded())
        {
            return;
        }

        GuiLvcProjectTaskActions.discardChanges(this);
    }

    @Nullable
    private LvcProjectService.CommitInfo requireSelectedCommit()
    {
        if (this.gui.selectedCommit == null)
        {
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.no_commit_selected");
            return null;
        }

        return this.gui.selectedCommit;
    }

    private void promptCheckoutCommit(LvcProjectService.CommitInfo commit)
    {
        GuiLvcProjectTaskActions.promptCheckoutCommit(this, commit);
    }

    void checkoutCommit(LvcProjectService.CommitInfo commit)
    {
        if (this.promptPendingInterruptedOperationIfNeeded())
        {
            return;
        }

        GuiLvcProjectTaskActions.checkoutCommit(this, commit);
    }

    void resetWorkingTreeThenCheckoutCommit(LvcProjectService.CommitInfo commit)
    {
        if (this.promptPendingInterruptedOperationIfNeeded())
        {
            return;
        }

        try
        {
            LvcProjectService.resetWorkingTreeToHead(this.gui.repositoryDirectory);
            this.checkoutCommit(commit);
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.CHECKOUT, "litematica.error.lvc_project.checkout_failed", e);
        }
    }

    void updateTrackingStatusAfterRestore()
    {
        if (this.gui.trackingOverlay == null)
        {
            this.gui.trackingStatus = "";
            return;
        }

        if (this.gui.trackingOverlay.verifierStarted())
        {
            if (this.gui.trackingOverlay.verifier().isFinished())
            {
                int errors = this.gui.trackingOverlay.verifier().getTotalErrors();
                this.gui.trackingStatus = errors == 0 ?
                        StringUtils.translate("litematica.gui.label.lvc_project.tracking_clean") :
                        StringUtils.translate("litematica.gui.label.lvc_project.tracking_dirty", errors);
            }
            else
            {
                this.gui.trackingStatus = StringUtils.translate("litematica.gui.label.lvc_project.tracking_checking", this.gui.trackingOverlay.placement().getName());
            }
        }
        else
        {
            this.gui.trackingStatus = StringUtils.translate("litematica.gui.label.lvc_project.tracking_loaded", this.gui.trackingOverlay.placement().getName());
        }
    }

    void handleButton(GuiLvcProjectButtonType type)
    {
        if (type != GuiLvcProjectButtonType.LITEMATICA_MENU)
        {
            this.abortOverlayLoadForForegroundOperation(type.name());
        }

        if (type != GuiLvcProjectButtonType.LITEMATICA_MENU && LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running", LvcTaskRegistry.activeOperationName());
            return;
        }

        if (type != GuiLvcProjectButtonType.CLOSE_PROJECT &&
                type != GuiLvcProjectButtonType.LITEMATICA_MENU &&
                this.promptPendingInterruptedOperationIfNeeded())
        {
            return;
        }

        switch (type)
        {
            case SAVE_VERSION -> this.promptCommitMessage();
            case DISCARD_CHANGES -> this.discardChanges();
            case CLEAR_AREA -> this.promptClearArea();
            case EXPORT -> this.exportSelectedCommit();
            case PUSH -> this.push();
            case PULL -> this.promptPull();
            case BRANCH_SELECTOR -> this.openBranchSelector();
            case CHECKOUT_VERSION -> this.promptCheckoutSelectedCommit();
            case VIEW_CHANGES -> this.viewChanges();
            case REVERT_CHANGES -> this.showFullReleaseFeature();
            case PROJECT_EDITOR -> GuiBase.openGui(new GuiLvcProjectEditor(this.gui.repositoryDirectory, this.gui.projectName));
            case PROJECT_SETTINGS -> this.showFullReleaseFeature();
            case CLOSE_PROJECT ->
            {
                this.closeTrackingOverlay();
                GuiBase.openGui(new GuiLvcProjectBrowser());
            }
            case LITEMATICA_MENU -> GuiBase.openGui(new GuiMainMenu());
        }
    }

    private boolean promptPendingInterruptedOperationIfNeeded()
    {
        LvcOperationJournal.Entry entry = this.pendingInterruptedOperation;
        LvcOperationJournal.Operation operation = this.pendingInterruptedOperationType;

        if (entry == null || operation == null)
        {
            return false;
        }

        LvcDiagnostics.debug("GuiLvcProjectManager: prompting pending interrupted operation repo='{}' operation='{}' phase='{}'",
                this.gui.repositoryDirectory, entry.operation(), entry.phase());
        GuiLvcProjectTaskActions.promptPendingInterruptedOperation(this, entry, operation);
        return true;
    }

}
