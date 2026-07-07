package me.zly2006.lvc.capture;

import java.io.IOException;
import javax.annotation.Nullable;
import me.zly2006.lvc.model.LvcIntPosition;

public interface LvcWorldReader
{
    default boolean canReadAt(LvcIntPosition worldPos)
    {
        return true;
    }

    String blockStateAt(LvcIntPosition worldPos) throws IOException;

    @Nullable
    default byte[] blockEntityNbtAt(LvcIntPosition worldPos) throws IOException
    {
        return null;
    }
}
