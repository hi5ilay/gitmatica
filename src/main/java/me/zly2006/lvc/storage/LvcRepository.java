package me.zly2006.lvc.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.zly2006.lvc.LvcPlayerIdentity;
import me.zly2006.lvc.LvcProjectService;

public final class LvcRepository
{
    public static final int LVC_VERSION = 1;

    private LvcRepository()
    {
    }

    @Nullable
    public static ObjectId resolveHead(Path directory) throws IOException
    {
        try (Repository repository = org.eclipse.jgit.storage.file.FileRepositoryBuilder.create(directory.resolve(".git").toFile()))
        {
            return repository.resolve(Constants.HEAD);
        }
    }

    @Nullable
    static RevCommit commitFilePatterns(Path directory, LvcPlayerIdentity player, String message, List<String> filePatterns, boolean allowEmpty) throws IOException, GitAPIException
    {
        try (Git git = openOrCreateGit(directory))
        {
            ObjectId parent = git.getRepository().resolve(Constants.HEAD);
            return commitFilePatterns(git, player, message, filePatterns, parent == null ? List.of() : List.of(parent), allowEmpty);
        }
    }

    @Nullable
    public static RevCommit commitFilePatternsWithParents(Path directory, LvcPlayerIdentity player, String message,
                                                          List<String> filePatterns, List<ObjectId> parents,
                                                          boolean allowEmpty) throws IOException, GitAPIException
    {
        Objects.requireNonNull(directory, "directory");
        try (Git git = openOrCreateGit(directory))
        {
            return commitFilePatterns(git, player, message, filePatterns, parents, allowEmpty);
        }
    }

    @Nullable
    private static RevCommit commitFilePatterns(Git git, LvcPlayerIdentity player, String message,
                                                List<String> filePatterns, List<ObjectId> parents,
                                                boolean allowEmpty) throws IOException, GitAPIException
    {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(filePatterns, "filePatterns");
        Objects.requireNonNull(parents, "parents");

        Repository repository = git.getRepository();
        requireCommittableHead(repository);
        List<ObjectId> parentIds = new ArrayList<>(parents);
        org.eclipse.jgit.api.AddCommand addCommand = git.add();

        for (String filePattern : filePatterns)
        {
            addCommand.addFilepattern(filePattern);
        }

        addCommand.call();

        org.eclipse.jgit.api.AddCommand updateCommand = git.add().setUpdate(true);

        for (String filePattern : filePatterns)
        {
            updateCommand.addFilepattern(filePattern);
        }

        updateCommand.call();

        if (!allowEmpty)
        {
            Status status = git.status().call();

            if (status.isClean())
            {
                return null;
            }
        }

        ObjectId commitId = createCommit(repository, player, parentIds, message);

        try (RevWalk revWalk = new RevWalk(repository))
        {
            return revWalk.parseCommit(commitId);
        }
    }

    private static void requireCommittableHead(Repository repository) throws IOException
    {
        String fullBranch = repository.getFullBranch();

        if (fullBranch != null && fullBranch.startsWith(Constants.R_HEADS))
        {
            return;
        }

        if (repository.resolve(Constants.HEAD) == null)
        {
            return;
        }

        throw new IOException("LVC commit is disabled while HEAD is detached. Checkout " + LvcProjectService.DEFAULT_BRANCH + " before committing.");
    }

    private static Git openOrCreateGit(Path directory) throws GitAPIException, IOException
    {
        if (Files.isDirectory(directory.resolve(".git")))
        {
            return Git.open(directory.toFile());
        }

        return Git.init().setDirectory(directory.toFile()).setInitialBranch(LvcProjectService.DEFAULT_BRANCH).call();
    }

    private static ObjectId createCommit(Repository repository, LvcPlayerIdentity player, List<ObjectId> parents, String message) throws IOException
    {
        PersonIdent identity = player.toPersonIdent();

        try (ObjectInserter inserter = repository.newObjectInserter())
        {
            DirCache dirCache = repository.readDirCache();
            ObjectId treeId = dirCache.writeTree(inserter);
            ObjectId commitId = inserter.insert(Constants.OBJ_COMMIT, createCommitBytes(treeId, identity, parents, message));
            inserter.flush();
            updateHead(repository, commitId, identity, parents.isEmpty() ? null : parents.get(0), message);
            return commitId;
        }
    }

    private static byte[] createCommitBytes(ObjectId treeId, PersonIdent identity, List<ObjectId> parents, String message)
    {
        StringBuilder commit = new StringBuilder(256);
        commit.append("tree ").append(treeId.name()).append('\n');

        for (ObjectId parent : parents)
        {
            commit.append("parent ").append(parent.name()).append('\n');
        }

        commit.append("author ").append(identity.toExternalString()).append('\n');
        commit.append("committer ").append(identity.toExternalString()).append('\n');
        commit.append("lvc-version ").append(LVC_VERSION).append('\n');
        commit.append("x-created-by lvc\n");
        commit.append('\n');
        commit.append(message).append('\n');

        return commit.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void updateHead(Repository repository, ObjectId commitId, PersonIdent identity, @Nullable ObjectId parent, String message) throws IOException
    {
        String fullBranch = repository.getFullBranch();
        String targetRef = fullBranch != null && fullBranch.startsWith(Constants.R_HEADS) ? fullBranch : Constants.HEAD;
        RefUpdate refUpdate = repository.updateRef(targetRef);
        refUpdate.setNewObjectId(commitId);
        refUpdate.setExpectedOldObjectId(parent != null ? parent : ObjectId.zeroId());
        refUpdate.setRefLogIdent(identity);
        refUpdate.setRefLogMessage("commit: " + message, false);

        RefUpdate.Result result = refUpdate.update();

        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD)
        {
            throw new IOException("Failed to update HEAD for LVC commit: " + result);
        }
    }
}
