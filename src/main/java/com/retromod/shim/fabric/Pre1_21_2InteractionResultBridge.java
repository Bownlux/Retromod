/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Pre-1.21.2 {@code InteractionResult} field-descriptor bridge (Fabric, pre-26.1, intermediary names).
 *
 * <p>1.21.2 rebuilt {@code class_1269} from a plain enum into a sealed interface with nested case
 * types, so {@code field_5811} (PASS) etc. went from type {@code Lclass_1269;} to
 * {@code Lclass_1269$class_9859;}. The names survive, but a pre-1.21.2 mod with {@code GETSTATIC
 * class_1269.field_5811 : Lclass_1269;} hits {@code NoSuchFieldError} on a 1.21.2+ host because field
 * resolution keys on the full {@code (owner, name, descriptor)} triple. AutoConfig hits this
 * populating screen defaults (Earth2Java).
 *
 * <p>We rewrite the GETSTATIC descriptor to whatever the host field declares; the value pushed is a
 * {@code class_1269} subtype, so downstream code verifies without a CHECKCAST. Targets are discovered
 * reflectively since the nested-type intermediary IDs shift between versions.
 */
public final class Pre1_21_2InteractionResultBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String INTERACTION_RESULT = "net/minecraft/class_1269";

    private static final String INTERACTION_RESULT_FQN = "net.minecraft.class_1269";

    /** Descriptor the pre-1.21.2 constants were compiled with. */
    private static final String OLD_DESC = "L" + INTERACTION_RESULT + ";";

    private Pre1_21_2InteractionResultBridge() {}

    /**
     * Register a descriptor rewrite for every static InteractionResult constant whose host type is a
     * nested case type. No-op when the fields still declare {@code Lclass_1269;} or when
     * {@code class_1269} isn't on the classpath.
     */
    public static void register(RetromodTransformer transformer) {
        Class<?> ir;
        try {
            ir = Class.forName(INTERACTION_RESULT_FQN, false,
                    Pre1_21_2InteractionResultBridge.class.getClassLoader());
        } catch (Throwable t) {
            LOGGER.debug("InteractionResult support is not needed because class_1269 is "
                            + "unavailable ({})",
                    t.getClass().getSimpleName());
            return;
        }

        int registered = 0;
        StringBuilder summary = new StringBuilder();
        for (Field f : ir.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (!(Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods))) {
                continue;
            }
            Class<?> fieldType = f.getType();
            // Lclass_1269; fields already resolve; rewriting them would break a pre-1.21.2 host.
            if (fieldType == ir) continue;

            if (!ir.isAssignableFrom(fieldType)) continue;

            String actualDesc = "L" + fieldType.getName().replace('.', '/') + ";";
            transformer.registerFieldRedirect(
                    INTERACTION_RESULT, f.getName(), OLD_DESC,
                    INTERACTION_RESULT, f.getName(), actualDesc);
            registered++;
            if (summary.length() > 0) summary.append(", ");
            summary.append(f.getName()).append("→").append(actualDesc);
        }

        if (registered == 0) {
            LOGGER.debug("The host still uses the old InteractionResult shape");
        } else {
            LOGGER.debug("Added {} InteractionResult descriptor changes: {}",
                    registered, summary);
        }
    }
}
