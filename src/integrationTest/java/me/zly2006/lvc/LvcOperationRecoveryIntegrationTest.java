package me.zly2006.lvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.zly2006.lvc.storage.LvcChunkStagingStore;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.task.LvcOperationHandle;
import me.zly2006.lvc.task.LvcOperationJournal;
import me.zly2006.lvc.task.LvcRefreshMarker;

final class LvcOperationRecoveryIntegrationTest
{
    private LvcOperationRecoveryIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("operation journal writes primary backup and no temp", LvcOperationRecoveryIntegrationTest::operationJournalWritesPrimaryBackupAndNoTemp);
        IntegrationTestSupport.run("operation journal rejects checksum corruption", LvcOperationRecoveryIntegrationTest::operationJournalRejectsChecksumCorruption);
        IntegrationTestSupport.run("operation journal can be written before git directory exists", LvcOperationRecoveryIntegrationTest::operationJournalCanBeWrittenBeforeGitDirectoryExists);
        IntegrationTestSupport.run("operation journal ignores temp only crash leftovers", LvcOperationRecoveryIntegrationTest::operationJournalIgnoresTempOnlyCrashLeftovers);
        IntegrationTestSupport.run("operation journal reads primary when backup was never copied", LvcOperationRecoveryIntegrationTest::operationJournalReadsPrimaryWhenBackupWasNeverCopied);
        IntegrationTestSupport.run("operation journal falls back to backup when primary is missing", LvcOperationRecoveryIntegrationTest::operationJournalFallsBackToBackupWhenPrimaryIsMissing);
        IntegrationTestSupport.run("operation journal falls back to backup when primary is corrupt", LvcOperationRecoveryIntegrationTest::operationJournalFallsBackToBackupWhenPrimaryIsCorrupt);
        IntegrationTestSupport.run("operation journal quarantines corrupt recovery data", LvcOperationRecoveryIntegrationTest::operationJournalQuarantinesCorruptRecoveryData);
        IntegrationTestSupport.run("operation journal delete clears active backup temp and legacy locations", LvcOperationRecoveryIntegrationTest::operationJournalDeleteClearsActiveBackupTempAndLegacyLocations);
        IntegrationTestSupport.run("interrupted staging cleanup preserves published objects", LvcOperationRecoveryIntegrationTest::interruptedStagingCleanupPreservesPublishedObjects);
        IntegrationTestSupport.run("refresh marker survives until overlay cleanup", LvcOperationRecoveryIntegrationTest::refreshMarkerSurvivesUntilOverlayCleanup);
    }

    private static void operationJournalWritesPrimaryBackupAndNoTemp() throws Exception
    {
        Path repoDir = createGitRepositoryDirectory("lvc-journal-write-");
        LvcOperationJournal.Entry written = LvcOperationJournal.writeCheckout(
                repoDir,
                "1234567890abcdef1234567890abcdef12345678",
                "feature/recovery",
                "abcdef1234567890abcdef1234567890abcdef12",
                "main",
                "restore"
        );

        Path journal = gitJournalPath(repoDir);
        Path backup = backupPath(journal);
        Path temp = tempPath(journal);
        LvcOperationJournal.Entry read = LvcOperationJournal.read(repoDir);

        IntegrationTestSupport.assertTrue(Files.isRegularFile(journal), "journal primary should exist");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(backup), "journal backup should exist");
        IntegrationTestSupport.assertTrue(!Files.exists(temp), "journal temp file should not remain after successful write");
        IntegrationTestSupport.assertEquals(LvcOperationJournal.CURRENT_SCHEMA_VERSION, read.schemaVersion(), "journal schema version");
        IntegrationTestSupport.assertTrue(read.checksum() != null && !read.checksum().isBlank(), "journal checksum should be written");
        IntegrationTestSupport.assertEquals(written.operation(), read.operation(), "read operation");
        IntegrationTestSupport.assertEquals(repoDir.toAbsolutePath().normalize().toString(), read.repositoryDirectory(), "read repository directory");
        IntegrationTestSupport.assertEquals("1234567890abcdef1234567890abcdef12345678", read.targetCommit(), "read target commit");
        IntegrationTestSupport.assertEquals("feature/recovery", read.targetBranch(), "read target branch");
        IntegrationTestSupport.assertEquals("abcdef1234567890abcdef1234567890abcdef12", read.previousHead(), "read previous head");
        IntegrationTestSupport.assertEquals("main", read.previousBranch(), "read previous branch");
        IntegrationTestSupport.assertEquals("restore", read.phase(), "read phase");
    }

    private static void operationJournalRejectsChecksumCorruption() throws Exception
    {
        Path repoDir = createGitRepositoryDirectory("lvc-journal-checksum-");
        Path journal = gitJournalPath(repoDir);

        LvcOperationJournal.write(repoDir, LvcOperationJournal.Operation.CHECKOUT,
                "1234567890abcdef1234567890abcdef12345678", "main", "restore");
        Files.delete(backupPath(journal));
        Files.writeString(journal, Files.readString(journal, StandardCharsets.UTF_8)
                .replace("1234567890abcdef1234567890abcdef12345678", "ffffffffffffffffffffffffffffffffffffffff"), StandardCharsets.UTF_8);

        try
        {
            LvcOperationJournal.read(repoDir);
            throw new AssertionError("tampered checksum journal should be rejected");
        }
        catch (LvcOperationJournal.CorruptJournalException e)
        {
            IntegrationTestSupport.assertEquals(1, e.corruptPaths().size(), "tampered primary should be reported as corrupt");
        }
    }

    private static void operationJournalCanBeWrittenBeforeGitDirectoryExists() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-journal-before-git-");

        LvcOperationJournal.write(repoDir, LvcOperationJournal.Operation.INIT, null, "capture");

        Path journal = repoDir.resolve(LvcOperationJournal.JOURNAL_FILE);
        LvcOperationJournal.Entry read = LvcOperationJournal.read(repoDir);

        IntegrationTestSupport.assertTrue(Files.isRegularFile(journal), "pre-git init journal should live at the repository root");
        IntegrationTestSupport.assertEquals(LvcOperationJournal.Operation.INIT.name(), read.operation(), "pre-git journal operation");
        IntegrationTestSupport.assertEquals("capture", read.phase(), "pre-git journal phase");

        Files.createDirectories(repoDir.resolve(".git"));
        LvcOperationJournal.Entry readAfterGitAppears = LvcOperationJournal.read(repoDir);

        IntegrationTestSupport.assertEquals(LvcOperationJournal.Operation.INIT.name(), readAfterGitAppears.operation(), "root journal should still be found after git directory appears");
        IntegrationTestSupport.assertEquals("capture", readAfterGitAppears.phase(), "root journal phase after git directory appears");
    }

    private static void operationJournalIgnoresTempOnlyCrashLeftovers() throws Exception
    {
        Path repoDir = createGitRepositoryDirectory("lvc-journal-temp-");
        Path journal = gitJournalPath(repoDir);
        Files.writeString(tempPath(journal), validJournalJson(repoDir, LvcOperationJournal.Operation.CHECKOUT), StandardCharsets.UTF_8);

        IntegrationTestSupport.assertEquals(null, LvcOperationJournal.read(repoDir), "temp-only journal must not count as committed recovery intent");

        LvcOperationJournal.delete(repoDir);
        IntegrationTestSupport.assertTrue(!Files.exists(tempPath(journal)), "delete should clean temp-only crash leftovers");
    }

    private static void operationJournalReadsPrimaryWhenBackupWasNeverCopied() throws Exception
    {
        Path repoDir = createGitRepositoryDirectory("lvc-journal-primary-only-");
        LvcOperationJournal.write(repoDir, LvcOperationJournal.Operation.CLEAR, null, "clear");
        Files.delete(backupPath(gitJournalPath(repoDir)));

        LvcOperationJournal.Entry read = LvcOperationJournal.read(repoDir);

        IntegrationTestSupport.assertEquals(LvcOperationJournal.Operation.CLEAR.name(), read.operation(), "primary-only journal should be recoverable");
        IntegrationTestSupport.assertEquals("clear", read.phase(), "primary-only journal phase");
    }

    private static void operationJournalFallsBackToBackupWhenPrimaryIsMissing() throws Exception
    {
        Path repoDir = createGitRepositoryDirectory("lvc-journal-backup-");
        Path journal = gitJournalPath(repoDir);
        LvcOperationJournal.write(repoDir, LvcOperationJournal.Operation.DISCARD, "abcdef1234567890", "discard");
        Files.delete(journal);

        LvcOperationJournal.Entry read = LvcOperationJournal.read(repoDir);

        IntegrationTestSupport.assertEquals(LvcOperationJournal.Operation.DISCARD.name(), read.operation(), "backup journal operation");
        IntegrationTestSupport.assertEquals("abcdef1234567890", read.targetCommit(), "backup journal target commit");
        IntegrationTestSupport.assertEquals("discard", read.phase(), "backup journal phase");
    }

    private static void operationJournalFallsBackToBackupWhenPrimaryIsCorrupt() throws Exception
    {
        Path repoDir = createGitRepositoryDirectory("lvc-journal-corrupt-primary-");
        Path journal = gitJournalPath(repoDir);
        LvcOperationJournal.write(repoDir, LvcOperationJournal.Operation.MERGE, "fedcba0987654321", "feature/source", "merge");
        Files.writeString(journal, "{", StandardCharsets.UTF_8);

        LvcOperationJournal.Entry read = LvcOperationJournal.read(repoDir);

        IntegrationTestSupport.assertEquals(LvcOperationJournal.Operation.MERGE.name(), read.operation(), "valid backup should recover corrupt primary");
        IntegrationTestSupport.assertEquals("fedcba0987654321", read.targetCommit(), "valid backup target commit");
        IntegrationTestSupport.assertEquals("feature/source", read.targetBranch(), "valid backup target branch");
        IntegrationTestSupport.assertEquals("merge", read.phase(), "valid backup phase");
    }

    private static void operationJournalQuarantinesCorruptRecoveryData() throws Exception
    {
        Path repoDir = createGitRepositoryDirectory("lvc-journal-corrupt-all-");
        Path journal = gitJournalPath(repoDir);
        Files.writeString(journal, "{", StandardCharsets.UTF_8);
        Files.writeString(backupPath(journal), "{\"operation\":\"NOT_A_REAL_OPERATION\"}", StandardCharsets.UTF_8);
        Files.writeString(tempPath(journal), validJournalJson(repoDir, LvcOperationJournal.Operation.CHECKOUT), StandardCharsets.UTF_8);

        try
        {
            LvcOperationJournal.read(repoDir);
            throw new AssertionError("corrupt primary and backup should throw");
        }
        catch (LvcOperationJournal.CorruptJournalException e)
        {
            IntegrationTestSupport.assertEquals(2, e.corruptPaths().size(), "temp files should not be treated as committed corrupt journals");
            LvcOperationJournal.quarantineCorruptJournals(repoDir, e.corruptPaths());
        }

        IntegrationTestSupport.assertTrue(!Files.exists(journal), "corrupt primary should be moved away");
        IntegrationTestSupport.assertTrue(!Files.exists(backupPath(journal)), "corrupt backup should be moved away");
        IntegrationTestSupport.assertTrue(!Files.exists(tempPath(journal)), "temp garbage should be deleted during quarantine");
        IntegrationTestSupport.assertEquals(null, LvcOperationJournal.read(repoDir), "quarantined recovery data should not block repo use");
        IntegrationTestSupport.assertEquals(2, corruptJournalCount(journal.getParent()), "primary and backup should both be quarantined for diagnosis");
    }

    private static void operationJournalDeleteClearsActiveBackupTempAndLegacyLocations() throws Exception
    {
        Path repoDir = createGitRepositoryDirectory("lvc-journal-delete-");
        List<Path> paths = allCurrentAndLegacyJournalPaths(repoDir);

        for (Path path : paths)
        {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "leftover", StandardCharsets.UTF_8);
        }

        LvcOperationJournal.delete(repoDir);

        for (Path path : paths)
        {
            IntegrationTestSupport.assertTrue(!Files.exists(path), "delete should remove recovery leftover: " + path);
        }
    }

    private static void interruptedStagingCleanupPreservesPublishedObjects() throws Exception
    {
        Path repoDir = createGitRepositoryDirectory("lvc-staging-cleanup-");
        LvcOperationHandle handle = new LvcOperationHandle(UUID.randomUUID(), "LVC Crash Test", repoDir);
        Path stagingRoot = repoDir.resolve(".git").resolve(LvcOperationJournal.STAGING_DIRECTORY);
        LvcChunkStagingStore stagingStore = new LvcChunkStagingStore(repoDir, LvcOperationJournal.stagingDirectory(repoDir, handle));
        byte[] objectBytes = "published object".getBytes(StandardCharsets.UTF_8);
        String objectId = LvcChunkStore.objectId(objectBytes);

        stagingStore.writeObject(objectId, objectBytes);
        LvcChunkStore.writeObjectIfMissing(repoDir, objectId, objectBytes);
        LvcOperationJournal.write(repoDir, LvcOperationJournal.Operation.SAVE, null, "capture");

        LvcChunkStagingStore.deleteRecursivelyIfExists(stagingRoot);
        LvcOperationJournal.delete(repoDir);

        IntegrationTestSupport.assertTrue(!Files.exists(stagingRoot), "interrupted staging root should be removed");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(LvcChunkStore.objectPath(repoDir, objectId)), "published objects must not be removed by staging cleanup");
        IntegrationTestSupport.assertTrue(!Files.exists(gitJournalPath(repoDir)), "journal should be cleared after cleanup");
        IntegrationTestSupport.assertTrue(!Files.exists(backupPath(gitJournalPath(repoDir))), "journal backup should be cleared after cleanup");
    }

    private static void refreshMarkerSurvivesUntilOverlayCleanup() throws Exception
    {
        Path repoDir = createGitRepositoryDirectory("lvc-refresh-marker-");

        LvcRefreshMarker.write(repoDir, "checkout", "1234567890abcdef");

        IntegrationTestSupport.assertTrue(LvcRefreshMarker.exists(repoDir), "refresh marker should exist after write");
        LvcRefreshMarker.Entry entry = LvcRefreshMarker.read(repoDir);
        IntegrationTestSupport.assertEquals("checkout", entry.reason(), "refresh marker reason");
        IntegrationTestSupport.assertEquals("1234567890abcdef", entry.targetCommit(), "refresh marker target commit");

        LvcRefreshMarker.delete(repoDir);

        IntegrationTestSupport.assertTrue(!LvcRefreshMarker.exists(repoDir), "refresh marker should be removed after successful overlay refresh");
    }

    private static Path createGitRepositoryDirectory(String prefix) throws Exception
    {
        Path repoDir = Files.createTempDirectory(prefix);
        Files.createDirectories(repoDir.resolve(".git"));
        return repoDir;
    }

    private static Path gitJournalPath(Path repoDir)
    {
        return repoDir.resolve(".git").resolve(LvcOperationJournal.JOURNAL_FILE);
    }

    private static Path backupPath(Path path)
    {
        return path.resolveSibling(path.getFileName() + ".bak");
    }

    private static Path backupPath(Path path, int generation)
    {
        return path.resolveSibling(path.getFileName() + (generation <= 1 ? ".bak" : ".bak" + generation));
    }

    private static Path tempPath(Path path)
    {
        return path.resolveSibling(path.getFileName() + ".tmp");
    }

    private static String validJournalJson(Path repoDir, LvcOperationJournal.Operation operation)
    {
        return """
                {
                  "operation": "%s",
                  "repositoryDirectory": "%s",
                  "targetCommit": "0123456789abcdef",
                  "targetBranch": "main",
                  "phase": "restore",
                  "startedAt": "2026-06-12T00:00:00Z"
                }
                """.formatted(operation.name(), repoDir.toAbsolutePath().normalize().toString().replace("\\", "\\\\"));
    }

    private static List<Path> allCurrentAndLegacyJournalPaths(Path repoDir)
    {
        List<Path> journals = List.of(
                repoDir.resolve(".git").resolve(LvcOperationJournal.JOURNAL_FILE),
                repoDir.resolve(LvcOperationJournal.JOURNAL_FILE),
                repoDir.resolve("." + LvcOperationJournal.JOURNAL_FILE)
        );
        List<Path> result = new ArrayList<>();

        for (Path journal : journals)
        {
            result.add(journal);
            result.add(backupPath(journal));
            result.add(backupPath(journal, 2));
            result.add(backupPath(journal, 3));
            result.add(tempPath(journal));
        }

        return result;
    }

    private static int corruptJournalCount(Path directory) throws Exception
    {
        try (var stream = Files.list(directory))
        {
            return (int) stream
                    .filter(path -> path.getFileName().toString().contains(".corrupt-"))
                    .count();
        }
    }
}
