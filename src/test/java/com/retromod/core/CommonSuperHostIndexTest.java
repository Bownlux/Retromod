/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.V21;

/**
 * #366: ProjectE's Arcana ring joins a vanilla snowball with its own fire projectile. Offline, the
 * CLI framed that join as {@code Object} and the class failed verification, because the shared
 * resolver never read the target Minecraft index.
 */
class CommonSuperHostIndexTest {

    private static final String PROJECTILE = "net/minecraft/world/entity/projectile/ThrowableProjectile";
    private static final String SNOWBALL = "net/minecraft/world/entity/projectile/throwableitemprojectile/Snowball";
    private static final String FIRE = "mod/FireProjectile";

    /** The host hierarchy as the target Minecraft index records it. */
    private static final Map<String, String> HOST = Map.of(
            SNOWBALL, "net/minecraft/world/entity/projectile/throwableitemprojectile/ThrowableItemProjectile",
            "net/minecraft/world/entity/projectile/throwableitemprojectile/ThrowableItemProjectile", PROJECTILE,
            PROJECTILE, "net/minecraft/world/entity/projectile/Projectile",
            "net/minecraft/world/entity/projectile/Projectile", "net/minecraft/world/entity/Entity",
            "net/minecraft/world/entity/Entity", "java/lang/Object");

    @Test
    @DisplayName("A mod projectile and a vanilla one meet at their shared Minecraft base")
    void joinsThroughTheTargetIndex() {
        byte[] fire = modClass(FIRE, PROJECTILE);
        Function<String, byte[]> jar = name -> FIRE.equals(name) ? fire : null;

        assertEquals(PROJECTILE, RetromodTransformer.resolveCommonSuperClass(SNOWBALL, FIRE, jar, HOST::get));
        assertEquals(PROJECTILE, RetromodTransformer.resolveCommonSuperClass(FIRE, SNOWBALL, jar, HOST::get));
    }

    @Test
    @DisplayName("Without an index the join stays the conservative Object")
    void withoutAnIndexTheJoinIsObject() {
        byte[] fire = modClass(FIRE, PROJECTILE);
        assertEquals("java/lang/Object", RetromodTransformer.resolveCommonSuperClass(
                SNOWBALL, FIRE, name -> FIRE.equals(name) ? fire : null, name -> null));
    }

    private static byte[] modClass(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC, name, null, superName, null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
