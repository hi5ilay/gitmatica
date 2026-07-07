package me.zly2006.lvc.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.LvcUserActionException;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.storage.LvcChunkCodec;
import me.zly2006.lvc.storage.LvcChunkStore;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcSemanticClearTask extends LvcChunkedTaskBase<LvcProjectService.SemanticWorldClearResult>
{
    private final Path repositoryDirectory;
    private final Level world;
    @Nullable private LvcSemanticRestoreEngine restoreEngine;
    private int regionCount;
    private boolean journalWritten;
    private boolean complete;

    public LvcSemanticClearTask(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                LvcTaskCallbacks<LvcProjectService.SemanticWorldClearResult> callbacks)
    {
        super(handle, "LVC Clear Area", callbacks, true, LITEMATICA_DIRECT_PASTE_BUDGET_NANOS);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public void init()
    {
        try
        {
            if (!(this.world instanceof ServerLevel serverWorld))
            {
                throw new LvcUserActionException(LvcUserActionException.Reason.NO_AUTHORITATIVE_WORLD,
                        "Semantic LVC clear requires server-authoritative world access");
            }

            LvcSemanticTaskContext.ActiveProject project = LvcSemanticTaskContext.readActiveProject(this.repositoryDirectory);
            LvcSemanticTaskContext.validatePlacementDimension(project.placement(), this.world);
            List<Map.Entry<String, String>> chunkRefs = List.copyOf(project.site().fullHashes().entrySet());
            this.regionCount = project.site().regions().size();
            this.restoreEngine = new LvcSemanticRestoreEngine(
                    serverWorld,
                    project.site(),
                    LvcIntPosition.fromList(project.placement().origin()),
                    chunkRefs,
                    this::readChunk,
                    this::writeJournalIfNeeded,
                    projectPos -> { },
                    LvcSemanticRestoreEngine.Options.clear());
            LvcDiagnostics.debug(this.handle(), "semantic clear initialized site={} dimension={} origin={} chunks={} convergentRestore={}",
                    project.siteId(), project.placement().dimension(), project.placement().origin(), chunkRefs.size(), true);
            this.updateProgressHud();
        }
        catch (Exception e)
        {
            this.fail(e instanceof Exception exception ? exception : new RuntimeException(e));
        }
    }

    @Override
    protected long executionDeadlineNanos()
    {
        return this.world instanceof ServerLevel serverWorld ?
                serverTickAwareDeadlineNanos(serverWorld, LITEMATICA_DIRECT_PASTE_BUDGET_NANOS) :
                super.executionDeadlineNanos();
    }

    @Override
    protected boolean shouldContinueWithinTick()
    {
        LvcSemanticRestoreEngine engine = this.restoreEngine;
        return engine == null || !engine.shouldYieldAfterStep();
    }

    @Override
    protected boolean step() throws Exception
    {
        if (!this.complete)
        {
            this.complete = this.requireRestoreEngine().processNextStep();
        }

        return this.complete;
    }

    @Override
    protected LvcProjectService.SemanticWorldClearResult result() throws Exception
    {
        LvcSemanticRestoreEngine engine = this.requireRestoreEngine();
        LvcProjectService.SemanticWorldClearResult result =
                new LvcProjectService.SemanticWorldClearResult(this.regionCount, engine.restoredBlocks());
        LvcDiagnostics.debug(this.handle(), "semantic clear complete regions={} clearedBlocks={} clearedEntities={} changedChunks={} dirtySubchunkPasses={}",
                result.regionCount(), result.clearedBlocks(), engine.clearedEntities(), engine.changedChunks(),
                engine.fullSubchunkRewritePasses());
        LvcRefreshMarker.write(this.repositoryDirectory, "clear", null);
        LvcOperationJournal.delete(this.repositoryDirectory);
        return result;
    }

    @Override
    protected void updateProgressHud()
    {
        this.infoHudLines.clear();

        if (this.restoreEngine != null)
        {
            this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_phase", this.restoreEngine.phaseLabel()));
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks",
                    this.restoreEngine.currentProgress(), this.restoreEngine.currentTotal()));
            this.infoHudLines.add("Cleared blocks: " + this.restoreEngine.restoredBlocks());
            this.infoHudLines.add("Dirty subchunk passes: " + this.restoreEngine.fullSubchunkRewritePasses());
        }
    }

    private void writeJournalIfNeeded() throws IOException
    {
        if (!this.journalWritten)
        {
            LvcOperationJournal.write(this.repositoryDirectory, LvcOperationJournal.Operation.CLEAR, null, "clear");
            this.journalWritten = true;
        }
    }

    private LvcChunk readChunk(String objectId) throws IOException
    {
        return LvcChunkCodec.decode(LvcChunkStore.readObject(this.repositoryDirectory, objectId));
    }

    private LvcSemanticRestoreEngine requireRestoreEngine()
    {
        if (this.restoreEngine == null)
        {
            throw new IllegalStateException("LVC clear task was not initialized");
        }

        return this.restoreEngine;
    }
}
