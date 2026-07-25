/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/**
 * 1.3.0: bridge for {@code ItemInteractionResult} ({@code class_9062}), the 1.20.5-1.21.1 sided
 * item-use result that 1.21.2 merged back into {@code InteractionResult} (6 corpus mods; absent
 * from the 1.21.4-era intermediary tsv because it died before the harvest, so nothing remapped it).
 * The class redirect retypes it to {@code net/minecraft/world/InteractionResult}; its members
 * become calls here:
 *
 * <ul>
 *   <li>Enum constants (GETSTATIC, the dominant usage): {@code SUCCESS}/{@code CONSUME} map
 *   directly; {@code CONSUME_PARTIAL} to {@code CONSUME}; {@code PASS_TO_DEFAULT_BLOCK_INTERACTION}
 *   (58 corpus refs) to its literal 1.21.2 rename {@code TRY_WITH_EMPTY_HAND}; {@code
 *   SKIP_DEFAULT_BLOCK_INTERACTION} to {@code FAIL} (stops the interaction chain without a swing,
 *   the closest surviving semantic); {@code FAIL} to {@code FAIL}.</li>
 *   <li>{@code sidedSuccess(boolean)} to plain {@code SUCCESS} (1.21.2 dropped the client/server
 *   split; vanilla's own migration replaced {@code sidedSuccess(isClientSide)} with SUCCESS).</li>
 *   <li>{@code consumesAction()} reflectively invokes the surviving interface default of the same
 *   name (the receiver IS an InteractionResult after the class redirect; a direct redirect would
 *   emit INVOKEVIRTUAL against an interface).</li>
 *   <li>{@code result()} (the unwrap to the old plain InteractionResult) is the identity: after
 *   the merge the receiver already IS the result.</li>
 * </ul>
 *
 * <p>Zero compile-time Minecraft dependency; cached reflection; fail-safe null/false returns.
 */
public final class RetroItemInteractionResult {

    private RetroItemInteractionResult() {}

    private static volatile Class<?> irClass;
    private static final java.util.Map<String, Object> CONSTANTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Object constant(String name) {
        try {
            Object v = CONSTANTS.get(name);
            if (v != null) return v;
            Class<?> c = irClass;
            if (c == null) {
                irClass = c = Class.forName("net.minecraft.world.InteractionResult");
            }
            v = c.getField(name).get(null);
            if (v != null) CONSTANTS.put(name, v);
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object success() { return constant("SUCCESS"); }

    public static Object consume() { return constant("CONSUME"); }

    /** CONSUME_PARTIAL: consumed-without-full-success has no 26.x form; CONSUME is the merge. */
    public static Object consumePartial() { return constant("CONSUME"); }

    /** PASS_TO_DEFAULT_BLOCK_INTERACTION: literally renamed TRY_WITH_EMPTY_HAND at 1.21.2. */
    public static Object passToDefaultBlockInteraction() { return constant("TRY_WITH_EMPTY_HAND"); }

    /** SKIP_DEFAULT_BLOCK_INTERACTION: stop the chain without a swing; FAIL is the closest. */
    public static Object skipDefaultBlockInteraction() { return constant("FAIL"); }

    public static Object fail() { return constant("FAIL"); }

    /** Old {@code sidedSuccess(boolean isClientSide)}: the sided split is gone. */
    public static Object sidedSuccess(boolean isClientSide) { return constant("SUCCESS"); }

    /** Old {@code consumesAction()}: invoke the surviving interface default reflectively. */
    public static boolean consumesAction(Object receiver) {
        try {
            return receiver != null
                    && (Boolean) receiver.getClass().getMethod("consumesAction").invoke(receiver);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Old {@code result()}: post-merge the receiver already IS the InteractionResult. */
    public static Object result(Object receiver) {
        return receiver;
    }
}
