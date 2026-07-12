package me.niicide.lvc.gui;

import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.Screen;
import me.niicide.lvc.diff3d.LvcDiff3DBlock;
import me.niicide.lvc.diff3d.LvcDiff3DComputer;
import me.niicide.lvc.diff3d.LvcIsometricRenderer;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiLvcDiff3DViewer extends GuiBase
{
    private static final int VIEWPORT_PADDING = 16;
    private static final int CONTROLS_HEIGHT  = 140;
    private static final int SLIDER_HEIGHT    = 12;
    private static final int SLIDER_TRACK_H  = 4;
    private static final int LEGEND_HEIGHT    = 24;
    private static final int LEGEND_SWATCH    = 10;

    private static final int COLOR_BG         = 0xFF111111;
    private static final int COLOR_VIEWPORT   = 0xFF1A1A2E;
    private static final int COLOR_OUTLINE    = 0xFF404060;
    private static final int COLOR_LABEL      = 0xFFAAAAAA;
    private static final int COLOR_VALUE      = 0xFFFFFFFF;

    private final Path repositoryDirectory;
    private final String commitAId;
    private final String commitBId;
    private final String commitALabel;
    private final String commitBLabel;

    @Nullable private List<LvcDiff3DBlock> blocks;
    private int buildHeight;
    private boolean loading = true;
    @Nullable private String loadError;

    // View state
    private float slabPosition  = 0.50f;
    private float slabThickness = 0.38f;
    private float explode       = 0.0f;
    private boolean twoPlane    = true;
    private boolean showUnchanged = true;

    // Dragging
    private int dragSlider = -1; // 0=slabPos, 1=slabThick, 2=explode
    private int sliderTrackX;
    private int sliderTrackWidth;

    public GuiLvcDiff3DViewer(Path repositoryDirectory,
                              String commitAId, String commitALabel,
                              String commitBId, String commitBLabel,
                              @Nullable Screen parent)
    {
        this.repositoryDirectory = repositoryDirectory;
        this.commitAId = commitAId;
        this.commitBId = commitBId;
        this.commitALabel = commitALabel;
        this.commitBLabel = commitBLabel;
        this.setParent(parent);
        this.title = "Change Viewer: " + commitALabel + " → " + commitBLabel;
        this.useTitleHierarchy = false;
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.clearElements();

        int y = this.getScreenHeight() - CONTROLS_HEIGHT + 8;
        int x = 12;

        // Mode toggle buttons
        ButtonGeneric onePlane = new ButtonGeneric(x, y, -1, 20, "ONE PLANE");
        onePlane.setEnabled(this.twoPlane);
        this.addButton(onePlane, (btn, mb) -> { this.twoPlane = false; this.initGui(); });
        x += onePlane.getWidth() + 4;

        ButtonGeneric twoPlaneBtn = new ButtonGeneric(x, y, -1, 20, "TWO-PLANE SLAB");
        twoPlaneBtn.setEnabled(!this.twoPlane);
        this.addButton(twoPlaneBtn, (btn, mb) -> { this.twoPlane = true; this.initGui(); });
        x += twoPlaneBtn.getWidth() + 12;

        // Unchanged toggle
        String unchangedLabel = "Unchanged: " + (this.showUnchanged ? "§aON§r" : "§cOFF§r");
        ButtonGeneric unchangedBtn = new ButtonGeneric(x, y, -1, 20, unchangedLabel);
        this.addButton(unchangedBtn, (btn, mb) -> { this.showUnchanged = !this.showUnchanged; this.initGui(); });

        // Back button
        String backLabel = StringUtils.translate("litematica.gui.button.lvc_project.back_to_manager");
        int backWidth = this.getStringWidth(backLabel) + 20;
        ButtonGeneric backBtn = new ButtonGeneric(this.getScreenWidth() - backWidth - 10,
                this.getScreenHeight() - 24, backWidth, 20, backLabel);
        this.addButton(backBtn, (btn, mb) -> GuiBase.openGui(this.getParent()));

        // Store slider track geometry for mouse handling
        this.sliderTrackX = 160;
        this.sliderTrackWidth = this.getScreenWidth() - this.sliderTrackX - 20;

        // Start loading diff data on first init
        if (this.loading && this.loadError == null && this.blocks == null)
        {
            this.loadDiff();
        }
    }

    private void loadDiff()
    {
        Thread thread = new Thread(() ->
        {
            try
            {
                LvcDiff3DComputer.Result result = LvcDiff3DComputer.compute(
                        this.repositoryDirectory, this.commitAId, this.commitBId);
                this.blocks = result.blocks();
                this.buildHeight = Math.max(1, result.height());
                this.loading = false;
            }
            catch (Exception e)
            {
                this.loadError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                this.loading = false;
            }
        }, "LVC-Diff3D-Compute");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        int w = this.getScreenWidth();
        int h = this.getScreenHeight();
        int viewportH = h - CONTROLS_HEIGHT - 20;

        // Background
        RenderUtils.drawRect(ctx, 0, 0, w, h, COLOR_BG);

        // Viewport
        RenderUtils.drawOutlinedBox(ctx, VIEWPORT_PADDING, 20, w - VIEWPORT_PADDING * 2, viewportH,
                COLOR_VIEWPORT, COLOR_OUTLINE);

        if (this.loading)
        {
            String msg = "Computing diff...";
            ctx.drawString(ctx.fontRenderer(), msg, w / 2 - this.getStringWidth(msg) / 2, 20 + viewportH / 2, COLOR_LABEL, false);
        }
        else if (this.loadError != null)
        {
            String msg = "Error: " + this.loadError;
            ctx.drawString(ctx.fontRenderer(), msg, VIEWPORT_PADDING + 8, 20 + viewportH / 2, 0xFFFF4444, false);
        }
        else if (this.blocks != null)
        {
            // Draw the 3D isometric view
            int scale = Math.max(2, Math.min(12, (viewportH - 20) / Math.max(1, this.buildHeight) / 3));
            LvcIsometricRenderer.render(
                    ctx,
                    this.blocks,
                    w / 2, 20 + viewportH / 2,
                    scale,
                    this.slabPosition,
                    this.slabThickness,
                    this.explode,
                    this.twoPlane,
                    this.showUnchanged,
                    this.buildHeight
            );
        }

        // Legend
        int legendY = 20 + viewportH - LEGEND_HEIGHT - 4;
        this.drawLegend(ctx, VIEWPORT_PADDING + 8, legendY);

        // Controls panel
        int controlsY = h - CONTROLS_HEIGHT + 4;
        RenderUtils.drawRect(ctx, 0, controlsY, w, CONTROLS_HEIGHT, 0xFF0D0D1A);
        RenderUtils.drawOutline(ctx, 0, controlsY, w, CONTROLS_HEIGHT, COLOR_OUTLINE);

        // Sliders
        int slY = controlsY + 30;
        this.drawSlider(ctx, "SLAB POSITION",  this.sliderTrackX, slY,      this.slabPosition,  mouseX, mouseY);
        this.drawSlider(ctx, "SLAB THICKNESS", this.sliderTrackX, slY + 28, this.slabThickness, mouseX, mouseY);
        this.drawSlider(ctx, "EXPLODE",        this.sliderTrackX, slY + 56, this.explode,        mouseX, mouseY);
    }

    private void drawLegend(GuiContext ctx, int x, int y)
    {
        int[][] legend = {
                { 0x33CC33, 0xFFFFFFFF },
                { 0xFF3333, 0xFFFFFFFF },
                { 0xFF9010, 0xFFFFFFFF },
                { 0x888888, 0xFFAAAAAA }
        };
        String[] labels = { "Added", "Removed", "Changed", "Unchanged" };

        for (int i = 0; i < labels.length; i++)
        {
            int swatchColor = 0xFF000000 | legend[i][0];
            RenderUtils.drawRect(ctx, x, y + 1, LEGEND_SWATCH, LEGEND_SWATCH, swatchColor);
            RenderUtils.drawOutline(ctx, x, y + 1, LEGEND_SWATCH, LEGEND_SWATCH, 0xFF606060);
            ctx.drawString(ctx.fontRenderer(), labels[i], x + LEGEND_SWATCH + 4, y + 2, legend[i][1], false);
            x += LEGEND_SWATCH + this.getStringWidth(labels[i]) + 16;
        }
    }

    private void drawSlider(GuiContext ctx, String label, int trackX, int y, float value, int mouseX, int mouseY)
    {
        int trackW = this.sliderTrackWidth;
        int labelX = 12;

        ctx.drawString(ctx.fontRenderer(), label, labelX, y + 2, COLOR_LABEL, false);

        // Percentage label on right
        String pct = (int) (value * 100) + "%";
        int pctX = trackX + trackW + 6;
        ctx.drawString(ctx.fontRenderer(), pct, pctX, y + 2, COLOR_VALUE, false);

        // Track
        RenderUtils.drawRect(ctx, trackX, y + (SLIDER_HEIGHT - SLIDER_TRACK_H) / 2,
                trackW, SLIDER_TRACK_H, 0xFF303050);

        // Fill
        int fillW = (int) (value * trackW);
        RenderUtils.drawRect(ctx, trackX, y + (SLIDER_HEIGHT - SLIDER_TRACK_H) / 2,
                fillW, SLIDER_TRACK_H, 0xFF5A5AFF);

        // Thumb
        int thumbX = trackX + fillW - 4;
        RenderUtils.drawRect(ctx, thumbX, y, 8, SLIDER_HEIGHT, 0xFFDDDDFF);
        RenderUtils.drawOutline(ctx, thumbX, y, 8, SLIDER_HEIGHT, 0xFF8888CC);
    }

    @Override
    public boolean onMouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubleClick)
    {
        if (super.onMouseClicked(click, doubleClick)) return true;

        if (click.input() == 0)
        {
            int slider = this.getSliderAt((int) click.x(), (int) click.y());

            if (slider >= 0)
            {
                this.dragSlider = slider;
                this.updateSlider(slider, (int) click.x());
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean onMouseReleased(net.minecraft.client.input.MouseButtonEvent click)
    {
        this.dragSlider = -1;
        return super.onMouseReleased(click);
    }

    @Override
    public boolean onMouseDragged(net.minecraft.client.input.MouseButtonEvent click, double dx, double dy)
    {
        if (this.dragSlider >= 0)
        {
            this.updateSlider(this.dragSlider, (int) click.x());
            return true;
        }

        return super.onMouseDragged(click, dx, dy);
    }

    private int getSliderAt(int mx, int my)
    {
        int h = this.getScreenHeight();
        int controlsY = h - CONTROLS_HEIGHT + 4;
        int slY = controlsY + 30;
        int[] ys = { slY, slY + 28, slY + 56 };

        for (int i = 0; i < ys.length; i++)
        {
            if (mx >= this.sliderTrackX && mx <= this.sliderTrackX + this.sliderTrackWidth
                    && my >= ys[i] && my <= ys[i] + SLIDER_HEIGHT)
            {
                return i;
            }
        }

        return -1;
    }

    private void updateSlider(int slider, int mouseX)
    {
        float value = Math.max(0f, Math.min(1f,
                (float) (mouseX - this.sliderTrackX) / this.sliderTrackWidth));

        switch (slider)
        {
            case 0 -> this.slabPosition  = value;
            case 1 -> this.slabThickness = value;
            case 2 -> this.explode       = value;
        }
    }
}
