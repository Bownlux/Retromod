/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.neoforge.guihost.DeltaTracker;
import com.retromod.shim.neoforge.guihost.Minecraft;
import com.retromod.shim.neoforge.guihost.RenderGuiEvent;
import com.retromod.shim.neoforge.guihost.Window;
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
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Old NeoForge and Forge event members reach the names, number types and helpers each NeoForge host has. */
class LegacyNeoForgeEventDriftTest {

    private static final String EVENT = "net/neoforged/neoforge/client/event/";
    private static final String MOD_CLASS = "test/drift/Handlers";
    private static final String READS = LegacyNeoForgeEventDrift.CLIENT_READS;
    private static final String HOOKS = LegacyNeoForgeEventDrift.HOOK_READS;
    private static final String NAME_TAGS = LegacyNeoForgeEventDrift.NAME_TAG_READS;
    private static final String WINDOW = "com/mojang/blaze3d/platform/Window";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void reset() {
        transformer.clearRedirectsForTesting();
        McReflect.setForceNeoForge(true);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        ClassResourceInspector.setOfflineHost(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("NeoForge 26.3: renamed members take the host's names")
    void renamesOn263() {
        host("26.3");
        String living = "Lnet/minecraft/world/entity/LivingEntity;";
        assertEquals(List.of(
                "INVOKEVIRTUAL net/neoforged/neoforge/event/level/BlockEvent$BlockToolModificationEvent.getItemAbility()Lnet/neoforged/neoforge/common/ItemAbility;",
                "INVOKEVIRTUAL net/neoforged/neoforge/event/entity/living/LivingChangeTargetEvent.getNewAboutToBeSetTarget()" + living,
                "INVOKEVIRTUAL net/neoforged/neoforge/event/entity/living/LivingChangeTargetEvent.setNewAboutToBeSetTarget(" + living + ")V",
                "INVOKEVIRTUAL net/neoforged/neoforge/event/entity/living/LivingChangeTargetEvent.getOriginalAboutToBeSetTarget()" + living,
                "INVOKEVIRTUAL " + EVENT + "EntityRenderersEvent$AddLayers.getPlayerRenderer(Lnet/minecraft/world/entity/player/PlayerModelType;)"
                        + "Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;",
                "INVOKEVIRTUAL " + EVENT + "RenderArmEvent.getLightCoords()I"),
                calls(transform(
                        "net/neoforged/neoforge/event/level/BlockEvent$BlockToolModificationEvent.getToolAction()Lnet/neoforged/neoforge/common/ItemAbility;",
                        "net/neoforged/neoforge/event/entity/living/LivingChangeTargetEvent.getNewTarget()" + living,
                        "net/neoforged/neoforge/event/entity/living/LivingChangeTargetEvent.setNewTarget(" + living + ")V",
                        "net/neoforged/neoforge/event/entity/living/LivingChangeTargetEvent.getOriginalTarget()" + living,
                        EVENT + "EntityRenderersEvent$AddLayers.getSkin(Lnet/minecraft/world/entity/player/PlayerModelType;)"
                                + "Lnet/minecraft/client/renderer/entity/EntityRenderer;",
                        EVENT + "RenderArmEvent.getPackedLight()I")));
        String holder = "net/neoforged/neoforge/registries/DeferredHolder";
        String create = "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/resources/ResourceKey;Ljava/lang/String;)L" + holder + ";";
        assertEquals(List.of("INVOKEVIRTUAL net/neoforged/neoforge/registries/RegisterEvent.getRegistry()Lnet/minecraft/core/Registry;",
                "INVOKESTATIC " + LegacyNeoForgeEventDrift.REGISTRY_OBJECTS + ".getHolder(L" + holder + ";)Ljava/util/Optional;",
                "INVOKESTATIC " + LegacyNeoForgeEventDrift.REGISTRY_OBJECTS + ".create" + create),
                calls(transform("net/neoforged/neoforge/registries/RegisterEvent.getVanillaRegistry()Lnet/minecraft/core/Registry;",
                        holder + ".getHolder()Ljava/util/Optional;", "static " + holder + ".create" + create)));
        String builder = "net/neoforged/neoforge/common/world/MobSpawnSettingsBuilder";
        String charge = "(Lnet/minecraft/world/entity/EntityType;DD)Lnet/minecraft/world/level/biome/MobSpawnSettings$Builder;";
        assertEquals(List.of("INVOKEVIRTUAL " + builder + ".addMobSpawnCost" + charge), calls(transform(builder + ".addMobCharge" + charge)));
    }

    @Test
    @DisplayName("NeoForge 26.3: a retyped number is converted where the old code reads or passes it")
    void numberDriftOn263() {
        host("26.3");
        ClassNode out = transform(EVENT + "ViewportEvent$ComputeCameraAngles.getPartialTick()D",
                EVENT + "ViewportEvent$ComputeFov.getFOV()D",
                EVENT + "ScreenEvent$CharacterTyped$Post.getCodePoint()C",
                "net/neoforged/neoforge/event/level/BlockEvent$FarmlandTrampleEvent.getFallDistance()F");
        assertEquals(List.of(EVENT + "ViewportEvent$ComputeCameraAngles.getPartialTick()F F2D",
                EVENT + "ViewportEvent$ComputeFov.getFOV()F F2D",
                EVENT + "ScreenEvent$CharacterTyped$Post.getCodePoint()I I2C",
                "net/neoforged/neoforge/event/level/BlockEvent$FarmlandTrampleEvent.getFallDistance()D D2F"), conversions(out));
    }

    @Test
    @DisplayName("NeoForge 26.3: partial ticks, HUD window, name tag, arm, light and held modifier reads reach the client helper")
    void clientReadsOn263() {
        host("26.3");
        List<String> calls = calls(transform(EVENT + "RenderGuiEvent$Post.getPartialTick()F",
                EVENT + "RenderGuiEvent$Pre.getWindow()L" + WINDOW + ";",
                EVENT + "CustomizeGuiOverlayEvent$Chat.getPartialTick()F",
                EVENT + "RenderArmEvent.getPlayer()Lnet/minecraft/client/player/AbstractClientPlayer;",
                EVENT + "RenderNameTagEvent.getContent()Lnet/minecraft/network/chat/Component;",
                EVENT + "RenderNameTagEvent.getEntity()Lnet/minecraft/world/entity/Entity;",
                EVENT + "RenderNameTagEvent.getPoseStack()Lcom/mojang/blaze3d/vertex/PoseStack;",
                EVENT + "RenderLivingEvent$Pre.getPackedLight()I",
                "static net/neoforged/neoforge/client/settings/KeyModifier.getActiveModifier()Lnet/neoforged/neoforge/client/settings/KeyModifier;"));
        assertEquals(List.of(
                "INVOKESTATIC " + READS + ".guiPartialTick(L" + EVENT + "RenderGuiEvent;)F",
                "INVOKESTATIC " + READS + ".guiWindow(L" + EVENT + "RenderGuiEvent;)L" + WINDOW + ";",
                "INVOKESTATIC " + READS + ".overlayPartialTick(L" + EVENT + "CustomizeGuiOverlayEvent;)F",
                "INVOKESTATIC " + READS + ".armPlayer(L" + EVENT + "RenderArmEvent;)Lnet/minecraft/client/player/AbstractClientPlayer;",
                "INVOKESTATIC " + NAME_TAGS + ".nameTagContent(L" + EVENT + "RenderNameTagEvent;)Lnet/minecraft/network/chat/Component;",
                "INVOKESTATIC " + NAME_TAGS + ".nameTagEntity(L" + EVENT + "RenderNameTagEvent;)Lnet/minecraft/world/entity/Entity;",
                "INVOKESTATIC " + NAME_TAGS + ".nameTagPoseStack(L" + EVENT + "RenderNameTagEvent;)Lcom/mojang/blaze3d/vertex/PoseStack;",
                "INVOKESTATIC " + LegacyNeoForgeEventDrift.RENDER_MEMBERS + ".getPackedLight(L" + EVENT + "RenderLivingEvent;)I",
                "INVOKESTATIC " + LegacyNeoForgeEventDrift.KEY_MODIFIERS + ".getActiveModifier()Lnet/neoforged/neoforge/client/settings/KeyModifier;"),
                calls);
        assertTrue(transformer.getSyntheticClasses().containsKey(READS), "the client helper is embedded");
        assertTrue(transformer.getSyntheticClasses().containsKey(NAME_TAGS), "the name tag helper is embedded");
    }

    @Test
    @DisplayName("NeoForge 26.3: the griefing hooks that took any level and the drops event's looting level reach the helper")
    void hooksOn263() {
        host("26.3");
        String grief = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;)Z";
        String drops = "net/neoforged/neoforge/event/entity/living/LivingDropsEvent";
        List<String> calls = calls(transform("static net/neoforged/neoforge/event/EventHooks.canEntityGrief" + grief,
                drops + ".getLootingLevel()I"));
        assertEquals(List.of("INVOKESTATIC " + HOOKS + ".canEntityGrief" + grief,
                "INVOKESTATIC " + HOOKS + ".lootingLevel(L" + drops + ";)I"), calls);
        assertTrue(transformer.getSyntheticClasses().containsKey(HOOKS));
    }

    @Test
    @DisplayName("NeoForge 26.3: the merged GUI layer ids read the layer that stands where they stood")
    void guiLayersOn263() {
        host("26.3");
        String layers = "net/neoforged/neoforge/client/gui/VanillaGuiLayers";
        assertEquals(List.of(layers + ".CONTEXTUAL_INFO_BAR", layers + ".CONTEXTUAL_INFO_BAR", layers + ".DEMO_OVERLAY", layers + ".HOTBAR"),
                fieldReads(transform("field " + layers + ".JUMP_METER", "field " + layers + ".EXPERIENCE_BAR",
                        "field " + layers + ".DEBUG_OVERLAY", "field " + layers + ".HOTBAR")));
    }

    @Test
    @DisplayName("NeoForge 26.3: the removed world worker manager and its worker type are the rebuilt ones, under both loaders' names")
    void worldWorkers() {
        host("26.3");
        java.util.Map<String, String> redirects = transformer.getClassRedirects();
        String workers = LegacyNeoForgeEventDrift.WORKERS;
        assertEquals(workers, redirects.get("net/neoforged/neoforge/common/WorldWorkerManager"));
        assertEquals(workers + "$IWorker", redirects.get("net/neoforged/neoforge/common/WorldWorkerManager$IWorker"));
        assertEquals(workers + "$IWorker", redirects.get("net/minecraftforge/common/WorldWorkerManager$IWorker"));
        assertTrue(transformer.getSyntheticClasses().containsKey(workers + "$IWorker"), "the worker type travels with the manager");
    }

    @Test
    @DisplayName("The rebuilt manager gives each worker a turn per tick, moves on when one asks to wait, and keeps a 10 ms floor")
    void workersRunOverTicks() throws Exception {
        Class<?> manager = Class.forName(LegacyNeoForgeEventDrift.WORKERS.replace('/', '.'));
        java.util.List<String> steps = new java.util.ArrayList<>();
        // The first worker has three steps and asks to wait after its second; the second has one.
        int[] firstLeft = {3};
        int[] secondLeft = {1};
        Object first = worker(() -> firstLeft[0] > 0, () -> { steps.add("first"); firstLeft[0]--; return firstLeft[0] != 1; });
        Object second = worker(() -> secondLeft[0] > 0, () -> { steps.add("second"); secondLeft[0]--; return true; });
        java.util.Deque<Object> workers = queue(manager, first, second);
        java.lang.reflect.Method tick = manager.getMethod("tick", boolean.class);
        setStatic(manager, "tickStarted", false);

        tick.invoke(null, false);
        assertEquals(List.of(), steps, "a tick end before any tick start runs nothing");
        tick.invoke(null, true);
        tick.invoke(null, false);
        assertEquals(List.of("first", "first", "second"), steps, "the first worker asks to wait, so the second takes the tick");
        tick.invoke(null, true);
        tick.invoke(null, false);
        assertEquals(List.of("first", "first", "second", "first"), steps);
        assertTrue(workers.isEmpty());

        // A tick that started long ago still gives a worker that always has work 10 ms, not one step.
        int[] busySteps = {0};
        queue(manager, worker(() -> true, () -> { busySteps[0]++; sleepOneMilli(); return true; }));
        tick.invoke(null, true);
        setStatic(manager, "tickStart", System.nanoTime() - 1_000_000_000L);
        tick.invoke(null, false);
        assertTrue(busySteps[0] >= 2, "a late tick ran " + busySteps[0] + " step(s)");
        workers.clear();
    }

    /** A worker built from two lambdas; equals and hashCode answer by identity, as the manager expects. */
    private static Object worker(java.util.function.BooleanSupplier hasWork, java.util.function.BooleanSupplier doWork) throws Exception {
        Class<?> type = Class.forName(LegacyNeoForgeEventDrift.WORKERS.replace('/', '.') + "$IWorker");
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "hasWork" -> hasWork.getAsBoolean();
                    case "doWork" -> doWork.getAsBoolean();
                    case "equals" -> proxy == args[0];
                    default -> System.identityHashCode(proxy);
                });
    }

    // Queued straight into the list, since addWorker also listens on NeoForge's bus, which this classpath lacks.
    @SuppressWarnings("unchecked")
    private static java.util.Deque<Object> queue(Class<?> manager, Object... workers) throws Exception {
        java.lang.reflect.Field field = manager.getDeclaredField("WORKERS");
        field.setAccessible(true);
        java.util.Deque<Object> queue = (java.util.Deque<Object>) field.get(null);
        queue.clear();
        queue.addAll(List.of(workers));
        return queue;
    }

    private static void setStatic(Class<?> owner, String name, Object value) throws Exception {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void sleepOneMilli() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("NeoForge 1.21.1 keeps every member that it still has")
    void olderHostKeepsItsMembers() {
        host("1.21.1");
        String layers = "net/neoforged/neoforge/client/gui/VanillaGuiLayers";
        ClassNode out = transform(EVENT + "ViewportEvent$ComputeFov.getFOV()D", EVENT + "ScreenEvent$CharacterTyped.getCodePoint()C",
                EVENT + "RenderArmEvent.getPackedLight()I", "field " + layers + ".JUMP_METER");
        assertEquals(List.of("INVOKEVIRTUAL " + EVENT + "ViewportEvent$ComputeFov.getFOV()D",
                "INVOKEVIRTUAL " + EVENT + "ScreenEvent$CharacterTyped.getCodePoint()C",
                "INVOKEVIRTUAL " + EVENT + "RenderArmEvent.getPackedLight()I"), calls(out));
        assertEquals(List.of(layers + ".JUMP_METER"), fieldReads(out));
    }

    @Test
    @DisplayName("NeoForge 1.21.11 converts the code point and field of view but keeps the viewport partial tick, a float only from 26.3")
    void hostGatesFollowEachChange() {
        host("1.21.11");
        ClassNode out = transform(EVENT + "ViewportEvent$ComputeCameraAngles.getPartialTick()D",
                EVENT + "ScreenEvent$CharacterTyped$Pre.getCodePoint()C", EVENT + "ViewportEvent$ComputeFov.setFOV(D)V");
        assertEquals(List.of("INVOKEVIRTUAL " + EVENT + "ViewportEvent$ComputeCameraAngles.getPartialTick()D",
                "INVOKEVIRTUAL " + EVENT + "ScreenEvent$CharacterTyped$Pre.getCodePoint()I",
                "INVOKEVIRTUAL " + EVENT + "ViewportEvent$ComputeFov.setFOV(F)V"), calls(out));
    }

    @Test
    @DisplayName("NeoForge 1.21.1: a NeoForge 1.20.6 HUD handler's window and float partial ticks reach the helper from the 1.20.6 to 1.21 shim")
    void guiReadsOn1211FromTheNeoForgeChain() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        new NeoForge_1_20_6_to_1_21().registerRedirects(transformer);
        assertEquals(List.of(
                "INVOKESTATIC " + READS + ".guiWindow(L" + EVENT + "RenderGuiEvent;)L" + WINDOW + ";",
                "INVOKESTATIC " + READS + ".guiPartialTick(L" + EVENT + "RenderGuiEvent;)F",
                "INVOKESTATIC " + READS + ".overlayPartialTick(L" + EVENT + "CustomizeGuiOverlayEvent;)F"),
                calls(transform(EVENT + "RenderGuiEvent$Pre.getWindow()L" + WINDOW + ";", EVENT + "RenderGuiEvent$Post.getPartialTick()F",
                        EVENT + "CustomizeGuiOverlayEvent$Chat.getPartialTick()F")));
        assertTrue(transformer.getSyntheticClasses().containsKey(READS), "the client helper is embedded");
        assertFalse(RebuiltApiClasses.tree(READS).contains(NAME_TAGS), "a 1.21.1 jar never carries the render state reads");
    }

    @Test
    @DisplayName("NeoForge 1.21.1: a Forge 1.20.1 MCreator overlay reads the window and partial tick from the helper (#410)")
    void forgeOverlayOn1211() {
        // The Forge event API shim moves Forge's event to NeoForge's, so the calls are matched under NeoForge's name.
        String forge = "net/minecraftforge/client/event/RenderGuiEvent$Pre";
        transformer.registerClassRedirect(forge, EVENT + "RenderGuiEvent$Pre");
        host("1.21.1");
        assertEquals(List.of(
                "INVOKESTATIC " + READS + ".guiWindow(L" + EVENT + "RenderGuiEvent;)L" + WINDOW + ";",
                "INVOKEVIRTUAL " + WINDOW + ".getGuiScaledWidth()I",
                "INVOKESTATIC " + READS + ".guiPartialTick(L" + EVENT + "RenderGuiEvent;)F"),
                calls(transform(forge + ".getWindow()L" + WINDOW + ";", WINDOW + ".getGuiScaledWidth()I", forge + ".getPartialTick()F")));
    }

    @Test
    @DisplayName("A host whose HUD event keeps getWindow() and the float partial tick, as NeoForge 1.20.4 does, keeps both calls")
    void hostWithTheOldGuiMembersKeepsThem() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        LegacyNeoForgeEventDrift.registerGuiReads(transformer);
        List<String> kept = List.of("INVOKEVIRTUAL " + EVENT + "RenderGuiEvent$Pre.getWindow()L" + WINDOW + ";",
                "INVOKEVIRTUAL " + EVENT + "RenderGuiEvent$Pre.getPartialTick()F");
        assertEquals(kept, calls(transform(EVENT + "RenderGuiEvent$Pre.getWindow()L" + WINDOW + ";", EVENT + "RenderGuiEvent$Pre.getPartialTick()F")));

        // The host's own shape decides over the target version where it can be read.
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        ClassWriter event = new ClassWriter(0);
        event.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, EVENT + "RenderGuiEvent", null, "java/lang/Object", null);
        event.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getWindow", "()L" + WINDOW + ";", null, null).visitEnd();
        event.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getPartialTick", "()F", null, null).visitEnd();
        event.visitEnd();
        ClassResourceInspector.setOfflineHost(hostWith(EVENT + "RenderGuiEvent", event.toByteArray()));
        transformer.clearRedirectsForTesting();
        LegacyNeoForgeEventDrift.registerGuiReads(transformer);
        assertEquals(kept, calls(transform(EVENT + "RenderGuiEvent$Pre.getWindow()L" + WINDOW + ";", EVENT + "RenderGuiEvent$Pre.getPartialTick()F")));
    }

    @Test
    @DisplayName("The rebuilt GUI reads answer the game's window and the tracker's partial tick")
    void guiReadsAnswerTheGame() throws Throwable {
        ClassLoader host = guiHost();
        Class<?> reads = host.loadClass(READS.replace('/', '.'));
        Class<?> event = host.loadClass((EVENT + "RenderGuiEvent").replace('/', '.'));
        Class<?> window = host.loadClass(WINDOW.replace('/', '.'));
        Object instance = event.getConstructor().newInstance();
        Object game = host.loadClass("net.minecraft.client.Minecraft").getField("INSTANCE").get(null);
        // Looked up one by one, since the helper's other reads name game types this loader does not have.
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        assertSame(game.getClass().getField("window").get(game),
                lookup.findStatic(reads, "guiWindow", MethodType.methodType(window, event)).invoke(instance));
        assertEquals(1.0F, (float) lookup.findStatic(reads, "guiPartialTick", MethodType.methodType(float.class, event)).invoke(instance),
                "the frame's tick, read as NeoForge's own layers read it");
    }

    // ---- fixtures ----

    /** A host that has one class, as {@code --host-jar} gives the CLI. */
    private static ClassLoader hostWith(String name, byte[] bytes) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String resource) {
                return resource.equals(name + ".class") ? new ByteArrayInputStream(bytes) : null;
            }
        };
    }

    /** The GUI stand-ins under the game's names and the rebuilt client reads, in their own loader. */
    private static ClassLoader guiHost() throws Exception {
        Map<String, String> names = new HashMap<>();
        names.put(internal(Window.class), WINDOW);
        names.put(internal(Minecraft.class), "net/minecraft/client/Minecraft");
        names.put(internal(DeltaTracker.class), "net/minecraft/client/DeltaTracker");
        names.put(internal(RenderGuiEvent.class), EVENT + "RenderGuiEvent");
        SimpleRemapper remapper = new SimpleRemapper(names);
        Map<String, byte[]> classes = new HashMap<>();
        for (Class<?> type : List.of(Window.class, Minecraft.class, DeltaTracker.class, RenderGuiEvent.class)) {
            try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(in.readAllBytes()).accept(new ClassRemapper(writer, remapper), 0);
                classes.put(names.get(internal(type)).replace('/', '.'), writer.toByteArray());
            }
        }
        try (InputStream in = LegacyNeoForgeEventDriftTest.class.getClassLoader().getResourceAsStream(READS + ".class")) {
            classes.put(READS.replace('/', '.'), in.readAllBytes());
        }
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private void host(String version) {
        RetromodVersion.TARGET_MC_VERSION = version;
        // Offline, so each row is judged by the target version; the test classpath has no NeoForge.
        NeoForgeMovedMembers.register(transformer);
    }

    /** One method per entry: a call on a null receiver, a {@code static} call, or a {@code field} read. */
    private ClassNode transform(String... calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        int index = 0;
        for (String call : calls) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call" + index++, "()V", null, null);
            mv.visitCode();
            if (call.startsWith("field ")) {
                String member = call.substring(6);
                mv.visitFieldInsn(Opcodes.GETSTATIC, member.substring(0, member.indexOf('.')), member.substring(member.indexOf('.') + 1),
                        "Lnet/minecraft/resources/Identifier;");
                mv.visitInsn(Opcodes.POP);
            } else {
                boolean isStatic = call.startsWith("static ");
                String member = isStatic ? call.substring(7) : call;
                String owner = member.substring(0, member.indexOf('.'));
                String name = member.substring(member.indexOf('.') + 1, member.indexOf('('));
                String desc = member.substring(member.indexOf('('));
                if (!isStatic) {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
                }
                for (Type argument : Type.getArgumentTypes(desc)) pushDefault(mv, argument);
                mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, owner, name, desc, false);
                int size = Type.getReturnType(desc).getSize();
                if (size > 0) mv.visitInsn(size == 2 ? Opcodes.POP2 : Opcodes.POP);
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        byte[] out = transformer.transformClass(bytes, MOD_CLASS);
        ClassNode node = new ClassNode();
        new ClassReader(out != null ? out : bytes).accept(node, 0);
        return node;
    }

    private static void pushDefault(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
            case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
            case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
            case Type.OBJECT, Type.ARRAY -> mv.visitInsn(Opcodes.ACONST_NULL);
            default -> mv.visitInsn(Opcodes.ICONST_0);
        }
    }

    /** Each method's calls, in method order, as {@code OPCODE owner.name desc}. */
    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    String opcode = switch (call.getOpcode()) {
                        case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
                        case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
                        default -> "INVOKEVIRTUAL";
                    };
                    calls.add(opcode + " " + call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }

    /** Each call followed by the conversion right after or before it, as {@code owner.name desc OPCODE}. */
    private static List<String> conversions(ClassNode node) {
        List<String> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                AbstractInsnNode next = call.getNext();
                String conversion = next instanceof InsnNode cast && cast.getOpcode() != Opcodes.POP && cast.getOpcode() != Opcodes.POP2
                        && cast.getOpcode() != Opcodes.RETURN ? org.objectweb.asm.util.Printer.OPCODES[cast.getOpcode()] : "none";
                found.add(call.owner + "." + call.name + call.desc + " " + conversion);
            }
        }
        return found;
    }

    private static List<String> fieldReads(ClassNode node) {
        List<String> reads = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) reads.add(field.owner + "." + field.name);
            }
        }
        return reads;
    }
}
