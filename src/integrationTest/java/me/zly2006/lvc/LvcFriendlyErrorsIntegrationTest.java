package me.zly2006.lvc;

import java.io.IOException;

final class LvcFriendlyErrorsIntegrationTest
{
    private LvcFriendlyErrorsIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("friendly errors map typed unloaded chunks", LvcFriendlyErrorsIntegrationTest::friendlyErrorsMapTypedUnloadedChunks);
        IntegrationTestSupport.run("friendly errors map wrapped typed checkout preflight unloaded chunks", LvcFriendlyErrorsIntegrationTest::friendlyErrorsMapWrappedTypedCheckoutPreflightUnloadedChunks);
        IntegrationTestSupport.run("friendly errors use interrupted message for recovery-sensitive restore failures", LvcFriendlyErrorsIntegrationTest::friendlyErrorsUseInterruptedMessageForRecoverySensitiveRestoreFailures);
        IntegrationTestSupport.run("friendly errors map typed unreadable capture chunks", LvcFriendlyErrorsIntegrationTest::friendlyErrorsMapTypedUnreadableCaptureChunks);
        IntegrationTestSupport.run("friendly errors hide unexpected raw details", LvcFriendlyErrorsIntegrationTest::friendlyErrorsHideUnexpectedRawDetails);
    }

    private static void friendlyErrorsMapTypedUnloadedChunks()
    {
        LvcFriendlyErrors.FriendlyMessage message = LvcFriendlyErrors.message(
                LvcFriendlyErrors.Operation.CHECKOUT,
                new LvcUserActionException(LvcUserActionException.Reason.TRACKED_CHUNK_UNLOADED,
                        "LVC restore target chunk is not loaded: BlockPos{x=1,y=2,z=3}")
        );

        IntegrationTestSupport.assertEquals("litematica.error.lvc_project.friendly_chunks_unloaded", message.translationKey(), "friendly key");
        IntegrationTestSupport.assertEquals(LvcUserActionException.Reason.TRACKED_CHUNK_UNLOADED, message.reason(), "friendly reason");
        IntegrationTestSupport.assertEquals("Checkout", message.args()[0], "operation display name");
        IntegrationTestSupport.assertTrue(message.expected(), "unloaded chunks should be expected user-action errors");
    }

    private static void friendlyErrorsMapWrappedTypedCheckoutPreflightUnloadedChunks()
    {
        IOException raw = new LvcUserActionException(LvcUserActionException.Reason.TRACKED_CHUNK_UNLOADED,
                "LVC restore target chunk is not loaded: BlockPos{x=10,y=64,z=-20}");
        IOException position = new IOException("Failed to checkout preflight current LVC block at BlockPos{x=10,y=64,z=-20}: " + raw.getMessage(), raw);
        IOException chunk = new IOException("LVC checkout failed during preflight current chunk 0,0,0: " + position.getMessage(), position);

        LvcFriendlyErrors.FriendlyMessage message = LvcFriendlyErrors.message(LvcFriendlyErrors.Operation.CHECKOUT, chunk);

        IntegrationTestSupport.assertEquals("litematica.error.lvc_project.friendly_chunks_unloaded", message.translationKey(), "friendly key");
        IntegrationTestSupport.assertEquals(LvcUserActionException.Reason.TRACKED_CHUNK_UNLOADED, message.reason(), "friendly reason");
    }

    private static void friendlyErrorsUseInterruptedMessageForRecoverySensitiveRestoreFailures()
    {
        IOException raw = new LvcUserActionException(LvcUserActionException.Reason.TRACKED_CHUNK_UNLOADED,
                "LVC restore target chunk is not loaded: BlockPos{x=10,y=64,z=-20}");
        IOException chunk = new IOException("LVC checkout failed during scan/restore chunk 0,0,0: " + raw.getMessage(), raw);

        LvcFriendlyErrors.FriendlyMessage message = LvcFriendlyErrors.message(LvcFriendlyErrors.Operation.CHECKOUT, chunk, true);

        IntegrationTestSupport.assertEquals("litematica.error.lvc_project.friendly_chunks_interrupted", message.translationKey(), "friendly key");
        IntegrationTestSupport.assertEquals("Checkout", message.args()[0], "operation display name");
    }

    private static void friendlyErrorsMapTypedUnreadableCaptureChunks()
    {
        IOException error = new LvcUserActionException(LvcUserActionException.Reason.TRACKED_CHUNK_UNREADABLE,
                "LVC world reader cannot read an authoritative block state at LvcIntPosition[x=1,y=2,z=3]");

        LvcFriendlyErrors.FriendlyMessage message = LvcFriendlyErrors.message(LvcFriendlyErrors.Operation.SAVE_VERSION, error);

        IntegrationTestSupport.assertEquals("litematica.error.lvc_project.friendly_chunks_unloaded", message.translationKey(), "friendly key");
        IntegrationTestSupport.assertEquals(LvcUserActionException.Reason.TRACKED_CHUNK_UNREADABLE, message.reason(), "friendly reason");
        IntegrationTestSupport.assertEquals("Save Version", message.args()[0], "operation display name");
    }

    private static void friendlyErrorsHideUnexpectedRawDetails()
    {
        IOException error = new IOException("sha256:abc123 internal chunk 9,-2 exploded at BlockPos{x=1,y=2,z=3}");

        LvcFriendlyErrors.FriendlyMessage message = LvcFriendlyErrors.message(LvcFriendlyErrors.Operation.MERGE_BRANCH, error);

        IntegrationTestSupport.assertEquals("litematica.error.lvc_project.friendly_unexpected", message.translationKey(), "friendly key");
        IntegrationTestSupport.assertEquals(null, message.reason(), "unexpected reason");
        IntegrationTestSupport.assertEquals("Merge Branch", message.args()[0], "operation display name");
        IntegrationTestSupport.assertEquals(1, message.args().length, "unexpected message must not expose raw details");
    }
}
