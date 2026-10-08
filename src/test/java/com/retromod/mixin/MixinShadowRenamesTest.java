/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.shim.common.LegacyMemberRenames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Shadow members renamed by Mojang after 1.21.4, in the shapes Sophisticated Core and Porting Lib
 * ship (#249): a {@code @Shadow int lastHurtByPlayerTime} on a {@code LivingEntity} mixin and a
 * {@code @Shadow getParamOrNull} on a {@code LootContext} mixin.
 */
public class MixinShadowRenamesTest {

    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String LOOT_CONTEXT = "net/minecraft/world/level/storage/loot/LootContext";
    private static final String PARAM_DESC =
            "(Lnet/minecraft/util/context/ContextKey;)Ljava/lang/Object;";

    @Test
    @DisplayName("A shadow field renamed on the host is renamed with the mixin's own reads")
    void shadowFieldFollowsHostRename() {
        ClassNode mixin = mixinOn(LIVING_ENTITY);
        FieldNode shadow = new FieldNode(ACC_PROTECTED, "lastHurtByPlayerTime", "I", null, null);
        shadow.invisibleAnnotations = shadowAnnotation();
        mixin.fields.add(shadow);
        MethodNode handler = handler(mixin);
        handler.instructions.add(new VarInsnNode(ALOAD, 0));
        handler.instructions.add(new FieldInsnNode(GETFIELD, mixin.name, "lastHurtByPlayerTime", "I"));
        handler.instructions.add(new InsnNode(POP));
        handler.instructions.add(new InsnNode(RETURN));

        assertTrue(MixinShadowRenames.apply(mixin, rename -> true));

        assertEquals("lastHurtByPlayerMemoryTime", shadow.name,
                "26.1 only has lastHurtByPlayerMemoryTime, so the old shadow is never located");
        FieldInsnNode read = (FieldInsnNode) handler.instructions.get(1);
        assertEquals("lastHurtByPlayerMemoryTime", read.name,
                "the mixin's own read must follow the renamed shadow");
    }

    @Test
    @DisplayName("A shadow method renamed on the host is renamed with the mixin's own calls")
    void shadowMethodFollowsHostRename() {
        ClassNode mixin = mixinOn(LOOT_CONTEXT);
        MethodNode shadow = new MethodNode(ACC_PUBLIC | ACC_ABSTRACT, "getParamOrNull", PARAM_DESC,
                null, null);
        shadow.visibleAnnotations = shadowAnnotation();
        mixin.methods.add(shadow);
        MethodNode handler = handler(mixin);
        handler.instructions.add(new VarInsnNode(ALOAD, 0));
        handler.instructions.add(new InsnNode(ACONST_NULL));
        handler.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, mixin.name, "getParamOrNull",
                PARAM_DESC, false));
        handler.instructions.add(new InsnNode(POP));
        handler.instructions.add(new InsnNode(RETURN));

        assertTrue(MixinShadowRenames.apply(mixin, rename -> true));

        assertEquals("getOptionalParameter", shadow.name);
        MethodInsnNode call = (MethodInsnNode) handler.instructions.get(2);
        assertEquals("getOptionalParameter", call.name,
                "the mixin's own call must follow the renamed shadow");
    }

    @Test
    @DisplayName("A host that still has the old name keeps the shadow unchanged")
    void unconfirmedRenameLeavesShadowAlone() {
        ClassNode mixin = mixinOn(LIVING_ENTITY);
        FieldNode shadow = new FieldNode(ACC_PROTECTED, "lastHurtByPlayerTime", "I", null, null);
        shadow.invisibleAnnotations = shadowAnnotation();
        mixin.fields.add(shadow);

        assertFalse(MixinShadowRenames.apply(mixin, rename -> false));
        assertEquals("lastHurtByPlayerTime", shadow.name,
                "a 1.21.1 to 1.21.4 host still declares the old field");
    }

    @Test
    @DisplayName("A same-named field that is not a shadow is the mod's own state and stays put")
    void ownFieldIsNotRenamed() {
        ClassNode mixin = mixinOn(LIVING_ENTITY);
        FieldNode own = new FieldNode(ACC_PRIVATE, "lastHurtByPlayerTime", "I", null, null);
        mixin.fields.add(own);

        assertFalse(MixinShadowRenames.apply(mixin, rename -> true));
        assertEquals("lastHurtByPlayerTime", own.name);
    }

    @Test
    @DisplayName("Without host classes the rename shim registers nothing")
    void renameShimNeedsHostProof() {
        for (LegacyMemberRenames.Rename rename : LegacyMemberRenames.RENAMES) {
            assertFalse(LegacyMemberRenames.hostRenamed(rename),
                    "the test classpath has no Minecraft, so no rename may be assumed: " + rename);
        }
    }

    private static ClassNode mixinOn(String target) {
        ClassNode mixin = new ClassNode();
        mixin.version = V17;
        mixin.access = ACC_PUBLIC | ACC_ABSTRACT;
        mixin.name = "test/TargetMixin";
        mixin.superName = "java/lang/Object";
        AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
        annotation.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(target))));
        mixin.invisibleAnnotations = new ArrayList<>(List.of(annotation));
        return mixin;
    }

    private static MethodNode handler(ClassNode mixin) {
        MethodNode handler = new MethodNode(ACC_PRIVATE, "handler", "()V", null, null);
        mixin.methods.add(handler);
        return handler;
    }

    private static List<AnnotationNode> shadowAnnotation() {
        return new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;")));
    }
}
