package me.zly2006.lvc.task;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.ProfilerFiller;

public abstract class LvcChunkedTaskBase<T> extends LvcTaskBase<T>
{
    protected static final long LITEMATICA_VERIFIER_BUDGET_NANOS = 50_000_000L;
    protected static final long LITEMATICA_DIRECT_PASTE_BUDGET_NANOS = 60_000_000L;

    // Keep world-writing tasks conservative; read-only scans can opt into the verifier-sized tick window.
    private static final long DEFAULT_BUDGET_NANOS = 25_000_000L;
    private final long budgetNanos;

    protected LvcChunkedTaskBase(LvcOperationHandle handle, String displayName, LvcTaskCallbacks<T> callbacks,
                                 boolean releaseLockOnSuccess)
    {
        this(handle, displayName, callbacks, releaseLockOnSuccess, DEFAULT_BUDGET_NANOS);
    }

    protected LvcChunkedTaskBase(LvcOperationHandle handle, String displayName, LvcTaskCallbacks<T> callbacks,
                                 boolean releaseLockOnSuccess, long budgetNanos)
    {
        super(handle, displayName, callbacks, releaseLockOnSuccess);
        this.budgetNanos = budgetNanos;
    }

    @Override
    public final boolean execute(ProfilerFiller profiler)
    {
        long deadline = this.executionDeadlineNanos();

        if (Util.getNanos() >= deadline)
        {
            this.updateProgressHud();
            return false;
        }

        try
        {
            do
            {
                if (this.step())
                {
                    return this.complete(this.result());
                }
            }
            while (Util.getNanos() < deadline && this.shouldContinueWithinTick());

            this.updateProgressHud();
            return false;
        }
        catch (Exception e)
        {
            return this.fail(e);
        }
    }

    protected boolean shouldContinueWithinTick()
    {
        return true;
    }

    protected long executionDeadlineNanos()
    {
        return Util.getNanos() + this.budgetNanos;
    }

    protected static long serverTickAwareDeadlineNanos(ServerLevel world, long maxTotalTickNanos)
    {
        long now = Util.getNanos();
        long vanillaTickTime = world.getServer().getTickTimesNanos()[world.getServer().getTickCount() % 100];
        return now + Math.max(0L, maxTotalTickNanos - vanillaTickTime);
    }

    protected void updateProgressHud()
    {
    }

    protected abstract boolean step() throws Exception;

    protected abstract T result() throws Exception;
}
