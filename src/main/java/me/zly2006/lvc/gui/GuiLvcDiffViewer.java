package me.zly2006.lvc.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.gui.GuiMainMenu.ButtonListenerChangeMenu;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.render.infohud.RenderPhase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.SortCriteria;
import fi.dy.masa.litematica.schematic.verifier.VerifierResultSorter;
import fi.dy.masa.litematica.schematic.verifier.inventory.VerifierInventoryOverlay;
import fi.dy.masa.litematica.schematic.verifier.inventory.VerifierInventoryPreview;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.game.BlockUtils;
import me.zly2006.lvc.LvcDiagnostics;

public class GuiLvcDiffViewer extends GuiListBase<GuiLvcDiffViewer.DiffEntry, WidgetLvcDiffViewerEntry, WidgetListLvcDiffViewerEntries>
        implements ISelectionListener<GuiLvcDiffViewer.DiffEntry>, ICompletionListener
{
    private static SchematicVerifier verifierLast;
    private final SchematicPlacement placement;
    private final SchematicVerifier verifier;
    private final EnumMap<DiffSection, Boolean> sectionExpanded = new EnumMap<>(DiffSection.class);
    private final EnumMap<DiffKind, Boolean> kindExpanded = new EnumMap<>(DiffKind.class);
    private boolean kindDefaultsInitialized;
    @Nullable private BlockMismatch inventoryPreviewMismatch;

    public GuiLvcDiffViewer(SchematicPlacement placement)
    {
        super(10, 60);
        this.title = StringUtils.translate("litematica.gui.title.lvc_diff_viewer", placement.getName());
        this.placement = placement;
        this.verifier = placement.getSchematicVerifier();
        this.verifier.setLvcDiffInfoHud(true);
        this.sectionExpanded.put(DiffSection.INVENTORIES, this.verifier.getWrongInventories() > 0);
        this.sectionExpanded.put(DiffSection.BLOCKS, true);
        this.sectionExpanded.put(DiffSection.ENTITIES, false);

        if (this.verifier != verifierLast)
        {
            WidgetLvcDiffViewerEntry.setMaxNameLengths(this.allDiffEntries());
            verifierLast = this.verifier;
        }
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.getScreenWidth() - 20;
    }

    @Override
    public int getBrowserHeight()
    {
        return this.getScreenHeight() - 94;
    }

    @Override
    public void initGui()
    {
        super.initGui();

        int x = 12;
        int y = 20;

        x += this.createButton(x, y, -1, ButtonListener.Type.START) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.STOP) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.RESET_VERIFIER) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.SET_LIST_TYPE) + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.RESET_IGNORED) + 4;
        this.createButton(x, y, -1, ButtonListener.Type.TOGGLE_INFO_HUD);

        y = this.getScreenHeight() - 36;
        this.addStatusLabels(y);

        ButtonListenerChangeMenu.ButtonType type = ButtonListenerChangeMenu.ButtonType.MAIN_MENU;
        String label = StringUtils.translate(type.getLabelKey());
        int buttonWidth = this.getStringWidth(label) + 20;
        x = this.getScreenWidth() - buttonWidth - 10;
        ButtonGeneric button = new ButtonGeneric(x, y, buttonWidth, 20, label);
        this.addButton(button, new ButtonListenerChangeMenu(type, this.getParent()));
    }

    private void addStatusLabels(int y)
    {
        if (this.verifier.isActive())
        {
            String str = StringUtils.translate("litematica.gui.label.schematic_verifier.status.verifying",
                    this.verifier.getUnseenChunks(), this.verifier.getTotalChunks());
            this.addLabel(12, y, 100, 12, 0xFFF0F0F0, str);
            return;
        }

        if (this.verifier.isFinished())
        {
            DiffCounts counts = this.diffCounts();
            String str = StringUtils.translate("litematica.gui.label.lvc_diff_viewer.status.blocks",
                    counts.added(), counts.removed(), counts.changedStates(), counts.changedBlocks());
            this.addLabel(12, y, 100, 12, 0xFFF0F0F0, str);
            str = StringUtils.translate("litematica.gui.label.schematic_verifier.status.done_correct_total",
                    this.verifier.getCorrectStatesCount(), this.verifier.getSchematicTotalBlocks());
            this.addLabel(12, y + 14, 100, 12, 0xFFF0F0F0, str);
        }
    }

    private int createButton(int x, int y, int width, ButtonListener.Type type)
    {
        ButtonListener listener = new ButtonListener(type, this);
        boolean enabled = true;
        String label = "";

        switch (type)
        {
            case START:
                if (this.verifier.isPaused())
                {
                    label = StringUtils.translate("litematica.gui.button.schematic_verifier.resume");
                }
                else
                {
                    label = StringUtils.translate("litematica.gui.button.schematic_verifier.start");
                    enabled = !this.verifier.isActive();
                }
                break;
            case STOP:
                label = StringUtils.translate("litematica.gui.button.schematic_verifier.stop");
                enabled = this.verifier.isActive();
                break;
            case RESET_VERIFIER:
                label = StringUtils.translate("litematica.gui.button.schematic_verifier.reset_verifier");
                enabled = this.verifier.isActive() || this.verifier.isPaused() || this.verifier.isFinished();
                break;
            case SET_LIST_TYPE:
                label = StringUtils.translate("litematica.gui.button.schematic_verifier.range_type",
                        this.placement.getSchematicVerifierType().getDisplayName());
                break;
            case RESET_IGNORED:
                label = StringUtils.translate("litematica.gui.button.schematic_verifier.reset_ignored");
                enabled = this.verifier.getIgnoredMismatches().size() > 0;
                break;
            case TOGGLE_INFO_HUD:
                boolean val = InfoHud.getInstance().isEnabled() && this.verifier.getShouldRenderText(RenderPhase.POST);
                String str = (val ? TXT_GREEN : TXT_RED) + StringUtils.translate("litematica.message.value." + (val ? "on" : "off")) + TXT_RST;
                label = StringUtils.translate("litematica.gui.button.schematic_verifier.toggle_info_hud", str);
                break;
        }

        if (width == -1)
        {
            width = this.getStringWidth(label) + 10;
        }

        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        button.setEnabled(enabled);
        this.addButton(button, listener);
        return width;
    }

    public SchematicPlacement getPlacement()
    {
        return this.placement;
    }

    public boolean isSectionExpanded(DiffSection section)
    {
        return this.sectionExpanded.getOrDefault(section, false);
    }

    public boolean isKindExpanded(DiffKind kind)
    {
        return this.kindExpanded.getOrDefault(kind, false);
    }

    public void initializeKindDefaults(Map<DiffKind, Integer> counts)
    {
        if (this.kindDefaultsInitialized)
        {
            return;
        }

        for (DiffKind kind : DiffKind.values())
        {
            this.kindExpanded.put(kind, counts.getOrDefault(kind, 0) > 0);
        }

        this.kindDefaultsInitialized = true;
    }

    public EnumMap<DiffKind, List<BlockMismatch>> blockDiffsByKind()
    {
        EnumMap<DiffKind, List<BlockMismatch>> result = new EnumMap<>(DiffKind.class);

        for (DiffKind kind : DiffKind.values())
        {
            result.put(kind, new ArrayList<>());
        }

        result.get(DiffKind.ADDED).addAll(this.verifier.getMismatchOverviewFor(MismatchType.EXTRA));
        result.get(DiffKind.REMOVED).addAll(this.verifier.getMismatchOverviewFor(MismatchType.MISSING));

        for (BlockMismatch mismatch : this.verifier.getMismatchOverviewFor(MismatchType.WRONG_STATE))
        {
            if (mismatch.stateExpected.getBlock() == mismatch.stateFound.getBlock())
            {
                result.get(DiffKind.CHANGED_STATES).add(mismatch);
            }
            else
            {
                result.get(DiffKind.CHANGED_BLOCKS).add(mismatch);
            }
        }

        result.get(DiffKind.CHANGED_BLOCKS).addAll(this.verifier.getMismatchOverviewFor(MismatchType.WRONG_BLOCK));
        result.get(DiffKind.CHANGED_BLOCKS).addAll(this.verifier.getMismatchOverviewFor(MismatchType.DIFF_BLOCK));
        VerifierResultSorter sorter = new VerifierResultSorter(this.verifier);

        for (List<BlockMismatch> list : result.values())
        {
            list.sort(sorter);
        }

        return result;
    }

    public List<BlockMismatch> inventoryDiffEntries()
    {
        List<BlockMismatch> entries = new ArrayList<>(this.verifier.getMismatchOverviewFor(MismatchType.WRONG_INVENTORIES));
        entries.sort(new VerifierResultSorter(this.verifier));
        return entries;
    }

    private List<BlockMismatch> blockDiffEntries()
    {
        List<BlockMismatch> entries = new ArrayList<>();

        for (List<BlockMismatch> list : this.blockDiffsByKind().values())
        {
            entries.addAll(list);
        }

        return entries;
    }

    private List<BlockMismatch> allDiffEntries()
    {
        List<BlockMismatch> entries = new ArrayList<>(this.inventoryDiffEntries());
        entries.addAll(this.blockDiffEntries());
        return entries;
    }

    private DiffCounts diffCounts()
    {
        EnumMap<DiffKind, List<BlockMismatch>> diffs = this.blockDiffsByKind();
        return new DiffCounts(
                count(diffs.get(DiffKind.ADDED)),
                count(diffs.get(DiffKind.REMOVED)),
                count(diffs.get(DiffKind.CHANGED_STATES)),
                count(diffs.get(DiffKind.CHANGED_BLOCKS))
        );
    }

    private static int count(List<BlockMismatch> mismatches)
    {
        int count = 0;

        for (BlockMismatch mismatch : mismatches)
        {
            count += mismatch.count;
        }

        return count;
    }

    @Override
    public void onTaskCompleted()
    {
        if (GuiUtils.getCurrentScreen() == this)
        {
            List<BlockMismatch> inventoryEntries = this.inventoryDiffEntries();

            if (inventoryEntries.isEmpty() == false)
            {
                this.sectionExpanded.put(DiffSection.INVENTORIES, true);
            }

            WidgetLvcDiffViewerEntry.setMaxNameLengths(this.allDiffEntries());
            LvcDiagnostics.debug("diff viewer verifier completed placement={} inventoryRows={} blockRows={}",
                    this.placement.getName(), inventoryEntries.size(), this.blockDiffEntries().size());
            this.initGui();
        }
    }

    @Nullable
    public VerifierInventoryPreview getInventoryPreview()
    {
        return this.inventoryPreviewMismatch != null ? this.inventoryPreviewMismatch.getInventoryPreview() : null;
    }

    public void toggleInventoryPreview(BlockMismatch mismatch)
    {
        this.inventoryPreviewMismatch = this.inventoryPreviewMismatch == mismatch ? null : mismatch;
    }

    @Override
    public void onSelectionChange(@Nullable DiffEntry entry)
    {
        if (entry == null)
        {
            return;
        }

        if (entry.type() == DiffEntry.Type.SECTION && entry.section() != null)
        {
            this.sectionExpanded.put(entry.section(), !this.isSectionExpanded(entry.section()));
            this.getListWidget().refreshEntries();
            return;
        }

        if (entry.type() == DiffEntry.Type.KIND && entry.kind() != null)
        {
            this.kindExpanded.put(entry.kind(), !this.isKindExpanded(entry.kind()));
            this.getListWidget().refreshEntries();
            return;
        }

        if (entry.type() == DiffEntry.Type.DATA && entry.mismatch() != null)
        {
            this.verifier.toggleMismatchEntrySelected(entry.mismatch());

            if (!Configs.InfoOverlays.VERIFIER_OVERLAY_ENABLED.getBooleanValue())
            {
                String name = Configs.InfoOverlays.VERIFIER_OVERLAY_ENABLED.getName();
                String hotkeyName = Hotkeys.TOGGLE_VERIFIER_OVERLAY_RENDERING.getName();
                String hotkeyVal = Hotkeys.TOGGLE_VERIFIER_OVERLAY_RENDERING.getKeybind().getKeysDisplayString();
                InfoUtils.showGuiOrInGameMessage(MessageType.WARNING,
                        "litematica.message.warn.schematic_verifier.overlay_disabled", name, hotkeyName, hotkeyVal);
            }
        }
    }

    @Override
    protected ISelectionListener<DiffEntry> getSelectionListener()
    {
        return this;
    }

    @Override
    protected WidgetListLvcDiffViewerEntries createListWidget(int listX, int listY)
    {
        return new WidgetListLvcDiffViewerEntries(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    public enum DiffSection
    {
        INVENTORIES("litematica.gui.label.lvc_diff_viewer.inventories"),
        BLOCKS("litematica.gui.label.lvc_diff_viewer.blocks"),
        ENTITIES("litematica.gui.label.lvc_diff_viewer.entities");

        private final String translationKey;

        DiffSection(String translationKey)
        {
            this.translationKey = translationKey;
        }

        public String displayName()
        {
            return StringUtils.translate(this.translationKey);
        }
    }

    public enum DiffKind
    {
        ADDED("litematica.gui.label.lvc_diff_viewer.added", GuiBase.TXT_GREEN),
        REMOVED("litematica.gui.label.lvc_diff_viewer.removed", GuiBase.TXT_RED),
        CHANGED_STATES("litematica.gui.label.lvc_diff_viewer.changed_states", GuiBase.TXT_YELLOW),
        CHANGED_BLOCKS("litematica.gui.label.lvc_diff_viewer.changed_blocks", GuiBase.TXT_GOLD);

        private final String translationKey;
        private final String colorCode;

        DiffKind(String translationKey, String colorCode)
        {
            this.translationKey = translationKey;
            this.colorCode = colorCode;
        }

        public String displayName()
        {
            return StringUtils.translate(this.translationKey);
        }

        public String formattedDisplayName()
        {
            return this.colorCode + this.displayName() + GuiBase.TXT_RST;
        }
    }

    public record DiffEntry(Type type, @Nullable DiffSection section, @Nullable DiffKind kind,
                            @Nullable BlockMismatch mismatch, @Nullable String label, int count)
    {
        public static DiffEntry section(DiffSection section, int count)
        {
            return new DiffEntry(Type.SECTION, section, null, null, section.displayName(), count);
        }

        public static DiffEntry kind(DiffKind kind, int count)
        {
            return new DiffEntry(Type.KIND, null, kind, null, kind.formattedDisplayName(), count);
        }

        public static DiffEntry data(DiffKind kind, BlockMismatch mismatch)
        {
            return new DiffEntry(Type.DATA, null, kind, mismatch, null, mismatch.count);
        }

        public static DiffEntry inventory(BlockMismatch mismatch)
        {
            return new DiffEntry(Type.DATA, null, null, mismatch, null, mismatch.count);
        }

        public static DiffEntry empty(String label)
        {
            return new DiffEntry(Type.EMPTY, null, null, null, label, 0);
        }

        public static DiffEntry header()
        {
            return new DiffEntry(Type.HEADER, null, null, null, null, 0);
        }

        public enum Type
        {
            HEADER,
            SECTION,
            KIND,
            DATA,
            EMPTY
        }
    }

    private record DiffCounts(int added, int removed, int changedStates, int changedBlocks)
    {
    }

    private record ButtonListener(Type type, GuiLvcDiffViewer parent) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case START:
                    if (this.parent.verifier.isPaused())
                    {
                        this.parent.verifier.resume();
                        break;
                    }

                    if (LvcVerifierStartWorkflow.startIfGitMatica(this.parent.placement, this.parent, this.parent::initGui))
                    {
                        return;
                    }
                    break;
                case STOP:
                    this.parent.verifier.stopVerification();
                    break;
                case RESET_VERIFIER:
                    this.parent.verifier.reset();
                    break;
                case SET_LIST_TYPE:
                    this.parent.verifier.reset();
                    BlockInfoListType type = this.parent.placement.getSchematicVerifierType();
                    this.parent.placement.setSchematicVerifierType((BlockInfoListType) type.cycle(mouseButton == 0));
                    break;
                case RESET_IGNORED:
                    this.parent.verifier.resetIgnoredStateMismatches();
                    break;
                case TOGGLE_INFO_HUD:
                    SchematicVerifier verifier = this.parent.verifier;
                    verifier.toggleShouldRenderInfoHUD();

                    if (verifier.getShouldRenderText(RenderPhase.POST))
                    {
                        InfoHud.getInstance().addInfoHudRenderer(verifier, true);
                    }
                    else
                    {
                        InfoHud.getInstance().removeInfoHudRenderer(verifier, false);
                    }
                    break;
            }

            this.parent.initGui();
        }

        enum Type
        {
            START,
            STOP,
            RESET_VERIFIER,
            SET_LIST_TYPE,
            RESET_IGNORED,
            TOGGLE_INFO_HUD
        }
    }
}

