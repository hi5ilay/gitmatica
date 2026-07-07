package me.zly2006.rvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

final class RvcSemanticStorageIntegrationTest
{
    private RvcSemanticStorageIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("semantic chunk encoding is deterministic and round trips", RvcSemanticStorageIntegrationTest::semanticChunkEncodingIsDeterministicAndRoundTrips);
        IntegrationTestSupport.run("semantic chunk store writes content addressed objects once", RvcSemanticStorageIntegrationTest::semanticChunkStoreWritesContentAddressedObjectsOnce);
        IntegrationTestSupport.run("semantic manifest and local state round trip", RvcSemanticStorageIntegrationTest::semanticManifestAndLocalStateRoundTrip);
        IntegrationTestSupport.run("semantic manifest allows overlapping regions as tracked union masks", RvcSemanticStorageIntegrationTest::semanticManifestAllowsOverlappingRegionsAsTrackedUnionMasks);
        IntegrationTestSupport.run("minecraft block state strings are canonical", RvcSemanticStorageIntegrationTest::minecraftBlockStateStringsAreCanonical);
        IntegrationTestSupport.run("canonical block entity nbt sorts keys and ignores position", RvcSemanticStorageIntegrationTest::canonicalBlockEntityNbtSortsKeysAndIgnoresPosition);
        IntegrationTestSupport.run("project service maps selection to semantic site", RvcSemanticStorageIntegrationTest::projectServiceMapsSelectionToSemanticSite);
        IntegrationTestSupport.run("project service updates regions from existing local origin", RvcSemanticStorageIntegrationTest::projectServiceUpdatesRegionsFromExistingLocalOrigin);
        IntegrationTestSupport.run("project editor helpers separate versioned and local state", RvcSemanticStorageIntegrationTest::projectEditorHelpersSeparateVersionedAndLocalState);
        IntegrationTestSupport.run("project service creates empty browser project without commit", RvcSemanticStorageIntegrationTest::projectServiceCreatesEmptyBrowserProjectWithoutCommit);
        IntegrationTestSupport.run("capture stores gaps as untracked positions", RvcSemanticStorageIntegrationTest::captureStoresGapsAsUntrackedPositions);
        IntegrationTestSupport.run("capture changes only the intersecting storage chunk hash", RvcSemanticStorageIntegrationTest::captureChangesOnlyTheIntersectingStorageChunkHash);
        IntegrationTestSupport.run("capture applies local site origin before reading world", RvcSemanticStorageIntegrationTest::captureAppliesLocalSiteOriginBeforeReadingWorld);
        IntegrationTestSupport.run("semantic scan hashes without writing objects", RvcSemanticStorageIntegrationTest::semanticScanHashesWithoutWritingObjects);
        IntegrationTestSupport.run("semantic scan reports unavailable chunks as unknown", RvcSemanticStorageIntegrationTest::semanticScanReportsUnavailableChunksAsUnknown);
        IntegrationTestSupport.run("semantic repository init commits manifest and objects", RvcSemanticStorageIntegrationTest::semanticRepositoryInitCommitsManifestAndObjects);
        IntegrationTestSupport.run("semantic repository no-op commit reports no changes", RvcSemanticStorageIntegrationTest::semanticRepositoryNoOpCommitReportsNoChanges);
        IntegrationTestSupport.run("semantic repository commit updates only changed chunk reference", RvcSemanticStorageIntegrationTest::semanticRepositoryCommitUpdatesOnlyChangedChunkReference);
        IntegrationTestSupport.run("semantic repository update areas changes regions and chunk refs", RvcSemanticStorageIntegrationTest::semanticRepositoryUpdateAreasChangesRegionsAndChunkRefs);
    }

    private static void semanticChunkEncodingIsDeterministicAndRoundTrips() throws Exception
    {
        BitSet mask = new BitSet(RvcChunk.DEFAULT_VOLUME);
        mask.set(0);
        mask.set(1);
        mask.set(RvcChunk.DEFAULT_VOLUME - 1);

        RvcChunk chunk = new RvcChunk(
                RvcChunk.DEFAULT_SIZE,
                RvcChunk.DEFAULT_SIZE,
                RvcChunk.DEFAULT_SIZE,
                mask,
                List.of("minecraft:air", "minecraft:stone"),
                new int[] { 0, 1, 1 },
                List.of(new RvcChunk.BlockEntityRecord(1, new byte[] { 10, 1, 2, 3 })),
                List.of(new RvcChunk.ScheduledTickRecord(1, "minecraft:stone", 4, (byte) 1, 9L)),
                List.of(new RvcChunk.ScheduledTickRecord(RvcChunk.DEFAULT_VOLUME - 1, "minecraft:water", 2, (byte) 0, 3L))
        );

        byte[] first = RvcChunkCodec.encode(chunk);
        byte[] second = RvcChunkCodec.encode(chunk);
        IntegrationTestSupport.assertTrue(Arrays.equals(first, second), "same semantic chunk should encode to identical bytes");

        RvcChunk decoded = RvcChunkCodec.decode(first);
        IntegrationTestSupport.assertEquals(RvcChunk.DEFAULT_SIZE, decoded.sizeX(), "decoded size x");
        IntegrationTestSupport.assertEquals(3, decoded.trackedCount(), "decoded tracked count");
        IntegrationTestSupport.assertEquals(List.of("minecraft:air", "minecraft:stone"), decoded.palette(), "decoded palette");
        IntegrationTestSupport.assertEquals("minecraft:air", decoded.blockStateAtTrackedOrdinal(0), "tracked air must round-trip as tracked content");
        IntegrationTestSupport.assertEquals("minecraft:stone", decoded.blockStateAtTrackedOrdinal(2), "last tracked block state");
        IntegrationTestSupport.assertEquals(1, decoded.blockEntities().size(), "decoded block entity count");
        IntegrationTestSupport.assertTrue(Arrays.equals(new byte[] { 10, 1, 2, 3 }, decoded.blockEntities().get(0).canonicalNbt()), "decoded block entity bytes");
        IntegrationTestSupport.assertEquals("minecraft:stone", decoded.pendingBlockTicks().get(0).targetId(), "decoded block tick target");
        IntegrationTestSupport.assertEquals("minecraft:water", decoded.pendingFluidTicks().get(0).targetId(), "decoded fluid tick target");
    }

    private static void semanticChunkStoreWritesContentAddressedObjectsOnce() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-semantic-store-");
        BitSet mask = new BitSet(RvcChunk.DEFAULT_VOLUME);
        mask.set(0);

        byte[] bytes = RvcChunkCodec.encode(RvcChunk.fromTrackedBlockStates(mask, List.of("minecraft:stone")));
        String objectId = RvcChunkStore.writeObjectIfMissing(repoDir, bytes);
        Path objectPath = RvcChunkStore.objectPath(repoDir, objectId);
        long firstModified = Files.getLastModifiedTime(objectPath).toMillis();

        String secondObjectId = RvcChunkStore.writeObjectIfMissing(repoDir, bytes);
        long secondModified = Files.getLastModifiedTime(objectPath).toMillis();

        IntegrationTestSupport.assertEquals(objectId, secondObjectId, "same bytes should produce the same object id");
        IntegrationTestSupport.assertTrue(Files.exists(objectPath), "object file should exist");
        IntegrationTestSupport.assertEquals(firstModified, secondModified, "existing object should not be rewritten");
        IntegrationTestSupport.assertTrue(Arrays.equals(bytes, RvcChunkStore.readObject(repoDir, objectId)), "object bytes should read back unchanged");
        IntegrationTestSupport.assertTrue(objectPath.toString().contains("/objects/sha256/"), "object path should use sha256 fanout directory");
    }

    private static void semanticManifestAndLocalStateRoundTrip() throws Exception
    {
        String objectId = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        RvcManifest.Site overworldMain = new RvcManifest.Site(
                "overworld_main",
                "Overworld Main",
                "minecraft:overworld",
                List.of(new RvcManifest.Region("storage", "Storage", List.of(0, 0, 0), List.of(32, 16, 32))),
                Map.of("0,0,0", objectId)
        );
        RvcManifest.Site overworldRemote = new RvcManifest.Site(
                "overworld_remote",
                "Overworld Remote",
                "minecraft:overworld",
                List.of(new RvcManifest.Region("dropoff", "Dropoff", List.of(0, 0, 0), List.of(16, 16, 16))),
                Map.of()
        );
        RvcManifest manifest = RvcManifest.create("Gold Farm", List.of(overworldMain, overworldRemote));
        String json = manifest.toJson();

        IntegrationTestSupport.assertTrue(json.contains("\"project_id\""), "manifest should use project_id JSON key");
        IntegrationTestSupport.assertTrue(json.contains("\"chunk_size\""), "manifest should use chunk_size JSON key");

        RvcManifest decodedManifest = RvcManifest.fromJson(json);
        IntegrationTestSupport.assertEquals("Gold Farm", decodedManifest.name(), "manifest name");
        IntegrationTestSupport.assertEquals(2, decodedManifest.sites().size(), "same dimension multi-site manifest should be valid");
        IntegrationTestSupport.assertEquals(objectId, decodedManifest.sites().get(0).chunks().get("0,0,0"), "chunk object id should round-trip");

        UUID projectId = decodedManifest.projectId();
        RvcLocalState localState = RvcLocalState.create(projectId, "overworld_main", Map.of(
                "overworld_main", new RvcLocalState.SitePlacement("minecraft:overworld", List.of(1000, 64, 1000), "Server"),
                "overworld_remote", new RvcLocalState.SitePlacement("minecraft:overworld", List.of(8000, 70, -3000), "Server")
        ));
        String localJson = localState.toJson();

        IntegrationTestSupport.assertTrue(localJson.contains("\"active_site\""), "local state should use active_site JSON key");
        IntegrationTestSupport.assertTrue(localJson.contains("\"world_hint\""), "local state should use world_hint JSON key");

        RvcLocalState decodedLocal = RvcLocalState.fromJson(localJson);
        IntegrationTestSupport.assertEquals(projectId, decodedLocal.projectId(), "local project id");
        IntegrationTestSupport.assertEquals(List.of(8000, 70, -3000), decodedLocal.sites().get("overworld_remote").origin(), "local remote site origin");
    }

    private static void semanticManifestAllowsOverlappingRegionsAsTrackedUnionMasks() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-overlap-union-");
        RvcManifest manifest = RvcManifest.create("Overlap", List.of(new RvcManifest.Site(
                "main",
                "Main",
                "minecraft:overworld",
                List.of(
                        new RvcManifest.Region("a", "A", List.of(0, 0, 0), List.of(2, 1, 1)),
                        new RvcManifest.Region("b", "B", List.of(1, 0, 0), List.of(2, 1, 1))
                ),
                Map.of()
        )));
        RvcManifest.Site site = manifest.site("main");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        reader.setBlock(new RvcIntPosition(1, 0, 0), "minecraft:dirt");

        RvcCaptureEngine.Result result = RvcCaptureEngine.captureSite(repoDir, site, placementAt(0, 0, 0), reader);
        RvcChunk chunk = readOnlyCapturedChunk(repoDir, result);

        IntegrationTestSupport.assertEquals(2, site.regions().size(), "overlapping region definitions should be preserved");
        IntegrationTestSupport.assertEquals(3, chunk.trackedCount(), "overlapping regions should track the union, not duplicate shared blocks");
        IntegrationTestSupport.assertEquals(3, reader.requestedPositions.size(), "overlap position should be read once");
        IntegrationTestSupport.assertTrue(chunk.isTracked(0), "first region start should be tracked");
        IntegrationTestSupport.assertTrue(chunk.isTracked(1), "shared overlap should be tracked once");
        IntegrationTestSupport.assertTrue(chunk.isTracked(2), "second region end should be tracked");
        IntegrationTestSupport.assertEquals("minecraft:stone", chunk.blockStateAtTrackedOrdinal(0), "first tracked block state");
        IntegrationTestSupport.assertEquals("minecraft:dirt", chunk.blockStateAtTrackedOrdinal(1), "overlapped tracked block state");
        IntegrationTestSupport.assertEquals("minecraft:stone", chunk.blockStateAtTrackedOrdinal(2), "last tracked block state");
    }

    private static void minecraftBlockStateStringsAreCanonical()
    {
        bootstrapMinecraft();

        BlockState repeater = Blocks.REPEATER.defaultBlockState()
                .setValue(RepeaterBlock.DELAY, 3)
                .setValue(RepeaterBlock.FACING, Direction.NORTH)
                .setValue(RepeaterBlock.LOCKED, true);

        IntegrationTestSupport.assertEquals("minecraft:repeater[delay=3,facing=north,locked=true,powered=false]", RvcMinecraftWorldReader.blockStateString(repeater), "properties should be sorted by name");
        IntegrationTestSupport.assertEquals("minecraft:stone", RvcMinecraftWorldReader.blockStateString(Blocks.STONE.defaultBlockState()), "single-state blocks should omit property brackets");
    }

    private static void canonicalBlockEntityNbtSortsKeysAndIgnoresPosition() throws Exception
    {
        CompoundTag first = new CompoundTag();
        first.putInt("z", 999);
        first.putString("id", "minecraft:chest");
        first.putInt("x", 123);
        first.putInt("y", 64);
        first.put("Items", itemList(1, "minecraft:stone"));
        CompoundTag nestedFirst = new CompoundTag();
        nestedFirst.putString("b", "two");
        nestedFirst.putString("a", "one");
        first.put("Custom", nestedFirst);

        CompoundTag second = new CompoundTag();
        CompoundTag nestedSecond = new CompoundTag();
        nestedSecond.putString("a", "one");
        nestedSecond.putString("b", "two");
        second.put("Custom", nestedSecond);
        second.put("Items", itemList(1, "minecraft:stone"));
        second.putInt("y", -10);
        second.putInt("x", -20);
        second.putString("id", "minecraft:chest");
        second.putInt("z", -30);

        byte[] firstBytes = RvcCanonicalNbt.encodeBlockEntity(first);
        byte[] secondBytes = RvcCanonicalNbt.encodeBlockEntity(second);

        IntegrationTestSupport.assertTrue(Arrays.equals(firstBytes, secondBytes), "canonical block entity bytes should ignore absolute position and key insertion order");

        second.getListOrEmpty("Items").getCompoundOrEmpty(0).putString("id", "minecraft:dirt");
        IntegrationTestSupport.assertTrue(!Arrays.equals(firstBytes, RvcCanonicalNbt.encodeBlockEntity(second)), "semantic block entity content changes should affect bytes");
    }

    private static void projectServiceMapsSelectionToSemanticSite()
    {
        AreaSelection selection = areaSelectionFromBoxes(
                "Farm",
                List.of(
                        boxJson("Main Storage", new BlockPos(10, 64, 10), new BlockPos(11, 65, 11)),
                        boxJson("Main/Storage", new BlockPos(20, 70, 20), new BlockPos(20, 70, 20))
                )
        );

        RvcManifest.Site site = RvcProjectService.createMainSiteFromSelection("Farm", "minecraft:overworld", selection);
        RvcLocalState.SitePlacement placement = RvcProjectService.createSitePlacement(selection.getEffectiveOrigin(), "minecraft:overworld");

        IntegrationTestSupport.assertEquals("main", site.id(), "MVP creates one active site");
        IntegrationTestSupport.assertEquals("minecraft:overworld", site.dimension(), "site dimension");
        IntegrationTestSupport.assertEquals(2, site.regions().size(), "selection boxes become explicit regions");
        IntegrationTestSupport.assertEquals("main_storage", site.regions().get(0).id(), "region id should be stable and safe");
        IntegrationTestSupport.assertEquals("main_storage_2", site.regions().get(1).id(), "duplicate region ids should be uniqued");
        IntegrationTestSupport.assertEquals(List.of(0, 0, 0), site.regions().get(0).min(), "first region should be relative to local origin");
        IntegrationTestSupport.assertEquals(List.of(2, 2, 2), site.regions().get(0).size(), "region size should be inclusive of both corners");
        IntegrationTestSupport.assertEquals(List.of(10, 64, 10), placement.origin(), "local placement stores the world origin");
    }

    private static void projectServiceUpdatesRegionsFromExistingLocalOrigin()
    {
        RvcManifest.Region existing = new RvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(1, 1, 1));
        AreaSelection expandedSelection = areaSelectionFromBoxes(
                "Farm",
                List.of(boxJson("Line", new BlockPos(10, 64, 10), new BlockPos(26, 64, 10)))
        );

        List<RvcManifest.Region> expanded = RvcProjectService.createRegionsFromSelection(expandedSelection, new BlockPos(10, 64, 10), List.of(existing));

        IntegrationTestSupport.assertEquals("line", expanded.get(0).id(), "same region name should preserve id while resizing");
        IntegrationTestSupport.assertEquals(List.of(0, 0, 0), expanded.get(0).min(), "update areas should stay relative to existing local origin");
        IntegrationTestSupport.assertEquals(List.of(17, 1, 1), expanded.get(0).size(), "expanded region size");

        AreaSelection renamedSelection = areaSelectionFromBoxes(
                "Farm",
                List.of(boxJson("Renamed Line", new BlockPos(10, 64, 10), new BlockPos(10, 64, 10)))
        );
        List<RvcManifest.Region> renamed = RvcProjectService.createRegionsFromSelection(renamedSelection, new BlockPos(10, 64, 10), List.of(existing));

        IntegrationTestSupport.assertEquals("line", renamed.get(0).id(), "same bounds should preserve id while renaming");
        IntegrationTestSupport.assertEquals("Renamed Line", renamed.get(0).name(), "region display name should update");
    }

    private static void projectEditorHelpersSeparateVersionedAndLocalState() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-editor-state-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        RvcSemanticRepository.CommitResult init = RvcSemanticRepository.initProject(repoDir, "Editor", singleLineSite(1), placementAt(10, 64, 10), reader, player("Editor"));
        String manifestBeforeLocalEdit = Files.readString(repoDir.resolve(RvcSemanticRepository.MANIFEST));

        RvcProjectService.updateSemanticLocalOrigin(repoDir, new BlockPos(20, 70, 20));
        RvcProjectService.ProjectEditorState localState = RvcProjectService.readSemanticProjectEditorState(repoDir);

        IntegrationTestSupport.assertEquals(new BlockPos(20, 70, 20), localState.localOrigin(), "local origin edit should update local.json");
        IntegrationTestSupport.assertEquals(manifestBeforeLocalEdit, Files.readString(repoDir.resolve(RvcSemanticRepository.MANIFEST)), "local origin edit must not touch rvc.json");

        try (Git git = Git.open(repoDir.toFile()))
        {
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "local-only editor change should keep Git status clean");
        }

        RvcProjectService.updateSemanticProjectName(repoDir, "Editor Renamed");
        RvcManifest renamedManifest = RvcSemanticRepository.readManifest(repoDir);

        IntegrationTestSupport.assertEquals("Editor Renamed", renamedManifest.name(), "project name edit should update manifest name");
        IntegrationTestSupport.assertEquals("Editor Renamed", renamedManifest.site("main").name(), "single-site MVP should keep site name aligned with project name");

        RvcProjectService.updateSemanticRegion(repoDir, "line", "Long Line", new BlockPos(0, 0, 0), new BlockPos(2, 1, 1));
        RvcManifest resizedManifest = RvcSemanticRepository.readManifest(repoDir);

        IntegrationTestSupport.assertEquals(List.of(2, 1, 1), resizedManifest.site("main").regions().get(0).size(), "region size edit should update versioned manifest");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").chunks(), resizedManifest.site("main").chunks(), "editor metadata edits should not recapture chunk refs until commit");

        RvcManifest.Region added = RvcProjectService.createSemanticRegion(repoDir, "Extra", new BlockPos(3, 0, 0), new BlockPos(1, 1, 1));
        IntegrationTestSupport.assertEquals(2, RvcSemanticRepository.readManifest(repoDir).site("main").regions().size(), "new region should be versioned");
        assertDuplicateRegionNameRejected(() -> RvcProjectService.createSemanticRegion(repoDir, " extra ", new BlockPos(4, 0, 0), new BlockPos(1, 1, 1)));
        assertDuplicateRegionNameRejected(() -> RvcProjectService.updateSemanticRegion(repoDir, "line", "Extra", BlockPos.ZERO, new BlockPos(2, 1, 1)));

        RvcProjectService.deleteSemanticRegion(repoDir, added.id());
        IntegrationTestSupport.assertEquals(1, RvcSemanticRepository.readManifest(repoDir).site("main").regions().size(), "deleted region should be removed from versioned manifest");
    }

    private static void projectServiceCreatesEmptyBrowserProjectWithoutCommit() throws Exception
    {
        Path gameDir = Files.createTempDirectory("rvc-empty-project-game-");
        BlockPos origin = new BlockPos(12, 65, -4);
        RvcProjectService.EmptyProjectResult created = RvcProjectService.createEmptyProject(gameDir, "Manual Project", origin, "minecraft:overworld");
        Path repoDir = created.repositoryDirectory();

        IntegrationTestSupport.assertEquals("Manual Project", created.projectName(), "manual project display name");
        IntegrationTestSupport.assertTrue(Files.isDirectory(repoDir.resolve(".git")), "manual project should initialize Git repository");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(repoDir.resolve(RvcSemanticRepository.MANIFEST)), "manual project should write manifest");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(repoDir.resolve(RvcSemanticRepository.LOCAL_JSON)), "manual project should write local state");
        IntegrationTestSupport.assertEquals(null, RvcRepository.resolveHead(repoDir), "manual project should start without an initial commit");
        IntegrationTestSupport.assertEquals(0, RvcProjectService.listCommits(repoDir).size(), "manual project history should start empty");

        try (Git git = Git.open(repoDir.toFile()))
        {
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + RvcProjectService.DEFAULT_BRANCH, git.getRepository().getFullBranch(), "manual project should initialize the default branch");
        }

        RvcManifest manifest = RvcSemanticRepository.readManifest(repoDir);
        RvcLocalState localState = RvcSemanticRepository.readLocalState(repoDir);

        IntegrationTestSupport.assertEquals(0, manifest.site("main").regions().size(), "manual project should start with no regions");
        IntegrationTestSupport.assertEquals(0, manifest.site("main").chunks().size(), "manual project should start with no chunk refs");
        IntegrationTestSupport.assertEquals(List.of(12, 65, -4), localState.sites().get("main").origin(), "manual project local origin");

        RvcProjectService.ProjectEditorState editorState = RvcProjectService.readSemanticProjectEditorState(repoDir);
        IntegrationTestSupport.assertEquals(0, editorState.regions().size(), "project editor should open empty manual project");

        RvcProjectService.createSemanticRegion(repoDir, "First Area", BlockPos.ZERO, new BlockPos(1, 1, 1));
        RvcManifest withRegion = RvcSemanticRepository.readManifest(repoDir);
        RvcSemanticRepository.CommitResult firstCommit = RvcSemanticRepository.commitSite(
                repoDir,
                withRegion,
                RvcSemanticRepository.readLocalState(repoDir),
                "main",
                new FakeWorldReader("minecraft:stone"),
                player("ManualProject"),
                "first version"
        );

        IntegrationTestSupport.assertNotNull(firstCommit.commit(), "manual project first region save should create first commit");
        IntegrationTestSupport.assertEquals(firstCommit.commit().getId(), RvcRepository.resolveHead(repoDir), "manual project first commit should become HEAD");
        IntegrationTestSupport.assertEquals(1, firstCommit.manifest().site("main").regions().size(), "first commit should keep the new region");
        IntegrationTestSupport.assertEquals(1, firstCommit.manifest().site("main").chunks().size(), "first commit should capture tracked content");
    }

    private static void assertDuplicateRegionNameRejected(IntegrationTestSupport.ThrowingRunnable action) throws Exception
    {
        try
        {
            action.run();
        }
        catch (IllegalArgumentException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("Duplicate RVC region name"), "duplicate name should report duplicate region name");
            return;
        }

        throw new AssertionError("duplicate region name should be rejected");
    }

    private static void captureStoresGapsAsUntrackedPositions() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-capture-gaps-");
        RvcManifest.Site site = validatedSingleSite(List.of(
                new RvcManifest.Region("left", "Left", List.of(0, 0, 0), List.of(1, 1, 1)),
                new RvcManifest.Region("right", "Right", List.of(2, 0, 0), List.of(1, 1, 1))
        ));
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        reader.setBlock(new RvcIntPosition(1, 0, 0), "minecraft:diamond_block");

        RvcCaptureEngine.Result result = RvcCaptureEngine.captureSite(repoDir, site, placementAt(0, 0, 0), reader);
        RvcChunk chunk = readOnlyCapturedChunk(repoDir, result);

        IntegrationTestSupport.assertEquals(2, chunk.trackedCount(), "only user region positions should be tracked");
        IntegrationTestSupport.assertTrue(chunk.isTracked(0), "left region position should be tracked");
        IntegrationTestSupport.assertTrue(!chunk.isTracked(1), "gap position must be untracked, not tracked air or real world block");
        IntegrationTestSupport.assertTrue(chunk.isTracked(2), "right region position should be tracked");
        IntegrationTestSupport.assertEquals(List.of("minecraft:stone"), chunk.palette(), "gap block should not enter the chunk palette");
    }

    private static void captureChangesOnlyTheIntersectingStorageChunkHash() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-capture-change-");
        RvcManifest.Site site = validatedSingleSite(List.of(new RvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(17, 1, 1))));
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");

        RvcCaptureEngine.Result first = RvcCaptureEngine.captureSite(repoDir, site, placementAt(0, 0, 0), reader);
        reader.setBlock(new RvcIntPosition(16, 0, 0), "minecraft:dirt");
        RvcCaptureEngine.Result second = RvcCaptureEngine.captureSite(repoDir, site, placementAt(0, 0, 0), reader);

        IntegrationTestSupport.assertEquals(2, first.chunkObjects().size(), "17-block line should span two RVC chunks");
        IntegrationTestSupport.assertEquals(first.chunkObjects().get("0,0,0"), second.chunkObjects().get("0,0,0"), "unchanged storage chunk hash should be reused");
        IntegrationTestSupport.assertTrue(!first.chunkObjects().get("1,0,0").equals(second.chunkObjects().get("1,0,0")), "changed storage chunk hash should differ");
        IntegrationTestSupport.assertTrue(Files.exists(RvcChunkStore.objectPath(repoDir, first.chunkObjects().get("0,0,0"))), "unchanged object should exist in store");
        IntegrationTestSupport.assertTrue(Files.exists(RvcChunkStore.objectPath(repoDir, second.chunkObjects().get("1,0,0"))), "changed object should exist in store");
    }

    private static void captureAppliesLocalSiteOriginBeforeReadingWorld() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-capture-origin-");
        RvcManifest.Site site = validatedSingleSite(List.of(new RvcManifest.Region("area", "Area", List.of(0, 0, 0), List.of(16, 1, 16))));
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");

        RvcCaptureEngine.captureSite(repoDir, site, placementAt(5, 64, 5), reader);

        IntegrationTestSupport.assertTrue(reader.requestedPositions.contains(new RvcIntPosition(5, 64, 5)), "capture should read at local site origin");
        IntegrationTestSupport.assertTrue(reader.requestedPositions.contains(new RvcIntPosition(20, 64, 20)), "project-relative RVC chunk can cross Minecraft chunk boundaries after origin offset");
    }

    private static void semanticScanHashesWithoutWritingObjects() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-semantic-scan-");
        RvcManifest.Site site = singleLineSite(1);
        RvcLocalState.SitePlacement placement = placementAt(0, 0, 0);
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        RvcCaptureEngine.Result committed = RvcCaptureEngine.captureSite(repoDir, site, placement, reader);

        reader.setBlock(new RvcIntPosition(8, 0, 0), "minecraft:dirt");
        RvcCaptureEngine.Result outsideChangeScan = RvcCaptureEngine.scanSite(site, placement, reader);
        RvcProjectService.SemanticScanResult clean = RvcProjectService.SemanticScanResult.compare("main", committed.chunkObjects(), outsideChangeScan);

        IntegrationTestSupport.assertTrue(clean.clean(), "outside tracked region changes should scan as clean");
        IntegrationTestSupport.assertEquals(1, clean.unchangedChunks(), "clean scan should count the tracked chunk as unchanged");

        reader.setBlock(new RvcIntPosition(0, 0, 0), "minecraft:dirt");
        RvcCaptureEngine.Result dirtyScan = RvcCaptureEngine.scanSite(site, placement, reader);
        RvcProjectService.SemanticScanResult dirty = RvcProjectService.SemanticScanResult.compare("main", committed.chunkObjects(), dirtyScan);
        String dirtyObjectId = dirtyScan.chunkObjects().get("0,0,0");

        IntegrationTestSupport.assertEquals(1, dirty.changedChunks(), "tracked block changes should scan as changed");
        IntegrationTestSupport.assertEquals(1, dirty.dirtyChunks(), "dirty chunk count");
        IntegrationTestSupport.assertTrue(!Files.exists(RvcChunkStore.objectPath(repoDir, dirtyObjectId)), "scan must not write newly hashed chunk objects");
    }

    private static void semanticScanReportsUnavailableChunksAsUnknown() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-semantic-scan-unknown-");
        RvcManifest.Site site = singleLineSite(17);
        RvcLocalState.SitePlacement placement = placementAt(0, 0, 0);
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        RvcCaptureEngine.Result committed = RvcCaptureEngine.captureSite(repoDir, site, placement, reader);

        reader.setUnavailable(new RvcIntPosition(16, 0, 0));
        RvcCaptureEngine.Result scan = RvcCaptureEngine.scanSite(site, placement, reader);
        RvcProjectService.SemanticScanResult result = RvcProjectService.SemanticScanResult.compare("main", committed.chunkObjects(), scan);

        IntegrationTestSupport.assertEquals(1, result.unknownChunks(), "unavailable tracked data should be reported as unknown");
        IntegrationTestSupport.assertEquals(1, result.unchangedChunks(), "available chunk should still compare clean");
        IntegrationTestSupport.assertEquals(0, result.removedChunks(), "unknown chunks must not be treated as removed");
        IntegrationTestSupport.assertTrue(!result.clean(), "unknown scan result is not clean");
    }

    private static void semanticRepositoryInitCommitsManifestAndObjects() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-semantic-init-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        RvcSemanticRepository.CommitResult result = RvcSemanticRepository.initProject(repoDir, "Semantic Init", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticInit"));

        IntegrationTestSupport.assertNotNull(result.commit(), "semantic init should create a commit");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(RvcSemanticRepository.MANIFEST), "\"format\": \"rvc-manifest-v1\"");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(RvcSemanticRepository.LOCAL_JSON), "\"format\": \"rvc-local-v1\"");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(RvcSemanticRepository.GITIGNORE), "/local.json");

        String objectId = result.manifest().site("main").chunks().get("0,0,0");
        IntegrationTestSupport.assertTrue(Files.exists(RvcChunkStore.objectPath(repoDir, objectId)), "semantic init should write captured chunk object");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            ObjectId headId = repository.resolve(Constants.HEAD);
            IntegrationTestSupport.assertNotNull(headId, "semantic repo HEAD");
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "semantic repo should be clean after init");

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit commit = revWalk.parseCommit(headId);
                Set<String> files = committedFiles(repository, commit);

                IntegrationTestSupport.assertTrue(files.contains(RvcSemanticRepository.MANIFEST), "semantic commit should include rvc.json");
                IntegrationTestSupport.assertTrue(files.contains(RvcSemanticRepository.README), "semantic commit should include README");
                IntegrationTestSupport.assertTrue(files.contains(RvcSemanticRepository.GITIGNORE), "semantic commit should include .gitignore");
                IntegrationTestSupport.assertTrue(files.stream().anyMatch(path -> path.endsWith(RvcChunkStore.EXTENSION)), "semantic commit should include chunk object");
                IntegrationTestSupport.assertTrue(!files.contains(RvcSemanticRepository.LOCAL_JSON), "semantic commit must not include local.json");

                String rawCommit = new String(repository.open(headId).getBytes(), java.nio.charset.StandardCharsets.UTF_8);
                IntegrationTestSupport.assertTrue(rawCommit.contains("\nrvc-version 1\n"), "semantic commit should include RVC metadata");
                IntegrationTestSupport.assertTrue(rawCommit.contains("\nx-created-by rvc\n"), "semantic commit should include created-by metadata");
            }
        }
    }

    private static void semanticRepositoryNoOpCommitReportsNoChanges() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-semantic-noop-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        RvcSemanticRepository.CommitResult init = RvcSemanticRepository.initProject(repoDir, "Semantic Noop", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticNoop"));
        ObjectId headBefore = RvcRepository.resolveHead(repoDir);

        RvcSemanticRepository.CommitResult noOp = RvcSemanticRepository.commitSite(repoDir, init.manifest(), init.localState(), "main", reader, player("SemanticNoop"), "same content");

        IntegrationTestSupport.assertEquals(null, noOp.commit(), "same semantic content should not create a commit");
        IntegrationTestSupport.assertEquals(headBefore, RvcRepository.resolveHead(repoDir), "no-op semantic commit should not move HEAD");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").chunks(), noOp.manifest().site("main").chunks(), "no-op semantic capture should keep chunk references");
    }

    private static void semanticRepositoryCommitUpdatesOnlyChangedChunkReference() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-semantic-update-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        RvcSemanticRepository.CommitResult init = RvcSemanticRepository.initProject(repoDir, "Semantic Update", singleLineSite(17), placementAt(0, 0, 0), reader, player("SemanticUpdate"));

        reader.setBlock(new RvcIntPosition(16, 0, 0), "minecraft:dirt");
        RvcSemanticRepository.CommitResult update = RvcSemanticRepository.commitSite(repoDir, init.manifest(), init.localState(), "main", reader, player("SemanticUpdate"), "change second chunk");

        IntegrationTestSupport.assertNotNull(update.commit(), "changed semantic content should create a commit");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").chunks().get("0,0,0"), update.manifest().site("main").chunks().get("0,0,0"), "unchanged chunk reference should be reused");
        IntegrationTestSupport.assertTrue(!init.manifest().site("main").chunks().get("1,0,0").equals(update.manifest().site("main").chunks().get("1,0,0")), "changed chunk reference should update");
        IntegrationTestSupport.assertEquals(update.manifest().site("main").chunks(), RvcSemanticRepository.readManifest(repoDir).site("main").chunks(), "updated manifest should be written to disk");
        IntegrationTestSupport.assertEquals(update.commit().getId(), RvcRepository.resolveHead(repoDir), "semantic update commit should move HEAD");
    }

    private static void semanticRepositoryUpdateAreasChangesRegionsAndChunkRefs() throws Exception
    {
        Path repoDir = Files.createTempDirectory("rvc-semantic-areas-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        RvcSemanticRepository.CommitResult init = RvcSemanticRepository.initProject(repoDir, "Semantic Areas", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticAreas"));
        ObjectId initHead = RvcRepository.resolveHead(repoDir);

        List<RvcManifest.Region> expandedRegions = List.of(new RvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(17, 1, 1)));
        RvcSemanticRepository.CommitResult expanded = RvcSemanticRepository.updateSiteAreas(repoDir, init.manifest(), init.localState(), "main", expandedRegions, reader, player("SemanticAreas"), "expand area");

        IntegrationTestSupport.assertNotNull(expanded.commit(), "expanded area should create a commit");
        IntegrationTestSupport.assertTrue(!initHead.equals(expanded.commit().getId()), "expanded area should move HEAD");
        IntegrationTestSupport.assertEquals(List.of(17, 1, 1), expanded.manifest().site("main").regions().get(0).size(), "expanded region should be versioned");
        IntegrationTestSupport.assertEquals(2, expanded.manifest().site("main").chunks().size(), "17-block area should reference two RVC chunks");

        List<RvcManifest.Region> shrunkRegions = List.of(new RvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(1, 1, 1)));
        RvcSemanticRepository.CommitResult shrunk = RvcSemanticRepository.updateSiteAreas(repoDir, expanded.manifest(), expanded.localState(), "main", shrunkRegions, reader, player("SemanticAreas"), "shrink area");

        IntegrationTestSupport.assertNotNull(shrunk.commit(), "shrunk area should create a commit");
        IntegrationTestSupport.assertEquals(List.of(1, 1, 1), shrunk.manifest().site("main").regions().get(0).size(), "shrunk region should be versioned");
        IntegrationTestSupport.assertEquals(1, shrunk.manifest().site("main").chunks().size(), "chunk refs with no tracked positions should leave the manifest");
        IntegrationTestSupport.assertEquals(shrunk.commit().getId(), RvcRepository.resolveHead(repoDir), "semantic area update commit should move HEAD");
    }

    private static RvcManifest.Site validatedSingleSite(List<RvcManifest.Region> regions)
    {
        RvcManifest manifest = RvcManifest.create("Capture", List.of(new RvcManifest.Site("main", "Main", "minecraft:overworld", regions, Map.of())));
        return manifest.sites().get(0);
    }

    private static RvcManifest.Site singleLineSite(int sizeX)
    {
        return new RvcManifest.Site(
                "main",
                "Main",
                "minecraft:overworld",
                List.of(new RvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(sizeX, 1, 1))),
                Map.of()
        );
    }

    private static void bootstrapMinecraft()
    {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        Bootstrap.bootStrap();
    }

    private static ListTag itemList(int count, String itemId)
    {
        ListTag items = new ListTag();
        CompoundTag item = new CompoundTag();
        item.putByte("Slot", (byte) 0);
        item.putInt("count", count);
        item.putString("id", itemId);
        items.add(item);
        return items;
    }

    private static AreaSelection areaSelectionFromBoxes(String name, List<JsonObject> boxes)
    {
        JsonObject selection = new JsonObject();
        JsonArray boxArray = new JsonArray();

        for (JsonObject box : boxes)
        {
            boxArray.add(box);
        }

        selection.add("name", new JsonPrimitive(name));
        selection.add("current", new JsonPrimitive(boxes.get(0).get("name").getAsString()));
        selection.add("boxes", boxArray);
        return AreaSelection.fromJson(selection);
    }

    private static JsonObject boxJson(String name, BlockPos pos1, BlockPos pos2)
    {
        JsonObject box = new JsonObject();
        box.add("name", new JsonPrimitive(name));
        box.add("pos1", JsonUtils.blockPosToJson(pos1));
        box.add("pos2", JsonUtils.blockPosToJson(pos2));
        return box;
    }

    private static RvcLocalState.SitePlacement placementAt(int x, int y, int z)
    {
        return new RvcLocalState.SitePlacement("minecraft:overworld", List.of(x, y, z), "Test World");
    }

    private static RvcChunk readOnlyCapturedChunk(Path repoDir, RvcCaptureEngine.Result result) throws Exception
    {
        IntegrationTestSupport.assertEquals(1, result.chunkObjects().size(), "expected exactly one captured chunk");
        String objectId = result.chunkObjects().values().iterator().next();
        return RvcChunkCodec.decode(RvcChunkStore.readObject(repoDir, objectId));
    }

    private static RvcPlayerIdentity player(String name)
    {
        return new RvcPlayerIdentity(name, UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static Set<String> committedFiles(Repository repository, RevCommit commit) throws Exception
    {
        try (TreeWalk treeWalk = new TreeWalk(repository))
        {
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            Set<String> files = new HashSet<>();

            while (treeWalk.next())
            {
                files.add(treeWalk.getPathString());
            }

            return files.stream().collect(Collectors.toSet());
        }
    }

    private static final class FakeWorldReader implements RvcWorldReader
    {
        private final String defaultBlock;
        private final Map<RvcIntPosition, String> blocks = new HashMap<>();
        private final Set<RvcIntPosition> unavailablePositions = new HashSet<>();
        private final Set<RvcIntPosition> requestedPositions = new HashSet<>();

        private FakeWorldReader(String defaultBlock)
        {
            this.defaultBlock = defaultBlock;
        }

        private void setBlock(RvcIntPosition pos, String blockState)
        {
            this.blocks.put(pos, blockState);
        }

        private void setUnavailable(RvcIntPosition pos)
        {
            this.unavailablePositions.add(pos);
        }

        @Override
        public boolean canReadAt(RvcIntPosition worldPos)
        {
            return !this.unavailablePositions.contains(worldPos);
        }

        @Override
        public String blockStateAt(RvcIntPosition worldPos)
        {
            this.requestedPositions.add(worldPos);
            return this.blocks.getOrDefault(worldPos, this.defaultBlock);
        }
    }
}
