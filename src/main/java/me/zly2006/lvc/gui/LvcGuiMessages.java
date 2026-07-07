package me.zly2006.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcFriendlyErrors;
import me.zly2006.lvc.LvcUserActionException;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcGuiMessages
{
    private static final int MESSAGE_LIFETIME_MS = 5000;
    private static final int MAX_MESSAGE_BOX_WIDTH = 400;
    private static final int MIN_MESSAGE_BOX_WIDTH = 160;
    private static final int SCREEN_MARGIN = 40;
    private static final int BOX_PADDING = 10;
    private static final int MESSAGE_GAP = 3;
    private static final int MESSAGE_HEIGHT_EXTRA = 5;
    private static final int BACKGROUND_COLOR = 0xA0000000;
    private static final List<CenteredMessage> IN_GAME_MESSAGES = new ArrayList<>();

    private LvcGuiMessages()
    {
    }

    public static void show(MessageType type, String translationKey, Object... args)
    {
        Screen currentScreen = GuiUtils.getCurrentScreen();
        LvcDiagnostics.debug("LVC user message routed type={} key={} currentScreen={}",
                type, translationKey, screenName(currentScreen));

        if (currentScreen instanceof IMessageConsumer messageConsumer)
        {
            messageConsumer.addMessage(type, MESSAGE_LIFETIME_MS, translationKey, args);
            return;
        }

        IN_GAME_MESSAGES.add(new CenteredMessage(type, MESSAGE_LIFETIME_MS, System.currentTimeMillis(), translationKey, args.clone()));
    }

    public static void renderInGameMessages(GuiContext ctx)
    {
        if (GuiUtils.getCurrentScreen() == null && ctx.mc().options.hideGui)
        {
            return;
        }

        if (IN_GAME_MESSAGES.isEmpty())
        {
            return;
        }

        long now = System.currentTimeMillis();
        IN_GAME_MESSAGES.removeIf(message -> message.hasExpired(now));

        if (IN_GAME_MESSAGES.isEmpty())
        {
            return;
        }

        int boxWidth = messageBoxWidth();
        List<RenderedMessage> renderedMessages = wrapMessages(boxWidth);
        int boxHeight = renderedMessagesHeight(renderedMessages) + BOX_PADDING * 2;
        int x = GuiUtils.getScaledWindowWidth() / 2 - boxWidth / 2;
        int y = GuiUtils.getScaledWindowHeight() / 2 - boxHeight / 2;

        RenderUtils.drawRect(ctx, x, y, boxWidth, boxHeight, BACKGROUND_COLOR);

        int textX = x + BOX_PADDING;
        int textY = y + BOX_PADDING;

        for (RenderedMessage message : renderedMessages)
        {
            textY = message.renderAt(ctx, textX, textY);
        }
    }

    public static void showTaskError(LvcFriendlyErrors.Operation operation, String fallbackKey, Exception error)
    {
        showTaskError(operation, fallbackKey, error, false);
    }

    public static void showTaskError(LvcFriendlyErrors.Operation operation, String fallbackKey, Exception error,
                                     boolean mayNeedRecovery)
    {
        LvcFriendlyErrors.FriendlyMessage message = LvcFriendlyErrors.message(operation, error, mayNeedRecovery);
        LvcDiagnostics.warn("LVC task error mapped operation={} fallbackKey={} expected={} reason={} friendlyKey={} raw='{}'",
                operation, fallbackKey, message.expected(), message.reason(), message.translationKey(), rawSummary(error));
        show(MessageType.ERROR, message.translationKey(), message.args());
    }

    public static void showUnloadedTrackedChunks(LvcFriendlyErrors.Operation operation, String fallbackKey,
                                                 int unknownChunks)
    {
        showTaskError(operation, fallbackKey, new LvcUserActionException(
                LvcUserActionException.Reason.TRACKED_CHUNK_UNLOADED,
                operation.displayName() + " preflight found unloaded or unreadable tracked chunks: " + unknownChunks));
    }

    private static String screenName(Screen screen)
    {
        return screen == null ? "<none>" : screen.getClass().getSimpleName();
    }

    private static String rawSummary(Exception error)
    {
        String message = error.getMessage();

        if (message == null || message.isBlank())
        {
            return error.getClass().getName();
        }

        return error.getClass().getName() + ": " + message;
    }

    private static int messageBoxWidth()
    {
        int scaledWidth = GuiUtils.getScaledWindowWidth();
        int availableWidth = Math.max(40, scaledWidth - SCREEN_MARGIN);

        if (availableWidth < MIN_MESSAGE_BOX_WIDTH)
        {
            return availableWidth;
        }

        return Math.min(MAX_MESSAGE_BOX_WIDTH, availableWidth);
    }

    private static List<RenderedMessage> wrapMessages(int boxWidth)
    {
        int lineWidth = Math.max(20, boxWidth - BOX_PADDING * 2);
        List<RenderedMessage> renderedMessages = new ArrayList<>();

        for (CenteredMessage message : IN_GAME_MESSAGES)
        {
            renderedMessages.add(message.wrap(lineWidth));
        }

        return renderedMessages;
    }

    private static int renderedMessagesHeight(List<RenderedMessage> renderedMessages)
    {
        int height = 0;

        for (RenderedMessage message : renderedMessages)
        {
            height += message.height();
        }

        return height;
    }

    private record CenteredMessage(MessageType type, long displayTimeMs, long createdAtMs, String translationKey, Object[] args)
    {
        private boolean hasExpired(long currentTimeMs)
        {
            return currentTimeMs > this.createdAtMs + this.displayTimeMs;
        }

        private RenderedMessage wrap(int maxLineWidth)
        {
            List<String> lines = new ArrayList<>();
            StringUtils.splitTextToLines(lines, StringUtils.translate(this.translationKey, this.args), maxLineWidth);
            return new RenderedMessage(this.type, lines);
        }
    }

    private record RenderedMessage(MessageType type, List<String> lines)
    {
        private int height()
        {
            return this.lines.size() * (StringUtils.getFontHeight() + 1) - 1 + MESSAGE_HEIGHT_EXTRA;
        }

        private int renderAt(GuiContext ctx, int x, int y)
        {
            String formatting = this.type.getFormatting();

            for (String line : this.lines)
            {
                StringUtils.drawString(ctx, x, y, 0xFFFFFFFF, formatting + line + GuiBase.TXT_RST);
                y += StringUtils.getFontHeight() + 1;
            }

            return y + MESSAGE_GAP;
        }
    }
}
