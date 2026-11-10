/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.retromod.shim.forge.NbtHostFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An old item's {@code initCapabilities} answers Forge 26.2's {@code getCapabilityProvider}, once per body. */
class LegacyItemCapabilityHookAdapterTest {

    private static final String INIT = LegacyItemCapabilityHookAdapter.INIT_DESC;
    private static final String HOOK = LegacyItemCapabilityHookAdapter.HOOK_DESC;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An item overriding initCapabilities gains getCapabilityProvider, which runs its own override with no tag")
    void overrideGainsTheHook() throws Exception {
        byte[] out = transformer.transformClass(item("mod/Pouch", ITEM, false), "mod/Pouch.class");
        MethodNode hook = method(node(out), "getCapabilityProvider", HOOK);

        List<String> code = new ArrayList<>();
        for (AbstractInsnNode insn : hook.instructions) {
            if (insn.getOpcode() >= 0) code.add(insn instanceof MethodInsnNode call
                    ? "INVOKESPECIAL " + call.owner + "." + call.name : String.valueOf(insn.getOpcode()));
        }
        assertEquals(List.of(String.valueOf(ALOAD), String.valueOf(ALOAD), String.valueOf(ACONST_NULL),
                "INVOKESPECIAL mod/Pouch.initCapabilities", String.valueOf(ARETURN)), code);
        assertEquals("", verify(out, NbtHostFixtures.loader(host())));
    }

    @Test
    @DisplayName("Overrides at two levels each run once through the host hook, with no recursion")
    void twoLevelsRunEachBodyOnce() throws Exception {
        ClassLoader loader = loader(Map.of("mod/BaseItem", item("mod/BaseItem", ITEM, false),
                "mod/SubItem", item("mod/SubItem", "mod/BaseItem", true)));
        Object stack = loader.loadClass(ITEM_STACK.replace('/', '.')).getConstructor().newInstance();
        Object sub = loader.loadClass("mod.SubItem").getConstructor().newInstance();
        Object base = loader.loadClass("mod.BaseItem").getConstructor().newInstance();

        assertNotNull(hook(loader, sub, stack), "the base's provider comes back through the subclass");
        assertEquals(1, calls(loader, "mod.SubItem"), "the subclass's body ran once");
        assertEquals(1, calls(loader, "mod.BaseItem"), "its super call ran the base's body once");
        assertNotNull(hook(loader, base, stack));
        assertEquals(1, calls(loader, "mod.SubItem"), "the base item's hook never reaches the subclass");
        assertEquals(2, calls(loader, "mod.BaseItem"));
    }

