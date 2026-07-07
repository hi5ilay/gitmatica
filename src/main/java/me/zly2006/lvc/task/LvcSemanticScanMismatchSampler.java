package me.zly2006.lvc.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.capture.LvcCaptureEngine;
import me.zly2006.lvc.capture.LvcCapturePlanner;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.capture.LvcSiteWorkPlan;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcChunkCodec;
import me.zly2006.lvc.storage.LvcChunkStore;

public final class LvcSemanticScanMismatchSampler
{
    private LvcSemanticScanMismatchSampler()
    {
    }

    public static List<LvcProjectService.SemanticScanMismatch> sample(Path repositoryDirectory, LvcManifest.Site site,
                                                                      LvcLocalState.SitePlacement placement, Level world,
                                                                      Map<String, String> expectedTrackedHashes,
                                                                      LvcCaptureEngine.Result scan, int limit) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(expectedTrackedHashes, "expectedTrackedHashes");
        Objects.requireNonNull(scan, "scan");

        if (limit <= 0)
        {
            return List.of();
        }

        Set<String> dirtyKeys = dirtyKeys(expectedTrackedHashes, scan);

        if (dirtyKeys.isEmpty())
        {
            return List.of();
        }

        LvcSiteWorkPlan plan = LvcSiteWorkPlan.create(site, placement);
        List<LvcProjectService.SemanticScanMismatch> samples = new ArrayList<>();

        for (LvcSiteWorkPlan.ChunkWork work : plan.chunks())
        {
            String chunkKey = work.coordinate().key();

            if (!dirtyKeys.contains(chunkKey))
            {
                continue;
            }

            String expectedHash = expectedTrackedHashes.get(chunkKey);
            String actualHash = scan.trackedHashes().get(chunkKey);

            if (expectedHash == null || actualHash == null)
            {
                samples.add(new LvcProjectService.SemanticScanMismatch(
                        chunkKey,
                        "<chunk>",
                        expectedHash == null ? "<not tracked in HEAD>" : expectedHash,
                        actualHash == null ? "<not captured>" : actualHash
                ));
            }
            else
            {
                sampleChangedChunk(repositoryDirectory, site, plan.origin(), world, work, samples, limit);
            }

            if (samples.size() >= limit)
            {
                break;
            }
        }

