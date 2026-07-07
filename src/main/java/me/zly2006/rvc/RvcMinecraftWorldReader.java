package me.zly2006.rvc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

public final class RvcMinecraftWorldReader implements RvcWorldReader
{
    private final Level world;

    public RvcMinecraftWorldReader(Level world)
    {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public boolean canReadAt(RvcIntPosition worldPos)
    {
        return this.world.hasChunk(SectionPos.blockToSectionCoord(worldPos.x()), SectionPos.blockToSectionCoord(worldPos.z()));
    }

    @Override
    public String blockStateAt(RvcIntPosition worldPos)
    {
        return blockStateString(this.world.getBlockState(toBlockPos(worldPos)));
    }

    @Override
    @Nullable
    public byte[] blockEntityNbtAt(RvcIntPosition worldPos) throws IOException
    {
        BlockEntity blockEntity = this.world.getBlockEntity(toBlockPos(worldPos));

        if (blockEntity == null)
        {
            return null;
        }

        CompoundTag tag = blockEntity.saveWithFullMetadata(this.world.registryAccess());
        return RvcCanonicalNbt.encodeBlockEntity(tag);
    }

    public static String blockStateString(BlockState state)
    {
        Objects.requireNonNull(state, "state");

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

    public static String dimensionId(Level world)
    {
        return world.dimension().identifier().toString();
    }

    private static BlockPos toBlockPos(RvcIntPosition position)
    {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static <T extends Comparable<T>> String propertyValueName(BlockState state, Property<T> property)
    {
        return property.getName(state.getValue(property));
    }
}
