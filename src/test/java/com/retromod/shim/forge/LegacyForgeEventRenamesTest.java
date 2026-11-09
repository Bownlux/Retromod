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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 removed EntityInteract and SaplingGrowTreeEvent; old handlers and calls land on the events that replaced them. */
class LegacyForgeEventRenamesTest {

    private static final String ENTITY_INTERACT = LegacyForgeEventRenames.ENTITY_INTERACT;
    private static final String SPECIFIC = LegacyForgeEventRenames.ENTITY_INTERACT_SPECIFIC;
    private static final String SAPLING = LegacyForgeEventRenames.SAPLING_GROW_TREE;
    private static final String FEATURE_GROW = LegacyForgeEventRenames.BLOCK_FEATURE_GROW;
    private static final String INTERACT_BASE = "net/minecraftforge/event/entity/player/PlayerInteractEvent";
    private static final String PLAYER_EVENT = "net/minecraftforge/event/entity/player/PlayerEvent";
    private static final String LEVEL_EVENT = "net/minecraftforge/event/level/LevelEvent";
    private static final String FACTORY = "net/minecraftforge/event/ForgeEventFactory";
    private static final String MOD = "com/example/OldEventMod";

    // Minecraft types in the members old mods call, unchanged from 1.20.1 to 26.3.
    private static final String PLAYER = "Lnet/minecraft/world/entity/player/Player;";
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String HAND = "Lnet/minecraft/world/InteractionHand;";
    private static final String RESULT = "Lnet/minecraft/world/InteractionResult;";
    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String VEC3 = "Lnet/minecraft/world/phys/Vec3;";
    private static final String LEVEL_ACCESSOR = "Lnet/minecraft/world/level/LevelAccessor;";
    private static final String RANDOM = "Lnet/minecraft/util/RandomSource;";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String GROW_ARGS = "(" + LEVEL_ACCESSOR + RANDOM + POS + HOLDER + ")";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void forgeTarget() {
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

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge 26.2 shim renames both events on a Forge target from 26.2")
    void shimRenamesBothEvents(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        assertEquals(SPECIFIC, transformer.getClassRedirects().get(ENTITY_INTERACT));
        assertEquals(FEATURE_GROW, transformer.getClassRedirects().get(SAPLING));
    }

    @Test
    @DisplayName("Forge 1.20.1, 1.21.1 and 26.1 targets, NeoForge, and a run with no Forge target keep the old names")
    void otherTargetsKeepTheOldNames() {
        for (String target : List.of("1.20.1", "1.21.1", "26.1")) {
            RetromodVersion.TARGET_MC_VERSION = target;
            assertNoRenames(target);
        }

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        assertNoRenames("NeoForge through the Forge shim");

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        assertNoRenames("Fabric, Quilt or an unnamed loader");
    }

    @Test
    @DisplayName("A running host is probed per event, and the new event must be there")
    void eachEventIsProbedOnItsOwn() {
        String anchor = ForgeHostShape.ANCHOR;
        LegacyForgeEventRenames.register(transformer, host(anchor, ENTITY_INTERACT, SPECIFIC, SAPLING));
        assertTrue(transformer.getClassRedirects().keySet().stream().noneMatch(this::isRenamed),
                "a Forge 1.20.1 host still ships both old events");

        LegacyForgeEventRenames.register(transformer, host(anchor, ENTITY_INTERACT, SPECIFIC, FEATURE_GROW));
        assertFalse(transformer.getClassRedirects().containsKey(ENTITY_INTERACT), "the host still ships EntityInteract");
        assertEquals(FEATURE_GROW, transformer.getClassRedirects().get(SAPLING));

        transformer.clearRedirectsForTesting();
        LegacyForgeEventRenames.register(transformer, host(anchor));
        assertFalse(transformer.getClassRedirects().keySet().stream().anyMatch(this::isRenamed),
                "a host without the new events gets no rename to them");

        LegacyForgeEventRenames.register(transformer, host(anchor, SPECIFIC, FEATURE_GROW));
        assertEquals(SPECIFIC, transformer.getClassRedirects().get(ENTITY_INTERACT));
        assertEquals(FEATURE_GROW, transformer.getClassRedirects().get(SAPLING));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("An old EntityInteract handler reads target, player, hand, level, stack and result from EntityInteractSpecific")
    void entityInteractHandlerLinks(String target) throws Exception {
        Host host = translatedOnto(target);
        Class<?> specific = host.type(SPECIFIC);
        Method handler = method(host.type(MOD), "onInteract");
        assertEquals(List.of(specific, host.type("net/minecraft/world/InteractionResult")),
                Arrays.asList(handler.getParameterTypes()), "the handler takes the host event, so the bus can bind it");

        Object player = host.newStandIn(PLAYER);
        Object hand = host.newStandIn(HAND);
        Object pig = host.newStandIn(ENTITY);
        Object consume = host.newStandIn(RESULT);
        Object event = specific.getConstructors()[0].newInstance(player, hand, pig, host.newStandIn(VEC3));

        Object[] seen = (Object[]) handler.invoke(null, event, consume);
        assertSame(pig, seen[0], "getTarget");
        assertSame(player, seen[1], "getEntity");
        assertSame(hand, seen[2], "getHand");
        assertNull(seen[3], "getLevel reached the host getter");
        assertNull(seen[4], "getItemStack reached the host getter");
        assertSame(consume, seen[5], "setCancellationResult wrote what getCancellationResult reads");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("ForgeEventFactory.blockGrowFeature returns BlockFeatureGrowEvent and the old getters and setters reach it")
    void saplingCallsLink(String target) throws Exception {
        Host host = translatedOnto(target);
        Object level = host.newStandIn(LEVEL_ACCESSOR);
        Object random = host.newStandIn(RANDOM);
        Object pos = host.newStandIn(POS);
        Object oak = host.newStandIn(HOLDER);
        Object birch = host.newStandIn(HOLDER);

        Object[] seen = (Object[]) method(host.type(MOD), "grow")
                .invoke(null, level, random, pos, oak, birch, host.newStandIn(KEY));
        Object event = seen[0];
        assertSame(host.type(FEATURE_GROW), event.getClass());
        assertSame(level, seen[1], "getLevel");
        assertSame(random, seen[2], "getRandomSource");
        assertSame(pos, seen[3], "getPos");
        assertSame(oak, seen[4], "getFeature");
        assertSame(birch, seen[5], "setFeature(Holder)");
        assertEquals("key", event.getClass().getField("lastSet").get(event), "setFeature(ResourceKey)");
    }

    @Test
    @DisplayName("No old event name is left in the translated class")
    void oldNamesAreGone() {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        Set<String> named = typesNamed(transformer.transformClass(oldMod(), MOD + ".class"));
        assertFalse(named.contains(ENTITY_INTERACT), named.toString());
        assertFalse(named.contains(SAPLING), named.toString());
        assertTrue(named.containsAll(List.of(SPECIFIC, FEATURE_GROW)), named.toString());
    }

    @Test
    @DisplayName("A 1.18 world.SaplingGrowTreeEvent reaches BlockFeatureGrowEvent through the 1.19 move")
    void worldPackageSaplingComposes() {
        LegacyForge119Bridge.register(transformer);
        LegacyForgeEventRenames.register(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onGrow",
                "(Lnet/minecraftforge/event/world/SaplingGrowTreeEvent;)" + POS, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraftforge/event/world/SaplingGrowTreeEvent", "getPos", "()" + POS, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        Set<String> named = typesNamed(transformer.transformClass(cw.toByteArray(), MOD + ".class"));
        assertTrue(named.contains(FEATURE_GROW), named.toString());
        assertFalse(named.contains(SAPLING) || named.contains("net/minecraftforge/event/world/SaplingGrowTreeEvent"),
                named.toString());
    }

    // ---- helpers ----

    private boolean isRenamed(String name) {
        return name.equals(ENTITY_INTERACT) || name.equals(SAPLING);
    }

    private void assertNoRenames(String why) {
        LegacyForgeEventRenames.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(ENTITY_INTERACT), why);
        assertFalse(transformer.getClassRedirects().containsKey(SAPLING), why);
        transformer.clearRedirectsForTesting();
    }

    /** Translates the old mod through the shims a Forge host of {@code target} runs and loads it beside that host's events. */
    private Host translatedOnto(String target) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = target;
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        if (target.equals("26.3")) new Forge_26_2_to_26_3().registerRedirects(transformer);
        Map<String, byte[]> classes = forgeHostEvents();
        classes.put(MOD, transformer.transformClass(oldMod(), MOD + ".class"));
        return new Host(classes);
    }

    /**
     * A Forge 1.20.1 mod as javac compiled it: an EntityInteract handler that reads the event and
     * sets its result, and a sapling check through ForgeEventFactory.blockGrowFeature.
     */
    private static byte[] oldMod() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onInteract",
                "(L" + ENTITY_INTERACT + ";" + RESULT + ")[Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, ENTITY_INTERACT, "setCancellationResult", "(" + RESULT + ")V", false);
        array(mv, List.of(
                read(0, ENTITY_INTERACT, "getTarget", ENTITY), read(0, ENTITY_INTERACT, "getEntity", PLAYER),
                read(0, ENTITY_INTERACT, "getHand", HAND), read(0, ENTITY_INTERACT, "getLevel", LEVEL),
                read(0, ENTITY_INTERACT, "getItemStack", STACK),
                read(0, ENTITY_INTERACT, "getCancellationResult", RESULT)));
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "grow",
                "(" + LEVEL_ACCESSOR + RANDOM + POS + HOLDER + HOLDER + KEY + ")[Ljava/lang/Object;", null, null);
        mv.visitCode();
        for (int arg = 0; arg < 4; arg++) mv.visitVarInsn(ALOAD, arg);
        mv.visitMethodInsn(INVOKESTATIC, FACTORY, "blockGrowFeature", GROW_ARGS + "L" + SAPLING + ";", false);
        mv.visitVarInsn(ASTORE, 6);
        // The last entry is getFeature after setFeature(Holder) with the fifth argument.
        Consumer<MethodVisitor> replaced = m -> {
            m.visitVarInsn(ALOAD, 6);
            m.visitVarInsn(ALOAD, 4);
            m.visitMethodInsn(INVOKEVIRTUAL, SAPLING, "setFeature", "(" + HOLDER + ")V", false);
            read(6, SAPLING, "getFeature", HOLDER).accept(m);
        };
        array(mv, List.of(m -> m.visitVarInsn(ALOAD, 6),
                read(6, SAPLING, "getLevel", LEVEL_ACCESSOR), read(6, SAPLING, "getRandomSource", RANDOM),
                read(6, SAPLING, "getPos", POS), read(6, SAPLING, "getFeature", HOLDER), replaced));
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEVIRTUAL, SAPLING, "setFeature", "(" + KEY + ")V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Pushes the result of an instance getter called on the local in {@code slot}. */
    private static Consumer<MethodVisitor> read(int slot, String owner, String name, String desc) {
        return mv -> {
            mv.visitVarInsn(ALOAD, slot);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, name, "()" + desc, false);
        };
    }

