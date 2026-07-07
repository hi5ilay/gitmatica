package me.zly2006.lvc.project;

import java.util.List;
import javax.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

public final class LvcProjectPositions
{
    private LvcProjectPositions()
    {
    }

    public static JsonArray blockPosToArray(BlockPos pos)
    {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    public static List<Integer> blockPosToList(BlockPos pos)
    {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockPos blockPosFromList(List<Integer> values)
    {
        if (values == null || values.size() != 3)
        {
            throw new IllegalArgumentException("LVC position must contain three coordinates");
        }

        return new BlockPos(values.get(0), values.get(1), values.get(2));
    }

    @Nullable
    public static BlockPos readBlockPosArray(JsonObject obj, String key)
    {
        if (!obj.has(key) || !obj.get(key).isJsonArray())
        {
            return null;
        }

        JsonArray arr = obj.get(key).getAsJsonArray();

        if (arr.size() != 3)
        {
            return null;
        }

        return new BlockPos(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
    }
}
