package me.zly2006.lvc;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import me.zly2006.lvc.task.LvcOperationHandle;

public final class LvcDiagnostics
{
    public static final String LOGGER_NAME = "gitmatica-lvc";
    public static final String DEBUG_PROPERTY = "gitmatica.lvc.debug";
    public static final String DEBUG_ENV = "GITMATICA_LVC_DEBUG";

    private static final Logger LOGGER = LogManager.getLogger(LOGGER_NAME);
    @Nullable private static Boolean debugOverride;

    private LvcDiagnostics()
    {
    }

    public static boolean isDebugEnabled()
    {
        Boolean override = debugOverride;

        if (override != null)
        {
            return override;
        }

        return Boolean.getBoolean(DEBUG_PROPERTY) || isTruthy(System.getenv(DEBUG_ENV));
    }

    public static void setDebugOverride(@Nullable Boolean enabled)
    {
        debugOverride = enabled;
    }

    public static void debug(String message, Object... args)
    {
        if (isDebugEnabled())
        {
            LOGGER.debug(message, args);
        }
    }

    public static void debug(LvcOperationHandle handle, String message, Object... args)
    {
        if (!isDebugEnabled())
        {
            return;
        }

        Object[] taggedArgs = new Object[args.length + 1];
        taggedArgs[0] = operationTag(handle);
        System.arraycopy(args, 0, taggedArgs, 1, args.length);
        LOGGER.debug("{} " + message, taggedArgs);
    }

    public static void info(String message, Object... args)
    {
        LOGGER.info(message, args);
    }

    public static void info(LvcOperationHandle handle, String message, Object... args)
    {
        Object[] taggedArgs = new Object[args.length + 1];
        taggedArgs[0] = operationTag(handle);
        System.arraycopy(args, 0, taggedArgs, 1, args.length);
        LOGGER.info("{} " + message, taggedArgs);
    }

    public static void warn(String message, Object... args)
    {
        LOGGER.warn(message, args);
    }

    public static void error(String message, Object... args)
    {
        LOGGER.error(message, args);
    }

    public static void operationAcquired(LvcOperationHandle handle)
    {
        debug("operation acquired {}", operationTag(handle));
    }

    public static void operationReleased(LvcOperationHandle handle)
    {
        debug("operation released {}", operationTag(handle));
    }

    public static void taskCreated(LvcOperationHandle handle, String taskName)
    {
        debug("task created {} task={}", operationTag(handle), taskName);
    }

    public static void taskCompleted(LvcOperationHandle handle, String taskName)
    {
        debug("task completed {} task={}", operationTag(handle), taskName);
    }

    public static void taskAborted(LvcOperationHandle handle, String taskName)
    {
        debug("task aborted {} task={}", operationTag(handle), taskName);
    }

    public static void taskFailed(LvcOperationHandle handle, String taskName, Exception failure)
    {
        LOGGER.error("task failed {} task={}", operationTag(handle), taskName, failure);
    }

    public static String operationTag(LvcOperationHandle handle)
    {
        Objects.requireNonNull(handle, "handle");
        return "op=" + shortId(handle.id()) + " name=\"" + handle.name() + "\" repo=\"" + normalize(handle.repositoryDirectory()) + "\"";
    }

    private static String shortId(UUID id)
    {
        return id.toString().substring(0, 8);
    }

    private static String normalize(Path path)
    {
        return path.toAbsolutePath().normalize().toString();
    }

    private static boolean isTruthy(@Nullable String value)
    {
        if (value == null)
        {
            return false;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT))
        {
            case "1", "true", "yes", "y", "on" -> true;
            default -> false;
        };
    }
}
