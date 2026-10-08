/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.registry.LegacyRegistryAccess;
import com.retromod.shim.forge.registry.LegacyRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forge 1.20.1 custom registries (Cracker's Wither Storm's spell registry, for one) on a NeoForge
 * host: the builder, the registry it creates, and the calls a mod makes on it.
 */
class LegacyCustomRegistryBridgeTest {

    private static final String MOD_CLASS = "com/example/ModRegistries";
    private static final String FORGE_EVENT = "net/minecraftforge/registries/NewRegistryEvent";
    private static final String L_LOCATION = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String L_BUILDER = "L" + LegacyCustomRegistryBridge.FORGE_BUILDER + ";";
    private static final String FORGE_DEFERRED_REGISTER = "net/minecraftforge/registries/DeferredRegister";
    private static final String NEO_DEFERRED_REGISTER = "net/neoforged/neoforge/registries/DeferredRegister";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    void forgeBuilderCallsLinkAgainstTheStandIn() throws Exception {
        LegacyCustomRegistryBridge.register(transformer);
        ClassNode builder = read(transformer.getSyntheticClasses().get(LegacyCustomRegistryBridge.FORGE_BUILDER));
        ClassNode mod = read(transformer.transformClass(modCreatingARegistry(), MOD_CLASS));

        List<MethodInsnNode> builderCalls = new ArrayList<>();
        for (MethodNode method : mod.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.equals(LegacyCustomRegistryBridge.FORGE_BUILDER)) {
                    builderCalls.add(call);
                }
            }
        }
        assertEquals(3, builderCalls.size(), "constructor, setName, and the redirected create call");
        for (MethodInsnNode call : builderCalls) {
            assertTrue(declares(builder, call.name, call.desc),
                    "the stand-in must declare " + call.name + call.desc + " for the mod call to link");
        }
        MethodInsnNode create = builderCalls.get(2);
        assertEquals(Opcodes.INVOKESTATIC, create.getOpcode());
        assertEquals("retromod$create", create.name);
    }

    @Test
    void deferredRegisterMakeRegistryBecomesTheStaticBridgeCall() {
        // Forge_1_20_to_NeoForge_1_21 renames DeferredRegister before it registers this bridge.
        transformer.registerClassRedirect(FORGE_DEFERRED_REGISTER, NEO_DEFERRED_REGISTER);
        LegacyCustomRegistryBridge.register(transformer);

        ClassNode mod = read(transformer.transformClass(modMakingARegistry(), MOD_CLASS));
        MethodInsnNode call = null;
        for (MethodNode method : mod.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode candidate && candidate.name.contains("makeRegistry")) {
                    call = candidate;
                }
            }
        }
        assertNotNull(call, "the makeRegistry call must survive the transform");
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(),
                "NeoForge's makeRegistry takes a Consumer, so the Forge Supplier form must not stay a direct call");
        assertEquals(LegacyCustomRegistryBridge.FORGE_BUILDER, call.owner);
        assertEquals("retromod$makeRegistry", call.name);
    }

    @Test
    void lookupsFollowTheMembersThat1212And12111Renamed() {
        RenamedRegistry registry = new RenamedRegistry(List.of("empty", "pull"));
        RenamedBuffer buffer = new RenamedBuffer();

        assertEquals(Optional.of("holder:pull"), LegacyRegistryAccess.holderByKey(registry, "pull"),
                "getHolder became get, and only the Optional form is the holder");
        assertEquals("spell_types", LegacyRegistryAccess.registryName(registry),
                "ResourceKey.location() became identifier()");

        LegacyRegistryAccess.writeRegistryIdUnsafeByName(buffer, registry, "pull");
        assertEquals("pull", LegacyRegistryAccess.readRegistryIdUnsafe(buffer, registry),
                "a name lookup must read the value through getValue, not the holder from get");

        buffer.ids.clear();
        LegacyRegistryAccess.writeRegistryId(buffer, registry, "empty");
        assertEquals("spell_types", buffer.identifier, "the registry name is written with writeIdentifier");
    }

    @Test
    void castsToTheRegistryStandInBecomeRegistryCasts() {
        byte[] original = castingClass();
        ClassNode result = read(LegacyCustomRegistryBridge.retargetRegistryCasts(original));
        List<String> casts = new ArrayList<>();
        for (MethodNode method : result.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode type) casts.add(type.desc);
            }
        }
        assertEquals(List.of(LegacyCustomRegistryBridge.REGISTRY, LegacyCustomRegistryBridge.REGISTRY), casts,
                "both CHECKCAST and INSTANCEOF must target the vanilla registry");
    }

    @Test
    void classesWithoutTheRegistryStandInAreLeftAlone() {
        byte[] original = emptyClass();
        assertSame(original, LegacyCustomRegistryBridge.retargetRegistryCasts(original));
    }

    @Test
    void makeRegistryReplaysForgeOptionsOntoTheNeoForgeBuilder() {
        FakeDeferredRegister register = new FakeDeferredRegister();
        Supplier<LegacyRegistryBuilder> forgeBuilder = () -> new LegacyRegistryBuilder().setMaxID(255).disableSync();

        Supplier<Object> registry = LegacyRegistryBuilder.retromod$makeRegistry(register, forgeBuilder);

        assertSame(register.created, registry.get());
        assertEquals(255, register.builder.maxId);
        assertEquals(Boolean.FALSE, register.builder.sync, "disableSync must reach NeoForge");
    }

    @Test
    void registryIdsRoundTripThroughTheBuffer() {
        FakeRegistry registry = new FakeRegistry("spell_types", List.of("empty", "pull", "smash"));
        FakeBuffer buffer = new FakeBuffer();

        LegacyRegistryAccess.writeRegistryIdUnsafe(buffer, registry, "smash");
        assertEquals("smash", LegacyRegistryAccess.readRegistryIdUnsafe(buffer, registry));
    }

    @Test
    void holderLookupsUseTheValuesResourceKey() {
        FakeRegistry registry = new FakeRegistry("spell_types", List.of("empty", "pull"));

        assertEquals(Optional.of("holder:pull"), LegacyRegistryAccess.holderOf(registry, "pull"));
        assertEquals(Optional.empty(), LegacyRegistryAccess.holderOf(registry, "missing"));
        assertTrue(LegacyRegistryAccess.containsValue(registry, "empty"));
        assertFalse(LegacyRegistryAccess.isEmpty(registry));
        assertEquals("spell_types", LegacyRegistryAccess.registryName(registry));
    }

    // Mod bytecode: new RegistryBuilder().setName(name) passed to NewRegistryEvent.create.
    private static byte[] modCreatingARegistry() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerRegistries",
                "(L" + FORGE_EVENT + ";" + L_LOCATION + ")Ljava/util/function/Supplier;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, LegacyCustomRegistryBridge.FORGE_BUILDER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyCustomRegistryBridge.FORGE_BUILDER, "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyCustomRegistryBridge.FORGE_BUILDER, "setName",
                "(" + L_LOCATION + ")" + L_BUILDER, false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_EVENT, "create",
                "(" + L_BUILDER + ")Ljava/util/function/Supplier;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // Mod bytecode: DeferredRegister.makeRegistry(() -> new RegistryBuilder()).
    private static byte[] modMakingARegistry() {
        String supplier = "Ljava/util/function/Supplier;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "makeRegistry",
                "(L" + FORGE_DEFERRED_REGISTER + ";" + supplier + ")" + supplier, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_DEFERRED_REGISTER, "makeRegistry",
                "(" + supplier + ")" + supplier, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] castingClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "spells",
                "(Ljava/util/function/Supplier;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, LegacyCustomRegistryBridge.IFORGE_REGISTRY);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, LegacyCustomRegistryBridge.IFORGE_REGISTRY);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] emptyClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    /** NeoForge's builder surface that the stand-in replays options onto. */
    public static final class FakeNeoBuilder {
        public Integer maxId;
        public Boolean sync;

        public FakeNeoBuilder maxId(int maxId) {
            this.maxId = maxId;
            return this;
        }

        public FakeNeoBuilder sync(boolean sync) {
            this.sync = sync;
            return this;
        }
    }

    /** NeoForge's {@code DeferredRegister.makeRegistry(Consumer)}. */
    public static final class FakeDeferredRegister {
        public final Object created = new Object();
        public FakeNeoBuilder builder;

        public Object makeRegistry(Consumer<FakeNeoBuilder> configure) {
            builder = new FakeNeoBuilder();
            configure.accept(builder);
            return created;
        }
    }

    /** The parts of a vanilla registry the access helpers call. */
    public static final class FakeRegistry implements Iterable<String> {
        private final String name;
        private final List<String> values;

        FakeRegistry(String name, List<String> values) {
            this.name = name;
            this.values = values;
        }

        public Optional<String> getResourceKey(Object value) {
            return values.contains(value) ? Optional.of("key:" + value) : Optional.empty();
        }

        public Optional<String> getHolder(String key) {
            return Optional.of("holder:" + key.substring("key:".length()));
        }

        public FakeKey key() {
            return new FakeKey(name);
        }

        public int getId(Object value) {
            return values.indexOf(value);
        }

        public String byId(int id) {
            return values.get(id);
        }

        @Override
        public Iterator<String> iterator() {
            return values.iterator();
        }
    }

    /** A 1.21.11-shaped registry: {@code get} returns the holder and {@code getValue} the value. */
    public static final class RenamedRegistry implements Iterable<String> {
        private final List<String> values;

        RenamedRegistry(List<String> values) {
            this.values = values;
        }

        public Optional<String> get(String name) {
            return values.contains(name) ? Optional.of("holder:" + name) : Optional.empty();
        }

        public String getValue(String name) {
            return values.contains(name) ? name : null;
        }

        public RenamedKey key() {
            return new RenamedKey("spell_types");
        }

        public int getId(Object value) {
            return values.indexOf(value);
        }

        public String byId(int id) {
            return values.get(id);
        }

        @Override
        public Iterator<String> iterator() {
            return values.iterator();
        }
    }

    public record RenamedKey(String identifier) {
    }

    /** A 1.21.11-shaped buffer, which writes names with {@code writeIdentifier}. */
    public static final class RenamedBuffer {
        final List<Integer> ids = new ArrayList<>();
        String identifier;
        private int read;

        public void writeIdentifier(Object name) {
            identifier = String.valueOf(name);
        }

        public void writeVarInt(int value) {
            ids.add(value);
        }

        public int readVarInt() {
            return ids.get(read++);
        }
    }

    public record FakeKey(String location) {
        // A record accessor is a public method named like ResourceKey.location().
    }

    /** The varint half of {@code FriendlyByteBuf}. */
    public static final class FakeBuffer {
        private final Map<Integer, Integer> slots = new LinkedHashMap<>();
        private int written;
        private int read;

        public void writeVarInt(int value) {
            slots.put(written++, value);
        }

        public int readVarInt() {
            return slots.get(read++);
        }
    }
}
