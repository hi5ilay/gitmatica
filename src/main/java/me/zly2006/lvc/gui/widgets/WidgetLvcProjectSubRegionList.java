package me.zly2006.lvc.gui.widgets;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import me.zly2006.lvc.gui.GuiLvcProjectEditor;
import me.zly2006.lvc.model.LvcManifest;

import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;
import fi.dy.masa.malilib.util.AlphaNumComparator.AlphaNumStringComparator;

public class WidgetLvcProjectSubRegionList extends WidgetListBase<LvcManifest.Region, WidgetLvcProjectSubRegion>
{
    private final List<LvcManifest.Region> regions;
    private final GuiLvcProjectEditor editorGui;

    public WidgetLvcProjectSubRegionList(int x, int y, int width, int height,
                                         List<LvcManifest.Region> regions,
                                         GuiLvcProjectEditor editorGui,
                                         ISelectionListener<LvcManifest.Region> selectionListener)
    {
        super(x, y, width, height, selectionListener);

        this.regions = List.copyOf(regions);
        this.editorGui = editorGui;
        this.browserEntryHeight = 22;
        this.widgetSearchBar = new WidgetSearchBar(x, y + 4, width - 12, 14, 0, Icons.FILE_ICON_SEARCH, LeftRight.LEFT);
        this.browserEntriesOffsetY = this.widgetSearchBar.getHeight() + 3;
        this.shouldSortList = true;
    }

    public GuiLvcProjectEditor getEditorGui()
    {
        return this.editorGui;
    }

    @Override
    protected Collection<LvcManifest.Region> getAllEntries()
    {
        return this.regions;
    }

    @Override
    protected Comparator<LvcManifest.Region> getComparator()
    {
        AlphaNumStringComparator comparator = new AlphaNumStringComparator();
        return (left, right) -> comparator.compare(left.name(), right.name());
    }

    @Override
    protected List<String> getEntryStringsForFilter(LvcManifest.Region entry)
    {
        return List.of(entry.name().toLowerCase(), entry.id().toLowerCase());
    }

    @Override
    protected WidgetLvcProjectSubRegion createListEntryWidget(int x, int y, int listIndex, boolean isOdd, LvcManifest.Region entry)
    {
        return new WidgetLvcProjectSubRegion(x, y, this.browserEntryWidth, this.browserEntryHeight,
                isOdd, entry, listIndex, this);
    }
}
