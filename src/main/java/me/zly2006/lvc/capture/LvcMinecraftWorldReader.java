package me.zly2006.lvc.capture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import fi.dy.masa.litematica.util.EntityUtils;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.storage.LvcCanonicalNbt;
import me.zly2006.lvc.util.LvcEntityNbt;

public final class LvcMinecraftWorldReader implements LvcWorldReader
{
    private final Level world;
    private final Map<BlockState, String> blockStateStringCache = new IdentityHashMap<>();

    public LvcMinecraftWorldReader(Level world)
    {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public boolean canReadAt(LvcIntPosition worldPos)
    {
        return this.world.hasChunk(SectionPos.blockToSectionCoord(worldPos.x()), SectionPos.blockToSectionCoord(worldPos.z()));
    }

    @Override
    public String blockStateAt(LvcIntPosition worldPos)
    {
        return this.cachedBlockStateString(this.world.getBlockState(toBlockPos(worldPos)));
    }

    @Override
    @Nullable
    public byte[] blockEntityNbtAt(LvcIntPosition worldPos) throws IOException
    {
        BlockEntity blockEntity = this.world.getBlockEntity(toBlockPos(worldPos));

        if (blockEntity == null)
        {
            return null;
        }

        CompoundTag tag = blockEntity.saveWithFullMetadata(this.world.registryAccess());
        return LvcCanonicalNbt.encodeBlockEntity(tag);
    }

    @Override
    public List<LvcChunk.EntityRecord> entityRecordsInChunk(LvcChunkCoordinate coordinate, LvcIntPosition origin,
                                                            BitSet mask, int sizeX, int sizeY, int sizeZ) throws IOException
    {
        LvcIntPosition chunkMinProject = new LvcIntPosition(coordinate.x() * sizeX, coordinate.y() * sizeY, coordinate.z() * sizeZ);
        LvcIntPosition chunkMinWorld = origin.offset(chunkMinProject);
        AABB bounds = new AABB(chunkMinWorld.x(), chunkMinWorld.y(), chunkMinWorld.z(),
                chunkMinWorld.x() + sizeX, chunkMinWorld.y() + sizeY, chunkMinWorld.z() + sizeZ);
        List<Entity> entities = this.world.getEntities((Entity) null, bounds, EntityUtils.NOT_PLAYER);
        List<LvcChunk.EntityRecord> records = new ArrayList<>();
        Set<UUID> captured = new HashSet<>();

        for (Entity entity : entities)
        {
            if (entity.isPassenger() || !captured.add(entity.getUUID()))
            {
                continue;
            }

            int localX = floor(entity.getX() - origin.x()) - chunkMinProject.x();
            int localY = floor(entity.getY() - origin.y()) - chunkMinProject.y();
            int localZ = floor(entity.getZ() - origin.z()) - chunkMinProject.z();

            if (localX < 0 || localX >= sizeX || localY < 0 || localY >= sizeY || localZ < 0 || localZ >= sizeZ)
            {
                continue;
            }

            int maskIndex = LvcCapturePlanner.index(localX, localY, localZ, sizeX, sizeY);

            if (!mask.get(maskIndex))
            {
                continue;
            }

            byte[] canonicalNbt = LvcEntityNbt.captureProjectRelative(entity, this.world, origin);

            if (canonicalNbt != null)
            {
                records.add(new LvcChunk.EntityRecord(canonicalNbt));
            }
        }

        return records;
    }

    public static String blockStateString(BlockState state)
    {
        Objects.requireNonNull(state, "state");

        if (state.isAir())
        {
            return "minecraft:air";
        }

        StringBuilder builder = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        List<Property<?>> properties = new ArrayList<>(state.getProperties());

        if (properties.isEmpty())
        {
            return builder.toString();
        }

        properties.sort(Comparator.comparing(Property::getName));
        builder.append('[');

        for (int i = 0; i < properties.size(); i++)
        {
            Property<?> property = properties.get(i);

            if (i > 0)
            {
                builder.append(',');
            }

            builder.append(property.getName()).append('=').append(propertyValueName(state, property));
        }

        builder.append(']');
        return builder.toString();
    }

    private String cachedBlockStateString(BlockState state)
    {
        String cached = this.blockStateStringCache.get(state);

        if (cached != null)
        {
            return cached;
        }

        cached = blockStateString(state);
        this.blockStateStringCache.put(state, cached);
        return cached;
    }

    public static String dimensionId(Level world)
    {
        return world.dimension().identifier().toString();
    }

    private static BlockPos toBlockPos(LvcIntPosition position)
    {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static int floor(double value)
    {
        return (int) Math.floor(value);
    }

    private static <T extends Comparable<T>> String propertyValueName(BlockState state, Property<T> property)
    {
        return property.getName(state.getValue(property));
    }
}
