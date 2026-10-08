/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #264 (Pyrologer And Friends) and #290 (Mythic Fantasies): MCreator 1.19.2 mods that register
 * their worldgen in code.
 *
 * <p>1.19.3 moved configured features, placed features and biomes into data pack registries. The
 * mods died on {@code FeatureUtils.register} and {@code new BiomeGenerationSettings.Builder()}, and
 * their own {@code forge/biome_modifier} files name the features those calls used to create.
 */
class LegacyWorldgenBridgeTest {

    private static final String MOD_CLASS = "test/worldgen/ModFeatures";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String KEY = "net/minecraft/resources/ResourceKey";
    private static final String FEATURE = "net/minecraft/world/level/levelgen/feature/Feature";
    private static final String CONFIG =
            "net/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration";
    private static final String GEN_BUILDER = "net/minecraft/world/level/biome/BiomeGenerationSettings$Builder";
    private static final String DECORATION = "net/minecraft/world/level/levelgen/GenerationStep$Decoration";

    private RetromodTransformer transformer;
    private String savedHost;

    @BeforeEach
    void setUp() {
        // The bridge is generated for the host it runs on; these examples describe Forge 1.20.1.
        savedHost = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyWorldgenBridge.register(transformer);
        LegacyWorldgenBridge.registerPrecipitationBridge(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @Test
    @DisplayName("a host past 1.20.1 records worldgen but generates no pack source or overworld splice")
    void newerHostsDoNotLinkTheOldPackApi() {
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        transformer.clearRedirectsForTesting();
        LegacyWorldgenBridge.register(transformer);

        assertNull(transformer.getSyntheticClasses().get(LegacyWorldgenBridge.PACK_SOURCE),
                "the pack source links the 1.20.1 Pack API, which 1.21 reshaped");
        ClassNode worldgen = synthetic(transformer, LegacyWorldgenBridge.GENERATED);
        MethodNode source = worldgen.methods.stream().filter(m -> m.name.equals("repositorySource"))
                .findFirst().orElseThrow();
        for (AbstractInsnNode insn : source.instructions) {
            assertFalse(insn instanceof org.objectweb.asm.tree.TypeInsnNode,
                    "repositorySource must answer null instead of building the 1.20.1 pack source");
        }
        assertTrue(worldgen.methods.stream().anyMatch(m -> m.name.equals("registerConfigured")),
                "feature registration still works, so the mod loads");
        assertTrue(worldgen.methods.stream().noneMatch(m -> m.name.equals("dimensions")),
                "the overworld splice links registryOrThrow, which 1.21.2 renamed");
        assertFalse(LegacyWorldgenBridge.overworldInjectionLinks("1.21.4"));
        assertTrue(LegacyWorldgenBridge.overworldInjectionLinks("1.21.1"));
        assertTrue(LegacyWorldgenBridge.packApiLinks("1.20.1"));
        assertFalse(LegacyWorldgenBridge.packApiLinks("1.20.2"));
    }

    /** A static method {@code run()} on a mod class, with the given body. */
    private static byte[] modClass(Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private List<AbstractInsnNode> transformedRun(byte[] original) {
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(original, MOD_CLASS)).accept(cn, 0);
        MethodNode run = cn.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
        List<AbstractInsnNode> insns = new ArrayList<>();
        run.instructions.forEach(insns::add);
        return insns;
    }

    private static MethodInsnNode findCall(List<AbstractInsnNode> insns, String name) {
        return insns.stream().filter(i -> i instanceof MethodInsnNode m && m.name.equals(name))
                .map(i -> (MethodInsnNode) i).findFirst().orElse(null);
    }

    private static ClassNode synthetic(RetromodTransformer transformer, String name) {
        byte[] bytes = transformer.getSyntheticClasses().get(name);
        assertNotNull(bytes, name + " must be registered for per-mod embedding");
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    @Test
    @DisplayName("FeatureUtils.register and PlacementUtils.register reach the generated registrar")
    void featureRegistrationIsRecorded() {
        List<AbstractInsnNode> insns = transformedRun(modClass(mv -> {
            mv.visitLdcInsn("modid:barn");
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/data/worldgen/features/FeatureUtils",
                    "register", LegacyWorldgenBridge.REGISTER_CONFIGURED_DESC, false);
            mv.visitVarInsn(Opcodes.ASTORE, 0);
            mv.visitLdcInsn("modid:barn");
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/data/worldgen/placement/PlacementUtils",
                    "register", LegacyWorldgenBridge.REGISTER_PLACED_DESC, false);
        }));

        MethodInsnNode configured = findCall(insns, "registerConfigured");
        assertNotNull(configured, "FeatureUtils.register(String, Feature, Config) must be bridged");
        assertEquals(LegacyWorldgenBridge.GENERATED, configured.owner);
        MethodInsnNode placed = findCall(insns, "registerPlaced");
        assertNotNull(placed, "PlacementUtils.register(String, Holder, List) must be bridged");
        assertEquals(LegacyWorldgenBridge.GENERATED, placed.owner);
    }

    @Test
    @DisplayName("a vanilla worldgen constant read as a Holder becomes a key read plus a holder wrap")
    void holderConstantBecomesKeyRead() {
        List<AbstractInsnNode> insns = transformedRun(modClass(mv -> mv.visitFieldInsn(Opcodes.GETSTATIC,
                "net/minecraft/data/worldgen/features/VegetationFeatures", "PATCH_GRASS", "L" + HOLDER + ";")));

        FieldInsnNode read = insns.stream().filter(i -> i instanceof FieldInsnNode)
                .map(i -> (FieldInsnNode) i).findFirst().orElseThrow();
        assertEquals("L" + KEY + ";", read.desc,
                "1.19.3 turned the constant into a ResourceKey, so the Holder read cannot link");
        assertEquals("PATCH_GRASS", read.name, "the constant keeps its name");
        MethodInsnNode wrap = findCall(insns, "keyHolder");
        assertNotNull(wrap, "the key must be wrapped back into the Holder the mod expects");
        assertEquals(insns.indexOf(read) + 1, insns.indexOf(wrap), "the wrap must follow the read directly");
    }

    @Test
    @DisplayName("a Holder field outside the worldgen data package is left alone")
    void unrelatedHolderFieldIsUntouched() {
        List<AbstractInsnNode> insns = transformedRun(modClass(mv -> mv.visitFieldInsn(Opcodes.GETSTATIC,
                "test/worldgen/OwnFeatures", "PLACED", "L" + HOLDER + ";")));
        FieldInsnNode read = insns.stream().filter(i -> i instanceof FieldInsnNode)
                .map(i -> (FieldInsnNode) i).findFirst().orElseThrow();
        assertEquals("L" + HOLDER + ";", read.desc, "a mod's own Holder field did not change type");
        assertNull(findCall(insns, "keyHolder"));
    }

    @Test
    @DisplayName("the no-argument biome builder and its chained adder are bridged")
    void biomeBuilderIsBridged() {
        List<AbstractInsnNode> insns = transformedRun(modClass(mv -> {
            mv.visitTypeInsn(Opcodes.NEW, GEN_BUILDER);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, GEN_BUILDER, "<init>", "()V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GEN_BUILDER, "addFeature",
                    "(L" + DECORATION + ";L" + HOLDER + ";)L" + GEN_BUILDER + ";", false);
        }));

        MethodInsnNode factory = findCall(insns, "newGenerationBuilder");
        assertNotNull(factory, "1.19.3 removed the no-argument constructor; the factory must replace it");
        assertEquals(LegacyWorldgenBridge.GENERATED, factory.owner);
        MethodInsnNode adder = findCall(insns, "addFeature");
        assertNotNull(adder);
        assertEquals(Opcodes.INVOKESTATIC, adder.getOpcode(),
                "the adder now returns PlainBuilder, so the chained call goes through the bridge");
        assertEquals(LegacyWorldgenBridge.GENERATED, adder.owner);
    }

