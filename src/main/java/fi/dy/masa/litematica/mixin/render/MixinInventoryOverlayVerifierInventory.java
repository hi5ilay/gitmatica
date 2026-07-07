package fi.dy.masa.litematica.mixin.render;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import fi.dy.masa.litematica.schematic.verifier.inventory.VerifierInventoryOverlay;
import fi.dy.masa.malilib.render.GuiContext;

@Mixin(value = fi.dy.masa.malilib.render.InventoryOverlay.class)
public class MixinInventoryOverlayVerifierInventory
{
    @WrapOperation(method = "renderInventoryStacks(Lfi/dy/masa/malilib/render/GuiContext;Lfi/dy/masa/malilib/render/InventoryOverlayType;Lnet/minecraft/world/Container;IIIIILjava/util/Set;DD)V",
                   at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Container;getItem(I)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack litematica_shareVerifierInventorySlot(Container instance, int i, Operation<ItemStack> original, @Share("slotIndex") LocalIntRef slotIndex)
    {
        slotIndex.set(i);
        return original.call(instance, i);
    }

    @WrapOperation(method = "renderInventoryStacks(Lfi/dy/masa/malilib/render/GuiContext;Lfi/dy/masa/malilib/render/InventoryOverlayType;Lnet/minecraft/world/Container;IIIIILjava/util/Set;DD)V",
                   at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/render/InventoryOverlay;renderStackAt(Lfi/dy/masa/malilib/render/GuiContext;Lnet/minecraft/world/item/ItemStack;FFFDD)V"))
    private static void litematica_drawVerifierInventoryOverlay(GuiContext ctx, ItemStack stack, float x, float y, float scale, double mouseX, double mouseY,
                                                                Operation<Void> original, @Share("slotIndex") LocalIntRef slotIndex)
    {
        if (VerifierInventoryOverlay.infoOverlayInstance != null)
        {
            stack = VerifierInventoryOverlay.infoOverlayInstance.drawStackInternal(ctx, new Slot(null, slotIndex.get(), (int) x, (int) y), stack);
            original.call(ctx, stack, x, y, scale, mouseX, mouseY);
            VerifierInventoryOverlay.infoOverlayInstance.finalizeDrawStackInternal();
        }
        else
        {
            original.call(ctx, stack, x, y, scale, mouseX, mouseY);
        }
    }

    @Redirect(method = "renderInventoryStacks(Lfi/dy/masa/malilib/render/GuiContext;Lfi/dy/masa/malilib/render/InventoryOverlayType;Lnet/minecraft/world/Container;IIIIILjava/util/Set;DD)V",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isEmpty()Z"))
    private static boolean litematica_allowVerifierGhostSlots(ItemStack stack)
    {
        return VerifierInventoryOverlay.infoOverlayInstance == null && stack.isEmpty();
    }

    @WrapWithCondition(method = "renderInventoryStacks(Lfi/dy/masa/malilib/render/GuiContext;Lfi/dy/masa/malilib/render/InventoryOverlayType;Lnet/minecraft/world/Container;IIIIILjava/util/Set;DD)V",
                       at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/render/InventoryOverlay;renderStackToolTipStyled(Lfi/dy/masa/malilib/render/GuiContext;IILnet/minecraft/world/item/ItemStack;)V"))
    private static boolean litematica_delayVerifierInventoryTooltip(GuiContext ctx, int x, int y, ItemStack stack)
    {
        if (VerifierInventoryOverlay.delayRenderingHoveredStack)
        {
            VerifierInventoryOverlay.hoveredStackToRender = stack;
            return false;
        }

        return true;
    }

    @Inject(method = "renderInventoryStacks(Lfi/dy/masa/malilib/render/GuiContext;Lfi/dy/masa/malilib/render/InventoryOverlayType;Lnet/minecraft/world/Container;IIIIILjava/util/Set;DD)V", at = @At("RETURN"))
    private static void litematica_cleanVerifierInventoryOverlay(CallbackInfo ci)
    {
        VerifierInventoryOverlay.infoOverlayInstance = null;
    }
}
