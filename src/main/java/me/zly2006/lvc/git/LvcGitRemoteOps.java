package me.zly2006.lvc.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.PushResult;

public final class LvcGitRemoteOps
{
    private LvcGitRemoteOps()
    {
    }

    public static boolean hasRemote(Path repositoryDirectory) throws IOException
    {
        String remoteUrl = remoteOriginUrl(repositoryDirectory);
        return remoteUrl != null && !remoteUrl.isBlank();
    }

    public static String remoteOriginUrl(Path repositoryDirectory) throws IOException
    {
        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            return git.getRepository().getConfig().getString("remote", "origin", "url");
        }
    }

    public static void setRemote(Path repositoryDirectory, String remoteUrl) throws IOException
    {
        Objects.requireNonNull(remoteUrl, "remoteUrl");

        String normalizedRemoteUrl = remoteUrl.trim();

        if (normalizedRemoteUrl.isBlank())
        {
            throw new IllegalArgumentException("Remote Git URL must not be blank");
        }

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", normalizedRemoteUrl);
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            LvcGitBranchOps.configureRemoteTracking(git.getRepository(), config);
            config.save();
        }
    }

    public static List<String> push(Path repositoryDirectory) throws GitAPIException, IOException
    {
        List<String> statuses = new ArrayList<>();

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            String branch = LvcGitBranchOps.pushBranchRef(git.getRepository());

            for (PushResult result : git.push().setRemote("origin").add(branch)
                    .setTransportConfigCallback(LvcSshTransportFactory.transportConfigCallback()).call())
            {
                result.getRemoteUpdates().forEach(update ->
                        statuses.add(update.getRemoteName() + ": " + update.getStatus()));
            }
        }

        return List.copyOf(statuses);
    }

    public static String pull(Path repositoryDirectory) throws GitAPIException, IOException
    {
        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            LvcGitBranchOps.currentBranch(git.getRepository());
            PullResult result = git.pull().setRemote("origin")
                    .setTransportConfigCallback(LvcSshTransportFactory.transportConfigCallback()).call();
            return result.isSuccessful() ? "OK" : "FAILED";
        }
    }

    public static String describeRemoteFailure(Throwable throwable)
    {
        String message = throwable.getMessage();
        String fullMessage = collectThrowableMessages(throwable);
        String displayMessage = message != null ? message : throwable.getClass().getSimpleName();

        if (fullMessage.contains("no keys to try"))
        {
            return displayMessage + " " + LvcSshTransportFactory.sshIdentityDiagnostic();
        }

        if (fullMessage.contains("Server key did not validate"))
        {
            return displayMessage + " Trust GitHub's SSH host key once with: ssh -T git@github.com";
        }

        if (fullMessage.contains("Permission denied (publickey)"))
        {
            return displayMessage + " GitHub rejected the SSH key. Add your public key to GitHub and verify with: ssh -T git@github.com";
        }

        if (fullMessage.contains("CredentialsProvider has been registered"))
        {
            return displayMessage + " HTTPS private remotes need a GitHub token, which LVC does not support yet. Use an SSH remote such as git@github.com:user/repo.git.";
        }

        return displayMessage;
    }

    private static String collectThrowableMessages(Throwable throwable)
    {
        StringBuilder builder = new StringBuilder();

        for (Throwable current = throwable; current != null; current = current.getCause())
        {
            if (current.getMessage() != null)
            {
                builder.append(current.getMessage()).append('\n');
            }
        }

        return builder.toString();
    }
}
