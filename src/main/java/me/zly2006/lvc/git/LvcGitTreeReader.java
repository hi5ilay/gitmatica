package me.zly2006.lvc.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

public final class LvcGitTreeReader
{
    private LvcGitTreeReader()
    {
    }

    @Nullable
    public static String readCommitTextFile(Repository repository, RevCommit commit, String path) throws IOException
    {
        byte[] bytes = readCommitFile(repository, commit, path);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    @Nullable
    public static byte[] readCommitFile(Repository repository, RevCommit commit, String path) throws IOException
    {
        try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree()))
        {
            if (treeWalk == null)
            {
                return null;
            }

            return repository.open(treeWalk.getObjectId(0)).getBytes();
        }
    }

    public static RevCommit resolveCommit(Repository repository, RevWalk revWalk, String commitId) throws IOException
    {
        String trimmed = Objects.requireNonNull(commitId, "commitId").trim();

        if (trimmed.isBlank())
        {
            throw new IOException("LVC commit id must not be blank");
        }

        ObjectId objectId = repository.resolve(trimmed + "^{commit}");

        if (objectId == null)
        {
            objectId = repository.resolve(trimmed);
        }

        if (objectId == null)
        {
            throw new IOException("Unknown LVC commit: " + commitId);
        }

        return revWalk.parseCommit(objectId);
    }
}
