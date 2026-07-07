package me.zly2006.lvc.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.semantic.LvcSemanticSchematicBuilder;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.util.LvcLitematicExportFiles;
import me.zly2006.lvc.util.LvcLitematicExportFiles.LitematicExportFile;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcSemanticExportTask extends LvcChunkedTaskBase<LvcProjectService.ExportResult>
{
    private final Path repositoryDirectory;
    private final String commitId;
    private final Path outputDirectory;
    @Nullable private Git git;
    @Nullable private RevWalk revWalk;
    @Nullable private RevCommit commit;
    @Nullable private LvcSemanticSchematicBuilder.BuildSession buildSession;
    @Nullable private LitematicExportFile outputFile;
    @Nullable private LvcProjectService.ExportResult exportResult;
    private Phase phase = Phase.BUILD;

    public LvcSemanticExportTask(LvcOperationHandle handle, Path repositoryDirectory, String commitId, Path outputDirectory,
                                 LvcTaskCallbacks<LvcProjectService.ExportResult> callbacks)
    {
        super(handle, "LVC Export", callbacks, true);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.commitId = Objects.requireNonNull(commitId, "commitId");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
    }

    @Override
    public void init()
    {
        try
        {
            this.git = Git.open(this.repositoryDirectory.toFile());
            Repository repository = this.git.getRepository();
            this.revWalk = new RevWalk(repository);
            this.commit = LvcProjectGitOps.resolveCommit(repository, this.revWalk, this.commitId);
            LvcManifest manifest = LvcSemanticRepository.readCommitManifest(repository, this.commit);
            LvcLocalState localState = LvcSemanticRepository.readLocalState(this.repositoryDirectory);
            String siteId = localState.activeSite();
            LvcManifest.Site site = manifest.site(siteId);

            if (site.regions().isEmpty())
            {
                throw new IOException("LVC project has no tracked sub-regions to export");
            }

            this.outputFile = LvcLitematicExportFiles.targetFile(this.outputDirectory,
                    LvcLitematicExportFiles.commitBaseName(manifest.name(), this.commit.getName()));
            this.buildSession = LvcSemanticSchematicBuilder.beginSchematicBuild(
                    manifest,
                    localState,
                    siteId,
                    objectId -> this.readCommitObject(this.requireCommit(), objectId)
            );
            this.phase = Phase.BUILD;
            LvcDiagnostics.debug(this.handle(), "semantic export initialized commit={} site={} chunks={} output='{}'",
                    this.commit.getName(), siteId, this.buildSession.totalChunks(), this.outputFile.path());
            this.updateProgressHud();
        }
        catch (Exception e)
        {
            this.fail(e instanceof Exception exception ? exception : new RuntimeException(e));
        }
    }

    @Override
    protected boolean step() throws Exception
    {
        if (this.phase == Phase.BUILD)
        {
            LvcSemanticSchematicBuilder.BuildSession session = this.requireBuildSession();

            if (!session.isComplete())
            {
                session.processNextChunk();
                return false;
            }

            this.phase = Phase.WRITE_FILE;
            return false;
        }

        if (this.phase == Phase.WRITE_FILE)
        {
            LitematicExportFile file = this.requireOutputFile();
            LitematicaSchematic schematic = this.requireBuildSession().result();
            LvcLitematicExportFiles.write(schematic, this.outputDirectory, file);

            this.exportResult = new LvcProjectService.ExportResult(file.path(), file.fileName());
            this.phase = Phase.DONE;
            LvcDiagnostics.debug(this.handle(), "semantic export wrote file='{}' commit={}", file.path(), this.requireCommit().getName());
            return true;
        }

        return true;
    }

    @Override
    protected LvcProjectService.ExportResult result()
    {
        return Objects.requireNonNull(this.exportResult, "exportResult");
    }

    @Override
    public void stop()
    {
        try
        {
            super.stop();
        }
        finally
        {
            if (this.revWalk != null)
            {
                this.revWalk.close();
            }

            if (this.git != null)
            {
                this.git.close();
            }
        }
    }

    @Override
    protected void updateProgressHud()
    {
        this.infoHudLines.clear();
        this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);
        this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_phase", this.phase.label));

        if (this.phase == Phase.BUILD && this.buildSession != null)
        {
            this.infoHudLines.add(StringUtils.translate("litematica.gui.label.lvc_project.task_chunks",
                    this.buildSession.processedChunks(), this.buildSession.totalChunks()));
        }
    }

    private byte[] readCommitObject(RevCommit commit, String objectId) throws IOException
    {
        byte[] bytes = LvcProjectGitOps.readCommitFile(this.requireRepository(), commit, LvcChunkStore.objectRepositoryPath(objectId));

        if (bytes == null)
        {
            throw new IOException("Commit " + commit.getName() + " is missing LVC object: " + objectId);
        }

        return bytes;
    }

    private Repository requireRepository()
    {
        if (this.git == null)
        {
            throw new IllegalStateException("LVC export task has no open Git repository");
        }

        return this.git.getRepository();
    }

    private RevCommit requireCommit()
    {
        return Objects.requireNonNull(this.commit, "commit");
    }

    private LvcSemanticSchematicBuilder.BuildSession requireBuildSession()
    {
        return Objects.requireNonNull(this.buildSession, "buildSession");
    }

    private LitematicExportFile requireOutputFile()
    {
        return Objects.requireNonNull(this.outputFile, "outputFile");
    }

    private enum Phase
    {
        BUILD("build export"),
        WRITE_FILE("write file"),
        DONE("done");

        private final String label;

        Phase(String label)
        {
            this.label = label;
        }
    }

}
