/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.tools.ToolProvider;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A 1.20.1 mod tagging a brewing output with {@code PotionUtils.setPotion}, as Wither Storm does,
 * on a 1.20.5+ host where potions live in the {@code potion_contents} component.
 */
class LegacyPotionUtilsBridgeTest {

    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String POTION = "Lnet/minecraft/world/item/alchemy/Potion;";

    @TempDir
    Path dir;

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("setPotion keeps its call shape and lands on the generated component helper")
    void setPotionCallIsRedirected() {
        LegacyPotionUtilsBridge.registerRedirects(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Brewing", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "output", "(" + STACK + POTION + ")" + STACK, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, LegacyPotionUtilsBridge.POTION_UTILS, "setPotion",
                "(" + STACK + POTION + ")" + STACK, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Brewing")).accept(out, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }));
        assertTrue(calls.stream().anyMatch(c -> c.owner.equals(LegacyPotionUtilsBridge.GENERATED)
                        && c.name.equals("setPotion") && c.desc.equals("(" + STACK + POTION + ")" + STACK)),
                "PotionUtils is gone, so the call must reach the generated class with the same shape");
    }

    @Test
    @DisplayName("The generated helpers write the potion_contents component")
    void generatedHelpersUseTheComponent() {
        byte[] generated = LegacyPotionUtilsBridge.generate();
        assertDoesNotThrow(() -> new ClassReader(generated).accept(
                new CheckClassAdapter(new ClassWriter(0), false), 0));
        ClassNode node = new ClassNode();
        new ClassReader(generated).accept(node, 0);
        for (String[] method : new String[][] {
                {"setPotion", "(" + STACK + POTION + ")" + STACK},
                {"setCustomEffects", "(" + STACK + "Ljava/util/Collection;)" + STACK},
                {"getPotion", "(" + STACK + ")" + POTION}}) {
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(method[0]) && m.desc.equals(method[1])),
                    "the old static " + method[0] + " must exist with its 1.20.1 descriptor");
        }
        List<String> called = new ArrayList<>();
        node.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) called.add(call.owner + "." + call.name);
        }));
        assertTrue(called.contains("net/minecraft/world/item/ItemStack.set"), "the potion is stored as a component");
        assertTrue(called.contains(LegacyPotionUtilsBridge.CONTENTS + ".withPotion"));
        assertTrue(called.contains(LegacyPotionUtilsBridge.CONTENTS + ".withEffectAdded"));
    }

    @Test
    @DisplayName("#394: getMobEffects and getCustomEffects read the potion_contents component into new lists")
    void effectReadsUseTheComponent() throws Exception {
        ClassLoader loader = standIns();
        Class<?> effect = loader.loadClass("net.minecraft.world.effect.MobEffectInstance");
        Object speed = effect.getConstructor(String.class).newInstance("speed");
        Object regeneration = effect.getConstructor(String.class).newInstance("regeneration");
        Class<?> stackType = loader.loadClass("net.minecraft.world.item.ItemStack");
        Object stack = stackType.getConstructor().newInstance();
        Class<?> helper = loader.loadClass(LegacyPotionUtilsBridge.GENERATED.replace('/', '.'));

        // A recruit checks its potions this way before it drinks one: an untagged stack has no effects.
        assertEquals(List.of(), helper.getMethod("getMobEffects", stackType).invoke(null, stack));
        assertEquals(List.of(), helper.getMethod("getCustomEffects", stackType).invoke(null, stack));

        // A swiftness potion with an extra custom effect: all effects list the potion's first.
        Object potion = loader.loadClass("net.minecraft.world.item.alchemy.Potion").getConstructor(List.class)
                .newInstance(List.of(speed));
        helper.getMethod("setPotion", stackType, potion.getClass()).invoke(null, stack, potion);
        helper.getMethod("setCustomEffects", stackType, java.util.Collection.class).invoke(null, stack, List.of(regeneration));
        List<?> all = (List<?>) helper.getMethod("getMobEffects", stackType).invoke(null, stack);
        assertEquals(List.of(speed, regeneration), all);
        assertEquals(List.of(regeneration), helper.getMethod("getCustomEffects", stackType).invoke(null, stack));
        assertDoesNotThrow(() -> all.clear(), "1.20.1 returned a new list the caller could change");
    }

    /** The 1.20.5 component classes the generated helpers call, with the generated class beside them. */
    private ClassLoader standIns() throws Exception {
        Map<String, String> sources = Map.of(
                "net/minecraft/core/component/DataComponentType.java",
                "package net.minecraft.core.component; public final class DataComponentType {}",
                "net/minecraft/core/component/DataComponents.java", """
                        package net.minecraft.core.component;
                        public final class DataComponents { public static final DataComponentType POTION_CONTENTS = new DataComponentType(); }""",
                "net/minecraft/world/item/ItemStack.java", """
                        package net.minecraft.world.item;
                        import net.minecraft.core.component.DataComponentType;
                        public final class ItemStack {
                            private final java.util.Map<DataComponentType, Object> components = new java.util.HashMap<>();
                            public Object getOrDefault(DataComponentType type, Object fallback) { return components.getOrDefault(type, fallback); }
                            public Object set(DataComponentType type, Object value) { return components.put(type, value); }
                        }""",
                "net/minecraft/world/effect/MobEffectInstance.java",
                "package net.minecraft.world.effect; public record MobEffectInstance(String name) {}",
                "net/minecraft/world/item/alchemy/Potion.java", """
                        package net.minecraft.world.item.alchemy;
                        public record Potion(java.util.List<net.minecraft.world.effect.MobEffectInstance> effects) {}""",
                "net/minecraft/core/Holder.java", "package net.minecraft.core; public interface Holder<T> { T value(); }",
                "net/minecraft/core/Registry.java", """
                        package net.minecraft.core;
                        public interface Registry<T> { default Holder<T> wrapAsHolder(T value) { return () -> value; } }""",
                "net/minecraft/core/registries/BuiltInRegistries.java", """
                        package net.minecraft.core.registries;
                        public final class BuiltInRegistries { public static final net.minecraft.core.Registry<Object> POTION = new net.minecraft.core.Registry<>() {}; }""",
                "net/minecraft/world/item/alchemy/PotionContents.java", """
                        package net.minecraft.world.item.alchemy;
                        import java.util.*;
                        import net.minecraft.core.Holder;
                        import net.minecraft.world.effect.MobEffectInstance;
                        public record PotionContents(Optional<Holder<Potion>> potion, List<MobEffectInstance> customEffects) {
                            public static final PotionContents EMPTY = new PotionContents(Optional.empty(), List.of());
                            public PotionContents withPotion(Holder<Potion> potion) { return new PotionContents(Optional.of(potion), customEffects); }
                            public PotionContents withEffectAdded(MobEffectInstance effect) {
                                List<MobEffectInstance> effects = new ArrayList<>(customEffects);
                                effects.add(effect);
                                return new PotionContents(potion, List.copyOf(effects));
                            }
                            public Iterable<MobEffectInstance> getAllEffects() {
                                List<MobEffectInstance> all = new ArrayList<>(potion.map(p -> p.value().effects()).orElse(List.of()));
                                all.addAll(customEffects);
                                return List.copyOf(all);
                            }
                        }""");
        Path out = Files.createDirectories(dir.resolve("out"));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = dir.resolve("src").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])));
        // Child first, since other tests put stand-ins of the same Minecraft classes on the test class path.
        return new LegacyKeyDispatchCodecBridgeTest.ChildFirstLoader(new URL[] {out.toUri().toURL()},
                Map.of(LegacyPotionUtilsBridge.GENERATED, LegacyPotionUtilsBridge.generate()));
    }
}
