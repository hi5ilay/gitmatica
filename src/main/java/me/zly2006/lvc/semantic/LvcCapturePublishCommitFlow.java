package me.zly2006.lvc.semantic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcPlayerIdentity;
import me.zly2006.lvc.LvcUserActionException;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.project.LvcProjectPositions;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.world.LvcWorldAccess;

final class LvcCapturePublishCommitFlow
{
    private LvcCapturePublishCommitFlow()
    {
    }

    static LvcSemanticRepository.CommitResult initProject(Path repositoryDirectory, String projectName,
                                                          LvcManifest.Site site,
                                                          LvcLocalState.SitePlacement placement,
                                                          Level captureWorld,
                                                          LvcPlayerIdentity player) throws Exception
    {
        LvcDiagnostics.debug("LvcCapturePublishCommitFlow: init start repo='{}' site='{}' regions={}",
                repositoryDirectory, site.id(), site.regions().size());
        LvcSemanticRepository.CommitResult result = LvcWorldAccess.runOnSemanticCaptureWorld(captureWorld, authoritativeWorld ->
                LvcSemanticRepository.initProject(
                        repositoryDirectory,
                        projectName,
                        site,
                        placement,
                        new LvcMinecraftWorldReader(authoritativeWorld),
                        player
                )
        );
        LvcDiagnostics.debug("LvcCapturePublishCommitFlow: init committed repo='{}' site='{}' commit='{}'",
                repositoryDirectory, site.id(), result.commit() == null ? "<none>" : result.commit().getName());
        return result;
    }

    static LvcSemanticRepository.CommitResult commitActiveSite(Path repositoryDirectory, ActiveSite activeSite,
                                                               Level captureWorld, LvcPlayerIdentity player,
                                                               String message) throws Exception
    {
        String commitMessage = normalizeCommitMessage(message);

        if (activeSite.site().regions().isEmpty())
        {
            throw new IOException("Add at least one LVC sub-region before saving a version");
        }

        LvcDiagnostics.debug("LvcCapturePublishCommitFlow: save-version start repo='{}' site='{}' regions={} messageLength={}",
                repositoryDirectory, activeSite.siteId(), activeSite.site().regions().size(), commitMessage.length());
        LvcSemanticRepository.CommitResult result = LvcWorldAccess.runOnSemanticCaptureWorld(captureWorld, authoritativeWorld ->
                LvcSemanticRepository.commitSite(
                        repositoryDirectory,
                        activeSite.manifest(),
                        activeSite.localState(),
                        activeSite.siteId(),
                        new LvcMinecraftWorldReader(authoritativeWorld),
                        player,
                        commitMessage
                )
        );
        LvcDiagnostics.debug("LvcCapturePublishCommitFlow: save-version complete repo='{}' site='{}' commit='{}'",
                repositoryDirectory, activeSite.siteId(), result.commit() == null ? "<none>" : result.commit().getName());
        return result;
    }

    static LvcSemanticRepository.CommitResult updateActiveSiteAreas(Path repositoryDirectory, ActiveSite activeSite,
                                                                    Level captureWorld,
                                                                    List<LvcManifest.Region> updatedRegions,
                                                                    LvcPlayerIdentity player, String message) throws Exception
    {
        String commitMessage = normalizeCommitMessage(message);
        LvcDiagnostics.debug("LvcCapturePublishCommitFlow: update-areas start repo='{}' site='{}' oldRegions={} newRegions={} messageLength={}",
                repositoryDirectory, activeSite.siteId(), activeSite.site().regions().size(), updatedRegions.size(), commitMessage.length());
        LvcSemanticRepository.CommitResult result = LvcWorldAccess.runOnSemanticCaptureWorld(captureWorld, authoritativeWorld ->
                LvcSemanticRepository.updateSiteAreas(
                        repositoryDirectory,
                        activeSite.manifest(),
                        activeSite.localState(),
                        activeSite.siteId(),
                        updatedRegions,
                        new LvcMinecraftWorldReader(authoritativeWorld),
                        player,
                        commitMessage
                )
        );
        LvcDiagnostics.debug("LvcCapturePublishCommitFlow: update-areas complete repo='{}' site='{}' commit='{}' regions={}",
                repositoryDirectory,
                activeSite.siteId(),
                result.commit() == null ? "<none>" : result.commit().getName(),
                result.manifest().site(activeSite.siteId()).regions().size());
        return result;
    }

    static ActiveSite readActiveSite(Path repositoryDirectory, Level captureWorld) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(captureWorld, "captureWorld");

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

        validatePlacementDimension(placement, captureWorld);
        return new ActiveSite(manifest, localState, siteId, site, placement);
    }

    private static void validatePlacementDimension(LvcLocalState.SitePlacement placement, Level captureWorld) throws IOException
    {
        String worldDimension = LvcMinecraftWorldReader.dimensionId(captureWorld);

        if (!worldDimension.equals(placement.dimension()))
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.WRONG_DIMENSION,
                    "Active LVC site is in " + placement.dimension() + " but current world is " + worldDimension);
        }
    }

    private static String normalizeCommitMessage(String message)
    {
        String trimmed = Objects.requireNonNull(message, "message").trim();

        if (trimmed.isEmpty())
        {
            throw new IllegalArgumentException("Commit message must not be blank");
        }

        return trimmed;
    }

    record ActiveSite(LvcManifest manifest, LvcLocalState localState, String siteId,
                      LvcManifest.Site site, LvcLocalState.SitePlacement placement)
    {
        BlockPos origin()
        {
            return LvcProjectPositions.blockPosFromList(this.placement.origin());
        }
    }
}
