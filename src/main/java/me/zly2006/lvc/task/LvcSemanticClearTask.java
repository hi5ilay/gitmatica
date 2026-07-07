package me.zly2006.lvc.task;

import java.nio.file.Path;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.semantic.LvcSemanticWorldApplier;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcSemanticClearTask extends LvcChunkedTaskBase<LvcProjectService.SemanticWorldClearResult>
{
    private final Path repositoryDirectory;
    private final Level world;
    @Nullable private LvcSemanticWorldApplier.ClearSession session;
    private boolean journalWritten;

    public LvcSemanticClearTask(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                LvcTaskCallbacks<LvcProjectService.SemanticWorldClearResult> callbacks)
    {
        super(handle, "LVC Clear Area", callbacks, true);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public void init()
    {
        try
        {
            LvcSemanticTaskContext.ActiveProject project = LvcSemanticTaskContext.readActiveProject(this.repositoryDirectory);
            LvcSemanticTaskContext.validatePlacementDimension(project.placement(), this.world);
            this.session = LvcSemanticWorldApplier.clearSession(project.site(), project.placement(), this.world);
            LvcDiagnostics.debug(this.handle(), "semantic clear initialized site={} dimension={} origin={} chunks={}",
                    project.siteId(), project.placement().dimension(), project.placement().origin(), this.session.totalChunks());
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
        LvcSemanticWorldApplier.ClearSession currentSession = this.requireSession();

        if (!this.journalWritten)
        {
            LvcOperationJournal.write(this.repositoryDirectory, LvcOperationJournal.Operation.CLEAR, null, "clear");
            this.journalWritten = true;
        }

        if (!currentSession.isComplete())
        {
            currentSession.processNextChunk();
        }

        return currentSession.isComplete();
    }

    @Override
    protected LvcProjectService.SemanticWorldClearResult result() throws Exception
    {
        LvcProjectService.SemanticWorldClearResult result = this.requireSession().result();
        LvcRefreshMarker.write(this.repositoryDirectory, "clear", null);
        LvcOperationJournal.delete(this.repositoryDirectory);
        return result;
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

    private LvcSemanticWorldApplier.ClearSession requireSession()
    {
        if (this.session == null)
        {
            throw new IllegalStateException("LVC clear task was not initialized");
        }

        return this.session;
    }
}
