package me.zly2006.lvc.task;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import fi.dy.masa.litematica.scheduler.ITask;
import fi.dy.masa.litematica.scheduler.TaskScheduler;

public final class LvcTaskScheduling
{
    private LvcTaskScheduling()
    {
    }

    public static void scheduleForWorld(Level world, ITask task)
    {
        TaskScheduler scheduler = world instanceof ServerLevel ? TaskScheduler.getInstanceServer() : TaskScheduler.getInstanceClient();
        scheduler.scheduleTask(task, 1);
    }

    public static void scheduleClient(ITask task)
    {
        TaskScheduler.getInstanceClient().scheduleTask(task, 1);
    }
}
