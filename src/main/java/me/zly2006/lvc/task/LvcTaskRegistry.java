package me.zly2006.lvc.task;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import me.zly2006.lvc.LvcDiagnostics;
import fi.dy.masa.litematica.scheduler.TaskScheduler;

public final class LvcTaskRegistry
{
    private static LvcOperationHandle activeOperation;
    private static Runnable activeAbortCleanup;

    private LvcTaskRegistry()
    {
    }

    public static synchronized Optional<LvcOperationHandle> tryAcquire(String name, Path repositoryDirectory)
    {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (activeOperation != null)
        {
            return Optional.empty();
        }

        activeOperation = new LvcOperationHandle(UUID.randomUUID(), name, repositoryDirectory.toAbsolutePath().normalize());
        activeAbortCleanup = null;
        LvcDiagnostics.operationAcquired(activeOperation);
        return Optional.of(activeOperation);
    }

    public static synchronized void release(LvcOperationHandle handle)
    {
        Objects.requireNonNull(handle, "handle");

        if (activeOperation != null && activeOperation.id().equals(handle.id()))
        {
            LvcDiagnostics.operationReleased(handle);
            activeOperation = null;
            activeAbortCleanup = null;
        }
    }

    public static synchronized boolean isActive(LvcOperationHandle handle)
    {
        Objects.requireNonNull(handle, "handle");
        return activeOperation != null && activeOperation.id().equals(handle.id());
    }

    public static synchronized void setAbortCleanup(LvcOperationHandle handle, Runnable cleanup)
    {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(cleanup, "cleanup");

        if (activeOperation != null && activeOperation.id().equals(handle.id()))
        {
            activeAbortCleanup = cleanup;
        }
    }

    public static void abortActiveOperationForWorldUnload()
    {
        LvcOperationHandle handle;
        Runnable cleanup;

        synchronized (LvcTaskRegistry.class)
        {
            handle = activeOperation;
            cleanup = activeAbortCleanup;
            activeAbortCleanup = null;
        }

        if (handle == null)
        {
            return;
        }

        LvcDiagnostics.debug(handle, "aborting active operation because the world is unloading");

        if (cleanup != null)
        {
            try
            {
                cleanup.run();
            }
            catch (Exception e)
            {
                LvcDiagnostics.debug(handle, "active operation abort cleanup failed: {}", e.getMessage());
            }
        }

        boolean removedClientTask = TaskScheduler.getInstanceClient().removeTasksIf(LvcTaskRegistry::isLvcTask);
        boolean removedServerTask = TaskScheduler.getInstanceServer().removeTasksIf(LvcTaskRegistry::isLvcTask);

        if (!removedClientTask && !removedServerTask)
        {
            release(handle);
        }
    }

    private static boolean isLvcTask(Object task)
    {
        return task instanceof LvcTaskBase<?> || task instanceof LvcAuthoritativeClientSyncTask;
    }

    public static synchronized boolean hasActiveOperation()
    {
        return activeOperation != null;
    }

    public static synchronized String activeOperationName()
    {
        return activeOperation != null ? activeOperation.name() : "";
    }

    public static synchronized Optional<LvcOperationHandle> activeOperation()
    {
        return Optional.ofNullable(activeOperation);
    }
}
