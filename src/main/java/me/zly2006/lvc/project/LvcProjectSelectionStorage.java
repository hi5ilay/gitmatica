package me.zly2006.lvc.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;

import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;

public final class LvcProjectSelectionStorage
{
    private LvcProjectSelectionStorage()
    {
    }

    public static LvcManifest.Site createMainSiteFromSelection(String siteName, String dimensionId, AreaSelection selection)
    {
        Objects.requireNonNull(selection, "selection");
        validateProjectName(siteName);

        BlockPos origin = selection.getEffectiveOrigin();
        List<LvcManifest.Region> regions = createRegionsFromSelection(selection, origin, List.of());

        return new LvcManifest.Site("main", siteName, dimensionId, regions, Map.of());
    }

    public static List<LvcManifest.Region> createRegionsFromSelection(AreaSelection selection, BlockPos origin, List<LvcManifest.Region> existingRegions)
    {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(existingRegions, "existingRegions");

        List<Box> boxes = new ArrayList<>(getValidBoxes(selection));
        boxes.sort(Comparator.comparing(box -> box.getName() == null ? "" : box.getName()));

        if (boxes.isEmpty())
        {
            throw new IllegalArgumentException("LVC project has no valid area boxes");
        }

        List<LvcManifest.Region> regions = new ArrayList<>();
        Set<String> usedRegionIds = new HashSet<>();

        for (Box box : boxes)
        {
            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();

            if (pos1 == null || pos2 == null)
            {
                continue;
            }

            BlockPos min = PositionUtils.getMinCorner(pos1, pos2);
            BlockPos max = PositionUtils.getMaxCorner(pos1, pos2);
            BlockPos relativeMin = min.subtract(origin);
            BlockPos size = max.subtract(min).offset(1, 1, 1);
            String regionName = box.getName();
            String regionBoundsKey = regionBoundsKey(LvcProjectPositions.blockPosToList(relativeMin), LvcProjectPositions.blockPosToList(size));
            String regionId = matchingExistingRegionId(regionName, regionBoundsKey, existingRegions, usedRegionIds);

            if (regionId == null)
            {
                regionId = uniqueRegionId(regionName, usedRegionIds);
            }
            else
            {
                usedRegionIds.add(regionId);
            }

            regions.add(new LvcManifest.Region(
                    regionId,
                    regionName,
                    LvcProjectPositions.blockPosToList(relativeMin),
                    LvcProjectPositions.blockPosToList(size)
            ));
        }

        return List.copyOf(regions);
    }

    public static LvcLocalState.SitePlacement createSitePlacement(BlockPos origin, String dimensionId)
    {
        return new LvcLocalState.SitePlacement(dimensionId, LvcProjectPositions.blockPosToList(origin), "");
    }

    public static int countValidSelectionRegions(AreaSelection selection)
    {
        Objects.requireNonNull(selection, "selection");
        return getValidBoxes(selection).size();
    }

    public static void validateProjectName(String projectName)
    {
        if (projectName == null || projectName.isBlank())
        {
            throw new IllegalArgumentException("LVC project name must not be blank");
        }
    }

    private static List<Box> getValidBoxes(AreaSelection selection)
    {
        return PositionUtils.getValidBoxes(selection);
    }

    @Nullable
    private static String matchingExistingRegionId(String regionName, String regionBoundsKey, List<LvcManifest.Region> existingRegions, Set<String> usedRegionIds)
    {
        for (LvcManifest.Region region : existingRegions)
        {
            if (!usedRegionIds.contains(region.id()) && region.name().equals(regionName))
            {
                return region.id();
            }
        }

        for (LvcManifest.Region region : existingRegions)
        {
            if (!usedRegionIds.contains(region.id()) && regionBoundsKey(region).equals(regionBoundsKey))
            {
                return region.id();
            }
        }

        return null;
    }

    private static String regionBoundsKey(LvcManifest.Region region)
    {
        return regionBoundsKey(region.min(), region.size());
    }

    private static String regionBoundsKey(List<Integer> min, List<Integer> size)
    {
        return min + "|" + size;
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
}
