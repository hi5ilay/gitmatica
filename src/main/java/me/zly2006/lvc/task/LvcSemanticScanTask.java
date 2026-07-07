package me.zly2006.lvc.task;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.capture.LvcCaptureEngine;
import me.zly2006.lvc.capture.LvcCaptureSession;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.capture.LvcSiteWorkPlan;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcSemanticScanTask extends LvcChunkedTaskBase<LvcProjectService.SemanticScanResult>
{
    private static final int MISMATCH_SAMPLE_LIMIT = 8;

    private final Path repositoryDirectory;
    private final Level world;
    @Nullable private LvcSemanticTaskContext.ActiveProject project;
    @Nullable private LvcCaptureSession session;
    @Nullable private LvcCaptureEngine.Result scanResult;
    @Nullable private LvcProjectService.SemanticScanResult result;

    public LvcSemanticScanTask(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                               LvcTaskCallbacks<LvcProjectService.SemanticScanResult> callbacks,
                               boolean releaseLockOnSuccess)
    {
        super(handle, "LVC Scan Changes", callbacks, releaseLockOnSuccess, LITEMATICA_VERIFIER_BUDGET_NANOS);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public void init()
    {
        try
        {
            this.project = LvcSemanticTaskContext.readActiveProject(this.repositoryDirectory);
            LvcSemanticTaskContext.validatePlacementDimension(this.project.placement(), this.world);
            this.session = new LvcCaptureSession(
                    LvcSiteWorkPlan.create(this.project.site(), this.project.placement()),
                    new LvcMinecraftWorldReader(this.world),
                    null,
                    true,
                    false
            );
            LvcDiagnostics.debug(this.handle(), "semantic scan initialized site={} dimension={} origin={} chunks={} budgetNanos={}",
                    this.project.siteId(), this.project.placement().dimension(), this.project.placement().origin(),
                    this.session.totalChunks(), LITEMATICA_VERIFIER_BUDGET_NANOS);
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
        LvcCaptureSession currentSession = this.requireSession();

        if (!currentSession.isComplete())
        {
            currentSession.processNextChunk();
        }

        if (!currentSession.isComplete())
        {
            return false;
        }

        LvcSemanticTaskContext.ActiveProject currentProject = Objects.requireNonNull(this.project, "project");
        LvcCaptureEngine.Result scanResult = currentSession.result();
        this.scanResult = scanResult;
        Map<String, String> expectedTrackedHashes = LvcSemanticRepository.computeTrackedHashesFromFullObjects(this.repositoryDirectory, currentProject.site());
        this.result = LvcProjectService.SemanticScanResult.compare(
                currentProject.siteId(),
                expectedTrackedHashes,
                scanResult
        );

        if (!this.result.clean() && this.result.unknownChunks() == 0)
        {
            this.result = this.result.withSamples(LvcSemanticScanMismatchSampler.sample(
                    this.repositoryDirectory,
                    currentProject.site(),
                    currentProject.placement(),
                    this.world,
                    expectedTrackedHashes,
                    scanResult,
                    MISMATCH_SAMPLE_LIMIT
            ));
        }

        LvcDiagnostics.debug(this.handle(),
                "semantic scan complete site={} knownChunks={} dirtyChunks={} changedChunks={} addedChunks={} removedChunks={} unknownChunks={} blockEntityReadAttempts={} blockEntityRecords={} samples={}",
                this.result.siteId(), this.result.knownChunks(), this.result.dirtyChunks(),
                this.result.changedChunks(), this.result.addedChunks(), this.result.removedChunks(),
                this.result.unknownChunks(), currentSession.blockEntityReadAttempts(), currentSession.blockEntityRecords(), this.result.samples().size());
        this.logMismatchSamples();
        return true;
    }

    @Override
    protected LvcProjectService.SemanticScanResult result()
    {
        return Objects.requireNonNull(this.result, "result");
    }

    LvcSemanticTaskContext.ActiveProject activeProject()
    {
        return Objects.requireNonNull(this.project, "project");
    }

    LvcCaptureEngine.Result scanResult()
    {
        return Objects.requireNonNull(this.scanResult, "scanResult");
    }

    @Override
    protected void updateProgressHud()
    {
        this.infoHudLines.clear();

        if (this.session != null)
        {
            this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks", this.session.processedChunks(), this.session.totalChunks()));
        }
    }

    private LvcCaptureSession requireSession()
    {
        if (this.session == null)
        {
            throw new IllegalStateException("LVC scan task was not initialized");
        }

        return this.session;
    }

    private void logMismatchSamples()
    {
        LvcProjectService.SemanticScanResult currentResult = Objects.requireNonNull(this.result, "result");

        if (currentResult.samples().isEmpty())
        {
            return;
        }

        for (LvcProjectService.SemanticScanMismatch sample : currentResult.samples())
        {
            LvcDiagnostics.info(this.handle(), "semantic scan mismatch sample: {}", sample.summary());
        }
    }
}
