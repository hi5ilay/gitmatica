package me.zly2006.lvc.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.capture.LvcCaptureEngine;
import me.zly2006.lvc.capture.LvcWorldReader;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.LvcPlayerIdentity;

public final class LvcSemanticRepository
{
    public static final String MANIFEST = "lvc.json";
    public static final String GITIGNORE = ".gitignore";
    public static final String LOCAL_JSON = "local.json";

    private LvcSemanticRepository()
    {
    }

    public static CommitResult initProject(Path repositoryDirectory, String projectName, LvcManifest.Site site,
                                           LvcLocalState.SitePlacement placement, LvcWorldReader worldReader,
                                           LvcPlayerIdentity player) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(worldReader, "worldReader");
        Objects.requireNonNull(player, "player");

        LvcManifest initialManifest = LvcManifest.create(projectName, List.of(site));
        LvcLocalState localState = LvcLocalState.create(initialManifest.projectId(), site.id(), Map.of(site.id(), placement));
        LvcCaptureEngine.Result capture = LvcCaptureEngine.captureSite(repositoryDirectory, site, placement, worldReader);
        LvcManifest capturedManifest = initialManifest.withSiteHashRefs(site.id(), capture.fullHashes(), capture.trackedHashes());

        writeProjectFiles(repositoryDirectory, capturedManifest, localState);
        RevCommit commit = commitSemanticFiles(repositoryDirectory, player, "init", true);

        if (commit == null)
        {
            throw new IOException("Semantic LVC init unexpectedly had no changes");
        }

