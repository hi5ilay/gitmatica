package me.zly2006.lvc.model;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class LvcChunk
{
    public static final int DEFAULT_SIZE = 16;
    public static final int DEFAULT_VOLUME = DEFAULT_SIZE * DEFAULT_SIZE * DEFAULT_SIZE;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final BitSet trackedMask;
    private final List<String> palette;
    private final int[] blockStateIndices;
    private final List<BlockEntityRecord> blockEntities;
    private final List<ScheduledTickRecord> pendingBlockTicks;
    private final List<ScheduledTickRecord> pendingFluidTicks;
    private final List<EntityRecord> entities;

    public LvcChunk(int sizeX, int sizeY, int sizeZ, BitSet trackedMask, List<String> palette, int[] blockStateIndices,
                    List<BlockEntityRecord> blockEntities, List<ScheduledTickRecord> pendingBlockTicks,
                    List<ScheduledTickRecord> pendingFluidTicks)
    {
        this(sizeX, sizeY, sizeZ, trackedMask, palette, blockStateIndices, blockEntities, pendingBlockTicks,
                pendingFluidTicks, List.of());
    }

    public LvcChunk(int sizeX, int sizeY, int sizeZ, BitSet trackedMask, List<String> palette, int[] blockStateIndices,
                    List<BlockEntityRecord> blockEntities, List<ScheduledTickRecord> pendingBlockTicks,
                    List<ScheduledTickRecord> pendingFluidTicks, List<EntityRecord> entities)
    {
        this.sizeX = requirePositive(sizeX, "sizeX");
        this.sizeY = requirePositive(sizeY, "sizeY");
        this.sizeZ = requirePositive(sizeZ, "sizeZ");
        this.trackedMask = copyMask(trackedMask, this.volume());
        this.palette = List.copyOf(Objects.requireNonNull(palette, "palette"));
        this.blockStateIndices = Objects.requireNonNull(blockStateIndices, "blockStateIndices").clone();
        this.blockEntities = copyBlockEntities(blockEntities);
        this.pendingBlockTicks = List.copyOf(Objects.requireNonNull(pendingBlockTicks, "pendingBlockTicks"));
        this.pendingFluidTicks = List.copyOf(Objects.requireNonNull(pendingFluidTicks, "pendingFluidTicks"));
        this.entities = copyEntities(entities);

        this.validate();
    }

    public static LvcChunk fromTrackedBlockStates(BitSet trackedMask, List<String> trackedBlockStates)
    {
        return fromTrackedBlockStates(DEFAULT_SIZE, DEFAULT_SIZE, DEFAULT_SIZE, trackedMask, trackedBlockStates);
    }

    public static LvcChunk fromTrackedBlockStates(int sizeX, int sizeY, int sizeZ, BitSet trackedMask, List<String> trackedBlockStates)
    {
        return fromTrackedContent(sizeX, sizeY, sizeZ, trackedMask, trackedBlockStates, List.of(), List.of(), List.of());
    }

    public static LvcChunk fromTrackedContent(int sizeX, int sizeY, int sizeZ, BitSet trackedMask, List<String> trackedBlockStates,
                                             List<BlockEntityRecord> blockEntities, List<ScheduledTickRecord> pendingBlockTicks,
                                             List<ScheduledTickRecord> pendingFluidTicks)
    {
        return fromTrackedContent(sizeX, sizeY, sizeZ, trackedMask, trackedBlockStates, blockEntities,
                pendingBlockTicks, pendingFluidTicks, List.of());
    }

    public static LvcChunk fromTrackedContent(int sizeX, int sizeY, int sizeZ, BitSet trackedMask, List<String> trackedBlockStates,
                                             List<BlockEntityRecord> blockEntities, List<ScheduledTickRecord> pendingBlockTicks,
                                             List<ScheduledTickRecord> pendingFluidTicks, List<EntityRecord> entities)
    {
        Objects.requireNonNull(trackedBlockStates, "trackedBlockStates");
        Map<String, Integer> paletteMap = new LinkedHashMap<>();
        int[] indices = new int[trackedBlockStates.size()];

        for (int i = 0; i < trackedBlockStates.size(); i++)
        {
            String blockState = Objects.requireNonNull(trackedBlockStates.get(i), "trackedBlockState");
            Integer index = paletteMap.get(blockState);

            if (index == null)
            {
                index = paletteMap.size();
                paletteMap.put(blockState, index);
            }

            indices[i] = index;
        }

        return new LvcChunk(sizeX, sizeY, sizeZ, trackedMask, new ArrayList<>(paletteMap.keySet()), indices,
                blockEntities, pendingBlockTicks, pendingFluidTicks, entities);
    }

    public int sizeX()
    {
        return this.sizeX;
    }

    public int sizeY()
    {
        return this.sizeY;
    }

    public int sizeZ()
    {
        return this.sizeZ;
    }

    public int volume()
    {
        return this.sizeX * this.sizeY * this.sizeZ;
    }

    public BitSet trackedMask()
    {
        return (BitSet) this.trackedMask.clone();
    }

    public boolean isTracked(int index)
    {
        return this.trackedMask.get(index);
    }

    public int trackedCount()
    {
        return this.trackedMask.cardinality();
    }

    public List<String> palette()
    {
        return this.palette;
    }

    public int[] blockStateIndices()
    {
        return this.blockStateIndices.clone();
    }

    public List<BlockEntityRecord> blockEntities()
    {
        return this.blockEntities;
    }

    public List<ScheduledTickRecord> pendingBlockTicks()
    {
        return this.pendingBlockTicks;
    }

    public List<ScheduledTickRecord> pendingFluidTicks()
    {
        return this.pendingFluidTicks;
    }

    public List<EntityRecord> entities()
    {
        return this.entities;
    }

    public String blockStateAtTrackedOrdinal(int ordinal)
    {
        return this.palette.get(this.blockStateIndices[ordinal]);
    }

    private void validate()
    {
        if (this.blockStateIndices.length != this.trackedMask.cardinality())
        {
            throw new IllegalArgumentException("LVC chunk block state count must match tracked mask cardinality");
        }

        for (int index : this.blockStateIndices)
        {
            if (index < 0 || index >= this.palette.size())
            {
                throw new IllegalArgumentException("LVC chunk block state index is outside the palette: " + index);
            }
        }

        for (String entry : this.palette)
        {
            if (entry == null || entry.isBlank())
            {
                throw new IllegalArgumentException("LVC chunk palette entries must not be blank");
            }
        }

        Set<Integer> blockEntityIndices = new HashSet<>();

        for (BlockEntityRecord blockEntity : this.blockEntities)
        {
            requireTrackedIndex(blockEntity.index(), "block entity");

            if (!blockEntityIndices.add(blockEntity.index()))
            {
                throw new IllegalArgumentException("LVC chunk has duplicate block entity at tracked position: " + blockEntity.index());
            }
        }

        for (ScheduledTickRecord tick : this.pendingBlockTicks)
        {
            requireTrackedIndex(tick.index(), "pending block tick");
        }

        for (ScheduledTickRecord tick : this.pendingFluidTicks)
        {
            requireTrackedIndex(tick.index(), "pending fluid tick");
        }
    }

    private void requireTrackedIndex(int index, String label)
    {
        if (index < 0 || index >= this.volume() || !this.trackedMask.get(index))
        {
            throw new IllegalArgumentException("LVC chunk " + label + " must target a tracked position: " + index);
        }
    }

    private static int requirePositive(int value, String name)
    {
        if (value <= 0)
        {
            throw new IllegalArgumentException(name + " must be positive");
        }

        return value;
    }

    private static BitSet copyMask(BitSet mask, int volume)
    {
        Objects.requireNonNull(mask, "trackedMask");
        BitSet copy = (BitSet) mask.clone();

        if (copy.length() > volume)
        {
            throw new IllegalArgumentException("LVC chunk tracked mask exceeds chunk volume");
        }

        return copy;
    }

    private static List<BlockEntityRecord> copyBlockEntities(List<BlockEntityRecord> blockEntities)
    {
        Objects.requireNonNull(blockEntities, "blockEntities");
        List<BlockEntityRecord> copy = new ArrayList<>(blockEntities.size());

        for (BlockEntityRecord record : blockEntities)
        {
            copy.add(new BlockEntityRecord(record.index(), record.canonicalNbt()));
        }

        return List.copyOf(copy);
    }

    private static List<EntityRecord> copyEntities(List<EntityRecord> entities)
    {
        Objects.requireNonNull(entities, "entities");
        List<EntityRecord> copy = new ArrayList<>(entities.size());

        for (EntityRecord record : entities)
        {
            copy.add(new EntityRecord(record.canonicalNbt()));
        }

        copy.sort((left, right) -> compareBytes(left.canonicalNbt(), right.canonicalNbt()));
        return List.copyOf(copy);
    }

    private static int compareBytes(byte[] left, byte[] right)
    {
        int limit = Math.min(left.length, right.length);

        for (int i = 0; i < limit; i++)
        {
            int comparison = Byte.compareUnsigned(left[i], right[i]);

            if (comparison != 0)
            {
                return comparison;
            }
        }

        return Integer.compare(left.length, right.length);
    }

    public record BlockEntityRecord(int index, byte[] canonicalNbt)
    {
        public BlockEntityRecord
        {
            Objects.requireNonNull(canonicalNbt, "canonicalNbt");
            canonicalNbt = canonicalNbt.clone();
        }

        @Override
        public byte[] canonicalNbt()
        {
            return this.canonicalNbt.clone();
        }
    }

    public record ScheduledTickRecord(int index, String targetId, int delay, byte priority, long subTickOrder)
    {
        public ScheduledTickRecord
        {
            if (targetId == null || targetId.isBlank())
            {
                throw new IllegalArgumentException("scheduled tick target ID must not be blank");
            }
        }
    }

    public record EntityRecord(byte[] canonicalNbt)
    {
        public EntityRecord
        {
            Objects.requireNonNull(canonicalNbt, "canonicalNbt");
            canonicalNbt = canonicalNbt.clone();
        }

        @Override
        public byte[] canonicalNbt()
        {
            return this.canonicalNbt.clone();
        }
    }
}
