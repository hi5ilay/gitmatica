package me.zly2006.lvc.git;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nullable;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.storage.LvcSemanticRepository;

public final class LvcGitHistoryOps
{
    private static final DateTimeFormatter COMMIT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private LvcGitHistoryOps()
    {
    }

    public static List<LvcProjectService.CommitInfo> listCommits(Path repositoryDirectory) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        List<LvcProjectService.CommitInfo> commits = new ArrayList<>();

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String historyBranch = LvcGitBranchOps.historyBranchRef(repository);
            ObjectId historyStart = repository.resolve(historyBranch);

            if (historyStart == null)
            {
                return List.of();
            }

            ObjectId historyFloor = branchHistoryFloor(repository, historyBranch, historyStart);

            for (RevCommit commit : git.log().add(historyStart).call())
            {
                String fullMessage = commit.getFullMessage();
                String shortMessage = commit.getShortMessage();

                commits.add(new LvcProjectService.CommitInfo(
                        commit.getName(),
                        commit.getName().substring(0, Math.min(8, commit.getName().length())),
                        shortMessage,
                        commitDescription(fullMessage, shortMessage),
                        commit.getAuthorIdent().getName(),
                        formatCommitTime(commit.getAuthorIdent().getWhenAsInstant()),
                        countSubRegionsAtCommit(repository, commit),
                        ""
                ));

                if (historyFloor != null && commit.getId().equals(historyFloor))
                {
                    break;
                }
            }

            LvcDiagnostics.debug(
                    "LvcGitHistoryOps: listed commits repo='{}' branch='{}' count={} floor='{}'",
                    repositoryDirectory,
                    historyBranch,
                    commits.size(),
                    historyFloor == null ? "<none>" : historyFloor.name()
            );
        }

        return List.copyOf(commits);
    }

    public static String formatCommitTime(Instant instant)
    {
        return COMMIT_TIME_FORMAT.format(instant);
    }

    public static int countCommitsAcrossLocalBranches(Path repositoryDirectory) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            int branchCount = 0;

            for (Ref branch : git.branchList().call())
            {
                ObjectId tip = repository.resolve(branch.getName() + "^{commit}");

                if (tip != null)
                {
                    revWalk.markStart(revWalk.parseCommit(tip));
                    branchCount++;
                }
            }

            int commitCount = 0;

            while (revWalk.next() != null)
            {
                commitCount++;
            }

            LvcDiagnostics.debug("LvcGitHistoryOps: counted commits across local branches repo='{}' branches={} commits={}",
                    repositoryDirectory, branchCount, commitCount);
            return commitCount;
        }
    }

    public static Map<String, String> localBranchRefsSnapshot(Path repositoryDirectory) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Map<String, String> refs = new TreeMap<>();

            for (Ref branch : git.branchList().call())
            {
                ObjectId tip = git.getRepository().resolve(branch.getName() + "^{commit}");

                if (tip != null)
                {
                    refs.put(branch.getName(), tip.name());
                }
            }

            return Map.copyOf(refs);
        }
    }

    @Nullable
    private static ObjectId branchHistoryFloor(Repository repository, String historyBranch, ObjectId historyStart) throws IOException
    {
        if (!historyBranch.startsWith(Constants.R_HEADS))
        {
            return null;
        }

        String branchName = historyBranch.substring(Constants.R_HEADS.length());

        if (LvcProjectService.DEFAULT_BRANCH.equals(branchName))
        {
            return null;
        }

        ObjectId configuredStart = configuredBranchStart(repository, branchName, historyStart);

        if (configuredStart != null)
        {
            return configuredStart;
        }

        return mergeBaseWithDefaultBranch(repository, historyStart, historyBranch);
    }

    @Nullable
    private static ObjectId configuredBranchStart(Repository repository, String branchName, ObjectId historyStart) throws IOException
    {
        String configuredStart = LvcGitBranchOps.branchStartCommit(repository, branchName);

        if (configuredStart == null || configuredStart.isBlank())
        {
            return null;
        }

        ObjectId startCommit = repository.resolve(configuredStart + "^{commit}");

        if (startCommit == null)
        {
            LvcDiagnostics.debug("LvcGitHistoryOps: ignored missing branch start repo='{}' branch='{}' start='{}'", repository.getDirectory(), branchName, configuredStart);
            return null;
        }

        if (!isAncestor(repository, startCommit, historyStart))
        {
            LvcDiagnostics.debug("LvcGitHistoryOps: ignored non-ancestor branch start repo='{}' branch='{}' start='{}' tip='{}'", repository.getDirectory(), branchName, startCommit.name(), historyStart.name());
            return null;
        }

        return startCommit;
    }

    @Nullable
    private static ObjectId mergeBaseWithDefaultBranch(Repository repository, ObjectId historyStart, String historyBranch) throws IOException
    {
        String defaultBranch = Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH;
        ObjectId defaultStart = repository.resolve(defaultBranch + "^{commit}");

        if (defaultStart == null || defaultBranch.equals(historyBranch))
        {
            return null;
        }

        try (RevWalk revWalk = new RevWalk(repository))
        {
            RevCommit branchCommit = revWalk.parseCommit(historyStart);
            RevCommit defaultCommit = revWalk.parseCommit(defaultStart);
            revWalk.setRevFilter(RevFilter.MERGE_BASE);
            revWalk.markStart(branchCommit);
            revWalk.markStart(defaultCommit);
            RevCommit mergeBase = revWalk.next();

            return mergeBase == null ? null : mergeBase.getId();
        }
    }

    private static boolean isAncestor(Repository repository, ObjectId ancestor, ObjectId tip) throws IOException
    {
        try (RevWalk revWalk = new RevWalk(repository))
        {
            return revWalk.isMergedInto(revWalk.parseCommit(ancestor), revWalk.parseCommit(tip));
        }
    }

    private static String commitDescription(String fullMessage, String shortMessage)
    {
        if (fullMessage == null || fullMessage.isBlank())
        {
            return "";
        }

        if (shortMessage == null || shortMessage.isBlank() || !fullMessage.startsWith(shortMessage))
        {
            return fullMessage.trim();
        }

        return fullMessage.substring(Math.min(shortMessage.length(), fullMessage.length())).strip();
    }

    private static int countSubRegionsAtCommit(Repository repository, RevCommit commit)
    {
        try
        {
            String manifestJson = LvcGitTreeReader.readCommitTextFile(repository, commit, LvcSemanticRepository.MANIFEST);
            return manifestJson == null ? -1 : countSemanticSubRegions(manifestJson);
        }
        catch (IOException | RuntimeException e)
        {
            LvcDiagnostics.debug("LvcGitHistoryOps: failed to count sub-regions for commit '{}': {}", commit.getName(), e.getMessage());
        }

        return -1;
    }

    private static int countSemanticSubRegions(String manifestJson)
    {
        JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();

        if (!manifest.has("sites") || !manifest.get("sites").isJsonArray())
        {
            return -1;
        }

        int count = 0;

        for (JsonElement siteElement : manifest.getAsJsonArray("sites"))
        {
            if (siteElement.isJsonObject())
            {
                JsonObject site = siteElement.getAsJsonObject();

                if (site.has("regions") && site.get("regions").isJsonArray())
                {
                    count += site.getAsJsonArray("regions").size();
                }
            }
        }

        return count;
    }

}
