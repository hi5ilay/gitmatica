package me.zly2006.lvc;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

public final class LvcFriendlyErrors
{
    private static final String KEY_CHUNKS_UNLOADED = "litematica.error.lvc_project.friendly_chunks_unloaded";
    private static final String KEY_CHUNKS_INTERRUPTED = "litematica.error.lvc_project.friendly_chunks_interrupted";
    private static final String KEY_OUT_OF_WORLD_BOUNDS = "litematica.error.lvc_project.friendly_out_of_world_bounds";
    private static final String KEY_WRONG_DIMENSION = "litematica.error.lvc_project.friendly_wrong_dimension";
    private static final String KEY_NO_AUTHORITATIVE_WORLD = "litematica.error.lvc_project.friendly_no_authoritative_world";
    private static final String KEY_MISSING_LOCAL_PLACEMENT = "litematica.error.lvc_project.friendly_missing_local_placement";
    private static final String KEY_MISSING_HEAD = "litematica.error.lvc_project.friendly_missing_head";
    private static final String KEY_UNEXPECTED = "litematica.error.lvc_project.friendly_unexpected";

    private LvcFriendlyErrors()
    {
    }

    public static FriendlyMessage message(Operation operation, Exception error)
    {
        return message(operation, error, false);
    }

    public static FriendlyMessage message(Operation operation, Exception error, boolean mayNeedRecovery)
    {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(error, "error");

        LvcUserActionException.Reason reason = findReason(error);

        if (reason == null)
        {
            return new FriendlyMessage(KEY_UNEXPECTED, new Object[] { operation.displayName() }, null, false);
        }

        return switch (reason)
        {
            case TRACKED_CHUNK_UNLOADED, TRACKED_CHUNK_UNREADABLE -> new FriendlyMessage(
                    mayNeedRecovery ? KEY_CHUNKS_INTERRUPTED : KEY_CHUNKS_UNLOADED,
                    new Object[] { operation.displayName() },
                    reason,
                    true);
            case OUT_OF_WORLD_BOUNDS -> new FriendlyMessage(KEY_OUT_OF_WORLD_BOUNDS, new Object[0], reason, true);
            case WRONG_DIMENSION -> new FriendlyMessage(KEY_WRONG_DIMENSION, new Object[0], reason, true);
            case NO_AUTHORITATIVE_WORLD -> new FriendlyMessage(KEY_NO_AUTHORITATIVE_WORLD, new Object[0], reason, true);
            case MISSING_LOCAL_PLACEMENT -> new FriendlyMessage(KEY_MISSING_LOCAL_PLACEMENT, new Object[0], reason, true);
            case MISSING_HEAD -> new FriendlyMessage(KEY_MISSING_HEAD, new Object[0], reason, true);
        };
    }

    @Nullable
    public static LvcUserActionException.Reason findReason(Throwable throwable)
    {
        Set<Throwable> seen = new HashSet<>();
        Throwable current = throwable;

        while (current != null && seen.add(current))
        {
            if (current instanceof LvcUserActionException userActionException)
            {
                return userActionException.reason();
            }

            current = current.getCause();
        }

        return null;
    }

    public enum Operation
    {
        SCAN_CHANGES("Scan Changes"),
        MERGE_BRANCH("Merge Branch"),
        RECOVERY("Recovery"),
        CLEAR_AREA("Clear Area"),
        DISCARD_CHANGES("Discard Changes"),
        SAVE_VERSION("Save Version"),
        UPDATE_AREAS("Update Areas"),
        START_VERIFICATION("Start Verification"),
        CHECKOUT("Checkout"),
        CHECKOUT_BRANCH("Checkout Branch"),
        DELETE_VERSION("Delete Version"),
        LOAD_OVERLAY("Load Overlay"),
        PROJECT_BROWSER("Project Browser");

        private final String displayName;

        Operation(String displayName)
        {
            this.displayName = displayName;
        }

        public String displayName()
        {
            return this.displayName;
        }
    }

    public record FriendlyMessage(String translationKey, Object[] args, @Nullable LvcUserActionException.Reason reason,
                                  boolean expected)
    {
        public FriendlyMessage
        {
            Objects.requireNonNull(translationKey, "translationKey");
            args = args.clone();
        }

        @Override
        public Object[] args()
        {
            return this.args.clone();
        }
    }
}
