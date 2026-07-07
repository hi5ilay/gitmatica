package me.zly2006.lvc.util;

import java.util.Objects;

import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;

public final class LvcGuiTextFields
{
    private LvcGuiTextFields()
    {
    }

    public static void setPosition(GuiTextFieldGeneric field, int x, int y)
    {
        Objects.requireNonNull(field, "field");

        field.setX(x);
        field.setY(y);
        refreshTextPosition(field);
    }

    public static void refreshTextPosition(GuiTextFieldGeneric field)
    {
        Objects.requireNonNull(field, "field");
        // Malilib shadows EditBox x/y; toggling the same border state refreshes vanilla text/caret caches.
        field.setBordered(field.isBordered());
    }
}
