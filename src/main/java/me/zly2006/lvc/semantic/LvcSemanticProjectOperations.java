package me.zly2006.lvc.semantic;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.capture.LvcCaptureEngine;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.storage.LvcChunkCodec;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.LvcPlayerIdentity;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.LvcUserActionException;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.semantic.LvcSemanticSchematicBuilder;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.project.LvcProjectPaths;
import me.zly2006.lvc.project.LvcProjectPositions;
import me.zly2006.lvc.project.LvcProjectSelectionStorage;
import me.zly2006.lvc.task.LvcAuthoritativeClientSyncTask;
import me.zly2006.lvc.task.LvcSemanticRestoreEngine;
import me.zly2006.lvc.task.LvcSemanticScanMismatchSampler;
import me.zly2006.lvc.world.LvcWorldAccess;
import me.zly2006.lvc.util.LvcLitematicExportFiles;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.selection.AreaSelection;

public final class LvcSemanticProjectOperations
{
    private static final int PREFLIGHT_MISMATCH_SAMPLE_LIMIT = 8;

    private LvcSemanticProjectOperations()
    {
    }

    public static LvcProjectService.Result createProject(Path gameRunDirectory, String repositoryName, LvcPlayerIdentity player,
                                                  Level world, AreaSelection selection) throws Exception
    {
        Objects.requireNonNull(gameRunDirectory, "gameRunDirectory");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(selection, "selection");

        String displayName = LvcProjectPaths.normalizeDisplayName(repositoryName);
        int validBoxCount = LvcProjectSelectionStorage.countValidSelectionRegions(selection);

        if (validBoxCount <= 0)
        {
            throw new IllegalArgumentException("LVC project requires at least one valid selection box");
        }

        Path repositoryDirectory = LvcProjectPaths.repositoryDirectory(gameRunDirectory, displayName);

        if (Files.exists(repositoryDirectory))
        {
            throw new FileAlreadyExistsException(repositoryDirectory.toString());
        }

        Files.createDirectories(repositoryDirectory);

        Level captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(world);
        String dimensionId = LvcMinecraftWorldReader.dimensionId(captureWorld);
        LvcManifest.Site site = LvcProjectSelectionStorage.createMainSiteFromSelection(displayName, dimensionId, selection);
        LvcLocalState.SitePlacement placement = LvcProjectSelectionStorage.createSitePlacement(selection.getEffectiveOrigin(), dimensionId);
        LvcDiagnostics.debug("LvcSemanticProjectOperations: creating semantic project repo='{}' project='{}' dimension='{}' regions={} origin='{}'",
                repositoryDirectory, displayName, dimensionId, validBoxCount, placement.origin());
        LvcSemanticRepository.CommitResult result = LvcCapturePublishCommitFlow.initProject(
                repositoryDirectory,
                displayName,
                site,
                placement,
                captureWorld,
                player
        );
        RevCommit commit = result.commit();

        return new LvcProjectService.Result(repositoryDirectory, commit.getName());
    }

    public static LvcProjectService.EmptyProjectResult createEmptyProject(Path gameRunDirectory, String repositoryName,
                                                                   BlockPos origin, String dimensionId) throws Exception
    {
        Objects.requireNonNull(gameRunDirectory, "gameRunDirectory");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(dimensionId, "dimensionId");

        String displayName = LvcProjectPaths.normalizeDisplayName(repositoryName);
        LvcProjectSelectionStorage.validateProjectName(displayName);

        if (dimensionId.isBlank())
        {
            throw new IllegalArgumentException("LVC project dimension must not be blank");
        }

        Path repositoryDirectory = LvcProjectPaths.repositoryDirectory(gameRunDirectory, displayName);

        if (Files.exists(repositoryDirectory))
        {
            throw new FileAlreadyExistsException(repositoryDirectory.toString());
        }

        Files.createDirectories(repositoryDirectory);

        LvcManifest.Site site = new LvcManifest.Site("main", displayName, dimensionId, List.of(), Map.of());
        LvcLocalState.SitePlacement placement = LvcProjectSelectionStorage.createSitePlacement(origin, dimensionId);
        LvcSemanticRepository.initEmptyProject(repositoryDirectory, displayName, site, placement);

        return new LvcProjectService.EmptyProjectResult(repositoryDirectory, displayName);
    }

