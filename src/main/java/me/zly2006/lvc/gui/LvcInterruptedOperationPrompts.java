package me.zly2006.lvc.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import me.zly2006.lvc.storage.LvcChunkStagingStore;
import me.zly2006.lvc.task.LvcOperationJournal;

import fi.dy.masa.malilib.gui.GuiBase;

public final class LvcInterruptedOperationPrompts
{
    private LvcInterruptedOperationPrompts()
    {
    }

    public static void cancelInterruptedNonWorldOperationsOnWorldJoin(Minecraft minecraft)
    {
        List<String> cancelledOperations = new ArrayList<>();
        boolean unreadableRecoveryDataCleared = false;

        try
        {
            Path reposRoot = LvcProjectService.reposDirectory(minecraft.gameDirectory.toPath());

            if (!Files.isDirectory(reposRoot))
            {
                return;
            }

            try (var stream = Files.list(reposRoot))
            {
                for (Path candidate : stream.toList())
                {
                    unreadableRecoveryDataCleared |= cleanupInterruptedNonWorldOperation(candidate, cancelledOperations);
                }
            }
        }
        catch (Exception e)
        {
            LvcDiagnostics.warn("Failed to scan interrupted LVC operations on world join: {}", e.getMessage());
            return;
        }

        if (!cancelledOperations.isEmpty())
        {
            showCancelledOperationDialog(minecraft, cancelledOperations);
        }
        else if (unreadableRecoveryDataCleared)
        {
            showUnreadableRecoveryDataDialog(minecraft);
        }
    }

    private static boolean cleanupInterruptedNonWorldOperation(Path repositoryDirectory, List<String> cancelledOperations)
    {
        try
        {
            LvcOperationJournal.Entry entry = LvcOperationJournal.read(repositoryDirectory);

            if (entry == null)
            {
                return false;
            }

            LvcOperationJournal.Operation operation = LvcOperationJournal.Operation.valueOf(entry.operation());

            if (!isNonWorldMutatingOperation(operation))
            {
                return false;
            }

            if (operation == LvcOperationJournal.Operation.INIT)
            {
                LvcChunkStagingStore.deleteRecursivelyIfExists(repositoryDirectory);
            }
            else
            {
                LvcChunkStagingStore.deleteRecursivelyIfExists(
                        LvcOperationJournal.gitLocalDirectory(repositoryDirectory).resolve(LvcOperationJournal.STAGING_DIRECTORY)
                );
                LvcOperationJournal.delete(repositoryDirectory);
            }

            cancelledOperations.add(GuiLvcRecoveryDialog.displayOperation(operation));
            LvcDiagnostics.debug("LVC interrupted non-world operation cancelled on world join repo='{}' operation='{}' phase='{}'",
                    repositoryDirectory, entry.operation(), entry.phase());
            return false;
        }
        catch (LvcOperationJournal.CorruptJournalException e)
        {
            try
            {
                LvcOperationJournal.quarantineCorruptJournals(repositoryDirectory, e.corruptPaths());
                LvcDiagnostics.warn("Corrupt LVC operation journal quarantined on world join repo='{}' paths={}",
                        repositoryDirectory, e.corruptPaths());
                return true;
            }
            catch (Exception quarantineFailure)
            {
                LvcDiagnostics.warn("Failed to quarantine corrupt LVC operation journal repo='{}' error='{}'",
                        repositoryDirectory, quarantineFailure.getMessage());
                return false;
            }
        }
        catch (Exception e)
        {
            LvcDiagnostics.warn("Failed to clean interrupted LVC operation for repo='{}': {}", repositoryDirectory, e.getMessage());
            return false;
        }
    }

    private static boolean isNonWorldMutatingOperation(LvcOperationJournal.Operation operation)
    {
        return operation == LvcOperationJournal.Operation.INIT ||
                operation == LvcOperationJournal.Operation.SAVE ||
                operation == LvcOperationJournal.Operation.UPDATE_AREAS;
    }

    private static void showCancelledOperationDialog(Minecraft minecraft, List<String> cancelledOperations)
    {
        List<String> uniqueOperations = new ArrayList<>(new LinkedHashSet<>(cancelledOperations));
        String operations = String.join(", ", uniqueOperations);
        String messageKey = uniqueOperations.size() == 1 ?
                "litematica.gui.message.lvc_project.interrupted_operation_cancelled" :
                "litematica.gui.message.lvc_project.interrupted_operations_cancelled";

        minecraft.execute(() -> GuiBase.openGui(new GuiLvcNoticeDialog(
                minecraft.screen,
                "litematica.gui.title.lvc_project.operation_cancelled",
                messageKey,
                0xFFFF5555,
                operations
        )));
    }

    private static void showUnreadableRecoveryDataDialog(Minecraft minecraft)
    {
        minecraft.execute(() -> GuiBase.openGui(new GuiLvcNoticeDialog(
                minecraft.screen,
                "litematica.gui.title.lvc_project.operation_cancelled",
                "litematica.gui.message.lvc_project.recovery_data_unreadable",
                0xFFFF5555
        )));
    }
}
