package fi.dy.masa.litematica.schematic.verifier.inventory;

import java.util.Set;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import fi.dy.masa.litematica.render.RenderUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.InventoryOverlay.InventoryProperties;
import fi.dy.masa.malilib.render.InventoryOverlayType;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;

public class VerifierInventoryOverlay
{
    public static final float MISSING_ITEM_ALPHA = 0.5F;

    @Nullable public static VerifierInventoryOverlay infoOverlayInstance;
    @Nullable public static ItemStack hoveredStackToRender;
    public static boolean delayRenderingHoveredStack;
    public static boolean isRenderingTransparentItem;
    public static Set<GuiItemRenderState> transparentItemStates = new ReferenceOpenHashSet<>();

    @Nullable private static BlockPos lastClickedPos;
    @Nullable private static Container boundContainer;
    @Nullable private static BlockPos boundPos;
    @Nullable private static VerifierInventorySide boundExpected;
    @Nullable private static VerifierInventoryPreview boundPreview;
    private static int boundContainerSignature;

    private final VerifierInventoryPreview preview;
    private final boolean colorSlots;

    public VerifierInventoryOverlay(VerifierInventoryPreview preview, boolean colorSlots)
    {
        this.preview = preview;
        this.colorSlots = colorSlots;
    }

    public static void onContainerClick(BlockHitResult hitResult)
    {
        lastClickedPos = hitResult.getBlockPos().immutable();
    }

    public static void onScreenChanged(@Nullable Screen screen)
    {
        boundContainer = null;
        boundPos = null;
        boundExpected = null;
        boundPreview = null;
        boundContainerSignature = 0;

        if (lastClickedPos == null || (screen instanceof MenuAccess<?>) == false)
        {
            if ((screen instanceof MenuAccess<?>) == false)
            {
                lastClickedPos = null;
            }

            return;
        }

        bindOpenContainer(screen, lastClickedPos);
    }

    public static void markOpenContainerChanged()
    {
        if (boundContainer != null && boundPos != null)
        {
            boundPreview = createOpenContainerPreview(boundPos, boundContainer, boundExpected);
            boundContainerSignature = inventorySignature(boundContainer);
        }
    }

    public static ItemStack drawScreenStack(GuiContext ctx, Slot slot, ItemStack stack)
    {
        refreshOpenContainerPreviewIfChanged();

        if (boundPreview == null || isBoundSlot(slot) == false)
        {
            return stack;
        }

        return new VerifierInventoryOverlay(boundPreview, true).drawStackInternal(ctx, slot, stack);
    }

    public static void finalizeDrawStack()
    {
        isRenderingTransparentItem = false;
    }

    public static boolean setSlotToExpectedItem(@Nullable Slot slot)
    {
        if (boundPreview == null || slot == null || isBoundSlot(slot) == false)
        {
            return false;
        }

        int index = slot.getContainerSlot();

        if (boundPreview.slotDiff(index) != VerifierInventorySlotDiff.REMOVED)
        {
            return false;
        }

        ItemStack expected = boundPreview.expectedStack(index);

        if (expected.isEmpty())
        {
            return false;
        }

        slot.setByPlayer(expected.copy());
        return true;
    }

