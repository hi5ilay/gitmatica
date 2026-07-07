package me.zly2006.lvc.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import me.zly2006.lvc.storage.LvcHashIndexCodec;
import me.zly2006.lvc.storage.LvcChunkStore;

public record LvcManifest(
        String format,
        @SerializedName("project_id") UUID projectId,
        String name,
        Content content,
        List<Site> sites)
{
    public static final String FORMAT = "lvc-manifest-v1";
    public static final String CHUNK_FORMAT = "lvcchunk-deflate-v1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public LvcManifest
    {
        content = Content.defaultContent();
        sites = List.copyOf(Objects.requireNonNull(sites, "sites"));
    }

    public static LvcManifest create(String name, List<Site> sites)
    {
        return new LvcManifest(FORMAT, UUID.randomUUID(), name, Content.defaultContent(), sites).validate();
    }

    public static LvcManifest fromJson(String json)
    {
        ManifestJson manifest = GSON.fromJson(json, ManifestJson.class);

        if (manifest == null)
        {
            throw new IllegalArgumentException("LVC manifest JSON is empty");
        }

        List<Site> sites = new ArrayList<>();

        for (SiteJson site : requireNotNull(manifest.sites(), "sites"))
        {
            sites.add(new Site(site.id(), site.name(), site.dimension(), site.regions(), site.hashIndex(), Map.of(), Map.of()));
        }

        return new LvcManifest(manifest.format(), manifest.projectId(), manifest.name(), Content.defaultContent(), sites).validate();
    }

    public String toJson()
    {
        List<SiteJson> jsonSites = new ArrayList<>(this.sites.size());

        for (Site site : this.sites)
        {
            jsonSites.add(new SiteJson(site.id(), site.name(), site.dimension(), site.regions(), site.hashIndex()));
        }

        return GSON.toJson(new ManifestJson(this.format, this.projectId, this.name, jsonSites));
    }

    public static boolean hasSerializedContent(String json)
    {
        JsonElement element = JsonParser.parseString(json);
        return element != null && element.isJsonObject() && element.getAsJsonObject().has("content");
    }

    public LvcManifest validate()
    {
        requireEquals(FORMAT, this.format, "manifest format");
        requireNotNull(this.projectId, "project_id");
        requireNotBlank(this.name, "project name");
        requireNotNull(this.content, "content").validate();
        requireNotNull(this.sites, "sites");

        Set<String> siteIds = new HashSet<>();

        for (Site site : this.sites)
        {
            site.validate();

            if (!siteIds.add(site.id()))
            {
                throw new IllegalArgumentException("Duplicate LVC site id: " + site.id());
            }
        }

        return this;
    }

    public Site site(String siteId)
    {
        for (Site site : this.sites)
        {
            if (site.id().equals(siteId))
            {
                return site;
            }
        }

        throw new IllegalArgumentException("Unknown LVC site id: " + siteId);
    }

    public LvcManifest withSiteFullHashes(String siteId, Map<String, String> fullHashes)
    {
        return this.withSiteHashRefs(siteId, fullHashes, fullHashes);
    }

    public LvcManifest withSiteHashRefs(String siteId, Map<String, String> fullHashes, Map<String, String> trackedHashes)
    {
        List<Site> updatedSites = new java.util.ArrayList<>(this.sites.size());
        boolean replaced = false;

        for (Site site : this.sites)
        {
            if (site.id().equals(siteId))
            {
                updatedSites.add(site.withHashRefs(fullHashes, trackedHashes));
                replaced = true;
            }
            else
            {
                updatedSites.add(site);
            }
        }

        if (!replaced)
        {
            throw new IllegalArgumentException("Unknown LVC site id: " + siteId);
        }

        return new LvcManifest(this.format, this.projectId, this.name, this.content, updatedSites).validate();
    }

    public LvcManifest withSite(String siteId, Site updatedSite)
    {
        requireNotBlank(siteId, "site id");
        requireNotNull(updatedSite, "updated site");

        if (!siteId.equals(updatedSite.id()))
        {
            throw new IllegalArgumentException("Updated LVC site id does not match target site id: " + siteId);
        }

        List<Site> updatedSites = new java.util.ArrayList<>(this.sites.size());
        boolean replaced = false;

        for (Site site : this.sites)
        {
            if (site.id().equals(siteId))
            {
                updatedSites.add(updatedSite);
                replaced = true;
            }
            else
            {
                updatedSites.add(site);
            }
        }

        if (!replaced)
        {
            throw new IllegalArgumentException("Unknown LVC site id: " + siteId);
        }

        return new LvcManifest(this.format, this.projectId, this.name, this.content, updatedSites).validate();
    }

    private static void requireEquals(String expected, String actual, String label)
    {
        if (!Objects.equals(expected, actual))
        {
            throw new IllegalArgumentException("Invalid LVC " + label + ": " + actual);
        }
    }

    private static <T> T requireNotNull(T value, String label)
    {
        if (value == null)
        {
            throw new IllegalArgumentException("LVC " + label + " must not be null");
        }

        return value;
    }

    private static void requireNotBlank(String value, String label)
    {
        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException("LVC " + label + " must not be blank");
        }
    }

    private static void validateVector(List<Integer> vector, String label, boolean positive)
    {
        if (vector == null || vector.size() != 3)
        {
            throw new IllegalArgumentException("LVC " + label + " must have three coordinates");
        }

        for (Integer value : vector)
        {
            if (value == null || (positive && value <= 0))
            {
                throw new IllegalArgumentException("LVC " + label + " contains an invalid coordinate");
            }
        }
    }

    public record Content(
            @SerializedName("chunk_format") String chunkFormat,
            @SerializedName("hash_index_format") String hashIndexFormat,
            String hash,
            @SerializedName("chunk_size") List<Integer> chunkSize)
    {
        public Content
        {
            chunkSize = List.copyOf(Objects.requireNonNull(chunkSize, "chunkSize"));
        }

        public static Content defaultContent()
        {
            return new Content(CHUNK_FORMAT, LvcHashIndexCodec.FORMAT, LvcChunkStore.HASH_ALGORITHM, List.of(LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE));
        }

        private void validate()
        {
            requireEquals(CHUNK_FORMAT, this.chunkFormat, "chunk format");
            requireEquals(LvcHashIndexCodec.FORMAT, this.hashIndexFormat, "hash index format");
            requireEquals(LvcChunkStore.HASH_ALGORITHM, this.hash, "hash");
            validateVector(this.chunkSize, "chunk size", true);
        }
    }

    public record Site(
            String id,
            String name,
            String dimension,
            List<Region> regions,
            @SerializedName("hash_index") String hashIndex,
            Map<String, String> fullHashes,
            Map<String, String> trackedHashes)
    {
        public Site
        {
            hashIndex = normalizeHashIndexPath(hashIndex != null ? hashIndex : LvcHashIndexCodec.defaultIndexPath(id));
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
            fullHashes = java.util.Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(fullHashes, "fullHashes")));
            trackedHashes = trackedHashes == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(trackedHashes));
        }

        public Site(String id, String name, String dimension, List<Region> regions, Map<String, String> fullHashes)
        {
            this(id, name, dimension, regions, LvcHashIndexCodec.defaultIndexPath(id), fullHashes, fullHashes);
        }

        public Site(String id, String name, String dimension, List<Region> regions, String hashIndex, Map<String, String> fullHashes)
        {
            this(id, name, dimension, regions, hashIndex, fullHashes, fullHashes);
        }

        public Site withFullHashes(Map<String, String> fullHashes)
        {
            return this.withHashRefs(fullHashes, fullHashes);
        }

        public Site withHashRefs(Map<String, String> fullHashes, Map<String, String> trackedHashes)
        {
            return new Site(this.id, this.name, this.dimension, this.regions, this.hashIndex, fullHashes, trackedHashes);
        }

        public Site withName(String name)
        {
            return new Site(this.id, name, this.dimension, this.regions, this.hashIndex, this.fullHashes, this.trackedHashes);
        }

        public Site withRegions(List<Region> regions)
        {
            return new Site(this.id, this.name, this.dimension, regions, this.hashIndex, this.fullHashes, this.trackedHashes);
        }

        public Map<String, String> trackedHashesForComparison()
        {
            return this.trackedHashes.isEmpty() ? this.fullHashes : this.trackedHashes;
        }

        private void validate()
        {
            requireNotBlank(this.id, "site id");
            requireNotBlank(this.name, "site name");
            requireNotBlank(this.dimension, "site dimension");
            normalizeHashIndexPath(this.hashIndex);

            Set<String> regionIds = new HashSet<>();

            for (Region region : this.regions)
            {
                region.validate();

                if (!regionIds.add(region.id()))
                {
                    throw new IllegalArgumentException("Duplicate LVC region id in site " + this.id + ": " + region.id());
                }
            }

            for (Map.Entry<String, String> entry : this.fullHashes.entrySet())
            {
                LvcChunkCoordinate.parse(entry.getKey());

                if (entry.getValue() == null || !entry.getValue().startsWith(LvcChunkStore.HASH_ALGORITHM + ":"))
                {
                    throw new IllegalArgumentException("Invalid LVC full hash for chunk " + entry.getKey());
                }
            }

            for (Map.Entry<String, String> entry : this.trackedHashes.entrySet())
            {
                LvcChunkCoordinate.parse(entry.getKey());

                if (entry.getValue() == null || !entry.getValue().startsWith(LvcChunkStore.HASH_ALGORITHM + ":"))
                {
                    throw new IllegalArgumentException("Invalid LVC tracked hash for chunk " + entry.getKey());
                }
            }

            if (!this.trackedHashes.isEmpty() && !this.trackedHashes.keySet().equals(this.fullHashes.keySet()))
            {
                throw new IllegalArgumentException("LVC tracked hashes must match full hash keys for site " + this.id);
            }
        }
    }

    public record Region(String id, String name, List<Integer> min, List<Integer> size)
    {
        public Region
        {
            min = List.copyOf(Objects.requireNonNull(min, "min"));
            size = List.copyOf(Objects.requireNonNull(size, "size"));
        }

        private void validate()
        {
            requireNotBlank(this.id, "region id");
            requireNotBlank(this.name, "region name");
            validateVector(this.min, "region min", false);
            validateVector(this.size, "region size", true);
        }
    }

    private static String normalizeHashIndexPath(String hashIndex)
    {
        requireNotBlank(hashIndex, "site hash_index");
        Path path = Path.of(hashIndex).normalize();

        if (path.isAbsolute() || path.startsWith("..") || path.getNameCount() == 0)
        {
            throw new IllegalArgumentException("LVC site hash_index must be a relative path inside the repository: " + hashIndex);
        }

        String normalized = path.toString().replace('\\', '/');

        if (!normalized.endsWith(LvcHashIndexCodec.EXTENSION))
        {
            throw new IllegalArgumentException("LVC site hash_index must end with " + LvcHashIndexCodec.EXTENSION + ": " + hashIndex);
        }

        return normalized;
    }

    private record ManifestJson(String format, @SerializedName("project_id") UUID projectId, String name,
                                List<SiteJson> sites)
    {
    }

    private record SiteJson(String id, String name, String dimension, List<Region> regions,
                            @SerializedName("hash_index") String hashIndex)
    {
    }
}
