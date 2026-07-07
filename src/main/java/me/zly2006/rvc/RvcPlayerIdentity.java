package me.zly2006.rvc;

import java.util.Objects;
import java.util.UUID;
import org.eclipse.jgit.lib.PersonIdent;

public record RvcPlayerIdentity(String name, UUID uuid) {
    public RvcPlayerIdentity(String name, UUID uuid) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }

        this.name = name;
        this.uuid = Objects.requireNonNull(uuid, "uuid");
    }

    public String email() {
        return this.uuid + "@minecraft";
    }

    PersonIdent toPersonIdent() {
        return new PersonIdent(this.name, this.email());
    }
}
