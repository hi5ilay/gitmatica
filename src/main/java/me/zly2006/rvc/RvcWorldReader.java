package me.zly2006.rvc;

import java.io.IOException;
import javax.annotation.Nullable;

public interface RvcWorldReader
{
    default boolean canReadAt(RvcIntPosition worldPos)
    {
        return true;
    }

    String blockStateAt(RvcIntPosition worldPos) throws IOException;

    @Nullable
    default byte[] blockEntityNbtAt(RvcIntPosition worldPos) throws IOException
    {
        return null;
    }
}
