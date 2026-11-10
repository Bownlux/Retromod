/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** TerraBlender's and Citadel's generator settings mixins, in the shapes they ship, on 26.3's generator settings. */
class MixinMaterialRuleRepairTest {

    private static final String SETTINGS = "net/minecraft/world/level/levelgen/NoiseGeneratorSettings";
    private static final String RULE = "net/minecraft/world/level/levelgen/material/rule/MaterialRule";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String MANAGER = "terrablender/api/SurfaceRuleManager";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("the surface rule shadow and injection move to 26.3's rule holder, and the returned rule is wrapped in one")
    void settingsMixinFollowsTheHolder() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassNode mixin = settingsMixin("surfaceRule", false);

        assertTrue(MixinMaterialRuleRepair.apply(mixin, check(true)));

        FieldNode shadow = mixin.fields.get(0);
        assertEquals("materialRule", shadow.name, "26.3 has no surfaceRule field, so the old shadow is never located");
        assertEquals("L" + HOLDER + ";", shadow.desc);
        assertEquals(List.of("materialRule"), selectors(handler(mixin)));
        List<String> calls = calls(handler(mixin));
        assertTrue(calls.contains(HOLDER + ".value"), "a read of the shadow takes the rule out of the holder: " + calls);
        assertEquals(calls.indexOf(HOLDER + ".direct") + 1, calls.indexOf(CALLBACK + ".setReturnValue"),
                "the rule goes back wrapped in a holder, since materialRule() returns one: " + calls);
        verify(mixin);
    }

    @Test
    @DisplayName("a selector that names the old accessor with its owner and descriptor is retargeted too")
    void fullSelector() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        String old = "L" + SETTINGS + ";surfaceRule()L" + RULE + ";";
        ClassNode mixin = settingsMixin(old, false);
        assertTrue(MixinMaterialRuleRepair.apply(mixin, check(true)));
        assertEquals(List.of("L" + SETTINGS + ";materialRule()L" + HOLDER + ";"), selectors(handler(mixin)));
    }

    @Test
    @DisplayName("a write to the shadow, as Citadel swaps the rule, stores the rule in a holder")
    void writeStoresAHolder() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassNode writer = settingsMixin("surfaceRule", true);
        assertTrue(MixinMaterialRuleRepair.apply(writer, check(true)));
        MethodNode reset = writer.methods.stream().filter(m -> m.name.equals("reset")).findFirst().orElseThrow();
        assertEquals(List.of(HOLDER + ".direct"), calls(reset));
        FieldInsnNode write = (FieldInsnNode) reset.instructions.get(3);
        assertEquals("materialRule", write.name);
        verify(writer);
    }

    @Test
    @DisplayName("the repair leaves a 26.2 host, which still has surfaceRule(), as it was")
    void declinedOnOlderHost() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        ClassNode mixin = settingsMixin("surfaceRule", false);
        assertFalse(MixinMaterialRuleRepair.apply(mixin, check(true)));
        assertEquals("surfaceRule", mixin.fields.get(0).name);
    }

    @Test
    @DisplayName("a handler that reads the old return value, or passes its callback on, is left as it was")
    void callbackThatEscapesIsDeclined() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassNode reader = settingsMixin("surfaceRule", false);
        MethodNode handler = handler(reader);
        // Before the final return: cir.getReturnValue();
        AbstractInsnNode ret = handler.instructions.getLast();
        handler.instructions.insertBefore(ret, new VarInsnNode(ALOAD, 1));
        handler.instructions.insertBefore(ret, new MethodInsnNode(INVOKEVIRTUAL, CALLBACK, "getReturnValue",
                "()Ljava/lang/Object;", false));
        handler.instructions.insertBefore(ret, new InsnNode(POP));
        assertFalse(MixinMaterialRuleRepair.apply(reader, check(true)), "it would read a holder where it expects the rule");
        assertEquals("surfaceRule", reader.fields.get(0).name);

        ClassNode passer = settingsMixin("surfaceRule", false);
        MethodNode passing = handler(passer);
        ret = passing.instructions.getLast();
        passing.instructions.insertBefore(ret, new VarInsnNode(ALOAD, 1));
        passing.instructions.insertBefore(ret, new MethodInsnNode(INVOKESTATIC, MANAGER, "observe",
                "(L" + CALLBACK + ";)V", false));
        assertFalse(MixinMaterialRuleRepair.apply(passer, check(true)), "the mod's own code could read the return value");
    }

    @Test
    @DisplayName("an accessor interface on the settings is left as it was, since it is not merged into them")
    void interfaceDeclined() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassNode accessor = settingsMixin("surfaceRule", false);
        accessor.access |= ACC_INTERFACE;
        assertFalse(MixinMaterialRuleRepair.apply(accessor, check(true)));
        assertEquals("surfaceRule", accessor.fields.get(0).name);
    }

    @Test
    @DisplayName("the repair is declined when what the repaired code loads would not link, or no host is indexed")
    void linkCheckDeclines() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassNode unreadable = settingsMixin("surfaceRule", false);
        assertFalse(MixinMaterialRuleRepair.apply(unreadable, check(false)),
                "the mod class the handler calls cannot be read, so its code is not proven to load");
        assertEquals("surfaceRule", unreadable.fields.get(0).name);
        assertFalse(MixinMaterialRuleRepair.apply(settingsMixin("surfaceRule", false), null), "no indexed host");
    }

    @Test
    @DisplayName("an old context mixin that shadows a field 26.3 dropped is skipped, as it was while its target was missing")
    void contextMixinWithDroppedShadowIsSkipped() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        String context = "net/minecraft/world/level/levelgen/material/MaterialRuleContext";
        ClassNode lithostitched = mixin("dev/worldgen/lithostitched/mixin/common/SurfaceRulesContextMixin", context);
        lithostitched.fields.add(shadowField("blockX", "I"));
        lithostitched.fields.add(shadowField("chunk", "Lnet/minecraft/world/level/chunk/ChunkAccess;"));
        assertTrue(MixinMaterialRuleRepair.apply(lithostitched, null));
        AnnotationNode target = lithostitched.invisibleAnnotations.get(0);
        assertEquals(List.of("retromod/stripped/SurfaceRulesContextMixin"), target.values.get(target.values.indexOf("targets") + 1));

        ClassNode kept = mixin("mod/ContextMixin", context);
        kept.fields.add(shadowField("blockX", "I"));
        assertFalse(MixinMaterialRuleRepair.apply(kept, null), "every shadow it names is still there");
    }

    /** A link check over an indexed 26.3 host with the holder and rule types, and the mod's manager when {@code managerInJar}. */
    private MixinLinkCheck check(boolean managerInJar) throws Exception {
        Path jar = dir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            ClassWriter holder = new ClassWriter(0);
            holder.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, HOLDER, null, "java/lang/Object", null);
            holder.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "value", "()Ljava/lang/Object;", null, null).visitEnd();
            MethodVisitor direct = holder.visitMethod(ACC_PUBLIC | ACC_STATIC, "direct", "(Ljava/lang/Object;)L" + HOLDER + ";",
                    null, null);
            direct.visitCode();
            direct.visitInsn(ACONST_NULL);
            direct.visitInsn(ARETURN);
            direct.visitMaxs(1, 1);
            direct.visitEnd();
            holder.visitEnd();
            put(out, holder.toByteArray());
            ClassWriter rule = new ClassWriter(0);
            rule.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, RULE, null, "java/lang/Object", null);
            rule.visitEnd();
            put(out, rule.toByteArray());
            ClassWriter settings = new ClassWriter(0);
            settings.visit(V17, ACC_PUBLIC | ACC_FINAL, SETTINGS, null, "java/lang/Record", null);
            settings.visitEnd();
            put(out, settings.toByteArray());
        }
        FuzzyMethodResolver host = new FuzzyMethodResolver();
        host.indexJar(jar);
        ClassWriter manager = new ClassWriter(0);
        manager.visit(V17, ACC_PUBLIC, MANAGER, null, "java/lang/Object", null);
        manager.visitEnd();
        byte[] managerBytes = manager.toByteArray();
        return new MixinLinkCheck(host, Map.of(), name -> managerInJar && name.equals(MANAGER) ? managerBytes : null);
    }

    private static void put(JarOutputStream out, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(new ClassReader(bytes).getClassName() + ".class"));
        out.write(bytes);
        out.closeEntry();
    }

    private static FieldNode shadowField(String name, String desc) {
        FieldNode field = new FieldNode(0, name, desc, null, null);
        field.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;")));
        return field;
    }

    /** {@code @Mixin(NoiseGeneratorSettings)} with TerraBlender's shadow and its HEAD injection into the accessor. */
    private static ClassNode settingsMixin(String selector, boolean writesShadow) {
        ClassNode mixin = mixin("terrablender/mixin/MixinNoiseGeneratorSettings", SETTINGS);
        FieldNode shadow = new FieldNode(ACC_PRIVATE, "surfaceRule", "L" + RULE + ";", null, null);
        shadow.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;")));
        mixin.fields.add(shadow);
        mixin.fields.add(new FieldNode(ACC_PRIVATE, "namespaced", "L" + RULE + ";", null, null));

        MethodNode handler = new MethodNode(ACC_PRIVATE, "surfaceRule", "(L" + CALLBACK + ";)V", null, null);
        AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "cancellable", true));
        handler.visibleAnnotations = new ArrayList<>(List.of(inject));
        // if (namespaced == null) namespaced = SurfaceRuleManager.wrap(surfaceRule); cir.setReturnValue(namespaced);
        LabelNode cached = new LabelNode();
        handler.instructions.add(new VarInsnNode(ALOAD, 0));
        handler.instructions.add(new FieldInsnNode(GETFIELD, mixin.name, "namespaced", "L" + RULE + ";"));
        handler.instructions.add(new JumpInsnNode(IFNONNULL, cached));
        handler.instructions.add(new VarInsnNode(ALOAD, 0));
        handler.instructions.add(new VarInsnNode(ALOAD, 0));
        handler.instructions.add(new FieldInsnNode(GETFIELD, mixin.name, "surfaceRule", "L" + RULE + ";"));
        handler.instructions.add(new MethodInsnNode(INVOKESTATIC, MANAGER, "wrap", "(L" + RULE + ";)L" + RULE + ";", false));
        handler.instructions.add(new FieldInsnNode(PUTFIELD, mixin.name, "namespaced", "L" + RULE + ";"));
        handler.instructions.add(cached);
        handler.instructions.add(new VarInsnNode(ALOAD, 1));
        handler.instructions.add(new VarInsnNode(ALOAD, 0));
        handler.instructions.add(new FieldInsnNode(GETFIELD, mixin.name, "namespaced", "L" + RULE + ";"));
        handler.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, CALLBACK, "setReturnValue", "(Ljava/lang/Object;)V", false));
        handler.instructions.add(new InsnNode(RETURN));
        mixin.methods.add(handler);

        if (writesShadow) {
            MethodNode reset = new MethodNode(ACC_PUBLIC, "reset", "()V", null, null);
            reset.instructions.add(new VarInsnNode(ALOAD, 0));
            reset.instructions.add(new InsnNode(ACONST_NULL));
            reset.instructions.add(new FieldInsnNode(PUTFIELD, mixin.name, "surfaceRule", "L" + RULE + ";"));
            reset.instructions.add(new InsnNode(RETURN));
            mixin.methods.add(reset);
        }
        return mixin;
    }

    private static ClassNode mixin(String name, String target) {
        ClassNode mixin = new ClassNode();
        mixin.version = V17;
        mixin.access = ACC_PUBLIC | ACC_ABSTRACT;
        mixin.name = name;
        mixin.superName = "java/lang/Object";
        AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
        annotation.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(target)))));
        mixin.invisibleAnnotations = new ArrayList<>(List.of(annotation));
        return mixin;
    }

    private static MethodNode handler(ClassNode mixin) {
        return mixin.methods.stream().filter(m -> m.desc.equals("(L" + CALLBACK + ";)V")).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<String> selectors(MethodNode handler) {
        AnnotationNode inject = handler.visibleAnnotations.get(0);
        return (List<String>) inject.values.get(inject.values.indexOf("method") + 1);
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }

    /** Writes the class with computed sizes and checks every method's stack and local types. */
    private static void verify(ClassNode mixin) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        mixin.accept(writer);
        ClassNode written = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(written, 0);
        for (MethodNode method : written.methods) {
            if ((method.access & ACC_ABSTRACT) == 0) new Analyzer<>(new BasicVerifier()).analyze(written.name, method);
        }
    }
}
