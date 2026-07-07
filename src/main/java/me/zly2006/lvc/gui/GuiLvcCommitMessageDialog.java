package me.zly2006.lvc.gui;

import javax.annotation.Nullable;
import net.minecraft.client.input.MouseButtonEvent;
import me.zly2006.lvc.util.LvcGuiTextFields;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextInputStackedMultiLine;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.interfaces.IStringDualConsumerFeedback;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcCommitMessageDialog extends GuiTextInputStackedMultiLine
{
    private static final int DESCRIPTION_LABEL_VERTICAL_SPACE = 20;
    private static final int DESCRIPTION_LABEL_FIELD_OFFSET = 16;
    private static final int ERROR_VERTICAL_SPACE = 14;
    private static final int ERROR_BOX_HEIGHT = 12;
    private static final int ERROR_HORIZONTAL_INSET = 1;

    @Nullable private String errorMessage;
    private boolean errorSpaceVisible;

    GuiLvcCommitMessageDialog(int maxTextLength, int displayLines, int maxLines, String titleKey,
                              String defaultText1, String defaultText2, GuiBase parent,
                              IStringDualConsumerFeedback consumer)
    {
        super(maxTextLength, displayLines, maxLines, titleKey, defaultText1, defaultText2, parent, consumer);
        this.layoutDialog();
        this.textField1.setFocused(true);
        this.textField2.setFocused(false);
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.layoutDialog();

        int x = this.dialogLeft + 11;
        int y = this.getButtonY();

        x += this.createButton(x, y, ButtonType.OK) + 2;
        x += this.createButton(x, y, ButtonType.RESET) + 2;
        this.createButton(x, y, ButtonType.CANCEL);
    }

    @Override
    protected int createButton(int x, int y, ButtonType type)
    {
        String label = type == ButtonType.OK ?
                StringUtils.translate("litematica.gui.button.lvc_project.save") :
                type.getDisplayName();
        ButtonGeneric button = new ButtonGeneric(x, y, -1, this.buttonHeight, label);
        button.setWidth(Math.max(40, button.getWidth()));
        return this.addButton(button, this.createActionListener(type)).getWidth();
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);
        this.drawStringWithShadow(ctx, StringUtils.translate("litematica.gui.label.lvc_project.commit_description"),
                this.dialogLeft + 10, this.textField2.getY() - DESCRIPTION_LABEL_FIELD_OFFSET, COLOR_WHITE);

        if (this.textField1.getValue().isEmpty())
        {
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.lvc_project.commit_title_placeholder"),
                    this.textField1.getX() + 4, this.textField1.getY() + 6, 0xFF777777, false);
        }

        if (this.textField2.getValue().isEmpty())
        {
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.lvc_project.commit_description_optional"),
                    this.textField2.getX() + 4, this.textField2.getY() + 5, 0xFF777777, false);
        }

        if (this.errorMessage != null)
        {
            int errorTop = this.getErrorTopY();
            int errorLeft = this.textField2.getX() + ERROR_HORIZONTAL_INSET;
            int errorWidth = this.textField2.getWidth() - ERROR_HORIZONTAL_INSET * 2;
            RenderUtils.drawOutlinedBox(ctx, errorLeft, errorTop, errorWidth, ERROR_BOX_HEIGHT, 0x80300000, 0xFFFF5555);
            ctx.drawString(ctx.fontRenderer(), this.errorMessage, errorLeft + 4, errorTop + 2, 0xFFFF5555, false);
        }
    }

    private int getButtonY()
    {
        return this.dialogTop + this.totalHeight + this.buttonHeight + 10 + DESCRIPTION_LABEL_VERTICAL_SPACE +
                (this.errorSpaceVisible ? ERROR_VERTICAL_SPACE : 0);
    }

    private void layoutDialog()
    {
        this.setWidthAndHeight(this.totalWidth + 20, this.getDialogHeight());
        this.centerOnScreen();
        LvcGuiTextFields.setPosition(this.textField1, this.dialogLeft + 12, this.dialogTop + this.buttonHeight);
        this.textField2.setX(this.dialogLeft + 12);
        this.textField2.setY(this.dialogTop + this.text1Height + this.buttonHeight + 2 + DESCRIPTION_LABEL_VERTICAL_SPACE);
        this.textField2.setWidth(this.totalWidth);
    }

    private int getDialogHeight()
    {
        return this.totalHeight + this.buttonHeight + 40 + DESCRIPTION_LABEL_VERTICAL_SPACE +
                (this.errorSpaceVisible ? ERROR_VERTICAL_SPACE : 0);
    }

    private int getErrorTopY()
    {
        int descriptionBottom = this.textField2.getY() + this.textField2.getHeight();
        int availableSpace = Math.max(0, this.getButtonY() - descriptionBottom - ERROR_BOX_HEIGHT);
        return descriptionBottom + availableSpace / 2;
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.textField1.mouseClicked(click, doubleClick))
        {
            this.textField1.setFocused(true);
            this.textField2.setFocused(false);
            this.selectedBox = 1;
            return true;
        }
        else if (this.textField2.mouseClicked(click, doubleClick))
        {
            this.textField1.setFocused(false);
            this.textField2.setFocused(true);
            this.selectedBox = 2;
            return true;
        }

        this.textField1.setFocused(false);
        this.textField2.setFocused(false);
        return super.onMouseClicked(click, doubleClick);
    }

    @Override
    protected boolean applyValues(String title, String description)
    {
        if (title == null || title.isBlank())
        {
            this.errorMessage = StringUtils.translate("litematica.error.lvc_project.commit_title_required");
            this.showErrorSpace();
            this.textField1.setFocused(true);
            this.textField2.setFocused(false);
            this.selectedBox = 1;
            return false;
        }

        this.errorMessage = null;
        return super.applyValues(title, description);
    }

    private void showErrorSpace()
    {
        if (this.errorSpaceVisible)
        {
            return;
        }

        this.errorSpaceVisible = true;
        this.initGui();
    }
}
