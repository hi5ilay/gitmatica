package me.zly2006.rvc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.tuple.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.schematic.placement.TemporaryWorldHolder;
import fi.dy.masa.litematica.schematic.placement.TemporaryWorldManager;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.nbt.NbtView;

public final class RvcStructure
{
    private static final String TEMP_WORLD_NAME = "rvc_structure_export";

    private RvcStructure()
    {
    }

    public static StructureTemplate createFromWorld(Level world, List<Box> boxes, boolean ignoreEntities)
    {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(boxes, "boxes");

        Pair<BlockPos, BlockPos> corners = PositionUtils.getEnclosingAreaCorners(boxes);

        if (corners == null)
        {
            throw new IllegalArgumentException("RVC project requires a non-empty area selection");
        }

        BlockPos origin = corners.getLeft();
        BlockPos size = corners.getRight().subtract(origin).offset(1, 1, 1);

        TemporaryWorldManager.INSTANCE.removeTemporaryWorld(TEMP_WORLD_NAME);
        TemporaryWorldHolder holder = TemporaryWorldManager.INSTANCE.getTemporaryWorld(TEMP_WORLD_NAME, BlockPos.ZERO, size);

        try
        {
            Level tempWorld = holder.world();

            if (tempWorld == null)
            {
                throw new IllegalStateException("Failed to create RVC temporary structure world");
            }

            fillWithStructureVoid(tempWorld, size);
            copyTrackedBlocksToTemporaryWorld(world, tempWorld, origin, size, boxes);

            StructureTemplate template = new StructureTemplate();
            template.fillFromWorld(tempWorld, BlockPos.ZERO, size, !ignoreEntities, List.of());
            return template;
        }
        finally
        {
            TemporaryWorldManager.INSTANCE.removeTemporaryWorld(TEMP_WORLD_NAME);
        }
    }

    public static void writeCompressed(StructureTemplate template, Path file) throws IOException
    {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(file, "file");
        NbtIo.writeCompressed(template.save(new CompoundTag()), file);
    }

    private static void fillWithStructureVoid(Level world, Vec3i size)
    {
        for (int y = 0; y < size.getY(); y++)
        {
            for (int z = 0; z < size.getZ(); z++)
            {
                for (int x = 0; x < size.getX(); x++)
                {
                    world.setBlock(new BlockPos(x, y, z), Blocks.STRUCTURE_VOID.defaultBlockState(), 0x12);
                }
            }
        }
    }

    private static void copyTrackedBlocksToTemporaryWorld(Level sourceWorld, Level tempWorld, BlockPos origin, Vec3i size, List<Box> boxes)
    {
        for (int y = 0; y < size.getY(); y++)
        {
            for (int z = 0; z < size.getZ(); z++)
            {
                for (int x = 0; x < size.getX(); x++)
                {
                    BlockPos relativePos = new BlockPos(x, y, z);
                    BlockPos worldPos = relativePos.offset(origin);

                    if (isTrackedPosition(worldPos, boxes))
                    {
                        tempWorld.setBlock(relativePos, sourceWorld.getBlockState(worldPos), 0x12);
                        BlockEntity blockEntity = sourceWorld.getBlockEntity(worldPos);

                        if (blockEntity != null)
                        {
                            BlockEntity tempBlockEntity = tempWorld.getBlockEntity(relativePos);

                            if (tempBlockEntity != null)
                            {
                                try
                                {
                                    CompoundTag nbt = blockEntity.saveWithFullMetadata(sourceWorld.registryAccess());
                                    nbt.putInt("x", relativePos.getX());
                                    nbt.putInt("y", relativePos.getY());
                                    nbt.putInt("z", relativePos.getZ());
                                    NbtView view = NbtView.getReader(nbt, tempWorld.registryAccess());
                                    tempBlockEntity.loadWithComponents(view.getReader());
                                }
                                catch (Exception e)
                                {
                                    Litematica.LOGGER.debug("RvcStructure: failed to copy block entity at '{}' into temporary structure world: {}", worldPos, e.getMessage());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    static boolean isTrackedPosition(BlockPos pos, List<Box> boxes)
    {
        for (Box box : boxes)
        {
            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();

            if (pos1 == null || pos2 == null)
            {
                continue;
            }

            BlockPos min = PositionUtils.getMinCorner(pos1, pos2);
            BlockPos max = PositionUtils.getMaxCorner(pos1, pos2);

            if (pos.getX() >= min.getX() && pos.getX() <= max.getX() &&
                pos.getY() >= min.getY() && pos.getY() <= max.getY() &&
                pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ())
            {
                return true;
            }
        }

        return false;
    }
}
