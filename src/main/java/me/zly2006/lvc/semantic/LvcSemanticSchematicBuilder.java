package me.zly2006.lvc.semantic;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.util.BlockUtils;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.capture.LvcCapturePlanner;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcChunkCodec;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.util.LvcEntityNbt;
import me.zly2006.lvc.util.LvcLootTablePreview;

public final class LvcSemanticSchematicBuilder
{
    private LvcSemanticSchematicBuilder()
    {
    }

    public static LitematicaSchematic buildWorkingTreeSchematic(Path repositoryDirectory, LvcManifest manifest,
                                                         LvcLocalState localState, String siteId) throws IOException
    {
        return buildWorkingTreeSchematic(repositoryDirectory, manifest, localState, siteId, null);
    }

    public static LitematicaSchematic buildWorkingTreeSchematic(Path repositoryDirectory, LvcManifest manifest,
                                                         LvcLocalState localState, String siteId,
                                                         @javax.annotation.Nullable ServerLevel lootPreviewWorld) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        return buildSchematic(manifest, localState, siteId,
                objectId -> LvcChunkStore.readObject(repositoryDirectory, objectId), lootPreviewWorld);
    }

    public static LitematicaSchematic buildSchematic(LvcManifest manifest, LvcLocalState localState, String siteId,
                                              ChunkObjectReader objectReader) throws IOException
    {
        return buildSchematic(manifest, localState, siteId, objectReader, null);
    }

    public static LitematicaSchematic buildSchematic(LvcManifest manifest, LvcLocalState localState, String siteId,
                                              ChunkObjectReader objectReader,
                                              @javax.annotation.Nullable ServerLevel lootPreviewWorld) throws IOException
    {
        BuildSession session = beginSchematicBuild(manifest, localState, siteId, objectReader, lootPreviewWorld);

        while (!session.isComplete())
        {
            session.processNextChunk();
        }

        return session.result();
    }

    public static BuildSession beginSchematicBuild(LvcManifest manifest, LvcLocalState localState, String siteId,
                                                   ChunkObjectReader objectReader) throws IOException
    {
        return beginSchematicBuild(manifest, localState, siteId, objectReader, null);
    }

    public static BuildSession beginSchematicBuild(LvcManifest manifest, LvcLocalState localState, String siteId,
                                                   ChunkObjectReader objectReader,
                                                   @javax.annotation.Nullable ServerLevel lootPreviewWorld) throws IOException
    {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(localState, "localState");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(objectReader, "objectReader");

        LvcManifest.Site site = manifest.site(siteId);
        LvcLocalState.SitePlacement placement = localState.sites().get(siteId);

        if (placement == null)
        {
            throw new IOException("Missing local placement for LVC site: " + siteId);
        }

        if (site.regions().isEmpty())
        {
            throw new IOException("LVC project has no sub-regions to load as a schematic");
        }

        List<RegionView> regions = createRegionViews(site);
        BlockPos worldOrigin = blockPosFromList(placement.origin());
        AreaSelection selection = createSelection(manifest.name(), placement, regions);
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(selection, "LVC");

        if (schematic == null)
        {
            throw new IOException("Failed to create semantic LVC schematic");
        }

        return new BuildSession(manifest.name(), site, objectReader, schematic, regions, worldOrigin, lootPreviewWorld);
    }

    public static final class BuildSession
    {
        private final String projectName;
        private final ChunkObjectReader objectReader;
        private final LitematicaSchematic schematic;
        private final List<RegionView> regions;
        private final List<Map.Entry<String, String>> chunkRefs;
        private final Map<String, LitematicaBlockStateContainer> containers;
        private final Map<String, Map<BlockPos, CompoundTag>> blockEntityMaps;
        private final Map<String, List<EntityInfo>> entityLists;
        private final Map<LvcChunkCoordinate, List<RegionView>> regionsByChunk;
        private final Map<String, BlockState> blockStateCache = new HashMap<>();
        private final BlockPos worldOrigin;
        @javax.annotation.Nullable private final ServerLevel lootPreviewWorld;
        private int nextChunkIndex;
        private int blockEntityCount;
        private int entityCount;
        private int promotedContainerComponents;
        private int materializedContainerLootTables;
        private int failedContainerLootTables;

        private BuildSession(String projectName, LvcManifest.Site site, ChunkObjectReader objectReader,
                             LitematicaSchematic schematic, List<RegionView> regions, BlockPos worldOrigin,
                             @javax.annotation.Nullable ServerLevel lootPreviewWorld) throws IOException
        {
            this.projectName = Objects.requireNonNull(projectName, "projectName");
            this.objectReader = Objects.requireNonNull(objectReader, "objectReader");
            this.schematic = Objects.requireNonNull(schematic, "schematic");
            this.regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
            this.chunkRefs = List.copyOf(site.fullHashes().entrySet());
            this.containers = createContainerMap(schematic, this.regions);
            this.blockEntityMaps = createBlockEntityMap(schematic, this.regions);
            this.entityLists = createEntityListMap(schematic, this.regions);
            this.regionsByChunk = createRegionsByChunk(this.regions);
            this.worldOrigin = Objects.requireNonNull(worldOrigin, "worldOrigin");
            this.lootPreviewWorld = lootPreviewWorld;
        }

        public boolean isComplete()
        {
            return this.nextChunkIndex >= this.chunkRefs.size();
        }

        public int processedChunks()
        {
            return this.nextChunkIndex;
        }

        public int totalChunks()
        {
            return this.chunkRefs.size();
        }

        public void processNextChunk() throws IOException
        {
            if (this.isComplete())
            {
                return;
            }

            Map.Entry<String, String> entry = this.chunkRefs.get(this.nextChunkIndex);
            LvcChunkCoordinate coordinate = LvcChunkCoordinate.parse(entry.getKey());
            LvcChunk chunk = LvcChunkCodec.decode(this.objectReader.readObject(entry.getValue()));
            List<RegionView> chunkRegions = this.regionsByChunk.getOrDefault(coordinate, List.of());
            BuildStats stats = populateSchematicChunk(chunkRegions, this.containers, this.blockEntityMaps, this.entityLists,
                    this.blockStateCache, this.worldOrigin, this.lootPreviewWorld, coordinate, ChunkView.from(chunk));
            this.blockEntityCount += stats.blockEntities();
            this.entityCount += stats.entities();
            this.promotedContainerComponents += stats.promotedContainerComponents();
            this.materializedContainerLootTables += stats.materializedContainerLootTables();
            this.failedContainerLootTables += stats.failedContainerLootTables();
            this.nextChunkIndex++;
        }

        public LitematicaSchematic result()
        {
            if (!this.isComplete())
            {
                throw new IllegalStateException("LVC schematic build session is not complete");
            }

            this.schematic.getMetadata().setName(this.projectName);
            this.schematic.getMetadata().setTimeCreated(System.currentTimeMillis());
            this.schematic.getMetadata().setTimeModifiedToNow();
            LvcDiagnostics.debug("LvcSemanticSchematicBuilder: built schematic project='{}' regions={} chunks={} cachedBlockStates={} blockEntities={} entities={} promotedContainerComponents={} materializedContainerLootTables={} failedContainerLootTables={} lootPreviewWorld={}",
                    this.projectName, this.regions.size(), this.chunkRefs.size(), this.blockStateCache.size(),
                    this.blockEntityCount, this.entityCount, this.promotedContainerComponents,
                    this.materializedContainerLootTables, this.failedContainerLootTables, this.lootPreviewWorld != null);
            return this.schematic;
        }
    }

    private static List<RegionView> createRegionViews(LvcManifest.Site site)
    {
        List<RegionView> regions = new ArrayList<>(site.regions().size());
        Set<String> usedNames = new HashSet<>();

        for (LvcManifest.Region region : site.regions())
        {
            regions.add(new RegionView(
                    region,
                    uniqueRegionName(region, usedNames),
                    LvcIntPosition.fromList(region.min()),
                    LvcIntPosition.fromList(region.size())
            ));
        }

        return List.copyOf(regions);
    }

    private static Map<LvcChunkCoordinate, List<RegionView>> createRegionsByChunk(List<RegionView> regions)
    {
        Map<LvcChunkCoordinate, List<RegionView>> byChunk = new HashMap<>();
        int chunkSize = LvcChunk.DEFAULT_SIZE;

        for (RegionView region : regions)
        {
            int minChunkX = Math.floorDiv(region.min().x(), chunkSize);
            int minChunkY = Math.floorDiv(region.min().y(), chunkSize);
            int minChunkZ = Math.floorDiv(region.min().z(), chunkSize);
            int maxChunkX = Math.floorDiv(region.min().x() + region.size().x() - 1, chunkSize);
            int maxChunkY = Math.floorDiv(region.min().y() + region.size().y() - 1, chunkSize);
            int maxChunkZ = Math.floorDiv(region.min().z() + region.size().z() - 1, chunkSize);

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
            {
                for (int chunkY = minChunkY; chunkY <= maxChunkY; chunkY++)
                {
                    for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
                    {
                        LvcChunkCoordinate coordinate = new LvcChunkCoordinate(chunkX, chunkY, chunkZ);
                        byChunk.computeIfAbsent(coordinate, ignored -> new ArrayList<>()).add(region);
                    }
                }
            }
        }

        Map<LvcChunkCoordinate, List<RegionView>> immutable = new HashMap<>();

        for (Map.Entry<LvcChunkCoordinate, List<RegionView>> entry : byChunk.entrySet())
        {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return Map.copyOf(immutable);
    }

    private static String uniqueRegionName(LvcManifest.Region region, Set<String> usedNames)
    {
        String base = region.name() == null || region.name().isBlank() ? region.id() : region.name().trim();
        String candidate = base;
        int suffix = 2;

        while (!usedNames.add(candidate))
        {
            candidate = base + " (" + region.id() + (suffix == 2 ? "" : "-" + suffix) + ")";
            suffix++;
        }

        return candidate;
    }

    private static AreaSelection createSelection(String projectName, LvcLocalState.SitePlacement placement, List<RegionView> regions)
    {
        BlockPos origin = blockPosFromList(placement.origin());
        JsonObject selection = new JsonObject();
        JsonArray boxes = new JsonArray();

        selection.add("name", new JsonPrimitive(projectName));
        selection.add("origin", blockPosToArray(origin));

        for (RegionView region : regions)
        {
            BlockPos min = origin.offset(blockPosFromIntPosition(region.min()));
            BlockPos size = blockPosFromIntPosition(region.size());
            BlockPos max = min.offset(size).offset(-1, -1, -1);
            JsonObject box = new JsonObject();

            box.add("name", new JsonPrimitive(region.schematicName()));
            box.add("pos1", blockPosToArray(min));
            box.add("pos2", blockPosToArray(max));
            boxes.add(box);
        }

        if (!regions.isEmpty())
        {
            selection.add("current", new JsonPrimitive(regions.get(0).schematicName()));
        }

        selection.add("boxes", boxes);
        return AreaSelection.fromJson(selection);
    }

    private static Map<LvcChunkCoordinate, ChunkView> readChunks(LvcManifest.Site site, ChunkObjectReader objectReader) throws IOException
    {
        Map<LvcChunkCoordinate, ChunkView> chunks = new HashMap<>();

        for (Map.Entry<String, String> entry : site.fullHashes().entrySet())
        {
            LvcChunkCoordinate coordinate = LvcChunkCoordinate.parse(entry.getKey());
            LvcChunk chunk = LvcChunkCodec.decode(objectReader.readObject(entry.getValue()));
            chunks.put(coordinate, ChunkView.from(chunk));
        }

        return Map.copyOf(chunks);
    }

    @FunctionalInterface
    public interface ChunkObjectReader
    {
        byte[] readObject(String objectId) throws IOException;
    }

    private static Map<String, LitematicaBlockStateContainer> createContainerMap(LitematicaSchematic schematic,
                                                                                 List<RegionView> regions) throws IOException
    {
        Map<String, LitematicaBlockStateContainer> containers = new HashMap<>();

        for (RegionView region : regions)
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region.schematicName());

            if (container == null)
            {
                throw new IOException("Missing Litematica container for LVC region: " + region.schematicName());
            }

            containers.put(region.schematicName(), container);
        }

        return Map.copyOf(containers);
    }

    private static Map<String, Map<BlockPos, CompoundTag>> createBlockEntityMap(LitematicaSchematic schematic,
                                                                                List<RegionView> regions) throws IOException
    {
        Map<String, Map<BlockPos, CompoundTag>> blockEntityMaps = new HashMap<>();

        for (RegionView region : regions)
        {
            Map<BlockPos, CompoundTag> blockEntities = schematic.getBlockEntityMapForRegion(region.schematicName());

            if (blockEntities == null)
            {
                throw new IOException("Missing Litematica block entity map for LVC region: " + region.schematicName());
            }

            blockEntityMaps.put(region.schematicName(), blockEntities);
        }

        return Map.copyOf(blockEntityMaps);
    }

    private static Map<String, List<EntityInfo>> createEntityListMap(LitematicaSchematic schematic,
                                                                     List<RegionView> regions) throws IOException
    {
        Map<String, List<EntityInfo>> entityLists = new HashMap<>();

        for (RegionView region : regions)
        {
            List<EntityInfo> entities = schematic.getEntityListForRegion(region.schematicName());

            if (entities == null)
            {
                throw new IOException("Missing Litematica entity list for LVC region: " + region.schematicName());
            }

            entityLists.put(region.schematicName(), entities);
        }

        return Map.copyOf(entityLists);
    }

    private static BuildStats populateSchematicChunk(List<RegionView> regions,
                                               Map<String, LitematicaBlockStateContainer> containers,
                                               Map<String, Map<BlockPos, CompoundTag>> blockEntityMaps,
                                               Map<String, List<EntityInfo>> entityLists,
                                               Map<String, BlockState> blockStateCache,
                                               BlockPos worldOrigin, @javax.annotation.Nullable ServerLevel lootPreviewWorld,
                                               LvcChunkCoordinate coordinate, ChunkView chunk) throws IOException
    {
        int blockEntityCount = 0;
        int entityCount = 0;
        int promotedContainerComponents = 0;
        int materializedContainerLootTables = 0;
        int failedContainerLootTables = 0;

        for (Map.Entry<Integer, String> entry : chunk.blockStatesByIndex().entrySet())
        {
            LvcIntPosition projectPos = LvcCapturePlanner.projectPosition(coordinate, entry.getKey(),
                    chunk.sizeX(), chunk.sizeY(), chunk.sizeZ());
            BlockState state = blockStateCache.computeIfAbsent(entry.getValue(), value ->
            {
                try
                {
                    return parseBlockState(value);
                }
                catch (IOException e)
                {
                    throw new IllegalArgumentException(e);
                }
            });
            byte[] blockEntityNbt = chunk.blockEntitiesByIndex().get(entry.getKey());

            for (RegionView region : regions)
            {
                if (!region.contains(projectPos))
                {
                    continue;
                }

                int x = projectPos.x() - region.min().x();
                int y = projectPos.y() - region.min().y();
                int z = projectPos.z() - region.min().z();

                containers.get(region.schematicName()).set(x, y, z, state);

                if (blockEntityNbt != null)
                {
                    CompoundTag blockEntity = decodeBlockEntity(blockEntityNbt);
                    promotedContainerComponents += promoteContainerComponentItems(blockEntity) ? 1 : 0;

                    if (lootPreviewWorld != null)
                    {
                        LvcLootTablePreview.Result previewResult = LvcLootTablePreview.materializeContainerLoot(
                                blockEntity, lootPreviewWorld, worldOrigin.offset(blockPosFromIntPosition(projectPos)));

                        if (previewResult == LvcLootTablePreview.Result.MATERIALIZED)
                        {
                            materializedContainerLootTables++;
                        }
                        else if (previewResult == LvcLootTablePreview.Result.FAILED)
                        {
                            failedContainerLootTables++;
                        }
                    }

                    blockEntity.putInt("x", x);
                    blockEntity.putInt("y", y);
                    blockEntity.putInt("z", z);
                    blockEntityMaps.get(region.schematicName()).put(new BlockPos(x, y, z), blockEntity);
                    blockEntityCount++;
                }
            }
        }

        for (LvcChunk.EntityRecord entity : chunk.entities())
        {
            Vec3 projectPos = LvcEntityNbt.position(entity.canonicalNbt());

            for (RegionView region : regions)
            {
                if (!region.contains(projectPos))
                {
                    continue;
                }

                CompoundTag entityNbt = LvcEntityNbt.materializeForRegion(entity.canonicalNbt(), region.min());
                Vec3 regionPos = LvcEntityNbt.position(entityNbt);
                entityLists.get(region.schematicName()).add(new EntityInfo(regionPos, entityNbt));
                entityCount++;
                break;
            }
        }

        return new BuildStats(blockEntityCount, entityCount, promotedContainerComponents, materializedContainerLootTables, failedContainerLootTables);
    }

    private static void populateSchematic(LitematicaSchematic schematic, List<RegionView> regions,
                                          Map<LvcChunkCoordinate, ChunkView> chunks) throws IOException
    {
        for (RegionView region : regions)
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region.schematicName());
            Map<BlockPos, CompoundTag> blockEntities = schematic.getBlockEntityMapForRegion(region.schematicName());

            if (container == null || blockEntities == null)
            {
                throw new IOException("Missing Litematica container for LVC region: " + region.schematicName());
            }

            LvcIntPosition min = region.min();
            LvcIntPosition size = region.size();

            for (int y = 0; y < size.y(); y++)
            {
                for (int z = 0; z < size.z(); z++)
                {
                    for (int x = 0; x < size.x(); x++)
                    {
                        LvcIntPosition projectPos = new LvcIntPosition(min.x() + x, min.y() + y, min.z() + z);
                        ChunkBlock chunkBlock = resolveChunkBlock(projectPos, chunks);
                        BlockState state = parseBlockState(chunkBlock.blockState());

                        container.set(x, y, z, state);

                        if (chunkBlock.blockEntityNbt() != null)
                        {
                            CompoundTag blockEntity = decodeBlockEntity(chunkBlock.blockEntityNbt());
                            promoteContainerComponentItems(blockEntity);
                            blockEntity.putInt("x", x);
                            blockEntity.putInt("y", y);
                            blockEntity.putInt("z", z);
                            blockEntities.put(new BlockPos(x, y, z), blockEntity);
                        }
                    }
                }
            }
        }
    }

    private static ChunkBlock resolveChunkBlock(LvcIntPosition projectPos, Map<LvcChunkCoordinate, ChunkView> chunks) throws IOException
    {
        int chunkSize = LvcChunk.DEFAULT_SIZE;
        LvcChunkCoordinate coordinate = new LvcChunkCoordinate(
                Math.floorDiv(projectPos.x(), chunkSize),
                Math.floorDiv(projectPos.y(), chunkSize),
                Math.floorDiv(projectPos.z(), chunkSize)
        );
        ChunkView chunk = chunks.get(coordinate);

        if (chunk == null)
        {
            throw new IOException("LVC manifest is missing full hash for tracked subchunk " + coordinate.key());
        }

        int localX = Math.floorMod(projectPos.x(), chunk.sizeX());
        int localY = Math.floorMod(projectPos.y(), chunk.sizeY());
        int localZ = Math.floorMod(projectPos.z(), chunk.sizeZ());
        int localIndex = LvcCapturePlanner.index(localX, localY, localZ, chunk.sizeX(), chunk.sizeY());
        String blockState = chunk.blockStatesByIndex().get(localIndex);

        if (blockState == null)
        {
            throw new IOException("LVC chunk " + coordinate.key() + " does not contain tracked block " + projectPos);
        }

        return new ChunkBlock(blockState, chunk.blockEntitiesByIndex().get(localIndex));
    }

    private static BlockState parseBlockState(String blockState) throws IOException
    {
        return BlockUtils.getBlockStateFromString(blockState, LitematicaSchematic.MINECRAFT_DATA_VERSION)
                .orElseThrow(() -> new IOException("Invalid LVC block state: " + blockState));
    }

    private static CompoundTag decodeBlockEntity(byte[] canonicalNbt) throws IOException
    {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(canonicalNbt)))
        {
            Tag tag = NbtIo.readAnyTag(input, NbtAccounter.unlimitedHeap());

            if (tag instanceof CompoundTag compoundTag)
            {
                return compoundTag;
            }

            throw new IOException("LVC block entity payload is not a compound tag");
        }
    }

    private static boolean promoteContainerComponentItems(CompoundTag blockEntity)
    {
        if (blockEntity.contains("Items") || !blockEntity.contains("components"))
        {
            return false;
        }

        CompoundTag components = blockEntity.getCompoundOrEmpty("components");
        Tag containerTag = components.get("minecraft:container");

        if (containerTag == null)
        {
            containerTag = components.get("container");
        }

        if (!(containerTag instanceof ListTag containerSlots) || containerSlots.isEmpty())
        {
            return false;
        }

        ListTag items = new ListTag();

        for (int i = 0; i < containerSlots.size(); i++)
        {
            CompoundTag componentSlot = containerSlots.getCompoundOrEmpty(i);
            int slot = componentSlot.getIntOr("slot", -1);
            CompoundTag item = itemStackFromContainerComponentSlot(componentSlot);

            if (slot < 0 || slot > 255 || item == null)
            {
                continue;
            }

            item.putByte("Slot", (byte) slot);
            items.add(item);
        }

        if (items.isEmpty())
        {
            return false;
        }

        blockEntity.put("Items", items);
        return true;
    }

    private static CompoundTag itemStackFromContainerComponentSlot(CompoundTag componentSlot)
    {
        Tag itemTag = componentSlot.get("item");

        if (itemTag instanceof CompoundTag itemCompound)
        {
            return itemCompound.copy();
        }

        String itemId = itemTag != null ? itemTag.asString().orElse("") : "";

        if (itemId.isBlank())
        {
            return null;
        }

        CompoundTag item = new CompoundTag();
        item.putString("id", itemId);
        return item;
    }

    private static JsonArray blockPosToArray(BlockPos pos)
    {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    private static BlockPos blockPosFromList(List<Integer> values)
    {
        if (values == null || values.size() != 3)
        {
            throw new IllegalArgumentException("LVC position must contain three coordinates");
        }

        return new BlockPos(values.get(0), values.get(1), values.get(2));
    }

    private static BlockPos blockPosFromIntPosition(LvcIntPosition position)
    {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private record RegionView(LvcManifest.Region region, String schematicName, LvcIntPosition min, LvcIntPosition size)
    {
        private boolean contains(LvcIntPosition position)
        {
            return position.x() >= this.min.x() && position.x() < this.min.x() + this.size.x() &&
                    position.y() >= this.min.y() && position.y() < this.min.y() + this.size.y() &&
                    position.z() >= this.min.z() && position.z() < this.min.z() + this.size.z();
        }

        private boolean contains(Vec3 position)
        {
            return position.x >= this.min.x() && position.x < this.min.x() + this.size.x() &&
                    position.y >= this.min.y() && position.y < this.min.y() + this.size.y() &&
                    position.z >= this.min.z() && position.z < this.min.z() + this.size.z();
        }
    }

    private record ChunkBlock(String blockState, byte[] blockEntityNbt)
    {
    }

    private record BuildStats(int blockEntities, int entities, int promotedContainerComponents,
                              int materializedContainerLootTables, int failedContainerLootTables)
    {
    }

    private record ChunkView(int sizeX, int sizeY, int sizeZ, Map<Integer, String> blockStatesByIndex,
                             Map<Integer, byte[]> blockEntitiesByIndex, List<LvcChunk.EntityRecord> entities)
    {
        private static ChunkView from(LvcChunk chunk)
        {
            Map<Integer, String> blockStates = new HashMap<>();
            Map<Integer, byte[]> blockEntities = new HashMap<>();
            BitSet mask = chunk.trackedMask();
            int ordinal = 0;

            for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1))
            {
                blockStates.put(index, chunk.blockStateAtTrackedOrdinal(ordinal));
                ordinal++;
            }

            for (LvcChunk.BlockEntityRecord blockEntity : chunk.blockEntities())
            {
                blockEntities.put(blockEntity.index(), blockEntity.canonicalNbt());
            }

            return new ChunkView(chunk.sizeX(), chunk.sizeY(), chunk.sizeZ(), Map.copyOf(blockStates),
                    Map.copyOf(blockEntities), chunk.entities());
        }
    }
}
