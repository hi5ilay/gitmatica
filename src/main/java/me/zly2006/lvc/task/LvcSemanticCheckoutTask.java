package me.zly2006.lvc.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcUserActionException;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.semantic.LvcSemanticWorldApplier;
import me.zly2006.lvc.semantic.LvcTrackedBlockCursor;
import me.zly2006.lvc.storage.LvcCanonicalNbt;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.storage.LvcRepository;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcSemanticCheckoutTask
{
    private static final int PREFLIGHT_MISMATCH_SAMPLE_LIMIT = 8;

    private LvcSemanticCheckoutTask()
    {
    }

    public static PreparedCheckout prepareLatestCommitDelete(LvcOperationHandle handle, Path repositoryDirectory,
                                                             Level world) throws Exception
    {
        Git openedGit = null;
        RevWalk openedRevWalk = null;

        try
        {
            if (!(world instanceof ServerLevel serverWorld))
            {
                throw new LvcUserActionException(LvcUserActionException.Reason.NO_AUTHORITATIVE_WORLD,
                        "Semantic LVC delete version requires server-authoritative world access");
            }

            openedGit = Git.open(repositoryDirectory.toFile());
            Repository repository = openedGit.getRepository();
            openedRevWalk = new RevWalk(repository);
            ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

            if (head == null)
            {
                throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_HEAD,
                        "LVC repository has no HEAD commit to delete");
            }

            RevCommit currentCommit = openedRevWalk.parseCommit(head);

            if (currentCommit.getParentCount() == 0)
            {
                throw new IOException("Cannot delete the only version");
            }

            RevCommit targetCommit = openedRevWalk.parseCommit(currentCommit.getParent(0).getId());
            String currentBranchName = localBranchName(repository.getFullBranch());

            if (currentBranchName == null)
            {
                throw new IOException("LVC repository is not on a local branch");
            }

            LvcManifest currentManifest = LvcSemanticRepository.readCommitManifest(repository, currentCommit);
            LvcManifest targetManifest = LvcSemanticRepository.readCommitManifest(repository, targetCommit);
            LvcLocalState localState = LvcSemanticRepository.readLocalState(repositoryDirectory);
            String siteId = localState.activeSite();
            LvcManifest.Site currentSite = currentManifest.site(siteId);
            LvcManifest.Site targetSite = targetManifest.site(siteId);
            LvcLocalState.SitePlacement placement = localState.sites().get(siteId);

            if (placement == null)
            {
                throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_LOCAL_PLACEMENT,
                        "Missing local placement for active LVC site: " + siteId);
            }

            LvcSemanticTaskContext.validatePlacementDimension(placement, world);

            PreparedCheckout prepared = new PreparedCheckout(
                    handle,
                    repositoryDirectory,
                    serverWorld,
                    openedGit,
                    openedRevWalk,
                    currentCommit,
                    targetCommit,
                    currentBranchName,
                    currentSite,
                    targetSite,
                    placement,
                    LvcIntPosition.fromList(placement.origin())
            );
            openedGit = null;
            openedRevWalk = null;
            LvcDiagnostics.debug(handle,
                    "semantic delete version prepared branch='{}' commit={} parent={} site={} dimension={} origin={} chunks={}",
                    currentBranchName, currentCommit.getName(), targetCommit.getName(), siteId,
                    placement.dimension(), placement.origin(), unionChunkKeys(currentSite, targetSite).size());
            return prepared;
        }
        catch (Exception e)
        {
            if (openedRevWalk != null)
            {
                openedRevWalk.close();
            }

            if (openedGit != null)
            {
                openedGit.close();
            }

            throw e;
        }
    }

    public static final class Preflight extends LvcChunkedTaskBase<PreflightResult>
    {
        private final Path repositoryDirectory;
        private final Level world;
        private final String targetCommitId;
        @Nullable private PreparedCheckout prepared;
        private List<String> preflightChunkKeys = List.of();
        private final DiffStats currentDiff = new DiffStats();
        private final DiffStats targetDiff = new DiffStats();
        private int nextChunkIndex;
        private boolean hasGitChanges;
        private boolean handoff;

        public Preflight(LvcOperationHandle handle, Path repositoryDirectory, Level world, String targetCommitId,
                         LvcTaskCallbacks<PreflightResult> callbacks)
        {
            super(handle, "LVC Checkout Preflight", callbacks, false, LITEMATICA_VERIFIER_BUDGET_NANOS);
            this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
            this.world = Objects.requireNonNull(world, "world");
            this.targetCommitId = Objects.requireNonNull(targetCommitId, "targetCommitId");
        }

        @Override
        public void init()
        {
            Git openedGit = null;
            RevWalk openedRevWalk = null;

            try
            {
                if (!(this.world instanceof ServerLevel serverWorld))
                {
                    throw new LvcUserActionException(LvcUserActionException.Reason.NO_AUTHORITATIVE_WORLD,
                            "Semantic LVC checkout requires server-authoritative world access");
                }

                openedGit = Git.open(this.repositoryDirectory.toFile());
                Repository repository = openedGit.getRepository();
                openedRevWalk = new RevWalk(repository);
                ObjectId head = LvcRepository.resolveHead(this.repositoryDirectory);

                if (head == null)
                {
                    throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_HEAD,
                            "LVC repository has no HEAD commit to checkout from");
                }

                RevCommit currentCommit = openedRevWalk.parseCommit(head);
                RevCommit targetCommit = LvcProjectGitOps.resolveCommit(repository, openedRevWalk, this.targetCommitId);
                String currentBranchName = localBranchName(repository.getFullBranch());
                LvcManifest currentManifest = LvcSemanticRepository.readCommitManifest(repository, currentCommit);
                LvcManifest targetManifest = LvcSemanticRepository.readCommitManifest(repository, targetCommit);
                LvcLocalState localState = LvcSemanticRepository.readLocalState(this.repositoryDirectory);
                String siteId = localState.activeSite();
                LvcManifest.Site currentSite = currentManifest.site(siteId);
                LvcManifest.Site targetSite = targetManifest.site(siteId);
                LvcLocalState.SitePlacement placement = localState.sites().get(siteId);

                if (placement == null)
                {
                    throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_LOCAL_PLACEMENT,
                            "Missing local placement for active LVC site: " + siteId);
                }

                LvcSemanticTaskContext.validatePlacementDimension(placement, this.world);

                this.prepared = new PreparedCheckout(
                        this.handle(),
                        this.repositoryDirectory,
                        serverWorld,
                        openedGit,
                        openedRevWalk,
                        currentCommit,
                        targetCommit,
                        currentBranchName,
                        currentSite,
                        targetSite,
                        placement,
                        LvcIntPosition.fromList(placement.origin())
                );
                openedGit = null;
                openedRevWalk = null;
                this.preflightChunkKeys = unionChunkKeys(currentSite, targetSite);
                this.hasGitChanges = LvcProjectGitOps.hasUncommittedChanges(this.repositoryDirectory);
                this.prepared.gitChanges = this.hasGitChanges;
                LvcDiagnostics.debug(this.handle(),
                        "semantic checkout preflight initialized site={} current={} target={} dimension={} origin={} chunks={} gitDirty={} currentBlockEntityOnlyDirty={} targetBlockEntityOnlyDirty={} budgetNanos={}",
                        siteId, currentCommit.getName(), targetCommit.getName(), placement.dimension(), placement.origin(),
                        this.preflightChunkKeys.size(), this.hasGitChanges, false, true, LITEMATICA_VERIFIER_BUDGET_NANOS);
                this.updateProgressHud();
            }
            catch (Exception e)
            {
                if (openedRevWalk != null)
                {
                    openedRevWalk.close();
                }

                if (openedGit != null)
                {
                    openedGit.close();
                }

                this.closePreparedIfOwned();
                this.fail(e instanceof Exception exception ? exception : new RuntimeException(e));
            }
        }

        @Override
        protected boolean step() throws Exception
        {
            PreparedCheckout checkout = this.requirePrepared();

            if (this.nextChunkIndex >= this.preflightChunkKeys.size())
            {
                LvcCommitChunkCache.Stats cacheStats = checkout.chunkCacheStats();
                LvcDiagnostics.debug(this.handle(),
                        "semantic checkout preflight complete currentChangedBlocks={} currentChangedChunks={} targetChangedBlocks={} targetChangedChunks={} gitDirty={} chunkCacheCommitHits={} chunkCacheObjectHits={} chunkCacheMisses={} chunkCacheCommitEntries={} chunkCacheObjectEntries={}",
                        this.currentDiff.changedBlocks(), this.currentDiff.changedChunks,
                        this.targetDiff.changedBlocks(), this.targetDiff.changedChunks,
                        this.hasGitChanges, cacheStats.commitHits(), cacheStats.objectHits(), cacheStats.misses(),
                        cacheStats.commitEntries(), cacheStats.objectEntries());
                this.logMismatchSamples("current", this.currentDiff);
                this.logMismatchSamples("target", this.targetDiff);
                this.scheduleCurrentMismatchClientSync(checkout);
                return true;
            }

            String chunkKey = this.preflightChunkKeys.get(this.nextChunkIndex);
            String currentObjectId = checkout.currentSite.fullHashes().get(chunkKey);
            String targetObjectId = checkout.targetSite.fullHashes().get(chunkKey);

            if (currentObjectId != null)
            {
                LvcChunk currentChunk = checkout.readChunk(checkout.currentCommit, currentObjectId);
                this.compareChunk(checkout, checkout.currentCommit, chunkKey, currentObjectId, currentChunk, this.currentDiff, "current", false, true);
            }

            if (targetObjectId != null)
            {
                LvcChunk targetChunk = checkout.readChunk(checkout.targetCommit, targetObjectId);
                this.compareChunk(checkout, checkout.targetCommit, chunkKey, targetObjectId, targetChunk, this.targetDiff, "target", true, false);
            }

            this.nextChunkIndex++;
            return false;
        }

        @Override
        protected PreflightResult result()
        {
            PreparedCheckout checkout = this.requirePrepared();
            this.handoff = true;
            return new PreflightResult(checkout, this.currentDiff.toSummary(), this.targetDiff.toSummary(), this.hasGitChanges);
        }

        @Override
        public void stop()
        {
            try
            {
                super.stop();
            }
            finally
            {
                this.closePreparedIfOwned();
            }
        }

        @Override
        protected void updateProgressHud()
        {
            this.infoHudLines.clear();
            this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks", this.nextChunkIndex, this.preflightChunkKeys.size()));
        }

        private void compareChunk(PreparedCheckout checkout, RevCommit commit, String chunkKey, String objectId,
                                  LvcChunk chunk, DiffStats stats, String label,
                                  boolean detectBlockEntityOnlyChanges, boolean syncStateMismatches) throws IOException
        {
            boolean dirtyChunk = false;
            LvcChunkCoordinate coordinate = LvcChunkCoordinate.parse(chunkKey);

            try
            {
                for (LvcTrackedBlockCursor.StoredBlock block : LvcTrackedBlockCursor.storedBlocks(coordinate, checkout.origin, chunk))
                {
                    String blockState = null;
                    byte[] blockEntityBytes = block.blockEntityBytes();

                    try
                    {
                        LvcSemanticWorldApplier.validateRestoreTarget(checkout.world, block.blockPos());
                        blockState = block.blockState();
                        BlockState targetState = LvcSemanticWorldApplier.parseRestoreBlockState(blockState);
                        BlockState currentState = checkout.world.getBlockState(block.blockPos());

                        if (!LvcSemanticWorldApplier.isRestoredStateAcceptable(currentState, targetState))
                        {
                            stats.stateMismatches++;
                            stats.addSample(new PreflightMismatchSample(
                                    label,
                                    "block-state",
                                    chunkKey,
                                    block.blockPos(),
                                    blockState,
                                    LvcMinecraftWorldReader.blockStateString(currentState)));

                            if (syncStateMismatches)
                            {
                                stats.addClientSyncPosition(block.blockPos());
                            }

                            dirtyChunk = true;
                        }
                        else if (detectBlockEntityOnlyChanges && blockEntityBytes != null && !blockEntityMatchesStoredPayload(checkout.world, block.blockPos(), blockEntityBytes))
                        {
                            stats.blockEntityMismatches++;
                            stats.addSample(new PreflightMismatchSample(
                                    label,
                                    "block-entity",
                                    chunkKey,
                                    block.blockPos(),
                                    LvcChunkStore.objectId(blockEntityBytes),
                                    blockEntityPayloadId(checkout.world, block.blockPos())));
                            dirtyChunk = true;
                        }
                    }
                    catch (Exception e)
                    {
                        throw LvcSemanticWorldApplier.withPositionContext("checkout preflight " + label,
                                coordinate, block.maskIndex(), block.trackedOrdinal(), block.projectPos(), block.blockPos(),
                                blockState, blockEntityBytes, e);
                    }
                }
            }
            catch (Exception e)
            {
                throw withChunkContext("preflight " + label, chunkKey, objectId, commit.getName(), stats.scannedChunks, e);
            }

            stats.scannedChunks++;

            if (dirtyChunk)
            {
                stats.changedChunks++;
            }
        }

        private void logMismatchSamples(String label, DiffStats stats)
        {
            if (stats.samples.isEmpty())
            {
                return;
            }

            for (PreflightMismatchSample sample : stats.samples)
            {
                LvcDiagnostics.info(this.handle(), "semantic checkout preflight {} mismatch sample: {}", label, sample.summary());
            }

            int omitted = stats.changedBlocks() - stats.samples.size();

            if (omitted > 0)
            {
                LvcDiagnostics.info(this.handle(), "semantic checkout preflight {} mismatch samples omitted={}", label, omitted);
            }
        }

        private void scheduleCurrentMismatchClientSync(PreparedCheckout checkout)
        {
            int syncPositions = this.currentDiff.clientSyncPositions.size();

            if (syncPositions == 0)
            {
                return;
            }

            LvcAuthoritativeClientSyncTask.schedule(checkout.world, this.currentDiff.clientSyncPositions);
            LvcDiagnostics.info(this.handle(),
                    "semantic checkout preflight queued client sync for current mismatches positions={} stateMismatches={}",
                    syncPositions, this.currentDiff.stateMismatches);
        }

        private PreparedCheckout requirePrepared()
        {
            return Objects.requireNonNull(this.prepared, "prepared");
        }

        private void closePreparedIfOwned()
        {
            if (!this.handoff && this.prepared != null)
            {
                this.prepared.close();
                this.prepared = null;
            }
        }
    }

    public static final class Apply extends LvcChunkedTaskBase<Result>
    {
        private final PreparedCheckout checkout;
        @Nullable private final String targetBranchName;
        @Nullable private final String previousHeadOverride;
        @Nullable private final String previousBranchOverride;
        private final boolean resetTargetBranchToCommit;
        private final LvcOperationJournal.Operation journalOperation;
        private final List<Map.Entry<String, String>> chunkRefs;
        private Phase phase = Phase.CHECKOUT;
        @Nullable private LvcSemanticRestoreEngine restoreEngine;
        private boolean journalWritten;
        private boolean gitMoved;

        public Apply(LvcOperationHandle handle, PreparedCheckout checkout, LvcTaskCallbacks<Result> callbacks)
        {
            this(handle, checkout, null, callbacks);
        }

        public Apply(LvcOperationHandle handle, PreparedCheckout checkout, @Nullable String targetBranchName,
                     LvcTaskCallbacks<Result> callbacks)
        {
            this(handle, checkout, targetBranchName, null, null, callbacks);
        }

        public Apply(LvcOperationHandle handle, PreparedCheckout checkout, @Nullable String targetBranchName,
                     @Nullable String previousHeadOverride, @Nullable String previousBranchOverride,
                     LvcTaskCallbacks<Result> callbacks)
        {
            this(handle, checkout, targetBranchName, previousHeadOverride, previousBranchOverride,
                    false, LvcOperationJournal.Operation.CHECKOUT, "LVC Checkout", callbacks);
        }

        public Apply(LvcOperationHandle handle, PreparedCheckout checkout, @Nullable String targetBranchName,
                     @Nullable String previousHeadOverride, @Nullable String previousBranchOverride,
                     boolean resetTargetBranchToCommit, LvcOperationJournal.Operation journalOperation,
                     String displayName, LvcTaskCallbacks<Result> callbacks)
        {
            super(handle, displayName, callbacks, true, LITEMATICA_DIRECT_PASTE_BUDGET_NANOS);
            this.checkout = Objects.requireNonNull(checkout, "checkout");
            this.targetBranchName = targetBranchName == null || targetBranchName.isBlank() ? null : targetBranchName.trim();
            this.previousHeadOverride = normalizeNullable(previousHeadOverride);
            this.previousBranchOverride = normalizeNullable(previousBranchOverride);
            this.resetTargetBranchToCommit = resetTargetBranchToCommit;
            this.journalOperation = Objects.requireNonNull(journalOperation, "journalOperation");
            this.chunkRefs = List.copyOf(checkout.targetSite.fullHashes().entrySet());
        }

        @Override
        public void init()
        {
            LvcDiagnostics.debug(this.handle(), "semantic checkout apply initialized target={} branch='{}' chunks={} regions={} blockEntityOnlyRestore={}",
                    this.checkout.targetCommit.getName(), this.targetBranchName == null ? "<detached>" : this.targetBranchName,
                    this.chunkRefs.size(), this.checkout.targetSite.regions().size(), true);
            this.restoreEngine = new LvcSemanticRestoreEngine(
                    this.checkout.world,
                    this.checkout.targetSite,
                    this.checkout.origin,
                    this.chunkRefs,
                    objectId -> this.checkout.readChunk(this.checkout.targetCommit, objectId),
                    this::markWorldMutation,
                    projectPos -> { },
                    LvcSemanticRestoreEngine.Options.checkout(this.checkout.targetCommit.getName()));
            this.updateProgressHud();
        }

        @Override
        protected boolean step() throws Exception
        {
            if (!this.gitMoved)
            {
                this.writeJournalAndMoveGit();
                this.phase = Phase.RESTORE;
                return false;
            }

            return this.requireRestoreEngine().processNextStep();
        }

        @Override
        protected long executionDeadlineNanos()
        {
            return serverTickAwareDeadlineNanos(this.checkout.world, LITEMATICA_DIRECT_PASTE_BUDGET_NANOS);
        }

        @Override
        protected boolean shouldContinueWithinTick()
        {
            LvcSemanticRestoreEngine engine = this.restoreEngine;
            return engine == null || !engine.shouldYieldAfterStep();
        }

        @Override
        protected Result result() throws Exception
        {
            LvcRefreshMarker.write(this.checkout.repositoryDirectory,
                    this.journalOperation.name().toLowerCase(java.util.Locale.ROOT), this.checkout.targetCommit.getName());
            LvcOperationJournal.delete(this.checkout.repositoryDirectory);
            LvcCommitChunkCache.Stats cacheStats = this.checkout.chunkCacheStats();
            LvcSemanticRestoreEngine engine = this.requireRestoreEngine();
            LvcDiagnostics.debug(this.handle(),
                    "semantic checkout apply complete target={} restoredBlocks={} changedChunks={} blockEntityRewrites={} clearedEntities={} spawnedEntities={} chunkCacheCommitHits={} chunkCacheObjectHits={} chunkCacheMisses={} chunkCacheCommitEntries={} chunkCacheObjectEntries={}",
                    this.checkout.targetCommit.getName(), engine.restoredBlocks(), engine.changedChunks(), engine.blockEntityRewrites(),
                    engine.clearedEntities(), engine.spawnedEntities(),
                    cacheStats.commitHits(), cacheStats.objectHits(), cacheStats.misses(),
                    cacheStats.commitEntries(), cacheStats.objectEntries());
            return new Result(this.checkout.targetCommit.getName(), this.checkout.targetSite.regions().size(),
                    engine.restoredBlocks(), engine.blockEntityRewrites());
        }

        @Override
        public void stop()
        {
            try
            {
                super.stop();
            }
            finally
            {
                this.checkout.close();
            }
        }

        @Override
        protected void updateProgressHud()
        {
            this.infoHudLines.clear();
            this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);
            LvcSemanticRestoreEngine engine = this.restoreEngine;
            String phaseLabel = engine == null ? this.phase.label : engine.phaseLabel();
            int progress = engine == null ? 0 : engine.currentProgress();
            int total = engine == null ? this.chunkRefs.size() : engine.currentTotal();
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_phase", phaseLabel));
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks", progress, total));
            this.infoHudLines.add("Restored blocks: " + (engine == null ? 0 : engine.restoredBlocks()));
            this.infoHudLines.add("Dirty subchunk passes: " + (engine == null ? 0 : engine.fullSubchunkRewritePasses()));
        }

        private LvcSemanticRestoreEngine requireRestoreEngine()
        {
            return Objects.requireNonNull(this.restoreEngine, "restoreEngine");
        }

        private void writeJournalAndMoveGit() throws Exception
        {
            this.writeJournalIfNeeded("checkout");

            if (this.checkout.gitChanges)
            {
                LvcDiagnostics.debug(this.handle(), "semantic checkout resetting dirty working tree before target checkout");
                LvcProjectGitOps.resetWorkingTreeToHead(this.checkout.repositoryDirectory);
            }

            if (this.targetBranchName != null)
            {
                if (this.resetTargetBranchToCommit)
                {
                    LvcProjectGitOps.checkoutBranchAndResetToCommit(this.checkout.repositoryDirectory,
                            this.targetBranchName, this.checkout.targetCommit.getName());
                }
                else
                {
                    LvcProjectGitOps.checkoutBranchToWorkingTree(this.checkout.repositoryDirectory, this.targetBranchName);
                }
            }
            else
            {
                LvcProjectGitOps.checkoutCommitToWorkingTree(this.checkout.repositoryDirectory, this.checkout.targetCommit.getName());
            }

            this.gitMoved = true;
            LvcDiagnostics.debug(this.handle(), "semantic checkout moved Git working tree to {} branch='{}' resetBranch={}",
                    this.checkout.targetCommit.getName(),
                    this.targetBranchName == null ? "<detached>" : this.targetBranchName,
                    this.resetTargetBranchToCommit);
        }

        private void markWorldMutation() throws IOException
        {
            this.writeJournalIfNeeded("restore");
        }

        private void writeJournalIfNeeded(String phase) throws IOException
        {
            if (!this.journalWritten)
            {
                String previousHead = this.previousHeadOverride != null ? this.previousHeadOverride : this.checkout.currentCommit.getName();
                String previousBranch = this.previousBranchOverride != null ? this.previousBranchOverride : this.checkout.currentBranchName;
                if (this.journalOperation == LvcOperationJournal.Operation.DELETE_VERSION)
                {
                    LvcOperationJournal.writeDeleteVersion(this.checkout.repositoryDirectory, this.checkout.targetCommit.getName(),
                            this.targetBranchName, previousHead, previousBranch, phase);
                }
                else
                {
                    LvcOperationJournal.writeCheckout(this.checkout.repositoryDirectory, this.checkout.targetCommit.getName(),
                            this.targetBranchName, previousHead, previousBranch, phase);
                }
                this.journalWritten = true;
            }
        }
    }

    public static final class PreparedCheckout implements AutoCloseable
    {
        private final LvcOperationHandle handle;
        private final Path repositoryDirectory;
        private final ServerLevel world;
        private final Git git;
        private final RevWalk revWalk;
        private final RevCommit currentCommit;
        private final RevCommit targetCommit;
        @Nullable private final String currentBranchName;
        private final LvcManifest.Site currentSite;
        private final LvcManifest.Site targetSite;
        private final LvcLocalState.SitePlacement placement;
        private final LvcIntPosition origin;
        private final LvcCommitChunkCache chunkCache;
        private boolean gitChanges;
        private boolean closed;

        private PreparedCheckout(LvcOperationHandle handle, Path repositoryDirectory, ServerLevel world, Git git, RevWalk revWalk,
                                 RevCommit currentCommit, RevCommit targetCommit, @Nullable String currentBranchName,
                                 LvcManifest.Site currentSite, LvcManifest.Site targetSite,
                                 LvcLocalState.SitePlacement placement, LvcIntPosition origin)
        {
            this.handle = Objects.requireNonNull(handle, "handle");
            this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
            this.world = Objects.requireNonNull(world, "world");
            this.git = Objects.requireNonNull(git, "git");
            this.revWalk = Objects.requireNonNull(revWalk, "revWalk");
            this.currentCommit = Objects.requireNonNull(currentCommit, "currentCommit");
            this.targetCommit = Objects.requireNonNull(targetCommit, "targetCommit");
            this.currentBranchName = currentBranchName;
            this.currentSite = Objects.requireNonNull(currentSite, "currentSite");
            this.targetSite = Objects.requireNonNull(targetSite, "targetSite");
            this.placement = Objects.requireNonNull(placement, "placement");
            this.origin = Objects.requireNonNull(origin, "origin");
            this.chunkCache = new LvcCommitChunkCache(this.git.getRepository());
        }

        @Override
        public void close()
        {
            if (this.closed)
            {
                return;
            }

            this.closed = true;
            this.revWalk.close();
            this.git.close();
        }

        public ServerLevel world()
        {
            return this.world;
        }

        public String targetCommitId()
        {
            return this.targetCommit.getName();
        }

        public String currentCommitId()
        {
            return this.currentCommit.getName();
        }

        @Nullable
        public String currentBranchName()
        {
            return this.currentBranchName;
        }

        public String targetShortCommitId()
        {
            String id = this.targetCommit.getName();
            return id.substring(0, Math.min(8, id.length()));
        }

        public int regionCount()
        {
            return this.targetSite.regions().size();
        }

        private LvcChunk readChunk(RevCommit commit, String objectId) throws IOException
        {
            return this.chunkCache.read(commit, objectId);
        }

        private LvcCommitChunkCache.Stats chunkCacheStats()
        {
            return this.chunkCache.stats();
        }

        private BlockPos worldPos(LvcIntPosition projectPos)
        {
            LvcIntPosition worldPos = this.origin.offset(projectPos);
            return new BlockPos(worldPos.x(), worldPos.y(), worldPos.z());
        }

    }

    public record PreflightResult(PreparedCheckout prepared, DiffSummary currentDiff, DiffSummary targetDiff,
                                  boolean gitChanges)
    {
        public boolean hasUncommittedChanges()
        {
            return this.gitChanges || !this.currentDiff.clean();
        }
    }

    public record DiffSummary(int scannedChunks, int changedChunks, int stateMismatches, int blockEntityMismatches)
    {
        public int changedBlocks()
        {
            return this.stateMismatches + this.blockEntityMismatches;
        }

        public boolean clean()
        {
            return this.changedBlocks() == 0;
        }
    }

    public record Result(String commitId, int regionCount, int restoredBlocks, int blockEntityRewrites)
    {
    }

    private enum Phase
    {
        CHECKOUT("checkout"),
        RESTORE("restore");

        private final String label;

        Phase(String label)
        {
            this.label = label;
        }
    }

    private static final class DiffStats
    {
        private final List<PreflightMismatchSample> samples = new ArrayList<>();
        private final LongOpenHashSet clientSyncPositions = new LongOpenHashSet();
        private int scannedChunks;
        private int changedChunks;
        private int stateMismatches;
        private int blockEntityMismatches;

        private int changedBlocks()
        {
            return this.stateMismatches + this.blockEntityMismatches;
        }

        private DiffSummary toSummary()
        {
            return new DiffSummary(this.scannedChunks, this.changedChunks, this.stateMismatches, this.blockEntityMismatches);
        }

        private void addSample(PreflightMismatchSample sample)
        {
            if (this.samples.size() < PREFLIGHT_MISMATCH_SAMPLE_LIMIT)
            {
                this.samples.add(sample);
            }
        }

        private void addClientSyncPosition(BlockPos position)
        {
            this.clientSyncPositions.add(position.asLong());
        }
    }

    private record PreflightMismatchSample(String label, String kind, String chunkKey, BlockPos position,
                                           String expected, String actual)
    {
        private String summary()
        {
            return this.label + " " + this.kind + " chunk " + this.chunkKey + " at " +
                    this.position.getX() + "," + this.position.getY() + "," + this.position.getZ() +
                    ": expected " + this.expected + ", server " + this.actual;
        }
    }

    private static List<String> unionChunkKeys(LvcManifest.Site currentSite, LvcManifest.Site targetSite)
    {
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(currentSite.fullHashes().keySet());
        keys.addAll(targetSite.fullHashes().keySet());
        return List.copyOf(keys);
    }

    private static boolean blockEntityMatchesStoredPayload(Level world, BlockPos blockPos, byte[] expectedNbt) throws IOException
    {
        BlockEntity blockEntity = world.getBlockEntity(blockPos);

        if (blockEntity == null)
        {
            return false;
        }

        byte[] currentNbt = LvcCanonicalNbt.encodeBlockEntity(blockEntity.saveWithFullMetadata(world.registryAccess()));
        return Arrays.equals(currentNbt, expectedNbt);
    }

    private static String blockEntityPayloadId(Level world, BlockPos blockPos) throws IOException
    {
        BlockEntity blockEntity = world.getBlockEntity(blockPos);

        if (blockEntity == null)
        {
            return "<missing block entity>";
        }

        byte[] currentNbt = LvcCanonicalNbt.encodeBlockEntity(blockEntity.saveWithFullMetadata(world.registryAccess()));
        return LvcChunkStore.objectId(currentNbt);
    }

    private static IOException withChunkContext(String action, String chunkKey, String objectId, String commitId,
                                                int index, Exception cause)
    {
        String message = "LVC checkout failed during " + action +
                " chunk " + chunkKey +
                " (" + (index + 1) +
                ", object " + objectId +
                ", commit " + commitId + ")";

        if (cause.getMessage() != null && !cause.getMessage().isBlank())
        {
            message += ": " + cause.getMessage();
        }

        return new IOException(message, cause);
    }

    @Nullable
    private static String localBranchName(@Nullable String fullBranch)
    {
        if (fullBranch != null && fullBranch.startsWith(Constants.R_HEADS))
        {
            return fullBranch.substring(Constants.R_HEADS.length());
        }

        return null;
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }

        return value.trim();
    }

}
