package me.zly2006.lvc.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;

public final class LvcGitBranchOps
{
    private static final String GIT_CONFIG_SECTION = "lvc";
    private static final String GIT_CONFIG_HISTORY_BRANCH_KEY = "historyBranch";
    private static final String GIT_CONFIG_BRANCH_START_SECTION = "lvcBranchStart";
    private static final String GIT_CONFIG_BRANCH_START_KEY = "commit";

    private LvcGitBranchOps()
    {
    }

    public static void checkoutCommitToWorkingTree(Path repositoryDirectory, String commitId) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(commitId, "commitId");

        if (commitId.isBlank())
        {
            throw new IllegalArgumentException("Commit id must not be blank");
        }

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            rememberCurrentBranchForHistory(git.getRepository());
            git.checkout().setName(commitId.trim()).call();
        }
    }

    public static void checkoutBranchToWorkingTree(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");

        String normalizedBranchName = normalizeLocalBranchName(branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            LvcDiagnostics.debug("LvcGitBranchOps: checking out branch repo='{}' branch='{}'", repositoryDirectory, normalizedBranchName);
            git.checkout().setName(normalizedBranchName).call();
            rememberCurrentBranchForHistory(git.getRepository());
            LvcDiagnostics.debug("LvcGitBranchOps: checked out branch repo='{}' branch='{}'", repositoryDirectory, normalizedBranchName);
        }
    }

    public static String createAndCheckoutBranch(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        String normalizedBranchName = validateNewBranchName(repositoryDirectory, branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            ObjectId head = resolveHeadCommitId(repository);

            if (head == null)
            {
                throw new IOException("Create the first version before creating a branch");
            }

            LvcDiagnostics.debug(
                    "LvcGitBranchOps: creating branch repo='{}' branch='{}' start='{}'",
                    repositoryDirectory,
                    normalizedBranchName,
                    head.name()
            );
            git.checkout()
                    .setCreateBranch(true)
                    .setName(normalizedBranchName)
                    .setStartPoint(Constants.HEAD)
                    .call();
            rememberBranchStart(repository, normalizedBranchName, head);
            rememberHistoryBranch(repository, Constants.R_HEADS + normalizedBranchName);
            LvcDiagnostics.debug("LvcGitBranchOps: created and checked out branch repo='{}' branch='{}'", repositoryDirectory, normalizedBranchName);

            return normalizedBranchName;
        }
    }

    public static String deleteBranch(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");

        String normalizedBranchName = normalizeLocalBranchName(branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String fullBranch = Constants.R_HEADS + normalizedBranchName;
            Ref branchRef = repository.exactRef(fullBranch);

            if (branchRef == null)
            {
                throw new IllegalArgumentException("Branch does not exist: " + normalizedBranchName);
            }

            if (fullBranch.equals(repository.getFullBranch()))
            {
                throw new IllegalArgumentException("Cannot delete the checked-out branch: " + normalizedBranchName);
            }

            LvcDiagnostics.debug(
                    "LvcGitBranchOps: deleting branch repo='{}' branch='{}' tip='{}'",
                    repositoryDirectory,
                    normalizedBranchName,
                    branchRef.getObjectId() == null ? "<unknown>" : branchRef.getObjectId().name()
            );
            git.branchDelete()
                    .setBranchNames(normalizedBranchName)
                    .setForce(true)
                    .call();
            clearBranchStart(repository, normalizedBranchName);
            LvcDiagnostics.debug("LvcGitBranchOps: deleted branch repo='{}' branch='{}'", repositoryDirectory, normalizedBranchName);

            return normalizedBranchName;
        }
    }

    public static String renameBranch(Path repositoryDirectory, String oldName, String newName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(oldName, "oldName");
        Objects.requireNonNull(newName, "newName");

        String normalizedOldName = normalizeLocalBranchName(oldName);
        String normalizedNewCandidate = normalizeLocalBranchName(newName);

        if (normalizedOldName.equals(normalizedNewCandidate))
        {
            throw new IllegalArgumentException("New branch name must be different");
        }

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String normalizedNewName = validateNewBranchName(repository, newName);
            String fullOldBranch = Constants.R_HEADS + normalizedOldName;
            Ref oldBranchRef = repository.exactRef(fullOldBranch);

            if (oldBranchRef == null)
            {
                throw new IllegalArgumentException("Branch does not exist: " + normalizedOldName);
            }

            LvcDiagnostics.debug(
                    "LvcGitBranchOps: renaming branch repo='{}' old='{}' new='{}' tip='{}' current='{}'",
                    repositoryDirectory,
                    normalizedOldName,
                    normalizedNewName,
                    oldBranchRef.getObjectId() == null ? "<unknown>" : oldBranchRef.getObjectId().name(),
                    repository.getFullBranch()
            );
            git.branchRename()
                    .setOldName(fullOldBranch)
                    .setNewName(normalizedNewName)
                    .call();
            moveBranchMetadata(repository, normalizedOldName, normalizedNewName);
            LvcDiagnostics.debug("LvcGitBranchOps: renamed branch repo='{}' old='{}' new='{}'", repositoryDirectory, normalizedOldName, normalizedNewName);

            return normalizedNewName;
        }
    }

    public static String localBranchTipCommitId(Path repositoryDirectory, String branchName) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");

        String normalizedBranchName = normalizeLocalBranchName(branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String fullBranch = Constants.R_HEADS + normalizedBranchName;
            Ref branchRef = repository.exactRef(fullBranch);

            if (branchRef == null)
            {
                throw new IllegalArgumentException("Branch does not exist: " + normalizedBranchName);
            }

            ObjectId tip = repository.resolve(fullBranch + "^{commit}");

            if (tip == null)
            {
                throw new IOException("Branch has no commit: " + normalizedBranchName);
            }

            LvcDiagnostics.debug("LvcGitBranchOps: resolved branch tip repo='{}' branch='{}' tip='{}'",
                    repositoryDirectory, normalizedBranchName, tip.name());
            return tip.name();
        }
    }

    public static boolean headMatchesCommit(Path repositoryDirectory, String commitId) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            ObjectId head = resolveHeadCommitId(repository);

            return head != null && head.equals(LvcGitTreeReader.resolveCommit(repository, revWalk, commitId).getId());
        }
    }

    public static boolean reattachHeadToBranchIfAtTip(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");

        String shortBranchName = normalizeLocalBranchName(branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String fullBranch = Constants.R_HEADS + shortBranchName;
            ObjectId head = resolveHeadCommitId(repository);
            ObjectId branchTip = repository.resolve(fullBranch + "^{commit}");

            if (head == null || branchTip == null || !head.equals(branchTip))
            {
                return false;
            }

            git.checkout().setName(shortBranchName).call();
            rememberHistoryBranch(repository, fullBranch);
            return true;
        }
    }

    public static String headPointerName(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String fullBranch = repository.getFullBranch();

            if (fullBranch != null && fullBranch.startsWith(Constants.R_HEADS))
            {
                return fullBranch.substring(Constants.R_HEADS.length());
            }

            ObjectId head = resolveHeadCommitId(repository);

            if (head == null)
            {
                return "unknown";
            }

            String commitId = head.name();
            return commitId.substring(0, Math.min(8, commitId.length()));
        }
    }

    public static boolean isDetachedHead(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            String fullBranch = git.getRepository().getFullBranch();
            return fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS);
        }
    }

    public static String preferredCheckoutBranchName(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            String branch = historyBranchRef(git.getRepository());

            if (branch.startsWith(Constants.R_HEADS))
            {
                return branch.substring(Constants.R_HEADS.length());
            }

            throw new IOException("LVC repository has no local branch to checkout");
        }
    }

    public static List<String> listLocalBranches(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            List<String> branches = new ArrayList<>();

            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS))
            {
                addLocalBranchName(branches, ref.getName());
            }

            addLocalBranchName(branches, repository.getFullBranch());
            branches.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
            LvcDiagnostics.debug("LvcGitBranchOps: listed local branches repo='{}' count={} head='{}'",
                    repositoryDirectory, branches.size(), repository.getFullBranch());

            return List.copyOf(branches);
        }
    }

    public static String validateNewBranchName(Path repositoryDirectory, String branchName) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            return validateNewBranchName(git.getRepository(), branchName);
        }
    }

    public static boolean hasUncommittedChanges(Path repositoryDirectory) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Status status = git.status().call();
            return !status.getAdded().isEmpty() ||
                    !status.getChanged().isEmpty() ||
                    !status.getConflicting().isEmpty() ||
                    !status.getMissing().isEmpty() ||
                    !status.getModified().isEmpty() ||
                    !status.getRemoved().isEmpty();
        }
    }

    public static void resetWorkingTreeToHead(Path repositoryDirectory) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
        }
    }

    public static LvcProjectService.LatestCommitUndoTarget latestCommitUndoTarget(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            return latestCommitUndoTarget(git.getRepository(), revWalk);
        }
    }

    public static LvcProjectService.LatestCommitUndoResult undoLatestCommitKeepChanges(Path repositoryDirectory)
            throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            LvcProjectService.LatestCommitUndoTarget target = latestCommitUndoTarget(repository, revWalk);

            LvcDiagnostics.debug("LvcGitBranchOps: undo latest commit keep changes repo='{}' branch='{}' commit='{}' parent='{}'",
                    repositoryDirectory, target.branchName(), target.commitId(), target.parentCommitId());
            git.reset()
                    .setMode(ResetCommand.ResetType.MIXED)
                    .setRef(target.parentCommitId())
                    .call();
            rememberCurrentBranchForHistory(repository);
            LvcDiagnostics.debug("LvcGitBranchOps: undo latest commit keep changes complete repo='{}' branch='{}' parent='{}'",
                    repositoryDirectory, target.branchName(), target.parentCommitId());

            return new LvcProjectService.LatestCommitUndoResult(target.commitId(), target.parentCommitId(), target.branchName());
        }
    }

    public static LvcProjectService.LatestCommitUndoResult undoLatestCommitDeleteChanges(Path repositoryDirectory)
            throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            LvcProjectService.LatestCommitUndoTarget target = latestCommitUndoTarget(repository, revWalk);

            LvcDiagnostics.debug("LvcGitBranchOps: undo latest commit delete changes repo='{}' branch='{}' commit='{}' parent='{}'",
                    repositoryDirectory, target.branchName(), target.commitId(), target.parentCommitId());
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef(target.parentCommitId())
                    .call();
            rememberCurrentBranchForHistory(repository);
            LvcDiagnostics.debug("LvcGitBranchOps: undo latest commit delete changes complete repo='{}' branch='{}' parent='{}'",
                    repositoryDirectory, target.branchName(), target.parentCommitId());

            return new LvcProjectService.LatestCommitUndoResult(target.commitId(), target.parentCommitId(), target.branchName());
        }
    }

    public static void checkoutBranchAndResetToCommit(Path repositoryDirectory, String branchName, String commitId) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");
        Objects.requireNonNull(commitId, "commitId");

        if (commitId.isBlank())
        {
            throw new IllegalArgumentException("Commit id must not be blank");
        }

        String normalizedBranchName = normalizeLocalBranchName(branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            LvcDiagnostics.debug("LvcGitBranchOps: resetting branch repo='{}' branch='{}' commit='{}'",
                    repositoryDirectory, normalizedBranchName, commitId);
            git.checkout().setName(normalizedBranchName).call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commitId.trim()).call();
            rememberCurrentBranchForHistory(git.getRepository());
            LvcDiagnostics.debug("LvcGitBranchOps: reset branch repo='{}' branch='{}' commit='{}'",
                    repositoryDirectory, normalizedBranchName, commitId);
        }
    }

    static ObjectId resolveHeadCommitId(Repository repository) throws IOException
    {
        ObjectId head = repository.resolve(Constants.HEAD + "^{commit}");

        if (head == null)
        {
            head = repository.resolve(Constants.HEAD);
        }

        return head;
    }

    static String normalizeLocalBranchName(String branchName)
    {
        String normalizedBranchName = Objects.requireNonNull(branchName, "branchName").trim();

        if (normalizedBranchName.startsWith(Constants.R_HEADS))
        {
            normalizedBranchName = normalizedBranchName.substring(Constants.R_HEADS.length());
        }

        if (normalizedBranchName.isBlank())
        {
            throw new IllegalArgumentException("Branch name must not be blank");
        }

        return normalizedBranchName;
    }

    static String currentBranch(Repository repository) throws IOException
    {
        String fullBranch = repository.getFullBranch();

        if (fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS))
        {
            throw new IOException("LVC repository is not on a local branch");
        }

        return fullBranch;
    }

    static String historyBranchRef(Repository repository) throws IOException
    {
        String fullBranch = repository.getFullBranch();

        if (fullBranch != null && fullBranch.startsWith(Constants.R_HEADS))
        {
            rememberHistoryBranch(repository, fullBranch);
            return fullBranch;
        }

        String configuredBranch = repository.getConfig().getString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY);

        if (configuredBranch != null && !configuredBranch.isBlank() && repository.resolve(configuredBranch) != null)
        {
            return configuredBranch;
        }

        String defaultBranch = Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH;

        if (repository.resolve(defaultBranch) != null)
        {
            rememberHistoryBranch(repository, defaultBranch);
            return defaultBranch;
        }

        List<Ref> localBranches = repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);

        if (!localBranches.isEmpty())
        {
            String fallbackBranch = localBranches.get(0).getName();
            rememberHistoryBranch(repository, fallbackBranch);
            return fallbackBranch;
        }

        return Constants.HEAD;
    }

    static String pushBranchRef(Repository repository) throws IOException
    {
        String branch = historyBranchRef(repository);

        if (branch.startsWith(Constants.R_HEADS))
        {
            return branch;
        }

        throw new IOException("LVC repository has no local branch to push");
    }

    static void configureRemoteTracking(Repository repository, StoredConfig config) throws IOException
    {
        String branch = historyBranchRef(repository);

        if (!branch.startsWith(Constants.R_HEADS))
        {
            return;
        }

        String shortBranchName = branch.substring(Constants.R_HEADS.length());
        config.setString("branch", shortBranchName, "remote", "origin");
        config.setString("branch", shortBranchName, "merge", branch);
    }

    private static LvcProjectService.LatestCommitUndoTarget latestCommitUndoTarget(Repository repository, RevWalk revWalk)
            throws IOException
    {
        String fullBranch = currentBranch(repository);
        String branchName = fullBranch.substring(Constants.R_HEADS.length());
        ObjectId headId = resolveHeadCommitId(repository);

        if (headId == null)
        {
            throw new IOException("Create the first version before deleting a version");
        }

        RevCommit head = revWalk.parseCommit(headId);

        if (head.getParentCount() == 0)
        {
            throw new IOException("Cannot delete the only version");
        }

        RevCommit parent = revWalk.parseCommit(head.getParent(0).getId());
        return new LvcProjectService.LatestCommitUndoTarget(head.getName(), parent.getName(), branchName);
    }

    @Nullable
    static String branchStartCommit(Repository repository, String branchName)
    {
        return repository.getConfig().getString(GIT_CONFIG_BRANCH_START_SECTION, branchName, GIT_CONFIG_BRANCH_START_KEY);
    }

    private static String validateNewBranchName(Repository repository, String branchName) throws IOException
    {
        Objects.requireNonNull(branchName, "branchName");

        String requestedBranchName = branchName.trim();
        String normalizedBranchName = normalizeLocalBranchName(branchName);

        if (normalizedBranchName.equalsIgnoreCase(Constants.HEAD))
        {
            throw new IllegalArgumentException("Branch name cannot be HEAD");
        }

        if (requestedBranchName.startsWith("refs/"))
        {
            throw new IllegalArgumentException("Branch name must not start with refs/");
        }

        if (normalizedBranchName.chars().anyMatch(Character::isWhitespace))
        {
            throw new IllegalArgumentException("Branch name must not contain whitespace");
        }

        String fullBranch = Constants.R_HEADS + normalizedBranchName;

        if (!Repository.isValidRefName(fullBranch))
        {
            throw new IllegalArgumentException("Invalid branch name: " + normalizedBranchName);
        }

        if (repository.exactRef(fullBranch) != null || localBranchExistsIgnoringCase(repository, normalizedBranchName))
        {
            throw new IllegalArgumentException("Branch already exists: " + normalizedBranchName);
        }

        return normalizedBranchName;
    }

    private static void addLocalBranchName(List<String> branches, @Nullable String fullRef)
    {
        if (fullRef == null || !fullRef.startsWith(Constants.R_HEADS))
        {
            return;
        }

        String branchName = fullRef.substring(Constants.R_HEADS.length());

        if (!branchName.isBlank() && !branches.contains(branchName))
        {
            branches.add(branchName);
        }
    }

    private static boolean localBranchExistsIgnoringCase(Repository repository, String branchName) throws IOException
    {
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS))
        {
            String existingBranchName = ref.getName().substring(Constants.R_HEADS.length());

            if (existingBranchName.equalsIgnoreCase(branchName))
            {
                return true;
            }
        }

        return false;
    }

    private static void rememberCurrentBranchForHistory(Repository repository) throws IOException
    {
        String fullBranch = repository.getFullBranch();

        if (fullBranch != null && fullBranch.startsWith(Constants.R_HEADS))
        {
            rememberHistoryBranch(repository, fullBranch);
        }
    }

    private static void rememberBranchStart(Repository repository, String branchName, ObjectId startCommit) throws IOException
    {
        StoredConfig config = repository.getConfig();

        if (!startCommit.name().equals(config.getString(GIT_CONFIG_BRANCH_START_SECTION, branchName, GIT_CONFIG_BRANCH_START_KEY)))
        {
            config.setString(GIT_CONFIG_BRANCH_START_SECTION, branchName, GIT_CONFIG_BRANCH_START_KEY, startCommit.name());
            config.save();
        }
    }

    private static void clearBranchStart(Repository repository, String branchName) throws IOException
    {
        StoredConfig config = repository.getConfig();
        config.unsetSection(GIT_CONFIG_BRANCH_START_SECTION, branchName);
        config.save();
    }

    private static void moveBranchMetadata(Repository repository, String oldName, String newName) throws IOException
    {
        StoredConfig config = repository.getConfig();
        String startCommit = config.getString(GIT_CONFIG_BRANCH_START_SECTION, oldName, GIT_CONFIG_BRANCH_START_KEY);
        String oldFullBranch = Constants.R_HEADS + oldName;
        String newFullBranch = Constants.R_HEADS + newName;

        config.unsetSection(GIT_CONFIG_BRANCH_START_SECTION, newName);
        config.unsetSection(GIT_CONFIG_BRANCH_START_SECTION, oldName);

        if (startCommit != null && !startCommit.isBlank())
        {
            config.setString(GIT_CONFIG_BRANCH_START_SECTION, newName, GIT_CONFIG_BRANCH_START_KEY, startCommit);
        }

        if (oldFullBranch.equals(config.getString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY)))
        {
            config.setString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY, newFullBranch);
        }

        config.save();
    }

    private static void rememberHistoryBranch(Repository repository, String fullBranch) throws IOException
    {
        StoredConfig config = repository.getConfig();

        if (!fullBranch.equals(config.getString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY)))
        {
            config.setString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY, fullBranch);
            config.save();
        }
    }
}
