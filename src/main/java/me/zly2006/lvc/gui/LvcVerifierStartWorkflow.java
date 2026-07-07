package me.zly2006.lvc.gui;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcFriendlyErrors.Operation;
import me.zly2006.lvc.overlay.LvcTrackingOverlayService;
import me.zly2006.lvc.task.LvcAuthoritativeScanSync;
import me.zly2006.lvc.task.LvcOperationHandle;
import me.zly2006.lvc.task.LvcTaskCallbacks;
import me.zly2006.lvc.task.LvcTaskRegistry;
import me.zly2006.lvc.world.LvcWorldAccess;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.interfaces.ICompletionListener;

public final class LvcVerifierStartWorkflow
{
    private LvcVerifierStartWorkflow()
    {
    }

    public static boolean startIfGitMatica(SchematicPlacement placement,
                                           ICompletionListener verifierCompletionListener,
                                           Runnable refreshGui)
    {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(refreshGui, "refreshGui");

        Path repositoryDirectory = LvcTrackingOverlayService.semanticTrackingRepositoryDirectory(placement.getSchematicFile());

        if (repositoryDirectory == null)
        {
            return false;
        }

        SchematicVerifier verifier = placement.getSchematicVerifier();

        if (verifier.isPaused())
        {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientWorld = minecraft.level;

        if (clientWorld == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return true;
        }

        if (SchematicWorldHandler.getSchematicWorld() == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.generic.schematic_world_not_loaded");
            return true;
        }

        ServerLevel serverWorld;

        try
        {
            Level authoritativeWorld = LvcWorldAccess.resolveSemanticRestoreWorld(clientWorld);

            if (!(authoritativeWorld instanceof ServerLevel resolvedServerWorld))
            {
                throw new IllegalStateException("Start Verification requires a server-authoritative world");
            }

            serverWorld = resolvedServerWorld;
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.START_VERIFICATION, "litematica.error.lvc_project.start_verification_failed", e);
            return true;
        }

        Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquire("LVC Start Verification", repositoryDirectory);

        if (handle.isEmpty())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return true;
        }

        try
        {
            LvcDiagnostics.info(handle.get(), "GitMatica verifier start delayed until three-way scan/client sync completes repo='{}' placement='{}'",
                    repositoryDirectory, placement.getName());
            LvcAuthoritativeScanSync.schedule(
                    handle.get(),
                    repositoryDirectory,
                    serverWorld,
                    clientWorld,
                    LvcTaskCallbacks.of(
                            result -> startVerifierAfterSync(placement, verifierCompletionListener, refreshGui, result),
                            e -> LvcGuiMessages.showTaskError(Operation.START_VERIFICATION, "litematica.error.lvc_project.start_verification_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Start Verification")
                    )
            );
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.START_VERIFICATION, "litematica.error.lvc_project.start_verification_failed", e);
            return true;
        }

        int staleErrors = verifier.getTotalErrors();
        int staleInventoryErrors = verifier.getWrongInventories();
        verifier.reset();
        refreshGui.run();
        LvcDiagnostics.debug(handle.get(), "GitMatica verifier cleared stale rows before preflight repo='{}' placement='{}' errors={} inventoryErrors={}",
                repositoryDirectory, placement.getName(), staleErrors, staleInventoryErrors);
        LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_started", "LVC Start Verification");

        return true;
    }

    private static void startVerifierAfterSync(SchematicPlacement placement,
                                               ICompletionListener verifierCompletionListener,
                                               Runnable refreshGui,
                                               LvcAuthoritativeScanSync.Result result)
    {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientWorld = minecraft.level;
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

        if (clientWorld == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        if (schematicWorld == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.generic.schematic_world_not_loaded");
            return;
        }

        LvcDiagnostics.info("GitMatica verifier starting after three-way scan sync placement='{}' clean={} dirtyChunks={} staleChunks={} syncedPositions={}",
                placement.getName(), result.scanResult().clean(), result.scanResult().dirtyChunks(),
                result.staleChunks(), result.syncedPositions());
        placement.getSchematicVerifier().startVerification(clientWorld, schematicWorld, placement, verifierCompletionListener);
        refreshGui.run();
    }
}
