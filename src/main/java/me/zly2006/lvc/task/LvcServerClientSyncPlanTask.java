package me.zly2006.lvc.task;

import java.nio.file.Path;
import java.util.Objects;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

final class LvcServerClientSyncPlanTask extends LvcChunkedTaskBase<LvcServerClientSyncPlanTask.Result>
{
    private final Path repositoryDirectory;
    private final ServerLevel serverWorld;
    private final String siteId;
    private final Long2ObjectOpenHashMap<String> clientStates;
    private final long[] positions;
    private final LongOpenHashSet syncPositions = new LongOpenHashSet();
    private int nextPosition;
    @Nullable private Result result;

    LvcServerClientSyncPlanTask(LvcOperationHandle handle,
                                Path repositoryDirectory,
                                ServerLevel serverWorld,
                                String siteId,
                                Long2ObjectOpenHashMap<String> clientStates,
                                LvcTaskCallbacks<Result> callbacks,
                                boolean releaseLockOnSuccess)
    {
        super(handle, "LVC Plan Client Sync", callbacks, releaseLockOnSuccess);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.serverWorld = Objects.requireNonNull(serverWorld, "serverWorld");
        this.siteId = Objects.requireNonNull(siteId, "siteId");
        this.clientStates = new Long2ObjectOpenHashMap<>(Objects.requireNonNull(clientStates, "clientStates"));
        this.positions = this.clientStates.keySet().toLongArray();
        LongArrays.quickSort(this.positions);
    }

    @Override
    public void init()
    {
        LvcDiagnostics.debug(this.handle(), "three-way scan exact sync compare initialized repo='{}' site={} clientPositions={}",
                this.repositoryDirectory, this.siteId, this.positions.length);
        this.updateProgressHud();
    }

    @Override
    protected boolean step()
    {
        if (this.nextPosition >= this.positions.length)
        {
            this.result = new Result(new LongOpenHashSet(this.syncPositions), this.positions.length);
            LvcDiagnostics.info(this.handle(), "three-way scan exact sync compare complete repo='{}' site={} checkedPositions={} syncPositions={}",
                    this.repositoryDirectory, this.siteId, this.result.checkedPositions(), this.result.syncPositions().size());
            return true;
        }

        long packedPos = this.positions[this.nextPosition];
        this.nextPosition++;
        BlockPos pos = BlockPos.of(packedPos);

        if (this.serverWorld.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())))
        {
            String serverState = LvcMinecraftWorldReader.blockStateString(this.serverWorld.getBlockState(pos));
            String clientState = this.clientStates.get(packedPos);

            if (!Objects.equals(serverState, clientState))
            {
                this.syncPositions.add(packedPos);
            }
        }

        return false;
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
        this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks",
                this.nextPosition, this.positions.length));
    }

    record Result(LongOpenHashSet syncPositions, int checkedPositions)
    {
        Result
        {
            syncPositions = new LongOpenHashSet(syncPositions);
        }

        @Override
        public LongOpenHashSet syncPositions()
        {
            return new LongOpenHashSet(this.syncPositions);
        }
    }
}
