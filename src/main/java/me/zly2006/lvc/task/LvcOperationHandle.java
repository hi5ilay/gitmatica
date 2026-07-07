package me.zly2006.lvc.task;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public record LvcOperationHandle(UUID id, String name, Path repositoryDirectory)
{
    public LvcOperationHandle
    {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
    }
}