class WidgetListLvcDiffViewerEntries extends WidgetListBase<GuiLvcDiffViewer.DiffEntry, WidgetLvcDiffViewerEntry>
{
    private static int lastScrollbarPosition;
    private final GuiLvcDiffViewer parent;
    private boolean scrollbarRestored;

    WidgetListLvcDiffViewerEntries(int x, int y, int width, int height, GuiLvcDiffViewer parent)
    {
        super(x, y, width, height, parent);
        this.browserEntryHeight = 22;
        this.parent = parent;
        this.allowMultiSelection = true;
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);

        VerifierInventoryPreview preview = this.parent.getInventoryPreview();

        if (preview != null)
        {
            VerifierInventoryOverlay.renderPreviewTooltip(ctx, preview, mouseX, mouseY);
        }

        lastScrollbarPosition = this.scrollBar.getValue();
    }

    @Override
    protected void offsetSelectionOrScrollbar(int amount, boolean changeSelection)
    {
        super.offsetSelectionOrScrollbar(amount, changeSelection);
        lastScrollbarPosition = this.scrollBar.getValue();
    }

    @Override
    protected WidgetLvcDiffViewerEntry createHeaderWidget(int x, int y, int listIndexStart, int usableHeight, int usedHeight)
    {
        int height = this.browserEntryHeight;

        if ((usedHeight + height) > usableHeight)
        {
            return null;
        }

        return this.createListEntryWidget(x, y, listIndexStart, true, GuiLvcDiffViewer.DiffEntry.header());
    }

    @Override
    public void refreshEntries()
    {
        super.refreshEntries();
    }

    @Override
    protected void refreshBrowserEntries()
    {
        this.listContents.clear();
        List<BlockMismatch> inventoryDiffs = this.parent.inventoryDiffEntries();
        int inventoryCount = count(inventoryDiffs);
        EnumMap<GuiLvcDiffViewer.DiffKind, List<BlockMismatch>> diffs = this.parent.blockDiffsByKind();
        EnumMap<GuiLvcDiffViewer.DiffKind, Integer> counts = new EnumMap<>(GuiLvcDiffViewer.DiffKind.class);
        List<BlockMismatch> allMismatches = new ArrayList<>(inventoryDiffs);

        for (Map.Entry<GuiLvcDiffViewer.DiffKind, List<BlockMismatch>> entry : diffs.entrySet())
        {
            counts.put(entry.getKey(), count(entry.getValue()));
            allMismatches.addAll(entry.getValue());
        }

        this.parent.initializeKindDefaults(counts);
        WidgetLvcDiffViewerEntry.setMaxNameLengths(allMismatches);
        this.addInventorySection(inventoryDiffs, inventoryCount);
        this.addBlockSection(diffs, counts);
        this.addSection(GuiLvcDiffViewer.DiffSection.ENTITIES, 0);
        this.reCreateListEntryWidgets();

        if (!this.scrollbarRestored && lastScrollbarPosition <= this.scrollBar.getMaxValue())
        {
            this.scrollBar.setValue(lastScrollbarPosition);
            this.scrollbarRestored = true;
            this.reCreateListEntryWidgets();
        }
    }

    private void addSection(GuiLvcDiffViewer.DiffSection section, int count)
    {
        this.listContents.add(GuiLvcDiffViewer.DiffEntry.section(section, count));

        if (this.parent.isSectionExpanded(section))
        {
            this.listContents.add(GuiLvcDiffViewer.DiffEntry.empty(StringUtils.translate("litematica.gui.label.lvc_diff_viewer.empty_category")));
        }
    }

    private void addInventorySection(List<BlockMismatch> inventories, int count)
    {
        this.listContents.add(GuiLvcDiffViewer.DiffEntry.section(GuiLvcDiffViewer.DiffSection.INVENTORIES, count));

        if (!this.parent.isSectionExpanded(GuiLvcDiffViewer.DiffSection.INVENTORIES))
        {
            return;
        }

        if (inventories.isEmpty())
        {
            this.listContents.add(GuiLvcDiffViewer.DiffEntry.empty(StringUtils.translate("litematica.gui.label.lvc_diff_viewer.empty_category")));
            return;
        }

        for (BlockMismatch mismatch : inventories)
        {
            this.listContents.add(GuiLvcDiffViewer.DiffEntry.inventory(mismatch));
        }
    }

    private void addBlockSection(EnumMap<GuiLvcDiffViewer.DiffKind, List<BlockMismatch>> diffs,
                                 EnumMap<GuiLvcDiffViewer.DiffKind, Integer> counts)
    {
        int total = 0;

        for (Integer count : counts.values())
        {
            total += count;
        }

        this.listContents.add(GuiLvcDiffViewer.DiffEntry.section(GuiLvcDiffViewer.DiffSection.BLOCKS, total));

        if (!this.parent.isSectionExpanded(GuiLvcDiffViewer.DiffSection.BLOCKS))
        {
            return;
        }

        for (GuiLvcDiffViewer.DiffKind kind : GuiLvcDiffViewer.DiffKind.values())
        {
            int count = counts.getOrDefault(kind, 0);
            this.listContents.add(GuiLvcDiffViewer.DiffEntry.kind(kind, count));

            if (this.parent.isKindExpanded(kind))
            {
                List<BlockMismatch> list = diffs.getOrDefault(kind, List.of());

                if (list.isEmpty())
                {
                    this.listContents.add(GuiLvcDiffViewer.DiffEntry.empty(StringUtils.translate("litematica.gui.label.lvc_diff_viewer.empty_category")));
                }
                else
                {
                    for (BlockMismatch mismatch : list)
                    {
                        this.listContents.add(GuiLvcDiffViewer.DiffEntry.data(kind, mismatch));
                    }
                }
            }
        }
    }

    private static int count(List<BlockMismatch> mismatches)
    {
        int count = 0;

        for (BlockMismatch mismatch : mismatches)
        {
            count += mismatch.count;
        }

        return count;
    }

    @Override
    protected WidgetLvcDiffViewerEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, GuiLvcDiffViewer.DiffEntry entry)
    {
        return new WidgetLvcDiffViewerEntry(x, y, this.browserEntryWidth, this.getBrowserEntryHeightFor(entry),
                isOdd, this, this.parent, entry, listIndex);
    }
}

