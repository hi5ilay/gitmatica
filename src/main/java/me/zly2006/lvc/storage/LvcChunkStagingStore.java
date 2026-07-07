package me.zly2006.lvc.storage;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;

public final class LvcChunkStagingStore
{
    private final Path repositoryDirectory;
    private final Path stagingDirectory;

    public LvcChunkStagingStore(Path repositoryDirectory, Path stagingDirectory)
    {
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory");
    }

    public Path stagingDirectory()
    {
        return this.stagingDirectory;
    }

    public String writeObject(String objectId, byte[] bytes) throws IOException
    {
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(bytes, "bytes");
        Path path = this.stagingObjectPath(objectId);

        if (!Files.exists(path))
        {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        }

        return objectId;
    }

    public void publish(String objectId) throws IOException
    {
        Path target = LvcChunkStore.objectPath(this.repositoryDirectory, objectId);

        if (Files.exists(target))
        {
            return;
        }

        Path source = this.stagingObjectPath(objectId);

        if (!Files.isRegularFile(source))
        {
            throw new IOException("Missing staged LVC object: " + objectId);
        }

        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    public void publishAll(Map<String, String> objectIdsByChunk) throws IOException
    {
        for (String objectId : objectIdsByChunk.values())
        {
            this.publish(objectId);
        }
    }

    public void cleanup() throws IOException
    {
        deleteRecursivelyIfExists(this.stagingDirectory);
    }

    public static void deleteRecursivelyIfExists(Path directory) throws IOException
    {
        if (!Files.exists(directory))
        {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                if (exc != null)
                {
                    throw exc;
                }

                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path stagingObjectPath(String objectId)
    {
        return this.stagingDirectory.resolve(LvcChunkStore.objectRepositoryPath(objectId));
    }
}
