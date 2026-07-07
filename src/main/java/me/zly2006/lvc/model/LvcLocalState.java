package me.zly2006.lvc.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

public record LvcLocalState(
        String format,
        @SerializedName("project_id") UUID projectId,
        @SerializedName("active_site") String activeSite,
        Map<String, SitePlacement> sites)
{
    public static final String FORMAT = "lvc-local-v1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public LvcLocalState
    {
        sites = java.util.Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(sites, "sites")));
    }

    public static LvcLocalState create(UUID projectId, String activeSite, Map<String, SitePlacement> sites)
    {
        return new LvcLocalState(FORMAT, projectId, activeSite, sites).validate();
    }

    public static LvcLocalState fromJson(String json)
    {
        LvcLocalState localState = GSON.fromJson(json, LvcLocalState.class);

        if (localState == null)
        {
            throw new IllegalArgumentException("LVC local state JSON is empty");
        }

        return localState.validate();
    }

    public String toJson()
    {
        return GSON.toJson(this);
    }

    public LvcLocalState validate()
    {
        if (!FORMAT.equals(this.format))
        {
            throw new IllegalArgumentException("Invalid LVC local state format: " + this.format);
        }

        if (this.projectId == null)
        {
            throw new IllegalArgumentException("LVC local state project_id must not be null");
        }

        if (this.activeSite == null || this.activeSite.isBlank())
        {
            throw new IllegalArgumentException("LVC local state active_site must not be blank");
        }

        if (!this.sites.containsKey(this.activeSite))
        {
            throw new IllegalArgumentException("LVC local state active_site must exist in sites");
        }

        for (Map.Entry<String, SitePlacement> entry : this.sites.entrySet())
        {
            if (entry.getKey() == null || entry.getKey().isBlank())
            {
                throw new IllegalArgumentException("LVC local state site id must not be blank");
            }

            entry.getValue().validate();
        }

        return this;
    }

    public record SitePlacement(String dimension, List<Integer> origin, @SerializedName("world_hint") String worldHint)
    {
        public SitePlacement
        {
            origin = List.copyOf(Objects.requireNonNull(origin, "origin"));
        }

        private void validate()
        {
            if (this.dimension == null || this.dimension.isBlank())
            {
                throw new IllegalArgumentException("LVC local site dimension must not be blank");
            }

            if (this.origin.size() != 3)
            {
                throw new IllegalArgumentException("LVC local site origin must have three coordinates");
            }

            for (Integer value : this.origin)
            {
                if (value == null)
                {
                    throw new IllegalArgumentException("LVC local site origin contains null coordinate");
                }
            }
        }
    }
}
