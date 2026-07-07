package me.zly2006.lvc.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcChunkCodec;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.storage.LvcSemanticRepository;

final class LvcSemanticMergeEngine
{
    private LvcSemanticMergeEngine()
    {
    }

    static LvcSemanticMergeResult merge(Path repositoryDirectory, Repository repository, RevCommit baseCommit,
                                        RevCommit currentCommit, RevCommit sourceCommit,
                                        @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution) throws IOException
    {
        LvcManifest baseManifest = LvcSemanticRepository.readCommitManifest(repository, baseCommit);
        LvcManifest currentManifest = LvcSemanticRepository.readCommitManifest(repository, currentCommit);
        LvcManifest sourceManifest = LvcSemanticRepository.readCommitManifest(repository, sourceCommit);
        LvcManifest metadata = chooseManifestMetadata(baseManifest, currentManifest, sourceManifest, conflictResolution);
        Map<String, LvcManifest.Site> baseSites = sitesById(baseManifest);
        Map<String, LvcManifest.Site> currentSites = sitesById(currentManifest);
        Map<String, LvcManifest.Site> sourceSites = sitesById(sourceManifest);
        List<LvcManifest.Site> mergedSites = new ArrayList<>(metadata.sites().size());
        int mergedChunks = 0;

        for (LvcManifest.Site templateSite : metadata.sites())
        {
            LvcMergeSiteResult siteMerge = mergeSite(repositoryDirectory, repository, baseCommit, currentCommit, sourceCommit,
                    baseSites.get(templateSite.id()), currentSites.get(templateSite.id()), sourceSites.get(templateSite.id()), conflictResolution);
            mergedSites.add(templateSite.withHashRefs(siteMerge.fullHashes(), siteMerge.trackedHashes()));
            mergedChunks += siteMerge.mergedChunks();
        }

        return new LvcSemanticMergeResult(new LvcManifest(metadata.format(), metadata.projectId(), metadata.name(), metadata.content(), mergedSites), mergedChunks);
    }

    private static LvcManifest chooseManifestMetadata(LvcManifest baseManifest, LvcManifest currentManifest,
                                                      LvcManifest sourceManifest,
                                                      @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution) throws LvcMergeConflictException
    {
        String baseJson = baseManifest.toJson();
        String currentJson = currentManifest.toJson();
        String sourceJson = sourceManifest.toJson();

        if (currentJson.equals(sourceJson))
        {
            return currentManifest;
        }

        if (currentJson.equals(baseJson))
        {
            return sourceManifest;
        }

        if (sourceJson.equals(baseJson))
        {
            return currentManifest;
        }

        if (conflictResolution == null)
        {
            throw new LvcMergeConflictException(LvcMergeConflictException.Reason.MANIFEST_METADATA,
                    "LVC manifest metadata changed on both branches");
        }

        return switch (conflictResolution)
        {
            case BASE -> baseManifest;
            case INCOMING -> sourceManifest;
            case YOURS -> currentManifest;
        };
    }

