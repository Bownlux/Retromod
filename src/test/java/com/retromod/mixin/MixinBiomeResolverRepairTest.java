/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static com.retromod.mixin.MixinBiomeResolverRepair.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** TerraBlender's head handler on the removed per-position biome lookup runs through 26.3's resolvers. */
class MixinBiomeResolverRepairTest {

    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("The handler becomes the hook, and createResolver and createResolverForChunk hand their result to the wrapper")
    void headHandlerMovesOntoTheResolvers() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassNode mixin = terraBlenderMixin(OLD_LOOKUP, "HEAD");

        assertTrue(MixinBiomeResolverRepair.apply(mixin));

        MethodNode hook = method(mixin, HOOK_METHOD);
        assertEquals(HANDLER_DESC, hook.desc, "the handler keeps the arguments the mod's code reads");
        assertNotEquals(0, hook.access & ACC_PUBLIC, "the wrapper calls it from outside the biome source");
        assertNull(MixinMaterialRuleRepair.annotation(hook, INJECT), "the removed lookup is no longer an injection target");
        assertTrue(mixin.interfaces.contains(HOOK));
        assertEquals(List.of(CREATE), MixinMaterialRuleRepair.selectors(
                MixinMaterialRuleRepair.annotation(method(mixin, "retromod$wrapResolver"), INJECT)));
        assertEquals(List.of(CREATE_FOR_CHUNK), MixinMaterialRuleRepair.selectors(
                MixinMaterialRuleRepair.annotation(method(mixin, "retromod$wrapChunkResolver"), INJECT)));
        verify(mixin);
    }

    @Test
    @DisplayName("A handler that is not at the head, or a host that still has the lookup, is left alone")
    void onlyHeadHandlersOnTheNewHost() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertFalse(MixinBiomeResolverRepair.apply(terraBlenderMixin(OLD_LOOKUP, "TAIL")));
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        ClassNode mixin = terraBlenderMixin("getNoiseBiome", "HEAD");
        assertFalse(MixinBiomeResolverRepair.apply(mixin), "offline, a 26.2 target still has the lookup");
        assertNotNull(MixinMaterialRuleRepair.annotation(method(mixin, "getNoiseBiome"), INJECT));
    }

    /** TerraBlender 4.1's MixinMultiNoiseBiomeSource: cir.setReturnValue(((IExtendedParameterList) parameters()).findValuePositional(...)). */
    private static ClassNode terraBlenderMixin(String selector, String at) {
        ClassNode mixin = new ClassNode();
        mixin.version = V17;
        mixin.access = ACC_PUBLIC | ACC_ABSTRACT;
        mixin.name = "terrablender/mixin/MixinMultiNoiseBiomeSource";
        mixin.superName = "java/lang/Object";
        AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
        target.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(SOURCE)))));
        mixin.invisibleAnnotations = new ArrayList<>(List.of(target));

        MethodNode handler = new MethodNode(ACC_PUBLIC, "getNoiseBiome", HANDLER_DESC, null, null);
        AnnotationNode where = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        where.values = new ArrayList<>(List.of("value", at));
        AnnotationNode inject = new AnnotationNode(INJECT);
        inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at",
                new ArrayList<>(List.of(where)), "cancellable", true));
        handler.visibleAnnotations = new ArrayList<>(List.of(inject));
        handler.instructions.add(new VarInsnNode(ALOAD, 5));
        handler.instructions.add(new InsnNode(ACONST_NULL));
        handler.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, CALLBACK, "setReturnValue", "(Ljava/lang/Object;)V", false));
        handler.instructions.add(new InsnNode(RETURN));
        handler.maxStack = 2;
        handler.maxLocals = 6;
        mixin.methods.add(handler);
        return mixin;
    }

    private static MethodNode method(ClassNode mixin, String name) {
        return mixin.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static void verify(ClassNode mixin) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        mixin.accept(writer);
        ClassNode written = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(written, 0);
        for (MethodNode method : written.methods) new Analyzer<>(new BasicVerifier()).analyze(written.name, method);
    }
}
