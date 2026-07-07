package fi.dy.masa.litematica.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;

@Mixin(ServerLevel.class)
public interface IMixinServerLevel
{
    @Accessor("blockEvents")
    ObjectLinkedOpenHashSet<BlockEventData> lvc_getBlockEvents();
}