    private static LvcMergeSiteResult mergeSite(Path repositoryDirectory, Repository repository, RevCommit baseCommit,
                                                RevCommit currentCommit, RevCommit sourceCommit,
                                                @Nullable LvcManifest.Site baseSite, @Nullable LvcManifest.Site currentSite,
                                                @Nullable LvcManifest.Site sourceSite,
                                                @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution) throws IOException
    {
        if (baseSite == null)
        {
            if (currentSite == null && sourceSite == null)
            {
                return new LvcMergeSiteResult(Map.of(), Map.of(), 0);
            }

            if (currentSite == null)
            {
                LvcMergeObjectResolver.ensureSiteObjects(repositoryDirectory, repository, sourceCommit, sourceSite);
                return new LvcMergeSiteResult(sourceSite.fullHashes(), sourceSite.trackedHashesForComparison(), 0);
            }

            if (sourceSite == null || sameRefs(currentSite, sourceSite))
            {
                LvcMergeObjectResolver.ensureSiteObjects(repositoryDirectory, repository, currentCommit, currentSite);
                return new LvcMergeSiteResult(currentSite.fullHashes(), currentSite.trackedHashesForComparison(), 0);
            }

            return resolvedSite(repositoryDirectory, repository, baseCommit, currentCommit, sourceCommit,
                    baseSite, currentSite, sourceSite, conflictResolution, LvcMergeConflictException.Reason.SITE_ADD,
                    "LVC site was added differently on both branches: " + currentSite.id());
        }

        if (currentSite == null || sourceSite == null)
        {
            return resolvedSite(repositoryDirectory, repository, baseCommit, currentCommit, sourceCommit,
                    baseSite, currentSite, sourceSite, conflictResolution, LvcMergeConflictException.Reason.SITE_DELETE,
                    "LVC site was deleted on one branch and changed on another: " + baseSite.id());
        }

        TreeMap<String, String> fullHashes = new TreeMap<>();
        TreeMap<String, String> trackedHashes = new TreeMap<>();
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(baseSite.fullHashes().keySet());
        keys.addAll(currentSite.fullHashes().keySet());
        keys.addAll(sourceSite.fullHashes().keySet());
        int mergedChunks = 0;

        for (String key : keys)
        {
            LvcMergeChunkRef baseRef = chunkRef(baseSite, key);
            LvcMergeChunkRef currentRef = chunkRef(currentSite, key);
            LvcMergeChunkRef sourceRef = chunkRef(sourceSite, key);
            LvcMergeChunkResult merged = mergeChunkRef(repositoryDirectory, repository, baseCommit, currentCommit, sourceCommit,
                    key, baseRef, currentRef, sourceRef, conflictResolution);

            if (merged.ref() != null)
            {
                fullHashes.put(key, merged.ref().fullHash());
                trackedHashes.put(key, merged.ref().trackedHash());
            }

            if (merged.mergedChunk())
            {
                mergedChunks++;
            }
        }

        return new LvcMergeSiteResult(Map.copyOf(fullHashes), Map.copyOf(trackedHashes), mergedChunks);
    }

    private static LvcMergeSiteResult resolvedSite(Path repositoryDirectory, Repository repository, RevCommit baseCommit,
                                                   RevCommit currentCommit, RevCommit sourceCommit,
                                                   @Nullable LvcManifest.Site baseSite, @Nullable LvcManifest.Site currentSite,
                                                   @Nullable LvcManifest.Site sourceSite,
                                                   @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution,
                                                   LvcMergeConflictException.Reason reason, String conflictMessage) throws IOException
    {
        if (conflictResolution == null)
        {
            throw new LvcMergeConflictException(reason, conflictMessage);
        }

        LvcManifest.Site selectedSite = switch (conflictResolution)
        {
            case BASE -> baseSite;
            case INCOMING -> sourceSite;
            case YOURS -> currentSite;
        };
        RevCommit selectedCommit = switch (conflictResolution)
        {
            case BASE -> baseCommit;
            case INCOMING -> sourceCommit;
            case YOURS -> currentCommit;
        };

        LvcMergeObjectResolver.ensureSiteObjects(repositoryDirectory, repository, selectedCommit, selectedSite);

        if (selectedSite == null)
        {
            return new LvcMergeSiteResult(Map.of(), Map.of(), 0);
        }

        return new LvcMergeSiteResult(selectedSite.fullHashes(), selectedSite.trackedHashesForComparison(), 0);
    }

