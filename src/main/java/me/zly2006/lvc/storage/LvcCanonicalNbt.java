package me.zly2006.lvc.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class LvcCanonicalNbt
{
    private LvcCanonicalNbt()
    {
    }

    public static byte[] encodeBlockEntity(CompoundTag blockEntityTag) throws IOException
    {
        Objects.requireNonNull(blockEntityTag, "blockEntityTag");

        CompoundTag copy = blockEntityTag.copy();
        copy.remove("x");
        copy.remove("y");
        copy.remove("z");
        return encodeUnnamed(copy);
    }

    public static byte[] encodeUnnamed(Tag tag) throws IOException
    {
        Objects.requireNonNull(tag, "tag");

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(byteStream))
        {
            output.writeByte(tag.getId());
            writePayload(tag, output);
        }

        return byteStream.toByteArray();
    }

    public static CompoundTag decodeUnnamedCompound(byte[] bytes) throws IOException
    {
        Objects.requireNonNull(bytes, "bytes");

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes)))
        {
            Tag tag = NbtIo.readAnyTag(input, NbtAccounter.unlimitedHeap());

            if (tag instanceof CompoundTag compoundTag)
            {
                return compoundTag;
            }

            throw new IOException("LVC canonical NBT payload is not a compound tag");
        }
    }

    private static void writePayload(Tag tag, DataOutput output) throws IOException
    {
        if (tag instanceof CompoundTag compoundTag)
        {
            writeCompoundPayload(compoundTag, output);
        }
        else if (tag instanceof ListTag listTag)
        {
            writeListPayload(listTag, output);
        }
        else
        {
            tag.write(output);
        }
    }

    private static void writeCompoundPayload(CompoundTag tag, DataOutput output) throws IOException
    {
        List<String> keys = new ArrayList<>(tag.keySet());
        Collections.sort(keys);

        for (String key : keys)
        {
            Tag value = tag.get(key);

            if (value == null)
            {
                continue;
            }

            output.writeByte(value.getId());
            output.writeUTF(key);
            writePayload(value, output);
        }

        output.writeByte(Tag.TAG_END);
    }

    private static void writeListPayload(ListTag tag, DataOutput output) throws IOException
    {
        byte elementType = tag.isEmpty() ? Tag.TAG_END : tag.get(0).getId();

        output.writeByte(elementType);
        output.writeInt(tag.size());

        for (Tag value : tag)
        {
            if (value.getId() != elementType)
            {
                throw new IOException("Mixed-type NBT lists are not supported by LVC canonical NBT");
            }

            writePayload(value, output);
        }
    }
}
