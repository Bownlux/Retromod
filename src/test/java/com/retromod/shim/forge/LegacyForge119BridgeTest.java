/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forge 1.18.2 handlers and calls that Forge 1.19 renamed (#311). */
class LegacyForge119BridgeTest {

    private static final String MOD_CLASS = "com/example/ModEvents";
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForceNeoForge = McReflect.isForceNeoForge();
    private final boolean savedForceForge = McReflect.isForceForge();
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
        McReflect.setForceForge(savedForceForge);
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

    /**
     * Useful Slime, a 1.12.2 mod on NeoForge 1.21.1: its fall handler's event reached this pass
     * under NeoForge's package, where only the Forge owner had the getter rename.
     */
    @Test
    void livingGetterIsRenamedUnderNeoForgeOwner() {
        String event = "net/neoforged/neoforge/event/entity/living/LivingEvent";
        List<String> result = transform(handler(event, "getEntityLiving",
                "()Lnet/minecraft/world/entity/LivingEntity;"));

        assertTrue(result.contains("CALL " + event + ".getEntity()Lnet/minecraft/world/entity/LivingEntity;"),
                "getEntityLiving became getEntity, saw " + result);
    }

    @Test
    @DisplayName("on NeoForge a Forge 1.18.2 or 1.12.2 player getter becomes getEntity, and the random view ships")
    void neoForgeGettersAndRandomView() {
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_1_18_2_to_1_19().registerRedirects(transformer);
        String event = "net/neoforged/neoforge/event/entity/player/PlayerEvent";
        String player = "()Lnet/minecraft/world/entity/player/Player;";
        for (String getter : new String[] {"getPlayer", "getEntityPlayer"}) {
            List<String> result = transform(handler(event, getter, player));
            assertTrue(result.contains("CALL " + event + ".getEntity" + player), getter + " became getEntity, saw " + result);
        }
        List<String> level = transform(handler("net/neoforged/neoforge/event/level/BlockEvent", "getWorld",
                "()Lnet/minecraft/world/level/LevelAccessor;"));
        assertTrue(level.contains("CALL net/neoforged/neoforge/event/level/BlockEvent.getLevel()Lnet/minecraft/world/level/LevelAccessor;"),
                level.toString());
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyRandomSourceAdapter.VIEW),
                "entity and level randoms are vanilla, so NeoForge mods read them through the view too");
    }

    @Test
    @DisplayName("offline, a handler typed by a NeoForge event subclass is proven a PlayerEvent through the --host-jar jars")
    void neoForgeSubclassProvenThroughHostJars(@TempDir Path dir) throws Exception {
        String base = "net/neoforged/neoforge/event/entity/player/PlayerEvent";
        String loggedIn = base + "$PlayerLoggedInEvent";
        Path jar = dir.resolve("neoforge-universal.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String[] type : new String[][]{{base, "java/lang/Object"}, {loggedIn, base}}) {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, type[0], null, type[1], null);
                writer.visitEnd();
                out.putNextEntry(new JarEntry(type[0] + ".class"));
                out.write(writer.toByteArray());
                out.closeEntry();
            }
        }
        ClassLoader saved = ClassResourceInspector.offlineHost();
        ClassResourceInspector.setOfflineHost(new URLClassLoader(new URL[]{jar.toUri().toURL()}, null));
        try {
            McReflect.setForceNeoForge(true);
            transformer.clearRedirectsForTesting();
            new Forge_1_18_2_to_1_19().registerRedirects(transformer);
            String player = "()Lnet/minecraft/world/entity/player/Player;";
            List<String> result = transform(handler(loggedIn, "getPlayer", player));
            assertTrue(result.contains("CALL " + base + ".getEntity" + player), "saw " + result);
        } finally {
            ClassResourceInspector.setOfflineHost(saved);
        }
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
    @DisplayName("the 1.18 partial tick getter follows the host's ViewportEvent, not the target version")
    void partialTickFollowsTheHostShape(@TempDir Path dir) throws Exception {
        String forge = "net/minecraftforge/client/event/";
        String neo = "net/neoforged/neoforge/client/event/";
        String camera = "EntityViewRenderEvent$CameraSetup";
        // NeoForge 21.11 to 26.2 return a double and 26.3 a float; each target disagrees with its host on purpose.
        List<String> neoDouble = partialTickOn(dir.resolve("neo-double.jar"), true, "()D", false, "26.3", camera);
        assertTrue(neoDouble.contains("CALL " + neo + "ViewportEvent$ComputeCameraAngles.getPartialTick()D"), "saw " + neoDouble);
        assertFalse(neoDouble.contains("F2D"), "a double getter needs no widening, saw " + neoDouble);
        List<String> neoFloat = partialTickOn(dir.resolve("neo-float.jar"), true, "()F", false, "26.2", camera);
        assertTrue(neoFloat.contains("CALL " + neo + "ViewportEvent$ComputeCameraAngles.getPartialTick()F"), "saw " + neoFloat);
        assertTrue(neoFloat.contains("F2D"), "the float is widened back to the double old code reads, saw " + neoFloat);

        // Forge 1.20.1 returns a double from the base class; Forge 26.2 made ViewportEvent an interface with a float.
        List<String> forgeDouble = partialTickOn(dir.resolve("forge-double.jar"), false, "()D", false, "26.2", camera);
        assertTrue(forgeDouble.contains("CALL " + forge + "ViewportEvent.getPartialTick()D"), "saw " + forgeDouble);
        assertFalse(forgeDouble.contains("F2D"), "saw " + forgeDouble);
        List<String> forgeFloat = partialTickOn(dir.resolve("forge-float.jar"), false, "()F", true, "1.20.1", camera);
        assertTrue(forgeFloat.contains("CALL " + forge + "ViewportEvent$ComputeCameraAngles.getPartialTick()F"), "saw " + forgeFloat);
        assertTrue(forgeFloat.contains("F2D"), "saw " + forgeFloat);
        List<String> forgeBase = partialTickOn(dir.resolve("forge-base.jar"), false, "()F", true, "26.2", "EntityViewRenderEvent");
        assertTrue(forgeBase.contains("ITF " + forge + "ViewportEvent.getPartialTick()F"),
                "the interface's getter is called as an interface method, saw " + forgeBase);
    }

    @Test
    @DisplayName("an unreadable host is judged by the first version whose getter returns a float")
    void partialTickWithoutAHostFollowsTheTarget() {
        assertTrue(LegacyForgeClientEvents119.partialTickIsFloat(null, "26.3", LegacyForgeClientEvents119.NEO_FLOAT_TICK_SINCE));
        assertFalse(LegacyForgeClientEvents119.partialTickIsFloat(null, "26.2", LegacyForgeClientEvents119.NEO_FLOAT_TICK_SINCE));
        assertTrue(LegacyForgeClientEvents119.partialTickIsFloat(null, "26.2", LegacyForgeClientEvents119.FORGE_FLOAT_TICK_SINCE));
        assertFalse(LegacyForgeClientEvents119.partialTickIsFloat(null, "1.21.1", LegacyForgeClientEvents119.FORGE_FLOAT_TICK_SINCE));
        assertFalse(LegacyForgeClientEvents119.partialTickIsFloat(null, null, LegacyForgeClientEvents119.FORGE_FLOAT_TICK_SINCE));
    }

    @Test
    @DisplayName("Forge 1.18's config screen factory record is moved to ConfigScreenHandler")
    void configGuiFactoryMovesToConfigScreenHandler() {
        List<String> result = transform(handler("net/minecraftforge/client/ConfigGuiHandler$ConfigGuiFactory",
                "screenFunction", "()Ljava/util/function/BiFunction;"));
        assertTrue(result.contains("PARAM net/minecraftforge/client/ConfigScreenHandler$ConfigScreenFactory"), "saw " + result);
    }

    @Test
    @DisplayName("the private entity field rewrite takes mods built before 1.20, and a versionless mod only on Forge")
    void entityLevelWriteFollowsTheModVersion() {
        String write = "PUTFIELD " + LegacyEntityPrivateFieldAdapter.ENTITY + ".level";
        String setter = "CALL " + LegacyEntityPrivateFieldAdapter.ENTITY + ".setLevel(" + LegacyEntityPrivateFieldAdapter.LEVEL_DESC + ")V";
        // A 1.20.1 mod that opened Entity.level with an access transformer writes the field it can reach (Steam 'n' Rails).
        assertTrue(entityLevelWrite("1.20.1", true).contains(write));
        assertTrue(entityLevelWrite("1.20.1", false).contains(write));
        assertTrue(entityLevelWrite("1.18.2", true).contains(setter), "a 1.18.2 mod on NeoForge writes through the setter");
        assertTrue(entityLevelWrite("1.18.2", false).contains(setter));
        assertTrue(entityLevelWrite(null, false).contains(setter), "Forge keeps the rewrite for a mod that names no version");
        assertTrue(entityLevelWrite(null, true).contains(write), "NeoForge leaves a mod that names no version alone");
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

    /** What {@code static void move(Entity e, Level l) { e.level = l; }} does after translation for a mod built for {@code version}. */
    private List<String> entityLevelWrite(String version, boolean neoForge) {
        McReflect.setForceNeoForge(neoForge);
        transformer.clearRedirectsForTesting();
        new Forge_1_18_2_to_1_19().registerRedirects(transformer);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        String entity = LegacyEntityPrivateFieldAdapter.ENTITY;
        String level = LegacyEntityPrivateFieldAdapter.LEVEL_DESC;
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "move",
                "(L" + entity + ";" + level + ")V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitFieldInsn(Opcodes.PUTFIELD, entity, "level", level);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        transformer.setModSourceVersion(version);
        try {
            return transform(writer.toByteArray());
        } finally {
            transformer.setModSourceVersion(null);
        }
    }

    /** The calls a 1.18 partial tick handler makes after translation against a host whose ViewportEvent returns {@code desc}. */
    private List<String> partialTickOn(Path jar, boolean neoForge, String desc, boolean isInterface, String target,
            String oldEvent) throws Exception {
        String viewport = (neoForge ? "net/neoforged/neoforge/client/event/" : "net/minecraftforge/client/event/") + "ViewportEvent";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | (isInterface ? Opcodes.ACC_INTERFACE : 0),
                    viewport, null, "java/lang/Object", null);
            writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getPartialTick", desc, null, null).visitEnd();
            writer.visitEnd();
            out.putNextEntry(new JarEntry(viewport + ".class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        ClassLoader saved = ClassResourceInspector.offlineHost();
        ClassResourceInspector.setOfflineHost(new URLClassLoader(new URL[]{jar.toUri().toURL()}, null));
        try {
            RetromodVersion.TARGET_MC_VERSION = target;
            McReflect.setForceNeoForge(neoForge);
            McReflect.setForceForge(!neoForge);
            transformer.clearRedirectsForTesting();
            new Forge_1_18_2_to_1_19().registerRedirects(transformer);
            return transform(handler("net/minecraftforge/client/event/" + oldEvent, "getPartialTicks", "()D"));
        } finally {
            ClassResourceInspector.setOfflineHost(saved);
        }
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
                                if (itf) seen.add("ITF " + owner + "." + called + desc);
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.F2D) seen.add("F2D");
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                                if (opcode == Opcodes.PUTFIELD) seen.add("PUTFIELD " + owner + "." + field);
                            }
                        };
                    }
                }, 0);
        return seen;
    }
}
