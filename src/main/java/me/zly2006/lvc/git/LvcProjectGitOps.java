package me.zly2006.lvc.git;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.zly2006.lvc.LvcPlayerIdentity;
import me.zly2006.lvc.LvcProjectService;

public final class LvcProjectGitOps
{
    private LvcProjectGitOps()
    {
    }

    public static List<LvcProjectService.CommitInfo> listCommits(Path repositoryDirectory) throws IOException, GitAPIException
    {
        return LvcGitHistoryOps.listCommits(repositoryDirectory);
    }

    public static String formatCommitTime(Instant instant)
    {
        return LvcGitHistoryOps.formatCommitTime(instant);
    }

    public static int countCommitsAcrossLocalBranches(Path repositoryDirectory) throws IOException, GitAPIException
    {
        return LvcGitHistoryOps.countCommitsAcrossLocalBranches(repositoryDirectory);
    }

    public static Map<String, String> localBranchRefsSnapshot(Path repositoryDirectory) throws IOException, GitAPIException
    {
        return LvcGitHistoryOps.localBranchRefsSnapshot(repositoryDirectory);
    }

    public static void checkoutCommitToWorkingTree(Path repositoryDirectory, String commitId) throws GitAPIException, IOException
    {
        LvcGitBranchOps.checkoutCommitToWorkingTree(repositoryDirectory, commitId);
    }

    public static void checkoutBranchToWorkingTree(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        LvcGitBranchOps.checkoutBranchToWorkingTree(repositoryDirectory, branchName);
    }

    public static String createAndCheckoutBranch(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        return LvcGitBranchOps.createAndCheckoutBranch(repositoryDirectory, branchName);
    }

    public static String deleteBranch(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        return LvcGitBranchOps.deleteBranch(repositoryDirectory, branchName);
    }

    public static String renameBranch(Path repositoryDirectory, String oldName, String newName) throws GitAPIException, IOException
    {
        return LvcGitBranchOps.renameBranch(repositoryDirectory, oldName, newName);
    }

    public static LvcProjectService.BranchMergeResult mergeBranch(Path repositoryDirectory, String sourceBranch,
                                                                  LvcPlayerIdentity player,
                                                                  @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution) throws IOException, GitAPIException
    {
        return LvcBranchMergeOps.mergeBranch(repositoryDirectory, sourceBranch, player, conflictResolution);
    }

    public static String localBranchTipCommitId(Path repositoryDirectory, String branchName) throws IOException
    {
        return LvcGitBranchOps.localBranchTipCommitId(repositoryDirectory, branchName);
    }

    public static boolean headMatchesCommit(Path repositoryDirectory, String commitId) throws IOException
    {
        return LvcGitBranchOps.headMatchesCommit(repositoryDirectory, commitId);
    }

    public static boolean reattachHeadToBranchIfAtTip(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        return LvcGitBranchOps.reattachHeadToBranchIfAtTip(repositoryDirectory, branchName);
    }

    public static String headPointerName(Path repositoryDirectory) throws IOException
    {
        return LvcGitBranchOps.headPointerName(repositoryDirectory);
    }

    public static boolean isDetachedHead(Path repositoryDirectory) throws IOException
    {
        return LvcGitBranchOps.isDetachedHead(repositoryDirectory);
    }

    public static String preferredCheckoutBranchName(Path repositoryDirectory) throws IOException
    {
        return LvcGitBranchOps.preferredCheckoutBranchName(repositoryDirectory);
    }

    public static List<String> listLocalBranches(Path repositoryDirectory) throws IOException
    {
        return LvcGitBranchOps.listLocalBranches(repositoryDirectory);
    }

    public static String validateNewBranchName(Path repositoryDirectory, String branchName) throws IOException
    {
        return LvcGitBranchOps.validateNewBranchName(repositoryDirectory, branchName);
    }

    public static boolean hasUncommittedChanges(Path repositoryDirectory) throws GitAPIException, IOException
    {
        return LvcGitBranchOps.hasUncommittedChanges(repositoryDirectory);
    }

    public static void resetWorkingTreeToHead(Path repositoryDirectory) throws GitAPIException, IOException
    {
        LvcGitBranchOps.resetWorkingTreeToHead(repositoryDirectory);
    }

    public static LvcProjectService.LatestCommitUndoTarget latestCommitUndoTarget(Path repositoryDirectory) throws IOException
    {
        return LvcGitBranchOps.latestCommitUndoTarget(repositoryDirectory);
    }

    public static LvcProjectService.LatestCommitUndoResult undoLatestCommitKeepChanges(Path repositoryDirectory)
            throws GitAPIException, IOException
    {
        return LvcGitBranchOps.undoLatestCommitKeepChanges(repositoryDirectory);
    }

    public static LvcProjectService.LatestCommitUndoResult undoLatestCommitDeleteChanges(Path repositoryDirectory)
            throws GitAPIException, IOException
    {
        return LvcGitBranchOps.undoLatestCommitDeleteChanges(repositoryDirectory);
    }

    public static void checkoutBranchAndResetToCommit(Path repositoryDirectory, String branchName, String commitId) throws GitAPIException, IOException
    {
        LvcGitBranchOps.checkoutBranchAndResetToCommit(repositoryDirectory, branchName, commitId);
    }

    public static boolean hasRemote(Path repositoryDirectory) throws IOException
    {
        return LvcGitRemoteOps.hasRemote(repositoryDirectory);
    }

    @Nullable
    public static String remoteOriginUrl(Path repositoryDirectory) throws IOException
    {
        return LvcGitRemoteOps.remoteOriginUrl(repositoryDirectory);
    }

    public static void setRemote(Path repositoryDirectory, String remoteUrl) throws IOException
    {
        LvcGitRemoteOps.setRemote(repositoryDirectory, remoteUrl);
    }

    public static List<String> push(Path repositoryDirectory) throws GitAPIException, IOException
    {
        return LvcGitRemoteOps.push(repositoryDirectory);
    }

    public static String pull(Path repositoryDirectory) throws GitAPIException, IOException
    {
        return LvcGitRemoteOps.pull(repositoryDirectory);
    }

    public static String describeRemoteFailure(Throwable throwable)
    {
        return LvcGitRemoteOps.describeRemoteFailure(throwable);
    }

    @Nullable
    public static String readCommitTextFile(Repository repository, RevCommit commit, String path) throws IOException
    {
        return LvcGitTreeReader.readCommitTextFile(repository, commit, path);
    }

    @Nullable
    public static byte[] readCommitFile(Repository repository, RevCommit commit, String path) throws IOException
    {
        return LvcGitTreeReader.readCommitFile(repository, commit, path);
    }

    public static RevCommit resolveCommit(Repository repository, RevWalk revWalk, String commitId) throws IOException
    {
        return LvcGitTreeReader.resolveCommit(repository, revWalk, commitId);
    }
}
