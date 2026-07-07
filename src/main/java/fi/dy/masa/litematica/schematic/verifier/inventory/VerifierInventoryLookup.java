package fi.dy.masa.litematica.schematic.verifier.inventory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.SchematicUtils;

public class VerifierInventoryLookup
{
    public static Optional<VerifierInventorySide> getSchematicInventory(BlockPos worldPos, BlockState worldState)
    {
        ChestType type = getChestType(worldState);

        if (type == ChestType.SINGLE)
        {
            return getSchematicInventoryInternal(worldPos);
        }

        if (Minecraft.getInstance().level == null)
        {
            return Optional.empty();
        }

        BlockPos adjacent = worldPos.relative(ChestBlock.getConnectedDirection(worldState));
        BlockState adjacentState = Minecraft.getInstance().level.getBlockState(adjacent);
        Optional<VerifierInventorySide> first = getSchematicInventoryInternal(worldPos);
        Optional<VerifierInventorySide> second = getSchematicInventoryInternal(adjacent);

        if (first.isEmpty() && second.isEmpty())
        {
            return Optional.empty();
        }

        Container firstInventory = first.map(VerifierInventorySide::inventory).orElse(new SimpleContainer(27));
        Container secondInventory = second.map(VerifierInventorySide::inventory).orElse(new SimpleContainer(27));
        Container merged = type == ChestType.RIGHT ? merge(firstInventory, secondInventory) : merge(secondInventory, firstInventory);
        VerifierInventorySide template = first.or(() -> second).orElse(null);

        if (template == null)
        {
            return Optional.empty();
        }

        if (getChestType(adjacentState) != ChestType.SINGLE)
        {
            return Optional.of(VerifierInventorySide.ofContainer(merged, template));
        }

        return Optional.of(VerifierInventorySide.ofContainer(merged, template));
    }

    private static Optional<VerifierInventorySide> getSchematicInventoryInternal(BlockPos worldPos)
    {
        Optional<LocalPlacementPos> optionalPos = getLocalPlacementPos(worldPos);

        if (optionalPos.isEmpty())
        {
            return Optional.empty();
        }

        LocalPlacementPos placementPos = optionalPos.get();
        Map<BlockPos, CompoundTag> blockEntities = placementPos.placement().getSchematic().getBlockEntityMapForRegion(placementPos.region());

        if (blockEntities == null)
        {
            return Optional.empty();
        }

        CompoundTag nbt = blockEntities.get(placementPos.pos());

        if (nbt == null || Minecraft.getInstance().level == null)
        {
            return Optional.empty();
        }

        BlockEntity blockEntity = BlockEntity.loadStatic(placementPos.pos(), placementPos.blockState(), nbt,
                Minecraft.getInstance().level.registryAccess());

        if (blockEntity == null)
        {
            return Optional.empty();
        }

        return Optional.ofNullable(VerifierInventorySide.ofBlockEntity(blockEntity, Minecraft.getInstance().level.registryAccess()));
    }

    private static SimpleContainer merge(Container first, Container second)
    {
        CompoundContainer combined = new CompoundContainer(first, second);
        SimpleContainer inventory = new SimpleContainer(combined.getContainerSize());

        for (int i = 0; i < combined.getContainerSize(); ++i)
        {
            inventory.setItem(i, combined.getItem(i).copy());
        }

        return inventory;
    }

    private static ChestType getChestType(BlockState state)
    {
        if ((state.getBlock() instanceof ChestBlock) == false)
        {
            return ChestType.SINGLE;
        }

        return state.getValue(ChestBlock.TYPE);
    }

    private static Optional<LocalPlacementPos> getLocalPlacementPos(BlockPos worldPos)
    {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        SchematicPlacement selectedPlacement = manager.getSelectedSchematicPlacement();
        List<SchematicPlacementManager.PlacementPart> parts = manager.getAllPlacementsTouchingChunk(worldPos)
                .stream()
                .sorted(Comparator.comparing(part -> part.getPlacement() != selectedPlacement))
                .toList();

        for (SchematicPlacementManager.PlacementPart part : parts)
        {
            if (part.getBox().containsPos(worldPos) == false)
            {
                continue;
            }

            SchematicPlacement placement = part.getPlacement();
            String region = part.getSubRegionName();
            LitematicaBlockStateContainer container = placement.getSchematic().getSubRegionContainer(region);
            BlockPos schematicPos = SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                    worldPos,
                    placement.getSchematic(),
                    region,
                    placement,
                    Objects.requireNonNull(placement.getRelativeSubRegionPlacement(region), "Missing sub-region placement"),
                    container);

            return Optional.of(new LocalPlacementPos(schematicPos, region, placement));
        }

        return Optional.empty();
    }

    private record LocalPlacementPos(BlockPos pos, String region, SchematicPlacement placement)
    {
        public BlockState blockState()
        {
            return this.placement.getSchematic().getSubRegionContainer(this.region).get(this.pos.getX(), this.pos.getY(), this.pos.getZ());
        }
    }
}
