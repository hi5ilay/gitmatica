package me.zly2006.lvc.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.LvcUserActionException;
import me.zly2006.lvc.capture.LvcMinecraftWorldReader;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcSemanticRepository;

final class LvcSemanticTaskContext
{
    private LvcSemanticTaskContext()
    {
    }

    static ActiveProject readActiveProject(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (!LvcProjectService.isSemanticProject(repositoryDirectory))
        {
            throw new IOException("This LVC operation requires a semantic LVC project");
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

        if (site.regions().isEmpty())
        {
            throw new IOException("LVC project has no tracked sub-regions");
        }

        return new ActiveProject(manifest, localState, siteId, site, placement);
    }

    static void validatePlacementDimension(LvcLocalState.SitePlacement placement, Level world) throws IOException
    {
        String worldDimension = LvcMinecraftWorldReader.dimensionId(world);

        if (!worldDimension.equals(placement.dimension()))
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.WRONG_DIMENSION,
                    "Active LVC site is in " + placement.dimension() + " but current world is " + worldDimension);
        }
    }

    record ActiveProject(LvcManifest manifest, LvcLocalState localState, String siteId,
                         LvcManifest.Site site, LvcLocalState.SitePlacement placement)
    {
    }
}
