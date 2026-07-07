package me.zly2006.lvc.task;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.semantic.LvcSemanticWorldApplier;

import fi.dy.masa.litematica.scheduler.tasks.TaskBase;
import fi.dy.masa.malilib.interfaces.ICompletionListener;

public final class LvcAuthoritativeClientSyncTask extends TaskBase
{
    private static final long MAX_SYNC_WORK_NANOS = 10_000_000L;
    private static final long MAX_TOTAL_TICK_NANOS = 50_000_000L;

    private final ServerLevel world;
    private final long[] positions;
    private final long[] renderSections;
    private int nextPosition;

    LvcAuthoritativeClientSyncTask(ServerLevel world, LongOpenHashSet positions)
    {
        this.world = world;
        this.positions = toArray(positions);
        this.renderSections = renderSections(this.positions);
        this.name = "LVC Sync Client State";
    }

    public static void schedule(ServerLevel world, LongOpenHashSet positions)
    {
        schedule(world, positions, null);
    }

    public static boolean schedule(ServerLevel world, LongOpenHashSet positions, ICompletionListener completionListener)
    {
        if (!positions.isEmpty())
        {
            LvcAuthoritativeClientSyncTask task = new LvcAuthoritativeClientSyncTask(world, positions);
            task.setCompletionListener(completionListener);
            LvcTaskScheduling.scheduleForWorld(world, task);
            LvcDiagnostics.debug("LVC authoritative client sync scheduled dimension='{}' positions={} tickAware=true",
                    world.dimension().identifier(), positions.size());
            return true;
        }

        return false;
    }

    @Override
    public boolean execute(ProfilerFiller profiler)
    {
        long deadline = this.executionDeadlineNanos();

        if (Util.getNanos() >= deadline)
        {
            return false;
        }

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

            if (Util.getNanos() >= deadline && nextSection != currentSection)
            {
                break;
            }

            currentSection = nextSection;
        }
        while (true);

        this.finished = this.nextPosition >= this.positions.length;

        if (this.finished)
        {
            this.scheduleClientRenderRefresh();
            LvcDiagnostics.debug("LVC authoritative client sync complete dimension='{}' positions={}",
                    this.world.dimension().identifier(), this.positions.length);
        }

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

    private static long[] renderSections(long[] positions)
    {
        LongOpenHashSet sections = new LongOpenHashSet();

        for (long packedPos : positions)
        {
            sections.add(sectionKey(packedPos));
        }

        long[] values = new long[sections.size()];
        LongIterator iterator = sections.iterator();
        int index = 0;

        while (iterator.hasNext())
        {
            values[index] = iterator.nextLong();
            index++;
        }

        LongArrays.quickSort(values);
        return values;
    }

    private void scheduleClientRenderRefresh()
    {
        if (this.renderSections.length == 0)
        {
            return;
        }

        ResourceKey<Level> dimension = this.world.dimension();
        long[] sections = this.renderSections.clone();
        Minecraft minecraft = Minecraft.getInstance();

        minecraft.execute(() -> refreshClientRenderSections(minecraft, dimension, sections));
        LvcDiagnostics.debug("LVC authoritative client render refresh queued dimension='{}' sections={}",
                dimension.identifier(), sections.length);
    }

    private static void refreshClientRenderSections(Minecraft minecraft, ResourceKey<Level> dimension, long[] sections)
    {
        ClientLevel clientLevel = minecraft.level;

        if (clientLevel == null || !dimension.equals(clientLevel.dimension()))
        {
            LvcDiagnostics.debug("LVC authoritative client render refresh skipped dimension='{}' sections={} reason='client level unavailable or different dimension'",
                    dimension.identifier(), sections.length);
            return;
        }

        for (long section : sections)
        {
            minecraft.levelRenderer.setSectionDirtyWithNeighbors(
                    SectionPos.x(section),
                    SectionPos.y(section),
                    SectionPos.z(section));
        }

        minecraft.levelRenderer.needsUpdate();
        LvcDiagnostics.debug("LVC authoritative client render refresh complete dimension='{}' sections={}",
                dimension.identifier(), sections.length);
    }

    private long executionDeadlineNanos()
    {
        long now = Util.getNanos();
        long vanillaTickTime = this.world.getServer().getTickTimesNanos()[this.world.getServer().getTickCount() % 100];
        long tickHeadroom = Math.max(0L, MAX_TOTAL_TICK_NANOS - vanillaTickTime);
        return now + Math.min(MAX_SYNC_WORK_NANOS, tickHeadroom);
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
