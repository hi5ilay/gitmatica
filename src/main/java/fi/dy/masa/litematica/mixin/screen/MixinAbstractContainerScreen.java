package fi.dy.masa.litematica.mixin.screen;

import javax.annotation.Nullable;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fi.dy.masa.litematica.materials.MaterialListHudRenderer;
import fi.dy.masa.litematica.schematic.verifier.inventory.VerifierInventoryOverlay;
import fi.dy.masa.malilib.render.GuiContext;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

@Mixin(AbstractContainerScreen.class)
public abstract class MixinAbstractContainerScreen extends Screen
{
    @Shadow @Nullable protected Slot hoveredSlot;

    private MixinAbstractContainerScreen(Component title)
    {
        super(title);
    }

    @ModifyExpressionValue(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;", ordinal = 0))
    private ItemStack litematica_inventoryDiffSlotOverlay(ItemStack stack, @Local(argsOnly = true) GuiGraphicsExtractor graphics, @Local(argsOnly = true) Slot slot)
    {
        return VerifierInventoryOverlay.drawScreenStack(GuiContext.fromGuiGraphics(graphics), slot, stack);
    }

    @Inject(method = "extractSlot", at = @At("RETURN"))
    private void litematica_inventoryDiffSlotOverlayPost(CallbackInfo ci)
    {
        VerifierInventoryOverlay.finalizeDrawStack();
    }

    @ModifyExpressionValue(method = "extractTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;hasItem()Z"))
    private boolean litematica_inventoryDiffGhostTooltip(boolean hasStack, @Share("prevItem") LocalRef<ItemStack> previousItem)
    {
        if (this.hoveredSlot == null)
        {
            return hasStack;
        }

        ItemStack prevItem = this.hoveredSlot.getItem();

        if (hasStack == false && VerifierInventoryOverlay.setSlotToExpectedItem(this.hoveredSlot))
        {
            hasStack = this.hoveredSlot.hasItem();

            if (hasStack)
            {
                previousItem.set(prevItem);
            }
            else
            {
                this.hoveredSlot.setByPlayer(prevItem);
            }
        }

        return hasStack;
    }

    @Inject(method = "extractTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;", shift = At.Shift.AFTER))
    private void litematica_inventoryDiffRestoreGhostTooltip(CallbackInfo ci, @Share("prevItem") LocalRef<ItemStack> previousItem)
    {
        ItemStack prevItem = previousItem.get();

        if (prevItem != null && this.hoveredSlot != null)
        {
            this.hoveredSlot.setByPlayer(prevItem);
        }
    }

    @Inject(method = "extractContents",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;getHoveredSlot(DD)Lnet/minecraft/world/inventory/Slot;",
                     shift = At.Shift.AFTER)
    )
    private void litematica_renderSlotHighlightsPre(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci)
    {
        MaterialListHudRenderer.renderLookedAtBlockInInventory(GuiContext.fromGuiGraphics(graphics), (AbstractContainerScreen<?>) (Object) this, this.minecraft);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void litematica_renderSlotHighlightsPost(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci)
    {
        MaterialListHudRenderer.renderLookedAtBlockInInventory(GuiContext.fromGuiGraphics(graphics), (AbstractContainerScreen<?>) (Object) this, this.minecraft);
    }
}
