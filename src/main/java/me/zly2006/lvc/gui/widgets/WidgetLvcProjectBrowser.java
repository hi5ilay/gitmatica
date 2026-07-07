package me.zly2006.lvc.gui.widgets;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntryType;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetLvcProjectBrowser extends WidgetFileBrowserBase implements ISelectionListener<DirectoryEntry>
{
    private static final String BROWSER_CONTEXT = "lvc_project_repositories";
    private static final int INFO_WIDTH = 170;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLLBAR_RIGHT_OFFSET = 9;

    private final ISelectionListener<DirectoryEntry> selectionListener;
    protected final int infoWidth;
    @Nullable private LvcProjectService.Project selectedProject;
    @Nullable private LvcProjectService.ProjectSummary selectedSummary;
    private boolean draggingScrollbar;

    public WidgetLvcProjectBrowser(int x, int y, int width, int height, ISelectionListener<DirectoryEntry> selectionListener)
    {
        super(x, y, width, height, DataManager.getDirectoryCache(), BROWSER_CONTEXT, defaultDirectory(), null, Icons.DUMMY);

        this.selectionListener = selectionListener;
        this.browserEntryHeight = 14;
        this.infoWidth = INFO_WIDTH;
        this.ensureCurrentDirectoryInsideProjectRoot();
        this.setSelectionListener(this);
    }

    @Override
    protected Path getRootDirectory()
    {
        return reposDirectory();
    }

    @Override
    protected FileFilter getFileFilter()
    {
        return new FileFilter();
    }

    @Override
    protected int getBrowserWidthForTotalWidth(int width)
    {
        return getBrowserPanelWidthForTotalWidth(width);
    }

    public static int getBrowserPanelWidthForTotalWidth(int width)
    {
        return Math.max(14, width - 6 - getInfoWidthForTotalWidth(width));
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (click.input() == 0 && this.isMouseOverScrollbar((int) click.x(), (int) click.y()))
        {
            this.scrollToMouseY((int) click.y());
            this.draggingScrollbar = true;
            return true;
        }

        if (this.onMouseClickedSearchBar(click, doubleClick))
        {
            return true;
        }

        final int relativeY = (int) (click.y() - this.browserEntriesStartY - this.browserEntriesOffsetY);

        if (relativeY >= 0 &&
            click.x() >= this.getBrowserEntryVisualX() &&
            click.x() < this.getBrowserEntryVisualX() + this.getBrowserEntryVisualWidth())
        {
            for (WidgetDirectoryEntry widget : this.listWidgets)
            {
                if (widget.isMouseOver((int) click.x(), (int) click.y()))
                {
                    if (widget.canSelectAt(click))
                    {
                        int entryIndex = widget.getListIndex();

                        if (entryIndex >= 0 && entryIndex < this.listContents.size())
                        {
                            this.onEntryClicked(this.listContents.get(entryIndex), entryIndex);
                        }
                    }

                    return widget.onMouseClicked(click, doubleClick);
                }
            }
        }

        return false;
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragXAmount, double dragYAmount)
    {
        if (this.draggingScrollbar)
        {
            this.scrollToMouseY((int) click.y());
            return true;
        }

        return super.onMouseDragged(click, dragXAmount, dragYAmount);
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click)
    {
        boolean handled = false;

        if (click.input() == 0 && this.draggingScrollbar)
        {
            this.draggingScrollbar = false;
            handled = true;
        }

        return super.onMouseReleased(click) || handled;
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        RenderUtils.drawOutlinedBox(ctx, this.posX, this.posY, this.browserWidth, this.browserHeight, 0xB0000000, COLOR_HORIZONTAL_BAR);
        this.drawBrowserContents(ctx, mouseX, mouseY, partialTicks);
        this.drawAdditionalContents(ctx, mouseX, mouseY);
    }

    private void drawBrowserContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        WidgetBase hovered = null;
        boolean drawScrollbar = this.hasScrollableEntries();

        for (WidgetDirectoryEntry widget : this.listWidgets)
        {
            DirectoryEntry entry = widget.getEntry();
            boolean isSelected = this.allowMultiSelection ? this.selectedEntries.contains(entry) : entry != null && entry.equals(this.getLastSelectedEntry());
            widget.render(ctx, mouseX, mouseY, isSelected);

            if (widget.isMouseOver(mouseX, mouseY))
            {
                hovered = widget;
            }
        }

        if (drawScrollbar)
        {
            int scrollbarHeight = this.getScrollbarHeight();
            int totalHeight = Math.max(this.getEntriesTotalHeight(), scrollbarHeight);
            int scrollBarX = this.getScrollbarX();
            int scrollBarY = this.browserEntriesStartY + this.browserEntriesOffsetY;
            this.scrollBar.render(ctx, mouseX, mouseY, partialTicks, scrollBarX, scrollBarY, SCROLLBAR_WIDTH, scrollbarHeight, totalHeight);

            if (this.scrollBar.getValue() != this.lastScrollbarPosition)
            {
                this.lastScrollbarPosition = this.scrollBar.getValue();
                this.reCreateListEntryWidgets();
            }
        }
        else
        {
            this.scrollBar.setIsDragging(false);
            this.draggingScrollbar = false;
        }

        if (this.widgetSearchBar != null)
        {
            this.widgetSearchBar.render(ctx, mouseX, mouseY, false);
        }

        if (hovered == null && this.widgetSearchBar != null && this.widgetSearchBar.isMouseOver(mouseX, mouseY))
        {
            hovered = this.widgetSearchBar;
        }

        this.hoveredWidget = hovered;
    }

    private boolean hasScrollableEntries()
    {
        return this.getEntriesTotalHeight() > this.getUsableEntriesHeight();
    }

    private boolean isMouseOverScrollbar(int mouseX, int mouseY)
    {
        return this.hasScrollableEntries() &&
                mouseX >= this.getScrollbarX() && mouseX < this.getScrollbarX() + SCROLLBAR_WIDTH &&
                mouseY >= this.getScrollbarY() && mouseY < this.getScrollbarY() + this.getScrollbarHeight();
    }

    private void scrollToMouseY(int mouseY)
    {
        int maxScroll = this.scrollBar.getMaxValue();
        int slideHeight = this.getScrollbarHeight() - 2;
        int totalHeight = Math.max(this.getEntriesTotalHeight(), this.getScrollbarHeight());
        int thumbHeight = totalHeight > 0 ? (int) (Math.min(1.0F, slideHeight / (float) totalHeight) * slideHeight) : 0;
        int travel = slideHeight - thumbHeight;

        if (maxScroll <= 0 || travel <= 0)
        {
            return;
        }

        int relativeY = Math.clamp(mouseY - this.getScrollbarY() - 1 - thumbHeight / 2, 0, travel);
        int oldValue = this.scrollBar.getValue();
        this.scrollBar.setValue(Math.round(relativeY * maxScroll / (float) travel));

        if (this.scrollBar.getValue() != oldValue)
        {
            this.lastScrollbarPosition = this.scrollBar.getValue();
            this.reCreateListEntryWidgets();
        }
    }

    private int getScrollbarX()
    {
        return this.posX + this.browserWidth - SCROLLBAR_RIGHT_OFFSET;
    }

    private int getScrollbarY()
    {
        return this.browserEntriesStartY + this.browserEntriesOffsetY;
    }

    private int getScrollbarHeight()
    {
        return this.browserHeight - this.browserEntriesOffsetY - 8;
    }

    private int getEntriesTotalHeight()
    {
        int totalHeight = 0;

        for (DirectoryEntry entry : this.listContents)
        {
            totalHeight += this.getBrowserEntryHeightFor(entry);
        }

        return totalHeight;
    }

    private int getUsableEntriesHeight()
    {
        return this.browserHeight - this.browserPaddingY - this.browserEntriesOffsetY;
    }

    private int getBrowserEntryVisualX()
    {
        return this.posX + 2;
    }

    private int getBrowserEntryVisualWidth()
    {
        return Math.max(0, this.browserWidth - 4);
    }

    @Override
    protected void refreshBrowserEntries()
    {
        this.listContents.clear();

        String filterText = this.widgetSearchBar != null ? this.widgetSearchBar.getFilter() : null;
        List<DirectoryEntry> directories = new ArrayList<>();
        List<DirectoryEntry> projects = new ArrayList<>();

        if (Files.isDirectory(this.currentDirectory))
        {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.currentDirectory, new VisibleDirectoryFilter()))
            {
                for (Path entry : stream)
                {
                    String name = entry.getFileName().toString();

                    if (filterText != null && !this.matchesFilter(name.toLowerCase(java.util.Locale.ROOT), filterText))
                    {
                        continue;
                    }

                    if (LvcProjectService.isProjectRepository(entry))
                    {
                        projects.add(new DirectoryEntry(DirectoryEntryType.FILE, this.currentDirectory, name, null));
                    }
                    else
                    {
                        directories.add(new DirectoryEntry(DirectoryEntryType.DIRECTORY, this.currentDirectory, name, null));
                    }
                }
            }
            catch (IOException e)
            {
                LvcDiagnostics.debug("WidgetLvcProjectBrowser: failed to list '{}': {}", this.currentDirectory, e.getMessage());
            }
        }

        Collections.sort(directories);
        Collections.sort(projects);
        this.listContents.addAll(directories);
        this.listContents.addAll(projects);
        this.reCreateListEntryWidgets();
    }

    @Override
    protected List<Path> getSubDirectories(Path dir)
    {
        List<Path> dirs = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, new VisibleDirectoryFilter()))
        {
            for (Path entry : stream)
            {
                if (!LvcProjectService.isProjectRepository(entry))
                {
                    dirs.add(entry);
                }
            }
        }
        catch (IOException e)
        {
            LvcDiagnostics.debug("WidgetLvcProjectBrowser: failed to list sub-directories in '{}': {}", dir, e.getMessage());
        }

        return dirs;
    }

    @Override
    protected WidgetDirectoryEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, DirectoryEntry entry)
    {
        return new WidgetLvcProjectEntry(x, y, this.getBrowserEntryVisualWidth(), this.getBrowserEntryHeightFor(entry),
                isOdd, entry, listIndex, this, this.iconProvider);
    }

    @Override
    public void onSelectionChange(@Nullable DirectoryEntry entry)
    {
        this.selectedProject = null;
        this.selectedSummary = null;

        if (entry != null && entry.type() == DirectoryEntryType.FILE)
        {
            this.selectedProject = new LvcProjectService.Project(entry.name(), entry.getFullPath());

            try
            {
                this.selectedSummary = LvcProjectService.projectSummary(this.selectedProject);
            }
            catch (Exception e)
            {
                LvcDiagnostics.debug("WidgetLvcProjectBrowser: failed to read LVC project summary for '{}': {}", entry.getFullPath(), e.getMessage());
            }
        }

        this.selectionListener.onSelectionChange(entry);
    }

    @Override
    protected void drawAdditionalContents(GuiContext ctx, int mouseX, int mouseY)
    {
        this.drawSelectedProjectInfo(ctx, this.selectedProject, this.selectedSummary);
    }

    protected void drawSelectedProjectInfo(GuiContext ctx, @Nullable LvcProjectService.Project project,
                                           @Nullable LvcProjectService.ProjectSummary summary)
    {
        int effectiveInfoWidth = this.getInfoWidthForTotalWidth(this.totalWidth);

        if (effectiveInfoWidth <= 0)
        {
            return;
        }

        int x = this.posX + this.totalWidth - effectiveInfoWidth + 4;
        int y = this.posY + 4;
        int infoHeight = Math.min(100, this.browserHeight);

        if (infoHeight <= 0)
        {
            return;
        }

        RenderUtils.drawOutlinedBox(ctx, x - 4, y - 4, effectiveInfoWidth, infoHeight, 0xA0000000, COLOR_HORIZONTAL_BAR);

        if (project == null)
        {
            return;
        }

        String name = summary != null ? summary.name() : project.name();
        int versions = summary != null ? summary.versionCount() : 0;
        BlockPos origin = summary != null ? summary.origin() : null;
        String w = GuiBase.TXT_WHITE;
        String r = GuiBase.TXT_RST;
        int color = 0xFFB0B0B0;

        String projectLabel = StringUtils.translate("litematica.gui.label.lvc_project_manager.project");
        this.drawString(ctx, projectLabel + " " + w + name + r, x, y, color);
        y += 12;
        this.drawString(ctx, StringUtils.translate("litematica.gui.label.lvc_project_manager.versions", w + versions + r), x, y, color);
        y += 12;
        this.drawString(ctx, StringUtils.translate("litematica.gui.label.lvc_project_manager.origin"), x, y, color);
        y += 12;
        this.drawString(ctx, this.formatOrigin(origin, w, r), x + 8, y, color);
    }

    private static int getInfoWidthForTotalWidth(int width)
    {
        if (width < 340)
        {
            return 0;
        }

        return Math.min(INFO_WIDTH, Math.max(0, width - 150));
    }

    private String formatOrigin(@Nullable BlockPos origin, String w, String r)
    {
        if (origin == null)
        {
            return StringUtils.translate("litematica.gui.label.lvc_project_manager.origin_unknown");
        }

        return String.format("x: %s%d%s, y: %s%d%s, z: %s%d%s", w, origin.getX(), r, w, origin.getY(), r, w, origin.getZ(), r);
    }

    private static Path reposDirectory()
    {
        return LvcProjectService.reposDirectory(gameDirectory());
    }

    private static Path gameDirectory()
    {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    private static Path defaultDirectory()
    {
        Path repos = reposDirectory();
        ensureDirectoryExists(repos);
        return repos;
    }

    private void ensureCurrentDirectoryInsideProjectRoot()
    {
        Path root = reposDirectory().toAbsolutePath().normalize();
        Path current = this.currentDirectory.toAbsolutePath().normalize();

        if (!current.startsWith(root))
        {
            this.currentDirectory = root;
            DataManager.getDirectoryCache().setCurrentDirectoryForContext(BROWSER_CONTEXT, root);
        }
    }

    private static void ensureDirectoryExists(Path dir)
    {
        try
        {
            Files.createDirectories(dir);
        }
        catch (IOException e)
        {
            LvcDiagnostics.warn("WidgetLvcProjectBrowser: failed to create LVC project root '{}': {}", dir, e.getMessage());
        }
    }

    private static class WidgetLvcProjectEntry extends WidgetDirectoryEntry
    {
        private WidgetLvcProjectEntry(int x, int y, int width, int height, boolean isOdd, DirectoryEntry entry,
                                      int listIndex, WidgetFileBrowserBase navigator,
                                      fi.dy.masa.malilib.gui.interfaces.IFileBrowserIconProvider iconProvider)
        {
            super(x, y, width, height, isOdd, entry, listIndex, navigator, iconProvider);
        }

        @Override
        protected String getDisplayName()
        {
            return this.entry.getDisplayName();
        }
    }

    private static class VisibleDirectoryFilter implements DirectoryStream.Filter<Path>
    {
        @Override
        public boolean accept(Path entry)
        {
            return Files.isDirectory(entry) && !entry.getFileName().toString().startsWith(".");
        }
    }
}
