package me.zly2006.lvc.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcPlayerIdentity;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.LvcUserActionException;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.storage.LvcHashIndexCodec;
import me.zly2006.lvc.storage.LvcRepository;
import me.zly2006.lvc.storage.LvcSemanticObjectPruner;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.task.LvcOperationJournal;

final class LvcBranchMergeOps
{
    private LvcBranchMergeOps()
    {
    }

    static LvcProjectService.BranchMergeResult mergeBranch(Path repositoryDirectory, String sourceBranch,
                                                           LvcPlayerIdentity player,
                                                           @Nullable LvcProjectService.BranchMergeConflictResolution conflictResolution) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(player, "player");
        String normalizedSourceBranch = LvcGitBranchOps.normalizeLocalBranchName(sourceBranch);

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            String targetFullBranch = repository.getFullBranch();

            if (targetFullBranch == null || !targetFullBranch.startsWith(Constants.R_HEADS))
            {
                throw new IOException("Cannot merge while HEAD is detached");
            }

            String targetBranch = targetFullBranch.substring(Constants.R_HEADS.length());

            if (targetBranch.equals(normalizedSourceBranch))
            {
                throw new IllegalArgumentException("Cannot merge a branch into itself");
            }

            Ref sourceRef = repository.exactRef(Constants.R_HEADS + normalizedSourceBranch);

            if (sourceRef == null)
            {
                throw new IllegalArgumentException("Branch does not exist: " + normalizedSourceBranch);
            }

            if (LvcProjectGitOps.hasUncommittedChanges(repositoryDirectory))
            {
                throw new IOException("Commit or discard changes before merging");
            }

            ObjectId headId = repository.resolve(Constants.HEAD + "^{commit}");
            ObjectId sourceId = repository.resolve(Constants.R_HEADS + normalizedSourceBranch + "^{commit}");

