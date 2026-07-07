package fi.dy.masa.litematica.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import me.zly2006.lvc.world.LvcWorldFreezeService;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel
{
    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void lvc_suppressFrozenBlockEvent(BlockPos pos, Block block, int paramA, int paramB, CallbackInfo ci)
    {
        if (LvcWorldFreezeService.suppressBlockEvent((ServerLevel) (Object) this, pos, block, paramA, paramB))
        {
            ci.cancel();
        }
    }
}
