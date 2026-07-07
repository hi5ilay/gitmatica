package fi.dy.masa.litematica.mixin.world;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import me.zly2006.lvc.world.LvcWorldFreezeService;

@Mixin(LevelTicks.class)
public abstract class MixinLevelTicks<T>
{
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void lvc_suppressFrozenScheduledTick(ScheduledTick<T> tick, CallbackInfo ci)
    {
        if (LvcWorldFreezeService.suppressScheduledTick((LevelTicks<T>) (Object) this, tick))
        {
            ci.cancel();
        }
    }
}
