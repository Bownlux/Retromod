/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.ClassResourceInspector;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * from class resources since the nested-type intermediary IDs shift between versions.
 */
public final class Pre1_21_2InteractionResultBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String INTERACTION_RESULT = "net/minecraft/class_1269";

    /** Descriptor the pre-1.21.2 constants were compiled with. */
    private static final String OLD_DESC = "L" + INTERACTION_RESULT + ";";

    private Pre1_21_2InteractionResultBridge() {}

    /**
     * Register a descriptor rewrite for every static InteractionResult constant whose host type is a
     * nested case type. No-op when the fields still declare {@code Lclass_1269;} or when
     * {@code class_1269} isn't on the classpath.
     */
    public static void register(RetromodTransformer transformer) {
        var ir = ClassResourceInspector.read(INTERACTION_RESULT);
        if (ir == null) {
            LOGGER.debug("InteractionResult support is not needed because class_1269 is unavailable");
            return;
        }

        int registered = 0;
        StringBuilder summary = new StringBuilder();
        for (var f : ir.fields) {
            int required = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
            if ((f.access & required) != required) {
                continue;
            }
            // Lclass_1269; fields already resolve; rewriting them would break a pre-1.21.2 host.
            if (OLD_DESC.equals(f.desc)) continue;
            if (!f.desc.startsWith("L" + INTERACTION_RESULT + "$") || !f.desc.endsWith(";")) {
                continue;
            }

            transformer.registerFieldRedirect(
                    INTERACTION_RESULT, f.name, OLD_DESC,
                    INTERACTION_RESULT, f.name, f.desc);
            registered++;
            if (summary.length() > 0) summary.append(", ");
            summary.append(f.name).append(" -> ").append(f.desc);
        }

        if (registered == 0) {
            LOGGER.debug("The host still uses the old InteractionResult shape");
        } else {
            LOGGER.debug("Added {} InteractionResult descriptor changes: {}",
                    registered, summary);
        }
    }
}
