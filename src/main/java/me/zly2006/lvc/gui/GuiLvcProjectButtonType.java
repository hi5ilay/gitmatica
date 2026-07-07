package me.zly2006.lvc.gui;

import javax.annotation.Nullable;

import fi.dy.masa.litematica.gui.ButtonIcons;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.util.StringUtils;

enum GuiLvcProjectButtonType
{
    SAVE_VERSION("litematica.gui.button.lvc_project.save_version", null),
    DISCARD_CHANGES("litematica.gui.button.lvc_project.discard_changes", null),
    CLEAR_AREA("litematica.gui.button.lvc_project.clear_area", null),
    EXPORT("litematica.gui.button.lvc_project.export", null),
    PUSH("litematica.gui.button.lvc_project.push", null),
    PULL("litematica.gui.button.lvc_project.pull", null),
    BRANCH_SELECTOR("litematica.gui.button.lvc_project.branch", ButtonIcons.SCHEMATIC_PROJECTS),
    CHECKOUT_VERSION("litematica.gui.button.lvc_project.checkout_version", null),
    VIEW_CHANGES("litematica.gui.button.lvc_project.view_changes", null),
    REVERT_CHANGES("litematica.gui.button.lvc_project.revert_changes", null),
    PROJECT_EDITOR("litematica.gui.button.lvc_project.project_editor", ButtonIcons.AREA_EDITOR),
    PROJECT_SETTINGS("litematica.gui.button.lvc_project.project_settings", ButtonIcons.CONFIGURATION),
    CLOSE_PROJECT("litematica.gui.button.lvc_project.close_project", null),
    LITEMATICA_MENU("litematica.gui.button.lvc_project.litematica_menu", null);

    private final String translationKey;
    @Nullable private final IGuiIcon icon;

    GuiLvcProjectButtonType(String translationKey, @Nullable IGuiIcon icon)
    {
        this.translationKey = translationKey;
        this.icon = icon;
    }

    String getLabel(String checkoutBranchName)
    {
        if (this == BRANCH_SELECTOR)
        {
            return StringUtils.translate(this.translationKey, checkoutBranchName);
        }

        return StringUtils.translate(this.translationKey);
    }

    @Nullable
    IGuiIcon icon()
    {
        return this.icon;
    }
}
