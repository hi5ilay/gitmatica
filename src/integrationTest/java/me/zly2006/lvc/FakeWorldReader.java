package me.zly2006.lvc;

import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import me.zly2006.lvc.capture.LvcWorldReader;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.storage.LvcCanonicalNbt;

final class FakeWorldReader implements LvcWorldReader
{
    final Set<LvcIntPosition> requestedPositions = new HashSet<>();

    private final String defaultBlock;
    private final Map<LvcIntPosition, String> blocks = new HashMap<>();
    private final Map<LvcIntPosition, CompoundTag> blockEntities = new HashMap<>();
    private final Map<LvcChunkCoordinate, List<LvcChunk.EntityRecord>> entities = new HashMap<>();
    private final Set<LvcIntPosition> unavailablePositions = new HashSet<>();
    private int blockEntityReadCount;
    private int entityReadCount;

    FakeWorldReader(String defaultBlock)
    {
        this.defaultBlock = defaultBlock;
    }

    void setBlock(LvcIntPosition pos, String blockState)
    {
        this.blocks.put(pos, blockState);
    }

    void setBlockEntity(LvcIntPosition pos, CompoundTag blockEntity)
    {
        this.blockEntities.put(pos, blockEntity);
    }

    void setEntities(LvcChunkCoordinate coordinate, List<LvcChunk.EntityRecord> records)
    {
        this.entities.put(coordinate, List.copyOf(records));
    }

    void setUnavailable(LvcIntPosition pos)
    {
        this.unavailablePositions.add(pos);
    }

    int blockEntityReadCount()
    {
        return this.blockEntityReadCount;
    }

    void resetBlockEntityReadCount()
    {
        this.blockEntityReadCount = 0;
    }

    int entityReadCount()
    {
        return this.entityReadCount;
    }

    void resetEntityReadCount()
    {
        this.entityReadCount = 0;
    }

    @Override
    public boolean canReadAt(LvcIntPosition worldPos)
    {
        return !this.unavailablePositions.contains(worldPos);
    }

    @Override
    public String blockStateAt(LvcIntPosition worldPos)
    {
        this.requestedPositions.add(worldPos);
        return this.blocks.getOrDefault(worldPos, this.defaultBlock);
    }

    @Override
    public byte[] blockEntityNbtAt(LvcIntPosition worldPos) throws IOException
    {
        this.blockEntityReadCount++;
        CompoundTag blockEntity = this.blockEntities.get(worldPos);
        return blockEntity == null ? null : LvcCanonicalNbt.encodeBlockEntity(blockEntity);
    }

    @Override
    public List<LvcChunk.EntityRecord> entityRecordsInChunk(LvcChunkCoordinate coordinate, LvcIntPosition origin,
                                                            BitSet mask, int sizeX, int sizeY, int sizeZ)
    {
        this.entityReadCount++;
        return this.entities.getOrDefault(coordinate, List.of());
    }
}