    private static LvcMergeChunkResult mergeChunkRef(Path repositoryDirectory, Repository repository, RevCommit baseCommit,
                                                     RevCommit currentCommit, RevCommit sourceCommit, String chunkKey,
                                                     @Nullable LvcMergeChunkRef baseRef, @Nullable LvcMergeChunkRef currentRef,
                                                     @Nullable LvcMergeChunkRef sourceRef,
                                                     @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution) throws IOException
    {
        if (Objects.equals(currentRef, sourceRef))
        {
            LvcMergeObjectResolver.ensureObject(repositoryDirectory, repository, currentCommit, sourceCommit, baseCommit, currentRef);
            return new LvcMergeChunkResult(currentRef, false);
        }

        if (Objects.equals(currentRef, baseRef))
        {
            LvcMergeObjectResolver.ensureObject(repositoryDirectory, repository, sourceCommit, currentCommit, baseCommit, sourceRef);
            return new LvcMergeChunkResult(sourceRef, false);
        }

        if (Objects.equals(sourceRef, baseRef))
        {
            LvcMergeObjectResolver.ensureObject(repositoryDirectory, repository, currentCommit, sourceCommit, baseCommit, currentRef);
            return new LvcMergeChunkResult(currentRef, false);
        }

        if (currentRef == null || sourceRef == null)
        {
            return resolveChunkRef(repositoryDirectory, repository, baseCommit, currentCommit, sourceCommit,
                    baseRef, currentRef, sourceRef, conflictResolution, LvcMergeConflictException.Reason.CHUNK_DELETE,
                    "LVC chunk was deleted on one branch and changed on another: " + chunkKey);
        }

        if (baseRef == null)
        {
            if (Objects.equals(currentRef.trackedHash(), sourceRef.trackedHash()))
            {
                LvcMergeChunkRef selected = chooseSameTrackedRef(baseRef, currentRef, sourceRef);
                LvcMergeObjectResolver.ensureObject(repositoryDirectory, repository, currentCommit, sourceCommit, baseCommit, selected);
                return new LvcMergeChunkResult(selected, false);
            }

            return resolveChunkRef(repositoryDirectory, repository, baseCommit, currentCommit, sourceCommit,
                    baseRef, currentRef, sourceRef, conflictResolution, LvcMergeConflictException.Reason.CHUNK_ADD,
                    "LVC chunk was added differently on both branches: " + chunkKey);
        }

        if (Objects.equals(currentRef.trackedHash(), sourceRef.trackedHash()))
        {
            LvcMergeChunkRef selected = chooseSameTrackedRef(baseRef, currentRef, sourceRef);
            LvcMergeObjectResolver.ensureObject(repositoryDirectory, repository, currentCommit, sourceCommit, baseCommit, selected);
            return new LvcMergeChunkResult(selected, false);
        }

        return new LvcMergeChunkResult(mergeChunkContent(repositoryDirectory, repository, baseCommit, currentCommit, sourceCommit,
                chunkKey, baseRef, currentRef, sourceRef, conflictResolution), true);
    }

    private static LvcMergeChunkResult resolveChunkRef(Path repositoryDirectory, Repository repository, RevCommit baseCommit,
                                                       RevCommit currentCommit, RevCommit sourceCommit,
                                                       @Nullable LvcMergeChunkRef baseRef, @Nullable LvcMergeChunkRef currentRef,
                                                       @Nullable LvcMergeChunkRef sourceRef,
                                                       @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution,
                                                       LvcMergeConflictException.Reason reason, String conflictMessage) throws IOException
    {
        if (conflictResolution == null)
        {
            throw new LvcMergeConflictException(reason, conflictMessage);
        }

        LvcMergeChunkRef selectedRef = switch (conflictResolution)
        {
            case BASE -> baseRef;
            case INCOMING -> sourceRef;
            case YOURS -> currentRef;
        };
        RevCommit selectedCommit = switch (conflictResolution)
        {
            case BASE -> baseCommit;
            case INCOMING -> sourceCommit;
            case YOURS -> currentCommit;
        };

        LvcMergeObjectResolver.ensureObject(repositoryDirectory, repository, selectedCommit, null, null, selectedRef);
        return new LvcMergeChunkResult(selectedRef, false);
    }

    private static LvcMergeChunkRef chooseSameTrackedRef(@Nullable LvcMergeChunkRef baseRef,
                                                        LvcMergeChunkRef currentRef,
                                                        LvcMergeChunkRef sourceRef)
    {
        if (baseRef != null && Objects.equals(currentRef.fullHash(), baseRef.fullHash()))
        {
            return sourceRef;
        }

        return currentRef;
    }

