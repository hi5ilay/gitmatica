package me.zly2006.rvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

public class RvcRepositoryIntegrationTest
{
    public static void main(String[] args) throws Exception
    {
        IntegrationTestSupport.run("init writes RVC files and creates the first Git commit", RvcRepositoryIntegrationTest::initWritesRvcFilesAndCreatesTheFirstGitCommit);
        IntegrationTestSupport.run("init commits an index structure saved as vanilla nbt", RvcRepositoryIntegrationTest::initCommitsAnIndexStructureSavedAsVanillaNbt);
        IntegrationTestSupport.run("raw index bytes are rejected instead of committed", RvcRepositoryIntegrationTest::rawIndexBytesAreRejectedInsteadOfCommitted);
        IntegrationTestSupport.run("commit uses current branch HEAD and history lists newest commits first", RvcRepositoryIntegrationTest::commitUsesCurrentBranchHeadAndHistoryListsNewestFirst);
        IntegrationTestSupport.run("project service lists valid repositories and pushes to a remote", RvcRepositoryIntegrationTest::projectServiceListsValidRepositoriesAndPushesToRemote);
        IntegrationTestSupport.run("project service deletes valid repositories recursively", RvcRepositoryIntegrationTest::projectServiceDeletesValidRepositoriesRecursively);
        IntegrationTestSupport.run("remote URL config can be created and edited", RvcRepositoryIntegrationTest::remoteUrlConfigCanBeCreatedAndEdited);
        IntegrationTestSupport.run("push uses the last active branch while HEAD is detached", RvcRepositoryIntegrationTest::pushUsesLastActiveBranchWhileHeadIsDetached);
        IntegrationTestSupport.run("legacy local selection is local-only and ignored by Git", RvcRepositoryIntegrationTest::legacyLocalSelectionIsLocalOnlyAndIgnoredByGit);
        IntegrationTestSupport.run("sub-regions are versioned in index json and master origin is local only", RvcRepositoryIntegrationTest::subRegionsAreVersionedInIndexJsonAndMasterOriginIsLocalOnly);
        IntegrationTestSupport.run("untracked gaps between sub-regions are not tracked", RvcRepositoryIntegrationTest::untrackedGapsBetweenSubRegionsAreNotTracked);
        IntegrationTestSupport.run("checkout updates the working tree while preserving visible history", RvcRepositoryIntegrationTest::checkoutUpdatesWorkingTreeWhilePreservingVisibleHistory);
        IntegrationTestSupport.run("commit history remains scoped to the branch after checkout", RvcRepositoryIntegrationTest::commitHistoryRemainsScopedToBranchAfterCheckout);
        IntegrationTestSupport.run("commit after checkout is rejected while HEAD is detached", RvcRepositoryIntegrationTest::commitAfterCheckoutIsRejectedWhileHeadIsDetached);
        IntegrationTestSupport.run("reset working tree to HEAD discards tracked dirty changes", RvcRepositoryIntegrationTest::resetWorkingTreeToHeadDiscardsTrackedDirtyChanges);
        IntegrationTestSupport.run("checkout can continue after resetting a dirty working tree", RvcRepositoryIntegrationTest::checkoutCanContinueAfterResettingDirtyWorkingTree);
        RvcSemanticStorageIntegrationTest.runAll();
    }

