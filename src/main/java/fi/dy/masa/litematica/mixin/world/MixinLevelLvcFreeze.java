package fi.dy.masa.litematica.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import me.zly2006.lvc.world.LvcWorldFreezeService;

@Mixin(Level.class)
public abstract class MixinLevelLvcFreeze
{
    @WrapOperation(method = "tickBlockEntities", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void lvc_skipFrozenBlockEntityTick(TickingBlockEntity ticker, Operation<Void> original)
    {
        if (LvcWorldFreezeService.shouldSkipBlockEntityTick((Level) (Object) this, ticker) == false)
        {
            original.call(ticker);
        }
    }
}