        return List.copyOf(samples);
    }

    public static LongOpenHashSet mismatchedBlockStatePositions(Path repositoryDirectory, LvcManifest.Site site,
                                                               LvcLocalState.SitePlacement placement, Level world,
                                                               Map<String, String> expectedTrackedHashes,
                                                               LvcCaptureEngine.Result scan) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(expectedTrackedHashes, "expectedTrackedHashes");
        Objects.requireNonNull(scan, "scan");

        Set<String> dirtyKeys = dirtyKeys(expectedTrackedHashes, scan);
        LongOpenHashSet positions = new LongOpenHashSet();

        if (dirtyKeys.isEmpty())
        {
            return positions;
        }

        LvcSiteWorkPlan plan = LvcSiteWorkPlan.create(site, placement);

        for (LvcSiteWorkPlan.ChunkWork work : plan.chunks())
        {
            String chunkKey = work.coordinate().key();

            if (!dirtyKeys.contains(chunkKey))
            {
                continue;
            }

            if (expectedTrackedHashes.get(chunkKey) == null || scan.trackedHashes().get(chunkKey) == null)
            {
                continue;
            }

            collectChangedChunkPositions(repositoryDirectory, site, plan.origin(), world, work, positions);
        }

        return positions;
    }

    private static Set<String> dirtyKeys(Map<String, String> expectedTrackedHashes, LvcCaptureEngine.Result scan)
    {
        Set<String> keys = new TreeSet<>();
        keys.addAll(expectedTrackedHashes.keySet());
        keys.addAll(scan.trackedHashes().keySet());
        keys.removeAll(scan.unknownChunks());

        Set<String> dirtyKeys = new TreeSet<>();

        for (String key : keys)
        {
            if (!Objects.equals(expectedTrackedHashes.get(key), scan.trackedHashes().get(key)))
            {
                dirtyKeys.add(key);
            }
        }

        return dirtyKeys;
    }

    private static void sampleChangedChunk(Path repositoryDirectory, LvcManifest.Site site, LvcIntPosition origin,
                                           Level world, LvcSiteWorkPlan.ChunkWork work,
                                           List<LvcProjectService.SemanticScanMismatch> samples, int limit) throws IOException
    {
        String fullObjectId = site.fullHashes().get(work.coordinate().key());

        if (fullObjectId == null)
        {
            samples.add(new LvcProjectService.SemanticScanMismatch(
                    work.coordinate().key(),
                    "<chunk>",
                    "<missing full object ref>",
                    "<captured tracked hash differs>"
            ));
            return;
        }

        LvcChunk chunk = LvcChunkCodec.decode(LvcChunkStore.readObject(repositoryDirectory, fullObjectId));
        BitSet mask = chunk.trackedMask();
        int ordinal = 0;
        int samplesBeforeChunk = samples.size();

        for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1))
        {
            LvcIntPosition projectPos = LvcCapturePlanner.projectPosition(work.coordinate(), index,
                    chunk.sizeX(), chunk.sizeY(), chunk.sizeZ());
            LvcIntPosition worldPos = origin.offset(projectPos);
            BlockPos blockPos = new BlockPos(worldPos.x(), worldPos.y(), worldPos.z());
            String expected = LvcChunkCodec.canonicalTrackedBlockState(chunk.blockStateAtTrackedOrdinal(ordinal));
            String actual = LvcMinecraftWorldReader.blockStateString(world.getBlockState(blockPos));

            if (!Objects.equals(expected, actual))
            {
                samples.add(new LvcProjectService.SemanticScanMismatch(
                        work.coordinate().key(),
                        blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ(),
                        expected,
                        actual
                ));

                if (samples.size() >= limit)
                {
                    return;
                }
            }

            ordinal++;
        }

        if (samples.size() == samplesBeforeChunk && samples.size() < limit)
        {
            samples.add(new LvcProjectService.SemanticScanMismatch(
                    work.coordinate().key(),
                    "<chunk>",
                    "<tracked hash " + LvcChunkStore.objectId(LvcChunkCodec.encodeTrackedContent(chunk)) + ">",
                    "<captured tracked hash differs but no block-state sample was found>"
            ));
        }
    }

    private static void collectChangedChunkPositions(Path repositoryDirectory, LvcManifest.Site site, LvcIntPosition origin,
                                                     Level world, LvcSiteWorkPlan.ChunkWork work,
                                                     LongOpenHashSet positions) throws IOException
    {
        String fullObjectId = site.fullHashes().get(work.coordinate().key());

        if (fullObjectId == null)
        {
            return;
        }

        LvcChunk chunk = LvcChunkCodec.decode(LvcChunkStore.readObject(repositoryDirectory, fullObjectId));
        BitSet mask = chunk.trackedMask();
        int ordinal = 0;

        for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1))
        {
            LvcIntPosition projectPos = LvcCapturePlanner.projectPosition(work.coordinate(), index,
                    chunk.sizeX(), chunk.sizeY(), chunk.sizeZ());
            LvcIntPosition worldPos = origin.offset(projectPos);
            BlockPos blockPos = new BlockPos(worldPos.x(), worldPos.y(), worldPos.z());
            String expected = LvcChunkCodec.canonicalTrackedBlockState(chunk.blockStateAtTrackedOrdinal(ordinal));
            String actual = LvcMinecraftWorldReader.blockStateString(world.getBlockState(blockPos));

            if (!Objects.equals(expected, actual))
            {
                positions.add(blockPos.asLong());
            }

            ordinal++;
        }
    }
}
