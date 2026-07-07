package me.zly2006.lvc.util;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

public final class LvcLootTablePreview
{
    private LvcLootTablePreview()
    {
    }

    public static Result materializeContainerLoot(CompoundTag blockEntity, ServerLevel world, BlockPos worldPos)
    {
        Objects.requireNonNull(blockEntity, "blockEntity");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(worldPos, "worldPos");

        if (blockEntity.contains("Items"))
        {
            return Result.UNCHANGED;
        }

        LootReference lootReference = lootReference(blockEntity);

        if (lootReference == null)
        {
            return Result.UNCHANGED;
        }

        Identifier lootTableId = Identifier.tryParse(lootReference.lootTableId());

        if (lootTableId == null)
        {
            return Result.FAILED;
        }

        try
        {
            ResourceKey<LootTable> lootTableKey = ResourceKey.create(Registries.LOOT_TABLE, lootTableId);
            LootTable lootTable = world.getServer().reloadableRegistries().getLootTable(lootTableKey);
            SimpleContainer container = new SimpleContainer(containerSize(blockEntity.getStringOr("id", "")));
            LootParams params = new LootParams.Builder(world)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(worldPos))
                    .create(LootContextParamSets.CHEST);

            lootTable.fill(container, params, lootReference.seed());

            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registryAccess());
            ContainerHelper.saveAllItems(output, container.getItems(), true);
            ListTag items = output.buildResult().getListOrEmpty("Items");
            blockEntity.put("Items", items);
            removeLootReference(blockEntity);
            return Result.MATERIALIZED;
        }
        catch (RuntimeException e)
        {
            return Result.FAILED;
        }
    }

    private static int containerSize(String blockEntityId)
    {
        return switch (blockEntityId)
        {
            case "minecraft:hopper" -> 5;
            case "minecraft:dispenser", "minecraft:dropper", "minecraft:crafter" -> 9;
            case "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker" -> 3;
            case "minecraft:brewing_stand" -> 5;
            case "minecraft:chiseled_bookshelf" -> 6;
            case "minecraft:decorated_pot", "minecraft:jukebox" -> 1;
            default -> 27;
        };
    }

    @Nullable
    private static LootReference lootReference(CompoundTag blockEntity)
    {
        String rootLootTable = stringValue(blockEntity.get("LootTable"));

        if (!rootLootTable.isBlank())
        {
            return new LootReference(rootLootTable, blockEntity.getLongOr("LootTableSeed", 0L));
        }

        if (!blockEntity.contains("components"))
        {
            return null;
        }

        CompoundTag components = blockEntity.getCompoundOrEmpty("components");
        CompoundTag componentLoot = containerLootComponent(components);

        if (componentLoot == null)
        {
            return null;
        }

        String componentLootTable = stringValue(componentLoot.get("loot_table"));

        if (componentLootTable.isBlank())
        {
            return null;
        }

        return new LootReference(componentLootTable, componentLoot.getLongOr("seed", 0L));
    }

    @Nullable
    private static CompoundTag containerLootComponent(CompoundTag components)
    {
        Tag componentTag = components.get("minecraft:container_loot");

        if (!(componentTag instanceof CompoundTag))
        {
            componentTag = components.get("container_loot");
        }

        return componentTag instanceof CompoundTag compoundTag ? compoundTag : null;
    }

    private static String stringValue(@Nullable Tag tag)
    {
        return tag == null ? "" : tag.asString().orElse("");
    }

    private static void removeLootReference(CompoundTag blockEntity)
    {
        blockEntity.remove("LootTable");
        blockEntity.remove("LootTableSeed");

        if (blockEntity.contains("components"))
        {
            CompoundTag components = blockEntity.getCompoundOrEmpty("components");
            components.remove("minecraft:container_loot");
            components.remove("container_loot");
        }
    }

    public enum Result
    {
        UNCHANGED,
        MATERIALIZED,
        FAILED
    }

    private record LootReference(String lootTableId, long seed)
    {
    }
}