    @Test
    @DisplayName("A super call to a Minecraft item's initCapabilities becomes the host hook, which answers null")
    void hostSuperCallBecomesTheHook() throws Exception {
        byte[] bucket = item("mod/ModBucket", BUCKET_ITEM, true);
        byte[] out = transformer.transformClass(bucket, "mod/ModBucket.class");
        List<String> superCalls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node(out), "initCapabilities", INIT).instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL) {
                superCalls.add(call.owner + "." + call.name + call.desc);
            }
        }
        assertEquals(List.of(BUCKET_ITEM + ".getCapabilityProvider" + HOOK), superCalls);

        ClassLoader loader = loader(Map.of("mod/ModBucket", bucket));
        Object stack = loader.loadClass(ITEM_STACK.replace('/', '.')).getConstructor().newInstance();
        assertNull(hook(loader, loader.loadClass("mod.ModBucket").getConstructor().newInstance(), stack));
        assertEquals(1, calls(loader, "mod.ModBucket"));
    }

    @Test
    @DisplayName("An item that already has the hook, a host that still calls initCapabilities, and NeoForge are left alone")
    void leftAloneWhereTheHookIsNotNeeded() {
        ClassNode modern = node(item("mod/Modern", ITEM, false));
        MethodNode own = new MethodNode(ACC_PUBLIC, "getCapabilityProvider", HOOK, null, null);
        own.instructions.add(new InsnNode(ACONST_NULL));
        own.instructions.add(new InsnNode(ARETURN));
        modern.methods.add(own);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        modern.accept(writer);
        byte[] bytes = writer.toByteArray();
        assertSame(bytes, LegacyItemCapabilityHookAdapter.apply(bytes, name -> null), "it answers the hook already");

        for (boolean oldHost : new boolean[]{true, false}) {
            transformer.clearRedirectsForTesting();
            LegacyItemCapabilityHookAdapter.register(transformer, forgeItem(oldHost));
            assertEquals(oldHost ? 0 : 1, hooks(), oldHost ? "Forge 1.20.1 still calls initCapabilities" : "Forge 26.2");
        }
        transformer.clearRedirectsForTesting();
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        LegacyItemCapabilityHookAdapter.register(transformer, forgeItem(false));
        assertEquals(0, hooks(), "NeoForge has its own capability system");
    }

    // How many getCapabilityProvider methods an old item has after the transform.
    private long hooks() {
        return node(transformer.transformClass(item("mod/Pouch", ITEM, false), "mod/Pouch.class")).methods.stream()
                .filter(m -> m.name.equals("getCapabilityProvider")).count();
    }

    @Test
    @DisplayName("A mixin's own initCapabilities gets the hook, as Supplementaries adds one to its items; a shadowed or overwritten one does not")
    void mixinsGetTheHookOnlyForTheirOwnOverride() {
        for (String annotation : new String[]{"Lorg/jetbrains/annotations/Nullable;", "Lorg/spongepowered/asm/mixin/Shadow;",
                "Lorg/spongepowered/asm/mixin/Overwrite;"}) {
            byte[] out = LegacyItemCapabilityHookAdapter.apply(selfMixin(annotation), name -> null);
            boolean hooked = node(out).methods.stream().anyMatch(m -> m.name.equals("getCapabilityProvider"));
            assertEquals(annotation.endsWith("Nullable;"), hooked, annotation);
        }
    }

    /**
     * Supplementaries' {@code SelfLunchBoxItemMixin}: an abstract {@code @Mixin} on the mod's own item that extends
     * the item's base and declares a concrete {@code initCapabilities} carrying {@code annotation}.
     */
    private static byte[] selfMixin(String annotation) {
        String name = "mod/mixins/SelfLunchBoxItemMixin";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, name, null, "mod/SelectableContainerItem", null);
        org.objectweb.asm.AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, org.objectweb.asm.Type.getObjectType("mod/LunchBoxItem"));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "initCapabilities", INIT, null, null);
        init.visitAnnotation(annotation, false).visitEnd();
        init.visitCode();
        init.visitInsn(ACONST_NULL);
        init.visitInsn(ARETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- a translated mod on the test host ----

    private ClassLoader loader(Map<String, byte[]> modClasses) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(host());
        modClasses.forEach((name, bytes) -> classes.put(name, transformer.transformClass(bytes, name + ".class")));
        return NbtHostFixtures.loader(classes);
    }

    /** What Forge 26.2 does when a stack gathers its capabilities: asks the item through IForgeItem. */
    private static Object hook(ClassLoader loader, Object item, Object stack) throws Exception {
        try {
            return loader.loadClass(FORGE_ITEM.replace('/', '.'))
                    .getMethod("getCapabilityProvider", stack.getClass()).invoke(item, stack);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("the hook failed", failure.getCause());
        }
    }

    private static int calls(ClassLoader loader, String item) throws Exception {
        return loader.loadClass(item).getField("CALLS").getInt(null);
    }

    /**
     * {@code class name extends superName} whose {@code initCapabilities(stack, tag)} counts its runs in
     * {@code CALLS}, then returns the super call's provider when {@code callsSuper}, else a provider of its own.
     */
    static byte[] item(String name, String superName, boolean callsSuper) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "CALLS", "I", null, null).visitEnd();
        constructor(cw, superName);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "initCapabilities", INIT, null, null);
        init.visitCode();
        init.visitFieldInsn(GETSTATIC, name, "CALLS", "I");
        init.visitInsn(ICONST_1);
        init.visitInsn(IADD);
        init.visitFieldInsn(PUTSTATIC, name, "CALLS", "I");
        if (callsSuper) {
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 1);
            init.visitVarInsn(ALOAD, 2);
            init.visitMethodInsn(INVOKESPECIAL, superName, "initCapabilities", INIT, false);
        } else {
            init.visitTypeInsn(NEW, "host/Provider");
            init.visitInsn(DUP);
            init.visitMethodInsn(INVOKESPECIAL, "host/Provider", "<init>", "()V", false);
        }
        init.visitInsn(ARETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Forge host whose IForgeItem has the old hook, or only the new one. */
    private static Function<String, ClassNode> forgeItem(boolean old) {
        return name -> {
            if (!name.equals(ForgeHostShape.ANCHOR) && !name.equals(FORGE_ITEM)) return null;
            ClassNode node = new ClassNode();
            node.name = name;
            if (name.equals(FORGE_ITEM)) {
                node.methods.add(new MethodNode(ACC_PUBLIC, old ? "initCapabilities" : "getCapabilityProvider",
                        old ? INIT : HOOK, null, null));
            }
            return node;
        };
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name + desc));
    }
}