    /** Leaves an Object[] on the stack, filled by each value in order. */
    private static void array(MethodVisitor mv, List<Consumer<MethodVisitor>> values) {
        mv.visitIntInsn(BIPUSH, values.size());
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        for (int i = 0; i < values.size(); i++) {
            mv.visitInsn(DUP);
            mv.visitIntInsn(BIPUSH, i);
            values.get(i).accept(mv);
            mv.visitInsn(AASTORE);
        }
    }

    /** The host events with the members javap shows in Forge 65.0.0 and 66.1.2, which match. */
    private static Map<String, byte[]> forgeHostEvents() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(PLAYER_EVENT, hostInterface(PLAYER_EVENT, "getEntity", "()" + PLAYER));
        classes.put(LEVEL_EVENT, hostInterface(LEVEL_EVENT, "getLevel", "()" + LEVEL_ACCESSOR));

        // PlayerInteractEvent is abstract and sealed on the host; its getters live here.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, INTERACT_BASE, null, "java/lang/Object",
                new String[] {PLAYER_EVENT});
        field(cw, "player", PLAYER);
        field(cw, "hand", HAND);
        field(cw, "cancellationResult", RESULT);
        constructor(cw, INTERACT_BASE, "java/lang/Object", List.of(), new String[][] {{"player", PLAYER}, {"hand", HAND}});
        getter(cw, INTERACT_BASE, "getEntity", "player", PLAYER);
        getter(cw, INTERACT_BASE, "getHand", "hand", HAND);
        getter(cw, INTERACT_BASE, "getLevel", null, LEVEL);
        getter(cw, INTERACT_BASE, "getItemStack", null, STACK);
        getter(cw, INTERACT_BASE, "getCancellationResult", "cancellationResult", RESULT);
        setter(cw, INTERACT_BASE, "setCancellationResult", "cancellationResult", RESULT);
        classes.put(INTERACT_BASE, finish(cw));

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SPECIFIC, null, INTERACT_BASE, null);
        field(cw, "target", ENTITY);
        field(cw, "localPos", VEC3);
        constructor(cw, SPECIFIC, INTERACT_BASE, List.of(PLAYER, HAND),
                new String[][] {{"target", ENTITY}, {"localPos", VEC3}});
        getter(cw, SPECIFIC, "getTarget", "target", ENTITY);
        getter(cw, SPECIFIC, "getLocalPos", "localPos", VEC3);
        classes.put(SPECIFIC, finish(cw));

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, FEATURE_GROW, null, "java/lang/Object",
                new String[] {LEVEL_EVENT});
        String[][] growFields = {{"level", LEVEL_ACCESSOR}, {"randomSource", RANDOM}, {"pos", POS}, {"feature", HOLDER}};
        for (String[] f : growFields) field(cw, f[0], f[1]);
        cw.visitField(ACC_PUBLIC, "lastSet", "Ljava/lang/String;", null, null).visitEnd();
        constructor(cw, FEATURE_GROW, "java/lang/Object", List.of(), growFields);
        getter(cw, FEATURE_GROW, "getLevel", "level", LEVEL_ACCESSOR);
        getter(cw, FEATURE_GROW, "getRandomSource", "randomSource", RANDOM);
        getter(cw, FEATURE_GROW, "getPos", "pos", POS);
        getter(cw, FEATURE_GROW, "getFeature", "feature", HOLDER);
        setter(cw, FEATURE_GROW, "setFeature", "feature", HOLDER);
        // The key overload records that it ran; a fixture has no registry to look the key up in.
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "setFeature", "(" + KEY + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("key");
        mv.visitFieldInsn(PUTFIELD, FEATURE_GROW, "lastSet", "Ljava/lang/String;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        classes.put(FEATURE_GROW, finish(cw));

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, FACTORY, null, "java/lang/Object", null);
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blockGrowFeature", GROW_ARGS + "L" + FEATURE_GROW + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, FEATURE_GROW);
        mv.visitInsn(DUP);
        for (int arg = 0; arg < 4; arg++) mv.visitVarInsn(ALOAD, arg);
        mv.visitMethodInsn(INVOKESPECIAL, FEATURE_GROW, "<init>", GROW_ARGS + "V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        classes.put(FACTORY, finish(cw));
        return classes;
    }

    private static byte[] hostInterface(String name, String method, String desc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, name, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, method, desc, null, null).visitEnd();
        return finish(cw);
    }

    private static void field(ClassWriter cw, String name, String desc) {
        cw.visitField(ACC_PRIVATE, name, desc, null, null).visitEnd();
    }

    /** A constructor that passes {@code superArgs} to the super constructor and stores the rest in order. */
    private static void constructor(ClassWriter cw, String owner, String superName, List<String> superArgs,
            String[][] stored) {
        String superDesc = "(" + String.join("", superArgs) + ")V";
        StringBuilder desc = new StringBuilder("(").append(String.join("", superArgs));
        for (String[] f : stored) desc.append(f[1]);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", desc.append(")V").toString(), null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        for (int i = 1; i <= superArgs.size(); i++) mv.visitVarInsn(ALOAD, i);
        mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", superDesc, false);
        int slot = superArgs.size() + 1;
        for (String[] f : stored) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, slot++);
            mv.visitFieldInsn(PUTFIELD, owner, f[0], f[1]);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A getter that returns the field, or null when {@code field} is null. */
    private static void getter(ClassWriter cw, String owner, String name, String field, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()" + desc, null, null);
        mv.visitCode();
        if (field == null) {
            mv.visitInsn(ACONST_NULL);
        } else {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, owner, field, desc);
        }
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void setter(ClassWriter cw, String owner, String name, String field, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "(" + desc + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, owner, field, desc);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static byte[] finish(ClassWriter cw) {
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every type the class names, in descriptors, owners and signatures alike. */
    private static Set<String> typesNamed(byte[] bytes) {
        Set<String> named = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override
            public String map(String internalName) {
                named.add(internalName);
                return internalName;
            }
        }), 0);
        return named;
    }

    private static Method method(Class<?> owner, String name) {
        return Arrays.stream(owner.getMethods()).filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(owner.getName() + " has no " + name));
    }

    /** A host that ships exactly these classes. */
    private static Function<String, ClassNode> host(String... classes) {
        Set<String> shipped = Set.of(classes);
        return name -> {
            if (!shipped.contains(name)) return null;
            ClassNode node = new ClassNode();
            node.name = name;
            return node;
        };
    }

    /**
     * Loads the translated mod beside the host events. Each Minecraft type is an empty stand-in, and
     * a Forge class missing here fails the load, as a missing rename fails on a real host.
     */
    private static final class Host extends ClassLoader {
        private final Map<String, byte[]> classes;

        Host(Map<String, byte[]> classes) {
            super(LegacyForgeEventRenamesTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                String internal = name.replace('.', '/');
                byte[] bytes = classes.containsKey(internal) ? classes.get(internal)
                        : internal.startsWith("net/minecraft/") ? standIn(internal) : null;
                if (bytes == null) return super.loadClass(name, resolve);
                return defineClass(name, bytes, 0, bytes.length);
            }
        }

        Class<?> type(String internalName) throws ClassNotFoundException {
            return loadClass(internalName.replace('/', '.'));
        }

        Object newStandIn(String descriptor) throws Exception {
            return type(descriptor.substring(1, descriptor.length() - 1)).getConstructor().newInstance();
        }

        private static byte[] standIn(String internalName) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return finish(cw);
        }
    }
}
