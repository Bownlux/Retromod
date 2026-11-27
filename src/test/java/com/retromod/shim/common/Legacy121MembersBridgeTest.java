/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

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
 * #400: SoniCraft's (Forge 1.20.1) tame mobs build {@code new FollowOwnerGoal(this, speed, start, stop, false)}, and
 * its items check the vanishing curse, both removed in 1.21, so such a mob crashed the server as soon as it spawned.
 */
class Legacy121MembersBridgeTest {

    private static final String FOLLOW = Legacy121MembersBridge.FOLLOW;
    private static final String HELPER = Legacy121MembersBridge.HELPER;
    private static final String NAVIGATION = Legacy121MembersBridge.NAVIGATION;

    @TempDir
    Path dir;

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
        transformer.clearJarClassBytesProvider();
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on 1.21.1 the goal comes from the factory, a subclass drops the flag, and the curses ask the effects")
    void neoForge1211() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        Legacy121MembersBridge.register(transformer, name -> null);

        List<String> mob = calls(transformer.transformClass(tameMob(), "mod/ChaoEntity"));
        assertTrue(mob.contains(HELPER + ".followOwnerGoal" + Legacy121MembersBridge.OLD_FOLLOW.replace(")V", ")L" + FOLLOW + ";")), mob.toString());
        assertTrue(mob.contains(HELPER + ".hasVanishingCurse" + Legacy121MembersBridge.CURSE), mob.toString());

        List<String> goal = calls(transformer.transformClass(ownGoal(), "mod/FlyingFollowGoal"));
        assertTrue(goal.contains(FOLLOW + ".<init>" + Legacy121MembersBridge.NEW_FOLLOW), goal.toString());
        new ClassReader(transformer.getSyntheticClasses().get(HELPER))
                .accept(new org.objectweb.asm.util.CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    @Test
    @DisplayName("26.3 takes the goal only, since its own item calls answer the curses; 1.20.4 keeps both")
    void otherHosts() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Legacy121MembersBridge.register(transformer, name -> null);
        List<String> mob = calls(transformer.transformClass(tameMob(), "mod/ChaoEntity"));
        assertTrue(mob.contains(HELPER + ".followOwnerGoal" + Legacy121MembersBridge.OLD_FOLLOW.replace(")V", ")L" + FOLLOW + ";")), mob.toString());
        assertFalse(mob.contains(HELPER + ".hasVanishingCurse" + Legacy121MembersBridge.CURSE), mob.toString());

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        Legacy121MembersBridge.register(transformer, name -> null);
        assertNull(transformer.getSyntheticClasses().get(HELPER));
    }

    @Test
    @DisplayName("#394: a mod navigation's own isInLiquid() call asks the rebuilt check, which asks the mob")
    void navigationLiquidCheck() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        Legacy121MembersBridge.register(transformer, name -> null);
        // Villager Recruits' ground navigation extends its own async base, which extends PathNavigation.
        Map<String, byte[]> jar = Map.of("mod/AsyncPathNavigation", navigation("mod/AsyncPathNavigation", NAVIGATION, false),
                "mod/AsyncGroundPathNavigation", navigation("mod/AsyncGroundPathNavigation", "mod/AsyncPathNavigation", true));
        transformer.setJarClassBytesProvider(jar::get);
        List<String> calls = calls(transformer.transformClass(jar.get("mod/AsyncGroundPathNavigation"), "mod/AsyncGroundPathNavigation"));
        assertEquals(List.of(Legacy121MembersBridge.NAVIGATIONS + ".isInLiquid(L" + NAVIGATION + ";)Z"), calls);

