package me.zly2006.lvc.gui;

import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.interfaces.IConfirmationListener;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.interfaces.IStringDualConsumerFeedback;
import me.zly2006.lvc.LvcProjectService;

final class GuiLvcProjectCommandListeners
{
    private GuiLvcProjectCommandListeners()
    {
    }

    record ButtonListener(GuiLvcProjectButtonType type, GuiLvcProjectController controller) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.controller.focusTrackingOverlay();
            this.controller.handleButton(this.type);
        }
    }

    record RemoteUrlSetter(GuiLvcProjectController controller, boolean pushAfterSave) implements IStringConsumerFeedback
    {
        @Override
        public boolean setString(String remoteUrl)
        {
            try
            {
                LvcProjectService.setRemote(this.controller.gui.repositoryDirectory, remoteUrl);
                this.controller.refreshRepositoryState();

                if (this.pushAfterSave)
                {
                    LvcProjectService.push(this.controller.gui.repositoryDirectory);
                    LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.pushed");
                }
                else
                {
                    this.controller.gui.initGui();
                    LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.remote_updated", remoteUrl.trim());
                }

                return true;
            }
            catch (Exception e)
            {
                LvcGuiMessages.show(MessageType.ERROR, this.pushAfterSave ? "litematica.error.lvc_project.push_failed" : "litematica.error.lvc_project.remote_failed", LvcProjectService.describeRemoteFailure(e));
                return false;
            }
        }
    }

    record CommitMessageSetter(GuiLvcProjectController controller) implements IStringDualConsumerFeedback
    {
        @Override
        public boolean setStrings(String title, String description)
        {
            if (title == null || title.isBlank())
            {
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.commit_failed", "Commit message must not be blank");
                return false;
            }

            this.controller.commitStoredSelectionWithCurrentSelectionFallback(this.fullCommitMessage(title, description));
            return true;
        }

        private String fullCommitMessage(String title, String description)
        {
            String trimmedTitle = title.strip();
            String trimmedDescription = description == null ? "" : description.strip();

            if (trimmedDescription.isBlank())
            {
                return trimmedTitle;
            }

            return trimmedTitle + "\n\n" + trimmedDescription;
        }
    }

    record UpdateAreasConfirmListener(GuiLvcProjectController controller) implements IConfirmationListener
    {
        @Override
        public boolean onActionConfirmed()
        {
            this.controller.updateAreasFromCurrentSelection();
            return true;
        }

        @Override
        public boolean onActionCancelled()
        {
            return true;
        }
    }

    record ResetAndCheckoutCommitConfirmListener(GuiLvcProjectController controller, LvcProjectService.CommitInfo commit) implements IConfirmationListener
    {
        @Override
        public boolean onActionConfirmed()
        {
            this.controller.resetWorkingTreeThenCheckoutCommit(this.commit);
            return true;
        }

        @Override
        public boolean onActionCancelled()
        {
            return true;
        }
    }

}
