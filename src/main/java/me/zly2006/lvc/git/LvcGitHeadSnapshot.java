package me.zly2006.lvc.git;

import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;

public record LvcGitHeadSnapshot(@Nullable String fullBranch, @Nullable String commitId)
{
    public static LvcGitHeadSnapshot capture(Path repositoryDirectory) throws IOException
    {
        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            String fullBranch = git.getRepository().getFullBranch();
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            return new LvcGitHeadSnapshot(fullBranch, head == null ? null : head.getName());
        }
    }

    public void restore(Path repositoryDirectory) throws GitAPIException, IOException
    {
        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            if (this.fullBranch != null && this.fullBranch.startsWith(Constants.R_HEADS))
            {
                git.checkout().setName(this.fullBranch.substring(Constants.R_HEADS.length())).call();
                return;
            }

            if (this.commitId != null)
            {
                git.checkout().setName(this.commitId).call();
            }
        }
    }
}
