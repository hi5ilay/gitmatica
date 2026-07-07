package me.zly2006.lvc.semantic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.storage.LvcSemanticRepository;

public final class LvcSemanticProjectEditor
{
    private LvcSemanticProjectEditor()
    {
    }

    public static LvcProjectService.ProjectEditorState readState(Path repositoryDirectory) throws IOException
    {
        ActiveSemanticProject project = readActiveProject(repositoryDirectory);
        return new LvcProjectService.ProjectEditorState(
                project.manifest().name(),
                project.siteId(),
                project.site().name(),
                project.site().dimension(),
                project.placement().dimension(),
                blockPosFromList(project.placement().origin()),
                project.placement().worldHint(),
                project.site().regions()
        );
    }

    public static void updateProjectName(Path repositoryDirectory, String projectName) throws IOException
    {
        Objects.requireNonNull(projectName, "projectName");
        validateProjectName(projectName);

        ActiveSemanticProject project = readActiveProject(repositoryDirectory);
        String normalizedName = projectName.trim();
        LvcManifest manifestWithSiteName = project.manifest().withSite(project.siteId(), project.site().withName(normalizedName));
        LvcManifest updatedManifest = new LvcManifest(
                manifestWithSiteName.format(),
                manifestWithSiteName.projectId(),
                normalizedName,
                manifestWithSiteName.content(),
                manifestWithSiteName.sites()
        ).validate();

        LvcSemanticRepository.writeVersionedProjectFiles(repositoryDirectory, updatedManifest);
    }

    public static void updateLocalOrigin(Path repositoryDirectory, BlockPos origin) throws IOException
    {
        Objects.requireNonNull(origin, "origin");
        ActiveSemanticProject project = readActiveProject(repositoryDirectory);
        Map<String, LvcLocalState.SitePlacement> placements = new TreeMap<>(project.localState().sites());
        LvcLocalState.SitePlacement updatedPlacement = new LvcLocalState.SitePlacement(
                project.placement().dimension(),
                blockPosToList(origin),
                project.placement().worldHint()
        );

        placements.put(project.siteId(), updatedPlacement);
        LvcLocalState updatedLocalState = LvcLocalState.create(project.localState().projectId(), project.localState().activeSite(), placements);
        LvcSemanticRepository.writeLocalState(repositoryDirectory, updatedLocalState);
    }

    public static void updateRegion(Path repositoryDirectory, String regionId, String name, BlockPos min, BlockPos size) throws IOException
    {
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(size, "size");

        if (name.isBlank())
        {
            throw new IllegalArgumentException("LVC region name must not be blank");
        }

        ActiveSemanticProject project = readActiveProject(repositoryDirectory);
        List<LvcManifest.Region> regions = new ArrayList<>(project.site().regions().size());
        boolean replaced = false;

        for (LvcManifest.Region region : project.site().regions())
        {
            if (region.id().equals(regionId))
            {
                regions.add(new LvcManifest.Region(region.id(), name.trim(), blockPosToList(min), blockPosToList(size)));
                replaced = true;
            }
            else
            {
                regions.add(region);
            }
        }

        if (!replaced)
        {
            throw new IOException("Unknown LVC region id: " + regionId);
        }

        writeRegions(repositoryDirectory, project, regions);
    }

    public static LvcManifest.Region createRegion(Path repositoryDirectory, String name, BlockPos min, BlockPos size) throws IOException
    {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(size, "size");

        if (name.isBlank())
        {
            throw new IllegalArgumentException("LVC region name must not be blank");
        }

        ActiveSemanticProject project = readActiveProject(repositoryDirectory);
        Set<String> usedRegionIds = new HashSet<>();

        for (LvcManifest.Region region : project.site().regions())
        {
            usedRegionIds.add(region.id());
        }

        LvcManifest.Region region = new LvcManifest.Region(
                uniqueRegionId(name, usedRegionIds),
                name.trim(),
                blockPosToList(min),
                blockPosToList(size)
        );
        List<LvcManifest.Region> regions = new ArrayList<>(project.site().regions());
        regions.add(region);
        writeRegions(repositoryDirectory, project, regions);
        return region;
    }

    public static void deleteRegion(Path repositoryDirectory, String regionId) throws IOException
    {
        Objects.requireNonNull(regionId, "regionId");

        ActiveSemanticProject project = readActiveProject(repositoryDirectory);

        if (project.site().regions().size() <= 1)
        {
            throw new IOException("LVC project must keep at least one sub-region");
        }

        List<LvcManifest.Region> regions = new ArrayList<>(project.site().regions().size());
        boolean removed = false;

        for (LvcManifest.Region region : project.site().regions())
        {
            if (region.id().equals(regionId))
            {
                removed = true;
            }
            else
            {
                regions.add(region);
            }
        }

        if (!removed)
        {
            throw new IOException("Unknown LVC region id: " + regionId);
        }

        writeRegions(repositoryDirectory, project, regions);
    }

    private static ActiveSemanticProject readActiveProject(Path repositoryDirectory) throws IOException
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
            throw new IOException("Missing local placement for active LVC site: " + siteId);
        }

        return new ActiveSemanticProject(manifest, localState, siteId, site, placement);
    }

    private static void writeRegions(Path repositoryDirectory, ActiveSemanticProject project,
                                     List<LvcManifest.Region> regions) throws IOException
    {
        LvcManifest.Site updatedSite = project.site().withRegions(regions);
        LvcSemanticRepository.writeVersionedProjectFiles(repositoryDirectory, project.manifest().withSite(project.siteId(), updatedSite));
    }

    private static void validateProjectName(String projectName)
    {
        if (projectName == null || projectName.isBlank())
        {
            throw new IllegalArgumentException("LVC project name must not be blank");
        }
    }

    private static List<Integer> blockPosToList(BlockPos pos)
    {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos blockPosFromList(List<Integer> values)
    {
        if (values == null || values.size() != 3)
        {
            throw new IllegalArgumentException("LVC position must contain three coordinates");
        }

        return new BlockPos(values.get(0), values.get(1), values.get(2));
    }

    private static String uniqueRegionId(String name, Set<String> usedIds)
    {
        String base = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");

        if (base.isBlank())
        {
            base = "region";
        }

        String candidate = base;
        int index = 2;

        while (!usedIds.add(candidate))
        {
            candidate = base + "_" + index;
            index++;
        }

        return candidate;
    }

    private record ActiveSemanticProject(LvcManifest manifest, LvcLocalState localState, String siteId,
                                         LvcManifest.Site site, LvcLocalState.SitePlacement placement)
    {
    }
}
