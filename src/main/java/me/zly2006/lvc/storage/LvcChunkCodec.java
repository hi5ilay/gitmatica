package me.zly2006.lvc.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import me.zly2006.lvc.model.LvcChunk;

public final class LvcChunkCodec
{
    public static final int COMPRESSION_LEVEL = Deflater.BEST_SPEED;

    private static final byte[] STORAGE_MAGIC = new byte[] { 'L', 'V', 'C', 'C', 'H', 'Z', '1', 0 };
    private static final byte[] CONTENT_MAGIC = new byte[] { 'L', 'V', 'C', 'C', 'H', 'N', '2', 0 };

    private LvcChunkCodec()
    {
    }

    public static byte[] encode(LvcChunk chunk) throws IOException
    {
        return encodeStorageBytes(encodeHashContent(chunk));
    }

    public static byte[] encodeHashContent(LvcChunk chunk) throws IOException
    {
        Objects.requireNonNull(chunk, "chunk");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes))
        {
            out.write(CONTENT_MAGIC);
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
            writeEntities(out, chunk.entities());
        }

        return bytes.toByteArray();
    }

    public static byte[] encodeStorageBytes(byte[] hashContentBytes) throws IOException
    {
        Objects.requireNonNull(hashContentBytes, "hashContentBytes");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(STORAGE_MAGIC);

        Deflater deflater = new Deflater(COMPRESSION_LEVEL);

        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes, deflater))
        {
            out.write(hashContentBytes);
        }
        finally
        {
            deflater.end();
        }

        return bytes.toByteArray();
    }

    public static byte[] encodeTrackedContent(LvcChunk chunk) throws IOException
    {
        Objects.requireNonNull(chunk, "chunk");
        int[] blockStateIndices = chunk.blockStateIndices();
        List<String> trackedBlockStates = new ArrayList<>(blockStateIndices.length);

        for (int index : blockStateIndices)
        {
            trackedBlockStates.add(canonicalTrackedBlockState(chunk.palette().get(index)));
        }

        return encodeHashContent(LvcChunk.fromTrackedContent(
                chunk.sizeX(),
                chunk.sizeY(),
                chunk.sizeZ(),
                chunk.trackedMask(),
                trackedBlockStates,
                chunk.blockEntities(),
                List.of(),
                List.of()
        ));
    }

    public static String canonicalTrackedBlockState(String blockState)
    {
        Objects.requireNonNull(blockState, "blockState");

        if (blockState.equals("minecraft:cave_air") || blockState.equals("minecraft:void_air"))
        {
            return "minecraft:air";
        }

        return blockState;
    }

    public static LvcChunk decode(byte[] bytes) throws IOException
    {
        Objects.requireNonNull(bytes, "bytes");
        return decodeHashContent(inflateStorageBytes(bytes));
    }

    private static LvcChunk decodeHashContent(byte[] bytes) throws IOException
    {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));

        byte[] magic = in.readNBytes(CONTENT_MAGIC.length);

        if (magic.length != CONTENT_MAGIC.length || !Arrays.equals(CONTENT_MAGIC, magic))
        {
            throw new IOException("Invalid LVC chunk content magic");
        }

        int flags = in.readUnsignedShort();

        if (flags != 0)
        {
            throw new IOException("Unsupported LVC chunk flags: " + flags);
        }

        int sizeX = in.readUnsignedShort();
        int sizeY = in.readUnsignedShort();
        int sizeZ = in.readUnsignedShort();
        int volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        int expectedMaskLength = (volume + 7) / 8;
        int maskLength = in.readUnsignedShort();

        if (maskLength != expectedMaskLength)
        {
            throw new IOException("Invalid LVC chunk tracked mask length: " + maskLength);
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

        List<LvcChunk.BlockEntityRecord> blockEntities = readBlockEntities(in);
        List<LvcChunk.ScheduledTickRecord> pendingBlockTicks = readTicks(in);
        List<LvcChunk.ScheduledTickRecord> pendingFluidTicks = readTicks(in);
        List<LvcChunk.EntityRecord> entities = readEntities(in);

        if (in.read() != -1)
        {
            throw new IOException("Trailing bytes after LVC chunk payload");
        }

        return new LvcChunk(sizeX, sizeY, sizeZ, trackedMask, palette, blockStateIndices, blockEntities,
                pendingBlockTicks, pendingFluidTicks, entities);
    }

    private static byte[] inflateStorageBytes(byte[] bytes) throws IOException
    {
        if (bytes.length <= STORAGE_MAGIC.length || !Arrays.equals(STORAGE_MAGIC, Arrays.copyOf(bytes, STORAGE_MAGIC.length)))
        {
            throw new IOException("Invalid LVC chunk storage magic");
        }

        Inflater inflater = new Inflater();
        inflater.setInput(bytes, STORAGE_MAGIC.length, bytes.length - STORAGE_MAGIC.length);
        ByteArrayOutputStream inflated = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];

        try
        {
            while (!inflater.finished())
            {
                int count = inflater.inflate(buffer);

                if (count > 0)
                {
                    inflated.write(buffer, 0, count);
                }
                else if (inflater.needsInput())
                {
                    throw new EOFException("Unexpected end of compressed LVC chunk payload");
                }
                else if (inflater.needsDictionary())
                {
                    throw new IOException("Compressed LVC chunk payload requires a dictionary");
                }
                else
                {
                    throw new IOException("Compressed LVC chunk payload made no progress");
                }
            }

            if (inflater.getBytesRead() != bytes.length - STORAGE_MAGIC.length)
            {
                throw new IOException("Trailing bytes after compressed LVC chunk payload");
            }
        }
        catch (DataFormatException e)
        {
            throw new IOException("Invalid compressed LVC chunk payload", e);
        }
        finally
        {
            inflater.end();
        }

        return inflated.toByteArray();
    }

    static byte[] maskToBytes(BitSet mask, int volume)
    {
        byte[] bytes = new byte[(volume + 7) / 8];

        for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1))
        {
            if (index >= volume)
            {
                throw new IllegalArgumentException("LVC chunk tracked mask exceeds chunk volume");
            }

            bytes[index >> 3] |= (byte) (1 << (index & 7));
        }

        return bytes;
    }

    static BitSet bytesToMask(byte[] bytes, int volume) throws IOException
    {
        if (bytes.length != (volume + 7) / 8)
        {
            throw new IOException("Invalid LVC chunk tracked mask byte length");
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

    private static void writeBlockEntities(DataOutputStream out, List<LvcChunk.BlockEntityRecord> blockEntities) throws IOException
    {
        writeVarUInt(out, blockEntities.size());

        for (LvcChunk.BlockEntityRecord record : blockEntities)
        {
            byte[] nbt = record.canonicalNbt();
            out.writeShort(record.index());
            writeVarUInt(out, nbt.length);
            out.write(nbt);
        }
    }

    private static List<LvcChunk.BlockEntityRecord> readBlockEntities(DataInputStream in) throws IOException
    {
        int count = readVarUInt(in);
        List<LvcChunk.BlockEntityRecord> records = new ArrayList<>(count);

        for (int i = 0; i < count; i++)
        {
            int index = in.readUnsignedShort();
            int nbtLength = readVarUInt(in);
            byte[] nbt = in.readNBytes(nbtLength);

            if (nbt.length != nbtLength)
            {
                throw new EOFException("Unexpected end of LVC block entity payload");
            }

            records.add(new LvcChunk.BlockEntityRecord(index, nbt));
        }

        return records;
    }

    private static void writeTicks(DataOutputStream out, List<LvcChunk.ScheduledTickRecord> ticks) throws IOException
    {
        writeVarUInt(out, ticks.size());

        for (LvcChunk.ScheduledTickRecord tick : ticks)
        {
            out.writeShort(tick.index());
            writeString(out, tick.targetId());
            writeVarInt(out, tick.delay());
            out.writeByte(tick.priority());
            out.writeLong(tick.subTickOrder());
        }
    }

    private static List<LvcChunk.ScheduledTickRecord> readTicks(DataInputStream in) throws IOException
    {
        int count = readVarUInt(in);
        List<LvcChunk.ScheduledTickRecord> ticks = new ArrayList<>(count);

        for (int i = 0; i < count; i++)
        {
            int index = in.readUnsignedShort();
            String targetId = readString(in);
            int delay = readVarInt(in);
            byte priority = in.readByte();
            long subTickOrder = in.readLong();
            ticks.add(new LvcChunk.ScheduledTickRecord(index, targetId, delay, priority, subTickOrder));
        }

        return ticks;
    }

    private static void writeEntities(DataOutputStream out, List<LvcChunk.EntityRecord> entities) throws IOException
    {
        writeVarUInt(out, entities.size());

        for (LvcChunk.EntityRecord record : entities)
        {
            byte[] nbt = record.canonicalNbt();
            writeVarUInt(out, nbt.length);
            out.write(nbt);
        }
    }

    private static List<LvcChunk.EntityRecord> readEntities(DataInputStream in) throws IOException
    {
        int count = readVarUInt(in);
        List<LvcChunk.EntityRecord> records = new ArrayList<>(count);

        for (int i = 0; i < count; i++)
        {
            int nbtLength = readVarUInt(in);
            byte[] nbt = in.readNBytes(nbtLength);

            if (nbt.length != nbtLength)
            {
                throw new EOFException("Unexpected end of LVC entity payload");
            }

            records.add(new LvcChunk.EntityRecord(nbt));
        }

        return records;
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
            throw new EOFException("Unexpected end of LVC chunk string");
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

        throw new IOException("LVC chunk varuint is too long");
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
                throw new IOException("LVC chunk varint is too long");
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
