package me.zly2006.lvc.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import fi.dy.masa.litematica.mixin.server.IMixinServerLevel;
import fi.dy.masa.litematica.mixin.world.IMixinLevelTicks;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import me.zly2006.lvc.capture.LvcCapturePlanner;
import me.zly2006.lvc.capture.LvcSiteWorkPlan;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcIntPosition;

public final class LvcWorldFreezeService
{
    private static final java.util.IdentityHashMap<ServerLevel, ServerFreeze> SERVER_FREEZES = new java.util.IdentityHashMap<>();
    private static final java.util.IdentityHashMap<LevelTicks<?>, TickFreeze<?>> TICK_FREEZES = new java.util.IdentityHashMap<>();

    private LvcWorldFreezeService()
    {
    }

    public static FreezeHandle freeze(ServerLevel world, LvcSiteWorkPlan plan)
    {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(plan, "plan");

        if (SERVER_FREEZES.containsKey(world))
        {
            throw new IllegalStateException("LVC world freeze is already active for this world");
        }

        FreezeRegion region = FreezeRegion.create(plan);
        ServerFreeze serverFreeze = new ServerFreeze(world, region);

        try
        {
            SERVER_FREEZES.put(world, serverFreeze);
            serverFreeze.blockTicks = registerTickFreeze(world.getBlockTicks(), region);
            serverFreeze.fluidTicks = registerTickFreeze(world.getFluidTicks(), region);
            serverFreeze.clearQueuedBlockEvents();
            return new FreezeHandle(world, serverFreeze);
        }
        catch (RuntimeException e)
        {
            SERVER_FREEZES.remove(world);
            TICK_FREEZES.remove(world.getBlockTicks());
            TICK_FREEZES.remove(world.getFluidTicks());
            serverFreeze.replaySuppressedWork();
            throw e;
        }
    }

    public static <T> boolean suppressScheduledTick(LevelTicks<T> scheduler, ScheduledTick<T> tick)
    {
        @SuppressWarnings("unchecked")
        TickFreeze<T> freeze = (TickFreeze<T>) TICK_FREEZES.get(scheduler);

        return freeze != null && freeze.suppress(tick);
    }

    public static boolean suppressBlockEvent(ServerLevel world, BlockPos pos, Block block, int paramA, int paramB)
    {
        ServerFreeze freeze = SERVER_FREEZES.get(world);

        if (freeze == null || !freeze.region.contains(pos))
        {
            return false;
        }

        freeze.suppressBlockEvent(new BlockEventData(pos.immutable(), block, paramA, paramB));
        return true;
    }

    public static boolean shouldSkipBlockEntityTick(Level world, TickingBlockEntity ticker)
    {
        if (!(world instanceof ServerLevel serverLevel) || ticker == null)
        {
            return false;
        }

        ServerFreeze freeze = SERVER_FREEZES.get(serverLevel);

        if (freeze == null || !freeze.region.contains(ticker.getPos()))
        {
            return false;
        }

        freeze.skippedBlockEntityTicks++;
        return true;
    }

    public static boolean shouldSkipRandomTick(ServerLevel world, BlockPos pos)
    {
        ServerFreeze freeze = SERVER_FREEZES.get(world);

        if (freeze == null || freeze.region.containsNearby(pos) == false)
        {
            return false;
        }

        freeze.skippedRandomTicks++;
        return true;
    }

    private static <T> TickFreeze<T> registerTickFreeze(LevelTicks<T> ticks, FreezeRegion region)
    {
        if (TICK_FREEZES.containsKey(ticks))
        {
            throw new IllegalStateException("LVC tick freeze is already active for this scheduler");
        }

        TickFreeze<T> freeze = new TickFreeze<>(region);
        TICK_FREEZES.put(ticks, freeze);
        clearQueuedTicks(ticks, freeze);
        return freeze;
    }

    private static <T> void clearQueuedTicks(LevelTicks<T> ticks, TickFreeze<T> freeze)
    {
        Long2ObjectMap<LevelChunkTicks<T>> containers = ((IMixinLevelTicks<T>) ticks).litematica_getChunkTickSchedulers();
        LongIterator iterator = freeze.region.realChunkKeys.iterator();

        while (iterator.hasNext())
        {
            LevelChunkTicks<T> chunkTicks = containers.get(iterator.nextLong());

            if (chunkTicks != null)
            {
                chunkTicks.removeIf(freeze::suppress);
            }
        }
    }

    private static void release(ServerLevel world, ServerFreeze serverFreeze)
    {
        SERVER_FREEZES.remove(world);
        TICK_FREEZES.remove(world.getBlockTicks());
        TICK_FREEZES.remove(world.getFluidTicks());

        if (!serverFreeze.discardSuppressedWork)
        {
            serverFreeze.replaySuppressedWork();
        }
    }

    public static final class FreezeHandle implements AutoCloseable
    {
        private final ServerLevel world;
        private final ServerFreeze serverFreeze;
        private boolean closed;

