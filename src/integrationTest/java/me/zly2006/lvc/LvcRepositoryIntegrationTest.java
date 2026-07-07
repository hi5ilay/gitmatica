package me.zly2006.lvc;

import static me.zly2006.lvc.LvcIntegrationFixtures.inventoryBlockEntity;
import static me.zly2006.lvc.LvcIntegrationFixtures.placementAt;
import static me.zly2006.lvc.LvcIntegrationFixtures.player;
import static me.zly2006.lvc.LvcIntegrationFixtures.singleLineSite;
import static me.zly2006.lvc.LvcIntegrationFixtures.twoBlockSite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.git.LvcMergeConflictException;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcChunkCodec;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.storage.LvcRepository;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.task.LvcOperationJournal;

public class LvcRepositoryIntegrationTest
{
    public static void main(String[] args) throws Exception
    {
        IntegrationTestSupport.run("semantic repo commits use current branch HEAD and history lists newest first", LvcRepositoryIntegrationTest::semanticRepoCommitsUseCurrentBranchHeadAndHistoryListsNewestFirst);
        IntegrationTestSupport.run("project service lists semantic repositories and pushes to a remote", LvcRepositoryIntegrationTest::projectServiceListsSemanticRepositoriesAndPushesToRemote);
        IntegrationTestSupport.run("project repository names cannot escape project root", LvcRepositoryIntegrationTest::projectRepositoryNamesCannotEscapeProjectRoot);
        IntegrationTestSupport.run("project service deletes semantic repositories recursively", LvcRepositoryIntegrationTest::projectServiceDeletesSemanticRepositoriesRecursively);
        IntegrationTestSupport.run("remote URL config can be created and edited", LvcRepositoryIntegrationTest::remoteUrlConfigCanBeCreatedAndEdited);
        IntegrationTestSupport.run("push uses the last active branch while HEAD is detached", LvcRepositoryIntegrationTest::pushUsesLastActiveBranchWhileHeadIsDetached);
        IntegrationTestSupport.run("checkout updates the semantic working tree while preserving visible history", LvcRepositoryIntegrationTest::checkoutUpdatesSemanticWorkingTreeWhilePreservingVisibleHistory);
        IntegrationTestSupport.run("head commit detection compares resolved Git commits", LvcRepositoryIntegrationTest::headCommitDetectionComparesResolvedGitCommits);
        IntegrationTestSupport.run("head pointer name shows branch or detached commit", LvcRepositoryIntegrationTest::headPointerNameShowsBranchOrDetachedCommit);
        IntegrationTestSupport.run("detached head at branch tip can reattach without restore", LvcRepositoryIntegrationTest::detachedHeadAtBranchTipCanReattachWithoutRestore);
        IntegrationTestSupport.run("detached older head can reattach after checking out branch tip", LvcRepositoryIntegrationTest::detachedOlderHeadCanReattachAfterCheckingOutBranchTip);
        IntegrationTestSupport.run("commit history remains scoped to the branch after checkout", LvcRepositoryIntegrationTest::commitHistoryRemainsScopedToBranchAfterCheckout);
        IntegrationTestSupport.run("create branch shows branch start and later commits", LvcRepositoryIntegrationTest::createBranchShowsBranchStartAndLaterCommits);
        IntegrationTestSupport.run("create branch rejects duplicate invalid and empty repo inputs", LvcRepositoryIntegrationTest::createBranchRejectsDuplicateInvalidAndEmptyRepoInputs);
        IntegrationTestSupport.run("delete branch removes non-current branch and rejects protected inputs", LvcRepositoryIntegrationTest::deleteBranchRemovesNonCurrentBranchAndRejectsProtectedInputs);
        IntegrationTestSupport.run("rename branch updates refs metadata and rejects invalid inputs", LvcRepositoryIntegrationTest::renameBranchUpdatesRefsMetadataAndRejectsInvalidInputs);
        IntegrationTestSupport.run("merge branch fast-forwards current branch", LvcRepositoryIntegrationTest::mergeBranchFastForwardsCurrentBranch);
        IntegrationTestSupport.run("merge branch creates semantic two-parent merge commit", LvcRepositoryIntegrationTest::mergeBranchCreatesSemanticTwoParentMergeCommit);
        IntegrationTestSupport.run("merge branch cancels semantic conflicts without moving head", LvcRepositoryIntegrationTest::mergeBranchCancelsSemanticConflictsWithoutMovingHead);
        IntegrationTestSupport.run("merge branch conflicts when same inventory changes differently", LvcRepositoryIntegrationTest::mergeBranchConflictsWhenSameInventoryChangesDifferently);
        IntegrationTestSupport.run("merge branch conflict resolution accepts base incoming and yours", LvcRepositoryIntegrationTest::mergeBranchConflictResolutionAcceptsBaseIncomingAndYours);
        IntegrationTestSupport.run("same tip branch checkout preserves dirty tracked files", LvcRepositoryIntegrationTest::sameTipBranchCheckoutPreservesDirtyTrackedFiles);
        IntegrationTestSupport.run("commit after checkout is rejected while HEAD is detached", LvcRepositoryIntegrationTest::commitAfterCheckoutIsRejectedWhileHeadIsDetached);
        IntegrationTestSupport.run("reset working tree to HEAD discards tracked dirty changes", LvcRepositoryIntegrationTest::resetWorkingTreeToHeadDiscardsTrackedDirtyChanges);
        IntegrationTestSupport.run("undo latest commit keep changes preserves dirty working tree", LvcRepositoryIntegrationTest::undoLatestCommitKeepChangesPreservesDirtyWorkingTree);
        IntegrationTestSupport.run("save version after keep changes undo recommits dirty working tree", LvcRepositoryIntegrationTest::saveVersionAfterKeepChangesUndoRecommitsDirtyWorkingTree);
        IntegrationTestSupport.run("undo latest commit delete changes hard resets to parent", LvcRepositoryIntegrationTest::undoLatestCommitDeleteChangesHardResetsToParent);
        IntegrationTestSupport.run("checkout branch and reset to commit moves branch tip", LvcRepositoryIntegrationTest::checkoutBranchAndResetToCommitMovesBranchTip);
        IntegrationTestSupport.run("checkout can continue after resetting a dirty working tree", LvcRepositoryIntegrationTest::checkoutCanContinueAfterResettingDirtyWorkingTree);
        LvcFriendlyErrorsIntegrationTest.runAll();
        LvcOperationRecoveryIntegrationTest.runAll();
        LvcSemanticStorageIntegrationTest.runAll();
    }

