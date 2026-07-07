package me.zly2006.lvc.task;

import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import me.zly2006.lvc.capture.LvcCapturePlanner;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.semantic.LvcTrackedBlockCursor;
import me.zly2006.lvc.semantic.LvcSemanticWorldApplier;
import me.zly2006.lvc.storage.LvcCanonicalNbt;

public final class LvcSemanticRestoreEngine
{
    private final ServerLevel world;
    private final LvcManifest.Site targetSite;
    private final LvcIntPosition origin;
    private final ChunkReader chunkReader;
    private final MutationCallback mutationCallback;
    private final PositionCallback restoredPositionCallback;
    private final String operationName;
    private final String commitId;
    private final int totalChunks;
    private final boolean restoreBlockEntityOnlyChanges;
    private final boolean countBlockEntityOnlyAsRestoredBlock;
    private final LongOpenHashSet authoritativeClientSyncPositions = new LongOpenHashSet();
    private final LongOpenHashSet stabilizationCandidatePositions = new LongOpenHashSet();
    private int restoredBlocks;
    private int changedChunks;
    private int blockEntityRewrites;

    public LvcSemanticRestoreEngine(ServerLevel world, LvcManifest.Site targetSite, LvcIntPosition origin,
                                    ChunkReader chunkReader, MutationCallback mutationCallback,
                                    PositionCallback restoredPositionCallback, Options options)
    {
        this.world = Objects.requireNonNull(world, "world");
        this.targetSite = Objects.requireNonNull(targetSite, "targetSite");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.chunkReader = Objects.requireNonNull(chunkReader, "chunkReader");
        this.mutationCallback = Objects.requireNonNull(mutationCallback, "mutationCallback");
        this.restoredPositionCallback = Objects.requireNonNull(restoredPositionCallback, "restoredPositionCallback");
        this.operationName = Objects.requireNonNull(options, "options").operationName();
        this.commitId = options.commitId();
        this.totalChunks = options.totalChunks();
        this.restoreBlockEntityOnlyChanges = options.restoreBlockEntityOnlyChanges();
        this.countBlockEntityOnlyAsRestoredBlock = options.countBlockEntityOnlyAsRestoredBlock();
    }

    public void scanAndRestoreChunk(Map.Entry<String, String> entry, int index) throws IOException
    {
        LvcChunk chunk = this.chunkReader.read(entry.getValue());
        int restoredInChunk = this.scanAndRestoreChunk(entry, chunk, index);

        if (restoredInChunk > 0)
        {
            this.changedChunks++;
        }
    }

    public void stabilizeCandidateBlocks() throws IOException
    {
        if (this.stabilizationCandidatePositions.isEmpty())
        {
            return;
        }

        Map<String, LvcChunk> chunkCache = new HashMap<>();
        Map<String, Map<Integer, byte[]>> blockEntityCache = new HashMap<>();
        LongIterator iterator = this.stabilizationCandidatePositions.iterator();

        while (iterator.hasNext())
        {
            BlockPos blockPos = BlockPos.of(iterator.nextLong());
            StabilizationTarget target = this.readStabilizationTarget(blockPos, chunkCache, blockEntityCache);

            if (target == null)
            {
                continue;
            }

            try
            {
                BlockState currentState = this.world.getBlockState(blockPos);

                if (LvcSemanticWorldApplier.isRestoredStateAcceptable(currentState, target.state()))
                {
                    continue;
                }

                this.mutationCallback.onWorldMutation();

                if (currentState.is(target.state().getBlock()))
                {
                    LvcSemanticWorldApplier.forceBlockState(this.world, blockPos, target.state(), true);
                }
                else
                {
                    CompoundTag blockEntityNbt = LvcSemanticWorldApplier.decodeBlockEntity(target.blockEntityBytes(), blockPos);
                    LvcSemanticWorldApplier.restoreBlock(this.world, blockPos, target.state(), blockEntityNbt,
                            target.blockEntityBytes() != null, true);

                    if (target.blockEntityBytes() != null)
                    {
                        this.blockEntityRewrites++;
                    }
                }

                this.authoritativeClientSyncPositions.add(blockPos.asLong());
                this.restoredPositionCallback.onRestoredPosition(target.projectPos());
                this.restoredBlocks++;
            }
            catch (Exception e)
            {
                throw this.withPositionContext("stabilization", target, blockPos, e);
            }
        }
    }

