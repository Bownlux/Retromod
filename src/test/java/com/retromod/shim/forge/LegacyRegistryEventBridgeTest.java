/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #412 (Ancient Revival, Forge 1.18.2 on Forge 1.20.1): its menu registers from a RegistryEvent.Register handler, which Forge 1.19 removed. */
class LegacyRegistryEventBridgeTest {

    private static final String MENUS = "net/mcreator/ancientrevivalreboot/init/AncientRevivalRebootModMenus";

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
    @DisplayName("each @Mod constructor forwards the registries the jar's handlers listen to")
    void planForwardsTheHandlersRegistries() {
        byte[] menus = handlerClass(LegacyRegistryEventBridge.OLD_EVENT);
        ModConstructorHooks.JarPlan plan = LegacyRegistryEventBridge.plan(Map.of(MENUS, menus),
                Map.of("net/mcreator/ancientrevivalreboot/AncientRevivalRebootMod", "ancient_revival_reboot"));
        assertNotNull(plan);
        List<ModConstructorHooks.Call> calls = plan.callsFor("net/mcreator/ancientrevivalreboot/AncientRevivalRebootMod",
                "ancient_revival_reboot");
        assertEquals(1, calls.size());
        assertEquals("forwardRegistry", calls.get(0).name());
        assertEquals(List.of("ancient_revival_reboot", "menu"), calls.get(0).arguments());
    }

    @Test
    @DisplayName("a handler for one registry takes that registry's own stand-in event, so the bus filters it")
    void handlerIsRetypedToItsRegistry() {
        byte[] transformed = LegacyRegistryEventBridge.retypeHandlers(handlerClass(Forge1122EventBridge.REGISTER_EVENT));
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        MethodNode handler = node.methods.stream().filter(m -> m.name.equals("registerContainers")).findFirst().orElseThrow();
        assertEquals("(L" + LegacyRegistryEventBridge.eventFor("menu") + ";)V", handler.desc);
        assertNull(handler.signature, "the stand-in has no type parameter to fill");
        new ClassReader(transformed).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    @DisplayName("BoP-style mixed handlers: unknown, raw and wildcard handlers leave the shared stand-in for an unposted event")
    void unknownHandlersNeverReceiveAnotherRegistrysEvent() {
        String standIn = Forge1122EventBridge.REGISTER_EVENT;
        byte[] mixed = handlerClass(standIn, Map.of(
                "registerBlocks", "Lnet/minecraft/world/level/block/Block;",
                "registerPaintings", "Lnet/minecraft/world/entity/decoration/Motive;",
                "registerBiomes", "Lnet/minecraft/world/level/biome/Biome;",
                "registerAnything", "*",
                "registerRaw", ""));
        ClassNode node = new ClassNode();
        new ClassReader(LegacyRegistryEventBridge.retypeHandlers(mixed)).accept(node, 0);
        Map<String, String> expected = Map.of(
                "registerBlocks", LegacyRegistryEventBridge.eventFor("block"),
                "registerPaintings", LegacyRegistryEventBridge.UNBRIDGED,
                "registerBiomes", LegacyRegistryEventBridge.UNBRIDGED,
                "registerAnything", LegacyRegistryEventBridge.UNBRIDGED,
                "registerRaw", LegacyRegistryEventBridge.UNBRIDGED);
        for (MethodNode method : node.methods) {
            assertEquals("(L" + expected.get(method.name) + ";)V", method.desc,
                    method.name + " must not keep the stand-in, which every registry's event extends");
        }

        // A jar whose handlers are all unknown still gets a plan, so its handlers are retyped, but forwards nothing.
        byte[] unknownOnly = handlerClass(LegacyRegistryEventBridge.OLD_EVENT,
                Map.of("registerBiomes", "Lnet/minecraft/world/level/biome/Biome;"));
        ModConstructorHooks.JarPlan plan = LegacyRegistryEventBridge.plan(Map.of(MENUS, unknownOnly),
                Map.of("example/Mod", "example"));
        assertNotNull(plan);
        assertTrue(plan.callsFor("example/Mod", "example").isEmpty());
    }

    @Test
    @DisplayName("a jar that also adds Register listeners with addGenericListener is left alone, since those would get every posted event")
    void genericListenersKeepTheJarUnbridged() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "example/Setup", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "init", "(Lnet/minecraftforge/eventbus/api/IEventBus;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType("net/minecraft/world/item/Item"));
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraftforge/eventbus/api/IEventBus", "addGenericListener",
                "(Ljava/lang/Class;Ljava/util/function/Consumer;)V", true);
        mv.visitInsn(ACONST_NULL);
        mv.visitTypeInsn(CHECKCAST, LegacyRegistryEventBridge.OLD_EVENT);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        assertNull(LegacyRegistryEventBridge.plan(Map.of(MENUS, handlerClass(LegacyRegistryEventBridge.OLD_EVENT),
                "example/Setup", cw.toByteArray()), Map.of("example/Mod", "example")));
    }

    @Test
    @DisplayName("a mod with no registry handlers gets no plan, and a jar without the stand-in event no bridge")
    void nothingToForward() {
        assertNull(LegacyRegistryEventBridge.plan(Map.of(), Map.of()));
        LegacyRegistryEventBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyRegistryEventBridge.eventFor("menu")),
                "offline there is no stand-in Register event to extend");
    }

    @Test
    @DisplayName("with the host's stand-in event, every registry gets its own subclass")
    void perRegistryEventsExtendTheStandIn() {
        transformer.registerSyntheticClass(Forge1122EventBridge.REGISTER_EVENT, Forge1122EventBridge.registerEventBytes(
                "net/minecraftforge/eventbus/api/Event", false, "net/minecraftforge/fml/event/IModBusEvent"));
        LegacyRegistryEventBridge.register(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyRegistryEventBridge.UNBRIDGED));
        byte[] menu = transformer.getSyntheticClasses().get(LegacyRegistryEventBridge.eventFor("menu"));
        assertNotNull(menu);
        assertEquals(Forge1122EventBridge.REGISTER_EVENT, new ClassReader(menu).getSuperName());
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyRegistryEventBridge.eventFor("worldgen/feature")));
        new ClassReader(menu).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    /** A MOD-bus subscriber with one Register handler typed {@code eventType}, as MCreator 1.18.2 emits it. */
    private static byte[] handlerClass(String eventType) {
        return handlerClass(eventType, Map.of("registerContainers", "Lnet/minecraft/world/inventory/MenuType<*>;"));
    }

    /** A subscriber with one handler per name; the value is its type argument, "*" for a wildcard, empty for raw. */
    private static byte[] handlerClass(String eventType, Map<String, String> handlers) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MENUS, null, "java/lang/Object", null);
        handlers.forEach((name, argument) -> {
            String signature = argument.isEmpty() ? null : "(L" + eventType + "<" + argument + ">;)V";
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + eventType + ";)V", signature, null);
            AnnotationVisitor subscribe = mv.visitAnnotation(LegacyRegistryEventBridge.SUBSCRIBE, true);
            subscribe.visitEnd();
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, eventType, "getRegistry", "()Lnet/minecraftforge/registries/IForgeRegistry;", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        cw.visitEnd();
        return cw.toByteArray();
    }
}
