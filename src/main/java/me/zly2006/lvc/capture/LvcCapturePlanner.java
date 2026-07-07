package me.zly2006.lvc.capture;

import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.model.LvcManifest;

public final class LvcCapturePlanner
{
    private LvcCapturePlanner()
    {
    }

    public static Map<LvcChunkCoordinate, BitSet> planSite(LvcManifest.Site site)
    {
        return planSite(site, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE);
    }

    public static Map<LvcChunkCoordinate, BitSet> planSite(LvcManifest.Site site, int sizeX, int sizeY, int sizeZ)
    {
        Map<LvcChunkCoordinate, BitSet> masks = new TreeMap<>();
        int volume = sizeX * sizeY * sizeZ;

        for (LvcManifest.Region region : site.regions())
        {
            LvcIntPosition min = LvcIntPosition.fromList(region.min());
            LvcIntPosition size = LvcIntPosition.fromList(region.size());
            int maxX = min.x() + size.x() - 1;
            int maxY = min.y() + size.y() - 1;
            int maxZ = min.z() + size.z() - 1;
            int minChunkX = Math.floorDiv(min.x(), sizeX);
            int maxChunkX = Math.floorDiv(maxX, sizeX);
            int minChunkY = Math.floorDiv(min.y(), sizeY);
            int maxChunkY = Math.floorDiv(maxY, sizeY);
            int minChunkZ = Math.floorDiv(min.z(), sizeZ);
            int maxChunkZ = Math.floorDiv(maxZ, sizeZ);

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
            {
                for (int chunkY = minChunkY; chunkY <= maxChunkY; chunkY++)
                {
                    for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
                    {
                        LvcChunkCoordinate coordinate = new LvcChunkCoordinate(chunkX, chunkY, chunkZ);
                        BitSet mask = masks.computeIfAbsent(coordinate, ignored -> new BitSet(volume));
                        setIntersectionBits(mask, min, maxX, maxY, maxZ, coordinate, sizeX, sizeY, sizeZ);
                    }
                }
            }
        }

        return Map.copyOf(masks);
    }

    public static int index(int x, int y, int z, int sizeX, int sizeY)
    {
        return x + sizeX * (y + sizeY * z);
    }

    public static LvcIntPosition projectPosition(LvcChunkCoordinate coordinate, int localIndex, int sizeX, int sizeY, int sizeZ)
    {
        int localX = localIndex % sizeX;
        int localY = (localIndex / sizeX) % sizeY;
        int localZ = localIndex / (sizeX * sizeY);
        return new LvcIntPosition(
                coordinate.x() * sizeX + localX,
                coordinate.y() * sizeY + localY,
                coordinate.z() * sizeZ + localZ
        );
    }

    private static void setIntersectionBits(BitSet mask, LvcIntPosition regionMin, int regionMaxX, int regionMaxY, int regionMaxZ,
                                            LvcChunkCoordinate coordinate, int sizeX, int sizeY, int sizeZ)
    {
        int chunkMinX = coordinate.x() * sizeX;
        int chunkMinY = coordinate.y() * sizeY;
        int chunkMinZ = coordinate.z() * sizeZ;
        int minX = Math.max(regionMin.x(), chunkMinX);
        int minY = Math.max(regionMin.y(), chunkMinY);
        int minZ = Math.max(regionMin.z(), chunkMinZ);
        int maxX = Math.min(regionMaxX, chunkMinX + sizeX - 1);
        int maxY = Math.min(regionMaxY, chunkMinY + sizeY - 1);
        int maxZ = Math.min(regionMaxZ, chunkMinZ + sizeZ - 1);

        for (int z = minZ; z <= maxZ; z++)
        {
            for (int y = minY; y <= maxY; y++)
            {
                for (int x = minX; x <= maxX; x++)
                {
                    int localX = x - chunkMinX;
                    int localY = y - chunkMinY;
                    int localZ = z - chunkMinZ;
                    mask.set(index(localX, localY, localZ, sizeX, sizeY));
                }
            }
        }
    }
}
