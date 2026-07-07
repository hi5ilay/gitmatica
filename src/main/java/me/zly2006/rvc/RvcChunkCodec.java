package me.zly2006.rvc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

public final class RvcChunkCodec
{
    private static final byte[] MAGIC = new byte[] { 'R', 'V', 'C', 'C', 'H', 'N', '1', 0 };

    private RvcChunkCodec()
    {
    }

    public static byte[] encode(RvcChunk chunk) throws IOException
    {
        Objects.requireNonNull(chunk, "chunk");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes))
        {
            out.write(MAGIC);
            out.writeShort(0);
            out.writeShort(chunk.sizeX());
            out.writeShort(chunk.sizeY());
            out.writeShort(chunk.sizeZ());

            byte[] maskBytes = maskToBytes(chunk.trackedMask(), chunk.volume());
            out.writeShort(maskBytes.length);
            out.write(maskBytes);

            writeVarUInt(out, chunk.palette().size());

            for (String entry : chunk.palette())
            {
                writeString(out, entry);
            }

            for (int index : chunk.blockStateIndices())
            {
                writeVarUInt(out, index);
            }

            writeBlockEntities(out, chunk.blockEntities());
            writeTicks(out, chunk.pendingBlockTicks());
            writeTicks(out, chunk.pendingFluidTicks());
            writeVarUInt(out, 0);
        }

        return bytes.toByteArray();
    }

    public static RvcChunk decode(byte[] bytes) throws IOException
    {
        Objects.requireNonNull(bytes, "bytes");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));

        byte[] magic = in.readNBytes(MAGIC.length);

        if (magic.length != MAGIC.length || !java.util.Arrays.equals(MAGIC, magic))
        {
            throw new IOException("Invalid RVC chunk magic");
        }

        int flags = in.readUnsignedShort();

        if (flags != 0)
        {
            throw new IOException("Unsupported RVC chunk flags: " + flags);
        }

        int sizeX = in.readUnsignedShort();
        int sizeY = in.readUnsignedShort();
        int sizeZ = in.readUnsignedShort();
        int volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        int expectedMaskLength = (volume + 7) / 8;
        int maskLength = in.readUnsignedShort();

        if (maskLength != expectedMaskLength)
        {
            throw new IOException("Invalid RVC chunk tracked mask length: " + maskLength);
        }

        BitSet trackedMask = bytesToMask(in.readNBytes(maskLength), volume);
        int paletteCount = readVarUInt(in);
        List<String> palette = new ArrayList<>(paletteCount);

        for (int i = 0; i < paletteCount; i++)
        {
            palette.add(readString(in));
        }

        int trackedCount = trackedMask.cardinality();
        int[] blockStateIndices = new int[trackedCount];

        for (int i = 0; i < trackedCount; i++)
        {
            blockStateIndices[i] = readVarUInt(in);
        }

        List<RvcChunk.BlockEntityRecord> blockEntities = readBlockEntities(in);
        List<RvcChunk.ScheduledTickRecord> pendingBlockTicks = readTicks(in);
        List<RvcChunk.ScheduledTickRecord> pendingFluidTicks = readTicks(in);
        int entityCount = readVarUInt(in);

        if (entityCount != 0)
        {
            throw new IOException("RVC chunk entity records are not supported yet");
        }

        if (in.read() != -1)
        {
            throw new IOException("Trailing bytes after RVC chunk payload");
        }

        return new RvcChunk(sizeX, sizeY, sizeZ, trackedMask, palette, blockStateIndices, blockEntities, pendingBlockTicks, pendingFluidTicks);
    }

    static byte[] maskToBytes(BitSet mask, int volume)
    {
        byte[] bytes = new byte[(volume + 7) / 8];

        for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1))
        {
            if (index >= volume)
            {
                throw new IllegalArgumentException("RVC chunk tracked mask exceeds chunk volume");
            }

            bytes[index >> 3] |= (byte) (1 << (index & 7));
        }

        return bytes;
    }

    static BitSet bytesToMask(byte[] bytes, int volume) throws IOException
    {
        if (bytes.length != (volume + 7) / 8)
        {
            throw new IOException("Invalid RVC chunk tracked mask byte length");
        }

        BitSet mask = new BitSet(volume);

        for (int i = 0; i < volume; i++)
        {
            if ((bytes[i >> 3] & (1 << (i & 7))) != 0)
            {
                mask.set(i);
            }
        }

        return mask;
    }

    private static void writeBlockEntities(DataOutputStream out, List<RvcChunk.BlockEntityRecord> blockEntities) throws IOException
    {
        writeVarUInt(out, blockEntities.size());

        for (RvcChunk.BlockEntityRecord record : blockEntities)
        {
            byte[] nbt = record.canonicalNbt();
            out.writeShort(record.index());
            writeVarUInt(out, nbt.length);
            out.write(nbt);
        }
    }

    private static List<RvcChunk.BlockEntityRecord> readBlockEntities(DataInputStream in) throws IOException
    {
        int count = readVarUInt(in);
        List<RvcChunk.BlockEntityRecord> records = new ArrayList<>(count);

        for (int i = 0; i < count; i++)
        {
            int index = in.readUnsignedShort();
            int nbtLength = readVarUInt(in);
            records.add(new RvcChunk.BlockEntityRecord(index, in.readNBytes(nbtLength)));
        }

        return records;
    }

    private static void writeTicks(DataOutputStream out, List<RvcChunk.ScheduledTickRecord> ticks) throws IOException
    {
        writeVarUInt(out, ticks.size());

        for (RvcChunk.ScheduledTickRecord tick : ticks)
        {
            out.writeShort(tick.index());
            writeString(out, tick.targetId());
            writeVarInt(out, tick.delay());
            out.writeByte(tick.priority());
            out.writeLong(tick.subTickOrder());
        }
    }

    private static List<RvcChunk.ScheduledTickRecord> readTicks(DataInputStream in) throws IOException
    {
        int count = readVarUInt(in);
        List<RvcChunk.ScheduledTickRecord> ticks = new ArrayList<>(count);

        for (int i = 0; i < count; i++)
        {
            int index = in.readUnsignedShort();
            String targetId = readString(in);
            int delay = readVarInt(in);
            byte priority = in.readByte();
            long subTickOrder = in.readLong();
            ticks.add(new RvcChunk.ScheduledTickRecord(index, targetId, delay, priority, subTickOrder));
        }

        return ticks;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarUInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException
    {
        int length = readVarUInt(in);
        byte[] bytes = in.readNBytes(length);

        if (bytes.length != length)
        {
            throw new EOFException("Unexpected end of RVC chunk string");
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarUInt(DataOutputStream out, int value) throws IOException
    {
        if (value < 0)
        {
            throw new IllegalArgumentException("varuint must not be negative");
        }

        while ((value & ~0x7F) != 0)
        {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }

        out.writeByte(value);
    }

    private static int readVarUInt(DataInputStream in) throws IOException
    {
        int value = 0;
        int shift = 0;

        while (shift < 35)
        {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;

            if ((b & 0x80) == 0)
            {
                return value;
            }

            shift += 7;
        }

        throw new IOException("RVC chunk varuint is too long");
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException
    {
        boolean more;

        do
        {
            int b = value & 0x7F;
            value >>= 7;
            more = !((value == 0 && (b & 0x40) == 0) || (value == -1 && (b & 0x40) != 0));

            if (more)
            {
                b |= 0x80;
            }

            out.writeByte(b);
        }
        while (more);
    }

    private static int readVarInt(DataInputStream in) throws IOException
    {
        int value = 0;
        int shift = 0;
        int b;

        do
        {
            if (shift >= 35)
            {
                throw new IOException("RVC chunk varint is too long");
            }

            b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
        }
        while ((b & 0x80) != 0);

        if (shift < 32 && (b & 0x40) != 0)
        {
            value |= -1 << shift;
        }

        return value;
    }
}
