package me.zly2006.lvc.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import me.zly2006.lvc.model.LvcChunkCoordinate;

public final class LvcHashIndexCodec
{
    public static final String FORMAT = "lvchash-index-v1";
    public static final String INDEXES_DIRECTORY = "indexes";
    public static final String EXTENSION = ".lvcidx";
    public static final int COMPRESSION_LEVEL = Deflater.BEST_SPEED;

    private static final byte[] MAGIC = new byte[] { 'L', 'V', 'C', 'I', 'D', 'X', '1', 0 };
    private static final int HASH_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private LvcHashIndexCodec()
    {
    }

    public static String defaultIndexPath(String siteId)
    {
        String cleanSiteId = Objects.requireNonNull(siteId, "siteId").trim();

        if (!cleanSiteId.matches("[A-Za-z0-9._-]+"))
        {
            throw new IllegalArgumentException("LVC site id is not safe for an index file name: " + siteId);
        }

        return INDEXES_DIRECTORY + "/" + cleanSiteId + EXTENSION;
    }

    public static void write(Path path, Map<String, String> fullHashes, Map<String, String> trackedHashes) throws IOException
    {
        Objects.requireNonNull(path, "path");
        Path parent = path.getParent();

        if (parent != null)
        {
            Files.createDirectories(parent);
        }

        Files.write(path, encode(fullHashes, trackedHashes));
    }

    public static HashRefs read(Path path) throws IOException
    {
        Objects.requireNonNull(path, "path");
        return decode(Files.readAllBytes(path));
    }

    public static byte[] encode(Map<String, String> fullHashes, Map<String, String> trackedHashes) throws IOException
    {
        Objects.requireNonNull(fullHashes, "fullHashes");
        Objects.requireNonNull(trackedHashes, "trackedHashes");

        if (!fullHashes.keySet().equals(trackedHashes.keySet()))
        {
            throw new IllegalArgumentException("LVC hash index full/tracked hash keys must match");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(MAGIC);
        Deflater deflater = new Deflater(COMPRESSION_LEVEL);

        try (DeflaterOutputStream deflaterStream = new DeflaterOutputStream(bytes, deflater);
             DataOutputStream out = new DataOutputStream(deflaterStream))
        {
            TreeMap<LvcChunkCoordinate, HashPair> sorted = sortRefs(fullHashes, trackedHashes);
            writeVarUInt(out, sorted.size());

            int previousX = 0;
            int previousY = 0;
            int previousZ = 0;

            for (Map.Entry<LvcChunkCoordinate, HashPair> entry : sorted.entrySet())
            {
                LvcChunkCoordinate coordinate = entry.getKey();
                writeVarInt(out, coordinate.x() - previousX);
                writeVarInt(out, coordinate.y() - previousY);
                writeVarInt(out, coordinate.z() - previousZ);
                out.write(objectHashBytes(entry.getValue().fullHash()));
                out.write(objectHashBytes(entry.getValue().trackedHash()));
                previousX = coordinate.x();
                previousY = coordinate.y();
                previousZ = coordinate.z();
            }
        }
        finally
        {
            deflater.end();
        }

        return bytes.toByteArray();
    }

    public static HashRefs decode(byte[] bytes) throws IOException
    {
        Objects.requireNonNull(bytes, "bytes");

        if (bytes.length < MAGIC.length || !Arrays.equals(MAGIC, Arrays.copyOf(bytes, MAGIC.length)))
        {
            throw new IOException("Invalid LVC hash index magic");
        }

        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(bytes, MAGIC.length, bytes.length - MAGIC.length));
             DataInputStream in = new DataInputStream(inflater))
        {
            int count = readVarUInt(in);
            TreeMap<String, String> fullHashes = new TreeMap<>();
            TreeMap<String, String> trackedHashes = new TreeMap<>();
            int previousX = 0;
            int previousY = 0;
            int previousZ = 0;

            for (int i = 0; i < count; i++)
            {
                int x = previousX + readVarInt(in);
                int y = previousY + readVarInt(in);
                int z = previousZ + readVarInt(in);
                LvcChunkCoordinate coordinate = new LvcChunkCoordinate(x, y, z);
                String key = coordinate.key();
                String fullHash = objectId(readHashBytes(in));
                String trackedHash = objectId(readHashBytes(in));

                if (fullHashes.put(key, fullHash) != null || trackedHashes.put(key, trackedHash) != null)
                {
                    throw new IOException("Duplicate LVC hash index coordinate: " + key);
                }

                previousX = x;
                previousY = y;
                previousZ = z;
            }

            if (in.read() != -1)
            {
                throw new IOException("Trailing bytes after LVC hash index payload");
            }

            return new HashRefs(Map.copyOf(fullHashes), Map.copyOf(trackedHashes));
        }
    }

    public static String objectId(byte[] hashBytes)
    {
        Objects.requireNonNull(hashBytes, "hashBytes");

        if (hashBytes.length != HASH_BYTES)
        {
            throw new IllegalArgumentException("LVC hash index SHA-256 payload must be 32 bytes");
        }

        return LvcChunkStore.HASH_ALGORITHM + ":" + HEX.formatHex(hashBytes);
    }

    public static byte[] objectHashBytes(String objectId)
    {
        Objects.requireNonNull(objectId, "objectId");
        String prefix = LvcChunkStore.HASH_ALGORITHM + ":";

        if (!objectId.startsWith(prefix))
        {
            throw new IllegalArgumentException("LVC hash index object id must start with " + prefix);
        }

        String hex = objectId.substring(prefix.length());

        if (hex.length() != HASH_BYTES * 2 || !isLowercaseHex(hex))
        {
            throw new IllegalArgumentException("LVC hash index object id must contain a lowercase 64-character SHA-256 hex value");
        }

        return HEX.parseHex(hex);
    }

    private static TreeMap<LvcChunkCoordinate, HashPair> sortRefs(Map<String, String> fullHashes, Map<String, String> trackedHashes)
    {
        TreeMap<LvcChunkCoordinate, HashPair> sorted = new TreeMap<>();

        for (Map.Entry<String, String> entry : fullHashes.entrySet())
        {
            LvcChunkCoordinate coordinate = LvcChunkCoordinate.parse(entry.getKey());
            HashPair previous = sorted.put(coordinate, new HashPair(entry.getValue(), trackedHashes.get(entry.getKey())));

            if (previous != null)
            {
                throw new IllegalArgumentException("Duplicate LVC hash index coordinate: " + entry.getKey());
            }
        }

        return sorted;
    }

    private static byte[] readHashBytes(DataInputStream in) throws IOException
    {
        byte[] bytes = in.readNBytes(HASH_BYTES);

        if (bytes.length != HASH_BYTES)
        {
            throw new EOFException("Unexpected end of LVC hash index SHA-256 payload");
        }

        return bytes;
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

        throw new IOException("LVC hash index varuint is too long");
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
                throw new IOException("LVC hash index varint is too long");
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

    private static boolean isLowercaseHex(String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);

            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
            {
                return false;
            }
        }

        return true;
    }

    public record HashRefs(Map<String, String> fullHashes, Map<String, String> trackedHashes)
    {
        public HashRefs
        {
            fullHashes = Map.copyOf(fullHashes);
            trackedHashes = Map.copyOf(trackedHashes);

            if (!fullHashes.keySet().equals(trackedHashes.keySet()))
            {
                throw new IllegalArgumentException("LVC hash index full/tracked hash keys must match");
            }
        }
    }

    private record HashPair(String fullHash, String trackedHash)
    {
    }
}
