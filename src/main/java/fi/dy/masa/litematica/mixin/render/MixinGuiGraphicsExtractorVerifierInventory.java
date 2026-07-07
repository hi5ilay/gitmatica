package fi.dy.masa.litematica.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import fi.dy.masa.litematica.schematic.verifier.inventory.VerifierInventoryOverlay;

@Mixin(GuiGraphicsExtractor.class)
public class MixinGuiGraphicsExtractorVerifierInventory
{
    @ModifyArg(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V",
               at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addItem(Lnet/minecraft/client/renderer/state/gui/GuiItemRenderState;)V"),
               index = 0)
    private GuiItemRenderState litematica_trackTransparentVerifierItem(GuiItemRenderState original)
    {
        if (VerifierInventoryOverlay.isRenderingTransparentItem)
        {
            VerifierInventoryOverlay.transparentItemStates.add(original);
        }

        return original;
    }

    @WrapMethod(method = "innerFill(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/gui/render/TextureSetup;IIIIILjava/lang/Integer;)V")
    private void litematica_fillTransparentVerifierItem(RenderPipeline pipeline, TextureSetup textureSetup, int x1, int y1, int x2, int y2, int color, Integer color2,
                                                        Operation<Void> original)
    {
        if (VerifierInventoryOverlay.isRenderingTransparentItem)
        {
            color = ARGB.color(Math.round(ARGB.alpha(color) * VerifierInventoryOverlay.MISSING_ITEM_ALPHA), color);

            if (color2 != null)
            {
                color2 = ARGB.color(Math.round(ARGB.alpha(color2) * VerifierInventoryOverlay.MISSING_ITEM_ALPHA), color2);
            }
        }

        original.call(pipeline, textureSetup, x1, y1, x2, y2, color, color2);
    }

    @WrapMethod(method = "innerBlit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;IIIIFFFFI)V")
    private void litematica_blitTransparentVerifierItem(RenderPipeline pipeline, GpuTextureView atlasTexture, GpuSampler sampler,
                                                        int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1, int color,
                                                        Operation<Void> original)
    {
        if (VerifierInventoryOverlay.isRenderingTransparentItem)
        {
            if (pipeline.getColorTargetState().blendFunction().isPresent() &&
                pipeline.getColorTargetState().blendFunction().get().destAlpha() == DestFactor.ZERO)
            {
                color = ARGB.scaleRGB(color, VerifierInventoryOverlay.MISSING_ITEM_ALPHA);
            }
            else
            {
                color = ARGB.color(Math.round(ARGB.alpha(color) * VerifierInventoryOverlay.MISSING_ITEM_ALPHA), color);
            }
        }

        original.call(pipeline, atlasTexture, sampler, x0, y0, x1, y1, u0, u1, v0, v1, color);
    }

    @WrapMethod(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V")
    private void litematica_textTransparentVerifierItem(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow,
                                                        Operation<Void> original)
    {
        if (VerifierInventoryOverlay.isRenderingTransparentItem)
        {
            color = ARGB.color(Math.round(ARGB.alpha(color) * VerifierInventoryOverlay.MISSING_ITEM_ALPHA), color);
        }

        original.call(font, text, x, y, color, shadow);
    }
}
