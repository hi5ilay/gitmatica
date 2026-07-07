package fi.dy.masa.litematica.schematic.verifier.inventory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.InventoryOverlay.InventoryProperties;
import fi.dy.masa.malilib.render.InventoryOverlayType;
import fi.dy.masa.malilib.util.data.DataBlockUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import fi.dy.masa.malilib.util.game.BlockUtils;

public class VerifierInventorySide
{
    private final Container inventory;
    private final InventoryOverlayType type;
    private final CompoundData data;
    private final Set<Integer> disabledSlots;

    private VerifierInventorySide(Container inventory, InventoryOverlayType type, CompoundData data, Set<Integer> disabledSlots)
    {
        this.inventory = inventory;
        this.type = type;
        this.data = data;
        this.disabledSlots = Collections.unmodifiableSet(new HashSet<>(disabledSlots));
    }

    @Nullable
    public static VerifierInventorySide ofBlockEntity(BlockEntity blockEntity, RegistryAccess registryAccess)
    {
        if ((blockEntity instanceof Container) == false)
        {
            return null;
        }

        CompoundTag tag = blockEntity.saveWithFullMetadata(registryAccess);
        CompoundData data = DataConverterNbt.fromVanillaCompound(tag);
        BlockEntity copy = BlockEntity.loadStatic(blockEntity.getBlockPos(), blockEntity.getBlockState(), tag, registryAccess);
        Container inventory = copy instanceof Container container ? container : copyInventory((Container) blockEntity);
        InventoryOverlayType type = InventoryOverlay.getBestInventoryType((Container) blockEntity, data);
        return new VerifierInventorySide(inventory, type, data, getDisabledSlots(inventory, data, type));
    }

    public static VerifierInventorySide ofContainer(Container inventory, InventoryOverlayType type)
    {
        return new VerifierInventorySide(inventory, type, new CompoundData(), Set.of());
    }

    public static VerifierInventorySide ofContainer(Container inventory, VerifierInventorySide template)
    {
        return new VerifierInventorySide(inventory, template.type, template.data, template.disabledSlots);
    }

    private static Container copyInventory(Container inventory)
    {
        SimpleContainer copy = new SimpleContainer(inventory.getContainerSize());

        for (int i = 0; i < inventory.getContainerSize(); ++i)
        {
            copy.setItem(i, inventory.getItem(i).copy());
        }

        return copy;
    }

    private static Set<Integer> getDisabledSlots(Container inventory, CompoundData data, InventoryOverlayType type)
    {
        if (type != InventoryOverlayType.CRAFTER)
        {
            return Set.of();
        }

        if (data != null && data.isEmpty() == false)
        {
            return DataBlockUtils.getDisabledSlots(data);
        }
        else if (inventory instanceof CrafterBlockEntity cbe)
        {
            return BlockUtils.getDisabledSlots(cbe);
        }

        return Set.of();
    }

    public Container inventory()
    {
        return this.inventory;
    }

    public InventoryOverlayType type()
    {
        return this.type;
    }

    public InventoryProperties properties()
    {
        return InventoryOverlay.getInventoryPropsTemp(this.type, this.inventory.getContainerSize());
    }

    public Set<Integer> disabledSlots()
    {
        return this.disabledSlots;
    }
}
