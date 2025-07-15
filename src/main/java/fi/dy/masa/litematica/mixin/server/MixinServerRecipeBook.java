package fi.dy.masa.litematica.mixin.server;

//@Mixin(ServerRecipeBook.class)
@Deprecated(forRemoval = true)
public abstract class MixinServerRecipeBook
{
//    @Shadow @Final protected Set<RegistryKey<Recipe<?>>> unlocked;
//    @Shadow @Final private ServerRecipeBook.DisplayCollector collector;
//
//    @Inject(method = "sendInitRecipesPacket", at = @At("TAIL"))
//    private void litematica_updateRemainingLockedRecipesToClient(ServerPlayerEntity player, CallbackInfo ci)
//    {
//        if (Configs.Generic.RECIPE_BOOK_SP_SHADOW_UNLOCK.getBooleanValue())
//        {
//            Collection<RecipeEntry<?>> serverRecipes = Objects.requireNonNull(player.getServer()).getRecipeManager().values();
//            Set<RegistryKey<Recipe<?>>> append = new HashSet<>();
//
//            for (RecipeEntry<?> entry : serverRecipes)
//            {
//                RegistryKey<Recipe<?>> key = entry.id();
//
//                if (!this.unlocked.contains(key))
//                {
//                    append.add(key);
//                }
//            }
//
//            List<RecipeBookAddS2CPacket.Entry> list = new ArrayList<>(append.size());
//
//            for (RegistryKey<Recipe<?>> key : append)
//            {
//                this.collector.displaysForRecipe(
//                        key, (display) ->
//                                list.add(
//                                        new RecipeBookAddS2CPacket.Entry(display, false, false)
//                                )
//                );
//            }
//
//            if (!list.isEmpty())
//            {
//                Litematica.LOGGER.info("RecipeBookShadowUnlock: Shadow-unlocking [{}] additional server recipes.", list.size());
//                player.networkHandler.sendPacket(new RecipeBookAddS2CPacket(list, true));
//            }
//        }
//    }
}
