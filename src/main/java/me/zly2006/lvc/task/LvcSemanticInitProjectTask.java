package me.zly2006.lvc.task;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.world.level.Level;
import org.eclipse.jgit.revwalk.RevCommit;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcPlayerIdentity;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.capture.LvcCaptureEngine;
import me.zly2006.lvc.capture.LvcCaptureSession;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.capture.LvcSiteWorkPlan;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.project.LvcProjectPaths;
import me.zly2006.lvc.project.LvcProjectSelectionStorage;
import me.zly2006.lvc.storage.LvcChunkCodec;
import me.zly2006.lvc.storage.LvcChunkStagingStore;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcSemanticInitProjectTask extends LvcChunkedTaskBase<LvcProjectService.Result>
{
    private final Path gameRunDirectory;
    private final String repositoryName;
    private final LvcPlayerIdentity player;
    private final Level world;
    private final AreaSelection selection;
    @Nullable private Path repositoryDirectory;
    @Nullable private LvcManifest.Site site;
    @Nullable private LvcLocalState.SitePlacement placement;
    @Nullable private LvcChunkStagingStore stagingStore;
    @Nullable private LvcCaptureSession captureSession;
    @Nullable private LvcCaptureEngine.Result captureResult;
    private List<String> publishObjectIds = List.of();
    private Phase phase = Phase.CAPTURE;
    private int nextPublishIndex;
    private boolean repositoryCreated;
    private boolean committed;
    @Nullable private RevCommit commit;

    public LvcSemanticInitProjectTask(LvcOperationHandle handle, Path gameRunDirectory, String repositoryName,
                                      LvcPlayerIdentity player, Level world, AreaSelection selection,
                                      LvcTaskCallbacks<LvcProjectService.Result> callbacks)
    {
        super(handle, "LVC Create Project", callbacks, true);
        this.gameRunDirectory = Objects.requireNonNull(gameRunDirectory, "gameRunDirectory");
        this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
        this.player = Objects.requireNonNull(player, "player");
        this.world = Objects.requireNonNull(world, "world");
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    @Override
    public void init()
    {
        try
        {
            String displayName = LvcProjectPaths.normalizeDisplayName(this.repositoryName);
            int validBoxCount = LvcProjectSelectionStorage.countValidSelectionRegions(this.selection);

            if (validBoxCount <= 0)
            {
                throw new IllegalArgumentException("LVC project requires at least one valid selection box");
            }

            this.repositoryDirectory = LvcProjectPaths.repositoryDirectory(this.gameRunDirectory, displayName);

            if (Files.exists(this.repositoryDirectory))
            {
                if (this.cleanupInterruptedInitDirectory(this.repositoryDirectory))
                {
                    Files.createDirectories(this.repositoryDirectory);
                }
                else
                {
                    throw new FileAlreadyExistsException(this.repositoryDirectory.toString());
                }
            }
            else
            {
                Files.createDirectories(this.repositoryDirectory);
            }

            if (!Files.isDirectory(this.repositoryDirectory))
            {
                throw new FileAlreadyExistsException(this.repositoryDirectory.toString());
            }

            this.repositoryCreated = true;

            String dimensionId = LvcMinecraftWorldReader.dimensionId(this.world);
            this.site = LvcProjectSelectionStorage.createMainSiteFromSelection(displayName, dimensionId, this.selection);
            this.placement = LvcProjectSelectionStorage.createSitePlacement(this.selection.getEffectiveOrigin(), dimensionId);
            LvcDiagnostics.debug(this.handle(), "semantic init prepared repo='{}' project='{}' dimension='{}' regions={} origin='{}'",
                    this.repositoryDirectory, displayName, dimensionId, validBoxCount, this.placement.origin());
            this.stagingStore = new LvcChunkStagingStore(
                    this.repositoryDirectory,
                    LvcOperationJournal.stagingDirectory(this.repositoryDirectory, this.handle())
            );
            this.captureSession = new LvcCaptureSession(
                    LvcSiteWorkPlan.create(this.site, this.placement),
                    new LvcMinecraftWorldReader(this.world),
                    this.stagingStore::writeObject,
                    false,
                    true
            );
            LvcOperationJournal.write(this.repositoryDirectory, LvcOperationJournal.Operation.INIT, null, "capture");
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
        if (this.phase == Phase.CAPTURE)
        {
            LvcCaptureSession session = this.requireCaptureSession();

            if (!session.isComplete())
            {
                session.processNextChunk();
                return false;
            }

            this.captureResult = session.result();
            this.publishObjectIds = new ArrayList<>(this.captureResult.fullHashes().values());
            LvcDiagnostics.debug(this.handle(), "semantic init capture completed chunks={} objects={} canonicalBytes={} storedBytes={} blockEntityReadAttempts={} blockEntityRecords={} chunkFormat={} compressionLevel={}",
                    session.totalChunks(), this.publishObjectIds.size(), session.fullHashContentBytes(), session.storedObjectBytes(),
                    session.blockEntityReadAttempts(), session.blockEntityRecords(),
                    LvcManifest.CHUNK_FORMAT, LvcChunkCodec.COMPRESSION_LEVEL);
            this.phase = Phase.PUBLISH;
            return false;
        }

        if (this.phase == Phase.PUBLISH)
        {
            if (this.nextPublishIndex < this.publishObjectIds.size())
            {
                this.requireStagingStore().publish(this.publishObjectIds.get(this.nextPublishIndex));
                this.nextPublishIndex++;
                return false;
            }

            this.phase = Phase.COMMIT;
            return false;
        }

        if (this.phase == Phase.COMMIT)
        {
            LvcSemanticRepository.CommitResult result = LvcSemanticRepository.initProjectFromCapture(
                    this.requireRepositoryDirectory(),
                    this.requireSite().name(),
                    this.requireSite(),
                    this.requirePlacement(),
                    Objects.requireNonNull(this.captureResult, "captureResult"),
                    this.player
            );
            this.commit = result.commit();
            this.committed = true;
            this.phase = Phase.DONE;
            return true;
        }

        return true;
    }

    @Override
    protected LvcProjectService.Result result() throws Exception
    {
        this.requireStagingStore().cleanup();
        LvcOperationJournal.delete(this.requireRepositoryDirectory());
        return new LvcProjectService.Result(this.requireRepositoryDirectory(), Objects.requireNonNull(this.commit, "commit").getName());
    }

    @Override
    protected void updateProgressHud()
    {
        this.infoHudLines.clear();
        this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);
        this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_phase", this.phase.label));

        if (this.phase == Phase.CAPTURE && this.captureSession != null)
        {
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks", this.captureSession.processedChunks(), this.captureSession.totalChunks()));
        }
        else if (this.phase == Phase.PUBLISH)
        {
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_objects", this.nextPublishIndex, this.publishObjectIds.size()));
        }
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
            if (!this.committed)
            {
                this.cleanupUncommittedRepository();
            }
        }
    }

    private void cleanupUncommittedRepository()
    {
        try
        {
            if (this.stagingStore != null)
            {
                this.stagingStore.cleanup();
            }

            if (this.repositoryCreated && this.repositoryDirectory != null)
            {
                LvcOperationJournal.delete(this.repositoryDirectory);
                LvcChunkStagingStore.deleteRecursivelyIfExists(this.repositoryDirectory);
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private boolean cleanupInterruptedInitDirectory(Path directory) throws Exception
    {
        LvcOperationJournal.Entry entry = LvcOperationJournal.read(directory);

        if (entry == null || !LvcOperationJournal.Operation.INIT.name().equals(entry.operation()))
        {
            return false;
        }

        LvcChunkStagingStore.deleteRecursivelyIfExists(directory);
        return true;
    }

    private LvcCaptureSession requireCaptureSession()
    {
        return Objects.requireNonNull(this.captureSession, "captureSession");
    }

    private LvcChunkStagingStore requireStagingStore()
    {
        return Objects.requireNonNull(this.stagingStore, "stagingStore");
    }

    private Path requireRepositoryDirectory()
    {
        return Objects.requireNonNull(this.repositoryDirectory, "repositoryDirectory");
    }

    private LvcManifest.Site requireSite()
    {
        return Objects.requireNonNull(this.site, "site");
    }

    private LvcLocalState.SitePlacement requirePlacement()
    {
        return Objects.requireNonNull(this.placement, "placement");
    }

    private enum Phase
    {
        CAPTURE("capture subchunks"),
        PUBLISH("publish objects"),
        COMMIT("write commit"),
        DONE("done");

        private final String label;

        Phase(String label)
        {
            this.label = label;
        }
    }
}
