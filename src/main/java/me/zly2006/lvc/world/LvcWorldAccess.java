package me.zly2006.lvc.world;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcUserActionException;

public final class LvcWorldAccess
{
    private LvcWorldAccess()
    {
    }

    public static Level resolveSemanticCaptureWorld(Level currentWorld)
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (!minecraft.hasSingleplayerServer())
        {
            return currentWorld;
        }

        MinecraftServer server = minecraft.getSingleplayerServer();

        if (server == null)
        {
            return currentWorld;
        }

        ServerLevel serverLevel = server.getLevel(currentWorld.dimension());
        return serverLevel != null ? serverLevel : currentWorld;
    }

    public static Level resolveSemanticRestoreWorld(Level currentWorld) throws IOException
    {
        if (currentWorld instanceof ServerLevel)
        {
            return currentWorld;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (!minecraft.hasSingleplayerServer())
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.NO_AUTHORITATIVE_WORLD,
                    "Semantic LVC world writes require singleplayer or integrated-server authority");
        }

        MinecraftServer server = minecraft.getSingleplayerServer();

        if (server == null)
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.NO_AUTHORITATIVE_WORLD,
                    "Semantic LVC world writes require an integrated server");
        }

        ServerLevel serverLevel = server.getLevel(currentWorld.dimension());

        if (serverLevel == null)
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.NO_AUTHORITATIVE_WORLD,
                    "Current world dimension is not available on the integrated server");
        }

        return serverLevel;
    }

    public static <T> T runOnSemanticCaptureWorld(Level captureWorld, SemanticWorldAction<T> action) throws Exception
    {
        if (captureWorld instanceof ServerLevel serverLevel)
        {
            MinecraftServer server = serverLevel.getServer();

            if (!server.isSameThread())
            {
                try
                {
                    return server.submit(() ->
                    {
                        try
                        {
                            return action.run(serverLevel);
                        }
                        catch (Exception e)
                        {
                            throw new CompletionException(e);
                        }
                    }).get();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for server-authoritative LVC capture", e);
                }
                catch (ExecutionException e)
                {
                    throw unwrapSemanticCaptureException(e.getCause());
                }
            }
        }

        return action.run(captureWorld);
    }

    private static Exception unwrapSemanticCaptureException(Throwable throwable)
    {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;

        if (cause instanceof RuntimeException e)
        {
            throw e;
        }

        if (cause instanceof Error e)
        {
            throw e;
        }

        if (cause instanceof Exception e)
        {
            return e;
        }

        return new IOException("Server-authoritative LVC capture failed", cause);
    }

    @FunctionalInterface
    public interface SemanticWorldAction<T>
    {
        T run(Level captureWorld) throws Exception;
    }
}