    public void scheduleAuthoritativeClientSync()
    {
        LvcAuthoritativeClientSyncTask.schedule(this.world, this.authoritativeClientSyncPositions);
    }

    public int restoredBlocks()
    {
        return this.restoredBlocks;
    }

    public int changedChunks()
    {
        return this.changedChunks;
    }

    public int blockEntityRewrites()
    {
        return this.blockEntityRewrites;
    }

    private int scanAndRestoreChunk(Map.Entry<String, String> entry, LvcChunk chunk, int index) throws IOException
    {
        try
        {
            LvcChunkCoordinate coordinate = LvcChunkCoordinate.parse(entry.getKey());
            int restoredInChunk = 0;

            for (LvcTrackedBlockCursor.StoredBlock block : LvcTrackedBlockCursor.storedBlocks(coordinate, this.origin, chunk))
            {
                String blockState = null;
                byte[] blockEntityBytes = block.blockEntityBytes();

                try
                {
                    LvcSemanticWorldApplier.validateRestoreTarget(this.world, block.blockPos());
                    blockState = block.blockState();
                    BlockState targetState = LvcSemanticWorldApplier.parseRestoreBlockState(blockState);
                    BlockState currentState = this.world.getBlockState(block.blockPos());
                    boolean stateMatches = LvcSemanticWorldApplier.isRestoredStateAcceptable(currentState, targetState);
                    this.authoritativeClientSyncPositions.add(block.blockPos().asLong());

                    if (!stateMatches)
                    {
                        this.mutationCallback.onWorldMutation();
                        CompoundTag blockEntityNbt = LvcSemanticWorldApplier.decodeBlockEntity(blockEntityBytes, block.blockPos());
                        LvcSemanticWorldApplier.restoreBlock(this.world, block.blockPos(), targetState, blockEntityNbt,
                                blockEntityBytes != null, false);
                        this.markChangedBlock(block.blockPos());
                        this.restoredPositionCallback.onRestoredPosition(block.projectPos());
                        restoredInChunk++;
                        this.restoredBlocks++;

                        if (blockEntityBytes != null)
                        {
                            this.blockEntityRewrites++;
                        }
                    }
                    else if (this.restoreBlockEntityOnlyChanges && blockEntityBytes != null && !blockEntityMatchesStoredPayload(this.world, block.blockPos(), blockEntityBytes))
                    {
                        this.mutationCallback.onWorldMutation();
                        CompoundTag blockEntityNbt = LvcSemanticWorldApplier.decodeBlockEntity(blockEntityBytes, block.blockPos());
                        LvcSemanticWorldApplier.restoreBlock(this.world, block.blockPos(), targetState, blockEntityNbt, true, true);
                        this.markChangedBlock(block.blockPos());
                        this.restoredPositionCallback.onRestoredPosition(block.projectPos());

                        if (this.countBlockEntityOnlyAsRestoredBlock)
                        {
                            restoredInChunk++;
                            this.restoredBlocks++;
                        }

                        this.blockEntityRewrites++;
                    }
                }
                catch (Exception e)
                {
                    throw LvcSemanticWorldApplier.withPositionContext(this.operationName + " scan/restore", coordinate,
                            block.maskIndex(), block.trackedOrdinal(), block.projectPos(), block.blockPos(), blockState, blockEntityBytes, e);
                }
            }

            return restoredInChunk;
        }
        catch (Exception e)
        {
            throw this.withChunkContext("scan/restore", entry, index, e);
        }
    }

    private void markChangedBlock(BlockPos blockPos)
    {
        this.authoritativeClientSyncPositions.add(blockPos.asLong());
        this.stabilizationCandidatePositions.add(blockPos.asLong());

        for (Direction direction : Direction.values())
        {
            this.stabilizationCandidatePositions.add(blockPos.relative(direction).asLong());
        }
    }

