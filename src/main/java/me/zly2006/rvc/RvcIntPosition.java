package me.zly2006.rvc;

import java.util.List;

public record RvcIntPosition(int x, int y, int z)
{
    public RvcIntPosition offset(RvcIntPosition other)
    {
        return new RvcIntPosition(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public static RvcIntPosition fromList(List<Integer> values)
    {
        if (values == null || values.size() != 3)
        {
            throw new IllegalArgumentException("RVC position must contain three coordinates");
        }

        return new RvcIntPosition(values.get(0), values.get(1), values.get(2));
    }
}
