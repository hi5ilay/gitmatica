package me.zly2006.lvc.task;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import me.zly2006.lvc.semantic.LvcSemanticWorldApplier;

import fi.dy.masa.litematica.scheduler.tasks.TaskBase;

public final class LvcAuthoritativeClientSyncTask extends TaskBase
{
    private static final long BUDGET_NANOS = 10_000_000L;

    private final ServerLevel world;
    private final long[] positions;
    private int nextPosition;

    LvcAuthoritativeClientSyncTask(ServerLevel world, LongOpenHashSet positions)
    {
        this.world = world;
        this.positions = toArray(positions);
        this.name = "LVC Sync Client State";
    }

    public static void schedule(ServerLevel world, LongOpenHashSet positions)
    {
        if (!positions.isEmpty())
        {
            LvcTaskScheduling.scheduleForWorld(world, new LvcAuthoritativeClientSyncTask(world, positions));
        }
    }

    @Override
    public boolean execute(ProfilerFiller profiler)
    {
        long deadline = System.nanoTime() + BUDGET_NANOS;
        long currentSection = Long.MIN_VALUE;

        do
        {
            long packedPos = this.positions[this.nextPosition];
            BlockPos pos = BlockPos.of(packedPos);

            if (this.world.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())))
            {
                LvcSemanticWorldApplier.syncRestoredBlock(this.world, pos);
            }

            this.nextPosition++;

            if (this.nextPosition >= this.positions.length)
            {
                break;
            }

            long nextSection = sectionKey(this.positions[this.nextPosition]);

            if (currentSection == Long.MIN_VALUE)
            {
                currentSection = sectionKey(packedPos);
            }

            if (System.nanoTime() >= deadline && nextSection != currentSection)
            {
                break;
            }

            currentSection = nextSection;
        }
        while (true);

        this.finished = this.nextPosition >= this.positions.length;
        return this.finished;
    }

    @Override
    public boolean shouldRemove()
    {
        return this.finished || super.shouldRemove();
    }

    private static long[] toArray(LongOpenHashSet positions)
    {
        long[] values = new long[positions.size()];
        LongIterator iterator = positions.iterator();
        int index = 0;

        while (iterator.hasNext())
        {
            values[index] = iterator.nextLong();
            index++;
        }

        LongArrays.quickSort(values, LvcAuthoritativeClientSyncTask::compareBySection);
        return values;
    }

    private static int compareBySection(long left, long right)
    {
        int compared = Integer.compare(sectionX(left), sectionX(right));

        if (compared != 0)
        {
            return compared;
        }

        compared = Integer.compare(sectionZ(left), sectionZ(right));

        if (compared != 0)
        {
            return compared;
        }

        compared = Integer.compare(sectionY(left), sectionY(right));

        if (compared != 0)
        {
            return compared;
        }

        return Long.compare(left, right);
    }

    private static long sectionKey(long packedPos)
    {
        return SectionPos.asLong(sectionX(packedPos), sectionY(packedPos), sectionZ(packedPos));
    }

    private static int sectionX(long packedPos)
    {
        return SectionPos.blockToSectionCoord(BlockPos.getX(packedPos));
    }

    private static int sectionY(long packedPos)
    {
        return SectionPos.blockToSectionCoord(BlockPos.getY(packedPos));
    }

    private static int sectionZ(long packedPos)
    {
        return SectionPos.blockToSectionCoord(BlockPos.getZ(packedPos));
    }
}
