package me.niicide.lvc.diff3d;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.niicide.lvc.capture.LvcCapturePlanner;
import me.niicide.lvc.git.LvcProjectGitOps;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcChunkCoordinate;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.semantic.LvcTrackedBlockCursor;
import me.niicide.lvc.storage.LvcChunkCodec;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcSemanticRepository;

public final class LvcDiff3DComputer
{
    private LvcDiff3DComputer()
    {
    }

    public static Result compute(Path repositoryDirectory, String commitAId, String commitBId) throws Exception
    {
        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repo = git.getRepository();
            RevCommit commitA = LvcProjectGitOps.resolveCommit(repo, revWalk, commitAId);
            RevCommit commitB = LvcProjectGitOps.resolveCommit(repo, revWalk, commitBId);

            LvcManifest manifestA = LvcSemanticRepository.readCommitManifest(repo, commitA);
            LvcManifest manifestB = LvcSemanticRepository.readCommitManifest(repo, commitB);

            String siteId = manifestA.sites().iterator().next().id();
            LvcManifest.Site siteA = manifestA.site(siteId);
            LvcManifest.Site siteB = manifestB.site(siteId);

            Map<String, String> hashesA = siteA.fullHashes();
            Map<String, String> hashesB = siteB.fullHashes();

            Set<String> allChunkKeys = new HashSet<>(hashesA.keySet());
            allChunkKeys.addAll(hashesB.keySet());

            List<LvcDiff3DBlock> blocks = new ArrayList<>();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            for (String chunkKey : allChunkKeys)
            {
                String objectA = hashesA.get(chunkKey);
                String objectB = hashesB.get(chunkKey);
                LvcChunk chunkA = objectA != null ? readChunk(repo, commitA, objectA) : null;
                LvcChunk chunkB = objectB != null ? readChunk(repo, commitB, objectB) : null;
                LvcChunkCoordinate coord = LvcChunkCoordinate.parse(chunkKey);

                // Build maskIndex -> state maps for both commits
                Map<Integer, String> statesA = buildStateMap(chunkA);
                Map<Integer, String> statesB = buildStateMap(chunkB);

                Set<Integer> allIndices = new HashSet<>(statesA.keySet());
                allIndices.addAll(statesB.keySet());

                int sizeX = chunkA != null ? chunkA.sizeX() : chunkB.sizeX();
                int sizeY = chunkA != null ? chunkA.sizeY() : chunkB.sizeY();
                int sizeZ = chunkA != null ? chunkA.sizeZ() : chunkB.sizeZ();

                for (int maskIndex : allIndices)
                {
                    String stateA = statesA.get(maskIndex);
                    String stateB = statesB.get(maskIndex);

                    LvcDiff3DBlock.Status status;
                    String blockState;

                    if (stateA == null)
                    {
                        status = LvcDiff3DBlock.Status.ADDED;
                        blockState = stateB;
                    }
                    else if (stateB == null)
                    {
                        status = LvcDiff3DBlock.Status.REMOVED;
                        blockState = stateA;
                    }
                    else if (stateA.equals(stateB))
                    {
                        status = LvcDiff3DBlock.Status.UNCHANGED;
                        blockState = stateA;
                    }
                    else
                    {
                        status = LvcDiff3DBlock.Status.CHANGED;
                        blockState = stateB;
                    }

                    LvcIntPosition pos = LvcCapturePlanner.projectPosition(coord, maskIndex, sizeX, sizeY, sizeZ);
                    blocks.add(new LvcDiff3DBlock(pos.x(), pos.y(), pos.z(), status, blockState));
                    minX = Math.min(minX, pos.x()); maxX = Math.max(maxX, pos.x());
                    minY = Math.min(minY, pos.y()); maxY = Math.max(maxY, pos.y());
                    minZ = Math.min(minZ, pos.z()); maxZ = Math.max(maxZ, pos.z());
                }
            }

            if (blocks.isEmpty())
            {
                return new Result(List.of(), 0);
            }

            // Center the build around (0,0,0)
            int cx = (minX + maxX) / 2;
            int cy = (minY + maxY) / 2;
            int cz = (minZ + maxZ) / 2;
            int height = maxY - minY + 1;

            List<LvcDiff3DBlock> centered = new ArrayList<>(blocks.size());
            for (LvcDiff3DBlock b : blocks)
            {
                centered.add(new LvcDiff3DBlock(b.x() - cx, b.y() - cy, b.z() - cz, b.status(), b.blockState()));
            }

            return new Result(centered, height);
        }
    }

    private static Map<Integer, String> buildStateMap(LvcChunk chunk)
    {
        if (chunk == null) return Map.of();
        Map<Integer, String> map = new HashMap<>();
        LvcIntPosition origin = new LvcIntPosition(0, 0, 0);
        LvcChunkCoordinate dummyCoord = new LvcChunkCoordinate(0, 0, 0);

        for (LvcTrackedBlockCursor.StoredBlock block : LvcTrackedBlockCursor.storedBlocks(dummyCoord, origin, chunk))
        {
            map.put(block.maskIndex(), block.blockState());
        }

        return map;
    }

    private static LvcChunk readChunk(Repository repo, RevCommit commit, String objectId) throws IOException
    {
        byte[] bytes = LvcProjectGitOps.readCommitFile(repo, commit, LvcChunkStore.objectRepositoryPath(objectId));

        if (bytes == null)
        {
            throw new IOException("Missing LVC object " + objectId + " in commit " + commit.getName());
        }

        return LvcChunkCodec.decode(bytes);
    }

    public record Result(List<LvcDiff3DBlock> blocks, int height)
    {
    }
}
