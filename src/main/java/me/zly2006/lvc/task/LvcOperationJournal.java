package me.zly2006.lvc.task;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

public final class LvcOperationJournal
{
    public static final String JOURNAL_FILE = "lvc-operation.json";
    public static final String STAGING_DIRECTORY = "lvc-staging";
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final String PHASE_MERGE_GIT = "merge_git";
    private static final String BACKUP_SUFFIX = ".bak";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String CORRUPT_SUFFIX = ".corrupt-";
    private static final int BACKUP_GENERATIONS = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LvcOperationJournal()
    {
    }

    public static Entry write(Path repositoryDirectory, Operation operation, @Nullable String targetCommit,
                              @Nullable String phase) throws IOException
    {
        return write(repositoryDirectory, operation, targetCommit, null, phase);
    }

    public static Entry write(Path repositoryDirectory, Operation operation, @Nullable String targetCommit,
                              @Nullable String targetBranch, @Nullable String phase) throws IOException
    {
        return write(repositoryDirectory, operation, targetCommit, targetBranch, null, null, phase);
    }

    public static Entry write(Path repositoryDirectory, Operation operation, @Nullable String targetCommit,
                              @Nullable String targetBranch, @Nullable String sourceBranch,
                              @Nullable String previousHead, @Nullable String phase) throws IOException
    {
        return write(repositoryDirectory, operation, targetCommit, targetBranch, sourceBranch, previousHead, null, phase);
    }

    public static Entry writeCheckout(Path repositoryDirectory, @Nullable String targetCommit,
                                      @Nullable String targetBranch, @Nullable String previousHead,
                                      @Nullable String previousBranch, @Nullable String phase) throws IOException
    {
        return write(repositoryDirectory, Operation.CHECKOUT, targetCommit, targetBranch, null, previousHead, previousBranch, phase);
    }

    public static Entry writeDeleteVersion(Path repositoryDirectory, @Nullable String targetCommit,
                                           @Nullable String targetBranch, @Nullable String previousHead,
                                           @Nullable String previousBranch, @Nullable String phase) throws IOException
    {
        return write(repositoryDirectory, Operation.DELETE_VERSION, targetCommit, targetBranch, null, previousHead, previousBranch, phase);
    }

    private static Entry write(Path repositoryDirectory, Operation operation, @Nullable String targetCommit,
                               @Nullable String targetBranch, @Nullable String sourceBranch,
                               @Nullable String previousHead, @Nullable String previousBranch,
                               @Nullable String phase) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(operation, "operation");