    private static LvcMergeChunkRef mergeChunkContent(Path repositoryDirectory, Repository repository, RevCommit baseCommit,
                                                      RevCommit currentCommit, RevCommit sourceCommit, String chunkKey,
                                                      LvcMergeChunkRef baseRef, LvcMergeChunkRef currentRef,
                                                      LvcMergeChunkRef sourceRef,
                                                      @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution) throws IOException
    {
        LvcChunk baseChunk = LvcMergeObjectResolver.readChunk(repository, baseCommit, baseRef.fullHash());
        LvcChunk currentChunk = LvcMergeObjectResolver.readChunk(repository, currentCommit, currentRef.fullHash());
        LvcChunk sourceChunk = LvcMergeObjectResolver.readChunk(repository, sourceCommit, sourceRef.fullHash());

        if (!compatibleChunks(baseChunk, currentChunk, sourceChunk))
        {
            LvcMergeChunkResult resolved = resolveChunkRef(repositoryDirectory, repository, baseCommit, currentCommit, sourceCommit,
                    baseRef, currentRef, sourceRef, conflictResolution, LvcMergeConflictException.Reason.CHUNK_SHAPE,
                    "LVC chunk shape changed on both branches: " + chunkKey);
            return resolved.ref();
        }

        Map<Integer, LvcMergeBlockPayload> basePayloads = payloadsByMaskIndex(baseChunk);
        Map<Integer, LvcMergeBlockPayload> currentPayloads = payloadsByMaskIndex(currentChunk);
        Map<Integer, LvcMergeBlockPayload> sourcePayloads = payloadsByMaskIndex(sourceChunk);
        BitSet mask = baseChunk.trackedMask();
        List<String> states = new ArrayList<>(mask.cardinality());
        List<LvcChunk.BlockEntityRecord> blockEntities = new ArrayList<>();
        List<LvcChunk.ScheduledTickRecord> pendingBlockTicks = new ArrayList<>();
        List<LvcChunk.ScheduledTickRecord> pendingFluidTicks = new ArrayList<>();
        List<LvcChunk.EntityRecord> entities = chooseEntityPayload(chunkKey, baseChunk, currentChunk, sourceChunk, conflictResolution);

        for (int maskIndex = mask.nextSetBit(0); maskIndex >= 0; maskIndex = mask.nextSetBit(maskIndex + 1))
        {
            LvcMergeBlockPayload basePayload = basePayloads.get(maskIndex);
            LvcMergeBlockPayload currentPayload = currentPayloads.get(maskIndex);
            LvcMergeBlockPayload sourcePayload = sourcePayloads.get(maskIndex);
            LvcMergeBlockPayload selected = choosePayload(chunkKey, maskIndex, basePayload, currentPayload, sourcePayload, conflictResolution);

            states.add(selected.blockState());

            if (selected.blockEntityNbt() != null)
            {
                blockEntities.add(new LvcChunk.BlockEntityRecord(maskIndex, selected.blockEntityNbt()));
            }

            pendingBlockTicks.addAll(selected.pendingBlockTicks());
            pendingFluidTicks.addAll(selected.pendingFluidTicks());
        }

        LvcChunk mergedChunk = LvcChunk.fromTrackedContent(
                baseChunk.sizeX(),
                baseChunk.sizeY(),
                baseChunk.sizeZ(),
                mask,
                states,
                blockEntities,
                pendingBlockTicks,
                pendingFluidTicks,
                entities
        );
        byte[] hashContentBytes = LvcChunkCodec.encodeHashContent(mergedChunk);
        String fullHash = LvcChunkStore.objectId(hashContentBytes);
        String trackedHash = LvcChunkStore.objectId(LvcChunkCodec.encodeTrackedContent(mergedChunk));
        LvcChunkStore.writeObjectIfMissing(repositoryDirectory, fullHash, LvcChunkCodec.encodeStorageBytes(hashContentBytes));
        LvcDiagnostics.debug("LvcSemanticMergeEngine: semantic chunk merge chunk='{}' object='{}' tracked='{}'", chunkKey, fullHash, trackedHash);
        return new LvcMergeChunkRef(fullHash, trackedHash);
    }

