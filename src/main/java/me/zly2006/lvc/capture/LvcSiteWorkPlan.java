package me.zly2006.lvc.capture;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;

public final class LvcSiteWorkPlan
{
    private final LvcManifest.Site site;
    private final LvcLocalState.SitePlacement placement;
    private final LvcIntPosition origin;
    private final List<ChunkWork> chunks;
    private final int blockCount;

    private LvcSiteWorkPlan(LvcManifest.Site site, LvcLocalState.SitePlacement placement,
                            LvcIntPosition origin, List<ChunkWork> chunks, int blockCount)
    {
        this.site = site;
        this.placement = placement;
        this.origin = origin;
        this.chunks = List.copyOf(chunks);
        this.blockCount = blockCount;
    }

    public static LvcSiteWorkPlan create(LvcManifest.Site site, LvcLocalState.SitePlacement placement)
    {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(placement, "placement");

        List<ChunkWork> chunks = new ArrayList<>();
        int blockCount = 0;

        for (Map.Entry<LvcChunkCoordinate, BitSet> entry : LvcCapturePlanner.planSite(site).entrySet())
        {
            BitSet mask = (BitSet) entry.getValue().clone();
            chunks.add(new ChunkWork(entry.getKey(), mask));
            blockCount += mask.cardinality();
        }

        return new LvcSiteWorkPlan(site, placement, LvcIntPosition.fromList(placement.origin()), chunks, blockCount);
    }

    public LvcManifest.Site site()
    {
        return this.site;
    }

    public LvcLocalState.SitePlacement placement()
    {
        return this.placement;
    }

    public LvcIntPosition origin()
    {
        return this.origin;
    }

    public List<ChunkWork> chunks()
    {
        return this.chunks;
    }

    public int chunkCount()
    {
        return this.chunks.size();
    }

    public int blockCount()
    {
        return this.blockCount;
    }

    public record ChunkWork(LvcChunkCoordinate coordinate, BitSet mask)
    {
        public ChunkWork
        {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(mask, "mask");
            mask = (BitSet) mask.clone();
        }
    }
}
