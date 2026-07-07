package me.zly2006.rvc;

import java.util.Objects;

public record RvcChunkCoordinate(int x, int y, int z) implements Comparable<RvcChunkCoordinate>
{
    public static RvcChunkCoordinate parse(String key)
    {
        Objects.requireNonNull(key, "key");
        String[] parts = key.split(",", -1);

        if (parts.length != 3)
        {
            throw new IllegalArgumentException("RVC chunk key must have form x,y,z: " + key);
        }

        try
        {
            return new RvcChunkCoordinate(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("RVC chunk key contains a non-integer coordinate: " + key, e);
        }
    }

    public String key()
    {
        return this.x + "," + this.y + "," + this.z;
    }

    @Override
    public int compareTo(RvcChunkCoordinate other)
    {
        int xCompare = Integer.compare(this.x, other.x);

        if (xCompare != 0)
        {
            return xCompare;
        }

        int yCompare = Integer.compare(this.y, other.y);

        if (yCompare != 0)
        {
            return yCompare;
        }

        return Integer.compare(this.z, other.z);
    }
}
