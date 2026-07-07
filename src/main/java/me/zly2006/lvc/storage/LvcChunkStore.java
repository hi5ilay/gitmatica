package me.zly2006.lvc.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class LvcChunkStore
{
    public static final String OBJECTS_DIRECTORY = "objects";
    public static final String HASH_ALGORITHM = "sha256";
    public static final String EXTENSION = ".lvcchunk";

    private LvcChunkStore()
    {
    }

    public static String writeObjectIfMissing(Path repositoryDirectory, String objectId, byte[] bytes) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(bytes, "bytes");
        Path objectPath = objectPath(repositoryDirectory, objectId);

        if (!Files.exists(objectPath))
        {
            Files.createDirectories(objectPath.getParent());
            Files.write(objectPath, bytes);
        }

        return objectId;
    }

    public static byte[] readObject(Path repositoryDirectory, String objectId) throws IOException
    {
        return Files.readAllBytes(objectPath(repositoryDirectory, objectId));
    }

    public static Path objectPath(Path repositoryDirectory, String objectId)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        String hex = objectHex(objectId);
        return repositoryDirectory.resolve(OBJECTS_DIRECTORY).resolve(HASH_ALGORITHM).resolve(hex.substring(0, 2)).resolve(hex + EXTENSION);
    }

    public static String objectRepositoryPath(String objectId)
    {
        String hex = objectHex(objectId);
        return OBJECTS_DIRECTORY + "/" + HASH_ALGORITHM + "/" + hex.substring(0, 2) + "/" + hex + EXTENSION;
    }

    public static String objectId(byte[] bytes)
    {
        return HASH_ALGORITHM + ":" + sha256Hex(bytes);
    }

    public static String sha256Hex(byte[] bytes)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String objectHex(String objectId)
    {
        Objects.requireNonNull(objectId, "objectId");

        if (!objectId.startsWith(HASH_ALGORITHM + ":"))
        {
            throw new IllegalArgumentException("LVC object id must start with sha256:");
        }

        String hex = objectId.substring((HASH_ALGORITHM + ":").length());

        if (hex.length() != 64 || !isLowercaseHex(hex))
        {
            throw new IllegalArgumentException("LVC object id must contain a lowercase 64-character SHA-256 hex value");
        }

        return hex;
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
}
