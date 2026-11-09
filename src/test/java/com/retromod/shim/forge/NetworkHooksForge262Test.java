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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 removed NetworkHooks; old menu and spawn calls reach the player's own openMenu and the host's packets. */
class NetworkHooksForge262Test {

    private static final String HOOKS = LegacyNetworkHooksBridge.HOOKS;
    private static final String MOD = "mod/Menus";
    private static final String L_PLAYER = "Lnet/minecraft/server/level/ServerPlayer;";
    private static final String L_PROVIDER = "Lnet/minecraft/world/MenuProvider;";
    private static final String L_POS = "Lnet/minecraft/core/BlockPos;";
    private static final String L_CONSUMER = "Ljava/util/function/Consumer;";

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
    @DisplayName("The three openScreen forms and the spawn packet call Retromod's helper on a Forge 26.2 target")
    void callsGoToTheHelper() {
        LegacyNetworkHooksBridge.register(transformer);
        List<String> calls = calls(transformer.transformClass(mod(), MOD));
        String helper = LegacyNetworkHooksBridge.HELPER;
        assertEquals(List.of(
                helper + ".openScreen(" + L_PLAYER + L_PROVIDER + ")V",
                helper + ".openScreen(" + L_PLAYER + L_PROVIDER + L_POS + ")V",
                helper + ".openScreen(" + L_PLAYER + L_PROVIDER + L_CONSUMER + ")V",
                helper + ".getEntitySpawningPacket" + LegacyNetworkHooksBridge.SPAWN_DESC), calls);
    }

