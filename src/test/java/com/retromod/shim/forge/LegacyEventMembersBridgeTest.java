/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.RenderStateLookupHost;
import com.retromod.shim.neoforge.NeoForgeMovedMembers;
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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Old render and screen event members reach Retromod's helpers on the hosts that removed them, and the helpers answer. */
class LegacyEventMembersBridgeTest {

    private static final String FORGE_EVENT = "net/minecraftforge/client/event/";
    private static final String NEO_EVENT = "net/neoforged/neoforge/client/event/";
    private static final String LIVING = "()Lnet/minecraft/world/entity/LivingEntity;";
    private static final String MOD_CLASS = "test/members/Handlers";
    private static final String FORGE_RENDER_MEMBERS = LegacyForgeEventMembersBridge.RENDER_MEMBERS;
    private static final String NEO_RENDER_MEMBERS = RebuiltApiClasses.ROOT + "neoforge/client/event/RenderLivingMembers";
    private static final String HELD = LegacyForgeEventMembersBridge.HELD_MODIFIERS;
    private static final String KEY_MEMBERS = LegacyForgeEventMembersBridge.KEY_MEMBERS;

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
    @DisplayName("Forge: getEntity, a typed character's getModifiers and getScrollDelta reach their replacements on the event and its records")
    void forgeMembers(String host) {
        forge(host);

        ClassNode out = transform(caller(
                FORGE_EVENT + "RenderLivingEvent.getEntity" + LIVING,
                FORGE_EVENT + "RenderLivingEvent$Pre.getEntity" + LIVING,
                FORGE_EVENT + "RenderLivingEvent$Post.getEntity" + LIVING,
                FORGE_EVENT + "ScreenEvent$CharacterTyped.getModifiers()I",
                FORGE_EVENT + "ScreenEvent$CharacterTyped$Pre.getModifiers()I",
                FORGE_EVENT + "ScreenEvent$MouseScrolled$Post.getScrollDelta()D",
                FORGE_EVENT + "ScreenEvent$MouseScrolled.getScrollDelta()D",
                FORGE_EVENT + "InputEvent$MouseScrollingEvent.getScrollDelta()D"));

        List<String> calls = calls(out);
        String toEntity = "INVOKESTATIC " + FORGE_RENDER_MEMBERS + ".getEntity(L" + FORGE_EVENT + "RenderLivingEvent;)"
                + LIVING.substring(2);
        assertEquals(List.of(toEntity, toEntity, toEntity,
                "INVOKESTATIC " + HELD + ".modifiers(Ljava/lang/Object;)I",
                "INVOKESTATIC " + HELD + ".modifiers(Ljava/lang/Object;)I",
                "INVOKEVIRTUAL " + FORGE_EVENT + "ScreenEvent$MouseScrolled$Post.getDeltaY()D",
                "INVOKEINTERFACE " + FORGE_EVENT + "ScreenEvent$MouseScrolled.getDeltaY()D",
                "INVOKEVIRTUAL " + FORGE_EVENT + "InputEvent$MouseScrollingEvent.getDeltaY()D"), calls);
        assertTrue(transformer.getSyntheticClasses().containsKey(FORGE_RENDER_MEMBERS), "the render helper is embedded");
        assertTrue(transformer.getSyntheticClasses().containsKey(HELD), "the modifier helper is embedded");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Forge: getScanCode stays on 26.2, which still has it, and goes to the keycode helper on 26.3")
    void forgeScanCode(String host) {
        forge(host);

        List<String> calls = calls(transform(caller(
                FORGE_EVENT + "ScreenEvent$KeyPressed$Pre.getScanCode()I",
                FORGE_EVENT + "ScreenEvent$KeyReleased.getScanCode()I",
                FORGE_EVENT + "InputEvent$Key.getScanCode()I")));

        if (host.equals("26.2")) {
            assertEquals(List.of("INVOKEVIRTUAL " + FORGE_EVENT + "ScreenEvent$KeyPressed$Pre.getScanCode()I",
                    "INVOKEINTERFACE " + FORGE_EVENT + "ScreenEvent$KeyReleased.getScanCode()I",
                    "INVOKEVIRTUAL " + FORGE_EVENT + "InputEvent$Key.getScanCode()I"), calls,
                    "26.2's key events still answer getScanCode, the interface one as an interface call");
            assertFalse(transformer.getSyntheticClasses().containsKey(KEY_MEMBERS));
        } else {
            String helper = "INVOKESTATIC " + KEY_MEMBERS + ".scanCode(L" + FORGE_EVENT + "ScreenEvent$KeyInput;)I";
            assertEquals(List.of(helper, helper,
                    "INVOKESTATIC " + KEY_MEMBERS + ".inputScanCode(L" + FORGE_EVENT + "InputEvent$Key;)I"), calls);
            assertTrue(transformer.getSyntheticClasses().containsKey(KEY_MEMBERS));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"forge 1.21.1", "neoforge 26.2", "fabric 26.2"})
    @DisplayName("Forge's event members stay as written below Forge 26.2 and on other loaders")
    void forgeNamesStayElsewhere(String target) {
        String[] parts = target.split(" ");
        RetromodVersion.TARGET_MC_VERSION = parts[1];
        McReflect.setForceForge(parts[0].equals("forge"));
        McReflect.setForceNeoForge(parts[0].equals("neoforge"));
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        new Forge_26_2_to_26_3().registerRedirects(transformer);
        transformer.registerMethodRedirect("test/members/Old", "old", "()V", "test/members/New", "now", "()V");

        List<String> calls = calls(transform(caller(FORGE_EVENT + "RenderLivingEvent$Pre.getEntity" + LIVING,
                FORGE_EVENT + "ScreenEvent$CharacterTyped$Post.getModifiers()I")));

        assertEquals(List.of("INVOKEVIRTUAL " + FORGE_EVENT + "RenderLivingEvent$Pre.getEntity" + LIVING,
                "INVOKEVIRTUAL " + FORGE_EVENT + "ScreenEvent$CharacterTyped$Post.getModifiers()I"), calls);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"1.21.4", "1.21.11", "26.2", "26.3"})
    @DisplayName("NeoForge: getEntity reaches the render state helper from 1.21.9, and a typed character's getModifiers the held keys from 26.2")
    void neoForgeMembers(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        NeoForgeMovedMembers.register(transformer);
        transformer.registerMethodRedirect("test/members/Old", "old", "()V", "test/members/New", "now", "()V");

        List<String> calls = calls(transform(caller(NEO_EVENT + "RenderLivingEvent$Post.getEntity" + LIVING,
                NEO_EVENT + "ScreenEvent$CharacterTyped$Pre.getModifiers()I")));

        // 1.21.2 to 1.21.4 states have no entity type, and until 1.21.9 one state is refilled for every entity drawn.
        assertEquals(host.equals("1.21.4") ? "INVOKEVIRTUAL " + NEO_EVENT + "RenderLivingEvent$Post.getEntity" + LIVING
                : "INVOKESTATIC " + NEO_RENDER_MEMBERS + ".getEntity(L" + NEO_EVENT + "RenderLivingEvent;)"
                        + LIVING.substring(2), calls.get(0));
        boolean keepsModifiers = host.startsWith("1.21");
        assertEquals(keepsModifiers ? "INVOKEVIRTUAL " + NEO_EVENT + "ScreenEvent$CharacterTyped$Pre.getModifiers()I"
                : "INVOKESTATIC " + HELD + ".modifiers(Ljava/lang/Object;)I", calls.get(1),
                "NeoForge 1.21.11 still has the modifiers; 26.2 dropped them");
    }

