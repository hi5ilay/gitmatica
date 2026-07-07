package me.zly2006.lvc;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.git.LvcGitBranchOps;
import me.zly2006.lvc.git.LvcGitHistoryOps;
import me.zly2006.lvc.git.LvcGitRemoteOps;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.overlay.LvcTrackingOverlayService;
import me.zly2006.lvc.semantic.LvcSemanticProjectEditor;
import me.zly2006.lvc.semantic.LvcSemanticProjectOperations;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import me.zly2006.lvc.capture.LvcCaptureEngine;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.project.LvcProjectPaths;
import me.zly2006.lvc.project.LvcProjectPositions;
import me.zly2006.lvc.project.LvcProjectSelectionStorage;

public final class LvcProjectService
{
    public static final String REPOS_DIRECTORY = "lvc-projects";
    public static final String LOCAL_JSON = "local.json";
    public static final String DEFAULT_BRANCH = "main";
    private static final ConcurrentMap<Path, CachedProjectSummary> PROJECT_SUMMARY_CACHE = new ConcurrentHashMap<>();

    private LvcProjectService()
    {
    }

    public static Result createProject(Path gameRunDirectory, String repositoryName, LvcPlayerIdentity player, Level world, AreaSelection selection) throws Exception
    {
        return LvcSemanticProjectOperations.createProject(gameRunDirectory, repositoryName, player, world, selection);
    }

    public static EmptyProjectResult createEmptyProject(Path gameRunDirectory, String repositoryName, BlockPos origin, String dimensionId) throws Exception
    {
        return LvcSemanticProjectOperations.createEmptyProject(gameRunDirectory, repositoryName, origin, dimensionId);
    }

    @Nullable
    public static RevCommit gitCommit(Path repositoryDirectory, String projectName, LvcPlayerIdentity player, Level world,
                                      @Nullable AreaSelection currentSelectionFallback, boolean ignoreEntities, String message) throws Exception
    {
        return LvcSemanticProjectOperations.gitCommit(repositoryDirectory, projectName, player, world, currentSelectionFallback, ignoreEntities, message);
    }

    public static SemanticScanResult scanSemanticChanges(Path repositoryDirectory, Level world) throws Exception
    {
        return LvcSemanticProjectOperations.scanSemanticChanges(repositoryDirectory, world);
    }

    public static SemanticCheckoutPreflight preflightSemanticCheckout(Path repositoryDirectory, Level world) throws Exception
    {
        return LvcSemanticProjectOperations.preflightSemanticCheckout(repositoryDirectory, world);
    }

    public static SemanticCheckoutPreflight preflightSemanticClearArea(Path repositoryDirectory, Level world) throws Exception
    {
        return LvcSemanticProjectOperations.preflightSemanticClearArea(repositoryDirectory, world);
    }

    public static SemanticWorldClearResult clearSemanticArea(Path repositoryDirectory, Level world) throws Exception
    {
        return LvcSemanticProjectOperations.clearSemanticArea(repositoryDirectory, world);
    }

    public static ExportResult exportCommitToLitematic(Path repositoryDirectory, String commitId, Path outputDirectory) throws IOException
    {
        return LvcSemanticProjectOperations.exportCommitToLitematic(repositoryDirectory, commitId, outputDirectory);
    }

    public static LitematicaSchematic buildSemanticCommitSchematic(Path repositoryDirectory, String commitId) throws IOException
    {
        return LvcSemanticProjectOperations.buildSemanticCommitSchematic(repositoryDirectory, commitId);
    }

    public static UpdateAreasResult updateSemanticAreas(Path repositoryDirectory, LvcPlayerIdentity player, Level world,
                                                        AreaSelection selection, String message) throws Exception
    {
        return LvcSemanticProjectOperations.updateSemanticAreas(repositoryDirectory, player, world, selection, message);
    }

    public static List<Project> listProjects(Path gameRunDirectory) throws IOException
    {
        Path reposDirectory = reposDirectory(gameRunDirectory);

        if (!Files.isDirectory(reposDirectory))
        {
            return List.of();
        }

        List<Project> projects = new ArrayList<>();

        try (var stream = Files.list(reposDirectory))
        {
            for (Path candidate : stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList())
            {
                if (isValidProjectRepository(candidate))
                {
                    projects.add(new Project(candidate.getFileName().toString(), candidate));
                }
            }
        }

        return List.copyOf(projects);
    }

