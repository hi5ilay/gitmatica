package me.zly2006.lvc.task;

import java.util.Objects;
import java.util.function.Consumer;

public record LvcTaskCallbacks<T>(Consumer<T> success, Consumer<Exception> failure, Runnable aborted)
{
    public LvcTaskCallbacks
    {
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(aborted, "aborted");
    }

    public static <T> LvcTaskCallbacks<T> of(Consumer<T> success, Consumer<Exception> failure, Runnable aborted)
    {
        return new LvcTaskCallbacks<>(success, failure, aborted);
    }
}
