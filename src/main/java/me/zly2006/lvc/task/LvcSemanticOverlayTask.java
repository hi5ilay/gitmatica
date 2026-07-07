package me.zly2006.lvc.task;

import java.nio.file.Path;
import java.util.Objects;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.overlay.LvcTrackingOverlayService;
import me.zly2006.lvc.semantic.LvcSemanticSchematicBuilder;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.storage.LvcRepository;
import me.zly2006.lvc.storage.LvcSemanticRepository;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcSemanticOverlayTask extends LvcChunkedTaskBase<LvcProjectService.TrackingOverlay>
{
    private static final long OVERLAY_BUILD_BUDGET_NANOS = 6_000_000L;

    private final Path repositoryDirectory;
    private final String projectName;
    @Nullable private final ClientLevel clientLevel;
    @Nullable private final ICompletionListener completionListener;
    private final boolean startVerifier;
    private final boolean forceRebuild;
    @Nullable private LvcManifest manifest;
    @Nullable private LvcLocalState localState;
    @Nullable private LvcLocalState.SitePlacement placementState;
    @Nullable private String siteId;
    @Nullable private String overlayName;
    @Nullable private ServerLevel lootPreviewWorld;
    @Nullable private LvcSemanticSchematicBuilder.BuildSession buildSession;
    @Nullable private LvcProjectService.TrackingOverlay overlay;
    @Nullable private LitematicaSchematic cachedSchematic;
    @Nullable private Path cacheFile;
    @Nullable private Git git;
    @Nullable private RevWalk revWalk;
    @Nullable private RevCommit sourceCommit;
    private boolean committedHeadSource;
    private boolean yieldAfterStep;
    private Phase phase = Phase.BUILD;

    public LvcSemanticOverlayTask(LvcOperationHandle handle, Path repositoryDirectory, String projectName,
                                  @Nullable ClientLevel clientLevel, @Nullable ICompletionListener completionListener,
                                  boolean startVerifier,
                                  LvcTaskCallbacks<LvcProjectService.TrackingOverlay> callbacks)
    {
        this(handle, repositoryDirectory, projectName, clientLevel, completionListener, startVerifier, false, callbacks);
    }

    public LvcSemanticOverlayTask(LvcOperationHandle handle, Path repositoryDirectory, String projectName,
                                  @Nullable ClientLevel clientLevel, @Nullable ICompletionListener completionListener,
                                  boolean startVerifier, boolean forceRebuild,
                                  LvcTaskCallbacks<LvcProjectService.TrackingOverlay> callbacks)
    {
        super(handle, "LVC Load Overlay", callbacks, true, OVERLAY_BUILD_BUDGET_NANOS);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.projectName = Objects.requireNonNull(projectName, "projectName");
        this.clientLevel = clientLevel;
        this.completionListener = completionListener;
        this.startVerifier = startVerifier;
        this.forceRebuild = forceRebuild;
    }

    @Override
    public void init()
    {
        try
        {
            LvcTrackingOverlayService.removeTrackingOverlay(this.repositoryDirectory);
            this.localState = LvcSemanticRepository.readLocalState(this.repositoryDirectory);
            this.committedHeadSource = this.shouldUseCommittedHeadSource();
            this.manifest = this.committedHeadSource ? this.readCommittedHeadManifest() :
                    LvcSemanticRepository.readManifest(this.repositoryDirectory);
            this.siteId = this.localState.activeSite();
            this.placementState = this.localState.sites().get(this.siteId);

            if (this.placementState == null)
            {
                throw new IllegalStateException("Missing local placement for active LVC site: " + this.siteId);
            }

            this.overlayName = LvcTrackingOverlayService.trackingOverlayDisplayName(this.repositoryDirectory, this.manifest.name());
            this.lootPreviewWorld = LvcTrackingOverlayService.resolveLootPreviewWorld(this.clientLevel, this.placementState);

            if (!this.forceRebuild && !this.committedHeadSource && LvcTrackingOverlayService.isSemanticTrackingCacheCurrent(this.repositoryDirectory))
            {
                this.phase = Phase.LOAD_CACHE;
            }
            else
            {
                this.buildSession = LvcSemanticSchematicBuilder.beginSchematicBuild(
                        this.manifest,
                        this.localState,
                        this.siteId,
                        this::readSourceObject,
                        this.lootPreviewWorld
                );
                this.phase = Phase.BUILD;
            }
            me.zly2006.lvc.LvcDiagnostics.debug(this.handle(), "semantic overlay initialized source={} head='{}' site={} chunks={} forceRebuild={}",
                    this.committedHeadSource ? "committed-head" : "working-tree",
                    this.sourceCommit == null ? "<working-tree>" : this.sourceCommit.getName(),
                    this.siteId,
                    this.buildSession == null ? 0 : this.buildSession.totalChunks(),
                    this.forceRebuild);

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
        this.yieldAfterStep = false;

        if (this.phase == Phase.LOAD_CACHE)
        {
            long started = Util.getNanos();
            LitematicaSchematic schematic = LvcTrackingOverlayService.reloadLitematicaSchematic(
                    LvcTrackingOverlayService.semanticTrackingCachePath(this.repositoryDirectory)
            );
            this.yieldAfterStep = true;

            if (schematic == null)
            {
                this.phase = Phase.BUILD;
                this.buildSession = LvcSemanticSchematicBuilder.beginSchematicBuild(
                        this.requireManifest(),
                        this.requireLocalState(),
                        this.requireSiteId(),
                        this::readSourceObject,
                        this.lootPreviewWorld
                );
                return false;
            }

            schematic.getMetadata().setName(this.requireOverlayName());
            this.cachedSchematic = schematic;
            this.phase = Phase.ADD_OVERLAY;
            me.zly2006.lvc.LvcDiagnostics.debug(this.handle(), "semantic overlay cache loaded repo='{}' elapsedMs={}",
                    this.repositoryDirectory, elapsedMillis(started));
            return false;
        }

        if (this.phase == Phase.BUILD)
        {
            LvcSemanticSchematicBuilder.BuildSession session = this.requireBuildSession();

            if (!session.isComplete())
            {
                session.processNextChunk();
                return false;
            }

            this.phase = Phase.WRITE_CACHE;
            this.yieldAfterStep = true;
            return false;
        }

        if (this.phase == Phase.WRITE_CACHE)
        {
            long started = Util.getNanos();
            LitematicaSchematic schematic = this.requireBuildSession().result();
            schematic.getMetadata().setName(this.requireOverlayName());
            this.cacheFile = LvcTrackingOverlayService.writeSemanticTrackingCacheFile(this.repositoryDirectory, schematic);
            this.phase = Phase.RELOAD_CACHE;
            this.yieldAfterStep = true;
            me.zly2006.lvc.LvcDiagnostics.debug(this.handle(), "semantic overlay cache written repo='{}' file='{}' elapsedMs={}",
                    this.repositoryDirectory, this.cacheFile, elapsedMillis(started));
            return false;
        }

        if (this.phase == Phase.RELOAD_CACHE)
        {
            long started = Util.getNanos();
            LitematicaSchematic reloaded = LvcTrackingOverlayService.reloadLitematicaSchematic(this.requireCacheFile());

            if (reloaded == null)
            {
                throw new IllegalStateException("Failed to reload LVC tracking schematic cache: " + this.requireCacheFile());
            }

            reloaded.getMetadata().setName(this.requireOverlayName());
            this.cachedSchematic = reloaded;
            this.phase = Phase.ADD_OVERLAY;
            this.yieldAfterStep = true;
            me.zly2006.lvc.LvcDiagnostics.debug(this.handle(), "semantic overlay cache reloaded repo='{}' file='{}' elapsedMs={}",
                    this.repositoryDirectory, this.requireCacheFile(), elapsedMillis(started));
            return false;
        }

        if (this.phase == Phase.ADD_OVERLAY)
        {
            long started = Util.getNanos();
            this.overlay = LvcTrackingOverlayService.addSemanticTrackingOverlay(
                    this.repositoryDirectory,
                    this.requireCachedSchematic(),
                    this.requirePlacementState(),
                    this.requireOverlayName(),
                    this.clientLevel,
                    this.completionListener,
                    this.startVerifier
            );
            this.phase = Phase.DONE;
            me.zly2006.lvc.LvcDiagnostics.debug(this.handle(), "semantic overlay placement added repo='{}' startVerifier={} elapsedMs={}",
                    this.repositoryDirectory, this.startVerifier, elapsedMillis(started));
            return true;
        }

        return true;
    }

    @Override
    protected boolean shouldContinueWithinTick()
    {
        return !this.yieldAfterStep;
    }

    @Override
    protected LvcProjectService.TrackingOverlay result()
    {
        return Objects.requireNonNull(this.overlay, "overlay");
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

    @Override
    protected void updateProgressHud()
    {
        this.infoHudLines.clear();
        this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);
        this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_phase", this.phase.label));

        if (this.phase == Phase.BUILD && this.buildSession != null)
        {
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks", this.buildSession.processedChunks(), this.buildSession.totalChunks()));
        }
    }

    private LvcSemanticSchematicBuilder.BuildSession requireBuildSession()
    {
        return Objects.requireNonNull(this.buildSession, "buildSession");
    }

    private LvcManifest requireManifest()
    {
        return Objects.requireNonNull(this.manifest, "manifest");
    }

    private LvcLocalState requireLocalState()
    {
        return Objects.requireNonNull(this.localState, "localState");
    }

    private LvcLocalState.SitePlacement requirePlacementState()
    {
        return Objects.requireNonNull(this.placementState, "placementState");
    }

    private LitematicaSchematic requireCachedSchematic()
    {
        return Objects.requireNonNull(this.cachedSchematic, "cachedSchematic");
    }

    private Path requireCacheFile()
    {
        return Objects.requireNonNull(this.cacheFile, "cacheFile");
    }

    private String requireSiteId()
    {
        return Objects.requireNonNull(this.siteId, "siteId");
    }

    private String requireOverlayName()
    {
        return Objects.requireNonNull(this.overlayName, "overlayName");
    }

    private boolean shouldUseCommittedHeadSource() throws Exception
    {
        return LvcProjectService.hasUncommittedChanges(this.repositoryDirectory) &&
                LvcRepository.resolveHead(this.repositoryDirectory) != null;
    }

    private LvcManifest readCommittedHeadManifest() throws Exception
    {
        this.git = Git.open(this.repositoryDirectory.toFile());
        Repository repository = this.git.getRepository();
        this.revWalk = new RevWalk(repository);
        this.sourceCommit = LvcProjectGitOps.resolveCommit(repository, this.revWalk, Constants.HEAD);
        return LvcSemanticRepository.readCommitManifest(repository, this.sourceCommit);
    }

    private byte[] readSourceObject(String objectId) throws java.io.IOException
    {
        if (!this.committedHeadSource)
        {
            return LvcChunkStore.readObject(this.repositoryDirectory, objectId);
        }

        byte[] bytes = LvcProjectGitOps.readCommitFile(this.requireRepository(), this.requireSourceCommit(),
                LvcChunkStore.objectRepositoryPath(objectId));

        if (bytes == null)
        {
            throw new java.io.IOException("Commit " + this.requireSourceCommit().getName() + " is missing LVC object: " + objectId);
        }

        return bytes;
    }

    private static long elapsedMillis(long startedNanos)
    {
        return (Util.getNanos() - startedNanos) / 1_000_000L;
    }

    private Repository requireRepository()
    {
        if (this.git == null)
        {
            throw new IllegalStateException("LVC overlay task has no open Git repository");
        }

        return this.git.getRepository();
    }

    private RevCommit requireSourceCommit()
    {
        return Objects.requireNonNull(this.sourceCommit, "sourceCommit");
    }

    private enum Phase
    {
        LOAD_CACHE("load cache"),
        BUILD("build overlay"),
        WRITE_CACHE("write cache"),
        RELOAD_CACHE("reload cache"),
        ADD_OVERLAY("add overlay"),
        DONE("done");

        private final String label;

        Phase(String label)
        {
            this.label = label;
        }
    }
}
