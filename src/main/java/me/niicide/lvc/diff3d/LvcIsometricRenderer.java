package me.niicide.lvc.diff3d;

import java.util.Comparator;
import java.util.List;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;

public final class LvcIsometricRenderer
{
    private LvcIsometricRenderer()
    {
    }

    public static void render(GuiContext ctx,
                              List<LvcDiff3DBlock> blocks,
                              int centerX, int centerY,
                              int scale,
                              float slabPosition,
                              float slabThickness,
                              float explodeFactor,
                              boolean twoPlane,
                              boolean showUnchanged,
                              int buildHeight)
    {
        if (blocks.isEmpty()) return;

        int s = Math.max(1, scale);

        // Sort back-to-front: painter's algorithm for isometric view (x-z ascending, y ascending)
        List<LvcDiff3DBlock> sorted = blocks.stream()
                .filter(b -> passesSlabFilter(b, slabPosition, slabThickness, twoPlane, buildHeight, showUnchanged))
                .sorted(Comparator.comparingInt(b -> isoDepth(b)))
                .toList();

        for (LvcDiff3DBlock block : sorted)
        {
            float explodeOffset = block.y() * explodeFactor * s * 0.5f;

            int sx = centerX + (block.x() - block.z()) * s;
            int sy = centerY - (int) ((block.x() + block.z()) * s * 0.5f) - block.y() * s - (int) explodeOffset;

            drawIsoCube(ctx, sx, sy, s, block.color(), block.alpha());
        }
    }

    private static boolean passesSlabFilter(LvcDiff3DBlock block, float slabPos, float slabThick,
                                            boolean twoPlane, int buildHeight, boolean showUnchanged)
    {
        if (!showUnchanged && block.status() == LvcDiff3DBlock.Status.UNCHANGED)
        {
            return false;
        }

        if (slabThick >= 1.0f)
        {
            return true;
        }

        float normalizedY = buildHeight > 0 ? (float) (block.y() + buildHeight / 2) / buildHeight : 0.5f;
        float halfThick = slabThick * 0.5f;
        float dist = Math.abs(normalizedY - slabPos);

        if (twoPlane)
        {
            return dist <= halfThick || (1.0f - dist) <= halfThick;
        }
        else
        {
            return dist <= halfThick;
        }
    }

    private static int isoDepth(LvcDiff3DBlock b)
    {
        return b.x() - b.z() + b.y() * 100;
    }

    private static void drawIsoCube(GuiContext ctx, int sx, int sy, int s, int rgb, float alpha)
    {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        int topColor   = argb(a, tint(rgb, 1.20f));
        int leftColor  = argb(a, tint(rgb, 0.65f));
        int rightColor = argb(a, tint(rgb, 0.80f));

        // TOP face: diamond with:
        //   top point   (sx, sy - s)
        //   right point (sx + s, sy - s/2)
        //   bottom point(sx, sy)
        //   left point  (sx - s, sy - s/2)
        for (int i = 0; i <= s; i++)
        {
            int rowY = sy - s + i;
            int halfWidth;
            int leftX;

            if (i <= s / 2)
            {
                halfWidth = 2 * i;
            }
            else
            {
                halfWidth = 2 * (s - i);
            }

            leftX = sx - halfWidth;
            int width = halfWidth * 2 + 1;

            if (width > 0)
            {
                RenderUtils.drawRect(ctx, leftX, rowY, width, 1, topColor);
            }
        }

        // LEFT face: parallelogram
        //   top-left  (sx - s, sy - s/2)
        //   top-right (sx, sy)
        //   bot-right (sx, sy + s/2)
        //   bot-left  (sx - s, sy)
        int halfS = s / 2;

        for (int i = 0; i <= halfS; i++)
        {
            int rowY = sy + i;
            int leftX = sx - s + i * 2;
            int width = s - i * 2;

            if (width > 0)
            {
                RenderUtils.drawRect(ctx, leftX, rowY, width, 1, leftColor);
            }
        }

        // RIGHT face: parallelogram
        //   top-left  (sx, sy)
        //   top-right (sx + s, sy - s/2)
        //   bot-right (sx + s, sy)
        //   bot-left  (sx, sy + s/2)
        for (int i = 0; i <= halfS; i++)
        {
            int rowY = sy + i;
            int rightX = sx + s - i * 2;
            int width = s - i * 2;

            if (width > 0)
            {
                RenderUtils.drawRect(ctx, sx, rowY, width, 1, rightColor);
            }
        }
    }

    private static int argb(int alpha, int rgb)
    {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static int tint(int rgb, float factor)
    {
        int r = Math.min(255, Math.max(0, (int) (((rgb >> 16) & 0xFF) * factor)));
        int g = Math.min(255, Math.max(0, (int) (((rgb >> 8)  & 0xFF) * factor)));
        int b = Math.min(255, Math.max(0, (int) (( rgb        & 0xFF) * factor)));
        return (r << 16) | (g << 8) | b;
    }
}