    public static void deleteProjectRepository(Path gameRunDirectory, Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(gameRunDirectory, "gameRunDirectory");
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        Path reposRoot = reposDirectory(gameRunDirectory).toAbsolutePath().normalize();
        Path target = repositoryDirectory.toAbsolutePath().normalize();

        if (!target.startsWith(reposRoot) || target.equals(reposRoot))
        {
            throw new IOException("LVC project must be under " + reposRoot);
        }

        if (!isValidProjectRepository(target))
        {
            throw new IOException("Not a valid LVC project repository: " + target);
        }

        deleteRecursively(target);
        LvcDiagnostics.debug("LvcProjectService: deleted LVC project repository '{}'", target);
    }

    public static ProjectSummary projectSummary(Project project) throws IOException, GitAPIException
    {
        Objects.requireNonNull(project, "project");
        Path repositoryDirectory = project.directory().toAbsolutePath().normalize();
        ProjectSummarySnapshot snapshot = projectSummarySnapshot(repositoryDirectory);
        CachedProjectSummary cached = PROJECT_SUMMARY_CACHE.get(repositoryDirectory);

        if (cached != null && cached.snapshot().equals(snapshot))
        {
            return cached.summary();
        }

        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        LvcLocalState localState = LvcSemanticRepository.readLocalState(repositoryDirectory);
        LvcLocalState.SitePlacement placement = localState.sites().get(localState.activeSite());
        BlockPos origin = placement == null ? null : LvcProjectPositions.blockPosFromList(placement.origin());
        ProjectSummary summary = new ProjectSummary(manifest.name(), countCommitsAcrossLocalBranches(repositoryDirectory), origin);

        PROJECT_SUMMARY_CACHE.put(repositoryDirectory, new CachedProjectSummary(snapshot, summary));
        return summary;
    }

    private static ProjectSummarySnapshot projectSummarySnapshot(Path repositoryDirectory) throws IOException, GitAPIException
    {
        long manifestModified = Files.getLastModifiedTime(repositoryDirectory.resolve(LvcSemanticRepository.MANIFEST)).toMillis();
        long localModified = Files.getLastModifiedTime(repositoryDirectory.resolve(LvcSemanticRepository.LOCAL_JSON)).toMillis();
        return new ProjectSummarySnapshot(manifestModified, localModified, LvcGitHistoryOps.localBranchRefsSnapshot(repositoryDirectory));
    }

    public static ProjectEditorState readSemanticProjectEditorState(Path repositoryDirectory) throws IOException
    {
        return LvcSemanticProjectEditor.readState(repositoryDirectory);
    }

    public static void updateSemanticProjectName(Path repositoryDirectory, String projectName) throws IOException
    {
        LvcSemanticProjectEditor.updateProjectName(repositoryDirectory, projectName);
    }

    public static void updateSemanticLocalOrigin(Path repositoryDirectory, BlockPos origin) throws IOException
    {
        LvcSemanticProjectEditor.updateLocalOrigin(repositoryDirectory, origin);
    }

    public static boolean updateSemanticPlacementOrigin(Path repositoryDirectory, BlockPos origin) throws IOException
    {
        LvcSemanticProjectEditor.updateLocalOrigin(repositoryDirectory, origin);
        return LvcTrackingOverlayService.updateTrackingOverlayOrigin(repositoryDirectory, origin);
    }

    public static void updateSemanticRegion(Path repositoryDirectory, String regionId, String name, BlockPos min, BlockPos size) throws IOException
    {
        LvcSemanticProjectEditor.updateRegion(repositoryDirectory, regionId, name, min, size);
    }

    public static LvcManifest.Region createSemanticRegion(Path repositoryDirectory, String name, BlockPos min, BlockPos size) throws IOException
    {
        return LvcSemanticProjectEditor.createRegion(repositoryDirectory, name, min, size);
    }

    public static void deleteSemanticRegion(Path repositoryDirectory, String regionId) throws IOException
    {
        LvcSemanticProjectEditor.deleteRegion(repositoryDirectory, regionId);
    }

    public static List<CommitInfo> listCommits(Path repositoryDirectory) throws IOException, GitAPIException
    {
        return LvcGitHistoryOps.listCommits(repositoryDirectory);
    }

