package fi.dy.masa.litematica.schematic.verifier.inventory;

import java.util.Arrays;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import fi.dy.masa.malilib.util.data.Color4f;

public class VerifierInventoryPreview
{
    private final BlockPos pos;
    @Nullable private final VerifierInventorySide expected;
    @Nullable private final VerifierInventorySide found;
    private final VerifierInventorySlotDiff[] slotDiffs;
    private final boolean hasDiffs;

    public VerifierInventoryPreview(BlockPos pos, @Nullable VerifierInventorySide expected, @Nullable VerifierInventorySide found)
    {
        this.pos = pos.immutable();
        this.expected = expected;
        this.found = found;
        this.slotDiffs = createSlotDiffs(expected, found);
        this.hasDiffs = Arrays.stream(this.slotDiffs).anyMatch(diff -> diff != VerifierInventorySlotDiff.MATCH);
    }

    private static VerifierInventorySlotDiff[] createSlotDiffs(@Nullable VerifierInventorySide expected, @Nullable VerifierInventorySide found)
    {
        int size = Math.max(expected != null ? expected.inventory().getContainerSize() : 0,
                            found != null ? found.inventory().getContainerSize() : 0);
        VerifierInventorySlotDiff[] diffs = new VerifierInventorySlotDiff[size];

        for (int i = 0; i < size; ++i)
        {
            ItemStack expectedStack = getStack(expected, i);
            ItemStack foundStack = getStack(found, i);
            diffs[i] = classify(expectedStack, foundStack);
        }

        return diffs;
    }

    private static ItemStack getStack(@Nullable VerifierInventorySide side, int slot)
    {
        if (side == null || slot < 0 || slot >= side.inventory().getContainerSize())
        {
            return ItemStack.EMPTY;
        }

        return side.inventory().getItem(slot);
    }

    private static VerifierInventorySlotDiff classify(ItemStack expectedStack, ItemStack foundStack)
    {
        if (expectedStack.isEmpty() && foundStack.isEmpty() == false)
        {
            return VerifierInventorySlotDiff.ADDED;
        }
        else if (expectedStack.isEmpty() == false && foundStack.isEmpty())
        {
            return VerifierInventorySlotDiff.REMOVED;
        }
        else if (expectedStack.isEmpty() == false &&
                 (expectedStack.getItem() != foundStack.getItem() || expectedStack.getCount() != foundStack.getCount()))
        {
            return VerifierInventorySlotDiff.CHANGED;
        }

        return VerifierInventorySlotDiff.MATCH;
    }

    public BlockPos pos()
    {
        return this.pos;
    }

    @Nullable
    public VerifierInventorySide expected()
    {
        return this.expected;
    }

    @Nullable
    public VerifierInventorySide found()
    {
        return this.found;
    }

    public boolean hasDiffs()
    {
        return this.hasDiffs;
    }

    public VerifierInventorySlotDiff slotDiff(int slot)
    {
        return slot >= 0 && slot < this.slotDiffs.length ? this.slotDiffs[slot] : VerifierInventorySlotDiff.MATCH;
    }

    public ItemStack expectedStack(int slot)
    {
        return getStack(this.expected, slot);
    }

    public Color4f renderColor()
    {
        boolean changed = false;
        boolean added = false;

        for (VerifierInventorySlotDiff diff : this.slotDiffs)
        {
            if (diff == VerifierInventorySlotDiff.REMOVED)
            {
                return VerifierInventorySlotDiff.REMOVED.getRenderColor();
            }
            else if (diff == VerifierInventorySlotDiff.CHANGED)
            {
                changed = true;
            }
            else if (diff == VerifierInventorySlotDiff.ADDED)
            {
                added = true;
            }
        }

        if (changed)
        {
            return VerifierInventorySlotDiff.CHANGED.getRenderColor();
        }
        else if (added)
        {
            return VerifierInventorySlotDiff.ADDED.getRenderColor();
        }

        return VerifierInventorySlotDiff.CHANGED.getRenderColor();
    }
}
