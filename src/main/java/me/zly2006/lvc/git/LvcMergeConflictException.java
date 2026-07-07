package me.zly2006.lvc.git;

import java.io.IOException;

public class LvcMergeConflictException extends IOException
{
    public enum Reason
    {
        UNKNOWN,
        NO_MERGE_BASE,
        MANIFEST_METADATA,
        SITE_ADD,
        SITE_DELETE,
        CHUNK_ADD,
        CHUNK_DELETE,
        CHUNK_SHAPE,
        BLOCK_PAYLOAD
    }

    private final Reason reason;

    public LvcMergeConflictException(String message)
    {
        this(Reason.UNKNOWN, message);
    }

    public LvcMergeConflictException(Reason reason, String message)
    {
        super(message);
        this.reason = reason;
    }

    public Reason reason()
    {
        return this.reason;
    }
}
