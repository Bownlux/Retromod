/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.ClassResourceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-1.20 Material bridge for Fabric on pre-26.1 hosts (intermediary names).
 *
 * <p>Minecraft 1.20 deleted the Material system ({@code class_3614}) outright. Any reference
 * from a pre-1.20 mod ({@code anewarray}, {@code getstatic Material.WATER}, a method
 * signature) then dies {@code NoClassDefFoundError} at class load, and one such reference in
 * a {@code <clinit>} takes the whole class down with it (found live, round 7 of the
 * snapshot.8 acceptance pass: Collective's {@code GlobalVariables.<clinit>} builds a
 * {@code List<Material>}; every Serilum mod touching GlobalVariables then failed).
 *
 * <p>Class-redirects {@code class_3614} to {@link com.retromod.polyfill.fabric.MaterialPolyfill}
 * (a real class shipped in Retromod's jar; Fabric's knot loader has no module boundaries, so
 * mods resolve it directly, the FabricBlockSettings-bridge pattern), devirtualizes
 * {@code BlockState.getMaterial()} ({@code class_2680.method_26207}) to the polyfill's
 * {@code fromState}, and neutralizes the two Material methods whose return types can't be
 * declared in plain Java ({@code method_15798} piston behavior, {@code method_15803} color).
 *
 * <p>Host-introspecting: registers only when the host actually LOST {@code class_3614}
 * (1.20+) while still being an intermediary host ({@code class_2680} present), so 1.16.x-1.19.x
 * hosts (Material intact) are untouched. Probes read class resources without defining classes.
 */
public final class Pre1_20MaterialBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String MATERIAL = "net/minecraft/class_3614";
    private static final String BLOCK_STATE = "net/minecraft/class_2680";
    private static final String POLY = "com/retromod/polyfill/fabric/MaterialPolyfill";

    private Pre1_20MaterialBridge() {}

    /** Register the Material polyfill redirects when the host lost the class. */
    public static void register(RetromodTransformer transformer) {
        if (!ClassResourceInspector.exists(BLOCK_STATE)) return;
        if (ClassResourceInspector.exists(MATERIAL)) return;

        // Every reference (field owners/types, array types, casts, signatures) retypes to
        // the polyfill; its 44 constants carry the exact 1.16.5 intermediary field ids.
        transformer.registerClassRedirect(MATERIAL, POLY);

        // BlockState.getMaterial() was deleted with the system; devirtualize to fromState
        // (receiver becomes arg 0). Keyed on BOTH descriptor spellings: the transformer's
        // method visitor runs downstream of the remapper, so it normally sees the
        // post-redirect descriptor, but keep the original as a safety net.
        transformer.registerMethodRedirect(BLOCK_STATE, "method_26207",
                "()L" + POLY + ";",
                POLY, "fromState", "(Ljava/lang/Object;)L" + POLY + ";", true);
        transformer.registerMethodRedirect(BLOCK_STATE, "method_26207",
                "()L" + MATERIAL + ";",
                POLY, "fromState", "(Ljava/lang/Object;)L" + POLY + ";", true);

        // Material methods whose 1.16.5 return types (class_3619 piston behavior,
        // class_3620 material color) can't be declared on the plain-Java polyfill:
        // devirtualize to a null-returning static (removed-method neutralization only
        // supports void returns; the redirect emits a CHECKCAST back to the original
        // return type, which null passes).
        transformer.registerMethodRedirect(POLY, "method_15798",
                "()Lnet/minecraft/class_3619;",
                POLY, "nullMaterialProperty", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        transformer.registerMethodRedirect(POLY, "method_15803",
                "()Lnet/minecraft/class_3620;",
                POLY, "nullMaterialProperty", "(Ljava/lang/Object;)Ljava/lang/Object;", true);

        LOGGER.info("Registered pre-1.20 Material bridge "
                + "(class_3614 -> MaterialPolyfill, getMaterial -> fromState)");
    }
}