        // The rebuilt check reads the protected mob and asks it, as the entity answers on 1.21.
        ClassLoader loader = standIns(transformer.getSyntheticClasses().get(Legacy121MembersBridge.NAVIGATIONS));
        Class<?> mobType = loader.loadClass("net.minecraft.world.entity.Mob");
        Object swimmer = mobType.getConstructor(boolean.class).newInstance(true);
        Object walker = mobType.getConstructor(boolean.class).newInstance(false);
        Class<?> navigationType = loader.loadClass(NAVIGATION.replace('/', '.'));
        java.lang.reflect.Method isInLiquid = loader.loadClass(Legacy121MembersBridge.NAVIGATIONS.replace('/', '.'))
                .getMethod("isInLiquid", navigationType);
        assertEquals(true, isInLiquid.invoke(null, navigationType.getConstructor(mobType).newInstance(swimmer)));
        assertEquals(false, isInLiquid.invoke(null, navigationType.getConstructor(mobType).newInstance(walker)));
    }

    @Test
    @DisplayName("a host whose navigation still has isInLiquid() keeps the call")
    void hostWithTheOldCheck() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        ClassNode oldNavigation = new ClassNode();
        oldNavigation.methods.add(new MethodNode(ACC_PROTECTED, "isInLiquid", "()Z", null, null));
        Legacy121MembersBridge.register(transformer, name -> name.equals(NAVIGATION) ? oldNavigation : null);
        assertNull(transformer.getSyntheticClasses().get(Legacy121MembersBridge.NAVIGATIONS));
    }

    /** A mod navigation class; with {@code asks}, its canUpdatePath() calls {@code this.isInLiquid()}. */
    private static byte[] navigation(String name, String superName, boolean asks) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, name, null, superName, null);
        if (asks) {
            MethodVisitor mv = cw.visitMethod(ACC_PROTECTED, "canUpdatePath", "()Z", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, name, "isInLiquid", "()Z", false);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The 1.21.1 entity and navigation shapes the rebuilt check reads, with the rebuilt class beside them. */
    private ClassLoader standIns(byte[] rebuilt) throws Exception {
        Map<String, String> sources = Map.of(
                "net/minecraft/world/entity/Entity.java", """
                        package net.minecraft.world.entity;
                        public class Entity {
                            private final boolean liquid;
                            public Entity(boolean liquid) { this.liquid = liquid; }
                            public boolean isInLiquid() { return liquid; }
                        }""",
                "net/minecraft/world/entity/Mob.java", """
                        package net.minecraft.world.entity;
                        public class Mob extends Entity { public Mob(boolean liquid) { super(liquid); } }""",
                "net/minecraft/world/entity/ai/navigation/PathNavigation.java", """
                        package net.minecraft.world.entity.ai.navigation;
                        public class PathNavigation {
                            protected final net.minecraft.world.entity.Mob mob;
                            public PathNavigation(net.minecraft.world.entity.Mob mob) { this.mob = mob; }
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
        // Child first, since Retromod's own copy of the rebuilt class is on the test class path without the stand-ins.
        return new LegacyKeyDispatchCodecBridgeTest.ChildFirstLoader(new URL[] {out.toUri().toURL()},
                Map.of(Legacy121MembersBridge.NAVIGATIONS, rebuilt));
    }

    /** A tame mob's goals and a drop check, as MCreator writes them. */
    private static byte[] tameMob() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/ChaoEntity", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "goals",
                "(Lnet/minecraft/world/entity/TamableAnimal;Lnet/minecraft/world/item/ItemStack;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, Legacy121MembersBridge.ENCHANTMENT_HELPER, "hasVanishingCurse", Legacy121MembersBridge.CURSE, false);
        mv.visitInsn(POP);
        mv.visitTypeInsn(NEW, FOLLOW);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(DCONST_1);
        mv.visitLdcInsn(10.0F);
        mv.visitInsn(FCONST_2);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKESPECIAL, FOLLOW, "<init>", Legacy121MembersBridge.OLD_FOLLOW, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod's own follow goal, which passes the flag up. */
    private static byte[] ownGoal() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/FlyingFollowGoal", null, FOLLOW, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lnet/minecraft/world/entity/TamableAnimal;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(DCONST_1);
        mv.visitLdcInsn(10.0F);
        mv.visitInsn(FCONST_2);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESPECIAL, FOLLOW, "<init>", Legacy121MembersBridge.OLD_FOLLOW, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
