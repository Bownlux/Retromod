package com.retromod.shim.api.fabric.embedded;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.util.Optional;
public class ClothConfigShim {
    public static Object createBuilder() {
        try {
            Class<?> builder = Class.forName("me.shedaniel.clothconfig2.api.ConfigBuilder");
            return builder.getMethod("create").invoke(null);
        } catch (Exception e) { throw new RuntimeException("Cannot create Cloth Config builder", e); }
    }
    public static Object startBooleanToggle(Object builder, Object text, boolean value) {
        try {
            Method method = LegacyPublicMethods.method(builder.getClass(), "startBooleanToggle", Class.forName("net.minecraft.text.Text"), boolean.class);
            return method.invoke(builder, text, value);
        } catch (Exception e) { return null; }
    }
    public static void setTooltipCompat(Object entry, Optional<?> tooltip) {
        try {
            for (Method m : LegacyPublicMethods.methods(entry.getClass())) {
                if (m.getName().equals("setTooltip")) { m.invoke(entry, tooltip); return; }
            }
        } catch (Exception e) { }
    }
    public static Object startDropdownMenu(Object builder, Object text, Object value) {
        try {
            for (Method m : LegacyPublicMethods.methods(builder.getClass())) {
                if (m.getName().equals("startDropdownMenu")) { return m.invoke(builder, text, value); }
            }
        } catch (Exception e) { }
        return null;
    }
    public static Object startColorField(Object builder, Object text, int color) {
        try {
            Method method = LegacyPublicMethods.method(builder.getClass(), "startColorField", Class.forName("net.minecraft.text.Text"), int.class);
            return method.invoke(builder, text, color);
        } catch (Exception e) { return null; }
    }
}