    public static void renderPreviewTooltip(GuiContext ctx, VerifierInventoryPreview preview, int mouseX, int mouseY)
    {
        VerifierInventorySide expected = preview.expected();
        VerifierInventorySide found = preview.found();

        if (expected == null && found == null)
        {
            return;
        }

        SideMetrics expectedMetrics = SideMetrics.of(expected != null ? expected : found);
        SideMetrics foundMetrics = SideMetrics.of(found != null ? found : expected);
        int gap = 8;
        int labelHeight = 14;
        int totalWidth = expectedMetrics.width() + foundMetrics.width() + gap + 20;
        int totalHeight = Math.max(expectedMetrics.height(), foundMetrics.height()) + labelHeight + 12;
        int x = mouseX + 10;
        int y = mouseY + 10;

        if (x + totalWidth > GuiUtils.getCurrentScreenWidth())
        {
            x = mouseX - totalWidth - 10;
        }

        if (y + totalHeight > GuiUtils.getCurrentScreenHeight())
        {
            y = mouseY - totalHeight - 2;
        }

        RenderUtils.renderBackgroundMask(ctx, x + 1, y + 1, totalWidth - 1, totalHeight - 1);
        fi.dy.masa.malilib.render.RenderUtils.drawOutlinedBox(ctx, x, y, totalWidth, totalHeight, 0xFF000000, GuiBase.COLOR_HORIZONTAL_BAR);

        int xExpected = x + 8;
        int xFound = xExpected + expectedMetrics.width() + gap;
        int yInv = y + labelHeight + 6;
        String pre = GuiBase.TXT_WHITE + GuiBase.TXT_BOLD;

        ctx.drawString(ctx.fontRenderer(), pre + StringUtils.translate("litematica.gui.label.schematic_verifier.expected") + GuiBase.TXT_RST, xExpected, y + 5, 0xFFFFFFFF, false);
        ctx.drawString(ctx.fontRenderer(), pre + StringUtils.translate("litematica.gui.label.schematic_verifier.found") + GuiBase.TXT_RST, xFound, y + 5, 0xFFFFFFFF, false);

        delayRenderingHoveredStack = true;
        renderSide(ctx, preview, expected, expectedMetrics, false, xExpected, yInv, mouseX, mouseY);
        renderSide(ctx, preview, found, foundMetrics, true, xFound, yInv, mouseX, mouseY);
        delayRenderingHoveredStack = false;

        if (hoveredStackToRender != null)
        {
            InventoryOverlay.renderStackToolTipStyled(ctx, mouseX, mouseY, hoveredStackToRender);
            hoveredStackToRender = null;
        }
    }

    public ItemStack drawStackInternal(GuiContext ctx, Slot slot, ItemStack stack)
    {
        if (this.colorSlots == false)
        {
            return stack;
        }

        int slotIndex = slot.getContainerSlot();
        VerifierInventorySlotDiff diff = this.preview.slotDiff(slotIndex);

        if (diff == VerifierInventorySlotDiff.MATCH)
        {
            return stack;
        }

        fi.dy.masa.malilib.render.RenderUtils.drawRect(ctx, slot.x, slot.y, 16, 16, diff.getOverlayColor());

        if (diff == VerifierInventorySlotDiff.REMOVED)
        {
            ItemStack expected = this.preview.expectedStack(slotIndex);

            if (expected.isEmpty() == false)
            {
                isRenderingTransparentItem = true;
                return expected.copy();
            }
        }

        return stack;
    }

    public void finalizeDrawStackInternal()
    {
        isRenderingTransparentItem = false;
    }

    private static void bindOpenContainer(Screen screen, BlockPos pos)
    {
        MenuAccess<?> access = (MenuAccess<?>) screen;
        Slot validSlot = null;

        for (Slot slot : access.getMenu().slots)
        {
            if (isCandidateContainerSlot(slot))
            {
                validSlot = slot;
                break;
            }
        }

        if (validSlot == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null)
        {
            return;
        }

        BlockState state = mc.level.getBlockState(pos);
        boundContainer = validSlot.container;
        boundPos = pos.immutable();
        boundExpected = VerifierInventoryLookup.getSchematicInventory(pos, state).orElse(null);

        if (boundExpected == null)
        {
            boundContainer = null;
            boundPos = null;
            return;
        }

        boundPreview = createOpenContainerPreview(pos, boundContainer, boundExpected);
        boundContainerSignature = inventorySignature(boundContainer);
    }

