package me.niicide.lvc.task;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import fi.dy.masa.litematica.util.WorldUtils;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcChunkCoordinate;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.semantic.LvcSemanticWorldApplier;
import me.niicide.lvc.semantic.LvcTrackedBlockCursor;
import me.niicide.lvc.storage.LvcCanonicalNbt;
import me.niicide.lvc.storage.LvcChunkCodec;
import me.niicide.lvc.storage.LvcChunkStore;

public final class LvcSemanticRestoreEngine
{
    private static final int MISMATCH_SAMPLE_LIMIT = 8;

    private final ServerLevel world;
    private final LvcManifest.Site targetSite;
    private final LvcIntPosition origin;
    private final List<Map.Entry<String, String>> chunkRefs;
    private final ChunkReader chunkReader;
    private final MutationCallback mutationCallback;
    private final PositionCallback restoredPositionCallback;
    private final String operationName;
    private final String commitId;
    private final TargetMode targetMode;
    private final boolean restoreBlockEntityOnlyChanges;
    private final boolean restoreStoredEntities;
    private final LongOpenHashSet authoritativeClientSyncPositions = new LongOpenHashSet();
    private final Set<UUID> restoredEntityUuids = new HashSet<>();
    private final Set<String> rewrittenChunkKeys = new HashSet<>();
    private final Map<Long, List<RewriteTarget>> pendingRewritesByRealChunk = new LinkedHashMap<>();
    private final List<Long> pendingRewriteRealChunkKeys = new ArrayList<>();
    private final List<String> dirtyChunkKeys = new ArrayList<>();
    private final Set<String> dirtyChunkKeySet = new HashSet<>();
    private final List<String> mismatchSamples = new ArrayList<>();
    private Phase phase = Phase.INITIAL_SCAN;
    private int scanChunkIndex;
    private int rewriteIndex;
    private int pendingRewriteCount;
    private int restoredBlocks;
    private int changedChunks;
    private int blockEntityRewrites;
    private int clearedEntities;
    private int spawnedEntities;
    private int latestScanMismatches;
    private int latestScanStateMismatches;
    private int latestScanBlockEntityMismatches;
    private int latestScanOmittedSamples;
    private boolean storedEntitiesRestored;
    private boolean clientSyncScheduled;
    private boolean yieldAfterStep;
    private PostOperationDiffs postOperationDiffs = PostOperationDiffs.clean();

    public LvcSemanticRestoreEngine(ServerLevel world, LvcManifest.Site targetSite, LvcIntPosition origin,
                                    List<Map.Entry<String, String>> chunkRefs, ChunkReader chunkReader,
                                    MutationCallback mutationCallback, PositionCallback restoredPositionCallback,
                                    Options options)
    {
        this.world = Objects.requireNonNull(world, "world");
        this.targetSite = Objects.requireNonNull(targetSite, "targetSite");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.chunkRefs = List.copyOf(Objects.requireNonNull(chunkRefs, "chunkRefs"));
        this.chunkReader = Objects.requireNonNull(chunkReader, "chunkReader");
        this.mutationCallback = Objects.requireNonNull(mutationCallback, "mutationCallback");
        this.restoredPositionCallback = Objects.requireNonNull(restoredPositionCallback, "restoredPositionCallback");
        Options safeOptions = Objects.requireNonNull(options, "options");
        this.operationName = safeOptions.operationName();
        this.commitId = safeOptions.commitId();
        this.targetMode = safeOptions.targetMode();
        this.restoreBlockEntityOnlyChanges = safeOptions.restoreBlockEntityOnlyChanges();
        this.restoreStoredEntities = safeOptions.restoreStoredEntities();
    }

    public boolean processNextStep() throws IOException
    {
        this.yieldAfterStep = false;

        return switch (this.phase)
        {
            case INITIAL_SCAN -> this.processInitialScanStep();
            case INITIAL_REWRITE -> this.processInitialRewriteStep();
            case VERIFY_SCAN -> this.processVerifyScanStep();
            case RESTORE_ENTITIES -> this.processEntityRestoreStep();
            case CLIENT_SYNC -> this.processClientSyncStep();
            case COMPLETE -> true;
        };
    }

