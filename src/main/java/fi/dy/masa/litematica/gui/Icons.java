package fi.dy.masa.litematica.gui;

import java.nio.file.Path;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Matrix3x2f;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import fi.dy.masa.malilib.gui.interfaces.IFileBrowserIconProvider;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.render.element.MaLiLibTexturedGuiElement;
import fi.dy.masa.litematica.Reference;
import fi.dy.masa.litematica.util.FileType;

public enum Icons implements IGuiIcon, IFileBrowserIconProvider
{
    DUMMY                   (  0,   0,  0,  0),
    BUTTON_PLUS_MINUS_8     (  0,   0,  8,  8),
    BUTTON_PLUS_MINUS_12    ( 24,   0, 12, 12),
    BUTTON_PLUS_MINUS_16    (  0, 128, 16, 16),
    ENCLOSING_BOX_ENABLED   (  0, 144, 16, 16),
    ENCLOSING_BOX_DISABLED  (  0, 160, 16, 16),
    FILE_ICON_LITEMATIC     (144,   0, 12, 12),
    FILE_ICON_SCHEMATIC     (144,  12, 12, 12),
    FILE_ICON_SPONGE_SCH    (144,  24, 12, 12),
    FILE_ICON_VANILLA       (144,  36, 12, 12),
    FILE_ICON_JSON          (144,  44, 12, 12),
    FILE_ICON_DIR           (156,   0, 12, 12),
    FILE_ICON_DIR_UP        (156,  12, 12, 12),
    FILE_ICON_DIR_ROOT      (156,  24, 12, 12),
    FILE_ICON_SEARCH        (156,  36, 12, 12),
    FILE_ICON_CREATE_DIR    (156,  48, 12, 12),
    SCHEMATIC_TYPE_FILE     (144,   0, 12, 12),
    GITMATICA_SCHEMATIC_TYPE_FILE(18, 1, 12, 12, true, 100, 103),
    GITMATICA_UNDO          (  1,  35, 16, 16, true, 100, 103),
    GITMATICA_BRANCH        (  1,  86, 16, 16, true, 100, 103),
    GITMATICA_CHECK         (  1,  69, 16, 16, true, 100, 103),
    SCHEMATIC_TYPE_MEMORY   (186,   0, 12, 12),
    INFO_11                 (168,  18, 11, 11),
    NOTICE_EXCLAMATION_11   (168,  29, 11, 11),
    LOCK_LOCKED             (168,  51, 11, 11),
    CHECKBOX_UNSELECTED     (198,   0, 11, 11),
    CHECKBOX_SELECTED       (198,  11, 11, 11),
    ARROW_UP                (209,   0, 15, 15),
    ARROW_DOWN              (209,  15, 15, 15);

    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Reference.MOD_ID, "textures/gui/gui_widgets.png");
    public static final Identifier GITMATICA_TEXTURE = Identifier.fromNamespaceAndPath(Reference.MOD_ID, "textures/gui/gitmatica_widgets.png");

    private final int u;
    private final int v;
    private final int w;
    private final int h;
    private final boolean gitmaticaTexture;
    private final int textureWidth;
    private final int textureHeight;

    Icons(int u, int v, int w, int h)
    {
        this(u, v, w, h, false, 256, 256);
    }

    Icons(int u, int v, int w, int h, boolean gitmaticaTexture, int textureWidth, int textureHeight)
    {
        this.u = u;
        this.v = v;
        this.w = w;
        this.h = h;
        this.gitmaticaTexture = gitmaticaTexture;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
    }

    @Override
    public int getWidth()
    {
        return this.w;
    }

    @Override
    public int getHeight()
    {
        return this.h;
    }

    @Override
    public int getU()
    {
        return this.u;
    }

    @Override
    public int getV()
    {
        return this.v;
    }

    @Override
    public void renderAt(GuiContext ctx, int x, int y, float zLevel, boolean enabled, boolean selected)
    {
        if (this.textureWidth == 256 && this.textureHeight == 256)
        {
            RenderUtils.drawTexturedRect(ctx, this.getTexture(), x, y, this.u, this.v, this.w, this.h, zLevel);
            return;
        }

        Pair<GpuTextureView, GpuSampler> pair = ctx.bindTexture(this.getTexture());

        if (pair == null)
        {
            return;
        }

        float uScale = 1.0F / this.textureWidth;
        float vScale = 1.0F / this.textureHeight;
        ctx.addSimpleElement(new MaLiLibTexturedGuiElement(
                RenderPipelines.GUI_TEXTURED,
                ctx.setupTexture(pair),
                new Matrix3x2f(ctx.pose()),
                x, y, x + this.w, y + this.h,
                this.u * uScale, (this.u + this.w) * uScale,
                this.v * vScale, (this.v + this.h) * vScale,
                -1,
                ctx.peekLastScissor())
        );
    }

    public void renderScaledAt(GuiContext ctx, int x, int y, int width, int height, float zLevel)
    {
        if (width == this.w && height == this.h)
        {
            this.renderAt(ctx, x, y, zLevel, true, false);
            return;
        }

        Pair<GpuTextureView, GpuSampler> pair = ctx.bindTexture(this.getTexture());

        if (pair == null)
        {
            return;
        }

        float uScale = 1.0F / this.textureWidth;
        float vScale = 1.0F / this.textureHeight;
        ctx.addSimpleElement(new MaLiLibTexturedGuiElement(
                RenderPipelines.GUI_TEXTURED,
                ctx.setupTexture(pair),
                new Matrix3x2f(ctx.pose()),
                x, y, x + width, y + height,
                this.u * uScale, (this.u + this.w) * uScale,
                this.v * vScale, (this.v + this.h) * vScale,
                -1,
                ctx.peekLastScissor())
        );
    }

    @Override
    public Identifier getTexture()
    {
        return this.gitmaticaTexture ? GITMATICA_TEXTURE : TEXTURE;
    }

    @Override
    public IGuiIcon getIconRoot()
    {
        return FILE_ICON_DIR_ROOT;
    }

    @Override
    public IGuiIcon getIconUp()
    {
        return FILE_ICON_DIR_UP;
    }

    @Override
    public IGuiIcon getIconCreateDirectory()
    {
        return FILE_ICON_CREATE_DIR;
    }

    @Override
    public IGuiIcon getIconSearch()
    {
        return FILE_ICON_SEARCH;
    }

    @Override
    public IGuiIcon getIconDirectory()
    {
        return FILE_ICON_DIR;
    }

    @Override
    @Nullable
    public IGuiIcon getIconForFile(Path file)
    {
        if (this == DUMMY)
        {
            return null;
        }

        FileType fileType = FileType.fromFile(file);

        return switch (fileType)
        {
            case LITEMATICA_SCHEMATIC -> FILE_ICON_LITEMATIC;
            case SCHEMATICA_SCHEMATIC -> FILE_ICON_SCHEMATIC;
            case VANILLA_STRUCTURE -> FILE_ICON_VANILLA;
            case SPONGE_SCHEMATIC -> FILE_ICON_SPONGE_SCH;
            case JSON -> FILE_ICON_JSON;
            default -> DUMMY;
        };
    }
}
