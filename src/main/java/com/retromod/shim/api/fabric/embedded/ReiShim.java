package com.retromod.shim.api.fabric.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.util.List;

public class ReiShim {

    public static Object createBasicDisplay(List<?> inputs, List<?> outputs) {
        try {
            Class<?> display = Class.forName("me.shedaniel.rei.api.common.display.basic.BasicDisplay");
            return display.getConstructor(List.class, List.class).newInstance(inputs, outputs);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create BasicDisplay", e);
        }
    }

    public static void addCategory(Object registry, Object category) {
        try {
            Method add = LegacyPublicMethods.method(registry.getClass(), "add", Class.forName("me.shedaniel.rei.api.client.registry.display.DisplayCategory"));
            add.invoke(registry, category);
        } catch (Exception e) {
            // REI absent or signature drifted
        }
    }

    public static Object createRecipeBase(Object bounds) {
        try {
            Class<?> widgets = Class.forName("me.shedaniel.rei.api.client.gui.Widgets");
            Method method = widgets.getMethod("createRecipeBase", Class.forName("me.shedaniel.math.Rectangle"));
            return method.invoke(null, bounds);
        } catch (Exception e) {
            return null;
        }
    }

    public static Object createSlot(Object point) {
        try {
            Class<?> widgets = Class.forName("me.shedaniel.rei.api.client.gui.Widgets");
            Method method = widgets.getMethod("createSlot", Class.forName("me.shedaniel.math.Point"));
            return method.invoke(null, point);
        } catch (Exception e) {
            return null;
        }
    }
}
