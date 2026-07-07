package me.zly2006.lvc.task;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.capture.LvcCaptureEngine;
import me.zly2006.lvc.capture.LvcCaptureSession;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.capture.LvcSiteWorkPlan;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.semantic.LvcTrackedBlockCursor;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

final class LvcClientServerStaleScanTask extends LvcChunkedTaskBase<LvcClientServerStaleScanTask.Result>
{
    private final Path repositoryDirectory;
    private final ClientLevel clientWorld;
    private final LvcSemanticTaskContext.ActiveProject project;
    private final LvcCaptureEngine.Result serverScan;
    private final LvcSiteWorkPlan plan;
    @Nullable private LvcCaptureSession session;
    @Nullable private Set<String> staleChunkKeys;
    private final Long2ObjectOpenHashMap<String> clientStates = new Long2ObjectOpenHashMap<>();
    private int nextCollectChunkIndex;
    private int unknownClientChunks;
    @Nullable private Result result;

    LvcClientServerStaleScanTask(LvcOperationHandle handle,
                                 Path repositoryDirectory,
                                 ClientLevel clientWorld,
                                 LvcSemanticTaskContext.ActiveProject project,
                                 LvcCaptureEngine.Result serverScan,
                                 LvcTaskCallbacks<Result> callbacks,
                                 boolean releaseLockOnSuccess)
    {
        super(handle, "LVC Compare Client State", callbacks, releaseLockOnSuccess, LITEMATICA_VERIFIER_BUDGET_NANOS);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.clientWorld = Objects.requireNonNull(clientWorld, "clientWorld");
        this.project = Objects.requireNonNull(project, "project");
        this.serverScan = Objects.requireNonNull(serverScan, "serverScan");
        this.plan = LvcSiteWorkPlan.create(project.site(), project.placement());
    }

    @Override
    public void init()
    {
        try
        {
            LvcSemanticTaskContext.validatePlacementDimension(this.project.placement(), this.clientWorld);
            this.session = new LvcCaptureSession(
                    this.plan,
                    new LvcMinecraftWorldReader(this.clientWorld),
                    null,
                    true,
                    false
            );
            LvcDiagnostics.debug(this.handle(), "three-way scan client phase initialized repo='{}' site={} dimension={} chunks={} trackedBlocks={}",
                    this.repositoryDirectory, this.project.siteId(), this.project.placement().dimension(),
                    this.plan.chunkCount(), this.plan.blockCount());
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

        if (this.staleChunkKeys == null)
        {
            LvcCaptureEngine.Result clientScan = currentSession.result();
            this.unknownClientChunks = clientScan.unknownChunks().size();
            this.staleChunkKeys = staleChunkKeys(this.serverScan, clientScan);
            LvcDiagnostics.debug(this.handle(), "three-way scan client hash compare repo='{}' site={} staleChunks={} unknownClientChunks={}",
                    this.repositoryDirectory, this.project.siteId(), this.staleChunkKeys.size(), this.unknownClientChunks);
            return false;
        }

        while (this.nextCollectChunkIndex < this.plan.chunks().size())
        {
            LvcSiteWorkPlan.ChunkWork work = this.plan.chunks().get(this.nextCollectChunkIndex);
            this.nextCollectChunkIndex++;

            if (this.staleChunkKeys.contains(work.coordinate().key()))
            {
                this.collectClientStates(work);
                return false;
            }
        }

        this.result = new Result(this.project, this.serverScan, new Long2ObjectOpenHashMap<>(this.clientStates),
                this.staleChunkKeys.size(), this.unknownClientChunks);
        LvcDiagnostics.debug(this.handle(), "three-way scan client phase complete repo='{}' site={} staleChunks={} staleClientStates={} unknownClientChunks={}",
                this.repositoryDirectory, this.project.siteId(), this.result.staleChunks(), this.result.clientStates().size(),
                this.result.unknownClientChunks());
        return true;
    }

    @Override
    protected Result result()
    {
        return Objects.requireNonNull(this.result, "result");
    }

    @Override
    protected void updateProgressHud()
    {
        this.infoHudLines.clear();
        this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);

        if (this.session != null && this.staleChunkKeys == null)
        {
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks",
                    this.session.processedChunks(), this.session.totalChunks()));
        }
        else if (this.staleChunkKeys != null)
        {
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks",
                    Math.min(this.nextCollectChunkIndex, this.plan.chunkCount()), this.plan.chunkCount()));
        }
    }

    private void collectClientStates(LvcSiteWorkPlan.ChunkWork work)
    {
        LvcMinecraftWorldReader reader = new LvcMinecraftWorldReader(this.clientWorld);

        for (LvcTrackedBlockCursor.Position tracked : LvcTrackedBlockCursor.positions(work.coordinate(), this.plan.origin(), work.mask(),
                LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE))
        {
            if (reader.canReadAt(tracked.worldPos()))
            {
                this.clientStates.put(tracked.blockPos().asLong(), reader.blockStateAt(tracked.worldPos()));
            }
        }
    }

    private LvcCaptureSession requireSession()
    {
        if (this.session == null)
        {
            throw new IllegalStateException("LVC client stale scan task was not initialized");
        }

        return this.session;
    }

    private static Set<String> staleChunkKeys(LvcCaptureEngine.Result serverScan, LvcCaptureEngine.Result clientScan)
    {
        Set<String> keys = new TreeSet<>();
        keys.addAll(serverScan.trackedHashes().keySet());
        keys.addAll(clientScan.trackedHashes().keySet());
        keys.removeAll(serverScan.unknownChunks());
        keys.removeAll(clientScan.unknownChunks());

        Set<String> stale = new TreeSet<>();

        for (String key : keys)
        {
            if (!Objects.equals(serverScan.trackedHashes().get(key), clientScan.trackedHashes().get(key)))
            {
                stale.add(key);
            }
        }

        return stale;
    }

    record Result(LvcSemanticTaskContext.ActiveProject project,
                  LvcCaptureEngine.Result serverScan,
                  Long2ObjectOpenHashMap<String> clientStates,
                  int staleChunks,
                  int unknownClientChunks)
    {
        Result
        {
            clientStates = new Long2ObjectOpenHashMap<>(clientStates);
        }

        @Override
        public Long2ObjectOpenHashMap<String> clientStates()
        {
            return new Long2ObjectOpenHashMap<>(this.clientStates);
        }
    }
}
