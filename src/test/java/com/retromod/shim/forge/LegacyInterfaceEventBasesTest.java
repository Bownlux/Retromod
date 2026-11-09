/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.LegacyEventFixtures.Handler;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A mod's own event on a Forge class that Forge 26.2 made an interface extends Retromod's rebuilt class instead, and still posts. */
class LegacyInterfaceEventBasesTest {

    private static final String LIVING_EVENT = "net/minecraftforge/event/entity/living/LivingEvent";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String INHERITABLE = "net/minecraftforge/eventbus/api/event/InheritableEvent";
    private static final String CURIO_EVENT = "mod/CurioChangeEvent";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Each old base moves to its rebuilt class, with the super calls, and the event becomes inheritable on EventBus 7")
    void eachBaseMovesToItsRebuiltClass(String host) {
        target("forge", host);

        for (Map.Entry<String, String> base : LegacyInterfaceEventBases.BASES.entrySet()) {
            String[] parameters = constructorParameters(base.getKey());
            ClassNode out = transform(modEvent(CURIO_EVENT, base.getKey(), parameters));

            assertEquals(base.getValue(), out.superName, base.getKey());
            assertTrue(out.interfaces.contains(INHERITABLE), base.getKey() + ": listeners pass down from inheritable parents");
            List<String> superCalls = superCalls(out);
            assertEquals(List.of(base.getValue() + ".<init>", base.getValue() + ".getter"), superCalls, base.getKey());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"forge 1.21.1", "neoforge 26.2", "fabric 26.2"})
    @DisplayName("Below Forge 26.2 and on other loaders the old base is still a class, so the event keeps it")
    void otherHostsKeepTheBase(String host) {
        String[] parts = host.split(" ");
        target(parts[0], parts[1]);
        transformer.registerMethodRedirect("test/bases/Old", "old", "()V", "test/bases/New", "now", "()V");

        ClassNode out = transform(modEvent(CURIO_EVENT, LIVING_EVENT, new String[] {LIVING_ENTITY}));

        assertEquals(LIVING_EVENT, out.superName);
        assertFalse(out.interfaces.contains(INHERITABLE));
    }

    @Test
    @DisplayName("A mixin that extends the host's event mirrors its target, so it is left alone")
    void mixinsAreLeftAlone() {
        target("forge", "26.2");
        ClassNode mixin = node(modEvent("mod/mixin/LivingEventMixin", LIVING_EVENT, new String[] {LIVING_ENTITY}));
        mixin.visibleAnnotations = new ArrayList<>(List.of(new org.objectweb.asm.tree.AnnotationNode(
                "Lorg/spongepowered/asm/mixin/Mixin;")));
        ClassWriter cw = new ClassWriter(0);
        mixin.accept(cw);

        assertEquals(LIVING_EVENT, transform(cw.toByteArray()).superName);
    }

    @ParameterizedTest(name = "EventBus {0}")
    @ValueSource(strings = {"7.0.1", "7.0.6"})
    @DisplayName("On EventBus 7 the moved event posts to its old listener, keeps its entity, and is a host LivingEvent")
    void movedEventPostsOnEventBus7(String version) throws Exception {
        Path eventBus = EventBus7Host.eventBusJar(version);
        Assumptions.assumeTrue(eventBus != null, "no EventBus " + version + " jar on this machine");
        target("forge", version.equals("7.0.1") ? "26.2" : "26.3");
        EventBus7Host host = new EventBus7Host(eventBus);
        ClassLoader mod = host.mod(host, transformer, "", Map.of(
                CURIO_EVENT, modEvent(CURIO_EVENT, LIVING_EVENT, new String[] {LIVING_ENTITY}),
                "mod/CurioListener", LegacyEventFixtures.listener("mod/CurioListener",
                        Handler.of("changed", CURIO_EVENT, null))));
        host.completeLoading();
        Object bus = EventBus7Host.gameBus(mod);
        EventBus7Host.register(bus, EventBus7Host.create(mod, "mod/CurioListener"));
        host.startup(host.gameGroup());

        Class<?> livingEntity = host.type(LIVING_ENTITY);
        Object entity = livingEntity.getConstructor().newInstance();
        Object event = mod.loadClass(CURIO_EVENT.replace('/', '.')).getConstructor(livingEntity).newInstance(entity);
        assertFalse(EventBus7Host.post(bus, event));

        assertEquals(List.of("changed"), EventBus7Host.notes(mod, "mod/CurioListener"), "the old listener ran");
        assertSame(entity, event.getClass().getMethod("getEntity").invoke(event), "getEntity answers the entity it was made with");
        assertTrue(host.type(LIVING_EVENT).isInstance(event), "other code can still take it as a LivingEvent");
    }

    // ---- fixtures ----

    private void target(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        new Forge_26_1_to_26_2().registerRedirects(transformer);
    }

    /** The old base's constructor parameters, as Forge 1.20.1 declared them. */
    private static String[] constructorParameters(String base) {
        return switch (base) {
            case "net/minecraftforge/event/entity/EntityEvent" -> new String[] {"net/minecraft/world/entity/Entity"};
            case LIVING_EVENT -> new String[] {LIVING_ENTITY};
            case "net/minecraftforge/event/entity/player/PlayerEvent" -> new String[] {"net/minecraft/world/entity/player/Player"};
            case "net/minecraftforge/event/level/LevelEvent" -> new String[] {"net/minecraft/world/level/LevelAccessor"};
            default -> new String[] {"net/minecraft/world/level/LevelAccessor", "net/minecraft/core/BlockPos",
                    "net/minecraft/world/level/block/state/BlockState"};
        };
    }

    /**
     * A mod event as javac wrote it for Forge 1.20.1: {@code super(...)} with the base's arguments, and a
     * {@code getter()} that calls the base's first getter with {@code super.}, as Curios' events do.
     */
    static byte[] modEvent(String name, String base, String[] parameters) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, base, null);
        StringBuilder desc = new StringBuilder("(");
        for (String parameter : parameters) desc.append('L').append(parameter).append(';');
        desc.append(")V");
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", desc.toString(), null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        for (int i = 0; i < parameters.length; i++) init.visitVarInsn(ALOAD, i + 1);
        init.visitMethodInsn(INVOKESPECIAL, base, "<init>", desc.toString(), false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        String getter = base.endsWith("Event") && base.contains("/level/") ? "getLevel" : "getEntity";
        String returned = getter.equals("getLevel") ? "net/minecraft/world/level/LevelAccessor" : parameters[0];
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "getter", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitVarInsn(ALOAD, 0);
        get.visitMethodInsn(INVOKESPECIAL, base, getter, "()" + Type.getObjectType(returned).getDescriptor(), false);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The owner of each INVOKESPECIAL, as {@code owner.<init>} or {@code owner.getter} for any other member. */
    private static List<String> superCalls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL) {
                    calls.add(call.owner + (call.name.equals("<init>") ? ".<init>" : ".getter"));
                }
            }
        }
        return calls;
    }

    private ClassNode transform(byte[] bytes) {
        return node(transformer.transformClass(bytes, "mod/Event.class"));
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
