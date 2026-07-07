package me.zly2006.lvc.gui;

import java.nio.file.Path;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.ObjectId;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.overlay.LvcTrackingOverlayService;
import me.zly2006.lvc.storage.LvcRepository;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.util.LvcLitematicExportFiles;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;

public final class LvcSchematicPlacementRowActions
{
    private static final String OPEN_FAILED_KEY = "litematica.error.lvc_project.open_from_placement_failed";

    private LvcSchematicPlacementRowActions()
    {
    }

    public static boolean isLvcTrackingOverlay(SchematicPlacement placement)
    {
        return repositoryDirectory(placement) != null;
    }

    public static boolean isLvcTrackingOverlay(LitematicaSchematic schematic)
    {
        return repositoryDirectory(schematic) != null;
    }

    public static void openProjectManager(SchematicPlacement placement)
    {
        openProjectScreen(repositoryDirectory(placement), ScreenTarget.PROJECT_MANAGER,
                "placement", placement.getName(), placement.getSchematicFile(),
                repositoryDirectory -> LvcTrackingOverlayService.focusTrackingOverlay(repositoryDirectory, placement));
    }

    public static void openProjectManager(LitematicaSchematic schematic)
    {
        openProjectScreen(repositoryDirectory(schematic), ScreenTarget.PROJECT_MANAGER,
                "schematic", schematic.getMetadata().getName(), schematic.getFile(),
                repositoryDirectory -> focusTrackingOverlay(repositoryDirectory, schematic));
    }

    public static void openProjectEditor(SchematicPlacement placement)
    {
        openProjectScreen(repositoryDirectory(placement), ScreenTarget.PROJECT_EDITOR,
                "placement", placement.getName(), placement.getSchematicFile(),
                repositoryDirectory -> LvcTrackingOverlayService.focusTrackingOverlay(repositoryDirectory, placement));
    }

    public static void openProjectEditor(LitematicaSchematic schematic)
    {
        openProjectScreen(repositoryDirectory(schematic), ScreenTarget.PROJECT_EDITOR,
                "schematic", schematic.getMetadata().getName(), schematic.getFile(),
                repositoryDirectory -> focusTrackingOverlay(repositoryDirectory, schematic));
    }

    public static void exportLoadedOverlay(LitematicaSchematic schematic)
    {
        Path repositoryDirectory = repositoryDirectory(schematic);

        if (repositoryDirectory == null)
        {
            LvcDiagnostics.warn("LvcSchematicPlacementRowActions: missing repo for schematic export schematic='{}' file='{}'",
                    schematic.getMetadata().getName(), schematic.getFile());
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.export_failed", "Not an LVC tracking overlay");
            return;
        }

        try
        {
            LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
            String baseName = exportBaseName(repositoryDirectory, schematic, manifest);
            Path outputDirectory = DataManager.getSchematicsBaseDirectory();
            LvcLitematicExportFiles.LitematicExportFile outputFile = LvcLitematicExportFiles.writeDeterministic(schematic, outputDirectory, baseName);

            LvcDiagnostics.debug("LvcSchematicPlacementRowActions: exported loaded overlay repo='{}' project='{}' schematic='{}' output='{}'",
                    repositoryDirectory, manifest.name(), schematic.getMetadata().getName(), outputFile.path());
            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.exported", outputFile.fileName());
        }
        catch (Exception e)
        {
            LvcDiagnostics.warn("LvcSchematicPlacementRowActions: failed to export loaded overlay repo='{}' schematic='{}' error='{}'",
                    repositoryDirectory, schematic.getMetadata().getName(), e.getMessage());
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.export_failed", e.getMessage());
        }
    }

