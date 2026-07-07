package me.zly2006.lvc.overlay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.storage.LvcRepository;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.semantic.LvcSemanticSchematicBuilder;
import me.zly2006.lvc.project.LvcProjectPositions;
import me.zly2006.lvc.world.LvcWorldAccess;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.util.OverlayType;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.data.Color4f;

public final class LvcTrackingOverlayService
{
    private static final String TRACKING_OVERLAY_CACHE_DIRECTORY = "lvc-cache";
    private static final String TRACKING_OVERLAY_CACHE_FILE = "tracking-overlay.litematic";
    private static final String TRACKING_OVERLAY_DESCRIPTOR_FILE = "tracking-overlay.json";
    private static final Color4f CHANGE_OVERLAY_ADDED = Color4f.fromColor(0x33CC33, 0.30f);
    private static final Color4f CHANGE_OVERLAY_REMOVED = Color4f.fromColor(0xFF3333, 0.30f);
    private static final Color4f CHANGE_OVERLAY_STATE = Color4f.fromColor(0xFAF000, 0.30f);
    private static final Color4f CHANGE_OVERLAY_WRONG_BLOCK = Color4f.fromColor(0xFF9010, 0.30f);
    private static final Color4f CHANGE_MARKER_ADDED = Color4f.fromColor(0x33CC33, 1.0f);
    private static final Color4f CHANGE_MARKER_REMOVED = Color4f.fromColor(0xFF3333, 1.0f);
    private static final Color4f CHANGE_MARKER_STATE = Color4f.fromColor(0xFAF000, 1.0f);
    private static final Color4f CHANGE_MARKER_WRONG_BLOCK = Color4f.fromColor(0xFF9010, 1.0f);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Path, TrackingOverlayEntry> ACTIVE_TRACKING_OVERLAYS = new HashMap<>();

    private LvcTrackingOverlayService()
    {
    }

    public static LvcProjectService.TrackingOverlay loadTrackingOverlay(Path repositoryDirectory, String projectName,
                                                                 @Nullable ClientLevel clientLevel,
                                                                 @Nullable ICompletionListener completionListener) throws IOException
    {
        return loadTrackingOverlay(repositoryDirectory, projectName, clientLevel, completionListener, true);
    }

    public static LvcProjectService.TrackingOverlay loadTrackingOverlay(Path repositoryDirectory, String projectName,
                                                                 @Nullable ClientLevel clientLevel,
                                                                 @Nullable ICompletionListener completionListener,
                                                                 boolean startVerifier) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(projectName, "projectName");
        removeTrackingOverlay(repositoryDirectory);

        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        LvcLocalState localState = LvcSemanticRepository.readLocalState(repositoryDirectory);
        String siteId = localState.activeSite();
        LvcLocalState.SitePlacement placementState = localState.sites().get(siteId);

        if (placementState == null)
        {
            throw new IOException("Missing local placement for active LVC site: " + siteId);
        }

