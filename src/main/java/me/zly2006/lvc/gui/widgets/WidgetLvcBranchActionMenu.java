package me.zly2006.lvc.gui.widgets;

import java.util.function.Consumer;
import net.minecraft.client.input.MouseButtonEvent;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetLvcBranchActionMenu extends WidgetBase
{
    private static final int ROW_HEIGHT = 22;
    private static final int ROW_GAP = 4;
    private static final int PANEL_PADDING = 4;
    private static final int BUTTON_TOP_GAP = 4;
    private static final int SCREEN_MARGIN = 10;
    private static final int MIN_WIDTH = 132;

    private final Consumer<Action> actionConsumer;

    public WidgetLvcBranchActionMenu(int anchorX, int anchorY, int anchorWidth, int anchorHeight, int screenWidth, int screenHeight,
                                     Consumer<Action> actionConsumer)
    {
        super(0, 0, MIN_WIDTH, getPanelHeight());

        this.actionConsumer = actionConsumer;
        this.width = Math.max(anchorWidth, this.getRequiredWidth());
        this.x = Math.clamp(anchorX, SCREEN_MARGIN, Math.max(SCREEN_MARGIN, screenWidth - this.width - SCREEN_MARGIN));
        this.y = this.getPanelY(anchorY, anchorHeight, screenHeight);
    }

    private int getPanelY(int anchorY, int anchorHeight, int screenHeight)
    {
        int yBelow = anchorY + anchorHeight + BUTTON_TOP_GAP;
        return Math.clamp(yBelow, SCREEN_MARGIN, Math.max(SCREEN_MARGIN, screenHeight - this.height - SCREEN_MARGIN));
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        Action action = this.getActionAt((int) click.y());

        if (action != null)
        {
            this.actionConsumer.accept(action);
        }

        return true;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        super.render(ctx, mouseX, mouseY, selected);
        RenderUtils.drawOutlinedBox(ctx, this.x, this.y, this.width, this.height, 0xF0101010, 0xFFE0E0E0);

        int y = this.y + PANEL_PADDING;

        for (Action action : Action.values())
        {
            ButtonGeneric button = this.createActionButton(action, y);
            button.render(ctx, mouseX, mouseY, button.isMouseOver(mouseX, mouseY));
            y += ROW_HEIGHT + ROW_GAP;
        }
    }

    private ButtonGeneric createActionButton(Action action, int y)
    {
        return new ButtonGeneric(this.x + PANEL_PADDING, y, this.width - PANEL_PADDING * 2, ROW_HEIGHT, action.label(), (IGuiIcon) null);
    }

    private Action getActionAt(int mouseY)
    {
        int y = this.y + PANEL_PADDING;

        for (Action action : Action.values())
        {
            if (mouseY >= y && mouseY < y + ROW_HEIGHT)
            {
                return action;
            }

            y += ROW_HEIGHT + ROW_GAP;
        }

        return null;
    }

    private int getRequiredWidth()
    {
        int width = MIN_WIDTH;

        for (Action action : Action.values())
        {
            width = Math.max(width, this.getStringWidth(action.label()) + PANEL_PADDING * 2 + 12);
        }

        return width;
    }

    private static int getPanelHeight()
    {
        return PANEL_PADDING * 2 + Action.values().length * ROW_HEIGHT + (Action.values().length - 1) * ROW_GAP;
    }

    public enum Action
    {
        CREATE("litematica.gui.button.lvc_project.branch_action_create"),
        DELETE("litematica.gui.button.lvc_project.branch_action_delete"),
        RENAME("litematica.gui.button.lvc_project.branch_action_rename"),
        MERGE("litematica.gui.button.lvc_project.branch_action_merge");

        private final String translationKey;

        Action(String translationKey)
        {
            this.translationKey = translationKey;
        }

        public String label()
        {
            return StringUtils.translate(this.translationKey);
        }
    }
}
