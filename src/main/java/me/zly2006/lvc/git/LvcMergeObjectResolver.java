package me.zly2006.lvc.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcChunkCodec;
import me.zly2006.lvc.storage.LvcChunkStore;

final class LvcMergeObjectResolver
{
    private LvcMergeObjectResolver()
    {
    }

    static LvcChunk readChunk(Repository repository, RevCommit commit, String objectId) throws IOException
    {
        byte[] bytes = LvcGitTreeReader.readCommitFile(repository, commit, LvcChunkStore.objectRepositoryPath(objectId));

        if (bytes == null)
        {
            throw new IOException("Commit " + commit.getName() + " is missing LVC object: " + objectId);
        }

        return LvcChunkCodec.decode(bytes);
    }

    static void ensureSiteObjects(Path repositoryDirectory, Repository repository, RevCommit commit,
                                  @Nullable LvcManifest.Site site) throws IOException
    {
        if (site == null)
        {
            return;
        }

        for (String objectId : new HashSet<>(site.fullHashes().values()))
        {
            ensureObject(repositoryDirectory, repository, commit, null, null, new LvcMergeChunkRef(objectId, objectId));
        }
    }

    static void ensureObject(Path repositoryDirectory, Repository repository, @Nullable RevCommit primaryCommit,
                             @Nullable RevCommit secondaryCommit, @Nullable RevCommit fallbackCommit,
                             @Nullable LvcMergeChunkRef ref) throws IOException
    {
        if (ref == null)
        {
            return;
        }

        Path objectPath = LvcChunkStore.objectPath(repositoryDirectory, ref.fullHash());

        if (Files.exists(objectPath))
        {
            return;
        }

        byte[] bytes = readObjectFromAny(repository, ref.fullHash(), primaryCommit, secondaryCommit, fallbackCommit);
        LvcChunkStore.writeObjectIfMissing(repositoryDirectory, ref.fullHash(), bytes);
    }

    private static byte[] readObjectFromAny(Repository repository, String objectId, RevCommit... commits) throws IOException
    {
        String path = LvcChunkStore.objectRepositoryPath(objectId);

        for (RevCommit commit : commits)
        {
            if (commit == null)
            {
                continue;
            }

            byte[] bytes = LvcGitTreeReader.readCommitFile(repository, commit, path);

            if (bytes != null)
            {
                return bytes;
            }
        }

        throw new IOException("Missing LVC object needed for merge: " + objectId);
    }
}