    @Nullable
    private StabilizationTarget readStabilizationTarget(BlockPos blockPos, Map<String, LvcChunk> chunkCache,
                                                        Map<String, Map<Integer, byte[]>> blockEntityCache) throws IOException
    {
        LvcIntPosition projectPos = new LvcIntPosition(blockPos.getX() - this.origin.x(),
                blockPos.getY() - this.origin.y(), blockPos.getZ() - this.origin.z());
        LvcChunkCoordinate coordinate = new LvcChunkCoordinate(
                Math.floorDiv(projectPos.x(), LvcChunk.DEFAULT_SIZE),
                Math.floorDiv(projectPos.y(), LvcChunk.DEFAULT_SIZE),
                Math.floorDiv(projectPos.z(), LvcChunk.DEFAULT_SIZE));
        String chunkKey = coordinate.key();
        String objectId = this.targetSite.fullHashes().get(chunkKey);

        if (objectId == null)
        {
            return null;
        }

        LvcChunk chunk = chunkCache.get(chunkKey);

        if (chunk == null)
        {
            chunk = this.chunkReader.read(objectId);
            chunkCache.put(chunkKey, chunk);
        }

        int localX = Math.floorMod(projectPos.x(), chunk.sizeX());
        int localY = Math.floorMod(projectPos.y(), chunk.sizeY());
        int localZ = Math.floorMod(projectPos.z(), chunk.sizeZ());
        int maskIndex = LvcCapturePlanner.index(localX, localY, localZ, chunk.sizeX(), chunk.sizeY());
        BitSet mask = chunk.trackedMask();

        if (!mask.get(maskIndex))
        {
            return null;
        }

        int ordinal = mask.get(0, maskIndex).cardinality();
        String blockState = chunk.blockStateAtTrackedOrdinal(ordinal);
        BlockState targetState = LvcSemanticWorldApplier.parseRestoreBlockState(blockState);
        Map<Integer, byte[]> blockEntities = blockEntityCache.get(chunkKey);

        if (blockEntities == null)
        {
            blockEntities = LvcSemanticWorldApplier.blockEntitiesByIndex(chunk);
            blockEntityCache.put(chunkKey, blockEntities);
        }

        byte[] blockEntityBytes = blockEntities.get(maskIndex);
        return new StabilizationTarget(coordinate, maskIndex, ordinal, projectPos, blockState, blockEntityBytes, targetState);
    }

    private BlockPos worldPos(LvcIntPosition projectPos)
    {
        LvcIntPosition worldPos = this.origin.offset(projectPos);
        return new BlockPos(worldPos.x(), worldPos.y(), worldPos.z());
    }

    private IOException withChunkContext(String action, Map.Entry<String, String> entry, int index, Exception cause)
    {
        String message = "LVC " + this.operationName + " failed during " + action +
                " chunk " + entry.getKey() +
                " (" + (index + 1) + "/" + this.totalChunks +
                ", object " + entry.getValue() +
                ", commit " + this.commitId + ")";

        if (cause.getMessage() != null && !cause.getMessage().isBlank())
        {
            message += ": " + cause.getMessage();
        }

        return new IOException(message, cause);
    }

    private IOException withPositionContext(String action, StabilizationTarget target, BlockPos blockPos, Exception cause)
    {
        return LvcSemanticWorldApplier.withPositionContext(this.operationName + " " + action, target.coordinate(),
                target.maskIndex(), target.trackedOrdinal(), target.projectPos(), blockPos,
                target.blockState(), target.blockEntityBytes(), cause);
    }

    private static boolean blockEntityMatchesStoredPayload(Level world, BlockPos blockPos, byte[] expectedNbt) throws IOException
    {
        BlockEntity blockEntity = world.getBlockEntity(blockPos);

        if (blockEntity == null)
        {
            return false;
        }

        byte[] currentNbt = LvcCanonicalNbt.encodeBlockEntity(blockEntity.saveWithFullMetadata(world.registryAccess()));
        return java.util.Arrays.equals(currentNbt, expectedNbt);
    }

    @FunctionalInterface
    public interface ChunkReader
    {
        LvcChunk read(String objectId) throws IOException;
    }

    @FunctionalInterface
    public interface MutationCallback
    {
        void onWorldMutation() throws Exception;
    }

    @FunctionalInterface
    public interface PositionCallback
    {
        void onRestoredPosition(LvcIntPosition projectPos);
    }

    public record Options(String operationName, String commitId, int totalChunks,
                          boolean restoreBlockEntityOnlyChanges,
                          boolean countBlockEntityOnlyAsRestoredBlock)
    {
        public static Options checkout(String commitId, int totalChunks)
        {
            return new Options("checkout", commitId, totalChunks, true, false);
        }

        public static Options discard(String commitId, int totalChunks)
        {
            return new Options("discard", commitId, totalChunks, false, false);
        }
    }

    private record StabilizationTarget(LvcChunkCoordinate coordinate, int maskIndex, int trackedOrdinal,
                                       LvcIntPosition projectPos, String blockState,
                                       @Nullable byte[] blockEntityBytes, BlockState state)
    {
    }
}