        return new CommitResult(capturedManifest, localState, commit);
    }

    public static CommitResult initProjectFromCapture(Path repositoryDirectory, String projectName, LvcManifest.Site site,
                                                      LvcLocalState.SitePlacement placement, LvcCaptureEngine.Result capture,
                                                      LvcPlayerIdentity player) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(player, "player");

        LvcManifest initialManifest = LvcManifest.create(projectName, List.of(site));
        LvcLocalState localState = LvcLocalState.create(initialManifest.projectId(), site.id(), Map.of(site.id(), placement));
        LvcManifest capturedManifest = initialManifest.withSiteHashRefs(site.id(), capture.fullHashes(), capture.trackedHashes());

        writeProjectFiles(repositoryDirectory, capturedManifest, localState);
        RevCommit commit = commitSemanticFiles(repositoryDirectory, player, "init", true);

        if (commit == null)
        {
            throw new IOException("Semantic LVC init unexpectedly had no changes");
        }

        return new CommitResult(capturedManifest, localState, commit);
    }

    public static EmptyProjectResult initEmptyProject(Path repositoryDirectory, String projectName, LvcManifest.Site site,
                                                      LvcLocalState.SitePlacement placement) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(placement, "placement");

        LvcManifest manifest = LvcManifest.create(projectName, List.of(site));
        LvcLocalState localState = LvcLocalState.create(manifest.projectId(), site.id(), Map.of(site.id(), placement));

        writeProjectFiles(repositoryDirectory, manifest, localState);
        initGitRepository(repositoryDirectory);

        return new EmptyProjectResult(manifest, localState);
    }

    public static CommitResult commitSite(Path repositoryDirectory, LvcManifest manifest, LvcLocalState localState,
                                          String siteId, LvcWorldReader worldReader, LvcPlayerIdentity player,
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
            throw new IllegalArgumentException("LVC commit message must not be blank");
        }

        LvcManifest.Site site = manifest.site(siteId);
        LvcLocalState.SitePlacement placement = localState.sites().get(siteId);

        if (placement == null)
        {
            throw new IOException("Missing local placement for LVC site: " + siteId);
        }

        LvcCaptureEngine.Result scan = LvcCaptureEngine.scanSite(site, placement, worldReader);

        if (scan.unknownChunks().isEmpty() && saveVersionHasNoCommitChanges(repositoryDirectory, site, scan.trackedHashes()))
        {
            return new CommitResult(manifest, localState, null);
        }

        LvcCaptureEngine.Result capture = LvcCaptureEngine.captureSite(repositoryDirectory, site, placement, worldReader);
        return commitSiteFromCapture(repositoryDirectory, manifest, localState, siteId, capture, player, message);
    }

    public static CommitResult commitSiteFromCapture(Path repositoryDirectory, LvcManifest manifest, LvcLocalState localState,
                                                     String siteId, LvcCaptureEngine.Result capture,
                                                     LvcPlayerIdentity player, String message) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(localState, "localState");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");

        if (message.isBlank())
        {
            throw new IllegalArgumentException("LVC commit message must not be blank");
        }

        LvcManifest capturedManifest = manifest.withSiteHashRefs(siteId, capture.fullHashes(), capture.trackedHashes());

        writeProjectFiles(repositoryDirectory, capturedManifest, localState);
        LvcSemanticObjectPruner.pruneChangedObjects(repositoryDirectory, manifest, capturedManifest, siteId);
        RevCommit commit = commitSemanticFiles(repositoryDirectory, player, message.trim(), false);
        return new CommitResult(capturedManifest, localState, commit);
    }

    public static CommitResult updateSiteAreas(Path repositoryDirectory, LvcManifest manifest, LvcLocalState localState,
                                               String siteId, List<LvcManifest.Region> regions, LvcWorldReader worldReader,
                                               LvcPlayerIdentity player, String message) throws IOException, GitAPIException
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
            throw new IllegalArgumentException("LVC commit message must not be blank");
        }

        LvcManifest.Site site = manifest.site(siteId);
        LvcLocalState.SitePlacement placement = localState.sites().get(siteId);

        if (placement == null)
        {
            throw new IOException("Missing local placement for LVC site: " + siteId);
        }

        LvcManifest.Site updatedSite = site.withRegions(regions);
        LvcCaptureEngine.Result capture = LvcCaptureEngine.captureSite(repositoryDirectory, updatedSite, placement, worldReader);
        return updateSiteAreasFromCapture(repositoryDirectory, manifest, localState, siteId, updatedSite.regions(), capture, player, message);
    }

    public static CommitResult updateSiteAreasFromCapture(Path repositoryDirectory, LvcManifest manifest, LvcLocalState localState,
                                                          String siteId, List<LvcManifest.Region> regions,
                                                          LvcCaptureEngine.Result capture, LvcPlayerIdentity player,
                                                          String message) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(localState, "localState");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");

        if (message.isBlank())
        {
            throw new IllegalArgumentException("LVC commit message must not be blank");
        }

        LvcManifest.Site updatedSite = manifest.site(siteId).withRegions(regions);
        LvcManifest capturedManifest = manifest.withSite(siteId, updatedSite.withHashRefs(capture.fullHashes(), capture.trackedHashes()));

        writeProjectFiles(repositoryDirectory, capturedManifest, localState);
        LvcSemanticObjectPruner.pruneChangedObjects(repositoryDirectory, manifest, capturedManifest, siteId);
        RevCommit commit = commitSemanticFiles(repositoryDirectory, player, message.trim(), false);
        return new CommitResult(capturedManifest, localState, commit);
    }

    public static LvcManifest readManifest(Path repositoryDirectory) throws IOException
    {
        String manifestJson = Files.readString(repositoryDirectory.resolve(MANIFEST), StandardCharsets.UTF_8);
        logSerializedContentIgnored(repositoryDirectory.toString(), manifestJson);
        LvcManifest manifest = LvcManifest.fromJson(manifestJson);
        LvcManifest hydrated = hydrateHashIndexes(manifest, indexPath -> Files.readAllBytes(resolveVersionedPath(repositoryDirectory, indexPath)));
        LvcDiagnostics.debug("LvcSemanticRepository: read manifest repo='{}' sites={} chunkRefs={} internalContent='{}'",
                repositoryDirectory, hydrated.sites().size(), totalHashRefs(hydrated), contentSummary(hydrated.content()));
        return hydrated;
    }

    public static LvcManifest readCommitManifest(Repository repository, RevCommit commit) throws IOException
    {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(commit, "commit");
        String manifestJson = LvcProjectGitOps.readCommitTextFile(repository, commit, MANIFEST);

        if (manifestJson == null)
        {
            throw new IOException("Commit does not contain lvc.json: " + commit.getName());
        }

        logSerializedContentIgnored(commit.getName(), manifestJson);
        LvcManifest manifest = LvcManifest.fromJson(manifestJson);
        LvcManifest hydrated = hydrateHashIndexes(manifest, indexPath -> LvcProjectGitOps.readCommitFile(repository, commit, indexPath));
        LvcDiagnostics.debug("LvcSemanticRepository: read commit manifest commit={} sites={} chunkRefs={} internalContent='{}'",
                commit.getName(), hydrated.sites().size(), totalHashRefs(hydrated), contentSummary(hydrated.content()));
        return hydrated;
    }

    public static LvcManifest hydrateHashIndexes(LvcManifest manifest, HashIndexReader indexReader) throws IOException
    {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(indexReader, "indexReader");
        List<LvcManifest.Site> sites = new java.util.ArrayList<>(manifest.sites().size());

        for (LvcManifest.Site site : manifest.sites())
        {
            byte[] bytes = indexReader.read(site.hashIndex());

            if (bytes == null)
            {
                throw new IOException("Missing LVC hash index: " + site.hashIndex());
            }

            LvcHashIndexCodec.HashRefs refs = LvcHashIndexCodec.decode(bytes);
            LvcDiagnostics.debug("LvcSemanticRepository: hydrated hash index '{}' bytes={} chunks={}",
                    site.hashIndex(), bytes.length, refs.fullHashes().size());
            sites.add(site.withHashRefs(refs.fullHashes(), refs.trackedHashes()));
        }

        return new LvcManifest(manifest.format(), manifest.projectId(), manifest.name(), manifest.content(), sites).validate();
    }

    public static LvcLocalState readLocalState(Path repositoryDirectory) throws IOException
    {
        return LvcLocalState.fromJson(Files.readString(repositoryDirectory.resolve(LOCAL_JSON), StandardCharsets.UTF_8));
    }

    public static Map<String, String> computeTrackedHashesFromFullObjects(Path repositoryDirectory, LvcManifest.Site site) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");
        Map<String, String> trackedHashes = new java.util.TreeMap<>();
        Map<String, String> trackedHashesByFullObjectId = new HashMap<>();

        for (Map.Entry<String, String> entry : site.fullHashes().entrySet())
        {
            String trackedHash = trackedHashesByFullObjectId.get(entry.getValue());

            if (trackedHash == null)
            {
                LvcChunk chunk = LvcChunkCodec.decode(LvcChunkStore.readObject(repositoryDirectory, entry.getValue()));
                trackedHash = LvcChunkStore.objectId(LvcChunkCodec.encodeTrackedContent(chunk));
                trackedHashesByFullObjectId.put(entry.getValue(), trackedHash);
            }

            trackedHashes.put(entry.getKey(), trackedHash);
        }

        if (trackedHashes.isEmpty())
        {
            return site.trackedHashesForComparison();
        }

        LvcDiagnostics.debug("LvcSemanticRepository: computed tracked hashes from full objects repo='{}' chunks={} uniqueObjects={}",
                repositoryDirectory, trackedHashes.size(), trackedHashesByFullObjectId.size());
        return Map.copyOf(trackedHashes);
    }

    public static boolean saveVersionHasNoCommitChanges(Path repositoryDirectory, LvcManifest.Site site,
                                                        Map<String, String> capturedTrackedHashes)
            throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(capturedTrackedHashes, "capturedTrackedHashes");

        Map<String, String> expectedTrackedHashes = computeTrackedHashesFromFullObjects(repositoryDirectory, site);
        boolean trackedMatches = Objects.equals(expectedTrackedHashes, capturedTrackedHashes);
        boolean hasGitChanges = LvcProjectGitOps.hasUncommittedChanges(repositoryDirectory);

        LvcDiagnostics.debug("LvcSemanticRepository: save-version no-op check repo='{}' site='{}' trackedMatches={} hasGitChanges={} capturedChunks={} expectedChunks={}",
                repositoryDirectory, site.id(), trackedMatches, hasGitChanges, capturedTrackedHashes.size(), expectedTrackedHashes.size());
        return trackedMatches && !hasGitChanges;
    }

    public static void writeProjectFiles(Path repositoryDirectory, LvcManifest manifest, LvcLocalState localState) throws IOException
    {
        writeVersionedProjectFiles(repositoryDirectory, manifest);
        writeLocalState(repositoryDirectory, localState);
    }

    public static void writeVersionedProjectFiles(Path repositoryDirectory, LvcManifest manifest) throws IOException
    {
        Files.createDirectories(repositoryDirectory);
        writeHashIndexes(repositoryDirectory, manifest);
        Files.writeString(repositoryDirectory.resolve(MANIFEST), manifest.toJson(), StandardCharsets.UTF_8);
        Files.writeString(repositoryDirectory.resolve(GITIGNORE), "/" + LOCAL_JSON + "\n", StandardCharsets.UTF_8);
        LvcDiagnostics.debug("LvcSemanticRepository: wrote versioned project files repo='{}' sites={} chunkRefs={} internalContent='{}' manifestContentSerialized=false readmeGenerated=false",
                repositoryDirectory, manifest.sites().size(), totalHashRefs(manifest), contentSummary(manifest.content()));
    }

    public static void writeLocalState(Path repositoryDirectory, LvcLocalState localState) throws IOException
    {
        Files.createDirectories(repositoryDirectory);
        Files.writeString(repositoryDirectory.resolve(LOCAL_JSON), localState.toJson(), StandardCharsets.UTF_8);
    }

    @Nullable
    private static RevCommit commitSemanticFiles(Path repositoryDirectory, LvcPlayerIdentity player, String message, boolean allowEmpty) throws IOException, GitAPIException
    {
        return LvcRepository.commitFilePatterns(
                repositoryDirectory,
                player,
                message,
                List.of(MANIFEST, LvcHashIndexCodec.INDEXES_DIRECTORY, LvcChunkStore.OBJECTS_DIRECTORY, GITIGNORE),
                allowEmpty
        );
    }

    public static long hashIndexesLastModified(Path repositoryDirectory, LvcManifest manifest) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(manifest, "manifest");
        long newest = 0L;

        for (LvcManifest.Site site : manifest.sites())
        {
            newest = Math.max(newest, Files.getLastModifiedTime(resolveVersionedPath(repositoryDirectory, site.hashIndex())).toMillis());
        }

        return newest;
    }

    private static void writeHashIndexes(Path repositoryDirectory, LvcManifest manifest) throws IOException
    {
        for (LvcManifest.Site site : manifest.sites())
        {
            Path indexPath = resolveVersionedPath(repositoryDirectory, site.hashIndex());
            LvcHashIndexCodec.write(indexPath, site.fullHashes(), site.trackedHashesForComparison());
            LvcDiagnostics.debug("LvcSemanticRepository: wrote hash index '{}' site={} chunks={} bytes={} compressionLevel={}",
                    site.hashIndex(), site.id(), site.fullHashes().size(), Files.size(indexPath), LvcHashIndexCodec.COMPRESSION_LEVEL);
        }
    }

    private static int totalHashRefs(LvcManifest manifest)
    {
        int total = 0;

        for (LvcManifest.Site site : manifest.sites())
        {
            total += site.fullHashes().size();
        }

        return total;
    }

    private static void logSerializedContentIgnored(String source, String manifestJson)
    {
        if (LvcManifest.hasSerializedContent(manifestJson))
        {
            LvcDiagnostics.debug("LvcSemanticRepository: ignored serialized internal content metadata source='{}'", source);
        }
    }

    private static String contentSummary(LvcManifest.Content content)
    {
        return content.chunkFormat() + ", " + content.hashIndexFormat() + ", " + content.hash() + ", " + content.chunkSize();
    }

    private static Path resolveVersionedPath(Path repositoryDirectory, String relativePath) throws IOException
    {
        Path repositoryRoot = repositoryDirectory.toAbsolutePath().normalize();
        Path resolved = repositoryRoot.resolve(relativePath).normalize();

        if (!resolved.startsWith(repositoryRoot))
        {
            throw new IOException("LVC versioned path escapes repository: " + relativePath);
        }

        return resolved;
    }

    private static void initGitRepository(Path repositoryDirectory) throws GitAPIException
    {
        if (Files.isDirectory(repositoryDirectory.resolve(".git")))
        {
            return;
        }

        try (Git ignored = Git.init().setDirectory(repositoryDirectory.toFile()).setInitialBranch(LvcProjectService.DEFAULT_BRANCH).call())
        {
            // Repository intentionally has no initial commit in the manual browser flow.
        }
    }

    public record CommitResult(LvcManifest manifest, LvcLocalState localState, @Nullable RevCommit commit)
    {
    }

    public record EmptyProjectResult(LvcManifest manifest, LvcLocalState localState)
    {
    }

    @FunctionalInterface
    public interface HashIndexReader
    {
        @Nullable byte[] read(String indexPath) throws IOException;
    }
}