        String overlayName = trackingOverlayDisplayName(repositoryDirectory, manifest.name());
        ServerLevel lootPreviewWorld = resolveLootPreviewWorld(clientLevel, placementState);
        LitematicaSchematic schematic = writeAndReloadSemanticTrackingSchematic(repositoryDirectory, manifest, localState, siteId, overlayName, lootPreviewWorld);
        BlockPos origin = LvcProjectPositions.blockPosFromList(placementState.origin());
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, overlayName, true, true);
        ClientLevel verifierWorld = clientLevel;

        if (clientLevel != null && !LvcMinecraftWorldReader.dimensionId(clientLevel).equals(placementState.dimension()))
        {
            verifierWorld = null;
        }

        LvcProjectService.TrackingOverlay overlay = addTrackingOverlay(repositoryDirectory, placement, verifierWorld, completionListener, startVerifier);
        trackOverlay(repositoryDirectory, overlay, currentOverlayDescriptor(repositoryDirectory, siteId, placementState, overlayName));
        return overlay;
    }

    public static void removeTrackingOverlay(Path repositoryDirectory)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.remove(trackingOverlayKey(repositoryDirectory));
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay != null)
        {
            LitematicaSchematic schematic = overlay.placement().getSchematic();
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(overlay.placement(), false);
            SchematicHolder.getInstance().removeSchematic(schematic);
        }

        removeSemanticTrackingCacheOverlay(repositoryDirectory);
    }

    @Nullable
    public static LvcProjectService.TrackingOverlay getReusableSemanticTrackingOverlay(Path repositoryDirectory,
                                                                                       @Nullable ClientLevel clientLevel,
                                                                                       @Nullable ICompletionListener completionListener) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (hasTrackedGitChanges(repositoryDirectory))
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: refused reusable overlay for dirty repository '{}'", repositoryDirectory);
            return null;
        }

        Path key = trackingOverlayKey(repositoryDirectory);
        Path cacheFile = semanticTrackingCacheFile(repositoryDirectory);
        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        LvcLocalState localState = LvcSemanticRepository.readLocalState(repositoryDirectory);
        LvcLocalState.SitePlacement placementState = localState.sites().get(localState.activeSite());

        if (placementState == null || !isSemanticTrackingCacheCurrent(repositoryDirectory))
        {
            return null;
        }

        String expectedName = trackingOverlayDisplayName(repositoryDirectory, manifest.name());
        BlockPos expectedOrigin = LvcProjectPositions.blockPosFromList(placementState.origin());
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(key);
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay != null)
        {
            if (isMatchingTrackingPlacement(overlay.placement(), cacheFile, expectedName, expectedOrigin))
            {
                attachCompletionListener(overlay, completionListener);
                focusTrackingOverlayInternal(repositoryDirectory, overlay.placement());
                LvcDiagnostics.debug("LvcTrackingOverlayService: reused active tracking overlay for '{}'", repositoryDirectory);
                return overlay;
            }

            ACTIVE_TRACKING_OVERLAYS.remove(key);
        }

        SchematicPlacement restoredPlacement = findMatchingTrackingPlacement(cacheFile, expectedName, expectedOrigin);

        if (restoredPlacement == null)
        {
            return null;
        }

        LvcProjectService.TrackingOverlay restoredOverlay = wrapExistingPlacement(restoredPlacement);
        trackOverlay(repositoryDirectory, restoredOverlay, currentOverlayDescriptor(repositoryDirectory, localState.activeSite(), placementState, expectedName));
        attachCompletionListener(restoredOverlay, completionListener);
        focusTrackingOverlayInternal(repositoryDirectory, restoredPlacement);
        LvcDiagnostics.debug("LvcTrackingOverlayService: attached restart-persisted tracking overlay for '{}'", repositoryDirectory);
        return restoredOverlay;
    }

    @Nullable
    public static LvcProjectService.TrackingOverlay refreshVerifierIfCurrent(Path repositoryDirectory,
                                                                             @Nullable ClientLevel clientLevel,
                                                                             @Nullable ICompletionListener completionListener) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        OverlayTarget target = currentOverlayTarget(repositoryDirectory);

        if (target == null)
        {
            return null;
        }

        TrackingOverlayEntry entry = activeEntryIfCurrent(repositoryDirectory, target);

        if (entry == null)
        {
            entry = persistedEntryIfCurrent(repositoryDirectory, target);
        }

        if (entry == null)
        {
            return null;
        }

        LvcProjectService.TrackingOverlay overlay = restartVerifier(repositoryDirectory, entry.overlay(), target, clientLevel, completionListener);
        trackOverlay(repositoryDirectory, overlay, target.descriptor());
        LvcDiagnostics.debug("LvcTrackingOverlayService: refreshed verifier without rebuilding overlay repo='{}' commit='{}'",
                repositoryDirectory, target.commitId());
        return overlay;
    }

    public static String trackingOverlayDisplayName(Path repositoryDirectory, String projectName)
    {
        try
        {
            ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

            if (head != null)
            {
                String commitId = head.getName();
                return trackingOverlayDisplayNameForCommit(projectName, commitId);
            }
        }
        catch (Exception ignored)
        {
        }

        return projectName;
    }

    public static LitematicaSchematic writeAndReloadSemanticTrackingSchematic(Path repositoryDirectory, LvcManifest manifest,
                                                                       LvcLocalState localState, String siteId,
                                                                       String overlayName) throws IOException
    {
        return writeAndReloadSemanticTrackingSchematic(repositoryDirectory, manifest, localState, siteId, overlayName, null);
    }

    public static LitematicaSchematic writeAndReloadSemanticTrackingSchematic(Path repositoryDirectory, LvcManifest manifest,
                                                                       LvcLocalState localState, String siteId,
                                                                       String overlayName,
                                                                       @Nullable ServerLevel lootPreviewWorld) throws IOException
    {
        Path cacheFile = writeSemanticTrackingCacheFile(repositoryDirectory, manifest, localState, siteId, overlayName, lootPreviewWorld);
        LitematicaSchematic reloaded = reloadLitematicaSchematic(cacheFile);

        if (reloaded == null)
        {
            throw new IOException("Failed to reload LVC tracking schematic cache: " + cacheFile);
        }

        reloaded.getMetadata().setName(overlayName);
        return reloaded;
    }

    public static boolean isSemanticTrackingCacheCurrent(Path repositoryDirectory) throws IOException
    {
        Path cacheFile = semanticTrackingCacheFile(repositoryDirectory);

        if (!Files.isRegularFile(cacheFile))
        {
            return false;
        }

        long cacheTime = Files.getLastModifiedTime(cacheFile).toMillis();
        long manifestTime = Files.getLastModifiedTime(repositoryDirectory.resolve(LvcSemanticRepository.MANIFEST)).toMillis();
        long localTime = Files.getLastModifiedTime(repositoryDirectory.resolve(LvcSemanticRepository.LOCAL_JSON)).toMillis();
        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        long hashIndexTime = LvcSemanticRepository.hashIndexesLastModified(repositoryDirectory, manifest);
        return cacheTime >= manifestTime && cacheTime >= localTime && cacheTime >= hashIndexTime;
    }

    public static Path writeSemanticTrackingCacheFile(Path repositoryDirectory, LitematicaSchematic schematic) throws IOException
    {
        Path cacheFile = semanticTrackingCacheFile(repositoryDirectory);
        Files.createDirectories(cacheFile.getParent());

        if (!schematic.writeToFile(cacheFile.getParent(), cacheFile.getFileName().toString(), true))
        {
            throw new IOException("Failed to write LVC tracking schematic cache: " + cacheFile);
        }

        return cacheFile;
    }

    public static Path writeSemanticTrackingCacheFile(Path repositoryDirectory, LvcManifest manifest,
                                               LvcLocalState localState, String siteId,
                                               String overlayName) throws IOException
    {
        return writeSemanticTrackingCacheFile(repositoryDirectory, manifest, localState, siteId, overlayName, null);
    }

    public static Path writeSemanticTrackingCacheFile(Path repositoryDirectory, LvcManifest manifest,
                                               LvcLocalState localState, String siteId,
                                               String overlayName,
                                               @Nullable ServerLevel lootPreviewWorld) throws IOException
    {
        LitematicaSchematic schematic = LvcSemanticSchematicBuilder.buildWorkingTreeSchematic(repositoryDirectory, manifest, localState, siteId, lootPreviewWorld);
        schematic.getMetadata().setName(overlayName);
        return writeSemanticTrackingCacheFile(repositoryDirectory, schematic);
    }

    @Nullable
    public static ServerLevel resolveLootPreviewWorld(@Nullable ClientLevel clientLevel,
                                                      LvcLocalState.SitePlacement placementState)
    {
        Objects.requireNonNull(placementState, "placementState");

        if (clientLevel == null || !LvcMinecraftWorldReader.dimensionId(clientLevel).equals(placementState.dimension()))
        {
            return null;
        }

        Level captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(clientLevel);
        return captureWorld instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    public static LitematicaSchematic reloadLitematicaSchematic(Path structureFile)
    {
        SchematicHolder holder = SchematicHolder.getInstance();

        for (LitematicaSchematic schematic : new ArrayList<>(holder.getAllSchematics()))
        {
            if (structureFile.equals(schematic.getFile()))
            {
                holder.removeSchematic(schematic);
            }
        }

        return holder.getOrLoad(structureFile);
    }

    public static LvcProjectService.TrackingOverlay addSemanticTrackingOverlay(Path repositoryDirectory,
                                                                               LitematicaSchematic schematic,
                                                                               LvcLocalState.SitePlacement placementState,
                                                                               String overlayName,
                                                                               @Nullable ClientLevel clientLevel,
                                                                               @Nullable ICompletionListener completionListener,
                                                                               boolean startVerifier) throws IOException
    {
        BlockPos origin = LvcProjectPositions.blockPosFromList(placementState.origin());
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, overlayName, true, true);
        ClientLevel verifierWorld = clientLevel;

        if (clientLevel != null && !LvcMinecraftWorldReader.dimensionId(clientLevel).equals(placementState.dimension()))
        {
            verifierWorld = null;
        }

        LvcProjectService.TrackingOverlay overlay = addTrackingOverlay(repositoryDirectory, placement, verifierWorld, completionListener, startVerifier);
        trackOverlay(repositoryDirectory, overlay, currentOverlayDescriptor(repositoryDirectory, currentSiteId(repositoryDirectory), placementState, overlayName));
        return overlay;
    }

    public static Path semanticTrackingCachePath(Path repositoryDirectory)
    {
        return semanticTrackingCacheFile(repositoryDirectory);
    }

    public static boolean isSemanticTrackingCachePath(@Nullable Path path)
    {
        if (path == null)
        {
            return false;
        }

        Path normalized = path.toAbsolutePath().normalize();
        Path fileName = normalized.getFileName();
        Path cacheDirectory = normalized.getParent();
        Path gitDirectory = cacheDirectory != null ? cacheDirectory.getParent() : null;
        Path cacheDirectoryName = cacheDirectory != null ? cacheDirectory.getFileName() : null;
        Path gitDirectoryName = gitDirectory != null ? gitDirectory.getFileName() : null;

        return fileName != null &&
                TRACKING_OVERLAY_CACHE_FILE.equals(fileName.toString()) &&
                cacheDirectoryName != null &&
                TRACKING_OVERLAY_CACHE_DIRECTORY.equals(cacheDirectoryName.toString()) &&
                gitDirectoryName != null &&
                ".git".equals(gitDirectoryName.toString());
    }

    public static boolean isSemanticTrackingPlacement(@Nullable SchematicPlacement placement)
    {
        return placement != null && isSemanticTrackingCachePath(placement.getSchematicFile());
    }

    @Nullable
    public static Color4f semanticTrackingOverlayColor(OverlayType overlayType)
    {
        // LVC names changes from the current world relative to the tracked schematic baseline.
        return switch (overlayType)
        {
            case EXTRA -> CHANGE_OVERLAY_ADDED;
            case MISSING -> CHANGE_OVERLAY_REMOVED;
            case WRONG_STATE -> CHANGE_OVERLAY_STATE;
            case WRONG_BLOCK, DIFF_BLOCK -> CHANGE_OVERLAY_WRONG_BLOCK;
            default -> null;
        };
    }

    public static Color4f semanticTrackingMismatchColor(MismatchType mismatchType)
    {
        return switch (mismatchType)
        {
            case EXTRA -> CHANGE_MARKER_ADDED;
            case MISSING -> CHANGE_MARKER_REMOVED;
            case WRONG_STATE, WRONG_INVENTORIES -> CHANGE_MARKER_STATE;
            case WRONG_BLOCK, DIFF_BLOCK -> CHANGE_MARKER_WRONG_BLOCK;
            default -> mismatchType.getColor();
        };
    }

    @Nullable
    public static Path semanticTrackingRepositoryDirectory(@Nullable Path path)
    {
        if (!isSemanticTrackingCachePath(path))
        {
            return null;
        }

        Path cacheDirectory = path.toAbsolutePath().normalize().getParent();
        Path gitDirectory = cacheDirectory != null ? cacheDirectory.getParent() : null;
        Path repositoryDirectory = gitDirectory != null ? gitDirectory.getParent() : null;
        return repositoryDirectory != null ? repositoryDirectory.normalize() : null;
    }

    private static boolean hasTrackedGitChanges(Path repositoryDirectory)
    {
        try
        {
            return LvcProjectService.hasUncommittedChanges(repositoryDirectory);
        }
        catch (Exception e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: treating overlay cache as non-reusable because dirty check failed repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
            return true;
        }
    }

    public static boolean focusTrackingOverlay(Path repositoryDirectory)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Path key = trackingOverlayKey(repositoryDirectory);
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(key);
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay == null)
        {
            return false;
        }

        if (!DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(overlay.placement()))
        {
            ACTIVE_TRACKING_OVERLAYS.remove(key);
            return false;
        }

        focusTrackingOverlayInternal(repositoryDirectory, overlay.placement());
        return true;
    }

    public static boolean updateTrackingOverlayOrigin(Path repositoryDirectory, BlockPos origin)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(origin, "origin");

        Path key = trackingOverlayKey(repositoryDirectory);
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(key);
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;
        SchematicPlacement placement = overlay != null ? overlay.placement() : null;

        if (placement == null || !DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(placement))
        {
            ACTIVE_TRACKING_OVERLAYS.remove(key);
            placement = findTrackingPlacementByCacheFile(semanticTrackingCacheFile(repositoryDirectory));

            if (placement == null)
            {
                LvcDiagnostics.debug("LvcTrackingOverlayService: no active tracking overlay to move repo='{}' origin='{}'",
                        repositoryDirectory, origin);
                return false;
            }

            overlay = wrapExistingPlacement(placement);
            trackOverlay(repositoryDirectory, overlay, descriptorForExistingPlacement(repositoryDirectory, placement));
        }

        BlockPos oldOrigin = placement.getOrigin();

        if (!oldOrigin.equals(origin))
        {
            placement.setOrigin(origin, InfoUtils.INFO_MESSAGE_CONSUMER);
        }

        boolean updated = placement.getOrigin().equals(origin);

        if (updated && overlay != null)
        {
            trackOverlay(repositoryDirectory, overlay, descriptorForExistingPlacement(repositoryDirectory, placement));
        }

        LvcDiagnostics.debug("LvcTrackingOverlayService: moved tracking overlay origin repo='{}' placement='{}' old='{}' new='{}' updated={}",
                repositoryDirectory, placement.getName(), oldOrigin, origin, updated);
        return updated;
    }

    public static boolean focusTrackingOverlay(Path repositoryDirectory, SchematicPlacement placement)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(placement, "placement");

        if (!DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(placement))
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: refused focus for unregistered tracking overlay repo='{}' placement='{}'",
                    repositoryDirectory, placement.getName());
            return false;
        }

        Path expectedRepository = semanticTrackingRepositoryDirectory(placement.getSchematicFile());

        if (expectedRepository == null || !trackingOverlayKey(repositoryDirectory).equals(trackingOverlayKey(expectedRepository)))
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: refused focus for mismatched tracking overlay repo='{}' placement='{}' expectedRepo='{}'",
                    repositoryDirectory, placement.getName(), expectedRepository);
            return false;
        }

        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(trackingOverlayKey(repositoryDirectory));
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay == null || overlay.placement() != placement)
        {
            trackOverlay(repositoryDirectory, wrapExistingPlacement(placement), descriptorForExistingPlacement(repositoryDirectory, placement));
            LvcDiagnostics.debug("LvcTrackingOverlayService: attached placement-list tracking overlay repo='{}' placement='{}'",
                    repositoryDirectory, placement.getName());
        }

        focusTrackingOverlayInternal(repositoryDirectory, placement);
        return true;
    }

    private static void trackOverlay(Path repositoryDirectory, LvcProjectService.TrackingOverlay overlay,
                                     @Nullable OverlayDescriptor descriptor)
    {
        ACTIVE_TRACKING_OVERLAYS.put(trackingOverlayKey(repositoryDirectory), new TrackingOverlayEntry(overlay, descriptor));

        if (descriptor != null)
        {
            writeOverlayDescriptor(repositoryDirectory, descriptor);
        }
    }

    private static Path trackingOverlayKey(Path repositoryDirectory)
    {
        return repositoryDirectory.toAbsolutePath().normalize();
    }

    private static void removeSemanticTrackingCacheOverlay(Path repositoryDirectory)
    {
        Path cacheFile = semanticTrackingCacheFile(repositoryDirectory);

        for (SchematicPlacement placement : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            if (pathsEqual(cacheFile, placement.getSchematicFile()))
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement, false);
            }
        }

        SchematicHolder holder = SchematicHolder.getInstance();

        for (LitematicaSchematic schematic : new ArrayList<>(holder.getAllSchematics()))
        {
            if (pathsEqual(cacheFile, schematic.getFile()))
            {
                holder.removeSchematic(schematic);
            }
        }
    }

    private static boolean pathsEqual(Path expected, @Nullable Path actual)
    {
        return actual != null && expected.equals(actual.toAbsolutePath().normalize());
    }

    @Nullable
    private static SchematicPlacement findTrackingPlacementByCacheFile(Path cacheFile)
    {
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (pathsEqual(cacheFile, placement.getSchematicFile()))
            {
                return placement;
            }
        }

        return null;
    }

    @Nullable
    private static SchematicPlacement findMatchingTrackingPlacement(Path cacheFile, String expectedName, BlockPos expectedOrigin)
    {
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (isMatchingTrackingPlacement(placement, cacheFile, expectedName, expectedOrigin))
            {
                return placement;
            }
        }

        return null;
    }

    private static boolean isMatchingTrackingPlacement(SchematicPlacement placement, Path cacheFile,
                                                       String expectedName, BlockPos expectedOrigin)
    {
        return expectedName.equals(placement.getName()) &&
                expectedOrigin.equals(placement.getOrigin()) &&
                pathsEqual(cacheFile, placement.getSchematicFile()) &&
                DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(placement);
    }

    private static LvcProjectService.TrackingOverlay wrapExistingPlacement(SchematicPlacement placement)
    {
        boolean hadVerifier = placement.hasVerifier();
        SchematicVerifier verifier = placement.getSchematicVerifier();
        boolean verifierStarted = hadVerifier && (verifier.isActive() || verifier.isPaused() || verifier.isFinished());
        return new LvcProjectService.TrackingOverlay(placement, verifier, verifierStarted);
    }

    private static void attachCompletionListener(LvcProjectService.TrackingOverlay overlay,
                                                 @Nullable ICompletionListener completionListener)
    {
        if (completionListener != null && overlay.verifierStarted() && !overlay.verifier().isFinished())
        {
            overlay.verifier().setCompletionListener(completionListener);
        }
    }

    private static void focusTrackingOverlayInternal(Path repositoryDirectory, SchematicPlacement placement)
    {
        if (DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement() != placement)
        {
            DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
            LvcDiagnostics.debug("LvcTrackingOverlayService: focused tracking overlay repo='{}' placement='{}'",
                    repositoryDirectory, placement.getName());
        }
    }

    @Nullable
    private static TrackingOverlayEntry activeEntryIfCurrent(Path repositoryDirectory, OverlayTarget target) throws IOException
    {
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(trackingOverlayKey(repositoryDirectory));

        if (entry == null)
        {
            return null;
        }

        if (entryMatchesTarget(entry, target))
        {
            return entry;
        }

        ACTIVE_TRACKING_OVERLAYS.remove(trackingOverlayKey(repositoryDirectory));
        return null;
    }

    @Nullable
    private static TrackingOverlayEntry persistedEntryIfCurrent(Path repositoryDirectory, OverlayTarget target) throws IOException
    {
        if (!Files.isRegularFile(target.cacheFile()))
        {
            return null;
        }

        SchematicPlacement placement = findMatchingTrackingPlacement(target.cacheFile(), target.overlayName(), target.origin());

        if (placement == null)
        {
            return null;
        }

        OverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);

        if (descriptor != null && !descriptor.matches(target))
        {
            return null;
        }

        if (descriptor == null && !isSemanticTrackingCacheCurrent(repositoryDirectory))
        {
            return null;
        }

        return new TrackingOverlayEntry(wrapExistingPlacement(placement), descriptor != null ? descriptor : target.descriptor());
    }

    private static boolean entryMatchesTarget(TrackingOverlayEntry entry, OverlayTarget target) throws IOException
    {
        if (!isMatchingTrackingPlacement(entry.overlay().placement(), target.cacheFile(), target.overlayName(), target.origin()))
        {
            return false;
        }

        if (entry.descriptor() != null)
        {
            return entry.descriptor().matches(target);
        }

        return isSemanticTrackingCacheCurrent(target.repositoryDirectory());
    }

    private static LvcProjectService.TrackingOverlay restartVerifier(Path repositoryDirectory,
                                                                     LvcProjectService.TrackingOverlay overlay,
                                                                     OverlayTarget target,
                                                                     @Nullable ClientLevel clientLevel,
                                                                     @Nullable ICompletionListener completionListener)
    {
        focusTrackingOverlayInternal(repositoryDirectory, overlay.placement());

        SchematicVerifier verifier = overlay.placement().getSchematicVerifier();
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        boolean verifierStarted = false;

        if (clientLevel != null && schematicWorld != null && LvcMinecraftWorldReader.dimensionId(clientLevel).equals(target.dimension()))
        {
            verifier.startVerification(clientLevel, schematicWorld, overlay.placement(), completionListener);
            verifierStarted = true;
        }
        else
        {
            verifier.reset();
            LvcDiagnostics.debug("LvcTrackingOverlayService: reused tracking overlay '{}' without starting verifier", overlay.placement().getName());
        }

        return new LvcProjectService.TrackingOverlay(overlay.placement(), verifier, verifierStarted);
    }

    @Nullable
    private static OverlayTarget currentOverlayTarget(Path repositoryDirectory) throws IOException
    {
        ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

        if (head == null)
        {
            return null;
        }

        LvcLocalState localState = LvcSemanticRepository.readLocalState(repositoryDirectory);
        String siteId = localState.activeSite();
        LvcLocalState.SitePlacement placementState = localState.sites().get(siteId);

        if (placementState == null)
        {
            throw new IOException("Missing local placement for active LVC site: " + siteId);
        }

        String commitId = head.getName();
        LvcManifest manifest = readCommitManifest(repositoryDirectory, commitId);
        String overlayName = trackingOverlayDisplayNameForCommit(manifest.name(), commitId);
        OverlayDescriptor descriptor = currentOverlayDescriptor(repositoryDirectory, siteId, placementState, overlayName);
        BlockPos origin = LvcProjectPositions.blockPosFromList(placementState.origin());
        return new OverlayTarget(repositoryDirectory.toAbsolutePath().normalize(), commitId, siteId, placementState.dimension(),
                origin, semanticTrackingCacheFile(repositoryDirectory), overlayName, descriptor);
    }

    private static LvcManifest readCommitManifest(Path repositoryDirectory, String commitId) throws IOException
    {
        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            RevCommit commit = LvcProjectGitOps.resolveCommit(repository, revWalk, commitId);
            return LvcSemanticRepository.readCommitManifest(repository, commit);
        }
    }

    private static String trackingOverlayDisplayNameForCommit(String projectName, String commitId)
    {
        return projectName + " @ " + commitId.substring(0, Math.min(8, commitId.length()));
    }

    private static String currentSiteId(Path repositoryDirectory) throws IOException
    {
        return LvcSemanticRepository.readLocalState(repositoryDirectory).activeSite();
    }

    @Nullable
    private static OverlayDescriptor currentOverlayDescriptor(Path repositoryDirectory, String siteId,
                                                             LvcLocalState.SitePlacement placementState,
                                                             String overlayName) throws IOException
    {
        ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

        if (head == null)
        {
            return null;
        }

        BlockPos origin = LvcProjectPositions.blockPosFromList(placementState.origin());
        return new OverlayDescriptor(
                head.getName(),
                siteId,
                placementState.dimension(),
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                semanticTrackingCacheFile(repositoryDirectory).toString(),
                overlayName
        );
    }

    @Nullable
    private static OverlayDescriptor descriptorForExistingPlacement(Path repositoryDirectory, SchematicPlacement placement)
    {
        try
        {
            OverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);

            if (descriptor != null && descriptor.matchesPlacement(repositoryDirectory, placement))
            {
                return descriptor;
            }

            OverlayTarget target = currentOverlayTarget(repositoryDirectory);

            if (target != null && isMatchingTrackingPlacement(placement, target.cacheFile(), target.overlayName(), target.origin()))
            {
                return target.descriptor();
            }
        }
        catch (Exception e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: failed to describe existing overlay repo='{}' placement='{}' error='{}'",
                    repositoryDirectory, placement.getName(), e.getMessage());
        }

        return null;
    }

    private static void writeOverlayDescriptor(Path repositoryDirectory, OverlayDescriptor descriptor)
    {
        Path path = semanticTrackingDescriptorFile(repositoryDirectory);

        try
        {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(descriptor), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: failed to write overlay descriptor repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
        }
    }

    @Nullable
    private static OverlayDescriptor readOverlayDescriptor(Path repositoryDirectory)
    {
        Path path = semanticTrackingDescriptorFile(repositoryDirectory);

        if (!Files.isRegularFile(path))
        {
            return null;
        }

        try
        {
            return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), OverlayDescriptor.class);
        }
        catch (IOException | JsonParseException | IllegalArgumentException | NullPointerException e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: ignored invalid overlay descriptor repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
            return null;
        }
    }

    private static Path semanticTrackingCacheFile(Path repositoryDirectory)
    {
        return repositoryDirectory.toAbsolutePath().normalize()
                .resolve(".git")
                .resolve(TRACKING_OVERLAY_CACHE_DIRECTORY)
                .resolve(TRACKING_OVERLAY_CACHE_FILE)
                .normalize();
    }

    private static Path semanticTrackingDescriptorFile(Path repositoryDirectory)
    {
        return repositoryDirectory.toAbsolutePath().normalize()
                .resolve(".git")
                .resolve(TRACKING_OVERLAY_CACHE_DIRECTORY)
                .resolve(TRACKING_OVERLAY_DESCRIPTOR_FILE)
                .normalize();
    }

    private static LvcProjectService.TrackingOverlay addTrackingOverlay(Path repositoryDirectory,
                                                                        SchematicPlacement placement,
                                                                        @Nullable ClientLevel clientLevel,
                                                                        @Nullable ICompletionListener completionListener,
                                                                        boolean startVerifier)
    {
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        focusTrackingOverlayInternal(repositoryDirectory, placement);

        SchematicVerifier verifier = placement.getSchematicVerifier();
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        boolean verifierStarted = false;

        if (startVerifier && clientLevel != null && schematicWorld != null)
        {
            verifier.startVerification(clientLevel, schematicWorld, placement, completionListener);
            verifierStarted = true;
        }

        LvcDiagnostics.debug("LvcTrackingOverlayService: added tracking overlay repo='{}' placement='{}' startVerifier={} verifierStarted={} colorPalette='added=green removed=red changed_state=yellow wrong_block=orange'",
                repositoryDirectory, placement.getName(), startVerifier, verifierStarted);

        return new LvcProjectService.TrackingOverlay(placement, verifier, verifierStarted);
    }

    private record TrackingOverlayEntry(LvcProjectService.TrackingOverlay overlay, @Nullable OverlayDescriptor descriptor)
    {
    }

    private record OverlayTarget(Path repositoryDirectory, String commitId, String siteId, String dimension,
                                 BlockPos origin, Path cacheFile, String overlayName, OverlayDescriptor descriptor)
    {
    }

    private record OverlayDescriptor(String commitId, String siteId, String dimension,
                                     int originX, int originY, int originZ,
                                     String cacheFile, String overlayName)
    {
        private boolean matches(OverlayTarget target)
        {
            return this.commitId.equals(target.commitId()) &&
                    this.siteId.equals(target.siteId()) &&
                    this.dimension.equals(target.dimension()) &&
                    this.originX == target.origin().getX() &&
                    this.originY == target.origin().getY() &&
                    this.originZ == target.origin().getZ() &&
                    this.cacheFile.equals(target.cacheFile().toString()) &&
                    this.overlayName.equals(target.overlayName());
        }

        private boolean matchesPlacement(Path repositoryDirectory, SchematicPlacement placement)
        {
            return this.cacheFile.equals(semanticTrackingCacheFile(repositoryDirectory).toString()) &&
                    this.overlayName.equals(placement.getName()) &&
                    new BlockPos(this.originX, this.originY, this.originZ).equals(placement.getOrigin()) &&
                    pathsEqual(semanticTrackingCacheFile(repositoryDirectory), placement.getSchematicFile());
        }
    }
}
