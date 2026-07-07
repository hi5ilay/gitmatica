package me.zly2006.rvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

public final class RvcSemanticRepository
{
    public static final String MANIFEST = "rvc.json";
    public static final String README = "README.md";
    public static final String GITIGNORE = ".gitignore";
    public static final String LOCAL_JSON = "local.json";

    private RvcSemanticRepository()
    {
    }

    public static CommitResult initProject(Path repositoryDirectory, String projectName, RvcManifest.Site site,
                                           RvcLocalState.SitePlacement placement, RvcWorldReader worldReader,
                                           RvcPlayerIdentity player) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(worldReader, "worldReader");
        Objects.requireNonNull(player, "player");

        RvcManifest initialManifest = RvcManifest.create(projectName, List.of(site));
        RvcLocalState localState = RvcLocalState.create(initialManifest.projectId(), site.id(), Map.of(site.id(), placement));
        RvcCaptureEngine.Result capture = RvcCaptureEngine.captureSite(repositoryDirectory, site, placement, worldReader);
        RvcManifest capturedManifest = initialManifest.withSiteChunks(site.id(), capture.chunkObjects());

        writeProjectFiles(repositoryDirectory, capturedManifest, localState);
        RevCommit commit = commitSemanticFiles(repositoryDirectory, player, "init", true);

        if (commit == null)
        {
            throw new IOException("Semantic RVC init unexpectedly had no changes");
        }

        return new CommitResult(capturedManifest, localState, commit);
    }

    public static EmptyProjectResult initEmptyProject(Path repositoryDirectory, String projectName, RvcManifest.Site site,
                                                      RvcLocalState.SitePlacement placement) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(placement, "placement");

        RvcManifest manifest = RvcManifest.create(projectName, List.of(site));
        RvcLocalState localState = RvcLocalState.create(manifest.projectId(), site.id(), Map.of(site.id(), placement));

        writeProjectFiles(repositoryDirectory, manifest, localState);
        initGitRepository(repositoryDirectory);

        return new EmptyProjectResult(manifest, localState);
    }

    public static CommitResult commitSite(Path repositoryDirectory, RvcManifest manifest, RvcLocalState localState,
                                          String siteId, RvcWorldReader worldReader, RvcPlayerIdentity player,
                                          String message) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(localState, "localState");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(worldReader, "worldReader");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");

        if (message.isBlank())
        {
            throw new IllegalArgumentException("RVC commit message must not be blank");
        }

        RvcManifest.Site site = manifest.site(siteId);
        RvcLocalState.SitePlacement placement = localState.sites().get(siteId);

        if (placement == null)
        {
            throw new IOException("Missing local placement for RVC site: " + siteId);
        }

        RvcCaptureEngine.Result capture = RvcCaptureEngine.captureSite(repositoryDirectory, site, placement, worldReader);
        RvcManifest capturedManifest = manifest.withSiteChunks(siteId, capture.chunkObjects());

        writeProjectFiles(repositoryDirectory, capturedManifest, localState);
        RevCommit commit = commitSemanticFiles(repositoryDirectory, player, message.trim(), false);
        return new CommitResult(capturedManifest, localState, commit);
    }

    public static CommitResult updateSiteAreas(Path repositoryDirectory, RvcManifest manifest, RvcLocalState localState,
                                               String siteId, List<RvcManifest.Region> regions, RvcWorldReader worldReader,
                                               RvcPlayerIdentity player, String message) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(localState, "localState");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(worldReader, "worldReader");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");

        if (message.isBlank())
        {
            throw new IllegalArgumentException("RVC commit message must not be blank");
        }

        RvcManifest.Site site = manifest.site(siteId);
        RvcLocalState.SitePlacement placement = localState.sites().get(siteId);

        if (placement == null)
        {
            throw new IOException("Missing local placement for RVC site: " + siteId);
        }

        RvcManifest.Site updatedSite = site.withRegions(regions);
        RvcCaptureEngine.Result capture = RvcCaptureEngine.captureSite(repositoryDirectory, updatedSite, placement, worldReader);
        RvcManifest capturedManifest = manifest.withSite(siteId, updatedSite.withChunks(capture.chunkObjects()));

        writeProjectFiles(repositoryDirectory, capturedManifest, localState);
        RevCommit commit = commitSemanticFiles(repositoryDirectory, player, message.trim(), false);
        return new CommitResult(capturedManifest, localState, commit);
    }

    public static RvcManifest readManifest(Path repositoryDirectory) throws IOException
    {
        return RvcManifest.fromJson(Files.readString(repositoryDirectory.resolve(MANIFEST), StandardCharsets.UTF_8));
    }

    public static RvcLocalState readLocalState(Path repositoryDirectory) throws IOException
    {
        return RvcLocalState.fromJson(Files.readString(repositoryDirectory.resolve(LOCAL_JSON), StandardCharsets.UTF_8));
    }

    public static void writeProjectFiles(Path repositoryDirectory, RvcManifest manifest, RvcLocalState localState) throws IOException
    {
        writeVersionedProjectFiles(repositoryDirectory, manifest);
        writeLocalState(repositoryDirectory, localState);
    }

    public static void writeVersionedProjectFiles(Path repositoryDirectory, RvcManifest manifest) throws IOException
    {
        Files.createDirectories(repositoryDirectory);
        Files.writeString(repositoryDirectory.resolve(MANIFEST), manifest.toJson(), StandardCharsets.UTF_8);
        Files.writeString(repositoryDirectory.resolve(README), createReadme(manifest.name()), StandardCharsets.UTF_8);
        Files.writeString(repositoryDirectory.resolve(GITIGNORE), "/" + LOCAL_JSON + "\n", StandardCharsets.UTF_8);
    }

    public static void writeLocalState(Path repositoryDirectory, RvcLocalState localState) throws IOException
    {
        Files.createDirectories(repositoryDirectory);
        Files.writeString(repositoryDirectory.resolve(LOCAL_JSON), localState.toJson(), StandardCharsets.UTF_8);
    }

    @Nullable
    private static RevCommit commitSemanticFiles(Path repositoryDirectory, RvcPlayerIdentity player, String message, boolean allowEmpty) throws IOException, GitAPIException
    {
        return RvcRepository.commitFilePatterns(
                repositoryDirectory,
                player,
                message,
                List.of(MANIFEST, RvcChunkStore.OBJECTS_DIRECTORY, README, GITIGNORE),
                allowEmpty
        );
    }

    private static String createReadme(String name)
    {
        return "# " + name + "\n\n" +
                "Created by RVC.\n\n" +
                "This repository stores a Minecraft structure project using RVC semantic chunks.\n\n" +
                "## Project files\n\n" +
                "- `rvc.json` stores project metadata, sites, regions, and chunk object references.\n" +
                "- `objects/sha256/` stores immutable `.rvcchunk` content objects.\n" +
                "- `local.json` stores local placement state and is ignored by Git.\n";
    }

    private static void initGitRepository(Path repositoryDirectory) throws GitAPIException
    {
        if (Files.isDirectory(repositoryDirectory.resolve(".git")))
        {
            return;
        }

        try (Git ignored = Git.init().setDirectory(repositoryDirectory.toFile()).setInitialBranch(RvcProjectService.DEFAULT_BRANCH).call())
        {
            // Repository intentionally has no initial commit in the manual browser flow.
        }
    }

    public record CommitResult(RvcManifest manifest, RvcLocalState localState, @Nullable RevCommit commit)
    {
    }

    public record EmptyProjectResult(RvcManifest manifest, RvcLocalState localState)
    {
    }
}
