package com.retromod.shim.api.fabric.embedded;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
public class CardinalShim {
    public static Object getRegistry() {
        try {
            Class<?> registry = Class.forName("org.ladysnake.cca.api.v3.component.ComponentRegistry");
            return registry.getField("INSTANCE").get(null);
        } catch (Exception e) {
            try {
                Class<?> registry = Class.forName("dev.onyxstudios.cca.api.v3.component.ComponentRegistry");
                return registry.getField("INSTANCE").get(null);
            } catch (Exception e2) { return null; }
        }
    }
    public static void readFromNbt(Object component, Object tag) {
        try {
            for (Method m : LegacyPublicMethods.methods(component.getClass())) {
                if (m.getName().equals("readFromNbt") || m.getName().equals("fromTag")) {
                    if (m.getParameterCount() == 1) { m.invoke(component, tag); }
                    else if (m.getParameterCount() == 2) { m.invoke(component, tag, null); }
                    return;
                }
            }
        } catch (Exception e) { }
    }
    public static Object writeToNbt(Object component, Object tag) {
        try {
            for (Method m : LegacyPublicMethods.methods(component.getClass())) {
                if (m.getName().equals("writeToNbt") || m.getName().equals("toTag")) {
                    if (m.getParameterCount() == 1) { return m.invoke(component, tag); }
                    else if (m.getParameterCount() == 2) { return m.invoke(component, tag, null); }
                }
            }
        } catch (Exception e) { }
        return tag;
    }
}
