package me.zly2006.lvc.semantic;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import me.zly2006.lvc.capture.LvcCapturePlanner;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;

public final class LvcTrackedBlockCursor
{
    private LvcTrackedBlockCursor()
    {
    }

    public static Iterable<Position> positions(LvcChunkCoordinate coordinate, LvcIntPosition origin, BitSet mask,
                                               int sizeX, int sizeY, int sizeZ)
    {
        BitSet cursorMask = (BitSet) mask.clone();

        return () -> new Iterator<>()
        {
            private int nextIndex = cursorMask.nextSetBit(0);
            private int ordinal;

            @Override
            public boolean hasNext()
            {
                return this.nextIndex >= 0;
            }

            @Override
            public Position next()
            {
                int maskIndex = this.nextIndex;
                int trackedOrdinal = this.ordinal;
                LvcIntPosition projectPos = LvcCapturePlanner.projectPosition(coordinate, maskIndex, sizeX, sizeY, sizeZ);
                LvcIntPosition worldPos = origin.offset(projectPos);
                this.nextIndex = cursorMask.nextSetBit(maskIndex + 1);
                this.ordinal++;
                return new Position(coordinate, maskIndex, trackedOrdinal, projectPos, worldPos,
                        new BlockPos(worldPos.x(), worldPos.y(), worldPos.z()));
            }
        };
    }

    public static Iterable<StoredBlock> storedBlocks(LvcChunkCoordinate coordinate, LvcIntPosition origin, LvcChunk chunk)
    {
        Map<Integer, byte[]> blockEntities = blockEntitiesByIndex(chunk);

        return () -> new Iterator<>()
        {
            private final Iterator<Position> positions = positions(coordinate, origin, chunk.trackedMask(),
                    chunk.sizeX(), chunk.sizeY(), chunk.sizeZ()).iterator();

            @Override
            public boolean hasNext()
            {
                return this.positions.hasNext();
            }

            @Override
            public StoredBlock next()
            {
                Position position = this.positions.next();
                return new StoredBlock(
                        position.coordinate(),
                        position.maskIndex(),
                        position.trackedOrdinal(),
                        position.projectPos(),
                        position.worldPos(),
                        position.blockPos(),
                        chunk.blockStateAtTrackedOrdinal(position.trackedOrdinal()),
                        blockEntities.get(position.maskIndex())
                );
            }
        };
    }

    public static Map<Integer, byte[]> blockEntitiesByIndex(LvcChunk chunk)
    {
        Map<Integer, byte[]> blockEntities = new HashMap<>();

        for (LvcChunk.BlockEntityRecord record : chunk.blockEntities())
        {
            blockEntities.put(record.index(), record.canonicalNbt());
        }

        return blockEntities;
    }

    public record Position(LvcChunkCoordinate coordinate, int maskIndex, int trackedOrdinal,
                           LvcIntPosition projectPos, LvcIntPosition worldPos, BlockPos blockPos)
    {
    }

    public record StoredBlock(LvcChunkCoordinate coordinate, int maskIndex, int trackedOrdinal,
                              LvcIntPosition projectPos, LvcIntPosition worldPos, BlockPos blockPos,
                              String blockState, @Nullable byte[] blockEntityBytes)
    {
        @Override
        public byte[] blockEntityBytes()
        {
            return this.blockEntityBytes == null ? null : this.blockEntityBytes.clone();
        }
    }
}
