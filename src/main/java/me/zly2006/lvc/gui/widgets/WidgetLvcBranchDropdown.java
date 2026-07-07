package me.zly2006.lvc.gui.widgets;

import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetLvcBranchDropdown extends WidgetLvcSearchableListDropdown<String>
{
    private static final int BRANCH_ICON_SIZE = 14;
    private static final String OPTIONS_TOOLTIP_KEY = "litematica.gui.label.lvc_project.branch_dropdown_options_hint";

    @Nullable private String headBranch;

    public WidgetLvcBranchDropdown(int x, int y, int width, int height, int maxVisibleRows,
                                   List<String> branches, @Nullable String selectedBranch, @Nullable String headBranch,
                                   Consumer<String> selectionConsumer)
    {
        super(x, y, width, height, maxVisibleRows, branches, selectedBranch, selectionConsumer);
        this.setHeadBranch(headBranch);
    }

    public void setBranches(List<String> branches)
    {
        this.setItems(branches);
    }

    public void setSelectedBranch(@Nullable String selectedBranch)
    {
        this.setSelectedItem(selectedBranch);
    }

    public void setHeadBranch(@Nullable String headBranch)
    {
        this.headBranch = headBranch;
    }

    @Override
    protected boolean isValidItem(@Nullable String item)
    {
        return item != null && !item.isBlank();
    }

    @Override
    protected String itemLabel(String item)
    {
        return item;
    }

    @Override
    protected String emptyListText()
    {
        return "No branches";
    }

    @Override
    protected String noSelectionText()
    {
        return "-";
    }

    @Override
    protected void renderClosedIcon(GuiContext ctx, int x, int y)
    {
        Icons.GITMATICA_BRANCH.renderScaledAt(ctx, x, y, BRANCH_ICON_SIZE, BRANCH_ICON_SIZE, 0);
    }

    @Override
    protected void renderRowIcon(GuiContext ctx, String branch, int x, int y)
    {
        Icons icon = this.isHeadBranch(branch) ? Icons.GITMATICA_CHECK : Icons.GITMATICA_BRANCH;
        icon.renderScaledAt(ctx, x, y, BRANCH_ICON_SIZE, BRANCH_ICON_SIZE, 0);
    }

    @Override
    protected List<String> closedTooltipLines()
    {
        return List.of(StringUtils.translate(OPTIONS_TOOLTIP_KEY));
    }

    @Override
    protected int iconSize()
    {
        return BRANCH_ICON_SIZE;
    }

    private boolean isHeadBranch(String branch)
    {
        return this.headBranch != null && this.headBranch.equals(branch);
    }
}
