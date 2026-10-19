/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #271: Minepathy 1.1.1 gives its syringe a damage type with
 * {@code component(DataComponents.DAMAGE_TYPE, new EitherHolder<>(key))}, and 26.1 removed
 * {@code EitherHolder}, so the item class failed with {@code NoClassDefFoundError} on 26.2.
 */
class LegacyEitherHolderBridgeTest {

    private static final String EITHER_HOLDER = "net/minecraft/world/item/EitherHolder";
    private static final String PROPERTIES = "net/minecraft/world/item/Item$Properties";
    private static final String COMPONENT_TYPE = "net/minecraft/core/component/DataComponentType";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String KEY = "net/minecraft/resources/ResourceKey";
    private static final String JUKEBOX = "net/minecraft/world/item/JukeboxPlayable";
    private static final String MOD_ITEMS = "test/ModItems";
    private static final String PLAIN_ITEMS = "test/PlainItems";

    private static final String L_PROPERTIES = "L" + PROPERTIES + ";";
    private static final String COMPONENT_DESC = "(L" + COMPONENT_TYPE + ";Ljava/lang/Object;)" + L_PROPERTIES;

    private RetromodTransformer transformer;
    private String host;

    @BeforeEach
    void setUp() {
        host = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Every loader's 26.1 shim replaces EitherHolder")
    void everyLoaderRegistersTheBridge() {
        assertBridged("Fabric", () -> new Fabric_1_21_11_to_26_1().registerRedirects(transformer));
        assertBridged("NeoForge", () -> new NeoForge_1_21_11_to_26_1().registerRedirects(transformer));
        // Forge assembles its own list of the shared 26.1 adaptations.
        assertBridged("Forge", () -> new Forge_1_21_11_to_26_1().registerRedirects(transformer));
    }

    @Test
    @DisplayName("A key wrapped in EitherHolder becomes the host's delayed holder component")
    void keyBecomesDelayedHolderComponent() throws Exception {
        Fixture fixture = fixture();
        Object properties = fixture.newInstance(PROPERTIES);
        Object type = fixture.newInstance(COMPONENT_TYPE);
        Object key = fixture.newKey("minepathy:syringe");

        Object returned = fixture.call("byKey", properties, type, key);

        assertSame(properties, returned, "the builder chain continues on the same properties");
        assertEquals(List.of("delayedHolderComponent"), fixture.calls(properties),
                "26.1 resolves a key-only damage type once the registries load");
        assertSame(key, fixture.field(properties, "lastValue"));
    }

    @Test
    @DisplayName("A holder wrapped in EitherHolder is stored as the holder itself")
    void holderIsStoredDirectly() throws Exception {
        Fixture fixture = fixture();
        Object properties = fixture.newInstance(PROPERTIES);
        Object holder = fixture.newHolder("minecraft:generic");

        fixture.call("byHolder", properties, fixture.newInstance(COMPONENT_TYPE), holder);

        assertEquals(List.of("component"), fixture.calls(properties));
        assertSame(holder, fixture.field(properties, "lastValue"),
                "26.1 types DAMAGE_TYPE as a plain Holder, so the wrapper must not reach it");
    }

    @Test
    @DisplayName("Other component values pass through unchanged")
    void otherValuesPassThrough() throws Exception {
        Fixture fixture = fixture();
        Object properties = fixture.newInstance(PROPERTIES);

        fixture.call("plain", properties, fixture.newInstance(COMPONENT_TYPE), "unchanged");

        assertEquals(List.of("component"), fixture.calls(properties));
        assertEquals("unchanged", fixture.field(properties, "lastValue"));
    }

    @Test
    @DisplayName("A jukebox component built from a holder gets the holder, and reads back as EitherHolder")
    void jukeboxRecordTakesTheHolder() throws Exception {
        Fixture fixture = fixture();
        Object holder = fixture.newHolder("minepathy:anthem");

        Object playable = fixture.call("jukeboxFromHolder", holder);

        assertSame(holder, fixture.field(playable, "song"));
        assertEquals(Optional.of(fixture.field(holder, "key")), fixture.call("songKey", playable),
                "song() hands the old caller a wrapper whose key() is the holder's key");
    }

    @Test
    @DisplayName("A jukebox component built from a bare key fails with a message naming the key")
    void jukeboxRecordFromKeyExplainsItself() throws Exception {
        Fixture fixture = fixture();
        Object key = fixture.newKey("minepathy:anthem");

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> fixture.call("jukeboxFromKey", key));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        String message = failure.getCause().getMessage();
        assertTrue(message.contains("JukeboxPlayable") && message.contains("minepathy:anthem")
                && message.contains("26.1"), message);
    }

