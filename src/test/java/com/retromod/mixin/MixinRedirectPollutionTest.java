/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for #66: a mod-API-specific method rename must not pollute the
 * owner-agnostic bare-name redirect map and rename unrelated mixin targets.
 *
 * <p>AutoRegLib's shim redirects {@code NetworkHandler.register → registerPacket}.
 * Before the fix, {@link MixinCompatibilityTransformer#buildMixinRedirects()}
 * stored that as a bare {@code "register" → "registerPacket"} entry, so Reinforced
 * Barrels' {@code @Invoker} on vanilla {@code BlockEntityType} (which resolves to
 * the very common name {@code register}) got rewritten to {@code registerPacket}
 * and crashed with {@code InvalidAccessorException: No candidates matching
 * registerPacket}. The fix only adds bare-name entries for globally-unique
 * obfuscated names (intermediary {@code method_XXXX} / SRG {@code m_NNNNN_}).</p>
 */
class MixinRedirectPollutionTest {

    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    /** A @Mixin interface with one abstract @Invoker(value=<target>) method. */
    private static byte[] invokerMixin(String internalName, String invokerTarget) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "create", "()V", null, null);
        AnnotationVisitor av = mv.visitAnnotation(INVOKER_DESC, false);
        av.visit("value", invokerTarget);
        av.visitEnd();
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Read the @Invoker's {@code value} from the (single) method of a mixin class. */
    private static String invokerValue(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            for (java.util.List<AnnotationNode> anns : java.util.List.of(
                    m.visibleAnnotations != null ? m.visibleAnnotations : java.util.List.<AnnotationNode>of(),
                    m.invisibleAnnotations != null ? m.invisibleAnnotations : java.util.List.<AnnotationNode>of())) {
                for (AnnotationNode a : anns) {
                    if (!INVOKER_DESC.equals(a.desc) || a.values == null) continue;
                    for (int i = 0; i < a.values.size(); i += 2) {
                        if ("value".equals(a.values.get(i))) return String.valueOf(a.values.get(i + 1));
                    }
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("#66: a mod-API 'register' rename must NOT rewrite a bare 'register' @Invoker")
    void modApiRenameDoesNotPolluteBareName() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        // The exact AutoRegLib redirect that caused #66.
        t.registerMethodRedirect(
                "vazkii/autoreglib/network/NetworkHandler", "register",
                "(Ljava/lang/Class;Lnet/minecraftforge/fml/relauncher/Side;)V",
                "com/retromod/shim/api/common/embedded/AutoRegLibShim", "registerPacket",
                "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/Object;)V", false);

        var mt = new MixinCompatibilityTransformer(t);
        byte[] out = mt.transformMixinClass(invokerMixin("test/BlockEntityTypeInvoker", "register"));

        assertEquals("register", invokerValue(out),
                "a bare 'register' @Invoker must stay 'register' - the owner-specific "
                        + "AutoRegLib NetworkHandler.register→registerPacket rename must not leak (#66)");
    }

    @Test
    @DisplayName("Obfuscated names DO still get bare-name redirects (MC renames keep working)")
    void obfuscatedNamesStillRedirectBareName() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        // A globally-unique intermediary rename, safe to apply owner-agnostically.
        t.registerMethodRedirect(
                "net/minecraft/class_2591", "method_99999001",
                "()V", "net/minecraft/class_2591", "uniquelyRenamedTarget", "()V", false);

        var mt = new MixinCompatibilityTransformer(t);
        byte[] out = mt.transformMixinClass(invokerMixin("test/SomeInvoker", "method_99999001"));

        assertEquals("uniquelyRenamedTarget", invokerValue(out),
                "an intermediary method_XXXX @Invoker target must still be remapped by name");
    }

    @Test
    @DisplayName("A redirect into a Retromod adapter does not rename a mixin target after the adapter")
    void adapterRedirectDoesNotRenameMixinTargets() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        // The shape of the attribute modifier getId() bridge: the mod's calls go to a static
        // helper, which Minecraft never calls and the target class does not declare.
        t.registerMethodRedirect(
                "net/minecraft/class_99999002", "method_99999002", "()Ljava/util/UUID;",
                "com/retromod/generated/LegacyTestHelper", "getId",
                "(Lnet/minecraft/class_99999002;)Ljava/util/UUID;", false);

        var mt = new MixinCompatibilityTransformer(t);
        byte[] out = mt.transformMixinClass(invokerMixin("test/AdapterInvoker", "method_99999002"));

        assertEquals("method_99999002", invokerValue(out),
                "a mixin target must keep the Minecraft name, not the adapter's method name");
    }

    @Test
    @DisplayName("A redirect into a Retromod stand-in for a replaced class still renames mixin targets")
    void standInRedirectStillRenamesMixinTargets() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.registerClassRedirect("net/minecraft/class_99999003", "com/retromod/generated/LegacyTestStandIn");
        t.registerMethodRedirect(
                "net/minecraft/class_99999003", "method_99999003", "()V",
                "com/retromod/generated/LegacyTestStandIn", "standInMethod", "()V", false);

        var mt = new MixinCompatibilityTransformer(t);
        byte[] out = mt.transformMixinClass(invokerMixin("test/StandInInvoker", "method_99999003"));

        assertEquals("standInMethod", invokerValue(out),
                "a mixin follows a class Retromod replaced with its own stand-in");
    }
}