    public static boolean closeProject(LitematicaSchematic schematic)
    {
        Path repositoryDirectory = repositoryDirectory(schematic);

        if (repositoryDirectory == null)
        {
            LvcDiagnostics.warn("LvcSchematicPlacementRowActions: missing repo for schematic close schematic='{}' file='{}'",
                    schematic.getMetadata().getName(), schematic.getFile());
            LvcGuiMessages.show(MessageType.ERROR, OPEN_FAILED_KEY, "Not an LVC tracking overlay");
            return false;
        }

        String projectName = schematic.getMetadata().getName();

        try
        {
            projectName = LvcSemanticRepository.readManifest(repositoryDirectory).name();
        }
        catch (Exception e)
        {
            LvcDiagnostics.debug("LvcSchematicPlacementRowActions: closing overlay with unreadable manifest repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
        }

        LvcProjectService.removeTrackingOverlay(repositoryDirectory);
        LvcDiagnostics.debug("LvcSchematicPlacementRowActions: closed loaded overlay repo='{}' project='{}' schematic='{}'",
                repositoryDirectory, projectName, schematic.getMetadata().getName());
        LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.closed", projectName);
        return true;
    }

    private static void openProjectScreen(@Nullable Path repositoryDirectory, ScreenTarget target,
                                          String sourceKind, @Nullable String sourceName, @Nullable Path sourceFile,
                                          FocusAction focusAction)
    {
        if (repositoryDirectory == null)
        {
            LvcDiagnostics.warn("LvcSchematicPlacementRowActions: missing repo for {} action target={} name='{}' file='{}'",
                    sourceKind, target, sourceName, sourceFile);
            LvcGuiMessages.show(MessageType.ERROR, OPEN_FAILED_KEY, "Not an LVC tracking overlay");
            return;
        }

        try
        {
            LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
            String projectName = manifest.name();
            boolean focused = focusAction.focus(repositoryDirectory);
            LvcDiagnostics.debug("LvcSchematicPlacementRowActions: opening {} repo='{}' project='{}' {}='{}' focused={}",
                    target, repositoryDirectory, projectName, sourceKind, sourceName, focused);

            if (target == ScreenTarget.PROJECT_EDITOR)
            {
                GuiBase.openGui(new GuiLvcProjectEditor(repositoryDirectory, projectName));
            }
            else
            {
                GuiBase.openGui(new GuiLvcProjectManager(repositoryDirectory, projectName));
            }
        }
        catch (Exception e)
        {
            LvcDiagnostics.warn("LvcSchematicPlacementRowActions: failed to open {} repo='{}' {}='{}' error='{}'",
                    target, repositoryDirectory, sourceKind, sourceName, e.getMessage());
            LvcGuiMessages.show(MessageType.ERROR, OPEN_FAILED_KEY, e.getMessage());
        }
    }

    private static boolean focusTrackingOverlay(Path repositoryDirectory, LitematicaSchematic schematic)
    {
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllPlacementsOfSchematic(schematic))
        {
            if (LvcTrackingOverlayService.focusTrackingOverlay(repositoryDirectory, placement))
            {
                return true;
            }
        }

        return LvcTrackingOverlayService.focusTrackingOverlay(repositoryDirectory);
    }

    private static String exportBaseName(Path repositoryDirectory, LitematicaSchematic schematic, LvcManifest manifest) throws Exception
    {
        ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

        if (head != null)
        {
            return LvcLitematicExportFiles.commitBaseName(manifest.name(), head.getName());
        }

        String schematicName = schematic.getMetadata().getName();
        return schematicName == null || schematicName.isBlank() ? manifest.name() : schematicName;
    }

    @Nullable
    private static Path repositoryDirectory(SchematicPlacement placement)
    {
        return LvcTrackingOverlayService.semanticTrackingRepositoryDirectory(placement.getSchematicFile());
    }

    @Nullable
    private static Path repositoryDirectory(LitematicaSchematic schematic)
    {
        return LvcTrackingOverlayService.semanticTrackingRepositoryDirectory(schematic.getFile());
    }

    private enum ScreenTarget
    {
        PROJECT_MANAGER,
        PROJECT_EDITOR
    }

    private interface FocusAction
    {
        boolean focus(Path repositoryDirectory);
    }
}
