package me.zly2006.lvc.gui;

final class LvcScrollbarMath
{
    private LvcScrollbarMath()
    {
    }

    static int offsetFromMouseY(int mouseY, int trackY, int trackHeight, int thumbHeight, int maxScroll)
    {
        int travel = trackHeight - thumbHeight;

        if (maxScroll <= 0 || travel <= 0)
        {
            return 0;
        }

        int relativeY = Math.clamp(mouseY - trackY - thumbHeight / 2, 0, travel);
        return Math.round(relativeY * maxScroll / (float) travel);
    }
}