    @Test
    @DisplayName("A 1.21.1 mod reads the key back through the key() that returned it directly")
    void oldKeyAccessorReturnsTheKey() throws Exception {
        Fixture fixture = fixture();
        Object key = fixture.newKey("minecraft:thirteen");
        Object holder = fixture.newHolder("minecraft:cat");

        assertSame(key, fixture.call("bareKeyOfKey", key),
                "1.21.1's key() returned the ResourceKey, not an Optional");
        assertSame(fixture.field(holder, "key"), fixture.call("bareKeyOfHolder", holder),
                "a holder-built value answers with the holder's own key");
    }

    @Test
    @DisplayName("A class that never builds an EitherHolder keeps its direct component call")
    void unrelatedClassIsUntouched() {
        LegacyEitherHolderBridge.register(transformer);
        byte[] plain = writeClass(PLAIN_ITEMS, cw -> method(cw, "plain",
                "(" + L_PROPERTIES + "L" + COMPONENT_TYPE + ";Ljava/lang/Object;)Ljava/lang/Object;", m -> {
                    m.visitVarInsn(ALOAD, 0);
                    m.visitVarInsn(ALOAD, 1);
                    m.visitVarInsn(ALOAD, 2);
                    m.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "component", COMPONENT_DESC, false);
                }));

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(plain, PLAIN_ITEMS)).accept(out, 0);

        MethodInsnNode call = onlyCall(out, "plain");
        assertEquals(PROPERTIES, call.owner, "only a class that uses the stand-in needs the helper");
        assertEquals(INVOKEVIRTUAL, call.getOpcode());
    }

    @Test
    @DisplayName("The generated stand-in passes ASM's structural checks")
    void standInIsWellFormed() {
        assertDoesNotThrow(() -> new ClassReader(LegacyEitherHolderBridge.generateStandIn())
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0));
    }

    private void assertBridged(String loader, Runnable register) {
        transformer.clearRedirectsForTesting();
        register.run();
        assertEquals(LegacyEitherHolderBridge.STAND_IN, transformer.getClassRedirects().get(EITHER_HOLDER),
                loader + " leaves EitherHolder in place, which 26.1 removed");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEitherHolderBridge.STAND_IN),
                loader + " redirects to a stand-in it never registered");
    }

    // ---- The 1.21.11 mod and the fake host it runs against -------------------------------------

    /** A 1.21.11 item class, as javac writes it, transformed for 26.2 and loaded with fake hosts. */
    private Fixture fixture() throws IOException {
        LegacyEitherHolderBridge.register(transformer);
        Map<String, byte[]> classes = fakeHost();
        classes.put(MOD_ITEMS, transformer.transformClass(modItems(), MOD_ITEMS));
        classes.put(LegacyEitherHolderBridge.STAND_IN,
                transformer.getSyntheticClasses().get(LegacyEitherHolderBridge.STAND_IN));
        return new Fixture(new DefiningLoader(classes));
    }

    private static byte[] modItems() {
        String eitherHolder = "L" + EITHER_HOLDER + ";";
        String build = "(" + L_PROPERTIES + "L" + COMPONENT_TYPE + ";";
        return writeClass(MOD_ITEMS, cw -> {
            method(cw, "byKey", build + "L" + KEY + ";)Ljava/lang/Object;",
                    m -> componentOfNewEitherHolder(m, KEY));
            method(cw, "byHolder", build + "L" + HOLDER + ";)Ljava/lang/Object;",
                    m -> componentOfNewEitherHolder(m, HOLDER));
            method(cw, "plain", build + "Ljava/lang/Object;)Ljava/lang/Object;", m -> {
                m.visitVarInsn(ALOAD, 0);
                m.visitVarInsn(ALOAD, 1);
                m.visitVarInsn(ALOAD, 2);
                m.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "component", COMPONENT_DESC, false);
            });
            method(cw, "jukeboxFromHolder", "(L" + HOLDER + ";)Ljava/lang/Object;", m -> newJukebox(m, HOLDER));
            method(cw, "jukeboxFromKey", "(L" + KEY + ";)Ljava/lang/Object;", m -> newJukebox(m, KEY));
            method(cw, "bareKeyOfKey", "(L" + KEY + ";)Ljava/lang/Object;", m -> bareKey(m, KEY));
            method(cw, "bareKeyOfHolder", "(L" + HOLDER + ";)Ljava/lang/Object;", m -> bareKey(m, HOLDER));
            method(cw, "songKey", "(L" + JUKEBOX + ";)Ljava/lang/Object;", m -> {
                m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKEVIRTUAL, JUKEBOX, "song", "()" + eitherHolder, false);
                m.visitMethodInsn(INVOKEVIRTUAL, EITHER_HOLDER, "key", "()Ljava/util/Optional;", false);
            });
        });
    }

    /** {@code properties.component(type, new EitherHolder<>(arg))}. */
    private static void componentOfNewEitherHolder(MethodVisitor m, String argType) {
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(NEW, EITHER_HOLDER);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKESPECIAL, EITHER_HOLDER, "<init>", "(L" + argType + ";)V", false);
        m.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "component", COMPONENT_DESC, false);
    }

    /** {@code new EitherHolder<>(arg).key()} as 1.21.1 declared it, returning the key itself. */
    private static void bareKey(MethodVisitor m, String argType) {
        m.visitTypeInsn(NEW, EITHER_HOLDER);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, EITHER_HOLDER, "<init>", "(L" + argType + ";)V", false);
        m.visitMethodInsn(INVOKEVIRTUAL, EITHER_HOLDER, "key", "()L" + KEY + ";", false);
    }

    /** {@code new JukeboxPlayable(new EitherHolder<>(arg))}. */
    private static void newJukebox(MethodVisitor m, String argType) {
        m.visitTypeInsn(NEW, JUKEBOX);
        m.visitInsn(DUP);
        m.visitTypeInsn(NEW, EITHER_HOLDER);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, EITHER_HOLDER, "<init>", "(L" + argType + ";)V", false);
        m.visitMethodInsn(INVOKESPECIAL, JUKEBOX, "<init>", "(L" + EITHER_HOLDER + ";)V", false);
    }

    private static byte[] writeClass(String name, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void method(ClassWriter cw, String name, String desc,
            java.util.function.Consumer<MethodVisitor> body) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        m.visitCode();
        body.accept(m);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static MethodInsnNode onlyCall(ClassNode cn, String methodName) {
        MethodNode method = cn.methods.stream().filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        assertEquals(1, calls.size(), "expected one call in " + methodName);
        return calls.get(0);
    }

    /** The test's fake host classes, renamed into the Minecraft packages the bridge names. */
    private Map<String, byte[]> fakeHost() throws IOException {
        Map<String, String> renames = Map.of(
                internal(FakeKey.class), KEY,
                internal(FakeHolder.class), HOLDER,
                internal(FakeDirectHolder.class), "test/DirectHolder",
                internal(FakeComponentType.class), COMPONENT_TYPE,
                internal(FakeProperties.class), PROPERTIES,
                internal(FakeJukeboxPlayable.class), JUKEBOX);
        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<String, String> rename : renames.entrySet()) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(rename.getKey() + ".class")) {
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(in.readAllBytes()).accept(
                        new ClassRemapper(writer, new SimpleRemapper(renames)), 0);
                classes.put(rename.getValue(), writer.toByteArray());
            }
        }
        return classes;
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** Becomes {@code ResourceKey}. */
    public static final class FakeKey {
        public final String id;

        public FakeKey(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "ResourceKey[" + id + "]";
        }
    }

    /** Becomes {@code Holder}. */
    public interface FakeHolder {
        Optional<FakeKey> unwrapKey();
    }

    /** A registered holder that knows its key. */
    public static final class FakeDirectHolder implements FakeHolder {
        public final FakeKey key;

        public FakeDirectHolder(FakeKey key) {
            this.key = key;
        }

        @Override
        public Optional<FakeKey> unwrapKey() {
            return Optional.of(key);
        }
    }

    /** Becomes {@code DataComponentType}. */
    public static final class FakeComponentType {
    }

    /** Becomes {@code Item.Properties}, recording which builder method received the value. */
    public static final class FakeProperties {
        public final List<String> calls = new ArrayList<>();
        public Object lastValue;

        public FakeProperties component(FakeComponentType type, Object value) {
            calls.add("component");
            lastValue = value;
            return this;
        }

        public FakeProperties delayedHolderComponent(FakeComponentType type, FakeKey key) {
            calls.add("delayedHolderComponent");
            lastValue = key;
            return this;
        }
    }

    /** Becomes the 26.1 {@code JukeboxPlayable}, which takes a {@code Holder}. */
    public static final class FakeJukeboxPlayable {
        public final FakeHolder song;

        public FakeJukeboxPlayable(FakeHolder song) {
            this.song = song;
        }

        public FakeHolder song() {
            return song;
        }
    }

    private static final class DefiningLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        DefiningLoader(Map<String, byte[]> classes) {
            super(LegacyEitherHolderBridgeTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
            }
        }
    }

    private record Fixture(DefiningLoader loader) {
        Class<?> type(String internalName) throws ClassNotFoundException {
            return loader.loadClass(internalName.replace('/', '.'));
        }

        Object newInstance(String internalName) throws Exception {
            return type(internalName).getConstructor().newInstance();
        }

        Object newKey(String id) throws Exception {
            return type(KEY).getConstructor(String.class).newInstance(id);
        }

        Object newHolder(String id) throws Exception {
            return type("test/DirectHolder").getConstructor(type(KEY)).newInstance(newKey(id));
        }

        Object call(String name, Object... args) throws Exception {
            for (Method method : type(MOD_ITEMS).getMethods()) {
                if (method.getName().equals(name)) return method.invoke(null, args);
            }
            throw new NoSuchMethodException(name);
        }

        Object field(Object owner, String name) throws Exception {
            return owner.getClass().getField(name).get(owner);
        }

        @SuppressWarnings("unchecked")
        List<String> calls(Object properties) throws Exception {
            return (List<String>) field(properties, "calls");
        }
    }
}
