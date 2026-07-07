package me.zly2006.lvc.gui;

import javax.annotation.Nullable;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import me.zly2006.lvc.LvcDiagnostics;
import fi.dy.masa.malilib.gui.GuiConfirmAction;
import fi.dy.masa.malilib.interfaces.IConfirmationListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.KeyCodes;

final class GuiLvcConfirmAction extends GuiConfirmAction
{
    GuiLvcConfirmAction(int width, String titleKey, IConfirmationListener listener, @Nullable Screen parent,
                        String messageKey, Object... args)
    {
        super(width, titleKey, listener, parent, messageKey, args);
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.centerOnScreen();
        super.initGui();
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (input.key() == KeyCodes.KEY_ESCAPE)
        {
            LvcDiagnostics.debug("GuiLvcConfirmAction: cancelling via Escape title='{}'", this.getTitleString());
            this.listener.onActionCancelled();
            this.closeGui(input.hasShiftDown() == false);
            return true;
        }

        return super.onKeyTyped(input);
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        // GuiConfirmAction already draws the title inside the dialog box.
    }
}
