package me.zly2006.lvc.gui;

import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.task.LvcOperationHandle;
import me.zly2006.lvc.task.LvcSemanticCheckoutTask;
import me.zly2006.lvc.task.LvcTaskRegistry;
import me.zly2006.lvc.task.LvcTaskScheduling;
import fi.dy.masa.litematica.scheduler.ITask;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.interfaces.IConfirmationListener;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;

final class LvcOperationCoordinator
{
    private LvcOperationCoordinator()
    {
    }

    static Optional<LvcOperationHandle> acquire(GuiLvcProjectController controller, String name)
    {
        Path repositoryDirectory = controller.gui.repositoryDirectory;
        Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquire(name, repositoryDirectory);

        if (handle.isEmpty())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
        }
        else
        {
            controller.focusTrackingOverlay();
            LvcDiagnostics.debug("LVC operation acquired name='{}' repo='{}'", name, repositoryDirectory);
        }

        return handle;
    }

    static void scheduleStarted(Level world, ITask task, String taskName)
    {
        LvcTaskScheduling.scheduleForWorld(world, task);
        LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_started", taskName);
    }

    static String regionCountText(int regionCount)
    {
        return StringUtils.translate(regionCount == 1
                ? "litematica.gui.label.lvc_project.region_count_one"
                : "litematica.gui.label.lvc_project.region_count_many",
                regionCount);
    }

    static Screen confirmParent(GuiLvcProjectController controller)
    {
        Screen currentScreen = GuiUtils.getCurrentScreen();
        Screen parent = currentScreen == controller.gui ? controller.gui : currentScreen;
        LvcDiagnostics.debug("LVC confirm parent selected current={} parent={} projectOpen={}",
                screenName(currentScreen), screenName(parent), currentScreen == controller.gui);
        return parent;
    }

    private static String screenName(Screen screen)
    {
        return screen == null ? "<none>" : screen.getClass().getSimpleName();
    }

    record HeldLockConfirmListener(LvcOperationHandle handle, Runnable confirmed) implements IConfirmationListener
    {
        @Override
        public boolean onActionConfirmed()
        {
            this.confirmed.run();
            return true;
        }

        @Override
        public boolean onActionCancelled()
        {
            LvcTaskRegistry.release(this.handle);
            return true;
        }
    }

    record HeldPreparedCheckoutConfirmListener(LvcOperationHandle handle,
                                               LvcSemanticCheckoutTask.PreparedCheckout prepared,
                                               Runnable confirmed,
                                               Runnable cancelled) implements IConfirmationListener
    {
        HeldPreparedCheckoutConfirmListener
        {
            LvcTaskRegistry.setAbortCleanup(handle, prepared::close);
        }

        HeldPreparedCheckoutConfirmListener(LvcOperationHandle handle,
                                            LvcSemanticCheckoutTask.PreparedCheckout prepared,
                                            Runnable confirmed)
        {
            this(handle, prepared, confirmed, () -> { });
        }

        @Override
        public boolean onActionConfirmed()
        {
            if (!LvcTaskRegistry.isActive(this.handle))
            {
                this.prepared.close();
                this.cancelled.run();
                return true;
            }

            this.confirmed.run();
            return true;
        }

        @Override
        public boolean onActionCancelled()
        {
            this.prepared.close();
            LvcTaskRegistry.release(this.handle);
            this.cancelled.run();
            return true;
        }
    }

    record ConfirmListener(Runnable confirmed) implements IConfirmationListener
    {
        @Override
        public boolean onActionConfirmed()
        {
            this.confirmed.run();
            return true;
        }

        @Override
        public boolean onActionCancelled()
        {
            return true;
        }
    }
}