class WidgetLvcDiffViewerEntry extends WidgetListEntrySortable<GuiLvcDiffViewer.DiffEntry>
{
    private static final int RIGHT_MOUSE_BUTTON = 1;
    private static final String HEADER_BEFORE = "litematica.gui.label.lvc_diff_viewer.before";
    private static final String HEADER_AFTER = "litematica.gui.label.lvc_diff_viewer.after";
    private static final String HEADER_COUNT = "litematica.gui.label.schematic_verifier.count";
    private static int maxNameLengthBefore;
    private static int maxNameLengthAfter;
    private static int maxCountLength;

    private final WidgetListLvcDiffViewerEntries listWidget;
    private final GuiLvcDiffViewer gui;
    private final SchematicVerifier verifier;
    private final GuiLvcDiffViewer.DiffEntry entry;
    private final boolean isOdd;
    @Nullable private final DiffMismatchInfo mismatchInfo;
    @Nullable private final ButtonGeneric buttonIgnore;

    WidgetLvcDiffViewerEntry(int x, int y, int width, int height, boolean isOdd,
                             WidgetListLvcDiffViewerEntries listWidget, GuiLvcDiffViewer gui,
                             GuiLvcDiffViewer.DiffEntry entry, int listIndex)
    {
        super(x, y, width, height, entry, listIndex);
        this.columnCount = 3;
        this.listWidget = listWidget;
        this.gui = gui;
        this.verifier = gui.getPlacement().getSchematicVerifier();
        this.entry = entry;
        this.isOdd = isOdd;

        if (entry.type() == GuiLvcDiffViewer.DiffEntry.Type.DATA && entry.mismatch() != null)
        {
            this.mismatchInfo = new DiffMismatchInfo(entry.mismatch().stateExpected, entry.mismatch().stateFound);
            this.buttonIgnore = this.createButton(this.x + this.width, y + 1, ButtonListener.ButtonType.IGNORE_MISMATCH);
        }
        else
        {
            this.mismatchInfo = null;
            this.buttonIgnore = null;
        }
    }

