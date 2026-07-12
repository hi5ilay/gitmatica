package me.niicide.lvc.diff3d;

public record LvcDiff3DBlock(int x, int y, int z, Status status, String blockState)
{
    public enum Status
    {
        ADDED,
        REMOVED,
        CHANGED,
        UNCHANGED
    }

    public int color()
    {
        return switch (this.status)
        {
            case ADDED     -> 0x33CC33;
            case REMOVED   -> 0xFF3333;
            case CHANGED   -> 0xFF9010;
            case UNCHANGED -> 0x888888;
        };
    }

    public float alpha()
    {
        return this.status == Status.UNCHANGED ? 0.18f : 0.85f;
    }
}