            if (headId == null)
            {
                throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_HEAD,
                        "LVC repository has no HEAD commit to merge into");
            }

            if (sourceId == null)
            {
                throw new IOException("Branch has no commit: " + normalizedSourceBranch);
            }

            RevCommit currentCommit = revWalk.parseCommit(headId);
            RevCommit sourceCommit = revWalk.parseCommit(sourceId);

            LvcDiagnostics.debug(
                    "LvcBranchMergeOps: merge start repo='{}' target='{}' targetCommit='{}' source='{}' sourceCommit='{}'",
                    repositoryDirectory,
                    targetBranch,
                    currentCommit.getName(),
                    normalizedSourceBranch,
                    sourceCommit.getName()
            );

            if (revWalk.isMergedInto(sourceCommit, currentCommit))
            {
                LvcManifest manifest = LvcSemanticRepository.readCommitManifest(repository, currentCommit);
                LvcDiagnostics.debug("LvcBranchMergeOps: merge up-to-date repo='{}' target='{}' source='{}'", repositoryDirectory, targetBranch, normalizedSourceBranch);
                return new LvcProjectService.BranchMergeResult(
                        LvcProjectService.BranchMergeStatus.UP_TO_DATE,
                        targetBranch,
                        normalizedSourceBranch,
                        currentCommit.getName(),
                        manifest.sites().stream().mapToInt(site -> site.regions().size()).sum(),
                        0
                );
            }

            if (revWalk.isMergedInto(currentCommit, sourceCommit))
            {
                writeMergeJournal(repositoryDirectory, sourceCommit.getName(), targetBranch, normalizedSourceBranch, currentCommit.getName(),
                        LvcOperationJournal.PHASE_MERGE_GIT);
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(sourceCommit.getName()).call();
                writeMergeJournal(repositoryDirectory, sourceCommit.getName(), targetBranch, normalizedSourceBranch, currentCommit.getName(), "restore");
                LvcManifest manifest = LvcSemanticRepository.readCommitManifest(repository, sourceCommit);
                LvcDiagnostics.debug("LvcBranchMergeOps: merge fast-forward repo='{}' target='{}' source='{}' commit='{}'",
                        repositoryDirectory, targetBranch, normalizedSourceBranch, sourceCommit.getName());
                return new LvcProjectService.BranchMergeResult(
                        LvcProjectService.BranchMergeStatus.FAST_FORWARD,
                        targetBranch,
                        normalizedSourceBranch,
                        sourceCommit.getName(),
                        manifest.sites().stream().mapToInt(site -> site.regions().size()).sum(),
                        0
                );
            }

            RevCommit mergeBase = mergeBase(repository, currentCommit, sourceCommit);

            if (mergeBase == null)
            {
                throw new LvcMergeConflictException(LvcMergeConflictException.Reason.NO_MERGE_BASE, "No merge base for LVC branches");
            }

            LvcManifest currentManifest = LvcSemanticRepository.readCommitManifest(repository, currentCommit);
            LvcSemanticMergeResult semanticMerge = LvcSemanticMergeEngine.merge(
                    repositoryDirectory, repository, mergeBase, currentCommit, sourceCommit, conflictResolution);
            writeMergeJournal(repositoryDirectory, null, targetBranch, normalizedSourceBranch, currentCommit.getName(),
                    LvcOperationJournal.PHASE_MERGE_GIT);
            LvcSemanticRepository.writeVersionedProjectFiles(repositoryDirectory, semanticMerge.manifest());
            LvcSemanticObjectPruner.pruneChangedObjects(repositoryDirectory, currentManifest, semanticMerge.manifest());
            RevCommit mergeCommit = LvcRepository.commitFilePatternsWithParents(
                    repositoryDirectory,
                    player,
                    "Merge branch '" + normalizedSourceBranch + "' into " + targetBranch,
                    List.of(LvcSemanticRepository.MANIFEST, LvcHashIndexCodec.INDEXES_DIRECTORY, LvcChunkStore.OBJECTS_DIRECTORY,
                            LvcSemanticRepository.GITIGNORE),
                    List.of(currentCommit.getId(), sourceCommit.getId()),
                    true
            );

            if (mergeCommit == null)
            {
                throw new IOException("Failed to create LVC merge commit");
            }

            writeMergeJournal(repositoryDirectory, mergeCommit.getName(), targetBranch, normalizedSourceBranch, currentCommit.getName(), "restore");
            LvcDiagnostics.debug("LvcBranchMergeOps: merge commit created repo='{}' target='{}' source='{}' base='{}' commit='{}' mergedChunks={}",
                    repositoryDirectory, targetBranch, normalizedSourceBranch, mergeBase.getName(), mergeCommit.getName(), semanticMerge.mergedChunks());
            return new LvcProjectService.BranchMergeResult(
                    LvcProjectService.BranchMergeStatus.MERGED,
                    targetBranch,
                    normalizedSourceBranch,
                    mergeCommit.getName(),
                    semanticMerge.manifest().sites().stream().mapToInt(site -> site.regions().size()).sum(),
                    semanticMerge.mergedChunks()
            );
        }
    }

    private static void writeMergeJournal(Path repositoryDirectory, @Nullable String targetCommit,
                                          String targetBranch, String sourceBranch, String previousHead,
                                          String phase) throws IOException
    {
        LvcOperationJournal.write(repositoryDirectory, LvcOperationJournal.Operation.MERGE,
                targetCommit, targetBranch, sourceBranch, previousHead, phase);
        LvcDiagnostics.debug("LvcBranchMergeOps: wrote merge journal repo='{}' phase='{}' target='{}' source='{}' previousHead='{}'",
                repositoryDirectory, phase, targetCommit, sourceBranch, previousHead);
    }

    @Nullable
    private static RevCommit mergeBase(Repository repository, RevCommit currentCommit, RevCommit sourceCommit) throws IOException
    {
        try (RevWalk mergeWalk = new RevWalk(repository))
        {
            mergeWalk.setRevFilter(RevFilter.MERGE_BASE);
            mergeWalk.markStart(mergeWalk.parseCommit(currentCommit.getId()));
            mergeWalk.markStart(mergeWalk.parseCommit(sourceCommit.getId()));
            return mergeWalk.next();
        }
    }

}