        Entry unsigned = new Entry(CURRENT_SCHEMA_VERSION, operation.name(),
                repositoryDirectory.toAbsolutePath().normalize().toString(), targetCommit, targetBranch,
                sourceBranch, previousHead, previousBranch, phase, Instant.now().toString(), null);
        Entry entry = unsigned.withChecksum(checksum(unsigned));
        Path path = journalPath(repositoryDirectory);
        Files.createDirectories(path.getParent());
        writeAtomic(path, GSON.toJson(entry));
        return entry;
    }

    @Nullable
    public static Entry read(Path repositoryDirectory) throws IOException
    {
        List<Path> corruptPaths = new ArrayList<>();

        for (Path path : journalCandidatePaths(repositoryDirectory))
        {
            if (!Files.isRegularFile(path))
            {
                continue;
            }

            try
            {
                return readJournalFile(path);
            }
            catch (IOException e)
            {
                corruptPaths.add(path);
            }
        }

        if (!corruptPaths.isEmpty())
        {
            throw new CorruptJournalException(repositoryDirectory, corruptPaths);
        }

        return null;
    }

    public static void delete(Path repositoryDirectory) throws IOException
    {
        for (Path path : journalCleanupPaths(repositoryDirectory))
        {
            Files.deleteIfExists(path);
        }
    }

    public static void quarantineCorruptJournals(Path repositoryDirectory, List<Path> corruptPaths) throws IOException
    {
        String suffix = CORRUPT_SUFFIX + Instant.now().toEpochMilli();

        for (Path path : corruptPaths)
        {
            if (Files.exists(path))
            {
                Files.move(path, path.resolveSibling(path.getFileName() + suffix), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        for (Path path : journalCleanupPaths(repositoryDirectory))
        {
            if (path.getFileName().toString().endsWith(TEMP_SUFFIX))
            {
                Files.deleteIfExists(path);
            }
        }
    }

    public static Path stagingDirectory(Path repositoryDirectory, LvcOperationHandle handle)
    {
        return gitLocalDirectory(repositoryDirectory).resolve(STAGING_DIRECTORY).resolve(handle.id().toString());
    }

    public static Path gitLocalDirectory(Path repositoryDirectory)
    {
        Path gitDirectory = repositoryDirectory.resolve(".git");
        return Files.isDirectory(gitDirectory) ? gitDirectory : repositoryDirectory;
    }

    private static Path journalPath(Path repositoryDirectory)
    {
        return gitLocalDirectory(repositoryDirectory).resolve(JOURNAL_FILE);
    }

    private static Path plainRootJournalPath(Path repositoryDirectory)
    {
        return repositoryDirectory.resolve(JOURNAL_FILE);
    }

    private static Path hiddenRootJournalPath(Path repositoryDirectory)
    {
        return repositoryDirectory.resolve("." + JOURNAL_FILE);
    }

    private static List<Path> journalCandidatePaths(Path repositoryDirectory)
    {
        Path gitJournal = journalPath(repositoryDirectory);
        Path plainRootJournal = plainRootJournalPath(repositoryDirectory);
        Path hiddenRootJournal = hiddenRootJournalPath(repositoryDirectory);
        return List.of(
                gitJournal,
                backupPath(gitJournal, 1),
                backupPath(gitJournal, 2),
                backupPath(gitJournal, 3),
                plainRootJournal,
                backupPath(plainRootJournal, 1),
                backupPath(plainRootJournal, 2),
                backupPath(plainRootJournal, 3),
                hiddenRootJournal,
                backupPath(hiddenRootJournal, 1),
                backupPath(hiddenRootJournal, 2),
                backupPath(hiddenRootJournal, 3)
        );
    }

    private static List<Path> journalCleanupPaths(Path repositoryDirectory)
    {
        Path gitJournal = journalPath(repositoryDirectory);
        Path plainRootJournal = plainRootJournalPath(repositoryDirectory);
        Path hiddenRootJournal = hiddenRootJournalPath(repositoryDirectory);
        return List.of(
                gitJournal,
                backupPath(gitJournal, 1),
                backupPath(gitJournal, 2),
                backupPath(gitJournal, 3),
                tempPath(gitJournal),
                plainRootJournal,
                backupPath(plainRootJournal, 1),
                backupPath(plainRootJournal, 2),
                backupPath(plainRootJournal, 3),
                tempPath(plainRootJournal),
                hiddenRootJournal,
                backupPath(hiddenRootJournal, 1),
                backupPath(hiddenRootJournal, 2),
                backupPath(hiddenRootJournal, 3),
                tempPath(hiddenRootJournal)
        );
    }

    private static Entry readJournalFile(Path path) throws IOException
    {
        try
        {
            Entry entry = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Entry.class);
            validateEntry(path, entry);
            return entry;
        }
        catch (JsonParseException | IllegalArgumentException | NullPointerException e)
        {
            throw new IOException("Invalid LVC operation journal: " + path, e);
        }
    }

    private static void validateEntry(Path path, @Nullable Entry entry)
    {
        if (entry == null || entry.operation() == null || entry.operation().isBlank())
        {
            throw new IllegalArgumentException("Missing operation in " + path);
        }

        Operation.valueOf(entry.operation());

        if (entry.schemaVersion() != null && entry.schemaVersion() > CURRENT_SCHEMA_VERSION)
        {
            throw new IllegalArgumentException("Unsupported LVC operation journal schema " + entry.schemaVersion() + " in " + path);
        }

        if (entry.checksum() != null && !entry.checksum().isBlank() && !entry.checksum().equals(checksum(entry.withChecksum(null))))
        {
            throw new IllegalArgumentException("Invalid LVC operation journal checksum in " + path);
        }
    }

    private static void writeAtomic(Path path, String json) throws IOException
    {
        Path temp = tempPath(path);
        Path backup = backupPath(path, 1);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        Files.createDirectories(path.getParent());

        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))
        {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }

        rotateBackups(path);
        moveReplacing(temp, path);
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        forceFile(backup);
        forceDirectory(path.getParent());
    }

    private static void rotateBackups(Path path) throws IOException
    {
        for (int generation = BACKUP_GENERATIONS; generation >= 2; generation--)
        {
            Path source = backupPath(path, generation - 1);
            Path target = backupPath(path, generation);

            if (Files.exists(source))
            {
                moveReplacing(source, target);
            }
        }

        if (Files.exists(path))
        {
            Files.copy(path, backupPath(path, 1), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            forceFile(backupPath(path, 1));
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceDirectory(Path directory)
    {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ))
        {
            channel.force(true);
        }
        catch (FileNotFoundException ignored)
        {
        }
        catch (IOException ignored)
        {
        }
    }

    private static void forceFile(Path path)
    {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ))
        {
            channel.force(true);
        }
        catch (IOException ignored)
        {
        }
    }

    private static Path backupPath(Path path, int generation)
    {
        return path.resolveSibling(path.getFileName() + (generation <= 1 ? BACKUP_SUFFIX : BACKUP_SUFFIX + generation));
    }

    private static Path tempPath(Path path)
    {
        return path.resolveSibling(path.getFileName() + TEMP_SUFFIX);
    }

    private static String checksum(Entry entry)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateChecksum(digest, entry.schemaVersion() == null ? "" : entry.schemaVersion().toString());
            updateChecksum(digest, entry.operation());
            updateChecksum(digest, entry.repositoryDirectory());
            updateChecksum(digest, entry.targetCommit());
            updateChecksum(digest, entry.targetBranch());
            updateChecksum(digest, entry.sourceBranch());
            updateChecksum(digest, entry.previousHead());
            updateChecksum(digest, entry.previousBranch());
            updateChecksum(digest, entry.phase());
            updateChecksum(digest, entry.startedAt());
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void updateChecksum(MessageDigest digest, @Nullable String value)
    {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    public static final class CorruptJournalException extends IOException
    {
        private final Path repositoryDirectory;
        private final List<Path> corruptPaths;

        private CorruptJournalException(Path repositoryDirectory, List<Path> corruptPaths)
        {
            super("LVC operation journal is unreadable: " + corruptPaths);
            this.repositoryDirectory = repositoryDirectory;
            this.corruptPaths = List.copyOf(corruptPaths);
        }

        public Path repositoryDirectory()
        {
            return this.repositoryDirectory;
        }

        public List<Path> corruptPaths()
        {
            return this.corruptPaths;
        }
    }

    public enum Operation
    {
        INIT,
        SAVE,
        UPDATE_AREAS,
        CHECKOUT,
        DISCARD,
        CLEAR,
        MERGE,
        DELETE_VERSION
    }

    public record Entry(@Nullable Integer schemaVersion, String operation, String repositoryDirectory,
                        @Nullable String targetCommit, @Nullable String targetBranch,
                        @Nullable String sourceBranch, @Nullable String previousHead,
                        @Nullable String previousBranch, @Nullable String phase, String startedAt,
                        @Nullable String checksum)
    {
        private Entry withChecksum(@Nullable String checksum)
        {
            return new Entry(this.schemaVersion, this.operation, this.repositoryDirectory, this.targetCommit,
                    this.targetBranch, this.sourceBranch, this.previousHead, this.previousBranch,
                    this.phase, this.startedAt, checksum);
        }
    }
}