    private static LvcMergeBlockPayload choosePayload(String chunkKey, int maskIndex, LvcMergeBlockPayload basePayload,
                                                      LvcMergeBlockPayload currentPayload, LvcMergeBlockPayload sourcePayload,
                                                      @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution) throws LvcMergeConflictException
    {
        boolean currentChanged = !trackedPayloadEquals(currentPayload, basePayload);
        boolean sourceChanged = !trackedPayloadEquals(sourcePayload, basePayload);

        if (trackedPayloadEquals(currentPayload, sourcePayload))
        {
            return currentPayload;
        }

        if (currentChanged == false)
        {
            return sourcePayload;
        }

        if (sourceChanged == false)
        {
            return currentPayload;
        }

        if (conflictResolution == null)
        {
            LvcDiagnostics.debug("LvcSemanticMergeEngine: tracked payload conflict chunk='{}' index={} baseState='{}' currentState='{}' sourceState='{}' baseBE={} currentBE={} sourceBE={}",
                    chunkKey, maskIndex, canonicalState(basePayload), canonicalState(currentPayload), canonicalState(sourcePayload),
                    basePayload.blockEntityNbt() != null, currentPayload.blockEntityNbt() != null, sourcePayload.blockEntityNbt() != null);
            throw new LvcMergeConflictException(LvcMergeConflictException.Reason.BLOCK_PAYLOAD,
                    "LVC chunk conflict at " + chunkKey + " index " + maskIndex);
        }

        return switch (conflictResolution)
        {
            case BASE -> basePayload;
            case INCOMING -> sourcePayload;
            case YOURS -> currentPayload;
        };
    }

    private static List<LvcChunk.EntityRecord> chooseEntityPayload(String chunkKey, LvcChunk baseChunk,
                                                                   LvcChunk currentChunk, LvcChunk sourceChunk,
                                                                   @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution)
    {
        if (conflictResolution != null)
        {
            return switch (conflictResolution)
            {
                case BASE -> baseChunk.entities();
                case INCOMING -> sourceChunk.entities();
                case YOURS -> currentChunk.entities();
            };
        }

        boolean currentChanged = !entityPayloadEquals(currentChunk.entities(), baseChunk.entities());
        boolean sourceChanged = !entityPayloadEquals(sourceChunk.entities(), baseChunk.entities());

        if (!currentChanged && sourceChanged)
        {
            return sourceChunk.entities();
        }

        if (currentChanged && sourceChanged)
        {
            LvcDiagnostics.debug("LvcSemanticMergeEngine: hidden entity payload changed on both sides chunk='{}'; kept current side entities={}",
                    chunkKey, currentChunk.entities().size());
        }

        return currentChunk.entities();
    }

    private static String canonicalState(LvcMergeBlockPayload payload)
    {
        return LvcChunkCodec.canonicalTrackedBlockState(payload.blockState());
    }

    private static boolean trackedPayloadEquals(LvcMergeBlockPayload left, LvcMergeBlockPayload right)
    {
        return Objects.equals(canonicalState(left), canonicalState(right)) &&
                Arrays.equals(left.blockEntityNbt(), right.blockEntityNbt());
    }

    private static boolean entityPayloadEquals(List<LvcChunk.EntityRecord> left, List<LvcChunk.EntityRecord> right)
    {
        if (left.size() != right.size())
        {
            return false;
        }

        for (int i = 0; i < left.size(); i++)
        {
            if (!Arrays.equals(left.get(i).canonicalNbt(), right.get(i).canonicalNbt()))
            {
                return false;
            }
        }

        return true;
    }

    private static boolean compatibleChunks(LvcChunk baseChunk, LvcChunk currentChunk, LvcChunk sourceChunk)
    {
        return !(baseChunk.sizeX() != currentChunk.sizeX() || baseChunk.sizeX() != sourceChunk.sizeX() ||
                baseChunk.sizeY() != currentChunk.sizeY() || baseChunk.sizeY() != sourceChunk.sizeY() ||
                baseChunk.sizeZ() != currentChunk.sizeZ() || baseChunk.sizeZ() != sourceChunk.sizeZ() ||
                !baseChunk.trackedMask().equals(currentChunk.trackedMask()) ||
                !baseChunk.trackedMask().equals(sourceChunk.trackedMask()));
    }

