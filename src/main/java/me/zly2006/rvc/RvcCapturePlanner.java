package me.zly2006.rvc;

import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;

public final class RvcCapturePlanner
{
    private RvcCapturePlanner()
    {
    }

    public static Map<RvcChunkCoordinate, BitSet> planSite(RvcManifest.Site site)
    {
        return planSite(site, RvcChunk.DEFAULT_SIZE, RvcChunk.DEFAULT_SIZE, RvcChunk.DEFAULT_SIZE);
    }

    public static Map<RvcChunkCoordinate, BitSet> planSite(RvcManifest.Site site, int sizeX, int sizeY, int sizeZ)
    {
        Map<RvcChunkCoordinate, BitSet> masks = new TreeMap<>();
        int volume = sizeX * sizeY * sizeZ;

        for (RvcManifest.Region region : site.regions())
        {
            RvcIntPosition min = RvcIntPosition.fromList(region.min());
            RvcIntPosition size = RvcIntPosition.fromList(region.size());
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
                        RvcChunkCoordinate coordinate = new RvcChunkCoordinate(chunkX, chunkY, chunkZ);
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

    public static RvcIntPosition projectPosition(RvcChunkCoordinate coordinate, int localIndex, int sizeX, int sizeY, int sizeZ)
    {
        int localX = localIndex % sizeX;
        int localY = (localIndex / sizeX) % sizeY;
        int localZ = localIndex / (sizeX * sizeY);
        return new RvcIntPosition(
                coordinate.x() * sizeX + localX,
                coordinate.y() * sizeY + localY,
                coordinate.z() * sizeZ + localZ
        );
    }

    private static void setIntersectionBits(BitSet mask, RvcIntPosition regionMin, int regionMaxX, int regionMaxY, int regionMaxZ,
                                            RvcChunkCoordinate coordinate, int sizeX, int sizeY, int sizeZ)
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