    public static int countCommitsAcrossLocalBranches(Path repositoryDirectory) throws IOException, GitAPIException
    {
        return LvcGitHistoryOps.countCommitsAcrossLocalBranches(repositoryDirectory);
    }

    public static void checkoutCommitToWorkingTree(Path repositoryDirectory, String commitId) throws GitAPIException, IOException
    {
        LvcGitBranchOps.checkoutCommitToWorkingTree(repositoryDirectory, commitId);
    }

    public static void checkoutBranchToWorkingTree(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        LvcGitBranchOps.checkoutBranchToWorkingTree(repositoryDirectory, branchName);
    }

    public static String localBranchTipCommitId(Path repositoryDirectory, String branchName) throws IOException
    {
        return LvcGitBranchOps.localBranchTipCommitId(repositoryDirectory, branchName);
    }

    public static String createAndCheckoutBranch(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        return LvcGitBranchOps.createAndCheckoutBranch(repositoryDirectory, branchName);
    }

    public static String validateNewBranchName(Path repositoryDirectory, String branchName) throws IOException
    {
        return LvcGitBranchOps.validateNewBranchName(repositoryDirectory, branchName);
    }

    public static String deleteBranch(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        return LvcGitBranchOps.deleteBranch(repositoryDirectory, branchName);
    }

    public static String renameBranch(Path repositoryDirectory, String oldName, String newName) throws GitAPIException, IOException
    {
        return LvcGitBranchOps.renameBranch(repositoryDirectory, oldName, newName);
    }

    public static BranchMergeResult mergeBranch(Path repositoryDirectory, String sourceBranch, LvcPlayerIdentity player) throws Exception
    {
        return LvcProjectGitOps.mergeBranch(repositoryDirectory, sourceBranch, player, null);
    }