    public String phaseLabel()
    {
        return this.phase.label;
    }

    public int currentProgress()
    {
        return switch (this.phase)
        {
            case INITIAL_SCAN, VERIFY_SCAN -> this.scanChunkIndex;
            case INITIAL_REWRITE -> this.rewriteIndex;
            case RESTORE_ENTITIES, CLIENT_SYNC, COMPLETE -> this.currentTotal();
        };
    }

    public int currentTotal()
    {
        return switch (this.phase)
        {
            case INITIAL_REWRITE -> Math.max(this.pendingRewriteRealChunkKeys.size(), 1);
            default -> this.chunkRefs.size();
        };
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

    public int clearedEntities()
    {
        return this.clearedEntities;
    }

    public int spawnedEntities()
    {
        return this.spawnedEntities;
    }

    public int changedEntities()
    {
        return this.clearedEntities + this.spawnedEntities;
    }

    public PostOperationDiffs postOperationDiffs()
    {
        return this.postOperationDiffs;
    }

    public boolean shouldYieldAfterStep()
    {
        return this.yieldAfterStep;
    }

    private boolean processInitialScanStep() throws IOException
    {
        if (this.scanChunkIndex < this.chunkRefs.size())
        {
            Map.Entry<String, String> entry = this.chunkRefs.get(this.scanChunkIndex);
            this.scanChunk(entry, this.scanChunkIndex, true, false);
            this.scanChunkIndex++;
            return false;
        }

        LvcDiagnostics.debug("semantic {} initial scan complete commit={} chunks={} realChunks={} diffBlocks={} samples={} omittedSamples={}",
                this.operationName, this.commitId, this.chunkRefs.size(), this.pendingRewriteRealChunkKeys.size(),
                this.pendingRewriteCount, this.mismatchSamples.size(), this.latestScanOmittedSamples);

        if (this.pendingRewriteRealChunkKeys.isEmpty())
        {
            this.phase = Phase.COMPLETE;
            return true;
        }
        else
        {
            this.phase = Phase.INITIAL_REWRITE;
            this.rewriteIndex = 0;
        }

        return false;
    }

    private boolean processInitialRewriteStep() throws IOException
    {
        if (this.rewriteIndex == 0)
        {
            this.clearLiveEntities("initial rewrite");
            this.clearStaleScheduledTicks();
        }

        if (this.rewriteIndex < this.pendingRewriteRealChunkKeys.size())
        {
            long realChunkKey = this.pendingRewriteRealChunkKeys.get(this.rewriteIndex);
            List<RewriteTarget> targets = this.pendingRewritesByRealChunk.getOrDefault(realChunkKey, List.of());
            this.rewriteTargets("diff", realChunkKey, targets);
            this.rewriteIndex++;
            return false;
        }

        this.pendingRewritesByRealChunk.clear();
        this.pendingRewriteRealChunkKeys.clear();
        this.pendingRewriteCount = 0;
        this.prepareVerifyScan();
        return false;
    }

    private boolean processVerifyScanStep() throws IOException
    {
        if (this.scanChunkIndex < this.chunkRefs.size())
        {
            Map.Entry<String, String> entry = this.chunkRefs.get(this.scanChunkIndex);
            this.scanChunk(entry, this.scanChunkIndex, false, true);
            this.scanChunkIndex++;
            return false;
        }

        LvcDiagnostics.debug("semantic {} verify scan complete commit={} chunks={} dirtyChunks={} mismatches={} stateMismatches={} blockEntityMismatches={} samples={} omittedSamples={}",
                this.operationName, this.commitId,
                this.chunkRefs.size(), this.dirtyChunkKeys.size(), this.latestScanMismatches,
                this.latestScanStateMismatches, this.latestScanBlockEntityMismatches,
                this.mismatchSamples.size(), this.latestScanOmittedSamples);

        if (this.dirtyChunkKeys.isEmpty())
        {
            this.phase = Phase.RESTORE_ENTITIES;
            return false;
        }

        this.completeWithPostOperationDiffs("post-rewrite verify");
        return false;
    }

    private void completeWithPostOperationDiffs(String reason)
    {
        this.postOperationDiffs = new PostOperationDiffs(
                this.dirtyChunkKeys.size(),
                this.latestScanMismatches,
                this.latestScanStateMismatches,
                this.latestScanBlockEntityMismatches);
        LvcDiagnostics.warn("semantic {} completed with post-operation diffs commit={} reason='{}' dirtySubchunks={} mismatches={} stateMismatches={} blockEntityMismatches={} samples={} omittedSamples={}",
                this.operationName, this.commitId, reason, this.postOperationDiffs.dirtySubchunks(),
                this.postOperationDiffs.mismatches(), this.postOperationDiffs.stateMismatches(),
                this.postOperationDiffs.blockEntityMismatches(),
                this.mismatchSamples, this.latestScanOmittedSamples);
        this.phase = Phase.RESTORE_ENTITIES;
    }

    private boolean processEntityRestoreStep() throws IOException
    {
        this.clearLiveEntities("stored entity restore");

        if (this.restoreStoredEntities)
        {
            this.restoreStoredEntities();
        }

        this.phase = Phase.CLIENT_SYNC;
        return false;
    }

    private boolean processClientSyncStep()
    {
        if (!this.clientSyncScheduled)
        {
            this.clientSyncScheduled = true;
            LvcAuthoritativeClientSyncTask.schedule(this.world, this.authoritativeClientSyncPositions);
        }

        this.phase = Phase.COMPLETE;
        return true;
    }

    private void prepareVerifyScan()
    {
        this.phase = Phase.VERIFY_SCAN;
        this.scanChunkIndex = 0;
        this.latestScanMismatches = 0;
        this.latestScanStateMismatches = 0;
        this.latestScanBlockEntityMismatches = 0;
        this.latestScanOmittedSamples = 0;
        this.dirtyChunkKeys.clear();
        this.dirtyChunkKeySet.clear();
        this.mismatchSamples.clear();
    }

    private void scanChunk(Map.Entry<String, String> entry, int index, boolean collectRewriteTargets,
                           boolean collectDirtyChunks) throws IOException
    {
        try
        {
            LvcChunk chunk = this.chunkReader.read(entry.getValue());
            LvcChunkCoordinate coordinate = LvcChunkCoordinate.parse(entry.getKey());
            boolean dirtyChunk = false;

            for (LvcTrackedBlockCursor.StoredBlock block : LvcTrackedBlockCursor.storedBlocks(coordinate, this.origin, chunk))
            {
                RewriteTarget target = this.targetFor(block);

                try
                {
                    LvcSemanticWorldApplier.validateRestoreTarget(this.world, target.blockPos());
                    BlockState currentState = this.world.getBlockState(target.blockPos());
                    boolean stateMatches = LvcSemanticWorldApplier.isRestoredStateAcceptable(currentState, target.state());
                    boolean blockEntityMatches = true;

                    if (stateMatches && this.targetMode == TargetMode.RESTORE &&
                        this.restoreBlockEntityOnlyChanges)
                    {
                        blockEntityMatches = blockEntityMatchesStoredPayload(this.world, target.blockPos(), target.blockEntityBytes());
                    }

                    this.authoritativeClientSyncPositions.add(target.blockPos().asLong());

                    if (!stateMatches || !blockEntityMatches)
                    {
                        dirtyChunk = true;
                        this.latestScanMismatches++;
                        if (!stateMatches)
                        {
                            this.latestScanStateMismatches++;
                        }
                        else
                        {
                            this.latestScanBlockEntityMismatches++;
                        }
                        this.addMismatchSample(entry.getKey(), target, currentState, stateMatches, blockEntityMatches);

                        if (collectRewriteTargets)
                        {
                            this.addPendingRewriteTarget(target);
                        }
                    }
                }
                catch (Exception e)
                {
                    throw LvcSemanticWorldApplier.withPositionContext(this.operationName + " scan", coordinate,
                            target.maskIndex(), target.trackedOrdinal(), target.projectPos(), target.blockPos(),
                            target.blockState(), target.blockEntityBytes(), e);
                }
            }

            if (dirtyChunk && collectDirtyChunks && this.dirtyChunkKeySet.add(entry.getKey()))
            {
                this.dirtyChunkKeys.add(entry.getKey());
            }
        }
        catch (Exception e)
        {
            throw this.withChunkContext("scan", entry, index, e);
        }
    }

    private void addPendingRewriteTarget(RewriteTarget target)
    {
        long realChunkKey = realChunkKey(target.blockPos());
        List<RewriteTarget> targets = this.pendingRewritesByRealChunk.get(realChunkKey);

        if (targets == null)
        {
            targets = new ArrayList<>();
            this.pendingRewritesByRealChunk.put(realChunkKey, targets);
            this.pendingRewriteRealChunkKeys.add(realChunkKey);
        }

        targets.add(target);
        this.pendingRewriteCount++;
    }

    private void rewriteTargets(String kind, long realChunkKey, List<RewriteTarget> targets) throws IOException
    {
        try
        {
            this.notifyWorldMutation("rewrite " + kind + " real chunk");
            int before = this.restoredBlocks;

            this.withPasteUpdateSuppression(() ->
            {
                for (RewriteTarget target : targets)
                {
                    this.rewriteTargetWithoutSuppression(target);
                }
            });

            LvcDiagnostics.debug("semantic {} {} real chunk rewrite commit={} realChunk={} targets={} rewrittenBlocks={}",
                    this.operationName, kind, this.commitId,
                    realChunkDescription(realChunkKey), targets.size(), this.restoredBlocks - before);
        }
        catch (Exception e)
        {
            throw new IOException("LVC " + this.operationName + " failed during " + kind + " real chunk rewrite " +
                    realChunkDescription(realChunkKey) + " (commit " + this.commitId + "): " + e.getMessage(), e);
        }
    }

    private void rewriteTargetWithoutSuppression(RewriteTarget target) throws IOException
    {
        if (this.targetMode == TargetMode.CLEAR)
        {
            if (LvcSemanticWorldApplier.clearBlock(this.world, target.blockPos()))
            {
                this.restoredBlocks++;
                this.markRewrittenTarget(target);
            }

            return;
        }

        CompoundTag blockEntityNbt = LvcSemanticWorldApplier.decodeBlockEntity(target.blockEntityBytes(), target.blockPos());
        boolean forceBlockEntityRefresh = target.blockEntityBytes() != null;
        LvcSemanticWorldApplier.restoreBlock(this.world, target.blockPos(), target.state(), blockEntityNbt,
                forceBlockEntityRefresh, true);
        this.restoredBlocks++;

        if (target.blockEntityBytes() != null)
        {
            this.blockEntityRewrites++;
        }

        this.markRewrittenTarget(target);
    }

    private void markRewrittenTarget(RewriteTarget target)
    {
        this.authoritativeClientSyncPositions.add(target.blockPos().asLong());
        this.restoredPositionCallback.onRestoredPosition(target.projectPos());

        if (this.rewrittenChunkKeys.add(target.coordinate().key()))
        {
            this.changedChunks = this.rewrittenChunkKeys.size();
        }
    }

    private RewriteTarget targetFor(LvcTrackedBlockCursor.StoredBlock block) throws IOException
    {
        if (this.targetMode == TargetMode.CLEAR)
        {
            return new RewriteTarget(block.coordinate(), block.maskIndex(), block.trackedOrdinal(),
                    block.projectPos(), block.blockPos(), "minecraft:air", null, Blocks.AIR.defaultBlockState());
        }

        String blockState = block.blockState();
        return new RewriteTarget(block.coordinate(), block.maskIndex(), block.trackedOrdinal(), block.projectPos(),
                block.blockPos(), blockState, block.blockEntityBytes(),
                LvcSemanticWorldApplier.parseRestoreBlockState(blockState));
    }

    private void clearStaleScheduledTicks()
    {
        if (this.pendingRewriteRealChunkKeys.isEmpty()) return;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet chunkKeys = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(this.pendingRewriteRealChunkKeys);
        int cleared = me.niicide.lvc.semantic.LvcSemanticWorldApplier.clearScheduledTicksInTrackedChunks(this.world, chunkKeys);
        LvcDiagnostics.debug("semantic {} cleared stale scheduled ticks before rewrite commit={} realChunks={} clearedContainers={}",
                this.operationName, this.commitId, chunkKeys.size(), cleared);
    }

    private int clearLiveEntities(String reason) throws IOException
    {
        int removed = LvcSemanticWorldApplier.clearLiveEntitiesInTrackedArea(
                this.targetSite,
                this.origin,
                this.world,
                () -> this.notifyWorldMutation("clear live entities"));

        if (removed > 0)
        {
            this.clearedEntities += removed;
            LvcDiagnostics.info("semantic {} live entity clear commit={} reason='{}' removed={} totalCleared={}",
                    this.operationName, this.commitId, reason, removed, this.clearedEntities);
        }
        else
        {
            LvcDiagnostics.debug("semantic {} live entity clear commit={} reason='{}' removed=0 totalCleared={}",
                    this.operationName, this.commitId, reason, this.clearedEntities);
        }

        return removed;
    }

    private void notifyWorldMutation(String action) throws IOException
    {
        try
        {
            this.mutationCallback.onWorldMutation();
        }
        catch (Exception e)
        {
            throw new IOException("LVC " + this.operationName + " failed before " + action, e);
        }
    }

    private void restoreStoredEntities() throws IOException
    {
        if (this.storedEntitiesRestored)
        {
            return;
        }

        this.storedEntitiesRestored = true;
        int chunksWithEntities = 0;

        for (Map.Entry<String, String> entry : this.chunkRefs)
        {
            LvcChunk chunk = this.chunkReader.read(entry.getValue());

            if (chunk.entities().isEmpty())
            {
                continue;
            }

            if (this.spawnedEntities == 0)
            {
                this.notifyWorldMutation("spawn stored entities");
            }

            chunksWithEntities++;
            this.spawnedEntities += LvcSemanticWorldApplier.spawnStoredEntitiesForChunk(
                    this.world, this.origin, chunk, this.restoredEntityUuids);
        }

        LvcDiagnostics.debug("semantic {} entity restore complete commit={} chunksWithEntities={} clearedEntities={} spawnedEntities={}",
                this.operationName, this.commitId, chunksWithEntities, this.clearedEntities, this.spawnedEntities);
    }

    private void addMismatchSample(String chunkKey, RewriteTarget target, BlockState currentState,
                                   boolean stateMatches, boolean blockEntityMatches) throws IOException
    {
        if (this.mismatchSamples.size() >= MISMATCH_SAMPLE_LIMIT)
        {
            this.latestScanOmittedSamples++;
            return;
        }

        String kind = stateMatches && !blockEntityMatches ? "block-entity" : "block-state";
        String actual = stateMatches ? trackedBlockEntityPayloadDescription(this.world, target.blockPos()) : currentState.toString();
        String expected = stateMatches ?
                trackedBlockEntityPayloadDescription(target.blockEntityBytes()) : target.blockState();
        String summary = kind + " chunk " + chunkKey + " at " +
                target.blockPos().getX() + "," + target.blockPos().getY() + "," + target.blockPos().getZ() +
                ": expected " + expected + ", server " + actual;
        this.mismatchSamples.add(summary);
    }

    private IOException withChunkContext(String action, Map.Entry<String, String> entry, int index, Exception cause)
    {
        String message = "LVC " + this.operationName + " failed during " + action +
                " chunk " + entry.getKey() +
                " (" + (index + 1) + "/" + this.chunkRefs.size() +
                ", object " + entry.getValue() +
                ", commit " + this.commitId + ")";

        if (cause.getMessage() != null && !cause.getMessage().isBlank())
        {
            message += ": " + cause.getMessage();
        }

        return new IOException(message, cause);
    }

    private void withPasteUpdateSuppression(WorldMutation mutation) throws IOException
    {
        boolean wasPreventingUpdates = WorldUtils.shouldPreventBlockUpdates(this.world);
        WorldUtils.setShouldPreventBlockUpdates(this.world, true);

        try
        {
            mutation.run();
        }
        finally
        {
            WorldUtils.setShouldPreventBlockUpdates(this.world, wasPreventingUpdates);
        }
    }

    private static long realChunkKey(BlockPos pos)
    {
        return ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    private static String realChunkDescription(long key)
    {
        return (int) key + "," + (int) (key >> 32);
    }

    private static boolean blockEntityMatchesStoredPayload(Level world, BlockPos blockPos, @Nullable byte[] expectedNbt) throws IOException
    {
        byte[] expectedTrackedNbt = expectedNbt == null ? null : LvcChunkCodec.encodeTrackedBlockEntityContent(expectedNbt);
        BlockEntity blockEntity = world.getBlockEntity(blockPos);

        if (blockEntity == null)
        {
            return expectedTrackedNbt == null;
        }

        byte[] currentNbt = LvcCanonicalNbt.encodeBlockEntity(blockEntity.saveWithFullMetadata(world.registryAccess()));
        byte[] currentTrackedNbt = LvcChunkCodec.encodeTrackedBlockEntityContent(currentNbt);
        return Arrays.equals(currentTrackedNbt, expectedTrackedNbt);
    }

    private static String trackedBlockEntityPayloadDescription(@Nullable byte[] canonicalNbt) throws IOException
    {
        if (canonicalNbt == null)
        {
            return "<no tracked inventory>";
        }

        byte[] trackedNbt = LvcChunkCodec.encodeTrackedBlockEntityContent(canonicalNbt);
        return trackedNbt == null ? "<no tracked inventory>" : LvcChunkStore.objectId(trackedNbt);
    }

    private static String trackedBlockEntityPayloadDescription(Level world, BlockPos blockPos) throws IOException
    {
        BlockEntity blockEntity = world.getBlockEntity(blockPos);

        if (blockEntity == null)
        {
            return "<missing block entity>";
        }

        byte[] currentNbt = LvcCanonicalNbt.encodeBlockEntity(blockEntity.saveWithFullMetadata(world.registryAccess()));
        return trackedBlockEntityPayloadDescription(currentNbt);
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

    @FunctionalInterface
    private interface WorldMutation
    {
        void run() throws IOException;
    }

    public enum TargetMode
    {
        RESTORE,
        CLEAR
    }

    public record Options(String operationName, String commitId, TargetMode targetMode,
                          boolean restoreBlockEntityOnlyChanges,
                          boolean restoreStoredEntities)
    {
        public static Options checkout(String commitId)
        {
            return new Options("checkout", commitId, TargetMode.RESTORE, true, true);
        }

        public static Options discard(String commitId)
        {
            return new Options("discard", commitId, TargetMode.RESTORE, true, true);
        }

        public static Options clear()
        {
            return new Options("clear", "<clear>", TargetMode.CLEAR, false, false);
        }
    }

    public record PostOperationDiffs(int dirtySubchunks, int mismatches, int stateMismatches,
                                     int blockEntityMismatches)
    {
        private static final PostOperationDiffs CLEAN = new PostOperationDiffs(0, 0, 0, 0);

        public static PostOperationDiffs clean()
        {
            return CLEAN;
        }

        public boolean detected()
        {
            return this.dirtySubchunks > 0 || this.mismatches > 0;
        }
    }

    private enum Phase
    {
        INITIAL_SCAN("scan"),
        INITIAL_REWRITE("rewrite diffs"),
        VERIFY_SCAN("verify"),
        RESTORE_ENTITIES("restore entities"),
        CLIENT_SYNC("sync client"),
        COMPLETE("complete");

        private final String label;

        Phase(String label)
        {
            this.label = label;
        }
    }

    private record RewriteTarget(LvcChunkCoordinate coordinate, int maskIndex, int trackedOrdinal,
                                 LvcIntPosition projectPos, BlockPos blockPos, String blockState,
                                 @Nullable byte[] blockEntityBytes, BlockState state)
    {
    }
}
