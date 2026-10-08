/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forge 1.18.2 handlers and calls that Forge 1.19 renamed (#311). */
class LegacyForge119BridgeTest {

    private static final String MOD_CLASS = "com/example/ModEvents";
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForceNeoForge = McReflect.isForceNeoForge();
    private RetromodTransformer transformer;

    @BeforeEach
    void registerForgeShim() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        McReflect.setForceNeoForge(false);
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Forge_1_18_2_to_1_19().registerRedirects(transformer);
    }

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedForceNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    void entityJoinHandlerTargetsTheLevelEventAndGetter() {
        String oldEvent = "net/minecraftforge/event/entity/EntityJoinWorldEvent";
        List<String> result = transform(handler(oldEvent, "getWorld",
                "()Lnet/minecraft/world/level/Level;"));

        assertTrue(result.contains("PARAM net/minecraftforge/event/entity/EntityJoinLevelEvent"),
                "the handler must subscribe to the renamed event, saw " + result);
        assertTrue(result.contains("CALL net/minecraftforge/event/entity/EntityJoinLevelEvent"
                + ".getLevel()Lnet/minecraft/world/level/Level;"),
                "getWorld became getLevel, saw " + result);
    }

    @Test
    void spawnCheckHandlerTargetsPositionCheck() {
        String oldEvent = "net/minecraftforge/event/entity/living/LivingSpawnEvent$CheckSpawn";
        List<String> result = transform(handler(oldEvent, "getSpawnReason",
                "()Lnet/minecraft/world/entity/MobSpawnType;"));

        String newEvent = "net/minecraftforge/event/entity/living/MobSpawnEvent$PositionCheck";
        assertTrue(result.contains("PARAM " + newEvent), "saw " + result);
        assertTrue(result.contains("CALL " + newEvent
                + ".getSpawnType()Lnet/minecraft/world/entity/MobSpawnType;"),
                "getSpawnReason became getSpawnType, saw " + result);
    }

    @Test
    void clientHandlersTargetTheRenamedEvents() {
        String client = "net/minecraftforge/client/event/";
        List<String> camera = transform(handler(client + "EntityViewRenderEvent$CameraSetup",
                "getPartialTicks", "()D"));
        assertTrue(camera.contains("PARAM " + client + "ViewportEvent$ComputeCameraAngles"), "saw " + camera);
        assertTrue(camera.contains("CALL " + client + "ViewportEvent.getPartialTick()D"),
                "getPartialTicks became getPartialTick, saw " + camera);

        List<String> screen = transform(screenSetter(client + "ScreenOpenEvent"));
        assertTrue(screen.contains("PARAM " + client + "ScreenEvent$Opening"), "saw " + screen);
        assertTrue(screen.contains("CALL " + client + "ScreenEvent$Opening.setNewScreen("
                + "Lnet/minecraft/client/gui/screens/Screen;)V"), "the replacement screen is set, saw " + screen);
    }

    @Test
    void newerOpeningHandlerKeepsItsInheritedGetScreen() {
        String opening = "net/minecraftforge/client/event/ScreenEvent$Opening";
        List<String> screen = transform(handler(opening, "getScreen",
                "()Lnet/minecraft/client/gui/screens/Screen;"));
        assertTrue(screen.contains("CALL " + opening + ".getScreen()Lnet/minecraft/client/gui/screens/Screen;"),
                "a 1.19.2 mod's call to the inherited ScreenEvent.getScreen must not become getNewScreen, saw "
                        + screen);
    }

    @Test
    void biomeLoadingHandlerRegistersAgainstAStandIn() {
        List<String> result = transform(handler(LegacyForge119Bridge.BIOME_LOADING_EVENT,
                "getName", "()Lnet/minecraft/resources/ResourceLocation;"));

        assertTrue(result.contains("PARAM " + LegacyForge119Bridge.BIOME_LOADING_STAND_IN),
                "a deleted event type made Forge reject every subscriber in the class, saw "
                        + result);
        assertTrue(transformer.getSyntheticClasses()
                        .containsKey(LegacyForge119Bridge.BIOME_LOADING_STAND_IN),
                "the stand-in must be available for embedding");
        assertEquals("net/minecraftforge/eventbus/api/Event",
                new ClassReader(LegacyForge119Bridge.generateBiomeLoadingStandIn()).getSuperName(),
                "Forge only accepts Event subclasses as handler parameters");
    }

    @Test
    void translatableComponentWithoutArgumentsBecomesFactoryCall() {
        String chat = "net/minecraft/network/chat/";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe", "()Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, chat + "TranslatableComponent");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("iob.dragonAir.needSaddle");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, chat + "TranslatableComponent", "<init>",
                "(Ljava/lang/String;)V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();

        List<String> result = transform(writer.toByteArray());
        assertTrue(result.contains("CALL " + chat + "Component.translatable(Ljava/lang/String;)L"
                + chat + "MutableComponent;"), "saw " + result);
        assertTrue(result.stream().noneMatch(entry -> entry.contains("<init>")),
                "no constructor call on the deleted class may remain, saw " + result);
    }

    @Test
    void packetConsumerBecomesNetworkThreadConsumer() {
        String builder = "net/minecraftforge/network/simple/SimpleChannel$MessageBuilder";
        String desc = "(Ljava/util/function/BiConsumer;)L" + builder + ";";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe", "(L" + builder + ";Ljava/util/function/BiConsumer;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "consumer", desc, false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();

        assertTrue(transform(writer.toByteArray())
                .contains("CALL " + builder + ".consumerNetworkThread" + desc),
                "consumer was renamed consumerNetworkThread in Forge 1.19");
    }

    @Test
    void registryEventStubCanBeAModBusEvent() {
        String marker = "net/minecraftforge/fml/event/IModBusEvent";
        ClassReader stub = new ClassReader(Forge_1_12_2_to_1_13_2.eventClassBytes(
                "com/retromod/generated/forge1122/RegistryEvent$Register",
                "net/minecraftforge/eventbus/api/Event", false, marker));
        assertEquals("net/minecraftforge/eventbus/api/Event", stub.getSuperName());
        assertTrue(List.of(stub.getInterfaces()).contains(marker),
                "a 1.18.2 MOD-bus subscriber is rejected unless the parameter is an IModBusEvent");
    }

    @Test
    void itemHandlerCapabilityReadsForgeCapabilities() {
        String capability = "Lnet/minecraftforge/common/capabilities/Capability;";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe", "()Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/items/CapabilityItemHandler",
                "ITEM_HANDLER_CAPABILITY", capability);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();

        List<String> reads = new ArrayList<>();
        new ClassReader(transformer.transformClass(writer.toByteArray(), MOD_CLASS))
                .accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                                reads.add(owner + "." + field);
                            }
                        };
                    }
                }, 0);
        assertEquals(List.of("net/minecraftforge/common/capabilities/ForgeCapabilities.ITEM_HANDLER"), reads,
                "the item handler token moved to ForgeCapabilities in Forge 1.19.2");
    }

    /** {@code static void on(Event event) { event.<getter>(); }} */
    private static byte[] handler(String eventType, String getter, String getterDesc) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "on", "(L" + eventType + ";)V", null, null);
        method.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, eventType, getter, getterDesc, false);
        method.visitInsn(Type.getReturnType(getterDesc).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A handler that replaces the screen being opened with nothing. */
    private static byte[] screenSetter(String eventType) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "on", "(L" + eventType + ";)V", null, null);
        method.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, eventType, "setScreen",
                "(Lnet/minecraft/client/gui/screens/Screen;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Handler parameter types and calls in the transformed class. */
    private List<String> transform(byte[] classBytes) {
        List<String> seen = new ArrayList<>();
        new ClassReader(transformer.transformClass(classBytes, MOD_CLASS))
                .accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        for (Type argument : Type.getArgumentTypes(descriptor)) {
                            seen.add("PARAM " + argument.getInternalName());
                        }
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String called,
                                    String desc, boolean itf) {
                                seen.add("CALL " + owner + "." + called + desc);
                            }
                        };
                    }
                }, 0);
        return seen;
    }
}
