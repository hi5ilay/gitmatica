package fi.dy.masa.litematica.schematic.verifier.inventory;

import fi.dy.masa.malilib.util.data.Color4f;

public enum VerifierInventorySlotDiff
{
    MATCH(0, null),
    ADDED(0x8030FF30, Color4f.fromColor(0x30FF30, 1f)),
    REMOVED(0x80FF3030, Color4f.fromColor(0xFF3030, 1f)),
    CHANGED(0x80FFFF30, Color4f.fromColor(0xFFFF30, 1f));

    private final int overlayColor;
    private final Color4f renderColor;

    VerifierInventorySlotDiff(int overlayColor, Color4f renderColor)
    {
        this.overlayColor = overlayColor;
        this.renderColor = renderColor;
    }

    public int getOverlayColor()
    {
        return this.overlayColor;
    }

    public Color4f getRenderColor()
    {
        return this.renderColor;
    }
}
