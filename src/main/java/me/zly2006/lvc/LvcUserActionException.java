package me.zly2006.lvc;

import java.io.IOException;
import java.util.Objects;

public class LvcUserActionException extends IOException
{
    private final Reason reason;

    public LvcUserActionException(Reason reason, String message)
    {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public LvcUserActionException(Reason reason, String message, Throwable cause)
    {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason()
    {
        return this.reason;
    }

    public enum Reason
    {
        TRACKED_CHUNK_UNLOADED,
        TRACKED_CHUNK_UNREADABLE,
        OUT_OF_WORLD_BOUNDS,
        WRONG_DIMENSION,
        NO_AUTHORITATIVE_WORLD,
        MISSING_LOCAL_PLACEMENT,
        MISSING_HEAD
    }
}
