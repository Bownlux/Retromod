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
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2's griefing hook takes a ServerLevel; old mobs also ask it with a client level. */
class MobGriefingForge262Test {

    private static final String MOD = "mod/Mob";
    private static final String FORGE = LegacyForgeEventFactoryBridge.FORGE;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path tempDir;

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A server level asks the host, which reads the game rule and posts the event")
    void serverLevelAsksTheHost() throws Exception {
        Class<?> mob = translatedMob();
        ClassLoader host = mob.getClassLoader();
        Object level = host.loadClass("net.minecraft.server.level.ServerLevel").getConstructor().newInstance();
        Object entity = host.loadClass("net.minecraft.world.entity.Entity").getConstructor().newInstance();
        Class<?> factory = host.loadClass("net.minecraftforge.event.ForgeEventFactory");

        factory.getField("rule").set(null, false);
        assertEquals(false, mob.getMethod("grief", host.loadClass("net.minecraft.world.level.Level"),
                entity.getClass()).invoke(null, level, entity));
        assertEquals(1, factory.getField("asked").get(null));
    }

    @Test
    @DisplayName("A client level posts the event and allows griefing unless a listener denies it")
    void clientLevelPostsTheEvent() throws Exception {
        Class<?> mob = translatedMob();
        ClassLoader host = mob.getClassLoader();
        Class<?> levelType = host.loadClass("net.minecraft.world.level.Level");
        Class<?> entityType = host.loadClass("net.minecraft.world.entity.Entity");
        Object level = levelType.getConstructor().newInstance();
        Object entity = entityType.getConstructor().newInstance();
        Class<?> event = host.loadClass("net.minecraftforge.event.entity.EntityMobGriefingEvent");
        Object bus = event.getField("BUS").get(null);

        assertEquals(true, mob.getMethod("grief", levelType, entityType).invoke(null, level, entity));
        bus.getClass().getField("answer").set(bus, "DENY");
        assertEquals(false, mob.getMethod("grief", levelType, entityType).invoke(null, level, entity));
        bus.getClass().getField("answer").set(bus, "ALLOW");
        assertEquals(true, mob.getMethod("grief", levelType, entityType).invoke(null, level, entity));
        assertEquals(3, bus.getClass().getField("posted").get(bus));
        assertEquals(0, host.loadClass("net.minecraftforge.event.ForgeEventFactory").getField("asked").get(null),
                "a client level never reaches the server-only hook");

        // Without an entity there is nothing to post.
        assertEquals(true, mob.getMethod("grief", levelType, entityType).invoke(null, level, null));
        assertEquals(3, bus.getClass().getField("posted").get(bus));
    }

    @Test
    @DisplayName("NeoForge and Forge 1.21.11 targets keep the old hook as it is")
    void otherHostsAreUntouched() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyForgeEventFactoryBridge.registerForge(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyForgeEventFactoryBridge.GRIEFING_HELPER));

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        LegacyForgeEventFactoryBridge.registerForge(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyForgeEventFactoryBridge.GRIEFING_HELPER));
    }

    @Test
    @DisplayName("The helper passes the stack verifier")
    void helperVerifies() throws Exception {
        ClassNode helper = new ClassNode();
        new ClassReader(LegacyForgeEventFactoryBridge.generateGriefing()).accept(helper, 0);
        for (MethodNode method : helper.methods) new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
    }

    private Class<?> translatedMob() throws Exception {
        LegacyForgeEventFactoryBridge.registerForge(transformer);
        ClassLoader host = new ForgeHostStandIns()
                .add("net/minecraft/world/level/Level.java", "package net.minecraft.world.level; public class Level {}")
                .add("net/minecraft/server/level/ServerLevel.java",
                        "package net.minecraft.server.level; public class ServerLevel extends net.minecraft.world.level.Level {}")
                .add("net/minecraft/world/entity/Entity.java", "package net.minecraft.world.entity; public class Entity {}")
                .add("net/minecraftforge/eventbus/internal/Event.java", "package net.minecraftforge.eventbus.internal; public interface Event {}")
                .add("net/minecraftforge/eventbus/api/bus/EventBus.java", """
                        package net.minecraftforge.eventbus.api.bus;
                        public interface EventBus { boolean post(net.minecraftforge.eventbus.internal.Event event); }""")
                .add("net/minecraftforge/common/util/Result.java",
                        "package net.minecraftforge.common.util; public enum Result { ALLOW, DEFAULT, DENY }")
                .add("net/minecraftforge/event/entity/EntityMobGriefingEvent.java", """
                        package net.minecraftforge.event.entity;
                        import net.minecraftforge.common.util.Result;
                        public final class EntityMobGriefingEvent implements net.minecraftforge.eventbus.internal.Event {
                            public static final net.minecraftforge.eventbus.api.bus.EventBus BUS = new RecordingBus();
                            private Result result = Result.DEFAULT;
                            public EntityMobGriefingEvent(net.minecraft.world.entity.Entity entity) {}
                            public Result getResult() { return result; }
                            public void setResult(Result result) { this.result = result; }
                            public static final class RecordingBus implements net.minecraftforge.eventbus.api.bus.EventBus {
                                public String answer = "DEFAULT";
                                public int posted;
                                public boolean post(net.minecraftforge.eventbus.internal.Event event) {
                                    posted++;
                                    ((EntityMobGriefingEvent) event).setResult(Result.valueOf(answer));
                                    return false;
                                }
                            }
                        }""")
                .add("net/minecraftforge/event/ForgeEventFactory.java", """
                        package net.minecraftforge.event;
                        public class ForgeEventFactory {
                            public static boolean rule = true;
                            public static int asked;
                            public static boolean getMobGriefingEvent(net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.world.entity.Entity entity) {
                                asked++;
                                return rule;
                            }
                        }""")
                .loader(tempDir, transformer, Map.of(MOD, transformer.transformClass(mob(), MOD)));
        return host.loadClass("mod.Mob");
    }

    /** {@code static boolean grief(Level level, Entity entity) { return ForgeEventFactory.getMobGriefingEvent(level, entity); }}. */
    private static byte[] mob() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "grief", LegacyForgeEventFactoryBridge.OLD_GRIEF, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, FORGE, "getMobGriefingEvent", LegacyForgeEventFactoryBridge.OLD_GRIEF, false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
