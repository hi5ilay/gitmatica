package fi.dy.masa.litematica.mixin.world;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FluidState;
import me.zly2006.lvc.world.LvcWorldFreezeService;

@Mixin(FluidState.class)
public abstract class MixinFluidStateLvcFreeze
{
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void lvc_skipFrozenRandomFluidTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci)
    {
        if (LvcWorldFreezeService.shouldSkipRandomTick(level, pos))
        {
            ci.cancel();
        }
    }
}