        private FreezeHandle(ServerLevel world, ServerFreeze serverFreeze)
        {
            this.world = world;
            this.serverFreeze = serverFreeze;
        }

        @Override
        public void close()
        {
            if (!this.closed)
            {
                release(this.world, this.serverFreeze);
                this.closed = true;
            }
        }

        public void discardSuppressedWork()
        {
            this.serverFreeze.discardSuppressedWork = true;
        }

        public int suppressedBlockEvents()
        {
            return this.serverFreeze.suppressedBlockEvents.size();
        }

        public int skippedBlockEntityTicks()
        {
            return this.serverFreeze.skippedBlockEntityTicks;
        }

        public int skippedRandomTicks()
        {
            return this.serverFreeze.skippedRandomTicks;
        }
    }

    private static final class ServerFreeze
    {
        private final ServerLevel world;
        private final FreezeRegion region;
        private final List<BlockEventData> suppressedBlockEvents = new ArrayList<>();
        private int skippedBlockEntityTicks;
        private int skippedRandomTicks;
        private boolean discardSuppressedWork;
        private TickFreeze<Block> blockTicks;
        private TickFreeze<Fluid> fluidTicks;

        private ServerFreeze(ServerLevel world, FreezeRegion region)
        {
            this.world = world;
            this.region = region;
        }

        private void clearQueuedBlockEvents()
        {
            ObjectLinkedOpenHashSet<BlockEventData> blockEvents = ((IMixinServerLevel) this.world).lvc_getBlockEvents();
            blockEvents.removeIf(event ->
            {
                if (!this.region.contains(event.pos()))
                {
                    return false;
                }

                this.suppressBlockEvent(event);
                return true;
            });
        }

        private void suppressBlockEvent(BlockEventData event)
        {
            this.suppressedBlockEvents.add(event);
        }

        private void replaySuppressedWork()
        {
            if (this.blockTicks != null)
            {
                this.blockTicks.replay(this.world.getBlockTicks());
            }

            if (this.fluidTicks != null)
            {
                this.fluidTicks.replay(this.world.getFluidTicks());
            }

            for (BlockEventData event : this.suppressedBlockEvents)
            {
                this.world.blockEvent(event.pos(), event.block(), event.paramA(), event.paramB());
            }
        }
    }

    private static final class TickFreeze<T>
    {
        private final FreezeRegion region;
        private final List<ScheduledTick<T>> suppressedTicks = new ArrayList<>();

        private TickFreeze(FreezeRegion region)
        {
            this.region = region;
        }

        private boolean suppress(ScheduledTick<T> tick)
        {
            if (!this.region.contains(tick.pos()))
            {
                return false;
            }

            this.suppressedTicks.add(tick);
            return true;
        }

        private void replay(LevelTicks<T> ticks)
        {
            for (ScheduledTick<T> tick : this.suppressedTicks)
            {
                ticks.schedule(tick);
            }
        }
    }

    private static final class FreezeRegion
    {
        private final LongOpenHashSet blockPositions;
        private final LongOpenHashSet realChunkKeys;

        private FreezeRegion(LongOpenHashSet blockPositions, LongOpenHashSet realChunkKeys)
        {
            this.blockPositions = blockPositions;
            this.realChunkKeys = realChunkKeys;
        }

        private static FreezeRegion create(LvcSiteWorkPlan plan)
        {
            LongOpenHashSet blockPositions = new LongOpenHashSet(Math.max(16, plan.blockCount()));
            LongOpenHashSet realChunkKeys = new LongOpenHashSet(Math.max(16, plan.chunkCount()));
            LvcIntPosition origin = plan.origin();

            for (LvcSiteWorkPlan.ChunkWork work : plan.chunks())
            {
                java.util.BitSet mask = work.mask();

                for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1))
                {
                    LvcIntPosition projectPos = LvcCapturePlanner.projectPosition(work.coordinate(), index,
                            LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE);
                    LvcIntPosition worldPos = origin.offset(projectPos);
                    blockPositions.add(BlockPos.asLong(worldPos.x(), worldPos.y(), worldPos.z()));
                    realChunkKeys.add(ChunkPos.pack(
                            SectionPos.blockToSectionCoord(worldPos.x()),
                            SectionPos.blockToSectionCoord(worldPos.z())
                    ));
                }
            }

            return new FreezeRegion(blockPositions, realChunkKeys);
        }

        private boolean contains(BlockPos pos)
        {
            return this.blockPositions.contains(pos.asLong());
        }

        private boolean containsNearby(BlockPos pos)
        {
            for (int x = -1; x <= 1; x++)
            {
                for (int y = -1; y <= 1; y++)
                {
                    for (int z = -1; z <= 1; z++)
                    {
                        if (this.blockPositions.contains(BlockPos.asLong(pos.getX() + x, pos.getY() + y, pos.getZ() + z)))
                        {
                            return true;
                        }
                    }
                }
            }

            return false;
        }
    }
}
