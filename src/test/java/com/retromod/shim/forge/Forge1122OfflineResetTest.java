/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An offline batch does not apply one 1.12.2 input's bridges to the next input. */
class Forge1122OfflineResetTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.resetForTesting();
        Forge1122EventBridge.resetForTesting();
        Forge1122FluidBridge.resetForTesting();
    }

    /** A class that names the 1.12 fluid stack, which only a registered fluid bridge renames. */
    private static byte[] fluidUser() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Tank", null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "stored", "Lnet/minecraftforge/fluids/FluidStack;", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void resettingRegistrationsTurnsTheLegacyGatesOff() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.resetOfflineRegistrations();
        Forge1122LifecycleSynthetics.register(t);
        Forge1122EventBridge.register(t);
        Forge1122ServiceStubs.register(t);
        assertTrue(Forge1122LifecycleSynthetics.isActive());
        byte[] tank = fluidUser();
        assertNotSame(tank, Forge1122FluidBridge.renameSharedFluidTypes(tank));

        t.resetOfflineRegistrations();

        assertFalse(Forge1122LifecycleSynthetics.isActive(),
                "the next input must not be upgraded as a 1.12.2 mod");
        assertSame(tank, Forge1122FluidBridge.renameSharedFluidTypes(tank),
                "the next input must keep its own FluidStack");
        assertSame(tank, Forge1122EventBridge.rewriteLegacyCalls(tank));
    }
}
