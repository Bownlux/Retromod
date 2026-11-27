/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.neoforge.LegacyNeoForgeEventDrift;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The hook and registry object helpers a NeoForge 1.21.1 host embeds name only what 1.21.1 has, so every copied method links. */
class NeoForgeHelpersOnOlderHostsTest {

    private static final String HOOK_READS = "com/retromod/legacyapi/neoforge/event/LegacyHookReads";
    private static final String REGISTRY_OBJECTS = "com/retromod/legacyapi/neoforge/registries/LegacyRegistryObjects";
    private static final String EVENT_HOOKS = "net/neoforged/neoforge/event/EventHooks";
    private static final String COMMON_HOOKS = "net/neoforged/neoforge/common/CommonHooks";
    private static final String DROPS = "net/neoforged/neoforge/event/entity/living/LivingDropsEvent";
    private static final String HOLDER = "net/neoforged/neoforge/registries/DeferredHolder";
    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String SERVER_LEVEL = "Lnet/minecraft/server/level/ServerLevel;";
    private static final String GRIEF_TAIL = "Lnet/minecraft/world/entity/Entity;)Z";
    private static final String DESTROY_TAIL = "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)Z";
    private static final String OLD_ID = "Lnet/minecraft/resources/ResourceLocation;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void neoForge() {
        transformer.clearRedirectsForTesting();
        McReflect.setForceNeoForge(true);
    }

    @AfterEach
    void restore() {
        ClassResourceInspector.setOfflineHost(null);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("NeoForge 1.21.1: the hook helper keeps only the looting level, and the registry helper takes ResourceLocation")
    void olderHostGetsLinkingHelpers() {
        Map<String, ClassNode> host = host(RebuiltApiClasses.BEFORE_IDENTIFIER);
        // 1.21.1 still takes any level in its griefing hooks, and has no Identifier.
        swapLevel(host.get(EVENT_HOOKS), "canEntityGrief", GRIEF_TAIL);
        swapLevel(host.get(COMMON_HOOKS), "canEntityDestroy", DESTROY_TAIL);
        register("1.21.1", host);

        ClassNode hooks = synthetic(HOOK_READS);
        assertEquals(List.of("lootingLevel"), methodNames(hooks), "the 26.x griefing hooks name ServerLevel forms 1.21.1 lacks");
        ClassNode objects = synthetic(REGISTRY_OBJECTS);
        assertTrue(methodNames(objects).containsAll(List.of("getHolder", "create")));
        assertFalse(text(REGISTRY_OBJECTS).contains("net/minecraft/resources/Identifier"), "1.21.1 names ids ResourceLocation");

        String create = "(" + OLD_ID + "Lnet/minecraft/resources/ResourceKey;Ljava/lang/String;)L" + HOLDER + ";";
        assertEquals(List.of(EVENT_HOOKS + ".canEntityGrief(" + LEVEL + GRIEF_TAIL,
                HOOK_READS + ".lootingLevel(L" + DROPS + ";)I",
                REGISTRY_OBJECTS + ".create" + create),
                calls(transformer.transformClass(caller("static " + EVENT_HOOKS + ".canEntityGrief(" + LEVEL + GRIEF_TAIL,
                        DROPS + ".getLootingLevel()I", "static " + HOLDER + ".create" + create), "test/older/Hooks")),
                "a Forge 1.20.1 mod's RegistryObject.create now links on 1.21.1, and the hook it still has stays");
    }

    @Test
    @DisplayName("NeoForge 26.3: both helpers link whole and ship as Retromod's own bytes")
    void newestHostKeepsWholeHelpers() {
        register("26.3", host(null));
        for (String helper : List.of(HOOK_READS, REGISTRY_OBJECTS)) {
            assertArrayEquals(RebuiltApiClasses.read(helper), transformer.getSyntheticClasses().get(helper), helper);
        }
        assertTrue(methodNames(synthetic(HOOK_READS)).containsAll(List.of("canEntityGrief", "canEntityDestroy", "lootingLevel")));
    }

    @Test
    @DisplayName("With no host to read, the whole helpers ship in the target's id names, as before")
    void offlineShipsWholeHelpers() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyNeoForgeEventDrift.register(transformer);
        assertTrue(methodNames(synthetic(HOOK_READS)).containsAll(List.of("canEntityGrief", "canEntityDestroy", "lootingLevel")));
        assertTrue(text(REGISTRY_OBJECTS).contains("net/minecraft/resources/ResourceLocation"));
    }

    // ---- fixtures ----

    private void register(String version, Map<String, ClassNode> host) {
        RetromodVersion.TARGET_MC_VERSION = version;
        ClassResourceInspector.setOfflineHost(loader(host));
        LegacyNeoForgeEventDrift.register(transformer);
    }

    /** A host with every class and member both helper trees name in {@code names}, and the classes that say it is a NeoForge game. */
    private static Map<String, ClassNode> host(Remapper names) {
        Map<String, ClassNode> host = new HashMap<>();
        for (String root : List.of(HOOK_READS, REGISTRY_OBJECTS)) {
            for (String name : RebuiltApiClasses.tree(root)) {
                ClassNode helper = new ClassNode();
                new ClassReader(RebuiltApiClasses.read(name)).accept(names == null ? helper : new ClassRemapper(helper, names), 0);
                for (MethodNode method : helper.methods) {
                    for (RebuiltHostLinks.Ref ref : RebuiltHostLinks.refs(method)) add(host, ref);
                }
            }
        }
        for (String anchor : List.of("net/neoforged/neoforge/common/NeoForge", "net/minecraft/server/MinecraftServer",
                "net/minecraft/world/entity/Entity", DROPS, HOLDER)) {
            host.computeIfAbsent(anchor, NeoForgeHelpersOnOlderHostsTest::named);
        }
        return host;
    }

    /** Makes the hook take any level, as NeoForge 1.21.1's did, instead of a server level. */
    private static void swapLevel(ClassNode hooks, String name, String tail) {
        hooks.methods.removeIf(m -> m.name.equals(name));
        hooks.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(" + LEVEL + tail, null, null));
    }

    private static void add(Map<String, ClassNode> host, RebuiltHostLinks.Ref ref) {
        if (!RebuiltHostLinks.isHost(ref.owner())) return;
        ClassNode node = host.computeIfAbsent(ref.owner(), NeoForgeHelpersOnOlderHostsTest::named);
        if (ref.name() == null) return;
        if (ref.itf()) node.access |= Opcodes.ACC_INTERFACE;
        int access = Opcodes.ACC_PUBLIC | (ref.isStatic() ? Opcodes.ACC_STATIC : 0);
        if (ref.field()) {
            node.fields.add(new FieldNode(access, ref.name(), ref.desc(), null, null));
        } else {
            node.methods.add(new MethodNode(access, ref.name(), ref.desc(), null, null));
        }
    }

    private static ClassNode named(String name) {
        ClassNode node = new ClassNode();
        node.name = name;
        node.access = Opcodes.ACC_PUBLIC;
        return node;
    }

    private static ClassLoader loader(Map<String, ClassNode> host) {
        Map<String, byte[]> classes = new HashMap<>();
        for (ClassNode node : host.values()) {
            node.version = Opcodes.V17;
            if (node.superName == null) node.superName = "java/lang/Object";
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            classes.put(node.name + ".class", writer.toByteArray());
        }
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String resource) {
                byte[] bytes = classes.get(resource);
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }
        };
    }

    private ClassNode synthetic(String name) {
        byte[] bytes = transformer.getSyntheticClasses().get(name);
        assertNotNull(bytes, name + " is embedded");
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private String text(String name) {
        return new String(transformer.getSyntheticClasses().get(name), StandardCharsets.ISO_8859_1);
    }

    private static List<String> methodNames(ClassNode node) {
        return node.methods.stream().map(m -> m.name).filter(n -> !n.startsWith("<")).toList();
    }

    /** One method per call: {@code owner.name desc} on a null receiver, or with {@code static } a static call. */
    private static byte[] caller(String... calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/older/Hooks", null, "java/lang/Object", null);
        int index = 0;
        for (String call : calls) {
            boolean isStatic = call.startsWith("static ");
            String member = isStatic ? call.substring(7) : call;
            String owner = member.substring(0, member.indexOf('.'));
            String name = member.substring(member.indexOf('.') + 1, member.indexOf('('));
            String desc = member.substring(member.indexOf('('));
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call" + index++, "()V", null, null);
            mv.visitCode();
            if (!isStatic) mv.visitInsn(Opcodes.ACONST_NULL);
            for (Type argument : Type.getArgumentTypes(desc)) {
                mv.visitInsn(argument.getSort() == Type.OBJECT ? Opcodes.ACONST_NULL : Opcodes.ICONST_0);
            }
            mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, owner, name, desc, false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
