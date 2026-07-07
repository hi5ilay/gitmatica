package me.zly2006.lvc.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import me.zly2006.lvc.LvcDiagnostics;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.util.FileNameUtils;

public final class LvcLitematicExportFiles
{
    private LvcLitematicExportFiles()
    {
    }

    public static LitematicExportFile writeDeterministic(LitematicaSchematic schematic, Path outputDirectory, String baseName) throws IOException
    {
        LitematicExportFile file = targetFile(outputDirectory, baseName);
        write(schematic, outputDirectory, file);
        return file;
    }

    public static void write(LitematicaSchematic schematic, Path outputDirectory, LitematicExportFile file) throws IOException
    {
        Objects.requireNonNull(schematic, "schematic");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(file, "file");

        Files.createDirectories(outputDirectory);
        boolean overwriting = Files.exists(file.path());
        LvcDiagnostics.debug("LvcLitematicExportFiles: writing export output='{}' overwrite={}", file.path(), overwriting);

        if (!schematic.writeToFile(outputDirectory, file.fileName(), true))
        {
            throw new IOException("Failed to write LVC export: " + file.path());
        }
    }

    public static LitematicExportFile targetFile(Path outputDirectory, String baseName)
    {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(baseName, "baseName");

        Path path = litematicOutputPath(outputDirectory, baseName);
        return new LitematicExportFile(path, path.getFileName().toString());
    }

    public static String commitBaseName(String projectName, String commitId)
    {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(commitId, "commitId");

        String cleanProjectName = projectName.trim();
        String cleanCommitId = commitId.trim();

        if (cleanProjectName.isEmpty())
        {
            throw new IllegalArgumentException("LVC export project name must not be blank");
        }

        if (cleanCommitId.isEmpty())
        {
            throw new IllegalArgumentException("LVC export commit id must not be blank");
        }

        return cleanProjectName + "-" + cleanCommitId.substring(0, Math.min(8, cleanCommitId.length()));
    }

    private static Path litematicOutputPath(Path outputDirectory, String fileNameIn)
    {
        String fileName = FileNameUtils.generateSimpleUnicodeSafeFileName(fileNameIn);

        if (!fileName.endsWith(LitematicaSchematic.FILE_EXTENSION))
        {
            fileName = fileName + LitematicaSchematic.FILE_EXTENSION;
        }

        return outputDirectory.resolve(FileNameUtils.generateSafeFileName(fileName)).normalize();
    }

    public record LitematicExportFile(Path path, String fileName)
    {
    }
}