    private static void semanticRepoCommitsUseCurrentBranchHeadAndHistoryListsNewestFirst() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-commit-parent-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult first = createInitialCommit(repoDir, "Parent Driven", reader, "ParentOne");
        LvcSemanticRepository.CommitResult second = commitSingleBlock(repoDir, first, reader, "minecraft:dirt", "ParentTwo", "update from world");
        LvcSemanticRepository.CommitResult third = commitSingleBlock(repoDir, second, reader, "minecraft:gold_block", "ParentThree", "ignore stale supplied parent");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH, repository.getFullBranch(), "initial commit should be on the default branch");

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit parsedHead = revWalk.parseCommit(repository.resolve(Constants.HEAD));
                IntegrationTestSupport.assertEquals(third.commit().getId(), parsedHead.getId(), "HEAD should point at the newest commit");
                IntegrationTestSupport.assertEquals(1, parsedHead.getParentCount(), "new commit should have one parent");
                IntegrationTestSupport.assertEquals(second.commit().getId(), parsedHead.getParent(0).getId(), "commit should parent the current branch HEAD");
            }
        }

        List<LvcProjectService.CommitInfo> history = LvcProjectService.listCommits(repoDir);
        IntegrationTestSupport.assertEquals(3, history.size(), "history size");
        IntegrationTestSupport.assertEquals(third.commit().getName(), history.get(0).id(), "newest commit first");
        IntegrationTestSupport.assertEquals("ignore stale supplied parent", history.get(0).message(), "newest commit message");
        IntegrationTestSupport.assertEquals(first.commit().getName(), history.get(2).id(), "oldest commit last");
    }

    private static void projectServiceListsSemanticRepositoriesAndPushesToRemote() throws Exception
    {
        Path runDir = Files.createTempDirectory("lvc-run-");
        Path reposDir = LvcProjectService.reposDirectory(runDir);
        Path validRepo = reposDir.resolve("Valid Project");
        Path invalidRepo = reposDir.resolve("Not Git");
        LvcSemanticRepository.CommitResult commit = createInitialCommit(validRepo, "Valid Project", new FakeWorldReader("minecraft:stone"), "ListValid");
        Files.createDirectories(invalidRepo);

        List<LvcProjectService.Project> projects = LvcProjectService.listProjects(runDir);
        IntegrationTestSupport.assertEquals(1, projects.size(), "only semantic git repositories should be listed");
        IntegrationTestSupport.assertEquals("Valid Project", projects.get(0).name(), "listed project name");
        IntegrationTestSupport.assertEquals(validRepo, projects.get(0).directory(), "listed project directory");

        Path remoteDir = Files.createTempDirectory("lvc-remote-").resolve("remote.git");
        List<String> pushStatuses;
        try (Git ignored = Git.init().setBare(true).setDirectory(remoteDir.toFile()).call())
        {
            LvcProjectService.setRemote(validRepo, remoteDir.toUri().toString());
            pushStatuses = LvcProjectService.push(validRepo);
        }

        try (Repository remoteRepository = new FileRepositoryBuilder().setGitDir(remoteDir.toFile()).build())
        {
            ObjectId defaultBranchId = remoteRepository.resolve(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH);
            IntegrationTestSupport.assertEquals(commit.commit().getId(), defaultBranchId, "remote should receive pushed main branch");
            IntegrationTestSupport.assertTrue(pushStatuses.stream().anyMatch(status -> status.contains(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH)), "push status should report the pushed branch");
        }
    }

    private static void projectServiceDeletesSemanticRepositoriesRecursively() throws Exception
    {
        Path runDir = Files.createTempDirectory("lvc-delete-run-");
        Path validRepo = LvcProjectService.repositoryDirectory(runDir, "Delete Me");
        Path invalidRepo = LvcProjectService.reposDirectory(runDir).resolve("Not LVC");

        createInitialCommit(validRepo, "Delete Me", new FakeWorldReader("minecraft:stone"), "DeleteMe");
        Files.createDirectories(validRepo.resolve("scratch/nested"));
        Files.writeString(validRepo.resolve("scratch/nested/untracked.txt"), "delete this", StandardCharsets.UTF_8);
        Files.createDirectories(invalidRepo.resolve("scratch"));
        Files.writeString(invalidRepo.resolve("scratch/keep.txt"), "keep this", StandardCharsets.UTF_8);

        LvcProjectService.deleteProjectRepository(runDir, validRepo);

        IntegrationTestSupport.assertTrue(!Files.exists(validRepo), "delete project should remove the whole repository directory");

        try
        {
            LvcProjectService.deleteProjectRepository(runDir, invalidRepo);
            throw new AssertionError("invalid LVC project directory should not be deleted");
        }
        catch (IOException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("Not a valid LVC project repository"), "invalid delete error should explain rejected directory");
        }

        IntegrationTestSupport.assertTrue(Files.exists(invalidRepo.resolve("scratch/keep.txt")), "invalid project delete must leave files intact");
    }

    private static void projectRepositoryNamesCannotEscapeProjectRoot() throws Exception
    {
        Path runDir = Files.createTempDirectory("lvc-path-root-");
        Path reposRoot = LvcProjectService.reposDirectory(runDir).toAbsolutePath().normalize();
        Path directProject = LvcProjectService.repositoryDirectory(runDir, "Direct Project").toAbsolutePath().normalize();

        IntegrationTestSupport.assertEquals(reposRoot, directProject.getParent(), "normal project should resolve directly under lvc-projects");
        assertRejectedProjectName(runDir, ".");
        assertRejectedProjectName(runDir, "..");
        assertRejectedProjectName(runDir, "../escape");
        assertRejectedProjectName(runDir, "nested/project");
        assertRejectedProjectName(runDir, "nested\\project");
    }

    private static void remoteUrlConfigCanBeCreatedAndEdited() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-remote-config-");
        createInitialCommit(repoDir, "Remote Config", new FakeWorldReader("minecraft:stone"), "RemoteConfig");

        String firstRemoteUrl = "git@github.com:example/first.git";
        String secondRemoteUrl = "https://github.com/example/second.git";

        IntegrationTestSupport.assertTrue(!LvcProjectService.hasRemote(repoDir), "new repo should not report a remote");

        LvcProjectService.setRemote(repoDir, "  " + firstRemoteUrl + "  ");

        IntegrationTestSupport.assertTrue(LvcProjectService.hasRemote(repoDir), "set remote should make origin available");
        IntegrationTestSupport.assertEquals(firstRemoteUrl, LvcProjectService.remoteOriginUrl(repoDir), "remote URL should be trimmed before saving");

        LvcProjectService.setRemote(repoDir, secondRemoteUrl);

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(secondRemoteUrl, repository.getConfig().getString("remote", "origin", "url"), "edited remote URL");
            IntegrationTestSupport.assertEquals("+refs/heads/*:refs/remotes/origin/*", repository.getConfig().getString("remote", "origin", "fetch"), "origin fetch refspec");
            IntegrationTestSupport.assertEquals("origin", repository.getConfig().getString("branch", LvcProjectService.DEFAULT_BRANCH, "remote"), "current branch remote");
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH, repository.getConfig().getString("branch", LvcProjectService.DEFAULT_BRANCH, "merge"), "current branch merge ref");
        }

        try
        {
            LvcProjectService.setRemote(repoDir, "   ");
            throw new AssertionError("blank remote URL should be rejected");
        }
        catch (IllegalArgumentException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("must not be blank"), "blank remote error should explain the rejected input");
        }

        IntegrationTestSupport.assertEquals(secondRemoteUrl, LvcProjectService.remoteOriginUrl(repoDir), "blank edit should not replace existing remote");
    }

    private static void pushUsesLastActiveBranchWhileHeadIsDetached() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Detached Push");
        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        Path remoteDir = Files.createTempDirectory("lvc-detached-push-remote-").resolve("remote.git");
        List<String> pushStatuses;
        try (Git ignored = Git.init().setBare(true).setDirectory(remoteDir.toFile()).call())
        {
            LvcProjectService.setRemote(repo.path(), remoteDir.toUri().toString());
            pushStatuses = LvcProjectService.push(repo.path());
        }

        try (Repository remoteRepository = new FileRepositoryBuilder().setGitDir(remoteDir.toFile()).build())
        {
            ObjectId defaultBranchId = remoteRepository.resolve(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH);
            IntegrationTestSupport.assertEquals(repo.second().commit().getId(), defaultBranchId, "detached push should publish the last active branch tip");
            IntegrationTestSupport.assertTrue(pushStatuses.stream().anyMatch(status -> status.contains(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH)), "detached push status should report the pushed branch");
        }

        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "detached push must not move the checked-out HEAD");
    }

    private static void checkoutUpdatesSemanticWorkingTreeWhilePreservingVisibleHistory() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Checkout Project");
        List<LvcProjectService.CommitInfo> branchHistoryBeforeCheckout = LvcProjectService.listCommits(repo.path());

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        IntegrationTestSupport.assertEquals(repo.first().manifest().site("main").fullHashes(), LvcSemanticRepository.readManifest(repo.path()).site("main").fullHashes(), "checkout should restore lvc.json from the selected commit");
        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "checkout should move HEAD to the selected commit");

        List<LvcProjectService.CommitInfo> history = LvcProjectService.listCommits(repo.path());
        IntegrationTestSupport.assertEquals(branchHistoryBeforeCheckout.stream().map(LvcProjectService.CommitInfo::id).toList(), history.stream().map(LvcProjectService.CommitInfo::id).toList(), "checkout should not reorder or replace branch commit history");
        IntegrationTestSupport.assertTrue(history.stream().anyMatch(commit -> commit.id().equals(repo.first().commit().getName())), "history should still show checked-out commit");
        IntegrationTestSupport.assertTrue(history.stream().anyMatch(commit -> commit.id().equals(repo.second().commit().getName())), "history should still show branch commits after detached checkout");
    }

    private static void commitHistoryRemainsScopedToBranchAfterCheckout() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Branch Scoped History");
        List<String> branchHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        List<String> historyAfterDetachedCommit = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(branchHistory, historyAfterDetachedCommit, "history should stay scoped to the branch selected before checkout");
        IntegrationTestSupport.assertTrue(historyAfterDetachedCommit.contains(repo.second().commit().getName()), "branch tip should remain visible");
    }

    private static void headCommitDetectionComparesResolvedGitCommits() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Head Match");

        IntegrationTestSupport.assertTrue(LvcProjectService.headMatchesCommit(repo.path(), repo.second().commit().getName()), "branch HEAD should match the newest commit");
        IntegrationTestSupport.assertTrue(!LvcProjectService.headMatchesCommit(repo.path(), repo.first().commit().getName()), "branch HEAD should not match an older commit");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        IntegrationTestSupport.assertTrue(LvcProjectService.headMatchesCommit(repo.path(), repo.first().commit().getName().substring(0, 8)), "detached HEAD should match the selected short commit id");
    }

    private static void createBranchShowsBranchStartAndLaterCommits() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Create Branch History");
        List<String> mainHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();

        String branchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/build");

        IntegrationTestSupport.assertEquals("feature/build", branchName, "created branch name should be normalized");
        IntegrationTestSupport.assertEquals("feature/build", LvcProjectService.headPointerName(repo.path()), "new branch should become the active HEAD branch");
        IntegrationTestSupport.assertTrue(!LvcProjectService.isDetachedHead(repo.path()), "created branch checkout should keep HEAD attached");
        IntegrationTestSupport.assertEquals(repo.second().commit().getId(), LvcRepository.resolveHead(repo.path()), "creating a branch must not move away from the current commit");

        List<String> branchHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(List.of(repo.second().commit().getName()), branchHistory, "new branch should show only its start commit before new work");

        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(), "minecraft:gold_block", "FeatureCommit", "feature work");

        branchHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(List.of(featureCommit.commit().getName(), repo.second().commit().getName()), branchHistory, "branch history should show branch commits back to the start commit");

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), LvcProjectService.DEFAULT_BRANCH);

        List<String> mainHistoryAfterBranchWork = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(mainHistory, mainHistoryAfterBranchWork, "main history should remain full and unchanged");
        LvcProjectService.ProjectSummary summary = LvcProjectService.projectSummary(new LvcProjectService.Project("Create Branch History", repo.path()));
        IntegrationTestSupport.assertEquals(3, summary.versionCount(), "project summary should count unique commits across all local branches");

        List<String> branches = LvcProjectService.listLocalBranches(repo.path());
        IntegrationTestSupport.assertTrue(branches.contains(LvcProjectService.DEFAULT_BRANCH), "main branch should still exist");
        IntegrationTestSupport.assertTrue(branches.contains("feature/build"), "new branch should be listed");
    }

    private static void createBranchRejectsDuplicateInvalidAndEmptyRepoInputs() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Create Branch Validation");

        LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/valid");
        assertCreateBranchRejected(repo.path(), "feature/valid", "Branch already exists");
        assertCreateBranchRejected(repo.path(), "FEATURE/VALID", "Branch already exists");
        assertCreateBranchRejected(repo.path(), " ", "must not be blank");
        assertCreateBranchRejected(repo.path(), "HEAD", "cannot be HEAD");
        assertCreateBranchRejected(repo.path(), "refs/heads/bad", "must not start with refs/");
        assertCreateBranchRejected(repo.path(), "bad branch", "must not contain whitespace");
        assertCreateBranchRejected(repo.path(), "bad..branch", "Invalid branch name");

        Path emptyRepo = Files.createTempDirectory("lvc-empty-branch-");

        try (Git ignored = Git.init().setDirectory(emptyRepo.toFile()).setInitialBranch(LvcProjectService.DEFAULT_BRANCH).call())
        {
            assertCreateBranchRejected(emptyRepo, "feature/empty", "Create the first version");
        }
    }

    private static void deleteBranchRemovesNonCurrentBranchAndRejectsProtectedInputs() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Delete Branch Validation");
        String branchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/delete");
        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(), "minecraft:gold_block", "DeleteFeature", "feature work");

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), LvcProjectService.DEFAULT_BRANCH);

        String deletedBranchName = LvcProjectService.deleteBranch(repo.path(), branchName);

        IntegrationTestSupport.assertEquals(branchName, deletedBranchName, "deleted branch name should be returned");
        IntegrationTestSupport.assertTrue(!LvcProjectService.listLocalBranches(repo.path()).contains(branchName), "deleted branch should disappear from local branch list");
        IntegrationTestSupport.assertEquals(LvcProjectService.DEFAULT_BRANCH, LvcProjectService.headPointerName(repo.path()), "deleting another branch must not move HEAD");

        List<String> mainHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(List.of(repo.second().commit().getName(), repo.first().commit().getName()), mainHistory, "main history should remain unchanged after deleting another branch");

        try (Git git = Git.open(repo.path().toFile()))
        {
            IntegrationTestSupport.assertEquals(null, git.getRepository().getConfig().getString("lvcBranchStart", branchName, "commit"), "branch start metadata should be removed");
            IntegrationTestSupport.assertEquals(null, git.getRepository().resolve(Constants.R_HEADS + branchName), "deleted branch ref should be gone");
            IntegrationTestSupport.assertTrue(git.getRepository().resolve(featureCommit.commit().getName()) != null, "force-deleted branch commit object may remain until Git GC");
        }

        assertDeleteBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "Cannot delete the checked-out branch");
        assertDeleteBranchRejected(repo.path(), "feature/missing", "Branch does not exist");
        assertDeleteBranchRejected(repo.path(), " ", "must not be blank");
    }

    private static void renameBranchUpdatesRefsMetadataAndRejectsInvalidInputs() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Rename Branch Validation");
        String oldBranchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/old");
        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(), "minecraft:gold_block", "RenameFeature", "feature work");
        List<String> oldBranchHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();

        String renamedCurrentBranch = LvcProjectService.renameBranch(repo.path(), oldBranchName, "feature/new");

        IntegrationTestSupport.assertEquals("feature/new", renamedCurrentBranch, "renamed current branch name should be returned");
        IntegrationTestSupport.assertEquals("feature/new", LvcProjectService.headPointerName(repo.path()), "renaming the checked-out branch should keep HEAD attached to the new branch");
        IntegrationTestSupport.assertTrue(!LvcProjectService.listLocalBranches(repo.path()).contains(oldBranchName), "old branch name should disappear after current branch rename");
        IntegrationTestSupport.assertTrue(LvcProjectService.listLocalBranches(repo.path()).contains("feature/new"), "new branch name should appear after current branch rename");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), LvcProjectService.localBranchTipCommitId(repo.path(), "feature/new"), "renamed branch tip should keep the old branch commit");
        IntegrationTestSupport.assertEquals(oldBranchHistory, LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList(), "renamed current branch should keep focused branch history");

        try (Git git = Git.open(repo.path().toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(null, repository.getConfig().getString("lvcBranchStart", oldBranchName, "commit"), "old branch-start metadata should be removed after rename");
            IntegrationTestSupport.assertEquals(repo.second().commit().getName(), repository.getConfig().getString("lvcBranchStart", "feature/new", "commit"), "new branch-start metadata should keep the original start commit");
            IntegrationTestSupport.assertEquals(null, repository.resolve(Constants.R_HEADS + oldBranchName), "old branch ref should be gone after rename");
            IntegrationTestSupport.assertTrue(repository.resolve(Constants.R_HEADS + "feature/new") != null, "new branch ref should exist after rename");
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + "feature/new", repository.getConfig().getString("lvc", null, "historyBranch"), "history branch metadata should follow current branch rename");
        }

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), LvcProjectService.DEFAULT_BRANCH);

        String renamedOtherBranch = LvcProjectService.renameBranch(repo.path(), "feature/new", "feature/renamed");

        IntegrationTestSupport.assertEquals("feature/renamed", renamedOtherBranch, "renamed non-current branch name should be returned");
        IntegrationTestSupport.assertEquals(LvcProjectService.DEFAULT_BRANCH, LvcProjectService.headPointerName(repo.path()), "renaming another branch must not move HEAD");
        IntegrationTestSupport.assertTrue(!LvcProjectService.listLocalBranches(repo.path()).contains("feature/new"), "old non-current branch name should disappear");
        IntegrationTestSupport.assertTrue(LvcProjectService.listLocalBranches(repo.path()).contains("feature/renamed"), "new non-current branch name should appear");

        try (Git git = Git.open(repo.path().toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(null, repository.getConfig().getString("lvcBranchStart", "feature/new", "commit"), "intermediate branch-start metadata should be removed after second rename");
            IntegrationTestSupport.assertEquals(repo.second().commit().getName(), repository.getConfig().getString("lvcBranchStart", "feature/renamed", "commit"), "renamed non-current branch should keep branch-start metadata");
        }

        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "feature/renamed", "Branch already exists");
        assertRenameBranchRejected(repo.path(), "feature/missing", "feature/unused", "Branch does not exist");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, " ", "must not be blank");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "HEAD", "cannot be HEAD");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "refs/heads/bad", "must not start with refs/");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "bad branch", "must not contain whitespace");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "bad..branch", "Invalid branch name");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, LvcProjectService.DEFAULT_BRANCH, "must be different");
    }

    private static void mergeBranchFastForwardsCurrentBranch() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Fast Forward Merge");
        String branchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/ff");
        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(), "minecraft:gold_block", "FastForwardFeature", "feature work");

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), LvcProjectService.DEFAULT_BRANCH);

        LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(repo.path(), branchName, player("FastForwardMerger"));

        IntegrationTestSupport.assertEquals(LvcProjectService.BranchMergeStatus.FAST_FORWARD, result.status(), "merge should fast-forward when current branch is an ancestor");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), result.commitId(), "fast-forward result commit");
        IntegrationTestSupport.assertEquals(LvcProjectService.DEFAULT_BRANCH, LvcProjectService.headPointerName(repo.path()), "fast-forward merge should keep current branch checked out");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getId(), LvcRepository.resolveHead(repo.path()), "fast-forward merge should move current branch to source tip");
        assertMergeRestoreJournal(repo.path(), result.commitId(), LvcProjectService.DEFAULT_BRANCH, branchName, repo.second().commit().getName());

        try (Git git = Git.open(repo.path().toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            RevCommit head = revWalk.parseCommit(git.getRepository().resolve(Constants.HEAD));
            IntegrationTestSupport.assertEquals(1, head.getParentCount(), "fast-forward should not create a merge commit");
        }
    }

    private static void mergeBranchCreatesSemanticTwoParentMergeCommit() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult initial = LvcSemanticRepository.initProject(repoDir, "Semantic Merge", twoBlockSite(), placementAt(0, 0, 0), reader, player("MergeInitial"));
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/semantic");

        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:gold_block");
        LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "MergeFeature", "feature changes second block");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:stone");
        LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "MergeMain", "main changes first block");
        String mainObject = mainCommit.manifest().site("main").fullHashes().get("0,0,0");

        LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(repoDir, branchName, player("SemanticMerger"));

        IntegrationTestSupport.assertEquals(LvcProjectService.BranchMergeStatus.MERGED, result.status(), "diverged branches should create a merge commit");
        IntegrationTestSupport.assertEquals(1, result.mergedChunks(), "different block edits in the same LVC chunk should merge semantically");
        IntegrationTestSupport.assertEquals(LvcProjectService.DEFAULT_BRANCH, LvcProjectService.headPointerName(repoDir), "merge should keep target branch checked out");
        assertMergeRestoreJournal(repoDir, result.commitId(), LvcProjectService.DEFAULT_BRANCH, branchName, mainCommit.commit().getName());
        String mergedObject = LvcSemanticRepository.readManifest(repoDir).site("main").fullHashes().get("0,0,0");
        String mainObjectPath = LvcChunkStore.objectRepositoryPath(mainObject);

        IntegrationTestSupport.assertTrue(!mainObject.equals(mergedObject), "semantic merge should create a new merged object");
        IntegrationTestSupport.assertTrue(!Files.exists(LvcChunkStore.objectPath(repoDir, mainObject)), "merge should prune obsolete target-branch object from current working tree");

        try (Git git = Git.open(repoDir.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            RevCommit head = revWalk.parseCommit(repository.resolve(Constants.HEAD));
            IntegrationTestSupport.assertEquals(2, head.getParentCount(), "semantic merge should create a two-parent commit");
            IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), head.getParent(0).getId(), "first merge parent should be current branch tip");
            IntegrationTestSupport.assertEquals(featureCommit.commit().getId(), head.getParent(1).getId(), "second merge parent should be source branch tip");
            IntegrationTestSupport.assertTrue(LvcProjectGitOps.readCommitFile(repository, mainCommit.commit(), mainObjectPath) != null, "target parent should still contain pruned object");
            IntegrationTestSupport.assertEquals(null, LvcProjectGitOps.readCommitFile(repository, head, mainObjectPath), "merge commit should not carry pruned target object");
        }

        LvcChunk mergedChunk = readOnlyChunk(repoDir);
        IntegrationTestSupport.assertEquals("minecraft:dirt", mergedChunk.blockStateAtTrackedOrdinal(0), "merged chunk should keep main's first block edit");
        IntegrationTestSupport.assertEquals("minecraft:gold_block", mergedChunk.blockStateAtTrackedOrdinal(1), "merged chunk should include feature's second block edit");
        IntegrationTestSupport.assertEquals(initial.localState(), LvcSemanticRepository.readLocalState(repoDir), "merge should not modify local placement state");
    }

    private static void mergeBranchCancelsSemanticConflictsWithoutMovingHead() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-conflict-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.initProject(repoDir, "Semantic Merge Conflict", twoBlockSite(), placementAt(0, 0, 0), reader, player("ConflictInitial"));
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/conflict");

        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:gold_block");
        LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "ConflictFeature", "feature changes first block");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "ConflictMain", "main changes first block");

        try
        {
            LvcProjectService.mergeBranch(repoDir, branchName, player("ConflictMerger"));
            throw new AssertionError("merge should be canceled when the same tracked block changed differently");
        }
        catch (LvcMergeConflictException expected)
        {
            IntegrationTestSupport.assertEquals(LvcMergeConflictException.Reason.BLOCK_PAYLOAD, expected.reason(), "same-block semantic conflict should be typed as block payload");
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("conflict"), "merge conflict error should explain conflict context");
        }

        IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), LvcRepository.resolveHead(repoDir), "conflict cancel must not move HEAD");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), LvcProjectService.localBranchTipCommitId(repoDir, branchName), "conflict cancel must not move source branch");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repoDir), "conflict cancel must leave working tree clean");
        IntegrationTestSupport.assertEquals("minecraft:dirt", readOnlyChunk(repoDir).blockStateAtTrackedOrdinal(0), "conflict cancel should leave target branch content checked out");
        IntegrationTestSupport.assertEquals(null, LvcOperationJournal.read(repoDir), "conflict-only merge must not leave a recovery journal");
    }

    private static void mergeBranchConflictsWhenSameInventoryChangesDifferently() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-inventory-conflict-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:furnace");
        LvcIntPosition pos = new LvcIntPosition(0, 0, 0);
        reader.setBlockEntity(pos, inventoryBlockEntity("minecraft:stone", "Merge Inventory"));
        LvcSemanticRepository.initProject(repoDir, "Semantic Merge Inventory Conflict",
                singleLineSite(), placementAt(0, 0, 0), reader, player("InventoryConflictInitial"));
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/inventory-conflict");

        reader.setBlockEntity(pos, inventoryBlockEntity("minecraft:gold_ingot", "Merge Inventory"));
        LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "InventoryConflictFeature", "feature changes inventory");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        reader.setBlockEntity(pos, inventoryBlockEntity("minecraft:diamond", "Merge Inventory"));
        LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "InventoryConflictMain", "main changes inventory");

        try
        {
            LvcProjectService.mergeBranch(repoDir, branchName, player("InventoryConflictMerger"));
            throw new AssertionError("merge should be canceled when the same inventory changed differently");
        }
        catch (LvcMergeConflictException expected)
        {
            IntegrationTestSupport.assertEquals(LvcMergeConflictException.Reason.BLOCK_PAYLOAD, expected.reason(), "same-inventory semantic conflict should be typed as block payload");
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("conflict"), "inventory merge conflict error should explain conflict context");
        }

        IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), LvcRepository.resolveHead(repoDir), "inventory conflict cancel must not move HEAD");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), LvcProjectService.localBranchTipCommitId(repoDir, branchName), "inventory conflict cancel must not move source branch");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repoDir), "inventory conflict cancel must leave working tree clean");
        IntegrationTestSupport.assertEquals("minecraft:furnace", readOnlyChunk(repoDir).blockStateAtTrackedOrdinal(0), "inventory conflict cancel should leave target branch content checked out");
        IntegrationTestSupport.assertEquals(null, LvcOperationJournal.read(repoDir), "inventory conflict-only merge must not leave a recovery journal");
    }

    private static void mergeBranchConflictResolutionAcceptsBaseIncomingAndYours() throws Exception
    {
        for (LvcProjectService.BranchMergeConflictResolution resolution : List.of(
                LvcProjectService.BranchMergeConflictResolution.BASE,
                LvcProjectService.BranchMergeConflictResolution.INCOMING,
                LvcProjectService.BranchMergeConflictResolution.YOURS))
        {
            Path repoDir = Files.createTempDirectory("lvc-semantic-merge-resolve-");
            FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
            LvcSemanticRepository.initProject(repoDir, "Semantic Merge Resolve", twoBlockSite(), placementAt(0, 0, 0), reader, player("ResolveInitial" + resolution));
            String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/resolve-" + resolution.name().toLowerCase(java.util.Locale.ROOT));

            reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:gold_block");
            reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:emerald_block");
            LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "ResolveFeature" + resolution, "feature changes conflict and second block");

            LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
            reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
            reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:stone");
            LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "ResolveMain" + resolution, "main changes conflicting block");

            LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(repoDir, branchName, player("ResolveMerger" + resolution), resolution);

            IntegrationTestSupport.assertEquals(LvcProjectService.BranchMergeStatus.MERGED, result.status(), "resolved conflict should create a merge commit");

            try (Git git = Git.open(repoDir.toFile());
                 RevWalk revWalk = new RevWalk(git.getRepository()))
            {
                RevCommit head = revWalk.parseCommit(git.getRepository().resolve(Constants.HEAD));
                IntegrationTestSupport.assertEquals(2, head.getParentCount(), "resolved conflict merge should have two parents");
                IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), head.getParent(0).getId(), "resolved merge first parent");
                IntegrationTestSupport.assertEquals(featureCommit.commit().getId(), head.getParent(1).getId(), "resolved merge second parent");
            }

            LvcChunk mergedChunk = readOnlyChunk(repoDir);
            IntegrationTestSupport.assertEquals(expectedResolvedConflictState(resolution), mergedChunk.blockStateAtTrackedOrdinal(0), "resolved conflict should choose requested side");
            IntegrationTestSupport.assertEquals("minecraft:emerald_block", mergedChunk.blockStateAtTrackedOrdinal(1), "resolved conflict should still keep non-conflicting incoming edit");
            IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repoDir), "resolved conflict merge should leave working tree clean");
        }
    }

    private static String expectedResolvedConflictState(LvcProjectService.BranchMergeConflictResolution resolution)
    {
        return switch (resolution)
        {
            case BASE -> "minecraft:stone";
            case INCOMING -> "minecraft:gold_block";
            case YOURS -> "minecraft:dirt";
        };
    }

    private static void sameTipBranchCheckoutPreservesDirtyTrackedFiles() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Same Tip Branch Switch");
        String branchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/same-tip");
        Path manifest = repo.path().resolve(LvcSemanticRepository.MANIFEST);

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), LvcProjectService.DEFAULT_BRANCH);
        Files.writeString(manifest, "dirty local edit\n", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "dirty tracked file should be reported before same-tip branch switch");
        IntegrationTestSupport.assertEquals(repo.second().commit().getName(), LvcProjectService.localBranchTipCommitId(repo.path(), branchName), "branch tip lookup should resolve the branch commit");

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), branchName);

        IntegrationTestSupport.assertEquals(branchName, LvcProjectService.headPointerName(repo.path()), "same-tip checkout should attach HEAD to target branch");
        IntegrationTestSupport.assertTrue(Files.readString(manifest, StandardCharsets.UTF_8).contains("dirty local edit"), "same-tip checkout should keep dirty tracked file content");
        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "dirty tracked file should stay dirty after same-tip branch switch");
        assertBranchTipRejected(repo.path(), "feature/missing", "Branch does not exist");
    }

    private static void headPointerNameShowsBranchOrDetachedCommit() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Head Pointer");

        IntegrationTestSupport.assertEquals(LvcProjectService.DEFAULT_BRANCH, LvcProjectService.headPointerName(repo.path()), "attached HEAD should display the branch name");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        IntegrationTestSupport.assertEquals(repo.first().commit().getName().substring(0, 8), LvcProjectService.headPointerName(repo.path()), "detached HEAD should display the short commit id");
    }

    private static void detachedHeadAtBranchTipCanReattachWithoutRestore() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Reattach Tip");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.second().commit().getName());

        IntegrationTestSupport.assertTrue(LvcProjectService.isDetachedHead(repo.path()), "checking out the current commit by id should detach HEAD");
        IntegrationTestSupport.assertTrue(LvcProjectService.reattachHeadToBranchIfAtTip(repo.path(), LvcProjectService.DEFAULT_BRANCH), "detached branch tip should reattach to the branch");
        IntegrationTestSupport.assertTrue(!LvcProjectService.isDetachedHead(repo.path()), "reattached HEAD should be on a local branch");
        IntegrationTestSupport.assertEquals(repo.second().commit().getId(), LvcRepository.resolveHead(repo.path()), "reattach must not move HEAD");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        IntegrationTestSupport.assertTrue(!LvcProjectService.reattachHeadToBranchIfAtTip(repo.path(), LvcProjectService.DEFAULT_BRANCH), "older detached commits should not silently reattach to the branch tip");
        IntegrationTestSupport.assertTrue(LvcProjectService.isDetachedHead(repo.path()), "older checkout should remain detached");
    }

    private static void detachedOlderHeadCanReattachAfterCheckingOutBranchTip() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Reattach After Tip Checkout");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());
        IntegrationTestSupport.assertTrue(LvcProjectService.isDetachedHead(repo.path()), "older checkout should detach HEAD");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.second().commit().getName());
        IntegrationTestSupport.assertTrue(LvcProjectService.reattachHeadToBranchIfAtTip(repo.path(), LvcProjectService.DEFAULT_BRANCH), "checking out the branch tip by commit id should be reattachable");
        IntegrationTestSupport.assertTrue(!LvcProjectService.isDetachedHead(repo.path()), "branch tip checkout should end attached after reattach");
        IntegrationTestSupport.assertEquals(repo.second().commit().getId(), LvcRepository.resolveHead(repo.path()), "reattach after tip checkout must keep HEAD at the selected commit");
    }

    private static void commitAfterCheckoutIsRejectedWhileHeadIsDetached() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Checkout Commit Project");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());
        repo.reader().setBlock(new LvcIntPosition(0, 0, 0), "minecraft:gold_block");

        try
        {
            LvcSemanticRepository.commitSite(repo.path(), repo.first().manifest(), repo.first().localState(), "main", repo.reader(), player("DetachedCommit"), "after checkout");
            throw new AssertionError("commit should fail while HEAD is detached");
        }
        catch (IOException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("HEAD is detached"), "detached HEAD commit error should explain the reason");
        }

        try (Git git = Git.open(repo.path().toFile()))
        {
            Repository repository = git.getRepository();

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit parsed = revWalk.parseCommit(repository.resolve(Constants.HEAD));
                IntegrationTestSupport.assertEquals(repo.first().commit().getId(), parsed.getId(), "detached HEAD should stay on the checked-out commit after rejected commit");
            }
        }

        List<String> historyAfterRejectedCommit = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(List.of(repo.second().commit().getName(), repo.first().commit().getName()), historyAfterRejectedCommit, "branch history should stay on main after rejected detached commit");
    }

    private static void resetWorkingTreeToHeadDiscardsTrackedDirtyChanges() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-dirty-reset-");
        LvcSemanticRepository.CommitResult commit = createInitialCommit(repoDir, "Dirty Reset", new FakeWorldReader("minecraft:stone"), "DirtyReset");
        Path manifest = repoDir.resolve(LvcSemanticRepository.MANIFEST);
        String originalManifest = Files.readString(manifest, StandardCharsets.UTF_8);
        Path untracked = repoDir.resolve("local-notes.txt");

        Files.writeString(manifest, "local edits\n", StandardCharsets.UTF_8);
        Files.writeString(untracked, "untracked local notes\n", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repoDir), "tracked local edits should be reported before reset");

        LvcProjectService.resetWorkingTreeToHead(repoDir);

        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repoDir), "reset should clean tracked local edits");
        IntegrationTestSupport.assertEquals(commit.commit().getId(), LvcRepository.resolveHead(repoDir), "reset should leave HEAD at the same commit");
        IntegrationTestSupport.assertEquals(originalManifest, Files.readString(manifest, StandardCharsets.UTF_8), "manifest should be restored from HEAD");
        IntegrationTestSupport.assertTrue(Files.exists(untracked), "reset to HEAD should leave untracked files alone");
    }

    private static void undoLatestCommitKeepChangesPreservesDirtyWorkingTree() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("UndoKeep");
        Path manifest = repo.path().resolve(LvcSemanticRepository.MANIFEST);
        String latestManifest = Files.readString(manifest, StandardCharsets.UTF_8);

        Files.writeString(manifest, latestManifest + "\n ", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "local manifest whitespace edit should be dirty before undo");

        LvcProjectService.LatestCommitUndoResult result = LvcProjectService.undoLatestCommitKeepChanges(repo.path());

        IntegrationTestSupport.assertEquals(repo.second().commit().getName(), result.commitId(), "keep-changes undo should report deleted commit");
        IntegrationTestSupport.assertEquals(repo.first().commit().getName(), result.parentCommitId(), "keep-changes undo should report parent commit");
        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "keep-changes undo should move HEAD to parent");
        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "keep-changes undo should leave working tree dirty");
        IntegrationTestSupport.assertEquals(repo.second().manifest().site("main").fullHashes(),
                LvcSemanticRepository.readManifest(repo.path()).site("main").fullHashes(),
                "keep-changes undo should keep the deleted commit content in the working tree");
    }

    private static void saveVersionAfterKeepChangesUndoRecommitsDirtyWorkingTree() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("UndoKeepRecommit");

        LvcProjectService.undoLatestCommitKeepChanges(repo.path());

        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "keep-changes undo should move HEAD to parent before recommit");
        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "keep-changes undo should leave semantic files dirty before recommit");

        LvcSemanticRepository.CommitResult recommit = LvcSemanticRepository.commitSite(
                repo.path(),
                LvcSemanticRepository.readManifest(repo.path()),
                LvcSemanticRepository.readLocalState(repo.path()),
                "main",
                repo.reader(),
                player("UndoKeepRecommit"),
                "recommit kept changes"
        );

        IntegrationTestSupport.assertNotNull(recommit.commit(), "saving kept changes should create a new commit");
        IntegrationTestSupport.assertEquals(repo.second().manifest().site("main").fullHashes(), recommit.manifest().site("main").fullHashes(),
                "recommit should preserve kept semantic content");
        IntegrationTestSupport.assertEquals(recommit.commit().getId(), LvcRepository.resolveHead(repo.path()), "recommit should become HEAD");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repo.path()), "recommit should clean the semantic working tree");

        try (Git git = Git.open(repo.path().toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            RevCommit parsed = revWalk.parseCommit(recommit.commit().getId());
            IntegrationTestSupport.assertEquals(1, parsed.getParentCount(), "recommit should have one parent");
            IntegrationTestSupport.assertEquals(repo.first().commit().getId(), parsed.getParent(0).getId(), "recommit should parent the kept-changes base commit");
        }
    }

    private static void undoLatestCommitDeleteChangesHardResetsToParent() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("UndoDelete");
        Path manifest = repo.path().resolve(LvcSemanticRepository.MANIFEST);
        String latestManifest = Files.readString(manifest, StandardCharsets.UTF_8);

        Files.writeString(manifest, latestManifest + "\n ", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "local manifest whitespace edit should be dirty before delete undo");

        LvcProjectService.LatestCommitUndoResult result = LvcProjectService.undoLatestCommitDeleteChanges(repo.path());

        IntegrationTestSupport.assertEquals(repo.second().commit().getName(), result.commitId(), "delete-changes undo should report deleted commit");
        IntegrationTestSupport.assertEquals(repo.first().commit().getName(), result.parentCommitId(), "delete-changes undo should report parent commit");
        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "delete-changes undo should move HEAD to parent");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repo.path()), "delete-changes undo should leave working tree clean");
        IntegrationTestSupport.assertEquals(repo.first().manifest().site("main").fullHashes(),
                LvcSemanticRepository.readManifest(repo.path()).site("main").fullHashes(),
                "delete-changes undo should restore the parent commit content");
    }

    private static void checkoutBranchAndResetToCommitMovesBranchTip() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("BranchReset");
        String branchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/reset");
        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(),
                "minecraft:gold_block", "BranchResetFeature", "feature work");

        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), LvcProjectService.localBranchTipCommitId(repo.path(), branchName), "branch should start at feature commit");

        LvcProjectService.checkoutBranchAndResetToCommit(repo.path(), branchName, repo.second().commit().getName());

        IntegrationTestSupport.assertEquals(branchName, LvcProjectService.headPointerName(repo.path()), "reset branch should be checked out");
        IntegrationTestSupport.assertEquals(repo.second().commit().getId(), LvcRepository.resolveHead(repo.path()), "reset branch should move HEAD to rollback commit");
        IntegrationTestSupport.assertEquals(repo.second().commit().getName(), LvcProjectService.localBranchTipCommitId(repo.path(), branchName), "branch tip should move to rollback commit");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repo.path()), "branch reset should leave working tree clean");
    }

    private static void checkoutCanContinueAfterResettingDirtyWorkingTree() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Reset Checkout");

        Files.writeString(repo.path().resolve(LvcSemanticRepository.MANIFEST), "local edits\n", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "tracked local edits should be reported before checkout reset");

        LvcProjectService.resetWorkingTreeToHead(repo.path());
        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repo.path()), "checkout after reset should leave the working tree clean");
        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "checkout after reset should move HEAD to the requested commit");
        IntegrationTestSupport.assertTrue(!repo.second().commit().getId().equals(LvcRepository.resolveHead(repo.path())), "checkout after reset should not remain on the previous commit");
        IntegrationTestSupport.assertEquals(repo.first().manifest().site("main").fullHashes(), LvcSemanticRepository.readManifest(repo.path()).site("main").fullHashes(), "checkout after reset should restore selected semantic manifest");
    }

    private static void assertRejectedProjectName(Path runDir, String name)
    {
        try
        {
            LvcProjectService.repositoryDirectory(runDir, name);
            throw new AssertionError("project name should be rejected: " + name);
        }
        catch (IllegalArgumentException expected)
        {
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("LVC repository name"), "rejection should explain LVC repository name constraint");
        }
    }

    private static void assertCreateBranchRejected(Path repoDir, String branchName, String expectedMessage)
    {
        try
        {
            LvcProjectService.createAndCheckoutBranch(repoDir, branchName);
            throw new AssertionError("branch name should be rejected: " + branchName);
        }
        catch (Exception e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains(expectedMessage), "branch rejection should explain: " + expectedMessage);
        }
    }

    private static void assertDeleteBranchRejected(Path repoDir, String branchName, String expectedMessage)
    {
        try
        {
            LvcProjectService.deleteBranch(repoDir, branchName);
            throw new AssertionError("branch delete should be rejected: " + branchName);
        }
        catch (Exception e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains(expectedMessage), "branch delete rejection should explain: " + expectedMessage);
        }
    }

    private static void assertRenameBranchRejected(Path repoDir, String oldName, String newName, String expectedMessage)
    {
        try
        {
            LvcProjectService.renameBranch(repoDir, oldName, newName);
            throw new AssertionError("branch rename should be rejected: " + oldName + " -> " + newName);
        }
        catch (Exception e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains(expectedMessage), "branch rename rejection should explain: " + expectedMessage);
        }
    }

    private static void assertBranchTipRejected(Path repoDir, String branchName, String expectedMessage)
    {
        try
        {
            LvcProjectService.localBranchTipCommitId(repoDir, branchName);
            throw new AssertionError("branch tip lookup should be rejected: " + branchName);
        }
        catch (Exception e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains(expectedMessage), "branch tip rejection should explain: " + expectedMessage);
        }
    }

    private static void assertMergeRestoreJournal(Path repoDir, String targetCommit, String targetBranch,
                                                  String sourceBranch, String previousHead) throws Exception
    {
        LvcOperationJournal.Entry entry = LvcOperationJournal.read(repoDir);
        IntegrationTestSupport.assertNotNull(entry, "successful merge should leave a recovery journal until world restore completes");
        IntegrationTestSupport.assertEquals(LvcOperationJournal.Operation.MERGE.name(), entry.operation(), "merge journal operation");
        IntegrationTestSupport.assertEquals("restore", entry.phase(), "merge journal phase");
        IntegrationTestSupport.assertEquals(targetCommit, entry.targetCommit(), "merge journal target commit");
        IntegrationTestSupport.assertEquals(targetBranch, entry.targetBranch(), "merge journal target branch");
        IntegrationTestSupport.assertEquals(sourceBranch, entry.sourceBranch(), "merge journal source branch");
        IntegrationTestSupport.assertEquals(previousHead, entry.previousHead(), "merge journal previous head");
        IntegrationTestSupport.assertTrue(entry.checksum() != null && !entry.checksum().isBlank(), "merge journal checksum");
    }

    private static SemanticRepo createTwoCommitRepo(String name) throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-git-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult first = createInitialCommit(repoDir, name, reader, name + "One");
        LvcSemanticRepository.CommitResult second = commitSingleBlock(repoDir, first, reader, "minecraft:dirt", name + "Two", "second");
        return new SemanticRepo(repoDir, reader, first, second);
    }

    private static LvcSemanticRepository.CommitResult createInitialCommit(Path repoDir, String projectName, FakeWorldReader reader, String playerName) throws Exception
    {
        return LvcSemanticRepository.initProject(repoDir, projectName, singleLineSite(), placementAt(0, 0, 0), reader, player(playerName));
    }

    private static LvcSemanticRepository.CommitResult commitSingleBlock(Path repoDir, LvcSemanticRepository.CommitResult previous,
                                                                        FakeWorldReader reader, String blockState,
                                                                        String playerName, String message) throws Exception
    {
        reader.setBlock(new LvcIntPosition(0, 0, 0), blockState);
        return LvcSemanticRepository.commitSite(repoDir, previous.manifest(), previous.localState(), "main", reader, player(playerName), message);
    }

    private static LvcSemanticRepository.CommitResult commitCurrent(Path repoDir, FakeWorldReader reader,
                                                                    String playerName, String message) throws Exception
    {
        return LvcSemanticRepository.commitSite(repoDir, LvcSemanticRepository.readManifest(repoDir),
                LvcSemanticRepository.readLocalState(repoDir), "main", reader, player(playerName), message);
    }

    private static LvcChunk readOnlyChunk(Path repoDir) throws Exception
    {
        LvcManifest manifest = LvcSemanticRepository.readManifest(repoDir);
        String objectId = manifest.site("main").fullHashes().values().iterator().next();
        return LvcChunkCodec.decode(LvcChunkStore.readObject(repoDir, objectId));
    }

    private record SemanticRepo(Path path, FakeWorldReader reader,
                                LvcSemanticRepository.CommitResult first,
                                LvcSemanticRepository.CommitResult second)
    {
    }
}
