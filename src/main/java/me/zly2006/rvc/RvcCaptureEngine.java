package me.zly2006.rvc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class RvcCaptureEngine
{
    private RvcCaptureEngine()
    {
    }

    public static Result captureSite(Path repositoryDirectory, RvcManifest.Site site, RvcLocalState.SitePlacement placement,
                                     RvcWorldReader worldReader) throws IOException
    {
        return captureSite(site, placement, worldReader, bytes -> RvcChunkStore.writeObjectIfMissing(repositoryDirectory, bytes), false);
    }

    public static Result scanSite(RvcManifest.Site site, RvcLocalState.SitePlacement placement,
                                  RvcWorldReader worldReader) throws IOException
    {
        return captureSite(site, placement, worldReader, RvcChunkStore::objectId, true);
    }

    private static Result captureSite(RvcManifest.Site site, RvcLocalState.SitePlacement placement,
                                      RvcWorldReader worldReader, ObjectIdResolver objectIdResolver,
                                      boolean allowUnknownChunks) throws IOException
    {
        RvcIntPosition origin = RvcIntPosition.fromList(placement.origin());
        Map<RvcChunkCoordinate, BitSet> plannedChunks = RvcCapturePlanner.planSite(site);
        Map<String, String> chunkObjects = new TreeMap<>();
        Set<String> unknownChunks = new TreeSet<>();

        for (Map.Entry<RvcChunkCoordinate, BitSet> entry : plannedChunks.entrySet())
        {
            RvcChunkCoordinate coordinate = entry.getKey();
            String chunkKey = coordinate.key();
            BitSet mask = entry.getValue();
            List<String> blockStates = new ArrayList<>(mask.cardinality());
            List<RvcChunk.BlockEntityRecord> blockEntities = new ArrayList<>();
            boolean unknown = false;

            for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1))
            {
                RvcIntPosition projectPos = RvcCapturePlanner.projectPosition(coordinate, index, RvcChunk.DEFAULT_SIZE, RvcChunk.DEFAULT_SIZE, RvcChunk.DEFAULT_SIZE);
                RvcIntPosition worldPos = origin.offset(projectPos);

                if (!worldReader.canReadAt(worldPos))
                {
                    if (!allowUnknownChunks)
                    {
                        throw new IOException("RVC world reader cannot read an authoritative block state at " + worldPos);
                    }

                    unknown = true;
                    break;
                }

                String blockState = worldReader.blockStateAt(worldPos);

                if (blockState == null || blockState.isBlank())
                {
                    throw new IOException("RVC world reader returned a blank block state at " + worldPos);
                }

                blockStates.add(blockState);
                byte[] blockEntityNbt = worldReader.blockEntityNbtAt(worldPos);

                if (blockEntityNbt != null)
                {
                    blockEntities.add(new RvcChunk.BlockEntityRecord(index, blockEntityNbt));
                }
            }

            if (unknown)
            {
                unknownChunks.add(chunkKey);
                continue;
            }

            RvcChunk chunk = RvcChunk.fromTrackedContent(
                    RvcChunk.DEFAULT_SIZE,
                    RvcChunk.DEFAULT_SIZE,
                    RvcChunk.DEFAULT_SIZE,
                    mask,
                    blockStates,
                    blockEntities,
                    List.of(),
                    List.of()
            );
            String objectId = objectIdResolver.resolve(RvcChunkCodec.encode(chunk));
            chunkObjects.put(chunkKey, objectId);
        }

        return new Result(Map.copyOf(chunkObjects), Set.copyOf(unknownChunks));
    }

    @FunctionalInterface
    private interface ObjectIdResolver
    {
        String resolve(byte[] bytes) throws IOException;
    }

    public record Result(Map<String, String> chunkObjects, Set<String> unknownChunks)
    {
        public Result
        {
            chunkObjects = Map.copyOf(chunkObjects);
            unknownChunks = Set.copyOf(unknownChunks);
        }

        public Result(Map<String, String> chunkObjects)
        {
            this(chunkObjects, Set.of());
        }
    }
}
