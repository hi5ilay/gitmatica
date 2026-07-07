package me.zly2006.lvc.model;

import java.util.Objects;

public record LvcChunkCoordinate(int x, int y, int z) implements Comparable<LvcChunkCoordinate>
{
    public static LvcChunkCoordinate parse(String key)
    {
        Objects.requireNonNull(key, "key");
        String[] parts = key.split(",", -1);

        if (parts.length != 3)
        {
            throw new IllegalArgumentException("LVC chunk key must have form x,y,z: " + key);
        }

        try
        {
            return new LvcChunkCoordinate(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("LVC chunk key contains a non-integer coordinate: " + key, e);
        }
    }

    public String key()
    {
        return this.x + "," + this.y + "," + this.z;
    }

    @Override
    public int compareTo(LvcChunkCoordinate other)
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