    public static BranchMergeResult mergeBranch(Path repositoryDirectory, String sourceBranch, LvcPlayerIdentity player,
                                                @Nullable BranchMergeConflictResolution conflictResolution) throws Exception
    {
        return LvcProjectGitOps.mergeBranch(repositoryDirectory, sourceBranch, player, conflictResolution);
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

    public static boolean hasUncommittedChanges(Path repositoryDirectory) throws GitAPIException, IOException
    {
        return LvcGitBranchOps.hasUncommittedChanges(repositoryDirectory);
    }

    public static void resetWorkingTreeToHead(Path repositoryDirectory) throws GitAPIException, IOException
    {
        LvcGitBranchOps.resetWorkingTreeToHead(repositoryDirectory);
    }

    public static LatestCommitUndoTarget latestCommitUndoTarget(Path repositoryDirectory) throws IOException
    {
        return LvcGitBranchOps.latestCommitUndoTarget(repositoryDirectory);
    }

    public static LatestCommitUndoResult undoLatestCommitKeepChanges(Path repositoryDirectory) throws GitAPIException, IOException
    {
        return LvcGitBranchOps.undoLatestCommitKeepChanges(repositoryDirectory);
    }

    public static LatestCommitUndoResult undoLatestCommitDeleteChanges(Path repositoryDirectory) throws GitAPIException, IOException
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

    public static TrackingOverlay loadTrackingOverlay(Path repositoryDirectory, String projectName,
                                                      @Nullable ClientLevel clientLevel,
                                                      @Nullable ICompletionListener completionListener) throws IOException
    {
        return LvcTrackingOverlayService.loadTrackingOverlay(repositoryDirectory, projectName, clientLevel, completionListener);
    }

    @Nullable
    public static TrackingOverlay refreshReusableTrackingOverlayVerifier(Path repositoryDirectory,
                                                                         @Nullable ClientLevel clientLevel,
                                                                         @Nullable ICompletionListener completionListener) throws IOException
    {
        return LvcTrackingOverlayService.refreshVerifierIfCurrent(repositoryDirectory, clientLevel, completionListener);
    }

    public static void removeTrackingOverlay(Path repositoryDirectory)
    {
        LvcTrackingOverlayService.removeTrackingOverlay(repositoryDirectory);
    }

    public static boolean focusTrackingOverlay(Path repositoryDirectory)
    {
        return LvcTrackingOverlayService.focusTrackingOverlay(repositoryDirectory);
    }

    @Nullable
    public static TrackingOverlay getReusableSemanticTrackingOverlay(Path repositoryDirectory,
                                                                     @Nullable ClientLevel clientLevel,
                                                                     @Nullable ICompletionListener completionListener) throws IOException
    {
        return LvcTrackingOverlayService.getReusableSemanticTrackingOverlay(repositoryDirectory, clientLevel, completionListener);
    }

    public static String trackingOverlayDisplayName(Path repositoryDirectory, String projectName)
    {
        return LvcTrackingOverlayService.trackingOverlayDisplayName(repositoryDirectory, projectName);
    }

    static LitematicaSchematic writeAndReloadSemanticTrackingSchematic(Path repositoryDirectory, LvcManifest manifest,
                                                                       LvcLocalState localState, String siteId,
                                                                       String overlayName) throws IOException
    {
        return LvcTrackingOverlayService.writeAndReloadSemanticTrackingSchematic(repositoryDirectory, manifest, localState, siteId, overlayName);
    }

    public static Path writeSemanticTrackingCacheFile(Path repositoryDirectory, LvcManifest manifest,
                                               LvcLocalState localState, String siteId,
                                               String overlayName) throws IOException
    {
        return LvcTrackingOverlayService.writeSemanticTrackingCacheFile(repositoryDirectory, manifest, localState, siteId, overlayName);
    }

    public static Path reposDirectory(Path gameRunDirectory)
    {
        return LvcProjectPaths.reposDirectory(gameRunDirectory);
    }

    public static Path repositoryDirectory(Path gameRunDirectory, String projectName)
    {
        return LvcProjectPaths.repositoryDirectory(gameRunDirectory, projectName);
    }

    public static boolean isSemanticProject(Path repositoryDirectory)
    {
        return Files.isRegularFile(repositoryDirectory.resolve(LvcSemanticRepository.MANIFEST));
    }

    public static boolean isProjectRepository(Path candidate)
    {
        Objects.requireNonNull(candidate, "candidate");
        return isValidProjectRepository(candidate);
    }

    public static LvcManifest.Site createMainSiteFromSelection(String siteName, String dimensionId, AreaSelection selection)
    {
        return LvcProjectSelectionStorage.createMainSiteFromSelection(siteName, dimensionId, selection);
    }

    public static List<LvcManifest.Region> createRegionsFromSelection(AreaSelection selection, BlockPos origin, List<LvcManifest.Region> existingRegions)
    {
        return LvcProjectSelectionStorage.createRegionsFromSelection(selection, origin, existingRegions);
    }

    public static LvcLocalState.SitePlacement createSitePlacement(BlockPos origin, String dimensionId)
    {
        return LvcProjectSelectionStorage.createSitePlacement(origin, dimensionId);
    }

    public static int countValidSelectionRegions(AreaSelection selection)
    {
        return LvcProjectSelectionStorage.countValidSelectionRegions(selection);
    }

    private static boolean isValidProjectRepository(Path candidate)
    {
        if (!Files.isDirectory(candidate.resolve(".git")) || !Files.isRegularFile(candidate.resolve(LvcSemanticRepository.MANIFEST)))
        {
            return false;
        }

        try (Git git = Git.open(candidate.toFile()))
        {
            git.getRepository();
            return true;
        }
        catch (Exception e)
        {
            LvcDiagnostics.debug("LvcProjectService: rejected invalid LVC project repository '{}': {}", candidate, e.getMessage());
            return false;
        }
    }

    private static void deleteRecursively(Path directory) throws IOException
    {
        Files.walkFileTree(directory, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                if (exc != null)
                {
                    throw exc;
                }

                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String shortCommit(String commitId)
    {
        return commitId.substring(0, Math.min(8, commitId.length()));
    }

    public record Project(String name, Path directory)
    {
    }

    public record ProjectSummary(String name, int versionCount, @Nullable BlockPos origin)
    {
    }

    private record ProjectSummarySnapshot(long manifestModified, long localModified, Map<String, String> localBranchRefs)
    {
    }

    private record CachedProjectSummary(ProjectSummarySnapshot snapshot, ProjectSummary summary)
    {
    }

    public record ProjectEditorState(String projectName, String siteId, String siteName, String siteDimension,
                                     String localDimension, BlockPos localOrigin, String worldHint,
                                     List<LvcManifest.Region> regions)
    {
        public ProjectEditorState
        {
            regions = List.copyOf(regions);
        }
    }

    public record CommitInfo(String id, String shortId, String message, String description, String author, String time,
                             int subRegionCount, String changes)
    {
    }

    public record LatestCommitUndoTarget(String commitId, String parentCommitId, String branchName)
    {
        public String shortCommitId()
        {
            return shortCommit(this.commitId);
        }

        public String shortParentCommitId()
        {
            return shortCommit(this.parentCommitId);
        }
    }

    public record LatestCommitUndoResult(String commitId, String parentCommitId, String branchName)
    {
        public String shortCommitId()
        {
            return shortCommit(this.commitId);
        }

        public String shortParentCommitId()
        {
            return shortCommit(this.parentCommitId);
        }
    }

    public record TrackingOverlay(SchematicPlacement placement, SchematicVerifier verifier, boolean verifierStarted)
    {
    }

    public record ExportResult(Path file, String fileName)
    {
    }

    public record SemanticScanResult(String siteId, int unchangedChunks, int changedChunks, int addedChunks,
                                     int removedChunks, int unknownChunks, List<SemanticScanMismatch> samples)
    {
        public SemanticScanResult(String siteId, int unchangedChunks, int changedChunks, int addedChunks,
                                  int removedChunks, int unknownChunks)
        {
            this(siteId, unchangedChunks, changedChunks, addedChunks, removedChunks, unknownChunks, List.of());
        }

        public SemanticScanResult
        {
            samples = List.copyOf(samples);
        }

        public static SemanticScanResult compare(String siteId, Map<String, String> expectedTrackedHashes, LvcCaptureEngine.Result scan)
        {
            Set<String> keys = new HashSet<>();
            keys.addAll(expectedTrackedHashes.keySet());
            keys.addAll(scan.trackedHashes().keySet());
            keys.addAll(scan.unknownChunks());

            int unchanged = 0;
            int changed = 0;
            int added = 0;
            int removed = 0;
            int unknown = 0;

            for (String key : keys)
            {
                if (scan.unknownChunks().contains(key))
                {
                    unknown++;
                    continue;
                }

                String expected = expectedTrackedHashes.get(key);
                String actual = scan.trackedHashes().get(key);

                if (expected == null && actual != null)
                {
                    added++;
                }
                else if (expected != null && actual == null)
                {
                    removed++;
                }
                else if (Objects.equals(expected, actual))
                {
                    unchanged++;
                }
                else
                {
                    changed++;
                }
            }

            return new SemanticScanResult(siteId, unchanged, changed, added, removed, unknown);
        }

        public boolean clean()
        {
            return this.dirtyChunks() == 0 && this.unknownChunks == 0;
        }

        public int dirtyChunks()
        {
            return this.changedChunks + this.addedChunks + this.removedChunks;
        }

        public int knownChunks()
        {
            return this.unchangedChunks + this.changedChunks + this.addedChunks + this.removedChunks;
        }

        public SemanticScanResult withSamples(List<SemanticScanMismatch> samples)
        {
            return new SemanticScanResult(this.siteId, this.unchangedChunks, this.changedChunks, this.addedChunks,
                    this.removedChunks, this.unknownChunks, samples);
        }
    }

    public record SemanticScanMismatch(String chunkKey, String position, String expected, String actual)
    {
        public String summary()
        {
            return "chunk " + this.chunkKey + " at " + this.position + ": expected " + this.expected + ", server " + this.actual;
        }
    }

    public record SemanticCheckoutPreflight(SemanticScanResult scanResult, int regionCount)
    {
    }

    public record SemanticWorldClearResult(int regionCount, int clearedBlocks)
    {
    }

    public record UpdateAreasResult(@Nullable RevCommit commit, int regionCount)
    {
    }

    public enum BranchMergeStatus
    {
        UP_TO_DATE,
        FAST_FORWARD,
        MERGED
    }

    public enum BranchMergeConflictResolution
    {
        BASE,
        INCOMING,
        YOURS
    }

    public record BranchMergeResult(BranchMergeStatus status, String targetBranch, String sourceBranch,
                                    String commitId, int regionCount, int mergedChunks)
    {
    }

    public record Result(Path repositoryDirectory, String commitId)
    {
    }

    public record EmptyProjectResult(Path repositoryDirectory, String projectName)
    {
    }
}
