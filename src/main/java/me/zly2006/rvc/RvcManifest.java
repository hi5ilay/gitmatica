package me.zly2006.rvc;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

public record RvcManifest(
        String format,
        @SerializedName("project_id") UUID projectId,
        String name,
        Content content,
        List<Site> sites)
{
    public static final String FORMAT = "rvc-manifest-v1";
    public static final String CHUNK_FORMAT = "rvcchunk-v1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public RvcManifest
    {
        sites = List.copyOf(Objects.requireNonNull(sites, "sites"));
    }

    public static RvcManifest create(String name, List<Site> sites)
    {
        return new RvcManifest(FORMAT, UUID.randomUUID(), name, Content.defaultContent(), sites).validate();
    }

    public static RvcManifest fromJson(String json)
    {
        RvcManifest manifest = GSON.fromJson(json, RvcManifest.class);

        if (manifest == null)
        {
            throw new IllegalArgumentException("RVC manifest JSON is empty");
        }

        return manifest.validate();
    }

    public String toJson()
    {
        return GSON.toJson(this);
    }

    public RvcManifest validate()
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
                throw new IllegalArgumentException("Duplicate RVC site id: " + site.id());
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

        throw new IllegalArgumentException("Unknown RVC site id: " + siteId);
    }

    public RvcManifest withSiteChunks(String siteId, Map<String, String> chunks)
    {
        List<Site> updatedSites = new java.util.ArrayList<>(this.sites.size());
        boolean replaced = false;

        for (Site site : this.sites)
        {
            if (site.id().equals(siteId))
            {
                updatedSites.add(site.withChunks(chunks));
                replaced = true;
            }
            else
            {
                updatedSites.add(site);
            }
        }

        if (!replaced)
        {
            throw new IllegalArgumentException("Unknown RVC site id: " + siteId);
        }

        return new RvcManifest(this.format, this.projectId, this.name, this.content, updatedSites).validate();
    }

    public RvcManifest withSite(String siteId, Site updatedSite)
    {
        requireNotBlank(siteId, "site id");
        requireNotNull(updatedSite, "updated site");

        if (!siteId.equals(updatedSite.id()))
        {
            throw new IllegalArgumentException("Updated RVC site id does not match target site id: " + siteId);
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
            throw new IllegalArgumentException("Unknown RVC site id: " + siteId);
        }

        return new RvcManifest(this.format, this.projectId, this.name, this.content, updatedSites).validate();
    }

    private static void requireEquals(String expected, String actual, String label)
    {
        if (!Objects.equals(expected, actual))
        {
            throw new IllegalArgumentException("Invalid RVC " + label + ": " + actual);
        }
    }

    private static <T> T requireNotNull(T value, String label)
    {
        if (value == null)
        {
            throw new IllegalArgumentException("RVC " + label + " must not be null");
        }

        return value;
    }

    private static void requireNotBlank(String value, String label)
    {
        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException("RVC " + label + " must not be blank");
        }
    }

    private static void validateVector(List<Integer> vector, String label, boolean positive)
    {
        if (vector == null || vector.size() != 3)
        {
            throw new IllegalArgumentException("RVC " + label + " must have three coordinates");
        }

        for (Integer value : vector)
        {
            if (value == null || (positive && value <= 0))
            {
                throw new IllegalArgumentException("RVC " + label + " contains an invalid coordinate");
            }
        }
    }

    public record Content(
            @SerializedName("chunk_format") String chunkFormat,
            String hash,
            @SerializedName("chunk_size") List<Integer> chunkSize)
    {
        public Content
        {
            chunkSize = List.copyOf(Objects.requireNonNull(chunkSize, "chunkSize"));
        }

        public static Content defaultContent()
        {
            return new Content(CHUNK_FORMAT, RvcChunkStore.HASH_ALGORITHM, List.of(RvcChunk.DEFAULT_SIZE, RvcChunk.DEFAULT_SIZE, RvcChunk.DEFAULT_SIZE));
        }

        private void validate()
        {
            requireEquals(CHUNK_FORMAT, this.chunkFormat, "chunk format");
            requireEquals(RvcChunkStore.HASH_ALGORITHM, this.hash, "hash");
            validateVector(this.chunkSize, "chunk size", true);
        }
    }

    public record Site(String id, String name, String dimension, List<Region> regions, Map<String, String> chunks)
    {
        public Site
        {
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
            chunks = java.util.Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(chunks, "chunks")));
        }

        public Site withChunks(Map<String, String> chunks)
        {
            return new Site(this.id, this.name, this.dimension, this.regions, chunks);
        }

        public Site withName(String name)
        {
            return new Site(this.id, name, this.dimension, this.regions, this.chunks);
        }

        public Site withRegions(List<Region> regions)
        {
            return new Site(this.id, this.name, this.dimension, regions, this.chunks);
        }

        private void validate()
        {
            requireNotBlank(this.id, "site id");
            requireNotBlank(this.name, "site name");
            requireNotBlank(this.dimension, "site dimension");

            Set<String> regionIds = new HashSet<>();
            Set<String> regionNames = new HashSet<>();

            for (Region region : this.regions)
            {
                region.validate();

                if (!regionIds.add(region.id()))
                {
                    throw new IllegalArgumentException("Duplicate RVC region id in site " + this.id + ": " + region.id());
                }

                String normalizedName = region.name().trim().toLowerCase(Locale.ROOT);

                if (!regionNames.add(normalizedName))
                {
                    throw new IllegalArgumentException("Duplicate RVC region name in site " + this.id + ": " + region.name().trim());
                }
            }

            for (Map.Entry<String, String> entry : this.chunks.entrySet())
            {
                RvcChunkCoordinate.parse(entry.getKey());

                if (entry.getValue() == null || !entry.getValue().startsWith(RvcChunkStore.HASH_ALGORITHM + ":"))
                {
                    throw new IllegalArgumentException("Invalid RVC chunk object id for chunk " + entry.getKey());
                }
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
}
