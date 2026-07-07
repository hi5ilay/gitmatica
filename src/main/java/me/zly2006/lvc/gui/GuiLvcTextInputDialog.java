package me.zly2006.lvc.gui;

import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.Screen;
import me.zly2006.lvc.util.LvcGuiTextFields;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;

class GuiLvcTextInputDialog extends GuiTextInput
{
    private static final int ERROR_VERTICAL_SPACE = 14;
    private static final int ERROR_BOX_HEIGHT = 12;
    private static final int ERROR_HORIZONTAL_INSET = 1;

    private final Validator validator;
    @Nullable private final Consumer<String> consumer;
    @Nullable private final IStringConsumerFeedback feedbackConsumer;
    private final int buttonRowXOffset;
    @Nullable private String errorMessage;
    private boolean errorSpaceVisible;

    GuiLvcTextInputDialog(int maxTextLength, String titleKey, String defaultText, @Nullable Screen parent,
                          Validator validator, Consumer<String> consumer)
    {
        this(maxTextLength, titleKey, defaultText, parent, validator, consumer, 0);
    }

    GuiLvcTextInputDialog(int maxTextLength, String titleKey, String defaultText, @Nullable Screen parent,
                          Validator validator, Consumer<String> consumer, int buttonRowXOffset)
    {
        super(maxTextLength, titleKey, defaultText, parent, string -> {});
        this.validator = validator;
        this.consumer = consumer;
        this.feedbackConsumer = null;
        this.buttonRowXOffset = buttonRowXOffset;
    }

    GuiLvcTextInputDialog(int maxTextLength, String titleKey, String defaultText, @Nullable Screen parent,
                          Validator validator, IStringConsumerFeedback feedbackConsumer)
    {
        super(maxTextLength, titleKey, defaultText, parent, string -> {});
        this.validator = validator;
        this.consumer = null;
        this.feedbackConsumer = feedbackConsumer;
        this.buttonRowXOffset = 0;
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.layoutDialog();

        int x = this.textField.getX() + this.buttonRowXOffset;
        int y = this.getButtonY();

        x += this.createButton(x, y, ButtonType.OK) + 2;
        x += this.createButton(x, y, ButtonType.RESET) + 2;
        this.createButton(x, y, ButtonType.CANCEL);
    }

    @Override
    protected int createButton(int x, int y, ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, this.buttonHeight, type.getDisplayName());
        button.setWidth(Math.max(40, button.getWidth()));
        return this.addButton(button, this.createActionListener(type)).getWidth();
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);

        if (this.errorMessage != null)
        {
            int errorTop = this.getErrorTopY();
            int errorLeft = this.textField.getX() + ERROR_HORIZONTAL_INSET;
            int errorWidth = this.textField.getWidth() - ERROR_HORIZONTAL_INSET * 2;
            RenderUtils.drawOutlinedBox(ctx, errorLeft, errorTop, errorWidth, ERROR_BOX_HEIGHT, 0x80300000, 0xFFFF5555);
            ctx.drawString(ctx.fontRenderer(), this.errorMessage, errorLeft + 4, errorTop + 2, 0xFFFF5555, false);
        }
    }

    @Override
    protected boolean applyValue(String string)
    {
        String validationError = this.validator.validate(string);

        if (validationError != null && !validationError.isBlank())
        {
            this.errorMessage = validationError;
            this.showErrorSpace();
            this.textField.setFocused(true);
            return false;
        }

        this.errorMessage = null;
        if (this.feedbackConsumer != null)
        {
            if (this.feedbackConsumer.setString(string))
            {
                GuiBase.openGui(this.getParent());
                return false;
            }

            this.textField.setFocused(true);
            return false;
        }

        GuiBase.openGui(this.getParent());
        this.consumer.accept(string);
        return false;
    }

    private void layoutDialog()
    {
        this.setWidthAndHeight(this.totalWidth + 20, this.getDialogHeight());
        this.centerOnScreen();
        LvcGuiTextFields.setPosition(this.textField, this.dialogLeft + 12, this.dialogTop + this.buttonHeight);
    }

    private int getDialogHeight()
    {
        return this.totalHeight + this.buttonHeight + 40 + (this.errorSpaceVisible ? ERROR_VERTICAL_SPACE : 0);
    }

    private int getButtonY()
    {
        int errorSpace = this.errorSpaceVisible ? ERROR_VERTICAL_SPACE : 0;
        return this.dialogTop + this.totalHeight + this.buttonHeight + 10 + errorSpace;
    }

    private int getErrorTopY()
    {
        int textBottom = this.textField.getY() + this.textField.getHeight();
        int availableSpace = Math.max(0, this.getButtonY() - textBottom - ERROR_BOX_HEIGHT);
        return textBottom + availableSpace / 2;
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

    @FunctionalInterface
    interface Validator
    {
        @Nullable
        String validate(String value);
    }
}
