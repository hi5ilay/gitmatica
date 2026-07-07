package me.zly2006.lvc.semantic;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcUserActionException;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcCanonicalNbt;
import me.zly2006.lvc.util.LvcEntityNbt;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.BlockUtils;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.util.nbt.NbtView;

public final class LvcSemanticWorldApplier
{
    private static final int RESTORE_BLOCK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int BLOCK_ENTITY_RESET_FLAGS = Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE;
    private static final int CLEAR_BLOCK_FLAGS = 0x32;
    private static final ConcurrentMap<String, BlockState> BLOCK_STATE_PARSE_CACHE = new ConcurrentHashMap<>();

    private LvcSemanticWorldApplier()
    {
    }

    public static void validateChunkTargets(Level world, LvcIntPosition origin, LvcChunkCoordinate coordinate,
                                            LvcChunk chunk) throws IOException
    {
        for (LvcTrackedBlockCursor.StoredBlock block : LvcTrackedBlockCursor.storedBlocks(coordinate, origin, chunk))
        {
            String blockState = null;
            byte[] blockEntityNbt = block.blockEntityBytes();

            try
            {
                validateRestoreTarget(world, block.blockPos());
                blockState = block.blockState();
                parseBlockState(blockState);
                decodeBlockEntity(blockEntityNbt, block.blockPos());
            }
            catch (Exception e)
            {
                throw withPositionContext("validate", block.coordinate(), block.maskIndex(), block.trackedOrdinal(),
                        block.projectPos(), block.blockPos(), blockState, blockEntityNbt, e);
            }
        }
    }

    public static int restoreChunk(Level world, LvcIntPosition origin, LvcChunkCoordinate coordinate,
                                   LvcChunk chunk) throws IOException
    {
        return restoreChunk(world, origin, coordinate, chunk, false);
    }

    public static int restoreChunk(Level world, LvcIntPosition origin, LvcChunkCoordinate coordinate,
                                   LvcChunk chunk, boolean forceClientSync) throws IOException
    {
        return withPasteUpdateSuppression(world, () ->
        {
            int restoredBlocks = 0;

            for (LvcTrackedBlockCursor.StoredBlock block : LvcTrackedBlockCursor.storedBlocks(coordinate, origin, chunk))
            {
                String blockState = null;
                byte[] blockEntityBytes = block.blockEntityBytes();

                try
                {
                    blockState = block.blockState();
                    BlockState state = parseRestoreBlockState(blockState);
                    CompoundTag blockEntityNbt = decodeBlockEntity(blockEntityBytes, block.blockPos());

                    restoreBlock(world, block.blockPos(), state, blockEntityNbt, false, forceClientSync);
                }
                catch (Exception e)
                {
                    throw withPositionContext("restore", block.coordinate(), block.maskIndex(), block.trackedOrdinal(),
                            block.projectPos(), block.blockPos(), blockState, blockEntityBytes, e);
                }

                restoredBlocks++;
            }

            return restoredBlocks;
        });
    }

    public static Map<Integer, byte[]> blockEntitiesByIndex(LvcChunk chunk)
    {
        return LvcTrackedBlockCursor.blockEntitiesByIndex(chunk);
    }

    public static int clearLiveEntitiesInTrackedArea(LvcManifest.Site site, LvcLocalState.SitePlacement placement, Level world) throws IOException
    {
        return clearLiveEntitiesInTrackedArea(site, LvcIntPosition.fromList(placement.origin()), world, () -> { });
    }

    public static int clearLiveEntitiesInTrackedArea(LvcManifest.Site site, LvcLocalState.SitePlacement placement,
                                                     Level world, EntityMutationCallback mutationCallback) throws IOException
    {
        return clearLiveEntitiesInTrackedArea(site, LvcIntPosition.fromList(placement.origin()), world, mutationCallback);
    }

