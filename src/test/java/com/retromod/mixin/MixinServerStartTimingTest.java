/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** TerraBlender's Fabric hook at the end of the server constructor runs at the start of level loading on 26.3. */
class MixinServerStartTimingTest {

    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("The constructor RETURN hook that sets up TerraBlender's regions moves to loadLevel HEAD")
    void levelSetupHookMoves() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassNode mixin = serverMixin("terrablender/util/LevelUtils");
        assertTrue(MixinServerStartTiming.apply(mixin));
        AnnotationNode inject = MixinMaterialRuleRepair.annotation(mixin.methods.get(0), INJECT);
        assertEquals(List.of(MixinServerStartTiming.LOAD_LEVEL), MixinMaterialRuleRepair.selectors(inject));
    }

    @Test
    @DisplayName("Other constructor hooks, and any hook before 26.3, stay where they are")
    void otherHooksStay() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertFalse(MixinServerStartTiming.apply(serverMixin("mod/Setup")));
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(MixinServerStartTiming.apply(serverMixin("terrablender/util/LevelUtils")));
    }

    private static ClassNode serverMixin(String setupOwner) {
        ClassNode mixin = new ClassNode();
        mixin.version = V17;
        mixin.access = ACC_PUBLIC | ACC_ABSTRACT;
        mixin.name = "terrablender/mixin/MixinMinecraftServer";
        mixin.superName = "java/lang/Object";
        AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
        target.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(MixinServerStartTiming.SERVER)))));
        mixin.invisibleAnnotations = new ArrayList<>(List.of(target));
        MethodNode handler = new MethodNode(ACC_PRIVATE, "onInit", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new ArrayList<>(List.of("value", "RETURN"));
        AnnotationNode inject = new AnnotationNode(INJECT);
        inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("<init>")), "at", new ArrayList<>(List.of(at))));
        handler.visibleAnnotations = new ArrayList<>(List.of(inject));
        handler.instructions.add(new VarInsnNode(ALOAD, 0));
        handler.instructions.add(new MethodInsnNode(INVOKESTATIC, setupOwner, "initializeOnServerStart",
                "(L" + MixinServerStartTiming.SERVER + ";)V", false));
        handler.instructions.add(new InsnNode(RETURN));
        mixin.methods.add(handler);
        return mixin;
    }
}
