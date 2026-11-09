package com.retromod.shim.api.fabric.embedded;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.util.Optional;
public class ModMenuShim {
    public static Optional<?> getModBadge() { return Optional.empty(); }
    public static Object createConfigScreen(Object factory, Object parent) {
        try {
            Method create = LegacyPublicMethods.method(factory.getClass(), "create", Class.forName("net.minecraft.client.gui.screen.Screen"));
            return create.invoke(factory, parent);
        } catch (Exception e) { return null; }
    }
}
