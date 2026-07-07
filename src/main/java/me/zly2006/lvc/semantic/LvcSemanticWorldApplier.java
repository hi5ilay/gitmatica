package me.zly2006.lvc.semantic;

import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.LvcUserActionException;
import me.zly2006.lvc.capture.LvcCapturePlanner;
import me.zly2006.lvc.capture.LvcSiteWorkPlan;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcCanonicalNbt;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.BlockUtils;
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

    public static ClearSession clearSession(LvcManifest.Site site, LvcLocalState.SitePlacement placement, Level world)
    {
        return new ClearSession(site, placement, world);
    }

    public static RestoreSession restoreSession(LvcManifest.Site site, LvcLocalState.SitePlacement placement,
                                                Map<LvcChunkCoordinate, LvcChunk> chunks, Level world)
    {
        return new RestoreSession(site, placement, chunks, world);
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

    public static final class ClearSession
    {
        private final LvcSiteWorkPlan plan;
        private final Level world;
        private int nextChunkIndex;
        private int clearedBlocks;

        private ClearSession(LvcManifest.Site site, LvcLocalState.SitePlacement placement, Level world)
        {
            this.plan = LvcSiteWorkPlan.create(site, placement);
            this.world = Objects.requireNonNull(world, "world");
        }

        public boolean isComplete()
        {
            return this.nextChunkIndex >= this.plan.chunks().size();
        }

        public int processedChunks()
        {
            return this.nextChunkIndex;
        }

        public int totalChunks()
        {
            return this.plan.chunkCount();
        }

        public void processNextChunk() throws IOException
        {
            if (this.isComplete())
            {
                return;
            }

            LvcSiteWorkPlan.ChunkWork work = this.plan.chunks().get(this.nextChunkIndex);
            this.clearedBlocks += this.clearChunk(work);
            this.nextChunkIndex++;
        }

        public LvcProjectService.SemanticWorldClearResult result()
        {
            if (!this.isComplete())
            {
                throw new IllegalStateException("LVC clear session is not complete");
            }

            return new LvcProjectService.SemanticWorldClearResult(this.plan.site().regions().size(), this.clearedBlocks);
        }

        private int clearChunk(LvcSiteWorkPlan.ChunkWork work) throws IOException
        {
            return withPasteUpdateSuppression(this.world, () ->
            {
                int chunkClearedBlocks = 0;
                BitSet mask = work.mask();

                for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1))
                {
                    LvcIntPosition projectPos = LvcCapturePlanner.projectPosition(work.coordinate(), index,
                            LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE);
                    LvcIntPosition worldPos = this.plan.origin().offset(projectPos);
                    BlockPos blockPos = new BlockPos(worldPos.x(), worldPos.y(), worldPos.z());

                    if (clearBlock(this.world, blockPos))
                    {
                        chunkClearedBlocks++;
                    }
                }

                return chunkClearedBlocks;
            });
        }
    }

    public static final class RestoreSession
    {
        private final LvcManifest.Site site;
        private final LvcIntPosition origin;
        private final List<Map.Entry<LvcChunkCoordinate, LvcChunk>> chunks;
        private final Level world;
        private int nextChunkIndex;
        private int restoredBlocks;

        private RestoreSession(LvcManifest.Site site, LvcLocalState.SitePlacement placement,
                               Map<LvcChunkCoordinate, LvcChunk> chunks, Level world)
        {
            this.site = Objects.requireNonNull(site, "site");
            this.origin = LvcIntPosition.fromList(Objects.requireNonNull(placement, "placement").origin());
            this.chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks").entrySet());
            this.world = Objects.requireNonNull(world, "world");
        }

        public boolean isComplete()
        {
            return this.nextChunkIndex >= this.chunks.size();
        }

        public int processedChunks()
        {
            return this.nextChunkIndex;
        }

        public int totalChunks()
        {
            return this.chunks.size();
        }

        public void processNextChunk() throws IOException
        {
            if (this.isComplete())
            {
                return;
            }

            Map.Entry<LvcChunkCoordinate, LvcChunk> entry = this.chunks.get(this.nextChunkIndex);
            this.restoredBlocks += restoreChunk(this.world, this.origin, entry.getKey(), entry.getValue());
            this.nextChunkIndex++;
        }

        public int regionCount()
        {
            return this.site.regions().size();
        }

        public int restoredBlocks()
        {
            return this.restoredBlocks;
        }
    }

    public static Map<Integer, byte[]> blockEntitiesByIndex(LvcChunk chunk)
    {
        return LvcTrackedBlockCursor.blockEntitiesByIndex(chunk);
    }

    private static boolean clearBlock(Level world, BlockPos pos) throws IOException
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
                blockEntity.loadWithComponents(view.getReader());
                blockEntity.setChanged();
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
}
