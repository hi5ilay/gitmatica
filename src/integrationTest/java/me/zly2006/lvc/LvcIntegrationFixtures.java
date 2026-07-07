package me.zly2006.lvc;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.util.data.json.JsonUtils;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcCanonicalNbt;
import me.zly2006.lvc.storage.LvcChunkStore;

final class LvcIntegrationFixtures
{
    private LvcIntegrationFixtures()
    {
    }

    static LvcManifest.Site singleLineSite()
    {
        return singleLineSite(1);
    }

    static LvcManifest.Site singleLineSite(int sizeX)
    {
        return new LvcManifest.Site(
                "main",
                "Main",
                "minecraft:overworld",
                List.of(new LvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(sizeX, 1, 1))),
                Map.of()
        );
    }

    static LvcManifest.Site twoBlockSite()
    {
        return new LvcManifest.Site(
                "main",
                "Main",
                "minecraft:overworld",
                List.of(new LvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(2, 1, 1))),
                Map.of()
        );
    }

    static LvcLocalState.SitePlacement placementAt(int x, int y, int z)
    {
        return new LvcLocalState.SitePlacement("minecraft:overworld", List.of(x, y, z), "");
    }

    static LvcPlayerIdentity player(String name)
    {
        return new LvcPlayerIdentity(name, UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }

    static void bootstrapMinecraft()
    {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        Bootstrap.bootStrap();
    }

    static ListTag itemList(int count, String itemId)
    {
        ListTag items = new ListTag();
        CompoundTag item = new CompoundTag();
        item.putByte("Slot", (byte) 0);
        item.putString("id", itemId);
        item.putInt("count", count);
        items.add(item);
        return items;
    }

    static CompoundTag inventoryBlockEntity(String itemId, String customName)
    {
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:furnace");
        blockEntity.putString("CustomName", customName);
        blockEntity.put("Items", itemList(1, itemId));
        blockEntity.putInt("x", 123);
        blockEntity.putInt("y", 64);
        blockEntity.putInt("z", -20);
        return blockEntity;
    }

    static LvcChunk singleBlockEntityChunk(CompoundTag blockEntity) throws Exception
    {
        BitSet mask = new BitSet(LvcChunk.DEFAULT_VOLUME);
        mask.set(0);
        return LvcChunk.fromTrackedContent(
                LvcChunk.DEFAULT_SIZE,
                LvcChunk.DEFAULT_SIZE,
                LvcChunk.DEFAULT_SIZE,
                mask,
                List.of("minecraft:furnace"),
                List.of(new LvcChunk.BlockEntityRecord(0, LvcCanonicalNbt.encodeBlockEntity(blockEntity))),
                List.of(),
                List.of()
        );
    }

    static AreaSelection areaSelectionFromBoxes(String name, List<JsonObject> boxes)
    {
        JsonObject root = new JsonObject();
        root.add("current", new JsonPrimitive(boxes.isEmpty() ? "" : boxes.get(0).get("name").getAsString()));
        root.add("name", new JsonPrimitive(name));
        JsonArray array = new JsonArray();

        for (JsonObject box : boxes)
        {
            array.add(box);
        }

        root.add("boxes", array);
        return AreaSelection.fromJson(root);
    }

    static JsonObject boxJson(String name, BlockPos pos1, BlockPos pos2)
    {
        JsonObject box = new JsonObject();
        box.add("name", new JsonPrimitive(name));
        box.add("pos1", JsonUtils.blockPosToJson(pos1));
        box.add("pos2", JsonUtils.blockPosToJson(pos2));
        return box;
    }

    static String objectId(String value)
    {
        return LvcChunkStore.objectId(value.getBytes(StandardCharsets.UTF_8));
    }

    static List<Integer> blockPosToList(BlockPos pos)
    {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }
}