    public static void setMaxNameLengths(List<BlockMismatch> mismatches)
    {
        maxNameLengthBefore = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_BEFORE) + GuiBase.TXT_RST);
        maxNameLengthAfter = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_AFTER) + GuiBase.TXT_RST);
        maxCountLength = 7 * StringUtils.getStringWidth("8");

        for (BlockMismatch mismatch : mismatches)
        {
            ItemStack stack = ItemUtils.getItemForState(mismatch.stateExpected);
            String name = WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(mismatch.stateExpected, stack);
            maxNameLengthBefore = Math.max(maxNameLengthBefore, StringUtils.getStringWidth(name));
            stack = ItemUtils.getItemForState(mismatch.stateFound);
            name = WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(mismatch.stateFound, stack);
            maxNameLengthAfter = Math.max(maxNameLengthAfter, StringUtils.getStringWidth(name));
        }

        maxCountLength = Math.max(maxCountLength, StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_COUNT) + GuiBase.TXT_RST));
    }

    private ButtonGeneric createButton(int x, int y, ButtonListener.ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, type.getDisplayName());
        return this.addButton(button, new ButtonListener(type, this.entry, this.gui));
    }

    @Override
    protected int getCurrentSortColumn()
    {
        return this.verifier.getSortCriteria().ordinal();
    }

    @Override
    protected boolean getSortInReverse()
    {
        return this.verifier.getSortInReverse();
    }

    @Override
    protected int getColumnPosX(int column)
    {
        int x1 = this.x + 4;
        int x2 = x1 + maxNameLengthBefore + 40;
        int x3 = x2 + maxNameLengthAfter + 40;

        return switch (column)
        {
            case 0 -> x1;
            case 1 -> x2;
            case 2 -> x3;
            case 3 -> x3 + maxCountLength + 20;
            default -> x1;
        };
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        if (click.input() == RIGHT_MOUSE_BUTTON && this.canShowInventoryPreview() &&
            (this.buttonIgnore == null || click.x() < this.buttonIgnore.getX()))
        {
            this.gui.toggleInventoryPreview(this.entry.mismatch());
            return true;
        }

        if (super.onMouseClickedImpl(click, doubleClick))
        {
            return true;
        }

        if (this.entry.type() != GuiLvcDiffViewer.DiffEntry.Type.HEADER)
        {
            return false;
        }

        int column = this.getMouseOverColumn((int) click.x(), (int) click.y());

        switch (column)
        {
            case 0 -> this.verifier.setSortCriteria(SortCriteria.NAME_EXPECTED);
            case 1 -> this.verifier.setSortCriteria(SortCriteria.NAME_FOUND);
            case 2 -> this.verifier.setSortCriteria(SortCriteria.COUNT);
            default ->
            {
                return false;
            }
        }

        this.listWidget.refreshEntries();
        return true;
    }

    @Override
    public boolean canSelectAt(MouseButtonEvent click)
    {
        if (this.entry.type() == GuiLvcDiffViewer.DiffEntry.Type.EMPTY)
        {
            return false;
        }

        return (this.buttonIgnore == null || click.x() < this.buttonIgnore.getX()) && super.canSelectAt(click);
    }

    protected boolean shouldRenderAsSelected()
    {
        return this.entry.type() == GuiLvcDiffViewer.DiffEntry.Type.DATA &&
                this.entry.mismatch() != null &&
                this.verifier.isMismatchEntrySelected(this.entry.mismatch());
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        selected = this.shouldRenderAsSelected();
        int color = 0xA0303030;

        if (selected)
        {
            color = 0xA0707070;
        }
        else if (this.isMouseOver(mouseX, mouseY))
        {
            color = 0xA0505050;
        }
        else if (this.isOdd)
        {
            color = 0xA0101010;
        }

        RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, color);

        if (selected)
        {
            RenderUtils.drawOutline(ctx, this.x, this.y, this.width, this.height, 0xFFE0E0E0);
        }

        int x1 = this.getColumnPosX(0);
        int x2 = this.getColumnPosX(1);
        int x3 = this.getColumnPosX(2);
        int y = this.y + 7;

        switch (this.entry.type())
        {
            case HEADER ->
            {
                this.drawString(ctx, x1, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate(HEADER_BEFORE) + GuiBase.TXT_RST);
                this.drawString(ctx, x2, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate(HEADER_AFTER) + GuiBase.TXT_RST);
                this.drawString(ctx, x3, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate(HEADER_COUNT) + GuiBase.TXT_RST);
                this.renderColumnHeader(ctx, mouseX, mouseY, Icons.ARROW_DOWN, Icons.ARROW_UP);
            }
            case SECTION, KIND ->
            {
                String marker = this.isExpanded() ? "[-] " : "[+] ";
                this.drawString(ctx, this.x + 4, y, 0xFFFFFFFF, marker + Objects.toString(this.entry.label(), "") + " (" + this.entry.count() + ")");
            }
            case EMPTY -> this.drawString(ctx, this.x + 18, y, 0xFFB0B0B0, Objects.toString(this.entry.label(), ""));
            case DATA -> this.renderDataRow(ctx, x1, x2, x3);
        }

        super.render(ctx, mouseX, mouseY, selected);
    }

    private boolean isExpanded()
    {
        if (this.entry.section() != null)
        {
            return this.gui.isSectionExpanded(this.entry.section());
        }

        if (this.entry.kind() != null)
        {
            return this.gui.isKindExpanded(this.entry.kind());
        }

        return false;
    }

    private void renderDataRow(GuiContext ctx, int x1, int x2, int x3)
    {
        if (this.mismatchInfo == null || this.entry.mismatch() == null)
        {
            return;
        }

        int y = this.y + 7;
        this.drawString(ctx, x1 + 20, y, 0xFFFFFFFF, this.mismatchInfo.nameBefore);
        this.drawString(ctx, x2 + 20, y, 0xFFFFFFFF, this.mismatchInfo.nameAfter);
        this.drawString(ctx, x3, y, 0xFFFFFFFF, String.valueOf(this.entry.mismatch().count));

        y = this.y + 3;
        RenderUtils.drawRect(ctx, x1, y, 16, 16, 0x20FFFFFF);
        RenderUtils.drawRect(ctx, x2, y, 16, 16, 0x20FFFFFF);
        this.renderStateIcon(ctx, x1, y, this.mismatchInfo.stateBefore, this.mismatchInfo.stackBefore);
        this.renderStateIcon(ctx, x2, y, this.mismatchInfo.stateAfter, this.mismatchInfo.stackAfter);
    }

    private void renderStateIcon(GuiContext ctx, int x, int y, BlockState state, ItemStack stack)
    {
        boolean hasModel = state.getRenderShape() == RenderShape.MODEL;
        boolean isAirItem = stack.isEmpty();
        boolean useBlockModel = hasModel && (isAirItem || state.getBlock() == Blocks.FLOWER_POT);

        if (useBlockModel && fi.dy.masa.litematica.render.RenderUtils.stateModelHasQuads(state))
        {
            WidgetSchematicVerificationResult.renderModelInGui(ctx, x, y, 1, state);
        }
        else
        {
            ctx.renderItem(stack, x, y);
            ctx.renderItemDecorations(this.textRenderer, stack, x, y);
        }
    }

    @Override
    public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        if (this.canShowInventoryPreview() && (this.buttonIgnore == null || mouseX < this.buttonIgnore.getX()))
        {
            RenderUtils.drawHoverText(ctx, mouseX, mouseY, List.of(StringUtils.translate("litematica.gui.label.schematic_verifier.inventory_preview_hint")));
            return;
        }

        if (this.mismatchInfo != null && this.buttonIgnore != null && mouseX < this.buttonIgnore.getX())
        {
            ctx.pose().pushMatrix();
            ctx.pose().translate(0, 0);
            int x = mouseX + 10;
            int y = mouseY;
            int width = this.mismatchInfo.totalWidth();
            int height = this.mismatchInfo.totalHeight();

            if (x + width > GuiUtils.getCurrentScreenWidth())
            {
                x = mouseX - width - 10;
            }

            if (y + height > GuiUtils.getCurrentScreenHeight())
            {
                y = mouseY - height - 2;
            }

            this.mismatchInfo.render(ctx, x, y);
            ctx.pose().popMatrix();
        }
    }

    private boolean canShowInventoryPreview()
    {
        return this.entry.type() == GuiLvcDiffViewer.DiffEntry.Type.DATA &&
               this.entry.mismatch() != null &&
               this.entry.mismatch().hasInventoryPreview();
    }

    private record DiffMismatchInfo(BlockState stateBefore, BlockState stateAfter, ItemStack stackBefore,
                                    ItemStack stackAfter, String registryBefore, String registryAfter,
                                    String nameBefore, String nameAfter, int totalWidth, int totalHeight,
                                    int columnWidthBefore)
    {
        DiffMismatchInfo(BlockState stateBefore, BlockState stateAfter)
        {
            this(stateBefore, stateAfter, ItemUtils.getItemForState(stateBefore), ItemUtils.getItemForState(stateAfter));
        }

        private DiffMismatchInfo(BlockState stateBefore, BlockState stateAfter, ItemStack stackBefore, ItemStack stackAfter)
        {
            this(stateBefore, stateAfter, stackBefore, stackAfter,
                    registryName(stateBefore), registryName(stateAfter),
                    WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(stateBefore, stackBefore),
                    WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(stateAfter, stackAfter));
        }

        private DiffMismatchInfo(BlockState stateBefore, BlockState stateAfter, ItemStack stackBefore, ItemStack stackAfter,
                                 String registryBefore, String registryAfter, String nameBefore, String nameAfter)
        {
            this(stateBefore, stateAfter, stackBefore, stackAfter, registryBefore, registryAfter, nameBefore, nameAfter,
                    totalWidth(stateBefore, stateAfter, registryBefore, registryAfter, nameBefore, nameAfter),
                    totalHeight(stateBefore, stateAfter),
                    columnWidth(stateBefore, registryBefore, nameBefore));
        }

        private static String registryName(BlockState state)
        {
            Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return id != null ? id.toString() : "<null>";
        }

        private static int columnWidth(BlockState state, String registryName, String displayName)
        {
            int width = Math.max(StringUtils.getStringWidth(displayName) + 20, StringUtils.getStringWidth(registryName));
            return Math.max(width, fi.dy.masa.litematica.render.RenderUtils.getMaxStringRenderLength(
                    BlockUtils.getFormattedBlockStateProperties(state, " = ")));
        }

        private static int totalWidth(BlockState before, BlockState after, String registryBefore, String registryAfter,
                                      String nameBefore, String nameAfter)
        {
            return columnWidth(before, registryBefore, nameBefore) + columnWidth(after, registryAfter, nameAfter) + 40;
        }

        private static int totalHeight(BlockState before, BlockState after)
        {
            List<String> propsBefore = BlockUtils.getFormattedBlockStateProperties(before, " = ");
            List<String> propsAfter = BlockUtils.getFormattedBlockStateProperties(after, " = ");
            return Math.max(propsBefore.size(), propsAfter.size()) * (StringUtils.getFontHeight() + 2) + 60;
        }

        void render(GuiContext ctx, int x, int y)
        {
            fi.dy.masa.litematica.render.RenderUtils.renderBackgroundMask(ctx, x + 1, y + 1, this.totalWidth - 1, this.totalHeight - 1);
            RenderUtils.drawOutlinedBox(ctx, x, y, this.totalWidth, this.totalHeight, 0xFF000000, GuiBase.COLOR_HORIZONTAL_BAR);
            int x1 = x + 10;
            int x2 = x + this.columnWidthBefore + 30;
            y += 4;
            String pre = GuiBase.TXT_WHITE + GuiBase.TXT_BOLD;
            ctx.drawString(ctx.fontRenderer(), pre + StringUtils.translate(HEADER_BEFORE) + GuiBase.TXT_RST, x1, y, 0xFFFFFFFF, false);
            ctx.drawString(ctx.fontRenderer(), pre + StringUtils.translate(HEADER_AFTER) + GuiBase.TXT_RST, x2, y, 0xFFFFFFFF, false);
            y += 12;
            RenderUtils.drawRect(ctx, x1, y, 16, 16, 0x50C0C0C0);
            RenderUtils.drawRect(ctx, x2, y, 16, 16, 0x50C0C0C0);
            int iconY = y;
            ctx.drawString(ctx.fontRenderer(), this.nameBefore, x1 + 20, y + 4, 0xFFFFFFFF, false);
            ctx.drawString(ctx.fontRenderer(), this.nameAfter, x2 + 20, y + 4, 0xFFFFFFFF, false);
            y += 20;
            ctx.drawString(ctx.fontRenderer(), this.registryBefore, x1, y, 0xFF4060FF, false);
            ctx.drawString(ctx.fontRenderer(), this.registryAfter, x2, y, 0xFF4060FF, false);
            y += StringUtils.getFontHeight() + 4;
            RenderUtils.renderText(ctx, x1, y, 0xFFB0B0B0,
                    BlockUtils.getFormattedBlockStateProperties(this.stateBefore, " = "));
            RenderUtils.renderText(ctx, x2, y, 0xFFB0B0B0,
                    BlockUtils.getFormattedBlockStateProperties(this.stateAfter, " = "));
            renderHoverIcon(ctx, x1, iconY, this.stateBefore, this.stackBefore);
            renderHoverIcon(ctx, x2, iconY, this.stateAfter, this.stackAfter);
        }

        private static void renderHoverIcon(GuiContext ctx, int x, int y, BlockState state, ItemStack stack)
        {
            boolean hasModel = state.getRenderShape() == RenderShape.MODEL;
            boolean isAirItem = stack.isEmpty();
            boolean useBlockModel = hasModel && (isAirItem || state.getBlock() == Blocks.FLOWER_POT);

            if (useBlockModel && fi.dy.masa.litematica.render.RenderUtils.stateModelHasQuads(state))
            {
                WidgetSchematicVerificationResult.renderModelInGui(ctx, x, y, 1, state);
            }
            else
            {
                ctx.renderItem(stack, x, y);
                ctx.renderItemDecorations(ctx.fontRenderer(), stack, x, y);
            }
        }
    }

    private record ButtonListener(ButtonType type, GuiLvcDiffViewer.DiffEntry entry,
                                  GuiLvcDiffViewer gui) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.type == ButtonType.IGNORE_MISMATCH && this.entry.mismatch() != null)
            {
                this.gui.getPlacement().getSchematicVerifier().ignoreStateMismatch(this.entry.mismatch());
                this.gui.initGui();
            }
        }

        enum ButtonType
        {
            IGNORE_MISMATCH("litematica.gui.button.schematic_verifier.ignore");

            private final String translationKey;

            ButtonType(String translationKey)
            {
                this.translationKey = translationKey;
            }

            public String getDisplayName()
            {
                return StringUtils.translate(this.translationKey);
            }
        }
    }
}
