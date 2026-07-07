package me.zly2006.lvc.model;

import java.util.List;

public record LvcIntPosition(int x, int y, int z)
{
    public LvcIntPosition offset(LvcIntPosition other)
    {
        return new LvcIntPosition(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public static LvcIntPosition fromList(List<Integer> values)
    {
        if (values == null || values.size() != 3)
        {
            throw new IllegalArgumentException("LVC position must contain three coordinates");
        }

        return new LvcIntPosition(values.get(0), values.get(1), values.get(2));
    }
}