    public static int clearLiveEntitiesInTrackedArea(LvcManifest.Site site, LvcIntPosition origin,
                                                     Level world, EntityMutationCallback mutationCallback) throws IOException
    {
        List<EntityRegionBounds> regions = site.regions().stream()
                .map(region -> EntityRegionBounds.of(region, origin))
                .toList();
        Set<UUID> removed = new HashSet<>();

        for (EntityRegionBounds region : regions)
        {
            List<Entity> entities = world.getEntities((Entity) null, region.bounds(), EntityUtils.NOT_PLAYER);

            for (Entity entity : entities)
            {
                if (entity instanceof Player || entity.isRemoved() || !region.contains(entity))
                {
                    continue;
                }

                if (removed.add(entity.getUUID()))
                {
                    if (removed.size() == 1)
                    {
                        mutationCallback.onEntityMutation();
                    }

                    entity.discard();
                }
            }
        }

        return removed.size();
    }

    public static int spawnStoredEntitiesForChunk(Level world, LvcIntPosition origin, LvcChunk chunk,
                                                  Set<UUID> restoredEntityUuids) throws IOException
    {
        int spawned = 0;

        for (LvcChunk.EntityRecord record : chunk.entities())
        {
            CompoundTag entityNbt = LvcEntityNbt.materializeForWorld(record.canonicalNbt(), origin);
            Entity entity = EntityUtils.createEntityAndPassengersFromNBT(entityNbt, world);

            if (entity == null)
            {
                LvcDiagnostics.warn("LvcSemanticWorldApplier: failed to create stored LVC entity id='{}'",
                        entityNbt.getStringOr("id", "<missing>"));
                continue;
            }

            List<UUID> entityTreeUuids = entity.getSelfAndPassengers().map(Entity::getUUID).toList();

            if (entityTreeUuids.stream().anyMatch(restoredEntityUuids::contains))
            {
                LvcDiagnostics.warn("LvcSemanticWorldApplier: skipped duplicate stored LVC entity id='{}' uuid='{}'",
                        entityNbt.getStringOr("id", "<missing>"), entity.getStringUUID());
                continue;
            }

            if (entity.getSelfAndPassengers().anyMatch(Player.class::isInstance))
            {
                LvcDiagnostics.warn("LvcSemanticWorldApplier: skipped stored player entity id='{}' uuid='{}'",
                        entityNbt.getStringOr("id", "<missing>"), entity.getStringUUID());
                continue;
            }

            int discardedConflicts = discardExistingEntitiesWithUuids(world, entityTreeUuids);

            if (discardedConflicts > 0)
            {
                LvcDiagnostics.debug("LvcSemanticWorldApplier: discarded existing entity UUID conflicts before spawn count={} id='{}' uuid='{}'",
                        discardedConflicts, entityNbt.getStringOr("id", "<missing>"), entity.getStringUUID());
            }

            try
            {
                EntityUtils.spawnEntityAndPassengersInWorld(entity, world);
            }
            catch (IllegalStateException e)
            {
                LvcDiagnostics.warn("LvcSemanticWorldApplier: skipped stored LVC entity after spawn conflict id='{}' uuid='{}' error='{}'",
                        entityNbt.getStringOr("id", "<missing>"), entity.getStringUUID(), e.getMessage());
                continue;
            }

            restoredEntityUuids.addAll(entityTreeUuids);
            spawned++;
        }

        return spawned;
    }

    private static int discardExistingEntitiesWithUuids(Level world, List<UUID> uuids)
    {
        if (!(world instanceof ServerLevel serverWorld))
        {
            return 0;
        }

        int discarded = 0;
        Set<UUID> handled = new HashSet<>();

        for (UUID uuid : uuids)
        {
            if (!handled.add(uuid))
            {
                continue;
            }

            Entity existing = findEntityInLevel(serverWorld, uuid);

            if (existing != null && !(existing instanceof Player))
            {
                existing.discard();
                discarded++;
            }
        }

        return discarded;
    }

    @Nullable
    private static Entity findEntityInLevel(ServerLevel world, UUID uuid)
    {
        for (Entity entity : world.getAllEntities())
        {
            if (uuid.equals(entity.getUUID()))
            {
                return entity;
            }
        }

        return null;
    }