    @Test
    @DisplayName("The lookup needs states that name their entity type and are made fresh for each draw")
    void hostGateFollowsTheStateShape() {
        assertFalse(RenderStateLookupHost.supported(withField("entityType", false), withField("reusedState", false)),
                "1.21.2 to 1.21.4: no entity type on the state");
        assertFalse(RenderStateLookupHost.supported(withField("entityType", true), withField("reusedState", true)),
                "1.21.5 to 1.21.8: the renderer refills one state for every entity");
        assertTrue(RenderStateLookupHost.supported(withField("entityType", true), withField("reusedState", false)),
                "1.21.9 to 26.3");
    }

    private static ClassNode withField(String name, boolean present) {
        ClassNode node = new ClassNode();
        if (present) node.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PUBLIC, name, "Ljava/lang/Object;", null, null));
        return node;
    }

    // ---- the helpers at run time ----

    @Test
    @DisplayName("Two entities of one type that fit the same state give null, never the wrong one")
    void overlappingEntitiesGiveNull() throws Exception {
        Host host = new Host();
        Object zombie = host.entityType();
        // Pressed into one block of a farm, added in the same tick.
        Object first = host.living(zombie, 10.3, 64, 10.3);
        Object second = host.living(zombie, 10.3, 64, 10.3);
        host.add(first);
        host.add(second);
        assertNull(host.find(host.state(second, 0.4f)), "the same place and age");
        host.clearEntities();
        // Neither moved and one lived a tick longer, so at partial tick 0 and 1 both fit.
        Object older = host.living(zombie, 20.3, 64, 20.3);
        Object younger = host.living(zombie, 20.3, 64, 20.3);
        host.tickTo(older, 20.3, 64, 20.3);
        host.tickTo(older, 20.3, 64, 20.3);
        host.tickTo(younger, 20.3, 64, 20.3);
        host.add(younger);
        host.add(older);
        assertNull(host.find(host.state(older, 0f)), "one tick apart at partial tick 0");
    }

    @Test
    @DisplayName("A state filled again for another entity is searched again instead of answering the first")
    void refilledStateIsSearchedAgain() throws Exception {
        Host host = new Host();
        Object zombie = host.entityType();
        Object first = host.living(zombie, 30, 64, 30);
        Object second = host.living(zombie, 35, 64, 35);
        host.add(first);
        host.add(second);
        Object state = host.state(first, 0.5f);
        assertSame(first, host.find(state));
        host.copyInto(host.state(second, 0.5f), state);
        assertSame(second, host.find(state));
    }

    @Test
    @DisplayName("A player's and a witch's state name their entity by id, even beside another entity at the same place")
    void statesWithIdsNameTheirEntity() throws Exception {
        Host host = new Host();
        Object player = host.entityType();
        Object one = host.living(player, 0, 70, 0);
        Object other = host.living(player, 0, 70, 0);
        host.add(one);
        host.add(other);
        assertSame(other, host.find(host.stateWithId("AvatarRenderState", "id", other, 0.5f)));
        Object witch = host.entityType();
        Object hers = host.living(witch, 4, 70, 4);
        host.add(hers);
        assertSame(hers, host.find(host.stateWithId("WitchRenderState", "entityId", hers, 1f)));
        assertNull(host.find(host.stateWithId("WitchRenderState", "entityId", host.living(witch, 9, 70, 9), 1f)),
                "an id no entity in the level has");
    }

    @Test
    @DisplayName("The render state lookup finds the moving entity a state was made from, at any partial tick, and remembers it")
    void lookupFindsTheEntityOfAState() throws Exception {
        Host host = new Host();
        Object zombie = host.entityType();
        Object walker = host.living(zombie, 10, 64, 10);
        Object neighbour = host.living(zombie, 10.6, 64, 10);
        host.tickTo(walker, 10.5, 64, 10.25);
        host.tickTo(neighbour, 11, 64, 10);
        host.add(walker);
        host.add(neighbour);

        for (float partial : new float[] {0f, 0.25f, 0.5f, 0.999f, 1f}) {
            Object state = host.state(walker, partial);
            assertSame(walker, host.find(state), "the walker's own state at partial tick " + partial);
        }
        Object neighbours = host.state(neighbour, 0.5f);
        assertSame(neighbour, host.find(neighbours), "a second entity of the same type nearby is told apart");
        int searches = host.searches();
        host.find(neighbours);
        assertEquals(searches, host.searches(), "a second read of the same state answers from the first");
    }

    @Test
    @DisplayName("The render state lookup answers null for an entity outside the level, a moved state, or another type")
    void lookupAnswersNullWithoutAMatch() throws Exception {
        Host host = new Host();
        Object zombie = host.entityType();
        Object inLevel = host.living(zombie, 0, 70, 0);
        host.add(inLevel);
        Object preview = host.living(zombie, 0, 70, 0);
        host.tickTo(preview, 0.5, 70, 0);

        assertNull(host.find(host.state(preview, 0.5f)), "an entity drawn in a screen but not in the level");
        Object shifted = host.state(inLevel, 1f);
        host.setX(shifted, 3);
        assertNull(host.find(shifted), "a state whose position no entity had");
        Object other = host.state(inLevel, 1f);
        host.setType(other, host.entityType());
        assertNull(host.find(other), "an entity of another type at the same place");
        Object noLevel = host.state(inLevel, 1f);
        host.clearLevel();
        assertNull(host.find(noLevel), "no client level, as on the title screen");
    }

    @Test
    @DisplayName("The held modifiers use GLFW's bits: Shift 1, Control 2, Alt 4, and none without a client")
    void heldModifiers() throws Exception {
        Host host = new Host();
        Method modifiers = host.load(HELD).getMethod("modifiers", Object.class);
        Object minecraft = host.minecraft();
        assertEquals(0, modifiers.invoke(null, "event"));
        minecraft.getClass().getField("shift").setBoolean(minecraft, true);
        minecraft.getClass().getField("alt").setBoolean(minecraft, true);
        assertEquals(5, modifiers.invoke(null, "event"));
        minecraft.getClass().getField("control").setBoolean(minecraft, true);
        assertEquals(7, modifiers.invoke(null, "event"));
        host.load("net/minecraft/client/Minecraft").getField("instance").set(null, null);
        assertEquals(0, modifiers.invoke(null, "event"), "before the client exists");
    }

    @Test
    @DisplayName("A client-made player whose id collides with a real player's elsewhere gets null, not the real player")
    void collidingIdGivesNull() throws Exception {
        Host host = new Host();
        Object player = host.entityType();
        Object real = host.living(player, 50, 64, 50);
        host.add(real);
        // A fake player drawn outside the level, given the real player's id as a dedicated server's client can.
        Object fake = host.living(player, 0, 0, 0);
        Object state = host.stateWithId("AvatarRenderState", "id", fake, 1f);
        state.getClass().getField("id").setInt(state, real.getClass().getField("id").getInt(real));

        assertNull(host.find(state));
    }

    // ---- fixtures ----

    private void forge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        if (host.equals("26.3")) new Forge_26_2_to_26_3().registerRedirects(transformer);
    }

    /** One static method per call {@code owner.name(desc)}, each made on a null receiver as an old mod made it. */
    static byte[] caller(String... calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        int index = 0;
        for (String call : calls) {
            String owner = call.substring(0, call.indexOf('.'));
            String name = call.substring(call.indexOf('.') + 1, call.indexOf('('));
            String desc = call.substring(call.indexOf('('));
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call" + index++, "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, desc, false);
            mv.visitInsn(Type.getReturnType(desc).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassNode transform(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, MOD_CLASS)).accept(node, 0);
        return node;
    }

    /** Each method's call, in method order, as {@code OPCODE owner.name desc}. */
    static List<String> calls(ClassNode node) {
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

    /** A client with one level, made from the rshost stand-ins under their host names, and the compiled helpers. */
    static final class Host extends ClassLoader {

        private static final String STAND_INS = "com/retromod/shim/forge/rshost/";
        private static final Map<String, String> HOST_NAMES = Map.of(
                STAND_INS + "Minecraft", "net/minecraft/client/Minecraft",
                STAND_INS + "Level", "net/minecraft/world/level/Level",
                STAND_INS + "ClientLevel", "net/minecraft/client/multiplayer/ClientLevel",
                STAND_INS + "Entity", "net/minecraft/world/entity/Entity",
                STAND_INS + "LivingEntity", "net/minecraft/world/entity/LivingEntity",
                STAND_INS + "EntityType", "net/minecraft/world/entity/EntityType",
                STAND_INS + "EntityRenderState", "net/minecraft/client/renderer/entity/state/EntityRenderState",
                STAND_INS + "AvatarRenderState", "net/minecraft/client/renderer/entity/state/AvatarRenderState",
                STAND_INS + "WitchRenderState", "net/minecraft/client/renderer/entity/state/WitchRenderState",
                STAND_INS + "AABB", "net/minecraft/world/phys/AABB");
        private static final String LOOKUP = RebuiltApiClasses.ROOT + "minecraft/client/renderer/entity/RenderStateEntities";

        private final Map<String, byte[]> classes = new HashMap<>();
        private final Object level;

        Host() throws Exception {
            super(LegacyEventMembersBridgeTest.class.getClassLoader());
            SimpleRemapper names = new SimpleRemapper(HOST_NAMES);
            for (Map.Entry<String, String> standIn : HOST_NAMES.entrySet()) {
                try (InputStream in = getParent().getResourceAsStream(standIn.getKey() + ".class")) {
                    ClassWriter writer = new ClassWriter(0);
                    new ClassReader(in.readAllBytes()).accept(new ClassRemapper(writer, names), 0);
                    classes.put(standIn.getValue(), writer.toByteArray());
                }
            }
            // Each helper with its nested classes, as the embedder copies them.
            for (String helper : List.of(LOOKUP, HELD)) {
                Path folder = Path.of("target", "legacyapi-classes", helper).getParent();
                String simple = helper.substring(helper.lastIndexOf('/') + 1);
                try (var files = Files.list(folder)) {
                    for (Path file : files.toList()) {
                        String name = file.getFileName().toString();
                        if (name.equals(simple + ".class") || name.startsWith(simple + "$")) {
                            String internal = helper.substring(0, helper.lastIndexOf('/') + 1) + name.substring(0, name.length() - 6);
                            classes.put(internal, Files.readAllBytes(file));
                        }
                    }
                }
            }
            Class<?> minecraftType = load("net/minecraft/client/Minecraft");
            Object minecraft = minecraftType.getConstructor().newInstance();
            minecraftType.getField("instance").set(null, minecraft);
            level = load("net/minecraft/client/multiplayer/ClientLevel").getConstructor().newInstance();
            minecraftType.getField("level").set(minecraft, level);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    loaded = bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.loadClass(name, false);
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        Class<?> load(String internalName) throws ClassNotFoundException {
            return loadClass(internalName.replace('/', '.'));
        }

        Object minecraft() throws Exception {
            return load("net/minecraft/client/Minecraft").getField("instance").get(null);
        }

        Object entityType() throws Exception {
            return load("net/minecraft/world/entity/EntityType").getConstructor().newInstance();
        }

        Object living(Object type, double x, double y, double z) throws Exception {
            return load("net/minecraft/world/entity/LivingEntity")
                    .getConstructor(load("net/minecraft/world/entity/EntityType"), double.class, double.class, double.class)
                    .newInstance(type, x, y, z);
        }

        void tickTo(Object entity, double x, double y, double z) throws Exception {
            entity.getClass().getMethod("tickTo", double.class, double.class, double.class).invoke(entity, x, y, z);
        }

        @SuppressWarnings("unchecked")
        void add(Object entity) throws Exception {
            ((List<Object>) level.getClass().getField("entities").get(level)).add(entity);
        }

        int searches() throws Exception {
            return level.getClass().getField("searches").getInt(level);
        }

        @SuppressWarnings("unchecked")
        void clearEntities() throws Exception {
            ((List<Object>) level.getClass().getField("entities").get(level)).clear();
        }

        /** What a renderer that kept one state does for the next entity: the same object, new values. */
        void copyInto(Object from, Object into) throws Exception {
            for (String field : List.of("x", "y", "z", "ageInTicks", "entityType")) {
                java.lang.reflect.Field f = into.getClass().getField(field);
                f.set(into, f.get(from));
            }
        }

        /** A state of {@code kind}, filled for the entity at the partial tick, with its id field set to the entity's id. */
        Object stateWithId(String kind, String idField, Object entity, float partialTick) throws Exception {
            Object plain = state(entity, partialTick);
            Object state = load("net/minecraft/client/renderer/entity/state/" + kind).getConstructor().newInstance();
            copyInto(plain, state);
            state.getClass().getField(idField).setInt(state, entity.getClass().getField("id").getInt(entity));
            return state;
        }

        void clearLevel() throws Exception {
            Object minecraft = minecraft();
            minecraft.getClass().getField("level").set(minecraft, null);
        }

        Object state(Object entity, float partialTick) throws Exception {
            Class<?> state = load("net/minecraft/client/renderer/entity/state/EntityRenderState");
            return state.getMethod("of", load("net/minecraft/world/entity/Entity"), float.class).invoke(null, entity, partialTick);
        }

        void setX(Object state, double x) throws Exception {
            state.getClass().getField("x").setDouble(state, x);
        }

        void setType(Object state, Object type) throws Exception {
            state.getClass().getField("entityType").set(state, type);
        }

        Object find(Object state) throws Exception {
            Class<?> stateType = load("net/minecraft/client/renderer/entity/state/EntityRenderState");
            return load(LOOKUP).getMethod("entity", stateType).invoke(null, state);
        }
    }
}
