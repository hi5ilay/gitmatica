package me.zly2006.lvc.capture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.storage.LvcCanonicalNbt;

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

    private static <T extends Comparable<T>> String propertyValueName(BlockState state, Property<T> property)
    {
        return property.getName(state.getValue(property));
    }
}