    @Nullable
    private static VerifierInventoryPreview createOpenContainerPreview(BlockPos pos, Container container, @Nullable VerifierInventorySide expected)
    {
        Minecraft mc = Minecraft.getInstance();
        VerifierInventorySide foundTemplate = null;

        if (mc.level != null)
        {
            BlockEntity blockEntity = mc.level.getBlockEntity(pos);

            if (blockEntity instanceof Container)
            {
                foundTemplate = VerifierInventorySide.ofBlockEntity(blockEntity, mc.level.registryAccess());
            }
        }

        VerifierInventorySide found;

        if (expected != null)
        {
            found = VerifierInventorySide.ofContainer(container, expected);
        }
        else if (foundTemplate != null)
        {
            found = VerifierInventorySide.ofContainer(container, foundTemplate);
        }
        else
        {
            InventoryOverlayType type = InventoryOverlay.getBestInventoryType(container, new CompoundData());
            found = VerifierInventorySide.ofContainer(container, type);
        }

        return new VerifierInventoryPreview(pos, expected, found);
    }

    private static boolean isBoundSlot(Slot slot)
    {
        return slot.container == boundContainer && isCandidateContainerSlot(slot);
    }

    private static boolean isCandidateContainerSlot(Slot slot)
    {
        return (slot.container instanceof Inventory) == false &&
               (slot.container instanceof ResultContainer) == false;
    }

    private static void refreshOpenContainerPreviewIfChanged()
    {
        if (boundContainer == null || boundPos == null)
        {
            return;
        }

        int signature = inventorySignature(boundContainer);

        if (signature != boundContainerSignature)
        {
            boundPreview = createOpenContainerPreview(boundPos, boundContainer, boundExpected);
            boundContainerSignature = signature;
        }
    }

    private static int inventorySignature(Container container)
    {
        int hash = 1;
        hash = 31 * hash + container.getContainerSize();

        for (int i = 0; i < container.getContainerSize(); ++i)
        {
            ItemStack stack = container.getItem(i);

            if (stack.isEmpty())
            {
                hash = 31 * hash;
                hash = 31 * hash;
            }
            else
            {
                hash = 31 * hash + System.identityHashCode(stack.getItem());
                hash = 31 * hash + stack.getCount();
            }
        }

        return hash;
    }

    private static void renderSide(GuiContext ctx, VerifierInventoryPreview preview, @Nullable VerifierInventorySide side, SideMetrics metrics,
                                   boolean colorSlots, int x, int y, int mouseX, int mouseY)
    {
        Container inventory = side != null ? side.inventory() : new SimpleContainer(metrics.totalSlots());
        InventoryOverlayType type = side != null ? side.type() : InventoryOverlayType.GENERIC;
        Set<Integer> disabledSlots = side != null ? side.disabledSlots() : Set.of();

        InventoryOverlay.renderInventoryBackground(ctx, type, x, y, metrics.slotsPerRow(), metrics.totalSlots());
        infoOverlayInstance = new VerifierInventoryOverlay(preview, colorSlots);
        InventoryOverlay.renderInventoryStacks(ctx, type, inventory, x + metrics.slotOffsetX(), y + metrics.slotOffsetY(),
                metrics.slotsPerRow(), 0, inventory.getContainerSize(), disabledSlots, mouseX, mouseY);
        infoOverlayInstance = null;
    }

    private record SideMetrics(int width, int height, int slotsPerRow, int slotOffsetX, int slotOffsetY, int totalSlots)
    {
        private static SideMetrics of(@Nullable VerifierInventorySide side)
        {
            if (side == null)
            {
                InventoryProperties props = InventoryOverlay.getInventoryPropsTemp(InventoryOverlayType.GENERIC, 0);
                return new SideMetrics(props.width, props.height, props.slotsPerRow, props.slotOffsetX, props.slotOffsetY, props.totalSlots);
            }

            InventoryProperties props = side.properties();
            return new SideMetrics(props.width, props.height, props.slotsPerRow, props.slotOffsetX, props.slotOffsetY, props.totalSlots);
        }
    }
}