    public static boolean clearBlock(Level world, BlockPos pos) throws IOException
    {
        validateRestoreTarget(world, pos);

        if (world.getBlockState(pos).isAir())
        {
            return false;
        }

        BlockEntity oldBlockEntity = world.getBlockEntity(pos);

        if (oldBlockEntity != null)
        {
            if (oldBlockEntity instanceof Container container)
            {
                container.clearContent();
            }

            world.setBlock(pos, Blocks.BARRIER.defaultBlockState(), CLEAR_BLOCK_FLAGS);
        }

        world.setBlock(pos, Blocks.AIR.defaultBlockState(), CLEAR_BLOCK_FLAGS);
        return true;
    }

    public static void restoreBlock(Level world, BlockPos pos, BlockState state, @Nullable CompoundTag blockEntityNbt,
                                    boolean forceBlockEntityRefresh, boolean forceClientSync) throws IOException
    {
        validateRestoreTarget(world, pos);

        BlockEntity oldBlockEntity = world.getBlockEntity(pos);

        if (oldBlockEntity != null)
        {
            if (oldBlockEntity instanceof Container container)
            {
                container.clearContent();
            }

            world.setBlock(pos, Blocks.BARRIER.defaultBlockState(), BLOCK_ENTITY_RESET_FLAGS);
        }
        else if (forceBlockEntityRefresh && blockEntityNbt != null)
        {
            world.setBlock(pos, Blocks.BARRIER.defaultBlockState(), BLOCK_ENTITY_RESET_FLAGS);
        }

        boolean placed = world.setBlock(pos, state, RESTORE_BLOCK_FLAGS);
        BlockState restoredState = world.getBlockState(pos);

        if (!isRestoredStateAcceptable(restoredState, state))
        {
            throw new IOException("Failed to restore LVC block at " + pos + ": expected " + state + ", found " + restoredState +
                    ", setBlock returned " + placed);
        }

        if (!placed && forceClientSync)
        {
            world.sendBlockUpdated(pos, restoredState, restoredState, RESTORE_BLOCK_FLAGS);
        }

        if (blockEntityNbt != null)
        {
            BlockEntity blockEntity = world.getBlockEntity(pos);

            if (blockEntity == null)
            {
                LvcDiagnostics.warn("LvcProjectService: restored block '{}' at '{}' has no block entity for stored LVC payload", state, pos);
                return;
            }

            try
            {
                NbtView view = NbtView.getReader(blockEntityNbt, world.registryAccess());
                // Match Litematica direct paste: loading BE NBT must not notify comparator/neighbour state.
                blockEntity.loadWithComponents(view.getReader());
            }
            catch (Exception e)
            {
                throw new IOException("Failed to restore LVC block entity at " + pos, e);
            }
        }
    }

    public static void forceBlockState(Level world, BlockPos pos, BlockState state, boolean forceClientSync) throws IOException
    {
        validateRestoreTarget(world, pos);

        boolean placed = world.setBlock(pos, state, RESTORE_BLOCK_FLAGS);
        BlockState restoredState = world.getBlockState(pos);

        if (!isRestoredStateAcceptable(restoredState, state))
        {
            throw new IOException("Failed to reassert LVC block at " + pos + ": expected " + state + ", found " + restoredState +
                    ", setBlock returned " + placed);
        }

        if (!placed && forceClientSync)
        {
            world.sendBlockUpdated(pos, restoredState, restoredState, RESTORE_BLOCK_FLAGS);
        }
    }

    public static void syncRestoredBlock(Level world, BlockPos pos)
    {
        BlockState state = world.getBlockState(pos);
        world.sendBlockUpdated(pos, state, state, RESTORE_BLOCK_FLAGS);
    }

