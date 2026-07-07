package me.zly2006.lvc.util;

import java.io.IOException;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import fi.dy.masa.malilib.util.nbt.NbtUtils;
import fi.dy.masa.malilib.util.nbt.NbtView;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.storage.LvcCanonicalNbt;

public final class LvcEntityNbt
{
    private LvcEntityNbt()
    {
    }

    @Nullable
    public static byte[] captureProjectRelative(Entity entity, Level world, LvcIntPosition origin) throws IOException
    {
        NbtView view = NbtView.getWriter(world.registryAccess());

        if (!entity.save(view.getWriter()))
        {
            return null;
        }

        CompoundTag writtenTag = view.readNbt();
        CompoundTag tag = writtenTag != null ? writtenTag : new CompoundTag();
        Identifier id = EntityType.getKey(entity.getType());

        if (id == null)
        {
            return null;
        }

        tag.putString("id", id.toString());
        stripRuntimeEntityFields(tag);
        offsetEntityTree(tag, -origin.x(), -origin.y(), -origin.z());

        Vec3 projectPos = new Vec3(entity.getX() - origin.x(), entity.getY() - origin.y(), entity.getZ() - origin.z());
        NbtUtils.putVec3dCodec(tag, projectPos, "Pos");

        if (entity instanceof HangingEntity decorationEntity)
        {
            BlockPos pos = decorationEntity.blockPosition();
            tag.putInt("TileX", pos.getX() - origin.x());
            tag.putInt("TileY", pos.getY() - origin.y());
            tag.putInt("TileZ", pos.getZ() - origin.z());
        }

        if (entity instanceof BlockAttachedEntity attachedEntity)
        {
            BlockPos pos = attachedEntity.getPos();
            tag.store("block_pos", BlockPos.CODEC, new BlockPos(pos.getX() - origin.x(), pos.getY() - origin.y(), pos.getZ() - origin.z()));
        }

        if (tag.contains("SleepingX"))
        {
            tag.putInt("SleepingX", floor(projectPos.x));
        }

        if (tag.contains("SleepingY"))
        {
            tag.putInt("SleepingY", floor(projectPos.y));
        }

        if (tag.contains("SleepingZ"))
        {
            tag.putInt("SleepingZ", floor(projectPos.z));
        }

        return LvcCanonicalNbt.encodeUnnamed(tag);
    }

    public static CompoundTag materializeForWorld(byte[] canonicalNbt, LvcIntPosition origin) throws IOException
    {
        CompoundTag tag = LvcCanonicalNbt.decodeUnnamedCompound(canonicalNbt);
        stripRuntimeEntityFields(tag);
        offsetEntityTree(tag, origin.x(), origin.y(), origin.z());
        return tag;
    }

    public static CompoundTag materializeForRegion(byte[] canonicalNbt, LvcIntPosition regionMin) throws IOException
    {
        CompoundTag tag = LvcCanonicalNbt.decodeUnnamedCompound(canonicalNbt);
        stripRuntimeEntityFields(tag);
        offsetEntityTree(tag, -regionMin.x(), -regionMin.y(), -regionMin.z());
        return tag;
    }

    public static Vec3 position(byte[] canonicalNbt) throws IOException
    {
        return position(LvcCanonicalNbt.decodeUnnamedCompound(canonicalNbt));
    }

    public static Vec3 position(CompoundTag tag) throws IOException
    {
        Vec3 pos = NbtUtils.getVec3dCodec(tag, "Pos");

        if (pos == null)
        {
            throw new IOException("LVC entity payload is missing Pos");
        }

        return pos;
    }

    private static void offsetEntityTree(CompoundTag tag, int x, int y, int z)
    {
        offsetEntityCoordinates(tag, x, y, z);

        if (tag.contains("Passengers"))
        {
            ListTag passengers = tag.getListOrEmpty("Passengers");

            for (int i = 0; i < passengers.size(); i++)
            {
                offsetEntityTree(passengers.getCompoundOrEmpty(i), x, y, z);
            }
        }
    }

    private static void stripRuntimeEntityFields(CompoundTag tag)
    {
        tag.remove("LastEntityID");

        if (tag.contains("Passengers"))
        {
            ListTag passengers = tag.getListOrEmpty("Passengers");

            for (int i = 0; i < passengers.size(); i++)
            {
                stripRuntimeEntityFields(passengers.getCompoundOrEmpty(i));
            }
        }
    }

    private static void offsetEntityCoordinates(CompoundTag tag, int x, int y, int z)
    {
        Vec3 pos = NbtUtils.getVec3dCodec(tag, "Pos");

        if (pos != null)
        {
            NbtUtils.putVec3dCodec(tag, new Vec3(pos.x + x, pos.y + y, pos.z + z), "Pos");
        }

        if (tag.contains("TileX"))
        {
            tag.putInt("TileX", tag.getIntOr("TileX", 0) + x);
        }

        if (tag.contains("TileY"))
        {
            tag.putInt("TileY", tag.getIntOr("TileY", 0) + y);
        }

        if (tag.contains("TileZ"))
        {
            tag.putInt("TileZ", tag.getIntOr("TileZ", 0) + z);
        }

        BlockPos attachedPos = tag.read("block_pos", BlockPos.CODEC).orElse(null);

        if (attachedPos != null)
        {
            tag.store("block_pos", BlockPos.CODEC, attachedPos.offset(x, y, z));
        }

        if (tag.contains("SleepingX"))
        {
            tag.putInt("SleepingX", tag.getIntOr("SleepingX", 0) + x);
        }

        if (tag.contains("SleepingY"))
        {
            tag.putInt("SleepingY", tag.getIntOr("SleepingY", 0) + y);
        }

        if (tag.contains("SleepingZ"))
        {
            tag.putInt("SleepingZ", tag.getIntOr("SleepingZ", 0) + z);
        }
    }

    private static int floor(double value)
    {
        return (int) Math.floor(value);
    }
}
