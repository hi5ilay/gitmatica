package me.zly2006.lvc.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcUserActionException;
import me.zly2006.lvc.capture.LvcSiteWorkPlan;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcRepository;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.world.LvcWorldFreezeService;

import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcSemanticDiscardTask extends LvcChunkedTaskBase<LvcSemanticDiscardTask.Result>
{
    private static final String DISPLAY_NAME = "LVC Discard Changes";

    private final Path repositoryDirectory;
    private final Level world;
    private final LvcOperationJournal.Operation journalOperation;
    @Nullable private final String journalTargetBranch;
    @Nullable private final String journalSourceBranch;
    @Nullable private final String journalPreviousHead;
    @Nullable private String requestedCommitId;
    @Nullable private Git git;
    @Nullable private RevWalk revWalk;
    @Nullable private RevCommit commit;
    @Nullable private LvcManifest.Site site;
    @Nullable private LvcLocalState.SitePlacement placement;
    @Nullable private LvcIntPosition origin;
    @Nullable private LvcSiteWorkPlan workPlan;
    @Nullable private LvcWorldFreezeService.FreezeHandle freezeHandle;
    @Nullable private LvcCommitChunkCache chunkCache;
    private final Set<String> affectedRegionIds = new HashSet<>();
    private List<Map.Entry<String, String>> chunkRefs = List.of();
    private List<RegionBounds> regionBounds = List.of();
    @Nullable private LvcSemanticRestoreEngine restoreEngine;
    private int nextScanChunk;
    private boolean hasGitChanges;
    private boolean journalWritten;
    private boolean gitReset;
    private boolean operationWillDiscard;
    private boolean updateSuppressionStarted;
    private boolean wasPreventingUpdates;

    public LvcSemanticDiscardTask(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                  @Nullable String commitId, LvcTaskCallbacks<Result> callbacks)
    {
        this(handle, repositoryDirectory, world, commitId, callbacks, DISPLAY_NAME, LvcOperationJournal.Operation.DISCARD);
    }

    public LvcSemanticDiscardTask(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                  @Nullable String commitId, LvcTaskCallbacks<Result> callbacks,
                                  String displayName, LvcOperationJournal.Operation journalOperation)
    {
        this(handle, repositoryDirectory, world, commitId, callbacks, displayName, journalOperation, null, null, null);
    }

    public LvcSemanticDiscardTask(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                  @Nullable String commitId, LvcTaskCallbacks<Result> callbacks,
                                  String displayName, LvcOperationJournal.Operation journalOperation,
                                  @Nullable String journalTargetBranch, @Nullable String journalSourceBranch,
                                  @Nullable String journalPreviousHead)
    {
        super(handle, displayName, callbacks, true);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.world = Objects.requireNonNull(world, "world");
        this.journalOperation = Objects.requireNonNull(journalOperation, "journalOperation");
        this.journalTargetBranch = normalizeNullable(journalTargetBranch);
        this.journalSourceBranch = normalizeNullable(journalSourceBranch);
        this.journalPreviousHead = normalizeNullable(journalPreviousHead);
        this.requestedCommitId = commitId;
    }

    @Override
    public void init()
    {
        try
        {
            if (!(this.world instanceof ServerLevel serverWorld))
            {
                throw new LvcUserActionException(LvcUserActionException.Reason.NO_AUTHORITATIVE_WORLD,
                        "Semantic LVC discard requires server-authoritative world access");
            }

            this.git = Git.open(this.repositoryDirectory.toFile());
            Repository repository = this.git.getRepository();
            this.chunkCache = new LvcCommitChunkCache(repository);
            this.revWalk = new RevWalk(repository);
            String commitId = this.resolveCommitId();
            this.commit = LvcProjectGitOps.resolveCommit(repository, this.revWalk, commitId);
            this.requestedCommitId = this.commit.getName();
            LvcManifest manifest = LvcSemanticRepository.readCommitManifest(repository, this.commit);
            LvcLocalState localState = LvcSemanticRepository.readLocalState(this.repositoryDirectory);
            String siteId = localState.activeSite();
            this.site = manifest.site(siteId);
            this.placement = localState.sites().get(siteId);

            if (this.placement == null)
            {
                throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_LOCAL_PLACEMENT,
                        "Missing local placement for active LVC site: " + siteId);
            }

            LvcSemanticTaskContext.validatePlacementDimension(this.placement, this.world);
            this.origin = LvcIntPosition.fromList(this.placement.origin());
            this.workPlan = LvcSiteWorkPlan.create(this.site, this.placement);
            this.chunkRefs = List.copyOf(this.site.fullHashes().entrySet());
            this.regionBounds = this.site.regions().stream().map(RegionBounds::of).toList();
            this.hasGitChanges = LvcProjectGitOps.hasUncommittedChanges(this.repositoryDirectory);
            this.freezeHandle = LvcWorldFreezeService.freeze(serverWorld, this.workPlan);
            this.startUpdateSuppression();
            this.restoreEngine = new LvcSemanticRestoreEngine(
                    serverWorld,
                    this.site,
                    this.origin,
                    this::readChunk,
                    this::markOperationWillDiscard,
                    this::markAffectedRegions,
                    LvcSemanticRestoreEngine.Options.discard(this.requestedCommitId, this.chunkRefs.size()));
            LvcDiagnostics.debug(this.handle(), "semantic discard initialized site={} commit={} dimension={} origin={} chunks={} trackedBlocks={} gitDirty={} blockEntityOnlyRestore={}",
                    siteId, this.requestedCommitId, this.placement.dimension(), this.placement.origin(),
                    this.chunkRefs.size(), this.workPlan.blockCount(), this.hasGitChanges, false);
            this.updateProgressHud();
        }
        catch (Exception e)
        {
            this.fail(e instanceof Exception exception ? exception : new RuntimeException(e));
        }
    }

    @Override
    protected boolean step() throws Exception
    {
        if (this.nextScanChunk < this.chunkRefs.size())
        {
            Map.Entry<String, String> entry = this.chunkRefs.get(this.nextScanChunk);
            this.requireRestoreEngine().scanAndRestoreChunk(entry, this.nextScanChunk);
            this.nextScanChunk++;
            return false;
        }

        this.requireRestoreEngine().stabilizeCandidateBlocks();
        this.requireRestoreEngine().scheduleAuthoritativeClientSync();

        if (!this.hasGitChanges && !this.operationWillDiscard)
        {
            return true;
        }

        this.resetGitIfNeeded();
        return true;
    }

    @Override
    protected Result result() throws Exception
    {
        this.stopUpdateSuppression();
        this.closeFreeze();
        LvcRefreshMarker.write(this.repositoryDirectory, this.journalOperation.name().toLowerCase(java.util.Locale.ROOT), this.requireCommitId());
        LvcOperationJournal.delete(this.repositoryDirectory);
        LvcCommitChunkCache.Stats cacheStats = this.requireChunkCache().stats();
        LvcSemanticRestoreEngine engine = this.requireRestoreEngine();
        LvcDiagnostics.debug(this.handle(),
                "semantic discard complete commit={} restoredBlocks={} changedChunks={} affectedRegions={} totalRegions={} blockEntityRewrites={} discarded={} chunkCacheCommitHits={} chunkCacheObjectHits={} chunkCacheMisses={} chunkCacheCommitEntries={} chunkCacheObjectEntries={}",
                this.requireCommitId(), engine.restoredBlocks(), engine.changedChunks(), this.affectedRegionIds.size(),
                this.requireSite().regions().size(), engine.blockEntityRewrites(), this.hasGitChanges || this.operationWillDiscard,
                cacheStats.commitHits(), cacheStats.objectHits(), cacheStats.misses(),
                cacheStats.commitEntries(), cacheStats.objectEntries());
        return new Result(this.requireCommitId(), this.affectedRegionIds.size(), engine.restoredBlocks(),
                engine.blockEntityRewrites(), this.hasGitChanges || this.operationWillDiscard);
    }

    @Override
    protected void updateProgressHud()
    {
        this.infoHudLines.clear();
        this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);
        this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_phase", "scan/restore"));
        this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks", this.currentChunkProgress(), this.currentChunkTotal()));
        LvcSemanticRestoreEngine engine = this.restoreEngine;
        this.infoHudLines.add("Restored blocks: " + (engine == null ? 0 : engine.restoredBlocks()));
        this.infoHudLines.add("Changed subchunks: " + (engine == null ? 0 : engine.changedChunks()));
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
            this.stopUpdateSuppression();
            this.closeFreeze();

            if (this.revWalk != null)
            {
                this.revWalk.close();
            }

            if (this.git != null)
            {
                this.git.close();
            }
        }
    }

    private void resetGitIfNeeded() throws Exception
    {
        if (!this.gitReset && this.hasGitChanges)
        {
            this.markOperationWillDiscard();
            LvcProjectGitOps.resetWorkingTreeToHead(this.repositoryDirectory);
            this.gitReset = true;
        }
    }

    private void markOperationWillDiscard() throws Exception
    {
        this.operationWillDiscard = true;

        if (this.freezeHandle != null)
        {
            this.freezeHandle.discardSuppressedWork();
        }

        this.writeJournalIfNeeded();
    }

    private void writeJournalIfNeeded() throws IOException
    {
        if (!this.journalWritten)
        {
            if (this.journalOperation == LvcOperationJournal.Operation.MERGE)
            {
                LvcOperationJournal.write(this.repositoryDirectory, this.journalOperation, this.requireCommitId(),
                        this.journalTargetBranch, this.journalSourceBranch, this.journalPreviousHead, "restore");
            }
            else
            {
                LvcOperationJournal.write(this.repositoryDirectory, this.journalOperation, this.requireCommitId(), this.journalOperation.name().toLowerCase(java.util.Locale.ROOT));
            }

            this.journalWritten = true;
        }
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

    private LvcChunk readChunk(String objectId) throws IOException
    {
        return this.requireChunkCache().read(this.requireCommit(), objectId);
    }

    private void markAffectedRegions(LvcIntPosition projectPos)
    {
        for (RegionBounds region : this.regionBounds)
        {
            if (region.contains(projectPos))
            {
                this.affectedRegionIds.add(region.id());
            }
        }
    }

    private void startUpdateSuppression()
    {
        if (!this.updateSuppressionStarted)
        {
            this.wasPreventingUpdates = WorldUtils.shouldPreventBlockUpdates(this.world);
            WorldUtils.setShouldPreventBlockUpdates(this.world, true);
            this.updateSuppressionStarted = true;
        }
    }

    private void stopUpdateSuppression()
    {
        if (this.updateSuppressionStarted)
        {
            WorldUtils.setShouldPreventBlockUpdates(this.world, this.wasPreventingUpdates);
            this.updateSuppressionStarted = false;
        }
    }

    private void closeFreeze()
    {
        if (this.freezeHandle != null)
        {
            this.freezeHandle.close();
            this.freezeHandle = null;
        }
    }

    private String resolveCommitId() throws IOException
    {
        if (this.requestedCommitId != null && !this.requestedCommitId.isBlank())
        {
            return this.requestedCommitId;
        }

        ObjectId head = LvcRepository.resolveHead(this.repositoryDirectory);

        if (head == null)
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_HEAD,
                    "LVC repository has no HEAD commit to discard to");
        }

        return head.getName();
    }

    private int currentChunkProgress()
    {
        return this.nextScanChunk;
    }

    private int currentChunkTotal()
    {
        return this.chunkRefs.size();
    }

    private RevCommit requireCommit()
    {
        return Objects.requireNonNull(this.commit, "commit");
    }

    private LvcCommitChunkCache requireChunkCache()
    {
        return Objects.requireNonNull(this.chunkCache, "chunkCache");
    }

    private String requireCommitId()
    {
        return Objects.requireNonNull(this.requestedCommitId, "requestedCommitId");
    }

    private LvcManifest.Site requireSite()
    {
        return Objects.requireNonNull(this.site, "site");
    }

    private LvcSemanticRestoreEngine requireRestoreEngine()
    {
        return Objects.requireNonNull(this.restoreEngine, "restoreEngine");
    }

    public record Result(String commitId, int restoredRegionCount, int restoredBlocks, int blockEntityRewrites, boolean discarded)
    {
    }

    private record RegionBounds(String id, LvcIntPosition min, LvcIntPosition size)
    {
        private static RegionBounds of(LvcManifest.Region region)
        {
            return new RegionBounds(region.id(),
                    LvcIntPosition.fromList(region.min()),
                    LvcIntPosition.fromList(region.size()));
        }

        private boolean contains(LvcIntPosition projectPos)
        {
            return projectPos.x() >= this.min.x() && projectPos.x() < this.min.x() + this.size.x() &&
                    projectPos.y() >= this.min.y() && projectPos.y() < this.min.y() + this.size.y() &&
                    projectPos.z() >= this.min.z() && projectPos.z() < this.min.z() + this.size.z();
        }
    }
}
