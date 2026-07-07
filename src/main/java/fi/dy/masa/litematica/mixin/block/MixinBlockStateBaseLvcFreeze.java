package fi.dy.masa.litematica.mixin.block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockBehaviour;
import me.zly2006.lvc.world.LvcWorldFreezeService;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class MixinBlockStateBaseLvcFreeze
{
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void lvc_skipFrozenRandomBlockTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci)
    {
        if (LvcWorldFreezeService.shouldSkipRandomTick(level, pos))
        {
            ci.cancel();
        }
    }
}