    @Test
    @DisplayName("NeoForge and Forge 1.21.11 targets register nothing")
    void otherHostsKeepTheirOwnHandling() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyNetworkHooksBridge.register(transformer);
        assertNoHooksRedirect("Forge 1.21.11 still ships NetworkHooks");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        LegacyNetworkHooksBridge.register(transformer);
        assertNoHooksRedirect("NeoForge has its own menu bridge");
    }

    @Test
    @DisplayName("openScreen opens through the player's Forge openMenu, and the two-argument form writes no extra data")
    void openScreenUsesTheHostOpenMenu() throws Exception {
        LegacyNetworkHooksBridge.register(transformer);
        ClassLoader host = host();
        Class<?> menus = host.loadClass("mod.Menus");
        Object player = host.loadClass("net.minecraft.server.level.ServerPlayer").getConstructor().newInstance();
        Object pos = host.loadClass("net.minecraft.core.BlockPos").getConstructor().newInstance();

        invoke(menus, "open", player, null);
        invoke(menus, "openAt", player, null, pos);
        invoke(menus, "openWith", player, null, (Consumer<Object>) buffer -> ((List<String>) buffer).add("pos"));

        assertEquals(List.of("openMenu(provider, writer wrote [])", "openMenu(provider, pos)",
                "openMenu(provider, writer wrote [pos])"), player.getClass().getField("calls").get(player));
    }

    @Test
    @DisplayName("getEntitySpawningPacket keeps Forge's packet for extra spawn data and builds vanilla's for any other entity")
    void spawnPacketFollowsTheEntity() throws Exception {
        LegacyNetworkHooksBridge.register(transformer);
        ClassLoader host = host();
        Class<?> menus = host.loadClass("mod.Menus");
        Object plain = host.loadClass("net.minecraft.world.entity.Entity").getConstructor().newInstance();
        Object extra = host.loadClass("net.minecraft.world.entity.ExtraEntity").getConstructor().newInstance();

        assertEquals("forge spawn packet", invoke(menus, "spawn", extra).toString());
        // Forge 26.2 throws for an entity without extra data, which Forge 1.20.1 sent through its own packet.
        assertEquals("add entity 7 at 1.0,2.0,3.0 rot 4.0,5.0 data 0 head 6.0", invoke(menus, "spawn", plain).toString());
    }

    @Test
    @DisplayName("The helper passes the stack verifier")
    void helperVerifies() throws Exception {
        ClassNode helper = new ClassNode();
        new ClassReader(LegacyNetworkHooksBridge.generateHelper()).accept(helper, 0);
        assertEquals(List.of("java/util/function/Consumer"), helper.interfaces);
        for (MethodNode method : helper.methods) new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
    }

    private void assertNoHooksRedirect(String why) {
        assertTrue(transformer.getMethodRedirects().keySet().stream().noneMatch(key -> key.owner().equals(HOOKS)), why);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyNetworkHooksBridge.HELPER), why);
    }

    private ClassLoader host() throws Exception {
        return new ForgeHostStandIns()
                .add("net/minecraft/world/MenuProvider.java", "package net.minecraft.world; public interface MenuProvider {}")
                .add("net/minecraft/core/BlockPos.java", "package net.minecraft.core; public class BlockPos {}")
                .add("net/minecraftforge/common/extensions/IForgeServerPlayer.java", """
                        package net.minecraftforge.common.extensions;
                        public interface IForgeServerPlayer {
                            void openMenu(net.minecraft.world.MenuProvider provider, java.util.function.Consumer<Object> extra);
                            void openMenu(net.minecraft.world.MenuProvider provider, net.minecraft.core.BlockPos pos);
                        }""")
                .add("net/minecraft/server/level/ServerPlayer.java", """
                        package net.minecraft.server.level;
                        import java.util.ArrayList;
                        import java.util.List;
                        public class ServerPlayer implements net.minecraftforge.common.extensions.IForgeServerPlayer {
                            public final List<String> calls = new ArrayList<>();
                            public void openMenu(net.minecraft.world.MenuProvider provider, java.util.function.Consumer<Object> extra) {
                                List<String> written = new ArrayList<>();
                                extra.accept(written);
                                calls.add("openMenu(provider, writer wrote " + written + ")");
                            }
                            public void openMenu(net.minecraft.world.MenuProvider provider, net.minecraft.core.BlockPos pos) {
                                calls.add("openMenu(provider, pos)");
                            }
                        }""")
                .add("net/minecraft/network/protocol/Packet.java", "package net.minecraft.network.protocol; public interface Packet {}")
                .add("net/minecraft/world/entity/EntityType.java", "package net.minecraft.world.entity; public class EntityType {}")
                .add("net/minecraft/world/phys/Vec3.java", "package net.minecraft.world.phys; public class Vec3 {}")
                .add("net/minecraft/world/entity/Entity.java", """
                        package net.minecraft.world.entity;
                        public class Entity {
                            public int getId() { return 7; }
                            public java.util.UUID getUUID() { return new java.util.UUID(0, 1); }
                            public final double getX() { return 1; }
                            public final double getY() { return 2; }
                            public final double getZ() { return 3; }
                            public float getXRot() { return 4; }
                            public float getYRot() { return 5; }
                            public EntityType getType() { return new EntityType(); }
                            public net.minecraft.world.phys.Vec3 getDeltaMovement() { return new net.minecraft.world.phys.Vec3(); }
                            public float getYHeadRot() { return 6; }
                        }""")
                .add("net/minecraftforge/entity/IEntityAdditionalSpawnData.java",
                        "package net.minecraftforge.entity; public interface IEntityAdditionalSpawnData {}")
                .add("net/minecraft/world/entity/ExtraEntity.java", """
                        package net.minecraft.world.entity;
                        public class ExtraEntity extends Entity implements net.minecraftforge.entity.IEntityAdditionalSpawnData {}""")
                .add("net/minecraftforge/common/ForgeHooks.java", """
                        package net.minecraftforge.common;
                        import net.minecraft.network.protocol.Packet;
                        public class ForgeHooks {
                            public static Packet getEntitySpawnPacket(net.minecraft.world.entity.Entity entity) {
                                if (!(entity instanceof net.minecraftforge.entity.IEntityAdditionalSpawnData)) {
                                    throw new IllegalArgumentException(entity + " has no extra spawn data");
                                }
                                return new Packet() { public String toString() { return "forge spawn packet"; } };
                            }
                        }""")
                .add("net/minecraft/network/protocol/game/ClientboundAddEntityPacket.java", """
                        package net.minecraft.network.protocol.game;
                        public class ClientboundAddEntityPacket implements net.minecraft.network.protocol.Packet {
                            private final String summary;
                            public ClientboundAddEntityPacket(int id, java.util.UUID uuid, double x, double y, double z,
                                    float xRot, float yRot, net.minecraft.world.entity.EntityType type, int data,
                                    net.minecraft.world.phys.Vec3 motion, double headYaw) {
                                summary = "add entity " + id + " at " + x + "," + y + "," + z + " rot " + xRot + "," + yRot
                                        + " data " + data + " head " + headYaw;
                            }
                            public String toString() { return summary; }
                        }""")
                .loader(tempDir, transformer, Map.of(MOD, transformer.transformClass(mod(), MOD)));
    }

    private static Object invoke(Class<?> owner, String name, Object... args) throws Exception {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name)) return method.invoke(null, args);
        }
        throw new AssertionError("no method " + name);
    }

    /** An old mod that opens menus in the three Forge 1.20.1 ways and asks for an entity's spawn packet. */
    private static byte[] mod() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        forward(cw, "open", "openScreen", "(" + L_PLAYER + L_PROVIDER + ")V");
        forward(cw, "openAt", "openScreen", "(" + L_PLAYER + L_PROVIDER + L_POS + ")V");
        forward(cw, "openWith", "openScreen", "(" + L_PLAYER + L_PROVIDER + L_CONSUMER + ")V");
        forward(cw, "spawn", "getEntitySpawningPacket", LegacyNetworkHooksBridge.SPAWN_DESC);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public static <desc> name(args...) { return NetworkHooks.target(args...); }}. */
    private static void forward(ClassWriter cw, String name, String target, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        int count = org.objectweb.asm.Type.getArgumentTypes(desc).length;
        for (int slot = 0; slot < count; slot++) mv.visitVarInsn(ALOAD, slot);
        mv.visitMethodInsn(INVOKESTATIC, HOOKS, target, desc, false);
        mv.visitInsn(desc.endsWith(")V") ? RETURN : ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            method.instructions.forEach(insn -> {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            });
        }
        return calls;
    }
}
