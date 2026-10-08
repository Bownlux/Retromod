/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Applies a Forge {@code Event.Result} to NeoForge's {@code MobDespawnEvent}, which has its own
 * result enum with the same three names. Reflective because Retromod is not compiled against
 * NeoForge.
 */
public final class LegacyDespawnResults {

    private static final String RESULT = "net.neoforged.neoforge.event.entity.living.MobDespawnEvent$Result";

    private LegacyDespawnResults() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void setResult(Object event, Object result) {
        String name = result instanceof Enum<?> constant ? constant.name() : "DEFAULT";
        try {
            Class<? extends Enum> type = (Class<? extends Enum>) Class.forName(RESULT, false,
                    event.getClass().getClassLoader());
            event.getClass().getMethod("setResult", type).invoke(event, Enum.valueOf(type, name));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot set the despawn result " + name + " on " + event, e);
        }
    }
}
