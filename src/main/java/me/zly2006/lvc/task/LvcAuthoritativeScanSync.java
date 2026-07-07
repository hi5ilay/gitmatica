package me.zly2006.lvc.task;

import java.nio.file.Path;
import java.util.Objects;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.LvcProjectService;
import fi.dy.masa.malilib.interfaces.ICompletionListener;

public final class LvcAuthoritativeScanSync
{
    private LvcAuthoritativeScanSync()
    {
    }

    public static void schedule(LvcOperationHandle handle,
                                Path repositoryDirectory,
                                ServerLevel serverWorld,
                                ClientLevel clientWorld,
                                LvcTaskCallbacks<Result> callbacks)
    {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(serverWorld, "serverWorld");
        Objects.requireNonNull(clientWorld, "clientWorld");
        Objects.requireNonNull(callbacks, "callbacks");

        LvcSemanticScanTask[] serverTaskRef = new LvcSemanticScanTask[1];
        LvcSemanticScanTask serverTask = new LvcSemanticScanTask(
                handle,
                repositoryDirectory,
                serverWorld,
                LvcTaskCallbacks.of(
                        serverResult -> onServerScanComplete(handle, repositoryDirectory, serverWorld, clientWorld,
                                serverTaskRef[0], serverResult, callbacks),
                        callbacks.failure(),
                        callbacks.aborted()
                ),
                false
        );
        serverTaskRef[0] = serverTask;
        LvcTaskScheduling.scheduleForWorld(serverWorld, serverTask);
    }

    private static void onServerScanComplete(LvcOperationHandle handle,
                                             Path repositoryDirectory,
                                             ServerLevel serverWorld,
                                             ClientLevel clientWorld,
                                             LvcSemanticScanTask serverTask,
                                             LvcProjectService.SemanticScanResult serverResult,
                                             LvcTaskCallbacks<Result> callbacks)
    {
        LvcSemanticTaskContext.ActiveProject project = serverTask.activeProject();
        LvcDiagnostics.info(handle, "three-way authoritative scan server-vs-commit complete repo='{}' site={} knownChunks={} dirtyChunks={} changedChunks={} addedChunks={} removedChunks={} unknownChunks={}",
                repositoryDirectory, serverResult.siteId(), serverResult.knownChunks(), serverResult.dirtyChunks(),
                serverResult.changedChunks(), serverResult.addedChunks(), serverResult.removedChunks(), serverResult.unknownChunks());

        LvcClientServerStaleScanTask clientTask = new LvcClientServerStaleScanTask(
                handle,
                repositoryDirectory,
                clientWorld,
                project,
                serverTask.scanResult(),
                LvcTaskCallbacks.of(
                        staleResult -> onClientStaleScanComplete(handle, repositoryDirectory, serverWorld, serverResult, staleResult, callbacks),
                        callbacks.failure(),
                        callbacks.aborted()
                ),
                false
        );
        LvcTaskScheduling.scheduleClient(clientTask);
    }

    private static void onClientStaleScanComplete(LvcOperationHandle handle,
                                                  Path repositoryDirectory,
                                                  ServerLevel serverWorld,
                                                  LvcProjectService.SemanticScanResult serverResult,
                                                  LvcClientServerStaleScanTask.Result staleResult,
                                                  LvcTaskCallbacks<Result> callbacks)
    {
        LvcDiagnostics.info(handle, "three-way authoritative scan client-vs-server complete repo='{}' site={} staleChunks={} candidateClientPositions={} unknownClientChunks={}",
                repositoryDirectory, serverResult.siteId(), staleResult.staleChunks(), staleResult.clientStates().size(),
                staleResult.unknownClientChunks());

        LvcServerClientSyncPlanTask syncPlanTask = new LvcServerClientSyncPlanTask(
                handle,
                repositoryDirectory,
                serverWorld,
                serverResult.siteId(),
                staleResult.clientStates(),
                LvcTaskCallbacks.of(
                        syncPlan -> finishOrScheduleSync(handle, repositoryDirectory, serverWorld, serverResult, staleResult, syncPlan, callbacks),
                        callbacks.failure(),
                        callbacks.aborted()
                ),
                false
        );
        LvcTaskScheduling.scheduleForWorld(serverWorld, syncPlanTask);
    }

    private static void finishOrScheduleSync(LvcOperationHandle handle,
                                             Path repositoryDirectory,
                                             ServerLevel serverWorld,
                                             LvcProjectService.SemanticScanResult serverResult,
                                             LvcClientServerStaleScanTask.Result staleResult,
                                             LvcServerClientSyncPlanTask.Result syncPlan,
                                             LvcTaskCallbacks<Result> callbacks)
    {
        LongOpenHashSet syncPositions = syncPlan.syncPositions();
        Result result = new Result(serverResult, staleResult.staleChunks(), staleResult.clientStates().size(),
                syncPositions.size(), staleResult.unknownClientChunks());

        if (syncPositions.isEmpty())
        {
            LvcDiagnostics.info(handle, "three-way authoritative scan no client sync needed repo='{}' site={} staleChunks={} checkedPositions={}",
                    repositoryDirectory, serverResult.siteId(), staleResult.staleChunks(), syncPlan.checkedPositions());
            LvcTaskRegistry.release(handle);
            callbacks.success().accept(result);
            return;
        }

        LvcDiagnostics.info(handle, "three-way authoritative scan queued client sync repo='{}' site={} staleChunks={} checkedPositions={} syncPositions={}",
                repositoryDirectory, serverResult.siteId(), staleResult.staleChunks(), syncPlan.checkedPositions(), syncPositions.size());
        boolean scheduled = LvcAuthoritativeClientSyncTask.schedule(serverWorld, syncPositions, new ICompletionListener()
        {
            @Override
            public void onTaskCompleted()
            {
                LvcDiagnostics.info(handle, "three-way authoritative scan client sync complete repo='{}' site={} syncPositions={}",
                        repositoryDirectory, serverResult.siteId(), result.syncedPositions());
                LvcTaskRegistry.release(handle);
                callbacks.success().accept(result);
            }

            @Override
            public void onTaskAborted()
            {
                LvcDiagnostics.warn("three-way authoritative scan client sync aborted {}", LvcDiagnostics.operationTag(handle));
                LvcTaskRegistry.release(handle);
                callbacks.aborted().run();
            }
        });

        if (!scheduled)
        {
            LvcTaskRegistry.release(handle);
            callbacks.success().accept(result);
        }
    }

    public record Result(LvcProjectService.SemanticScanResult scanResult,
                         int staleChunks,
                         int candidateClientPositions,
                         int syncedPositions,
                         int unknownClientChunks)
    {
    }
}
