package me.zly2006.lvc.capture;

import java.io.IOException;
import java.util.BitSet;
import java.util.List;
import javax.annotation.Nullable;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;

public interface LvcWorldReader
{
    default boolean canReadAt(LvcIntPosition worldPos)
    {
        return true;
    }

    String blockStateAt(LvcIntPosition worldPos) throws IOException;

    @Nullable
    default byte[] blockEntityNbtAt(LvcIntPosition worldPos) throws IOException
    {
        return null;
    }

    default List<LvcChunk.EntityRecord> entityRecordsInChunk(LvcChunkCoordinate coordinate, LvcIntPosition origin,
                                                             BitSet mask, int sizeX, int sizeY, int sizeZ) throws IOException
    {
        return List.of();
    }
}
