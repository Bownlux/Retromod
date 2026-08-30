/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The signature table sits behind one 1.21.5 gate, but Minecraft keeps adding parameters in later
 * versions. 26.3 gave {@code LivingEntity.getVisibilityPercent} a leading {@code ServerLevel} while
 * 26.2 still declares the one-argument form, so applying that repair on 26.2 would break a handler
 * that was correct.
 */
class VersionGatedSignatureChangeTest {

    private static final String CI =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String SERVER_LEVEL = "Lnet/minecraft/server/level/ServerLevel;";

    private String saved;

    @AfterEach
    void restore() {
        if (saved != null) RetromodVersion.TARGET_MC_VERSION = saved;
    }

    private static MethodNode handler() {
        MethodNode h = new MethodNode(Opcodes.ACC_PRIVATE, "onVisibility",
                "(" + ENTITY + CI + ")V", null, null);
        AnnotationNode inject = new AnnotationNode(
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new ArrayList<>(
                List.of("method", new ArrayList<>(List.of("getVisibilityPercent"))));
        h.visibleAnnotations = new ArrayList<>(List.of(inject));
        return h;
    }

    private List<MixinHandlerResignature.ParamInsert> changeFor(String target) {
        saved = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = target;
        return MixinHandlerResignature.injectSignatureChange(handler());
    }

    @Test
    @DisplayName("The 26.3 change is offered on 26.3 and on its snapshots")
    void appliesFrom263Onward() {
        for (String target : List.of("26.3", "26.3-snapshot-10", "26.4")) {
            List<MixinHandlerResignature.ParamInsert> inserts = changeFor(target);
            assertNotNull(inserts, "expected the repair on " + target);
            assertEquals(1, inserts.size());
            assertEquals(SERVER_LEVEL, inserts.get(0).typeDescriptor());
            assertEquals(0, inserts.get(0).paramIndex());
            restore();
        }
    }

    @Test
    @DisplayName("It is withheld on hosts that still declare the old signature")
    void withheldBefore263() {
        for (String target : List.of("26.2", "26.1", "1.21.11", "1.21.5")) {
            assertNull(changeFor(target),
                    target + " still declares the one-argument form, so repairing would break it");
            restore();
        }
    }

    @Test
    @DisplayName("An ungated change is unaffected by the target version")
    void ungatedChangesStillApplyEverywhere() {
        MethodNode h = new MethodNode(Opcodes.ACC_PRIVATE, "onHurt",
                "(" + ENTITY + CI + ")V", null, null);
        AnnotationNode inject = new AnnotationNode(
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("doHurtTarget"))));
        h.visibleAnnotations = new ArrayList<>(List.of(inject));

        saved = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertNotNull(MixinHandlerResignature.injectSignatureChange(h),
                "doHurtTarget changed in 1.21.5, so it must still apply on 26.2");
    }
}