    public static void validateRestoreTarget(Level world, BlockPos pos) throws IOException
    {
        if (!world.isInWorldBounds(pos))
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.OUT_OF_WORLD_BOUNDS,
                    "LVC restore target is outside world build limits: " + pos);
        }

        if (!world.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())))
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.TRACKED_CHUNK_UNLOADED,
                    "LVC restore target chunk is not loaded: " + pos);
        }
    }

    private static int withPasteUpdateSuppression(Level world, WorldMutation mutation) throws IOException
    {
        boolean wasPreventingUpdates = WorldUtils.shouldPreventBlockUpdates(world);
        WorldUtils.setShouldPreventBlockUpdates(world, true);

        try
        {
            return mutation.run();
        }
        finally
        {
            WorldUtils.setShouldPreventBlockUpdates(world, wasPreventingUpdates);
        }
    }

    private static BlockState parseBlockState(String blockState) throws IOException
    {
        Objects.requireNonNull(blockState, "blockState");
        BlockState cached = BLOCK_STATE_PARSE_CACHE.get(blockState);

        if (cached != null)
        {
            return cached;
        }

        BlockState parsed = BlockUtils.getBlockStateFromString(blockState, LitematicaSchematic.MINECRAFT_DATA_VERSION)
                .orElseThrow(() -> new IOException("Invalid LVC block state: " + blockState));
        BlockState previous = BLOCK_STATE_PARSE_CACHE.putIfAbsent(blockState, parsed);
        return previous != null ? previous : parsed;
    }

    public static BlockState parseRestoreBlockState(String blockState) throws IOException
    {
        BlockState state = parseBlockState(blockState);
        return state.isAir() ? Blocks.AIR.defaultBlockState() : state;
    }

    public static boolean isRestoredStateAcceptable(BlockState currentState, BlockState targetState)
    {
        return currentState.equals(targetState) || (currentState.isAir() && targetState.isAir());
    }

    public static IOException withPositionContext(String action, LvcChunkCoordinate coordinate, int maskIndex, int trackedOrdinal,
                                                   LvcIntPosition projectPos, BlockPos blockPos, @Nullable String blockState,
                                                   @Nullable byte[] blockEntityNbt, Exception cause)
    {
        int realChunkX = SectionPos.blockToSectionCoord(blockPos.getX());
        int realChunkZ = SectionPos.blockToSectionCoord(blockPos.getZ());
        String message = "Failed to " + action + " LVC block at " + blockPos +
                " (project " + projectPos +
                ", LVC chunk " + coordinate.key() +
                ", real chunk " + realChunkX + "," + realChunkZ +
                ", mask index " + maskIndex +
                ", tracked ordinal " + trackedOrdinal +
                ", state " + (blockState != null ? blockState : "<unread>") +
                ", blockEntityNbt " + (blockEntityNbt != null ? "yes" : "no") + ")";

        if (cause.getMessage() != null && !cause.getMessage().isBlank())
        {
            message += ": " + cause.getMessage();
        }

        return new IOException(message, cause);
    }

    @Nullable
    public static CompoundTag decodeBlockEntity(@Nullable byte[] bytes, BlockPos pos) throws IOException
    {
        if (bytes == null)
        {
            return null;
        }

        CompoundTag tag = LvcCanonicalNbt.decodeUnnamedCompound(bytes);
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        return tag;
    }

    @FunctionalInterface
    private interface WorldMutation
    {
        int run() throws IOException;
    }

    @FunctionalInterface
    public interface EntityMutationCallback
    {
        void onEntityMutation() throws IOException;
    }

    private record EntityRegionBounds(String id, LvcIntPosition min, LvcIntPosition size, AABB bounds)
    {
        private static EntityRegionBounds of(LvcManifest.Region region, LvcIntPosition origin)
        {
            LvcIntPosition min = LvcIntPosition.fromList(region.min());
            LvcIntPosition size = LvcIntPosition.fromList(region.size());
            LvcIntPosition worldMin = origin.offset(min);
            AABB bounds = new AABB(worldMin.x(), worldMin.y(), worldMin.z(),
                    worldMin.x() + size.x(), worldMin.y() + size.y(), worldMin.z() + size.z());
            return new EntityRegionBounds(region.id(), min, size, bounds);
        }

        private boolean contains(Entity entity)
        {
            Vec3 pos = entity.position();
            return pos.x >= this.bounds.minX && pos.x < this.bounds.maxX &&
                    pos.y >= this.bounds.minY && pos.y < this.bounds.maxY &&
                    pos.z >= this.bounds.minZ && pos.z < this.bounds.maxZ;
        }
    }
}
