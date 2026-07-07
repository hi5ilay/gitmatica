package me.zly2006.lvc.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.model.LvcManifest;

public final class LvcSemanticObjectPruner
{
    private LvcSemanticObjectPruner()
    {
    }

    public static Result pruneChangedObjects(Path repositoryDirectory, LvcManifest previousManifest,
                                             LvcManifest resultingManifest) throws IOException
    {
        return pruneChangedObjects(repositoryDirectory, previousManifest, resultingManifest,
                allSiteIds(previousManifest, resultingManifest));
    }

    public static Result pruneChangedObjects(Path repositoryDirectory, LvcManifest previousManifest,
                                             LvcManifest resultingManifest, String affectedSiteId) throws IOException
    {
        return pruneChangedObjects(repositoryDirectory, previousManifest, resultingManifest, Set.of(affectedSiteId));
    }

    public static Result pruneChangedObjects(Path repositoryDirectory, LvcManifest previousManifest,
                                             LvcManifest resultingManifest, Set<String> affectedSiteIds) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(previousManifest, "previousManifest");
        Objects.requireNonNull(resultingManifest, "resultingManifest");
        Objects.requireNonNull(affectedSiteIds, "affectedSiteIds");

        Set<String> candidates = pruneCandidates(previousManifest, resultingManifest, affectedSiteIds);
        validateIntroducedObjectsExist(repositoryDirectory, previousManifest, resultingManifest, affectedSiteIds);
        Set<String> stillLiveCandidates = stillLiveCandidates(resultingManifest, candidates);
        int deleted = 0;
        int missing = 0;
        long deletedBytes = 0L;

        for (String objectId : candidates)
        {
            if (stillLiveCandidates.contains(objectId))
            {
                continue;
            }

            Path path = LvcChunkStore.objectPath(repositoryDirectory, objectId);

            if (!Files.isRegularFile(path))
            {
                missing++;
                continue;
            }

            long size = Files.size(path);
            Files.delete(path);
            deleteDirectoryIfEmpty(path.getParent());
            deleted++;
            deletedBytes += size;
        }

        Result result = new Result(candidates.size(), stillLiveCandidates.size(), deleted, missing, deletedBytes);
        LvcDiagnostics.debug("LvcSemanticObjectPruner: pruned repo='{}' candidates={} skippedLive={} deleted={} missing={} deletedBytes={}",
                repositoryDirectory, result.candidates(), result.skippedLive(), result.deleted(), result.missing(), result.deletedBytes());
        return result;
    }

    private static Set<String> pruneCandidates(LvcManifest previousManifest, LvcManifest resultingManifest,
                                               Set<String> affectedSiteIds)
    {
        Map<String, LvcManifest.Site> previousSites = sitesById(previousManifest);
        Map<String, LvcManifest.Site> resultingSites = sitesById(resultingManifest);
        Set<String> candidates = new TreeSet<>();

        for (String siteId : affectedSiteIds)
        {
            LvcManifest.Site previousSite = previousSites.get(siteId);

            if (previousSite == null)
            {
                continue;
            }

            LvcManifest.Site resultingSite = resultingSites.get(siteId);
            Map<String, String> resultingRefs = resultingSite != null ? resultingSite.fullHashes() : Map.of();

            for (Map.Entry<String, String> entry : previousSite.fullHashes().entrySet())
            {
                String previousObjectId = entry.getValue();
                String resultingObjectId = resultingRefs.get(entry.getKey());

                if (!previousObjectId.equals(resultingObjectId))
                {
                    candidates.add(previousObjectId);
                }
            }
        }

        return candidates;
    }

    private static void validateIntroducedObjectsExist(Path repositoryDirectory, LvcManifest previousManifest,
                                                       LvcManifest resultingManifest, Set<String> affectedSiteIds)
            throws IOException
    {
        Map<String, LvcManifest.Site> previousSites = sitesById(previousManifest);
        Map<String, LvcManifest.Site> resultingSites = sitesById(resultingManifest);

        for (String siteId : affectedSiteIds)
        {
            LvcManifest.Site resultingSite = resultingSites.get(siteId);

            if (resultingSite == null)
            {
                continue;
            }

            LvcManifest.Site previousSite = previousSites.get(siteId);
            Map<String, String> previousRefs = previousSite != null ? previousSite.fullHashes() : Map.of();

            for (Map.Entry<String, String> entry : resultingSite.fullHashes().entrySet())
            {
                String previousObjectId = previousRefs.get(entry.getKey());
                String resultingObjectId = entry.getValue();

                if (!resultingObjectId.equals(previousObjectId) &&
                    !Files.isRegularFile(LvcChunkStore.objectPath(repositoryDirectory, resultingObjectId)))
                {
                    throw new IOException("Missing LVC object needed by resulting manifest: " + resultingObjectId);
                }
            }
        }
    }

    private static Set<String> stillLiveCandidates(LvcManifest resultingManifest, Set<String> candidates)
    {
        if (candidates.isEmpty())
        {
            return Set.of();
        }

        Set<String> stillLive = new HashSet<>();

        for (LvcManifest.Site site : resultingManifest.sites())
        {
            for (String objectId : site.fullHashes().values())
            {
                if (candidates.contains(objectId))
                {
                    stillLive.add(objectId);
                }
            }
        }

        return stillLive;
    }

    private static Set<String> allSiteIds(LvcManifest previousManifest, LvcManifest resultingManifest)
    {
        Set<String> siteIds = new TreeSet<>();

        for (LvcManifest.Site site : previousManifest.sites())
        {
            siteIds.add(site.id());
        }

        for (LvcManifest.Site site : resultingManifest.sites())
        {
            siteIds.add(site.id());
        }

        return siteIds;
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

    private static void deleteDirectoryIfEmpty(Path directory) throws IOException
    {
        if (directory == null || !Files.isDirectory(directory))
        {
            return;
        }

        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory))
        {
            if (children.iterator().hasNext())
            {
                return;
            }
        }

        Files.delete(directory);
    }

    public record Result(int candidates, int skippedLive, int deleted, int missing, long deletedBytes)
    {
    }
}
