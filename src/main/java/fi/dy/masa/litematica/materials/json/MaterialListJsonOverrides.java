package fi.dy.masa.litematica.materials.json;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.ApiStatus;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;

import fi.dy.masa.litematica.data.CachedTagManager;

@ApiStatus.Experimental
public class MaterialListJsonOverrides
{
    public static final MaterialListJsonOverrides INSTANCE = new MaterialListJsonOverrides();
    private final Set<ResultOverride> overrides = new HashSet<>();

    protected MaterialListJsonOverrides()
    {
        this.initOverrides();
    }

    private RegistryEntry<Item> add(Item item)
    {
        return Registries.ITEM.getEntry(item);
    }

    private void initOverrides()
    {
        // Copper Block
        this.overrides.add(new ResultOverride(this.add(Items.EXPOSED_COPPER),         this.add(Items.COPPER_BLOCK), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WEATHERED_COPPER),       this.add(Items.COPPER_BLOCK), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.OXIDIZED_COPPER),        this.add(Items.COPPER_BLOCK), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_EXPOSED_COPPER),   this.add(Items.WAXED_COPPER_BLOCK), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_WEATHERED_COPPER), this.add(Items.WAXED_COPPER_BLOCK), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_OXIDIZED_COPPER),  this.add(Items.WAXED_COPPER_BLOCK), 1.0F));
        // Copper Grate
        this.overrides.add(new ResultOverride(this.add(Items.EXPOSED_COPPER_GRATE),         this.add(Items.COPPER_GRATE), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WEATHERED_COPPER_GRATE),       this.add(Items.COPPER_GRATE), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.OXIDIZED_COPPER_GRATE),        this.add(Items.COPPER_GRATE), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_EXPOSED_COPPER_GRATE),   this.add(Items.WAXED_COPPER_GRATE), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_WEATHERED_COPPER_GRATE), this.add(Items.WAXED_COPPER_GRATE), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_OXIDIZED_COPPER_GRATE),  this.add(Items.WAXED_COPPER_GRATE), 1.0F));
        // Cut Copper
        this.overrides.add(new ResultOverride(this.add(Items.EXPOSED_CUT_COPPER),         this.add(Items.CUT_COPPER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WEATHERED_CUT_COPPER),       this.add(Items.CUT_COPPER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.OXIDIZED_CUT_COPPER),        this.add(Items.CUT_COPPER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_EXPOSED_CUT_COPPER),   this.add(Items.WAXED_CUT_COPPER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_WEATHERED_CUT_COPPER), this.add(Items.WAXED_CUT_COPPER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_OXIDIZED_CUT_COPPER),  this.add(Items.WAXED_CUT_COPPER), 1.0F));
        // Chiseled Copper
        this.overrides.add(new ResultOverride(this.add(Items.EXPOSED_CHISELED_COPPER),         this.add(Items.CHISELED_COPPER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WEATHERED_CHISELED_COPPER),       this.add(Items.CHISELED_COPPER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.OXIDIZED_CHISELED_COPPER),        this.add(Items.CHISELED_COPPER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_EXPOSED_CHISELED_COPPER),   this.add(Items.WAXED_CHISELED_COPPER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_WEATHERED_CHISELED_COPPER), this.add(Items.WAXED_CHISELED_COPPER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_OXIDIZED_CHISELED_COPPER),  this.add(Items.WAXED_CHISELED_COPPER), 1.0F));
        // Copper Bulb
        this.overrides.add(new ResultOverride(this.add(Items.EXPOSED_COPPER_BULB),         this.add(Items.COPPER_BULB), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WEATHERED_COPPER_BULB),       this.add(Items.COPPER_BULB), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.OXIDIZED_COPPER_BULB),        this.add(Items.COPPER_BULB), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_EXPOSED_COPPER_BULB),   this.add(Items.WAXED_COPPER_BULB), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_WEATHERED_COPPER_BULB), this.add(Items.WAXED_COPPER_BULB), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_OXIDIZED_COPPER_BULB),  this.add(Items.WAXED_COPPER_BULB), 1.0F));
        // Copper Slab
        this.overrides.add(new ResultOverride(this.add(Items.EXPOSED_CUT_COPPER_SLAB),         this.add(Items.CUT_COPPER_SLAB), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WEATHERED_CUT_COPPER_SLAB),       this.add(Items.CUT_COPPER_SLAB), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.OXIDIZED_CUT_COPPER_SLAB),        this.add(Items.CUT_COPPER_SLAB), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_EXPOSED_CUT_COPPER_SLAB),   this.add(Items.WAXED_CUT_COPPER_SLAB), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_WEATHERED_CUT_COPPER_SLAB), this.add(Items.WAXED_CUT_COPPER_SLAB), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_OXIDIZED_CUT_COPPER_SLAB),  this.add(Items.WAXED_CUT_COPPER_SLAB), 1.0F));
        // Copper Stairs
        this.overrides.add(new ResultOverride(this.add(Items.EXPOSED_CUT_COPPER_STAIRS),         this.add(Items.CUT_COPPER_STAIRS), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WEATHERED_CUT_COPPER_STAIRS),       this.add(Items.CUT_COPPER_STAIRS), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.OXIDIZED_CUT_COPPER_STAIRS),        this.add(Items.CUT_COPPER_STAIRS), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_EXPOSED_CUT_COPPER_STAIRS),   this.add(Items.WAXED_CUT_COPPER_STAIRS), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_WEATHERED_CUT_COPPER_STAIRS), this.add(Items.WAXED_CUT_COPPER_STAIRS), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_OXIDIZED_CUT_COPPER_STAIRS),  this.add(Items.WAXED_CUT_COPPER_STAIRS), 1.0F));
        // Copper Door
        this.overrides.add(new ResultOverride(this.add(Items.EXPOSED_COPPER_DOOR),         this.add(Items.COPPER_DOOR), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WEATHERED_COPPER_DOOR),       this.add(Items.COPPER_DOOR), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.OXIDIZED_COPPER_DOOR),        this.add(Items.COPPER_DOOR), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_EXPOSED_COPPER_DOOR),   this.add(Items.WAXED_COPPER_DOOR), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_WEATHERED_COPPER_DOOR), this.add(Items.WAXED_COPPER_DOOR), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_OXIDIZED_COPPER_DOOR),  this.add(Items.WAXED_COPPER_DOOR), 1.0F));
        // Copper Trap Door
        this.overrides.add(new ResultOverride(this.add(Items.EXPOSED_COPPER_TRAPDOOR),         this.add(Items.COPPER_TRAPDOOR), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WEATHERED_COPPER_TRAPDOOR),       this.add(Items.COPPER_TRAPDOOR), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.OXIDIZED_COPPER_TRAPDOOR),        this.add(Items.COPPER_TRAPDOOR), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_EXPOSED_COPPER_TRAPDOOR),   this.add(Items.WAXED_COPPER_TRAPDOOR), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_WEATHERED_COPPER_TRAPDOOR), this.add(Items.WAXED_COPPER_TRAPDOOR), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WAXED_OXIDIZED_COPPER_TRAPDOOR),  this.add(Items.WAXED_COPPER_TRAPDOOR), 1.0F));
        // Stripped Woods
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_ACACIA_LOG),   this.add(Items.ACACIA_LOG), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_BAMBOO_BLOCK), this.add(Items.BAMBOO), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_BIRCH_LOG),    this.add(Items.BIRCH_LOG), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_CHERRY_LOG),   this.add(Items.CHERRY_LOG), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_CRIMSON_STEM), this.add(Items.CRIMSON_STEM), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_DARK_OAK_LOG), this.add(Items.DARK_OAK_LOG), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_JUNGLE_LOG),   this.add(Items.JUNGLE_LOG), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_MANGROVE_LOG), this.add(Items.MANGROVE_LOG), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_OAK_LOG),      this.add(Items.OAK_LOG), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_PALE_OAK_LOG), this.add(Items.PALE_OAK_LOG), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_SPRUCE_LOG),   this.add(Items.SPRUCE_LOG), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.STRIPPED_WARPED_STEM),  this.add(Items.WARPED_STEM), 1.0F));
        // Concrete
        this.overrides.add(new ResultOverride(this.add(Items.BLACK_CONCRETE),       this.add(Items.BLACK_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.BLUE_CONCRETE),        this.add(Items.BLUE_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.BROWN_CONCRETE),       this.add(Items.BROWN_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.CYAN_CONCRETE),        this.add(Items.CYAN_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.GRAY_CONCRETE),        this.add(Items.GRAY_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.GREEN_CONCRETE),       this.add(Items.GREEN_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.LIGHT_BLUE_CONCRETE),  this.add(Items.LIGHT_BLUE_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.LIGHT_GRAY_CONCRETE),  this.add(Items.LIGHT_GRAY_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.LIME_CONCRETE),        this.add(Items.LIME_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.MAGENTA_CONCRETE),     this.add(Items.MAGENTA_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.ORANGE_CONCRETE),      this.add(Items.ORANGE_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.PINK_CONCRETE),        this.add(Items.PINK_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.PURPLE_CONCRETE),      this.add(Items.PURPLE_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.RED_CONCRETE),         this.add(Items.RED_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.YELLOW_CONCRETE),      this.add(Items.YELLOW_CONCRETE_POWDER), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.WHITE_CONCRETE),       this.add(Items.WHITE_CONCRETE_POWDER), 1.0F));
        // Anvils
        this.overrides.add(new ResultOverride(this.add(Items.CHIPPED_ANVIL), this.add(Items.ANVIL), 1.0F));
        this.overrides.add(new ResultOverride(this.add(Items.DAMAGED_ANVIL), this.add(Items.ANVIL), 1.0F));
    }

    protected Pair<RegistryEntry<Item>, Integer> matchOverride(RegistryEntry<Item> result, Integer total)
    {
        for (ResultOverride map : this.overrides)
        {
            if (map.match(result))
            {
                return Pair.of(map.result(), map.mul(total));
            }
        }

        return Pair.of(result, total);
    }

    // Overrides for re-dying recipe's
    protected RegistryEntry<Item> overridePrimaryMaterial(RegistryEntry<Item> firstItem)
    {
        if (firstItem.isIn(ItemTags.WOOL))
        {
            return Registries.ITEM.getEntry(Items.WHITE_WOOL);
        }
        else if (firstItem.isIn(ItemTags.WOOL_CARPETS))
        {
            return Registries.ITEM.getEntry(Items.WHITE_CARPET);
        }
        else if (firstItem.isIn(ItemTags.BEDS))
        {
            return Registries.ITEM.getEntry(Items.WHITE_BED);
        }
        else if (firstItem.isIn(ItemTags.CANDLES))
        {
            return Registries.ITEM.getEntry(Items.CANDLE);
        }
        else if (firstItem.isIn(ItemTags.SHULKER_BOXES))
        {
            return Registries.ITEM.getEntry(Items.SHULKER_BOX);
        }
        else if (firstItem.isIn(ItemTags.BANNERS))
        {
            return Registries.ITEM.getEntry(Items.WHITE_BANNER);
        }
        else if (firstItem.isIn(ItemTags.TERRACOTTA))
        {
            return Registries.ITEM.getEntry(Items.TERRACOTTA);
        }
        else if (firstItem.isIn(ItemTags.BUNDLES))
        {
            return Registries.ITEM.getEntry(Items.BUNDLE);
        }
        else if (firstItem.isIn(ItemTags.HARNESSES))
        {
            return Registries.ITEM.getEntry(Items.WHITE_HARNESS);
        }
        else if (CachedTagManager.matchItemTag(CachedTagManager.GLASS_ITEMS_KEY, firstItem))
        {
            return Registries.ITEM.getEntry(Items.GLASS);
        }
        else if (CachedTagManager.matchItemTag(CachedTagManager.GLASS_PANE_ITEMS_KEY, firstItem))
        {
            return Registries.ITEM.getEntry(Items.GLASS_PANE);
        }
        else if (CachedTagManager.matchItemTag(CachedTagManager.CONCRETE_POWDER_ITEMS_KEY, firstItem))
        {
            return Registries.ITEM.getEntry(Items.WHITE_CONCRETE_POWDER);
        }
        else if (CachedTagManager.matchItemTag(CachedTagManager.CONCRETE_ITEMS_KEY, firstItem))
        {
            return Registries.ITEM.getEntry(Items.WHITE_CONCRETE);
        }
        else if (CachedTagManager.matchItemTag(CachedTagManager.GLAZED_TERRACOTTA_ITEMS_KEY, firstItem))
        {
            return Registries.ITEM.getEntry(Items.WHITE_GLAZED_TERRACOTTA);
        }

        return this.matchOverride(firstItem, 1).getLeft();
    }

    // Overrides for particular cases, such as redying of beds instead of choosing the Wool recipe.
    protected boolean overrideShouldSkipRecipe(RegistryEntry<Item> input, List<Ingredient> ingredients)
    {
        for (Ingredient ing : ingredients)
        {
            if (input.isIn(ItemTags.BEDS))
            {
                if (ing.test(Items.WHITE_BED.getDefaultStack()) ||
                        ing.test(Items.BLACK_BED.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.WOOL))
            {
                if (ing.test(Items.WHITE_WOOL.getDefaultStack()) ||
                        ing.test(Items.BLACK_WOOL.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.WOOL_CARPETS))
            {
                if (ing.test(Items.WHITE_CARPET.getDefaultStack()) ||
                        ing.test(Items.BLACK_CARPET.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.CANDLES))
            {
                if (ing.test(Items.WHITE_CANDLE.getDefaultStack()) ||
                        ing.test(Items.BLACK_CANDLE.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.SHULKER_BOXES))
            {
                if (ing.test(Items.WHITE_SHULKER_BOX.getDefaultStack()) ||
                        ing.test(Items.BLACK_SHULKER_BOX.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.BANNERS))
            {
                if (ing.test(Items.WHITE_BANNER.getDefaultStack()) ||
                        ing.test(Items.BLACK_BANNER.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.TERRACOTTA))
            {
                if (ing.test(Items.WHITE_TERRACOTTA.getDefaultStack()) ||
                        ing.test(Items.BLACK_TERRACOTTA.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (CachedTagManager.matchItemTag(CachedTagManager.GLASS_ITEMS_KEY, input))
            {
                if (ing.test(Items.WHITE_STAINED_GLASS.getDefaultStack()) ||
                        ing.test(Items.BLACK_STAINED_GLASS.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (CachedTagManager.matchItemTag(CachedTagManager.GLASS_PANE_ITEMS_KEY, input))
            {
                if (ing.test(Items.WHITE_STAINED_GLASS_PANE.getDefaultStack()) ||
                        ing.test(Items.BLACK_STAINED_GLASS_PANE.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (CachedTagManager.matchItemTag(CachedTagManager.CONCRETE_ITEMS_KEY, input))
            {
                if (ing.test(Items.WHITE_CONCRETE.getDefaultStack()) ||
                        ing.test(Items.BLACK_CONCRETE.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (CachedTagManager.matchItemTag(CachedTagManager.CONCRETE_POWDER_ITEMS_KEY, input))
            {
                if (ing.test(Items.WHITE_CONCRETE_POWDER.getDefaultStack()) ||
                        ing.test(Items.BLACK_CONCRETE_POWDER.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (CachedTagManager.matchItemTag(CachedTagManager.GLAZED_TERRACOTTA_ITEMS_KEY, input))
            {
                if (ing.test(Items.WHITE_GLAZED_TERRACOTTA.getDefaultStack()) ||
                        ing.test(Items.BLACK_GLAZED_TERRACOTTA.getDefaultStack()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    protected boolean shouldKeepItemOrBlock(RegistryEntry<Item> input)
    {
        if (CachedTagManager.matchItemTag(CachedTagManager.PACKED_BLOCK_ITEMS_KEY, input))
        {
            return true;
        }
        else return CachedTagManager.matchItemTag(CachedTagManager.UNPACKED_BLOCK_ITEMS_KEY, input);
    }

    public record ResultOverride(RegistryEntry<Item> input, RegistryEntry<Item> result, Float multiplier)
    {
        public boolean match(RegistryEntry<Item> otherItem)
        {
            return this.input().matchesKey(otherItem.getKey().orElseThrow());
        }

        public Integer mul(Integer totalIn)
        {
            return (int) (totalIn * this.multiplier());
        }
    }
}
