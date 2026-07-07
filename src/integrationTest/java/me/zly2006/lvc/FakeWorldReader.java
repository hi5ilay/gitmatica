package me.zly2006.lvc;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import me.zly2006.lvc.capture.LvcWorldReader;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.storage.LvcCanonicalNbt;

final class FakeWorldReader implements LvcWorldReader
{
    final Set<LvcIntPosition> requestedPositions = new HashSet<>();

    private final String defaultBlock;
    private final Map<LvcIntPosition, String> blocks = new HashMap<>();
    private final Map<LvcIntPosition, CompoundTag> blockEntities = new HashMap<>();
    private final Set<LvcIntPosition> unavailablePositions = new HashSet<>();
    private int blockEntityReadCount;

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
}