    @Test
    @DisplayName("the private 1.19.3 seagrass helper is answered on Mojang and SRG hosts")
    void seagrassPlacementIsRebuilt() {
        for (String name : new String[]{"seagrassPlacement", "m_195233_"}) {
            List<AbstractInsnNode> insns = transformedRun(modClass(mv -> {
                mv.visitInsn(Opcodes.ICONST_2);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/data/worldgen/placement/AquaticPlacements",
                        name, "(I)Ljava/util/List;", false);
            }));
            MethodInsnNode call = findCall(insns, "seagrassPlacement");
            assertNotNull(call, "the call named " + name + " must reach the rebuilt helper");
            assertEquals(LegacyWorldgenBridge.GENERATED, call.owner,
                    "calling the private vanilla method fails with IllegalAccessError");
        }
    }

    @Test
    @DisplayName("BiomeBuilder.precipitation(kind) becomes hasPrecipitation(kind != NONE)")
    void precipitationIsBridged() {
        String biomeBuilder = "net/minecraft/world/level/biome/Biome$BiomeBuilder";
        String precipitation = "net/minecraft/world/level/biome/Biome$Precipitation";
        List<AbstractInsnNode> insns = transformedRun(modClass(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, biomeBuilder, "precipitation",
                    "(L" + precipitation + ";)L" + biomeBuilder + ";", false);
        }));
        MethodInsnNode call = findCall(insns, "precipitation");
        assertEquals(LegacyWorldgenBridge.BIOME_BUILDER_BRIDGE, call.owner);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());

        ClassNode bridge = synthetic(transformer, LegacyWorldgenBridge.BIOME_BUILDER_BRIDGE);
        MethodNode method = bridge.methods.stream().filter(m -> m.name.equals("precipitation"))
                .findFirst().orElseThrow();
        boolean callsBoolean = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode m && m.name.equals("hasPrecipitation")) callsBoolean = true;
        }
        assertTrue(callsBoolean, "the bridge must use the 1.19.4 boolean setter");
    }

    @Test
    @DisplayName("the overworld biome splice calls are bridged off the removed 1.19.2 API")
    void overworldInjectionIsBridged() {
        String worldData = "net/minecraft/world/level/storage/WorldData";
        String settings = "net/minecraft/world/level/levelgen/WorldGenSettings";
        List<AbstractInsnNode> insns = transformedRun(modClass(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, worldData, "worldGenSettings",
                    "()L" + settings + ";", true);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, settings, "dimensions",
                    "()Lnet/minecraft/core/Registry;", false);
        }));
        MethodInsnNode settingsCall = findCall(insns, "worldGenSettings");
        MethodInsnNode dimensions = findCall(insns, "dimensions");
        assertEquals(LegacyWorldgenBridge.GENERATED, settingsCall.owner);
        assertEquals(LegacyWorldgenBridge.GENERATED, dimensions.owner,
                "the dimensions now live in the server's level stem registry");
        assertEquals(Opcodes.INVOKESTATIC, dimensions.getOpcode());
    }

    @Test
    @DisplayName("the generated classes implement the interfaces the game calls back through")
    void generatedClassesImplementTheirInterfaces() {
        ClassNode worldgen = synthetic(transformer, LegacyWorldgenBridge.GENERATED);
        assertTrue(worldgen.interfaces.contains(LegacyWorldgenBridge.CODECS),
                "the store reaches the codecs through this interface");

        ClassNode owner = synthetic(transformer, LegacyWorldgenBridge.OWNER);
        assertTrue(owner.interfaces.containsAll(List.of("net/minecraft/core/HolderOwner",
                "net/minecraft/core/HolderGetter", "net/minecraft/resources/RegistryOps$RegistryInfoLookup")),
                "one object must own the holders and name that owner to the codecs, or ids are not written");
        assertTrue(owner.methods.stream().anyMatch(m -> m.name.equals("get")
                && m.desc.equals("(L" + KEY + ";)Ljava/util/Optional;")));
        assertTrue(owner.methods.stream().anyMatch(m -> m.name.equals("lookup")));

        ClassNode packSource = synthetic(transformer, LegacyWorldgenBridge.PACK_SOURCE);
        assertTrue(packSource.interfaces.contains("net/minecraft/server/packs/repository/RepositorySource"));
        assertTrue(packSource.methods.stream().anyMatch(m -> m.name.equals("loadPacks")));

        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        assertTrue(synthetics.containsKey(LegacyWorldgenBridge.STORE),
                "the compiled store must be embedded with the generated classes");
        assertTrue(synthetics.containsKey(LegacyWorldgenBridge.CODECS));
    }

    @Test
    @DisplayName("Component.literal in the pack source is linked as an interface call")
    void packTitleUsesInterfaceMethodref() {
        ClassNode packSource = synthetic(transformer, LegacyWorldgenBridge.PACK_SOURCE);
        MethodNode load = packSource.methods.stream().filter(m -> m.name.equals("loadPacks"))
                .findFirst().orElseThrow();
        for (AbstractInsnNode insn : load.instructions) {
            if (insn instanceof MethodInsnNode m && m.name.equals("literal")) {
                assertTrue(m.itf, "Component is an interface; a plain Methodref fails to link");
                return;
            }
        }
        fail("the pack needs a title");
    }

    @Test
    @DisplayName("Registry.X_REGISTRY keys move to Registries.X, renamed keys included")
    void registryKeysMove() {
        transformer.clearRedirectsForTesting();
        LegacyRegistryKeyMoves.register(transformer);
        for (String[] move : new String[][]{{"DIMENSION_REGISTRY", "DIMENSION"},
                {"BIOME_REGISTRY", "BIOME"}, {"NOISE_GENERATOR_SETTINGS_REGISTRY", "NOISE_SETTINGS"}}) {
            List<AbstractInsnNode> insns = transformedRun(modClass(mv -> mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "net/minecraft/core/Registry", move[0], "L" + KEY + ";")));
            FieldInsnNode read = insns.stream().filter(i -> i instanceof FieldInsnNode)
                    .map(i -> (FieldInsnNode) i).findFirst().orElseThrow();
            assertEquals("net/minecraft/core/registries/Registries", read.owner, move[0] + " moved owner");
            assertEquals(move[1], read.name, move[0] + " must land on Registries." + move[1]);
        }
    }

    @Test
    @DisplayName("a lambda over a Minecraft interface keeps the host's SRG method name")
    void lambdaKeepsTargetSrgName() {
        transformer.clearRedirectsForTesting();
        String factory = "net/minecraft/world/entity/EntityType$EntityFactory";
        String samDesc = "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)"
                + "Lnet/minecraft/world/entity/Entity;";
        transformer.registerSrgNameMappings(Map.of("m_20721_", "create"), Map.of());
        transformer.registerTargetSrgNameMappings(Map.of(
                com.retromod.mapping.TargetSrgMapper.memberKey(factory, "create", samDesc), "m_20721_"), Map.of());

        List<AbstractInsnNode> insns = transformedRun(modClass(mv -> {
            Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                    "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                    + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
            Handle impl = new Handle(Opcodes.H_INVOKESTATIC, MOD_CLASS, "make", samDesc, false);
            mv.visitInvokeDynamicInsn("m_20721_", "()L" + factory + ";", metafactory,
                    Type.getType(samDesc), impl, Type.getType(samDesc));
        }));
        InvokeDynamicInsnNode indy = insns.stream().filter(i -> i instanceof InvokeDynamicInsnNode)
                .map(i -> (InvokeDynamicInsnNode) i).findFirst().orElseThrow();
        assertEquals("m_20721_", indy.name,
                "the host interface declares m_20721_; a lambda named create throws AbstractMethodError");
    }

    @Test
    @DisplayName("Entity.level read by SRG id becomes level() whatever subclass owns the read")
    void privateEntityLevelReadUsesGetter() {
        transformer.clearRedirectsForTesting();
        new Forge_1_19_4_to_1_20().registerRedirects(transformer);
        for (String owner : new String[]{"net/minecraft/world/entity/player/Player", MOD_CLASS}) {
            List<AbstractInsnNode> insns = transformedRun(modClass(mv -> {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitFieldInsn(Opcodes.GETFIELD, owner, "f_19853_", "Lnet/minecraft/world/level/Level;");
            }));
            assertTrue(insns.stream().noneMatch(i -> i instanceof FieldInsnNode),
                    "1.20 made the field private; reading it through " + owner + " is an IllegalAccessError");
            MethodInsnNode getter = findCall(insns, "level");
            assertNotNull(getter, "the read must become the public level() getter");
            assertEquals("net/minecraft/world/entity/Entity", getter.owner);
        }
    }

    @Test
    @DisplayName("a method Mojang renamed but SRG kept stays on its live SRG id")
    void renamedMethodKeepsLiveSrgId() {
        transformer.clearRedirectsForTesting();
        String entity = "net/minecraft/world/entity/Entity";
        String desc = "()Lnet/minecraft/world/level/Level;";
        transformer.registerSrgNameMappings(Map.of("m_9236_", "getLevel"), Map.of());
        transformer.registerTargetSrgNameMappings(Map.of(
                com.retromod.mapping.TargetSrgMapper.memberKey(entity, "level", desc), "m_9236_"), Map.of());

        List<AbstractInsnNode> insns = transformedRun(modClass(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, entity, "m_9236_", desc, false);
        }));
        MethodInsnNode call = findCall(insns, "m_9236_");
        assertNotNull(call, "1.20 renamed getLevel to level; the host still answers to m_9236_, "
                + "while getLevel does not exist there");
    }

    @Test
    @DisplayName("a mod's own method keeps its Mojang name even when its SRG id lives on")
    void modOverrideKeepsMojangName() {
        transformer.clearRedirectsForTesting();
        String entity = "net/minecraft/world/entity/Entity";
        String desc = "()Lnet/minecraft/world/level/Level;";
        transformer.registerSrgNameMappings(Map.of("m_9236_", "getLevel"), Map.of());
        transformer.registerTargetSrgNameMappings(Map.of(
                com.retromod.mapping.TargetSrgMapper.memberKey(entity, "level", desc), "m_9236_"), Map.of());

        List<AbstractInsnNode> insns = transformedRun(modClass(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "test/worldgen/Unrelated", "m_9236_", desc, false);
        }));
        assertNotNull(findCall(insns, "getLevel"),
                "an owner that does not declare m_9236_ on the host must not be pinned to that id");
    }

    @Test
    @DisplayName("a record accessor call takes the component's SRG name on an SRG host")
    void recordAccessorUsesComponentSrgName() {
        transformer.clearRedirectsForTesting();
        String stem = "net/minecraft/world/level/dimension/LevelStem";
        String generator = "Lnet/minecraft/world/level/chunk/ChunkGenerator;";
        transformer.registerSrgNameMappings(Map.of("m_63990_", "generator"), Map.of());
        transformer.registerTargetSrgNameMappings(Map.of(), Map.of(
                com.retromod.mapping.TargetSrgMapper.memberKey(stem, "generator", generator), "f_63976_"));

        List<AbstractInsnNode> insns = transformedRun(modClass(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, stem, "m_63990_", "()" + generator, false);
        }));
        MethodInsnNode call = insns.stream().filter(i -> i instanceof MethodInsnNode)
                .map(i -> (MethodInsnNode) i).findFirst().orElseThrow();
        assertEquals("f_63976_", call.name,
                "LevelStem became a record; its accessor is named after the component field");
    }
}