    @Nullable
    public static RevCommit gitCommit(Path repositoryDirectory, String projectName, LvcPlayerIdentity player, Level world,
                               @Nullable AreaSelection currentSelectionFallback, boolean ignoreEntities, String message) throws Exception
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(message, "message");

        Level captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(world);
        LvcCapturePublishCommitFlow.ActiveSite activeSite = LvcCapturePublishCommitFlow.readActiveSite(repositoryDirectory, captureWorld);
        return LvcCapturePublishCommitFlow.commitActiveSite(repositoryDirectory, activeSite, captureWorld, player, message).commit();
    }

    public static LvcProjectService.SemanticScanResult scanSemanticChanges(Path repositoryDirectory, Level world) throws Exception
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(world, "world");

        if (!LvcProjectService.isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Scan Changes currently supports semantic LVC projects only");
        }

        Level captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(world);
        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        LvcLocalState localState = LvcSemanticRepository.readLocalState(repositoryDirectory);
        String siteId = localState.activeSite();
        LvcManifest.Site site = manifest.site(siteId);
        LvcLocalState.SitePlacement placement = localState.sites().get(siteId);

        if (placement == null)
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_LOCAL_PLACEMENT,
                    "Missing local placement for active LVC site: " + siteId);
        }

        String worldDimension = LvcMinecraftWorldReader.dimensionId(captureWorld);

        if (!worldDimension.equals(placement.dimension()))
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.WRONG_DIMENSION,
                    "Active LVC site is in " + placement.dimension() + " but current world is " + worldDimension);
        }

        return LvcWorldAccess.runOnSemanticCaptureWorld(captureWorld, authoritativeWorld ->
        {
            LvcCaptureEngine.Result scan = LvcCaptureEngine.scanSite(site, placement, new LvcMinecraftWorldReader(authoritativeWorld));
            Map<String, String> expectedTrackedHashes = LvcSemanticRepository.computeTrackedHashesFromFullObjects(repositoryDirectory, site);
            return LvcProjectService.SemanticScanResult.compare(siteId, expectedTrackedHashes, scan);
        });
    }

    public static LvcProjectService.SemanticCheckoutPreflight preflightSemanticCheckout(Path repositoryDirectory, Level world) throws Exception
    {
        return preflightSemanticActiveSite(repositoryDirectory, world, "Semantic checkout preflight requires a semantic LVC project");
    }

    public static LvcProjectService.SemanticCheckoutPreflight preflightSemanticClearArea(Path repositoryDirectory, Level world) throws Exception
    {
        return preflightSemanticActiveSite(repositoryDirectory, world, "Semantic Clear Area preflight requires a semantic LVC project");
    }

    public static LvcProjectService.SemanticWorldClearResult clearSemanticArea(Path repositoryDirectory, Level world) throws Exception
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(world, "world");

        if (!LvcProjectService.isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Clear Area currently supports semantic LVC projects only");
        }

        Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(world);
        ActiveSemanticProject project = readActiveSemanticProject(repositoryDirectory);

        validateSemanticSiteReady(project.site());
        validateSemanticPlacementDimension(project.placement(), restoreWorld);

        return LvcWorldAccess.runOnSemanticCaptureWorld(restoreWorld, authoritativeWorld ->
        {
            if (!(authoritativeWorld instanceof ServerLevel serverWorld))
            {
                throw new LvcUserActionException(LvcUserActionException.Reason.NO_AUTHORITATIVE_WORLD,
                        "Semantic Clear Area requires server-authoritative world access");
            }

            List<Map.Entry<String, String>> chunkRefs = List.copyOf(project.site().fullHashes().entrySet());
            LvcSemanticRestoreEngine engine = new LvcSemanticRestoreEngine(
                    serverWorld,
                    project.site(),
                    LvcIntPosition.fromList(project.placement().origin()),
                    chunkRefs,
                    objectId -> readWorkingTreeChunk(repositoryDirectory, objectId),
                    () -> { },
                    projectPos -> { },
                    LvcSemanticRestoreEngine.Options.clear());

            while (!engine.processNextStep())
            {
            }

            return new LvcProjectService.SemanticWorldClearResult(project.site().regions().size(), engine.restoredBlocks());
        });
    }

    private static LvcChunk readWorkingTreeChunk(Path repositoryDirectory, String objectId) throws IOException
    {
        return LvcChunkCodec.decode(LvcChunkStore.readObject(repositoryDirectory, objectId));
    }

    public static LvcProjectService.ExportResult exportCommitToLitematic(Path repositoryDirectory, String commitId, Path outputDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(commitId, "commitId");
        Objects.requireNonNull(outputDirectory, "outputDirectory");

        SemanticCommitSnapshot snapshot = readSemanticCommitSnapshot(repositoryDirectory, commitId);
        String baseName = LvcLitematicExportFiles.commitBaseName(snapshot.manifest().name(), snapshot.commitId());
        LvcLitematicExportFiles.LitematicExportFile exportFile = LvcLitematicExportFiles.writeDeterministic(snapshot.schematic(), outputDirectory, baseName);

        return new LvcProjectService.ExportResult(exportFile.path(), exportFile.fileName());
    }

    public static LitematicaSchematic buildSemanticCommitSchematic(Path repositoryDirectory, String commitId) throws IOException
    {
        return readSemanticCommitSnapshot(repositoryDirectory, commitId).schematic();
    }

    public static LvcProjectService.UpdateAreasResult updateSemanticAreas(Path repositoryDirectory, LvcPlayerIdentity player,
                                                                   Level world, AreaSelection selection, String message) throws Exception
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(message, "message");

        if (!LvcProjectService.isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Update areas currently supports semantic LVC projects only");
        }

        Level captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(world);
        LvcCapturePublishCommitFlow.ActiveSite activeSite = LvcCapturePublishCommitFlow.readActiveSite(repositoryDirectory, captureWorld);
        List<LvcManifest.Region> updatedRegions = LvcProjectSelectionStorage.createRegionsFromSelection(selection, activeSite.origin(), activeSite.site().regions());
        LvcSemanticRepository.CommitResult result = LvcCapturePublishCommitFlow.updateActiveSiteAreas(
                repositoryDirectory,
                activeSite,
                captureWorld,
                updatedRegions,
                player,
                message
        );

        return new LvcProjectService.UpdateAreasResult(result.commit(), result.manifest().site(activeSite.siteId()).regions().size());
    }

    private static LvcProjectService.SemanticCheckoutPreflight preflightSemanticActiveSite(Path repositoryDirectory, Level world,
                                                                                          String nonSemanticError) throws Exception
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(world, "world");

        if (!LvcProjectService.isSemanticProject(repositoryDirectory))
        {
            throw new IOException(nonSemanticError);
        }

        Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(world);
        ActiveSemanticProject project = readActiveSemanticProject(repositoryDirectory);
        validateSemanticSiteReady(project.site());
        validateSemanticPlacementDimension(project.placement(), restoreWorld);

        return LvcWorldAccess.runOnSemanticCaptureWorld(restoreWorld, authoritativeWorld ->
        {
            LvcCaptureEngine.Result scan = LvcCaptureEngine.scanSite(project.site(), project.placement(), new LvcMinecraftWorldReader(authoritativeWorld));
            Map<String, String> expectedTrackedHashes = LvcSemanticRepository.computeTrackedHashesFromFullObjects(repositoryDirectory, project.site());
            LvcProjectService.SemanticScanResult result = LvcProjectService.SemanticScanResult.compare(project.siteId(), expectedTrackedHashes, scan);

            if (!result.clean() && result.unknownChunks() == 0)
            {
                result = result.withSamples(LvcSemanticScanMismatchSampler.sample(
                        repositoryDirectory,
                        project.site(),
                        project.placement(),
                        authoritativeWorld,
                        expectedTrackedHashes,
                        scan,
                        PREFLIGHT_MISMATCH_SAMPLE_LIMIT));
                syncPreflightMismatches(repositoryDirectory, project, authoritativeWorld, expectedTrackedHashes, scan, result);
            }

            return new LvcProjectService.SemanticCheckoutPreflight(result, project.site().regions().size());
        });
    }

    private static void syncPreflightMismatches(Path repositoryDirectory, ActiveSemanticProject project, Level authoritativeWorld,
                                                Map<String, String> expectedTrackedHashes,
                                                LvcCaptureEngine.Result scan,
                                                LvcProjectService.SemanticScanResult result) throws IOException
    {
        if (!(authoritativeWorld instanceof ServerLevel serverWorld))
        {
            return;
        }

        LongOpenHashSet positions = LvcSemanticScanMismatchSampler.mismatchedBlockStatePositions(
                repositoryDirectory,
                project.site(),
                project.placement(),
                authoritativeWorld,
                expectedTrackedHashes,
                scan);

        if (positions.isEmpty())
        {
            return;
        }

        LvcAuthoritativeClientSyncTask.schedule(serverWorld, positions);
        LvcDiagnostics.info("semantic active-site preflight queued client sync repo='{}' site={} positions={} changedChunks={} addedChunks={} removedChunks={}",
                repositoryDirectory, result.siteId(), positions.size(), result.changedChunks(), result.addedChunks(), result.removedChunks());
    }

    private static SemanticCommitSnapshot readSemanticCommitSnapshot(Path repositoryDirectory, String commitId) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        LvcLocalState localState = LvcSemanticRepository.readLocalState(repositoryDirectory);

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            RevCommit commit = LvcProjectGitOps.resolveCommit(repository, revWalk, commitId);
            LvcManifest manifest = LvcSemanticRepository.readCommitManifest(repository, commit);
            String siteId = localState.activeSite();
            LvcManifest.Site site = manifest.site(siteId);
            Map<String, byte[]> objectBytesById = readSemanticCommitChunkObjects(repository, commit, site);
            LitematicaSchematic schematic = LvcSemanticSchematicBuilder.buildSchematic(manifest, localState, siteId, objectId ->
            {
                byte[] bytes = objectBytesById.get(objectId);

                if (bytes == null)
                {
                    throw new IOException("Commit " + commit.getName() + " is missing LVC object: " + objectId);
                }

                return bytes;
            });

            return new SemanticCommitSnapshot(commit.getName(), manifest, localState, siteId, site, schematic);
        }
    }

    private static Map<String, byte[]> readSemanticCommitChunkObjects(Repository repository, RevCommit commit,
                                                                      LvcManifest.Site site) throws IOException
    {
        Map<String, byte[]> objects = new HashMap<>();

        for (String objectId : site.fullHashes().values())
        {
            String objectPath = LvcChunkStore.objectRepositoryPath(objectId);
            byte[] bytes = LvcProjectGitOps.readCommitFile(repository, commit, objectPath);

            if (bytes == null)
            {
                throw new IOException("Commit " + commit.getName() + " is missing LVC object: " + objectPath);
            }

            objects.put(objectId, bytes);
        }

        return Map.copyOf(objects);
    }

    private static ActiveSemanticProject readActiveSemanticProject(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (!LvcProjectService.isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Project editor currently supports semantic LVC projects only");
        }

        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        LvcLocalState localState = LvcSemanticRepository.readLocalState(repositoryDirectory);
        String siteId = localState.activeSite();
        LvcManifest.Site site = manifest.site(siteId);
        LvcLocalState.SitePlacement placement = localState.sites().get(siteId);

        if (placement == null)
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_LOCAL_PLACEMENT,
                    "Missing local placement for active LVC site: " + siteId);
        }

        return new ActiveSemanticProject(manifest, localState, siteId, site, placement);
    }

    private static void validateSemanticSiteReady(LvcManifest.Site site) throws IOException
    {
        if (site.regions().isEmpty())
        {
            throw new IOException("LVC project has no tracked sub-regions");
        }
    }

    private static void validateSemanticPlacementDimension(LvcLocalState.SitePlacement placement, Level world) throws IOException
    {
        String worldDimension = LvcMinecraftWorldReader.dimensionId(world);

        if (!worldDimension.equals(placement.dimension()))
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.WRONG_DIMENSION,
                    "Active LVC site is in " + placement.dimension() + " but current world is " + worldDimension);
        }
    }

    private record ActiveSemanticProject(LvcManifest manifest, LvcLocalState localState, String siteId,
                                         LvcManifest.Site site, LvcLocalState.SitePlacement placement)
    {
    }

    private record SemanticCommitSnapshot(String commitId, LvcManifest manifest, LvcLocalState localState,
                                          String siteId, LvcManifest.Site site,
                                          LitematicaSchematic schematic)
    {
    }

}