    private static Map<Integer, LvcMergeBlockPayload> payloadsByMaskIndex(LvcChunk chunk)
    {
        Map<Integer, byte[]> blockEntities = new HashMap<>();
        Map<Integer, List<LvcChunk.ScheduledTickRecord>> blockTicks = ticksByIndex(chunk.pendingBlockTicks());
        Map<Integer, List<LvcChunk.ScheduledTickRecord>> fluidTicks = ticksByIndex(chunk.pendingFluidTicks());
        Map<Integer, LvcMergeBlockPayload> payloads = new HashMap<>();
        BitSet mask = chunk.trackedMask();
        int ordinal = 0;

        for (LvcChunk.BlockEntityRecord record : chunk.blockEntities())
        {
            blockEntities.put(record.index(), record.canonicalNbt());
        }

        for (int maskIndex = mask.nextSetBit(0); maskIndex >= 0; maskIndex = mask.nextSetBit(maskIndex + 1))
        {
            payloads.put(maskIndex, new LvcMergeBlockPayload(
                    chunk.blockStateAtTrackedOrdinal(ordinal),
                    blockEntities.get(maskIndex),
                    blockTicks.getOrDefault(maskIndex, List.of()),
                    fluidTicks.getOrDefault(maskIndex, List.of())
            ));
            ordinal++;
        }

        return payloads;
    }

    private static Map<Integer, List<LvcChunk.ScheduledTickRecord>> ticksByIndex(List<LvcChunk.ScheduledTickRecord> ticks)
    {
        Map<Integer, List<LvcChunk.ScheduledTickRecord>> byIndex = new HashMap<>();

        for (LvcChunk.ScheduledTickRecord tick : ticks)
        {
            byIndex.computeIfAbsent(tick.index(), ignored -> new ArrayList<>()).add(tick);
        }

        return byIndex;
    }

    private static boolean sameRefs(LvcManifest.Site currentSite, LvcManifest.Site sourceSite)
    {
        return Objects.equals(currentSite.fullHashes(), sourceSite.fullHashes()) &&
                Objects.equals(currentSite.trackedHashesForComparison(), sourceSite.trackedHashesForComparison());
    }

    @Nullable
    private static LvcMergeChunkRef chunkRef(LvcManifest.Site site, String key)
    {
        String fullHash = site.fullHashes().get(key);

        if (fullHash == null)
        {
            return null;
        }

        String trackedHash = site.trackedHashesForComparison().get(key);

        if (trackedHash == null)
        {
            trackedHash = fullHash;
        }

        return new LvcMergeChunkRef(fullHash, trackedHash);
    }

    private static Map<String, LvcManifest.Site> sitesById(LvcManifest manifest)
    {
        Map<String, LvcManifest.Site> sites = new HashMap<>();

        for (LvcManifest.Site site : manifest.sites())
        {
            sites.put(site.id(), site);
        }

        return sites;
    }
}

record LvcSemanticMergeResult(LvcManifest manifest, int mergedChunks)
{
}

record LvcMergeSiteResult(Map<String, String> fullHashes, Map<String, String> trackedHashes, int mergedChunks)
{
}

record LvcMergeChunkResult(@Nullable LvcMergeChunkRef ref, boolean mergedChunk)
{
}

record LvcMergeChunkRef(String fullHash, String trackedHash)
{
}

record LvcMergeBlockPayload(String blockState, @Nullable byte[] blockEntityNbt,
                            List<LvcChunk.ScheduledTickRecord> pendingBlockTicks,
                            List<LvcChunk.ScheduledTickRecord> pendingFluidTicks)
{
    LvcMergeBlockPayload
    {
        pendingBlockTicks = List.copyOf(pendingBlockTicks);
        pendingFluidTicks = List.copyOf(pendingFluidTicks);
    }

    @Override
    public byte[] blockEntityNbt()
    {
        return this.blockEntityNbt == null ? null : this.blockEntityNbt.clone();
    }
}
