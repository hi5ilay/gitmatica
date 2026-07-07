package me.zly2006.rvc;

import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;

import fi.dy.masa.litematica.Reference;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiRvcProjectSettings extends GuiBase
{
    private static final int MARGIN = 10;
    private static final int TOP_Y = 30;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SECTION_GAP = 10;
    private static final int SECTION_PADDING = 8;
    private static final int ROW_HEIGHT = 14;
    private static final int REPOSITORY_ROW_HEIGHT = 20;
    private static final int REPOSITORY_ROW_COUNT = 3;
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int VALUE_COLOR = 0xFFFFFFFF;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int SUCCESS_COLOR = 0xFF55FF55;
    private static final int WARNING_COLOR = 0xFFFFD36A;
    private static final int ERROR_COLOR = 0xFFFF5555;

    private final Path repositoryDirectory;
    private final Path gameRunDirectory;
    private final String projectName;
    private final boolean returnToProjectBrowser;
    @Nullable private String remoteUrl;
    @Nullable private RvcGithubAuth.LinkedAccount githubAccount;
    @Nullable private RvcGithubAuth.DeviceAuthorization deviceAuthorization;
    private List<RvcGithubAuth.RepositoryInfo> repositories = List.of();
    private int repositoryScrollOffset;
    private GithubFlowState githubFlowState = GithubFlowState.IDLE;
    private boolean githubTaskRunning;
    private long githubTaskId;
    private String githubMessage = "";
    private int githubMessageColor = MUTED_COLOR;

    public GuiRvcProjectSettings(Path repositoryDirectory, String projectName)
    {
        this(repositoryDirectory, projectName, false);
    }

    public GuiRvcProjectSettings(Path repositoryDirectory, String projectName, boolean returnToProjectBrowser)
    {
        this.repositoryDirectory = repositoryDirectory;
        this.gameRunDirectory = Minecraft.getInstance().gameDirectory.toPath();
        this.projectName = projectName;
        this.returnToProjectBrowser = returnToProjectBrowser;
        this.title = StringUtils.translate("litematica.gui.title.rvc_project_settings", Reference.MOD_VERSION, projectName);
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.refreshRemote();
        this.refreshGithubAccount();
        this.createButtons();
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);
        this.drawGithubSyncSection(ctx);
        this.drawAdvancedRemoteSection(ctx);
    }

    private void refreshRemote()
    {
        try
        {
            this.remoteUrl = RvcProjectService.remoteOriginUrl(this.repositoryDirectory);
        }
        catch (Exception e)
        {
            this.remoteUrl = null;
            this.addMessage(MessageType.ERROR, "litematica.error.rvc_project.remote_failed", e.getMessage());
        }
    }

    private void refreshGithubAccount()
    {
        this.githubAccount = RvcGithubAuth.readStoredAccount(this.gameRunDirectory);
    }

    private void createButtons()
    {
        int githubY = this.getGithubSectionY() + this.getGithubSectionHeight() - SECTION_PADDING - BUTTON_HEIGHT;
        int x = MARGIN + SECTION_PADDING;

        if (this.githubFlowState == GithubFlowState.IDLE)
        {
            x += this.createButton(x, githubY, ButtonType.CONNECT_GITHUB) + 2;
            x += this.createButton(x, githubY, ButtonType.CHANGE_REPO) + 2;
            this.createButton(x, githubY, ButtonType.TEST_CONNECTION);
        }
        else
        {
            x += this.createButton(x, githubY, ButtonType.CANCEL_GITHUB_FLOW) + 2;

            if (this.githubFlowState == GithubFlowState.PICK_REPOSITORY)
            {
                x += this.createButton(x, githubY, ButtonType.REFRESH_REPOSITORIES) + 2;
                x += this.createButton(x, githubY, ButtonType.PREV_REPOSITORIES) + 2;
                this.createButton(x, githubY, ButtonType.NEXT_REPOSITORIES);
                this.createRepositoryButtons();
            }
        }

        int advancedY = this.getAdvancedSectionY() + this.getAdvancedSectionHeight() - SECTION_PADDING - BUTTON_HEIGHT;
        x = MARGIN + SECTION_PADDING;
        x += this.createButton(x, advancedY, ButtonType.MANUAL_REMOTE) + 2;
        x += this.createButton(x, advancedY, ButtonType.CLEAR_GITHUB_LINK) + 2;
        this.createButton(x, advancedY, ButtonType.SIGN_OUT_GITHUB);

        String backKey = this.returnToProjectBrowser ? "litematica.gui.button.rvc_project.back_to_manager" : "litematica.gui.button.rvc_project_settings.back_to_project";
        String backLabel = StringUtils.translate(backKey);
        int width = this.getStringWidth(backLabel) + 20;
        this.addButton(new ButtonGeneric(this.getScreenWidth() - MARGIN - width, this.getScreenHeight() - 24, width, BUTTON_HEIGHT, backLabel), new ButtonListener(ButtonType.BACK, this));
    }

    private int createButton(int x, int y, ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, BUTTON_HEIGHT, StringUtils.translate(type.translationKey));
        this.addButton(button, new ButtonListener(type, this));

        if (type == ButtonType.CONNECT_GITHUB && this.githubTaskRunning)
        {
            button.setEnabled(false);
        }

        if (type == ButtonType.CHANGE_REPO && (this.githubAccount == null || this.githubTaskRunning))
        {
            button.setEnabled(false);
        }

        if (type == ButtonType.TEST_CONNECTION && (!this.hasGithubRemote() || this.githubAccount == null || this.githubTaskRunning))
        {
            button.setEnabled(false);
        }

        if (type == ButtonType.CLEAR_GITHUB_LINK && !this.hasGithubRemote())
        {
            button.setEnabled(false);
        }

        if (type == ButtonType.SIGN_OUT_GITHUB && (this.githubAccount == null || this.githubTaskRunning))
        {
            button.setEnabled(false);
        }

        if (type == ButtonType.REFRESH_REPOSITORIES && this.githubTaskRunning)
        {
            button.setEnabled(false);
        }

        if (type == ButtonType.PREV_REPOSITORIES && this.repositoryScrollOffset <= 0)
        {
            button.setEnabled(false);
        }

        if (type == ButtonType.NEXT_REPOSITORIES && this.repositoryScrollOffset + REPOSITORY_ROW_COUNT >= this.repositories.size())
        {
            button.setEnabled(false);
        }

        return button.getWidth();
    }

    private void createRepositoryButtons()
    {
        int x = MARGIN + SECTION_PADDING + 2;
        int y = this.getRepositoryListY() + 2;
        int width = Math.min(320, this.getSectionWidth() - SECTION_PADDING * 2 - 4);
        int visibleRows = Math.clamp(this.repositories.size() - this.repositoryScrollOffset, 0, REPOSITORY_ROW_COUNT);

        for (int i = 0; i < visibleRows; i++)
        {
            int repositoryIndex = this.repositoryScrollOffset + i;
            RvcGithubAuth.RepositoryInfo repository = this.repositories.get(repositoryIndex);
            ButtonGeneric button = new ButtonGeneric(x, y + i * REPOSITORY_ROW_HEIGHT, width, BUTTON_HEIGHT, this.ellipsize(repository.displayName(), width - 12));
            button.setEnabled(!this.githubTaskRunning);
            this.addButton(button, new RepositoryButtonListener(repositoryIndex, this));
        }
    }

    private void drawGithubSyncSection(GuiContext ctx)
    {
        int x = MARGIN;
        int y = this.getGithubSectionY();
        int width = this.getSectionWidth();
        int height = this.getGithubSectionHeight();
        RenderUtils.drawOutlinedBox(ctx, x, y, width, height, 0xA0000000, COLOR_HORIZONTAL_BAR);

        int textX = x + SECTION_PADDING;
        int textY = y + SECTION_PADDING;
        ctx.drawString(ctx.fontRenderer(), GuiBase.TXT_BOLD + StringUtils.translate("litematica.gui.label.rvc_project_settings.github_sync"), textX, textY, VALUE_COLOR, false);
        textY += ROW_HEIGHT + 2;

        GithubRemote githubRemote = this.githubRemote();
        String status;
        int statusColor;

        if (githubRemote != null)
        {
            status = StringUtils.translate("litematica.gui.label.rvc_project_settings.status_connected", githubRemote.owner(), githubRemote.repository());
            statusColor = SUCCESS_COLOR;
        }
        else if (this.hasRemote())
        {
            status = StringUtils.translate("litematica.gui.label.rvc_project_settings.status_manual_remote");
            statusColor = WARNING_COLOR;
        }
        else
        {
            status = StringUtils.translate("litematica.gui.label.rvc_project_settings.status_not_connected");
            statusColor = WARNING_COLOR;
        }

        this.drawLabelValue(ctx, textX, textY, "litematica.gui.label.rvc_project_settings.status", status, statusColor);
        textY += ROW_HEIGHT;
        this.drawLabelValue(ctx, textX, textY, "litematica.gui.label.rvc_project_settings.repository", githubRemote != null ? githubRemote.fullName() : StringUtils.translate("litematica.gui.label.rvc_project_settings.no_repository"), githubRemote != null ? VALUE_COLOR : MUTED_COLOR);
        textY += ROW_HEIGHT;
        String auth = this.githubAccount != null ?
                StringUtils.translate("litematica.gui.label.rvc_project_settings.auth_signed_in", this.githubAccount.login()) :
                StringUtils.translate("litematica.gui.label.rvc_project_settings.auth_not_signed_in");
        this.drawLabelValue(ctx, textX, textY, "litematica.gui.label.rvc_project_settings.auth", auth, this.githubAccount != null ? SUCCESS_COLOR : MUTED_COLOR);
        textY += ROW_HEIGHT;

        if (this.githubFlowState == GithubFlowState.WAITING_FOR_GITHUB)
        {
            this.drawDeviceAuthorization(ctx, textX, textY + 4);
        }
        else if (this.githubFlowState == GithubFlowState.PICK_REPOSITORY)
        {
            this.drawRepositoryPicker(ctx, textX, textY + 2, width - SECTION_PADDING * 2);
        }
        else if (!this.githubMessage.isBlank())
        {
            ctx.drawString(ctx.fontRenderer(), this.ellipsize(this.githubMessage, width - SECTION_PADDING * 2), textX, textY + 4, this.githubMessageColor, false);
        }
    }

    private void drawAdvancedRemoteSection(GuiContext ctx)
    {
        int x = MARGIN;
        int y = this.getAdvancedSectionY();
        int width = this.getSectionWidth();
        int height = this.getAdvancedSectionHeight();
        RenderUtils.drawOutlinedBox(ctx, x, y, width, height, 0xA0000000, COLOR_HORIZONTAL_BAR);

        int textX = x + SECTION_PADDING;
        int textY = y + SECTION_PADDING;
        ctx.drawString(ctx.fontRenderer(), GuiBase.TXT_BOLD + StringUtils.translate("litematica.gui.label.rvc_project_settings.advanced_remote"), textX, textY, VALUE_COLOR, false);
        textY += ROW_HEIGHT + 2;

        String remote = this.hasRemote() ? this.remoteUrl : StringUtils.translate("litematica.gui.label.rvc_project.remote_not_set");
        this.drawLabelValue(ctx, textX, textY, "litematica.gui.label.rvc_project_settings.remote_url", this.ellipsize(remote, width - 140), this.hasRemote() ? VALUE_COLOR : MUTED_COLOR);
        textY += ROW_HEIGHT;
        this.drawLabelValue(ctx, textX, textY, "litematica.gui.label.rvc_project_settings.manual_remote_mode", StringUtils.translate("litematica.gui.label.rvc_project_settings.manual_remote_note"), LABEL_COLOR);
    }

    private void drawDeviceAuthorization(GuiContext ctx, int x, int y)
    {
        RvcGithubAuth.DeviceAuthorization authorization = this.deviceAuthorization;

        if (authorization == null)
        {
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.rvc_project_settings.requesting_github"), x, y, WARNING_COLOR, false);
            return;
        }

        ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.rvc_project_settings.waiting_for_github"), x, y, WARNING_COLOR, false);
        ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.rvc_project_settings.github_device_code", authorization.userCode()), x, y + ROW_HEIGHT, VALUE_COLOR, false);
        ctx.drawString(ctx.fontRenderer(), authorization.verificationUri().toString(), x, y + ROW_HEIGHT * 2, LABEL_COLOR, false);
    }

    private void drawRepositoryPicker(GuiContext ctx, int x, int y, int width)
    {
        int listY = this.getRepositoryListY();
        int listHeight = REPOSITORY_ROW_COUNT * REPOSITORY_ROW_HEIGHT + 4;
        RenderUtils.drawOutlinedBox(ctx, x, listY, width, listHeight, 0x80300000, COLOR_HORIZONTAL_BAR);

        if (this.githubTaskRunning && this.repositories.isEmpty())
        {
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.rvc_project_settings.loading_repositories"), x + 6, listY + 7, WARNING_COLOR, false);
            return;
        }

        if (this.repositories.isEmpty())
        {
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("litematica.gui.label.rvc_project_settings.repository_picker_empty"), x + 6, listY + 7, MUTED_COLOR, false);
            return;
        }

        String range = StringUtils.translate("litematica.gui.label.rvc_project_settings.repository_range",
                this.repositoryScrollOffset + 1,
                Math.min(this.repositoryScrollOffset + REPOSITORY_ROW_COUNT, this.repositories.size()),
                this.repositories.size());
        ctx.drawString(ctx.fontRenderer(), range, x + 6, y, LABEL_COLOR, false);
    }

    private void drawLabelValue(GuiContext ctx, int x, int y, String labelKey, String value, int valueColor)
    {
        String label = StringUtils.translate(labelKey) + ": ";
        ctx.drawString(ctx.fontRenderer(), label, x, y, LABEL_COLOR, false);
        ctx.drawString(ctx.fontRenderer(), value, x + this.getStringWidth(label), y, valueColor, false);
    }

    private String ellipsize(String value, int maxWidth)
    {
        if (value == null || this.getStringWidth(value) <= maxWidth)
        {
            return value;
        }

        String suffix = "...";
        int suffixWidth = this.getStringWidth(suffix);
        int length = value.length();

        while (length > 0 && this.getStringWidth(value.substring(0, length)) + suffixWidth > maxWidth)
        {
            length--;
        }

        return value.substring(0, Math.max(0, length)) + suffix;
    }

    private int getGithubSectionY()
    {
        return TOP_Y;
    }

    private int getGithubSectionHeight()
    {
        return 184;
    }

    private int getAdvancedSectionY()
    {
        return this.getGithubSectionY() + this.getGithubSectionHeight() + SECTION_GAP;
    }

    private int getRepositoryListY()
    {
        return this.getGithubSectionY() + SECTION_PADDING + ROW_HEIGHT * 4 + 4;
    }

    private int getAdvancedSectionHeight()
    {
        return 86;
    }

    private int getSectionWidth()
    {
        return this.getScreenWidth() - MARGIN * 2;
    }

    private boolean hasRemote()
    {
        return this.remoteUrl != null && !this.remoteUrl.isBlank();
    }

    private boolean hasGithubRemote()
    {
        return this.githubRemote() != null;
    }

    @Nullable
    private GithubRemote githubRemote()
    {
        if (!this.hasRemote())
        {
            return null;
        }

        String url = this.remoteUrl.trim();

        if (url.startsWith("https://github.com/"))
        {
            return parseGithubPath(url.substring("https://github.com/".length()));
        }

        if (url.startsWith("http://github.com/"))
        {
            return parseGithubPath(url.substring("http://github.com/".length()));
        }

        if (url.startsWith("git@github.com:"))
        {
            return parseGithubPath(url.substring("git@github.com:".length()));
        }

        if (url.startsWith("ssh://git@github.com/"))
        {
            return parseGithubPath(url.substring("ssh://git@github.com/".length()));
        }

        return null;
    }

    @Nullable
    private static GithubRemote parseGithubPath(String path)
    {
        String normalized = path;

        if (normalized.endsWith(".git"))
        {
            normalized = normalized.substring(0, normalized.length() - 4);
        }

        int slash = normalized.indexOf('/');

        if (slash <= 0 || slash >= normalized.length() - 1)
        {
            return null;
        }

        return new GithubRemote(normalized.substring(0, slash), normalized.substring(slash + 1));
    }

    private void startGithubConnect()
    {
        if (!RvcGithubAuth.isConfigured())
        {
            this.setGithubMessage(RvcGithubAuth.configurationHint(), ERROR_COLOR);
            this.initGui();
            return;
        }

        long taskId = this.beginGithubTask();
        this.githubFlowState = GithubFlowState.WAITING_FOR_GITHUB;
        this.deviceAuthorization = null;
        this.repositories = List.of();
        this.repositoryScrollOffset = 0;
        this.setGithubMessage(StringUtils.translate("litematica.gui.label.rvc_project_settings.requesting_github"), WARNING_COLOR);
        this.initGui();

        this.runGithubTask(taskId, () ->
        {
            RvcGithubAuth.DeviceAuthorization authorization = RvcGithubAuth.beginDeviceAuthorization();

            this.runOnClient(taskId, () ->
            {
                this.deviceAuthorization = authorization;
                boolean browserOpened = RvcGithubAuth.openBrowser(authorization.verificationUri());
                this.setGithubMessage(StringUtils.translate(browserOpened ?
                        "litematica.message.rvc_project_settings.github_browser_opened" :
                        "litematica.message.rvc_project_settings.github_browser_not_opened"), browserOpened ? SUCCESS_COLOR : WARNING_COLOR);
                this.initGui();
            });

            RvcGithubAuth.LinkedAccount account = RvcGithubAuth.pollDeviceAuthorization(this.gameRunDirectory, authorization, () -> this.isGithubTaskCurrent(taskId));

            this.runOnClient(taskId, () ->
            {
                this.githubAccount = account;
                this.githubFlowState = GithubFlowState.PICK_REPOSITORY;
                this.githubTaskRunning = false;
                this.setGithubMessage(StringUtils.translate("litematica.message.rvc_project_settings.github_connected", account.login()), SUCCESS_COLOR);
                this.initGui();
                this.loadRepositories();
            });
        });
    }

    private void startRepoPicker()
    {
        if (this.githubAccount == null)
        {
            this.setGithubMessage(StringUtils.translate("litematica.message.rvc_project_settings.github_connect_first"), WARNING_COLOR);
            this.initGui();
            return;
        }

        this.githubFlowState = GithubFlowState.PICK_REPOSITORY;
        this.initGui();
        this.loadRepositories();
    }

    private void cancelGithubFlow()
    {
        this.githubTaskId++;
        this.githubTaskRunning = false;
        this.githubFlowState = GithubFlowState.IDLE;
        this.deviceAuthorization = null;
        this.setGithubMessage("", MUTED_COLOR);
        this.initGui();
    }

    private void testConnection()
    {
        GithubRemote remote = this.githubRemote();

        if (remote == null)
        {
            this.setGithubMessage(StringUtils.translate("litematica.message.rvc_project_settings.github_no_repo"), WARNING_COLOR);
            this.initGui();
            return;
        }

        long taskId = this.beginGithubTask();
        this.setGithubMessage(StringUtils.translate("litematica.message.rvc_project_settings.github_testing"), WARNING_COLOR);
        this.initGui();

        this.runGithubTask(taskId, () ->
        {
            RvcGithubAuth.testRepositoryAccess(this.repositoryDirectory, remote.owner(), remote.repository());
            this.runOnClient(taskId, () ->
            {
                this.githubTaskRunning = false;
                this.setGithubMessage(StringUtils.translate("litematica.message.rvc_project_settings.github_test_ok", remote.fullName()), SUCCESS_COLOR);
                this.initGui();
            });
        });
    }

    private void clearGithubLink()
    {
        try
        {
            RvcProjectService.clearRemote(this.repositoryDirectory);
            this.refreshRemote();
            this.setGithubMessage(StringUtils.translate("litematica.message.rvc_project_settings.github_unlinked"), SUCCESS_COLOR);
            this.initGui();
        }
        catch (Exception e)
        {
            this.setGithubMessage(StringUtils.translate("litematica.error.rvc_project.remote_failed", e.getMessage()), ERROR_COLOR);
            this.initGui();
        }
    }

    private void signOutGithub()
    {
        try
        {
            RvcGithubAuth.clearStoredToken(this.gameRunDirectory);
            this.githubAccount = null;
            this.githubFlowState = GithubFlowState.IDLE;
            this.repositories = List.of();
            this.repositoryScrollOffset = 0;
            this.setGithubMessage(StringUtils.translate("litematica.message.rvc_project_settings.github_signed_out"), SUCCESS_COLOR);
            this.initGui();
        }
        catch (Exception e)
        {
            this.setGithubMessage(StringUtils.translate("litematica.error.rvc_project.remote_failed", e.getMessage()), ERROR_COLOR);
            this.initGui();
        }
    }

    private void loadRepositories()
    {
        long taskId = this.beginGithubTask();
        this.setGithubMessage(StringUtils.translate("litematica.gui.label.rvc_project_settings.loading_repositories"), WARNING_COLOR);
        this.initGui();

        this.runGithubTask(taskId, () ->
        {
            List<RvcGithubAuth.RepositoryInfo> loadedRepositories = RvcGithubAuth.listRepositories(this.gameRunDirectory);

            this.runOnClient(taskId, () ->
            {
                this.repositories = loadedRepositories;
                this.repositoryScrollOffset = Math.clamp(loadedRepositories.size() - REPOSITORY_ROW_COUNT, 0, this.repositoryScrollOffset);
                this.githubTaskRunning = false;
                this.setGithubMessage(StringUtils.translate("litematica.message.rvc_project_settings.github_repositories_loaded", loadedRepositories.size()), loadedRepositories.isEmpty() ? WARNING_COLOR : SUCCESS_COLOR);
                this.initGui();
            });
        });
    }

    private void linkRepository(int repositoryIndex)
    {
        if (repositoryIndex < 0 || repositoryIndex >= this.repositories.size())
        {
            return;
        }

        RvcGithubAuth.RepositoryInfo repository = this.repositories.get(repositoryIndex);

        try
        {
            RvcProjectService.setRemote(this.repositoryDirectory, repository.remoteUrl());
            this.refreshRemote();
            this.githubFlowState = GithubFlowState.IDLE;
            this.setGithubMessage(StringUtils.translate("litematica.message.rvc_project_settings.github_repo_linked", repository.fullName()), SUCCESS_COLOR);
            this.initGui();
        }
        catch (Exception e)
        {
            this.setGithubMessage(StringUtils.translate("litematica.error.rvc_project.remote_failed", e.getMessage()), ERROR_COLOR);
            this.initGui();
        }
    }

    private void nextRepositories()
    {
        this.repositoryScrollOffset = Math.clamp(this.repositories.size() - REPOSITORY_ROW_COUNT, 0, this.repositoryScrollOffset + REPOSITORY_ROW_COUNT);
        this.initGui();
    }

    private void previousRepositories()
    {
        this.repositoryScrollOffset = Math.max(0, this.repositoryScrollOffset - REPOSITORY_ROW_COUNT);
        this.initGui();
    }

    private void promptManualRemote()
    {
        String currentRemoteUrl = this.remoteUrl != null ? this.remoteUrl : "";
        GuiBase.openGui(new GuiTextInput(512, "litematica.gui.title.rvc_project.remote_url", currentRemoteUrl, this, new RemoteUrlSetter(this)));
    }

    private void goBack()
    {
        GuiBase.openGui(this.returnToProjectBrowser ? new GuiRvcProjectManager() : new GuiRvcProject(this.repositoryDirectory, this.projectName));
    }

    private long beginGithubTask()
    {
        this.githubTaskId++;
        this.githubTaskRunning = true;
        return this.githubTaskId;
    }

    private boolean isGithubTaskCurrent(long taskId)
    {
        return this.githubTaskRunning && this.githubTaskId == taskId;
    }

    private void runGithubTask(long taskId, GithubTask task)
    {
        Thread thread = new Thread(() ->
        {
            try
            {
                task.run();
            }
            catch (Exception e)
            {
                this.runOnClient(taskId, () ->
                {
                    this.githubTaskRunning = false;
                    this.setGithubMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), ERROR_COLOR);
                    this.initGui();
                });
            }
        }, "RVC GitHub Auth");
        thread.setDaemon(true);
        thread.start();
    }

    private void runOnClient(long taskId, Runnable runnable)
    {
        Minecraft.getInstance().execute(() ->
        {
            if (this.githubTaskId == taskId)
            {
                runnable.run();
            }
        });
    }

    private void setGithubMessage(String message, int color)
    {
        this.githubMessage = message != null ? message : "";
        this.githubMessageColor = color;
    }

    @FunctionalInterface
    private interface GithubTask
    {
        void run() throws Exception;
    }

    private enum GithubFlowState
    {
        IDLE,
        WAITING_FOR_GITHUB,
        PICK_REPOSITORY
    }

    private enum ButtonType
    {
        CONNECT_GITHUB("litematica.gui.button.rvc_project_settings.connect_github"),
        CHANGE_REPO("litematica.gui.button.rvc_project_settings.change_repo"),
        TEST_CONNECTION("litematica.gui.button.rvc_project_settings.test_connection"),
        CANCEL_GITHUB_FLOW("litematica.gui.button.rvc_project_settings.cancel"),
        REFRESH_REPOSITORIES("litematica.gui.button.rvc_project_settings.refresh_repositories"),
        PREV_REPOSITORIES("litematica.gui.button.rvc_project_settings.prev_repositories"),
        NEXT_REPOSITORIES("litematica.gui.button.rvc_project_settings.next_repositories"),
        MANUAL_REMOTE("litematica.gui.button.rvc_project_settings.manual_remote"),
        CLEAR_GITHUB_LINK("litematica.gui.button.rvc_project_settings.clear_github_link"),
        SIGN_OUT_GITHUB("litematica.gui.button.rvc_project_settings.sign_out_github"),
        BACK("litematica.gui.button.rvc_project_settings.back_to_project");

        private final String translationKey;

        ButtonType(String translationKey)
        {
            this.translationKey = translationKey;
        }
    }

    private record ButtonListener(ButtonType type, GuiRvcProjectSettings gui) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case CONNECT_GITHUB -> this.gui.startGithubConnect();
                case CHANGE_REPO -> this.gui.startRepoPicker();
                case TEST_CONNECTION -> this.gui.testConnection();
                case CANCEL_GITHUB_FLOW -> this.gui.cancelGithubFlow();
                case REFRESH_REPOSITORIES -> this.gui.loadRepositories();
                case PREV_REPOSITORIES -> this.gui.previousRepositories();
                case NEXT_REPOSITORIES -> this.gui.nextRepositories();
                case MANUAL_REMOTE -> this.gui.promptManualRemote();
                case CLEAR_GITHUB_LINK -> this.gui.clearGithubLink();
                case SIGN_OUT_GITHUB -> this.gui.signOutGithub();
                case BACK -> this.gui.goBack();
            }
        }
    }

    private record RepositoryButtonListener(int repositoryIndex, GuiRvcProjectSettings gui) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.gui.linkRepository(this.repositoryIndex);
        }
    }

    private record RemoteUrlSetter(GuiRvcProjectSettings gui) implements IStringConsumerFeedback
    {
        @Override
        public boolean setString(String remoteUrl)
        {
            try
            {
                RvcProjectService.setRemote(this.gui.repositoryDirectory, remoteUrl);
                this.gui.githubFlowState = GithubFlowState.IDLE;
                this.gui.refreshRemote();
                this.gui.addMessage(MessageType.SUCCESS, "litematica.message.rvc_project.remote_updated", remoteUrl.trim());
                this.gui.initGui();
                return true;
            }
            catch (Exception e)
            {
                this.gui.addMessage(MessageType.ERROR, "litematica.error.rvc_project.remote_failed", RvcProjectService.describeRemoteFailure(e));
                return false;
            }
        }
    }

    private record GithubRemote(String owner, String repository)
    {
        private String fullName()
        {
            return this.owner + "/" + this.repository;
        }
    }
}