    private static void rawIndexBytesAreRejectedInsteadOfCommitted() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-invalid-structure-");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderBadBytes", UUID.fromString("123e4567-e89b-12d3-a456-426614174010"));

        try
        {
            RvcRepository.init(repoDir, "Invalid Structure", "structure bytes".getBytes(StandardCharsets.UTF_8), player);
            throw new AssertionError("raw non-NBT index bytes should be rejected");
        }
        catch (IOException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("Not in GZIP format") || e.getMessage().contains("valid vanilla structure"), "invalid structure error should explain the rejected data");
        }
    }

    private static void commitUsesCurrentBranchHeadAndHistoryListsNewestFirst() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-commit-parent-");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderThree", UUID.fromString("123e4567-e89b-12d3-a456-426614174004"));

        RevCommit first = RvcRepository.commit(repoDir, "Parent Driven", createTinyStructureTemplate(), player, "init");
        RevCommit second = RvcRepository.commit(repoDir, "Parent Driven", createTinyStructureTemplate(), player, "update from world");
        RevCommit third = RvcRepository.commit(repoDir, "Parent Driven", createTinyStructureTemplate(), player, "ignore stale supplied parent");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + RvcProjectService.DEFAULT_BRANCH, repository.getFullBranch(), "initial commit should be on the default branch");

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit parsedSecond = revWalk.parseCommit(repository.resolve(Constants.HEAD));
                IntegrationTestSupport.assertEquals(third.getId(), parsedSecond.getId(), "HEAD should point at the newest commit");
                IntegrationTestSupport.assertEquals(1, parsedSecond.getParentCount(), "new commit should have one parent");
                IntegrationTestSupport.assertEquals(second.getId(), parsedSecond.getParent(0).getId(), "commit should parent the current branch HEAD, not a stale supplied parent");
            }
        }

        List<RvcProjectService.CommitInfo> history = RvcProjectService.listCommits(repoDir);
        IntegrationTestSupport.assertEquals(3, history.size(), "history size");
        IntegrationTestSupport.assertEquals(third.getName(), history.get(0).id(), "newest commit first");
        IntegrationTestSupport.assertEquals("ignore stale supplied parent", history.get(0).message(), "newest commit message");
        IntegrationTestSupport.assertEquals(first.getName(), history.get(2).id(), "oldest commit last");
    }

    private static void projectServiceListsValidRepositoriesAndPushesToRemote() throws Exception
    {
        Path runDir = Files.createTempDirectory("rvc-run-");
        Path reposDir = RvcProjectService.reposDirectory(runDir);
        Path validRepo = reposDir.resolve("Valid Project");
        Path invalidRepo = reposDir.resolve("Not Git");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderFour", UUID.fromString("123e4567-e89b-12d3-a456-426614174005"));

        RevCommit commit = RvcRepository.commit(validRepo, "Valid Project", createTinyStructureTemplate(), player, "init");
        Files.createDirectories(invalidRepo);

        List<RvcProjectService.Project> projects = RvcProjectService.listProjects(runDir);
        IntegrationTestSupport.assertEquals(1, projects.size(), "only valid git repositories should be listed");
        IntegrationTestSupport.assertEquals("Valid Project", projects.get(0).name(), "listed project name");
        IntegrationTestSupport.assertEquals(validRepo, projects.get(0).directory(), "listed project directory");

        Path remoteDir = Files.createTempDirectory("rvc-remote-").resolve("remote.git");
        List<String> pushStatuses;
        try (Git ignored = Git.init().setBare(true).setDirectory(remoteDir.toFile()).call())
        {
            RvcProjectService.setRemote(validRepo, remoteDir.toUri().toString());
            pushStatuses = RvcProjectService.push(validRepo);
        }

        try (Repository remoteRepository = new FileRepositoryBuilder().setGitDir(remoteDir.toFile()).build())
        {
            ObjectId defaultBranchId = remoteRepository.resolve(Constants.R_HEADS + RvcProjectService.DEFAULT_BRANCH);
            IntegrationTestSupport.assertEquals(commit.getId(), defaultBranchId, "remote should receive pushed main branch");
            IntegrationTestSupport.assertTrue(pushStatuses.stream().anyMatch(status -> status.contains(Constants.R_HEADS + RvcProjectService.DEFAULT_BRANCH)), "push status should report the pushed branch");
        }
    }

    private static void projectServiceDeletesValidRepositoriesRecursively() throws Exception
    {
        Path runDir = Files.createTempDirectory("rvc-delete-run-");
        Path validRepo = RvcProjectService.repositoryDirectory(runDir, "Delete Me");
        Path invalidRepo = RvcProjectService.reposDirectory(runDir).resolve("Not RVC");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderDelete", UUID.fromString("123e4567-e89b-12d3-a456-426614174015"));

        RvcRepository.commit(validRepo, "Delete Me", createTinyStructureTemplate(), player, "init");
        Files.createDirectories(validRepo.resolve("scratch/nested"));
        Files.writeString(validRepo.resolve("scratch/nested/untracked.txt"), "delete this", StandardCharsets.UTF_8);
        Files.createDirectories(invalidRepo.resolve("scratch"));
        Files.writeString(invalidRepo.resolve("scratch/keep.txt"), "keep this", StandardCharsets.UTF_8);

        RvcProjectService.deleteProjectRepository(runDir, validRepo);

        IntegrationTestSupport.assertTrue(!Files.exists(validRepo), "delete project should remove the whole repository directory");

        try
        {
            RvcProjectService.deleteProjectRepository(runDir, invalidRepo);
            throw new AssertionError("invalid RVC project directory should not be deleted");
        }
        catch (IOException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("Not a valid RVC project repository"), "invalid delete error should explain rejected directory");
        }

        IntegrationTestSupport.assertTrue(Files.exists(invalidRepo.resolve("scratch/keep.txt")), "invalid project delete must leave files intact");
    }

    private static void remoteUrlConfigCanBeCreatedAndEdited() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-remote-config-");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderRemoteConfig", UUID.fromString("123e4567-e89b-12d3-a456-426614174014"));

        RvcRepository.commit(repoDir, "Remote Config", createTinyStructureTemplate(), player, "init");

        String firstRemoteUrl = "git@github.com:example/first.git";
        String secondRemoteUrl = "https://github.com/example/second.git";

        IntegrationTestSupport.assertTrue(!RvcProjectService.hasRemote(repoDir), "new repo should not report a remote");

        RvcProjectService.setRemote(repoDir, "  " + firstRemoteUrl + "  ");

        IntegrationTestSupport.assertTrue(RvcProjectService.hasRemote(repoDir), "set remote should make origin available");
        IntegrationTestSupport.assertEquals(firstRemoteUrl, RvcProjectService.remoteOriginUrl(repoDir), "remote URL should be trimmed before saving");

        RvcProjectService.setRemote(repoDir, secondRemoteUrl);

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(secondRemoteUrl, repository.getConfig().getString("remote", "origin", "url"), "edited remote URL");
            IntegrationTestSupport.assertEquals("+refs/heads/*:refs/remotes/origin/*", repository.getConfig().getString("remote", "origin", "fetch"), "origin fetch refspec");
            IntegrationTestSupport.assertEquals("origin", repository.getConfig().getString("branch", RvcProjectService.DEFAULT_BRANCH, "remote"), "current branch remote");
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + RvcProjectService.DEFAULT_BRANCH, repository.getConfig().getString("branch", RvcProjectService.DEFAULT_BRANCH, "merge"), "current branch merge ref");
        }

        try
        {
            RvcProjectService.setRemote(repoDir, "   ");
            throw new AssertionError("blank remote URL should be rejected");
        }
        catch (IllegalArgumentException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("must not be blank"), "blank remote error should explain the rejected input");
        }

        IntegrationTestSupport.assertEquals(secondRemoteUrl, RvcProjectService.remoteOriginUrl(repoDir), "blank edit should not replace existing remote");
    }

    private static void pushUsesLastActiveBranchWhileHeadIsDetached() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-detached-push-");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderDetachedPush", UUID.fromString("123e4567-e89b-12d3-a456-426614174013"));
        RevCommit first = RvcRepository.commit(repoDir, "Detached Push", createSingleBlockStructureTemplate("minecraft:stone"), player, "first");
        RevCommit second = RvcRepository.commit(repoDir, "Detached Push", createSingleBlockStructureTemplate("minecraft:dirt"), player, "second");

        RvcProjectService.checkoutCommitToWorkingTree(repoDir, first.getName());

        Path remoteDir = Files.createTempDirectory("rvc-detached-push-remote-").resolve("remote.git");
        List<String> pushStatuses;
        try (Git ignored = Git.init().setBare(true).setDirectory(remoteDir.toFile()).call())
        {
            RvcProjectService.setRemote(repoDir, remoteDir.toUri().toString());
            pushStatuses = RvcProjectService.push(repoDir);
        }

        try (Repository remoteRepository = new FileRepositoryBuilder().setGitDir(remoteDir.toFile()).build())
        {
            ObjectId defaultBranchId = remoteRepository.resolve(Constants.R_HEADS + RvcProjectService.DEFAULT_BRANCH);
            IntegrationTestSupport.assertEquals(second.getId(), defaultBranchId, "detached push should publish the last active branch tip");
            IntegrationTestSupport.assertTrue(pushStatuses.stream().anyMatch(status -> status.contains(Constants.R_HEADS + RvcProjectService.DEFAULT_BRANCH)), "detached push status should report the pushed branch");
        }

        IntegrationTestSupport.assertEquals(first.getId(), RvcRepository.resolveHead(repoDir), "detached push must not move the checked-out HEAD");
    }

    private static void initWritesRvcFilesAndCreatesTheFirstGitCommit() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-init-");
        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        RvcRepository.init(
                repoDir,
                "Starter Build",
                createTinyStructureTemplate(),
                new RvcPlayerIdentity("BuilderOne", playerUuid)
        );

        IntegrationTestSupport.assertFileContains(repoDir.resolve("index.json"), "\"rvc_version\": 1");
        IntegrationTestSupport.assertFileContains(repoDir.resolve("index.json"), "\"name\": \"Starter Build\"");
        assertStructurePaletteContains(repoDir.resolve(RvcRepository.INDEX_STRUCTURE), "minecraft:stone");
        IntegrationTestSupport.assertFileContains(repoDir.resolve("README.md"), "# Starter Build");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();

            try (RevWalk revWalk = new RevWalk(repository))
            {
                ObjectId headId = repository.resolve(Constants.HEAD);
                IntegrationTestSupport.assertNotNull(headId, "HEAD should point at the initial commit");

                RevCommit commit = revWalk.parseCommit(headId);
                IntegrationTestSupport.assertEquals(0, commit.getParentCount(), "initial commit should not have parents");
                IntegrationTestSupport.assertEquals("BuilderOne", commit.getAuthorIdent().getName(), "author name");
                IntegrationTestSupport.assertEquals(playerUuid + "@minecraft", commit.getAuthorIdent().getEmailAddress(), "author email");
                IntegrationTestSupport.assertEquals("BuilderOne", commit.getCommitterIdent().getName(), "committer name");
                IntegrationTestSupport.assertEquals(playerUuid + "@minecraft", commit.getCommitterIdent().getEmailAddress(), "committer email");
                IntegrationTestSupport.assertEquals("init", commit.getShortMessage(), "commit message");

                String rawCommit = new String(repository.open(headId).getBytes(), StandardCharsets.UTF_8);
                IntegrationTestSupport.assertTrue(rawCommit.contains("\nrvc-version 1\n"), "commit should contain rvc-version metadata");
                IntegrationTestSupport.assertTrue(rawCommit.contains("\nx-created-by rvc\n"), "commit should contain x-created-by metadata");

                assertCommittedCoreFiles(repository, commit);
                IntegrationTestSupport.assertTrue(git.status().call().isClean(), "working tree should be clean after init commit");
            }
        }
    }

    private static void legacyLocalSelectionIsLocalOnlyAndIgnoredByGit() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-local-selection-");
        AreaSelection selection = createAreaSelectionFromJson("Stored Selection");

        RvcProjectService.writeLocalSelection(repoDir, selection);
        RvcRepository.commit(repoDir, "Local Selection", createTinyStructureTemplate(), new RvcPlayerIdentity("BuilderFive", UUID.fromString("123e4567-e89b-12d3-a456-426614174006")), "init");

        IntegrationTestSupport.assertFileContains(repoDir.resolve(RvcProjectService.LOCAL_JSON), "\"local_selection\"");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(".gitignore"), "/local.json");

        AreaSelection loaded = RvcProjectService.readLocalSelection(repoDir);
        IntegrationTestSupport.assertNotNull(loaded, "local selection should be readable");
        IntegrationTestSupport.assertEquals("Stored Selection", loaded.getName(), "local selection name");
        IntegrationTestSupport.assertEquals(1, loaded.getAllSubRegionBoxes().size(), "local selection boxes");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit commit = revWalk.parseCommit(repository.resolve(Constants.HEAD));
                Set<String> files = listTreeFiles(repository, commit.getTree());
                IntegrationTestSupport.assertTrue(!files.contains(RvcProjectService.LOCAL_JSON), "local.json should not be committed");
                IntegrationTestSupport.assertTrue(git.status().call().isClean(), "local.json should be ignored");
            }
        }
    }

    private static void subRegionsAreVersionedInIndexJsonAndMasterOriginIsLocalOnly() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-index-subregions-");
        AreaSelection selection = createAreaSelectionFromJson("Relative Selection");

        RvcProjectService.writeProjectMetadataWithSubRegions(repoDir, "Relative Project", selection);

        IntegrationTestSupport.assertFileContains(repoDir.resolve(RvcRepository.INDEX_JSON), "\"sub_regions\"");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(RvcRepository.INDEX_JSON), "\"name\": \"main\"");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(RvcRepository.INDEX_JSON), "\"pos1\": [");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(RvcProjectService.LOCAL_JSON), "\"master_origin\"");
        IntegrationTestSupport.assertTrue(!Files.readString(repoDir.resolve(RvcRepository.INDEX_JSON)).contains("master_origin"), "master origin must not be versioned in index.json");
        IntegrationTestSupport.assertTrue(!Files.readString(repoDir.resolve(RvcProjectService.LOCAL_JSON)).contains(RvcProjectService.LOCAL_SELECTION_KEY), "new sub-region metadata must not write legacy local_selection");

        AreaSelection restored = RvcProjectService.readProjectAreaSelection(repoDir);
        IntegrationTestSupport.assertNotNull(restored, "index/local should restore project area selection");
        IntegrationTestSupport.assertEquals("Relative Project", restored.getName(), "restored project selection name");
        IntegrationTestSupport.assertEquals(1, restored.getAllSubRegionBoxes().size(), "restored sub-region count");
    }

    private static void untrackedGapsBetweenSubRegionsAreNotTracked() throws Exception
    {
        AreaSelection selection = createTwoBoxAreaSelectionFromJson("Gap Selection");
        List<Box> boxes = List.copyOf(selection.getAllSubRegionBoxes());

        IntegrationTestSupport.assertTrue(RvcStructure.isTrackedPosition(new BlockPos(0, 0, 0), boxes), "first sub-region should be tracked");
        IntegrationTestSupport.assertTrue(!RvcStructure.isTrackedPosition(new BlockPos(1, 0, 0), boxes), "gap between independent sub-regions must not be tracked");
        IntegrationTestSupport.assertTrue(RvcStructure.isTrackedPosition(new BlockPos(2, 0, 0), boxes), "second sub-region should be tracked");
    }

    private static AreaSelection createAreaSelectionFromJson(String name)
    {
        JsonObject selection = new JsonObject();
        JsonArray boxes = new JsonArray();
        JsonObject box = new JsonObject();

        box.add("name", new JsonPrimitive("main"));
        box.add("pos1", JsonUtils.blockPosToJson(new BlockPos(1, 2, 3)));
        box.add("pos2", JsonUtils.blockPosToJson(new BlockPos(2, 3, 4)));
        boxes.add(box);

        selection.add("name", new JsonPrimitive(name));
        selection.add("current", new JsonPrimitive("main"));
        selection.add("boxes", boxes);

        return AreaSelection.fromJson(selection);
    }

    private static AreaSelection createTwoBoxAreaSelectionFromJson(String name)
    {
        JsonObject selection = new JsonObject();
        JsonArray boxes = new JsonArray();
        boxes.add(createBoxJson("left", new BlockPos(0, 0, 0), new BlockPos(0, 0, 0)));
        boxes.add(createBoxJson("right", new BlockPos(2, 0, 0), new BlockPos(2, 0, 0)));

        selection.add("name", new JsonPrimitive(name));
        selection.add("current", new JsonPrimitive("left"));
        selection.add("boxes", boxes);

        return AreaSelection.fromJson(selection);
    }

    private static JsonObject createBoxJson(String name, BlockPos pos1, BlockPos pos2)
    {
        JsonObject box = new JsonObject();
        box.add("name", new JsonPrimitive(name));
        box.add("pos1", JsonUtils.blockPosToJson(pos1));
        box.add("pos2", JsonUtils.blockPosToJson(pos2));
        return box;
    }

    private static void checkoutUpdatesWorkingTreeWhilePreservingVisibleHistory() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-checkout-");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderSix", UUID.fromString("123e4567-e89b-12d3-a456-426614174007"));
        RevCommit first = RvcRepository.commit(repoDir, "Checkout Project", createSingleBlockStructureTemplate("minecraft:stone"), player, "first");
        RevCommit second = RvcRepository.commit(repoDir, "Checkout Project", createSingleBlockStructureTemplate("minecraft:dirt"), player, "second");

        List<RvcProjectService.CommitInfo> branchHistoryBeforeCheckout = RvcProjectService.listCommits(repoDir);
        RvcProjectService.checkoutCommitToWorkingTree(repoDir, first.getName());

        assertStructurePaletteContains(repoDir.resolve(RvcRepository.INDEX_STRUCTURE), "minecraft:stone");
        IntegrationTestSupport.assertEquals(first.getId(), RvcRepository.resolveHead(repoDir), "checkout should move HEAD to the selected commit");

        List<RvcProjectService.CommitInfo> history = RvcProjectService.listCommits(repoDir);
        IntegrationTestSupport.assertEquals(branchHistoryBeforeCheckout.stream().map(RvcProjectService.CommitInfo::id).toList(), history.stream().map(RvcProjectService.CommitInfo::id).toList(), "checkout should not reorder or replace branch commit history");
        IntegrationTestSupport.assertTrue(history.stream().anyMatch(commit -> commit.id().equals(first.getName())), "history should still show checked-out commit");
        IntegrationTestSupport.assertTrue(history.stream().anyMatch(commit -> commit.id().equals(second.getName())), "history should still show branch commits after detached checkout");
    }

    private static void commitHistoryRemainsScopedToBranchAfterCheckout() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-checkout-history-");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderEight", UUID.fromString("123e4567-e89b-12d3-a456-426614174009"));
        RevCommit first = RvcRepository.commit(repoDir, "Branch Scoped History", createSingleBlockStructureTemplate("minecraft:stone"), player, "first");
        RevCommit second = RvcRepository.commit(repoDir, "Branch Scoped History", createSingleBlockStructureTemplate("minecraft:dirt"), player, "second");

        List<String> branchHistory = RvcProjectService.listCommits(repoDir).stream().map(RvcProjectService.CommitInfo::id).toList();

        RvcProjectService.checkoutCommitToWorkingTree(repoDir, first.getName());

        List<String> historyAfterDetachedCommit = RvcProjectService.listCommits(repoDir).stream().map(RvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(branchHistory, historyAfterDetachedCommit, "history should stay scoped to the branch selected before checkout");
        IntegrationTestSupport.assertTrue(historyAfterDetachedCommit.contains(second.getName()), "branch tip should remain visible");
    }

    private static void commitAfterCheckoutIsRejectedWhileHeadIsDetached() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-checkout-commit-");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderSeven", UUID.fromString("123e4567-e89b-12d3-a456-426614174008"));
        RevCommit first = RvcRepository.commit(repoDir, "Checkout Commit Project", createTinyStructureTemplate(), player, "first");
        RevCommit second = RvcRepository.commit(repoDir, "Checkout Commit Project", createTinyStructureTemplate(), player, "second");

        RvcProjectService.checkoutCommitToWorkingTree(repoDir, first.getName());

        try
        {
            RvcRepository.commit(repoDir, "Checkout Commit Project", createTinyStructureTemplate(), player, "after checkout");
            throw new AssertionError("commit should fail while HEAD is detached");
        }
        catch (IOException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("HEAD is detached"), "detached HEAD commit error should explain the reason");
        }

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit parsed = revWalk.parseCommit(repository.resolve(Constants.HEAD));
                IntegrationTestSupport.assertEquals(first.getId(), parsed.getId(), "detached HEAD should stay on the checked-out commit after rejected commit");
            }
        }

        List<String> historyAfterRejectedCommit = RvcProjectService.listCommits(repoDir).stream().map(RvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(List.of(second.getName(), first.getName()), historyAfterRejectedCommit, "branch history should stay on main after rejected detached commit");
    }

    private static void resetWorkingTreeToHeadDiscardsTrackedDirtyChanges() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-dirty-reset-");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderDirtyReset", UUID.fromString("123e4567-e89b-12d3-a456-426614174011"));
        RevCommit commit = RvcRepository.commit(repoDir, "Dirty Reset", createSingleBlockStructureTemplate("minecraft:stone"), player, "init");
        Path readme = repoDir.resolve(RvcRepository.README);
        Path untracked = repoDir.resolve("local-notes.txt");

        Files.writeString(readme, "local edits\n", StandardCharsets.UTF_8);
        Files.writeString(untracked, "untracked local notes\n", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(RvcProjectService.hasUncommittedChanges(repoDir), "tracked local edits should be reported before reset");

        RvcProjectService.resetWorkingTreeToHead(repoDir);

        IntegrationTestSupport.assertTrue(!RvcProjectService.hasUncommittedChanges(repoDir), "reset should clean tracked local edits");
        IntegrationTestSupport.assertEquals(commit.getId(), RvcRepository.resolveHead(repoDir), "reset should leave HEAD at the same commit");
        IntegrationTestSupport.assertTrue(Files.readString(readme, StandardCharsets.UTF_8).contains("# Dirty Reset"), "README should be restored from HEAD");
        IntegrationTestSupport.assertTrue(Files.exists(untracked), "reset to HEAD should leave untracked files alone");
    }

    private static void checkoutCanContinueAfterResettingDirtyWorkingTree() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-reset-checkout-");
        RvcPlayerIdentity player = new RvcPlayerIdentity("BuilderResetCheckout", UUID.fromString("123e4567-e89b-12d3-a456-426614174012"));
        RevCommit first = RvcRepository.commit(repoDir, "Reset Checkout", createSingleBlockStructureTemplate("minecraft:stone"), player, "first");
        RevCommit second = RvcRepository.commit(repoDir, "Reset Checkout", createSingleBlockStructureTemplate("minecraft:dirt"), player, "second");

        Files.writeString(repoDir.resolve(RvcRepository.README), "local edits\n", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(RvcProjectService.hasUncommittedChanges(repoDir), "tracked local edits should be reported before checkout reset");

        RvcProjectService.resetWorkingTreeToHead(repoDir);
        RvcProjectService.checkoutCommitToWorkingTree(repoDir, first.getName());

        IntegrationTestSupport.assertTrue(!RvcProjectService.hasUncommittedChanges(repoDir), "checkout after reset should leave the working tree clean");
        IntegrationTestSupport.assertEquals(first.getId(), RvcRepository.resolveHead(repoDir), "checkout after reset should move HEAD to the requested commit");
        IntegrationTestSupport.assertTrue(!second.getId().equals(RvcRepository.resolveHead(repoDir)), "checkout after reset should not remain on the previous commit");
        assertStructurePaletteContains(repoDir.resolve(RvcRepository.INDEX_STRUCTURE), "minecraft:stone");
    }

    private static Set<String> listTreeFiles(Repository repository, RevTree tree) throws Exception
    {
        try (TreeWalk treeWalk = new TreeWalk(repository))
        {
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);

            Set<String> paths = new java.util.HashSet<>();
            while (treeWalk.next())
            {
                paths.add(treeWalk.getPathString());
            }

            return paths.stream().collect(Collectors.toUnmodifiableSet());
        }
    }

    private static void initCommitsAnIndexStructureSavedAsVanillaNbt() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-structure-init-");
        StructureTemplate structure = createTinyStructureTemplate();

        RvcRepository.init(
                repoDir,
                "Vanilla Structure Backed",
                structure,
                new RvcPlayerIdentity("BuilderTwo", UUID.fromString("123e4567-e89b-12d3-a456-426614174003"))
        );

        Path structureFile = repoDir.resolve("index.nbt");
        IntegrationTestSupport.assertTrue(Files.size(structureFile) > 0, "index.nbt should be a real saved vanilla structure file");
        CompoundTag nbt = NbtIo.readCompressed(structureFile, NbtAccounter.unlimitedHeap());
        ListTag size = nbt.getListOrEmpty("size");
        IntegrationTestSupport.assertEquals(1, size.getIntOr(0, 0), "saved structure width");
        IntegrationTestSupport.assertEquals(1, size.getIntOr(1, 0), "saved structure height");
        IntegrationTestSupport.assertEquals(1, size.getIntOr(2, 0), "saved structure length");
        IntegrationTestSupport.assertTrue(nbt.contains("palette"), "saved structure should contain a palette");
        IntegrationTestSupport.assertTrue(nbt.contains("blocks"), "saved structure should contain block entries");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            ObjectId headId = repository.resolve(Constants.HEAD);
            IntegrationTestSupport.assertNotNull(headId, "vanilla-structure-backed repo should have HEAD");
        }
    }

    private static void assertCommittedCoreFiles(Repository repository, RevCommit commit) throws Exception
    {
        Set<String> files = listTreeFiles(repository, commit.getTree());
        IntegrationTestSupport.assertTrue(files.contains(RvcRepository.GITIGNORE), "commit should include .gitignore");
        IntegrationTestSupport.assertTrue(files.contains(RvcRepository.README), "commit should include README.md");
        IntegrationTestSupport.assertTrue(files.contains(RvcRepository.INDEX_JSON), "commit should include index.json");
        IntegrationTestSupport.assertTrue(files.contains(RvcRepository.INDEX_STRUCTURE), "commit should include index.nbt");
        IntegrationTestSupport.assertTrue(!files.contains(RvcProjectService.LOCAL_JSON), "commit must not include local.json");
    }

    private static void assertStructurePaletteContains(Path structureFile, String blockName) throws Exception
    {
        CompoundTag nbt = NbtIo.readCompressed(structureFile, NbtAccounter.unlimitedHeap());
        ListTag palette = nbt.getListOrEmpty("palette");

        for (int i = 0; i < palette.size(); i++)
        {
            if (palette.getCompoundOrEmpty(i).getStringOr("Name", "").equals(blockName))
            {
                return;
            }
        }

        throw new AssertionError("expected structure palette to contain " + blockName);
    }

    private static StructureTemplate createTinyStructureTemplate()
    {
        return createSingleBlockStructureTemplate("minecraft:stone");
    }

    private static StructureTemplate createSingleBlockStructureTemplate(String blockName)
    {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        Bootstrap.bootStrap();

        CompoundTag root = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(1));
        size.add(IntTag.valueOf(1));
        size.add(IntTag.valueOf(1));
        root.put("size", size);

        ListTag palette = new ListTag();
        CompoundTag blockState = new CompoundTag();
        blockState.putString("Name", blockName);
        palette.add(blockState);
        root.put("palette", palette);

        ListTag blocks = new ListTag();
        CompoundTag block = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        block.put("pos", pos);
        block.putInt("state", 0);
        blocks.add(block);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());

        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> lookup = BuiltInRegistries.BLOCK;
        template.load(lookup, root);
        return template;
    }
}
