package com.retromod.shim.api.forge.embedded;
import com.retromod.shim.api.common.embedded.ApiShimWarnings;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.util.Optional;
public class CapabilityProviderShim {
    public static LazyOptionalShim<?> getCapability(Object provider, Object capability, Object direction) {
        try {
            for (Method m : LegacyPublicMethods.methods(provider.getClass())) {
                if (m.getName().equals("getCapability")) {
                    Object result = m.invoke(provider, capability, direction);
                    if (result instanceof Optional) {
                        return LazyOptionalShim.of(() -> ((Optional<?>) result).orElse(null));
                    }
                    return LazyOptionalShim.of(() -> result);
                }
            }
        } catch (Exception e) {
            ApiShimWarnings.warnOnce("Capability", "getCapability", provider, "it returned an empty capability", e);
        }
        return LazyOptionalShim.empty();
    }
}
