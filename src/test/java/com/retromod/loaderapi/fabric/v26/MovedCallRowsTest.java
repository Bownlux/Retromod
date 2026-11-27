/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.shim.api.fabric.FabricRendererApiShim;
import com.retromod.shim.api.fabric.FabricRendererMaterialBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Renamed and reshaped Fabric API calls from 1.20.1 and 1.21.1 mods reach their 26.x names and helpers through the runtime's rows. */
class MovedCallRowsTest {

    private static final String API = "net/fabricmc/fabric/api/";
    private static final String MOD_CLASS = "test/fabricapi/Caller";
    private static final String CALLS = "com/retromod/legacyapi/fabric/LegacyFabricApiCalls";
    private static final String EMITTERS = "com/retromod/legacyapi/fabric/client/renderer/LegacyQuadEmitters";
    private static final String CONTEXT = "com/retromod/polyfill/minecraft/LegacyWorldRenderContext";
    private static final String VARIANT = API + "transfer/v1/fluid/FluidVariant";
    private static final String TRANSFER = API + "transfer/v1/storage/TransferVariant";
    private static final String GENERATION = API + "biome/v1/BiomeModificationContext$GenerationSettingsContext";
    private static final String CARVING = "net/minecraft/world/level/levelgen/GenerationStep$Carving";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String OLD_MESH = API + "renderer/v1/mesh/";
    private static final String MESH = API + "client/renderer/v1/mesh/";
    private static final String OLD_MATERIAL = API + "renderer/v1/material/RenderMaterial";
    private static final String OLD_WORLD_CONTEXT = API + "client/rendering/v1/WorldRenderContext";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void reset() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Every renamed member takes its 26.x name, as an interface call where the owner is an interface")
    void renamedMembers() {
        registerRuntime();
        List<String> calls = calls(transform(
                "itf " + API + "client/command/v2/FabricClientCommandSource.getWorld()Lnet/minecraft/client/multiplayer/ClientLevel;",
                "itf " + API + "biome/v1/BiomeSelectionContext.getBiomeRegistryEntry()Lnet/minecraft/core/Holder;",
                "static " + API + "networking/v1/PlayerLookup.world(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/Collection;",
                "itf " + API + "networking/v1/ServerConfigurationNetworking$Context.networkHandler()"
                        + "Lnet/minecraft/server/network/ServerConfigurationPacketListenerImpl;",
                "itf " + VARIANT + ".getComponents()Lnet/minecraft/core/component/DataComponentPatch;",
                "itf " + VARIANT + ".getComponentMap()Lnet/minecraft/core/component/DataComponentMap;",
                "itf " + OLD_MESH + "QuadEmitter.colorIndex(I)L" + OLD_MESH + "QuadEmitter;",
                "itf " + OLD_MESH + "QuadView.colorIndex()I"));
        assertEquals(List.of(
                "INVOKEINTERFACE " + API + "client/command/v2/FabricClientCommandSource.getLevel()Lnet/minecraft/client/multiplayer/ClientLevel;",
                "INVOKEINTERFACE " + API + "biome/v1/BiomeSelectionContext.getBiomeHolder()Lnet/minecraft/core/Holder;",
                "INVOKESTATIC " + API + "networking/v1/PlayerLookup.level(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/Collection;",
                "INVOKEINTERFACE " + API + "networking/v1/ServerConfigurationNetworking$Context.packetListener()"
                        + "Lnet/minecraft/server/network/ServerConfigurationPacketListenerImpl;",
                "INVOKEINTERFACE " + TRANSFER + ".getComponentsPatch()Lnet/minecraft/core/component/DataComponentPatch;",
                "INVOKEINTERFACE " + TRANSFER + ".getComponents()Lnet/minecraft/core/component/DataComponentMap;",
                "INVOKEINTERFACE " + MESH + "QuadEmitter.tintIndex(I)L" + MESH + "QuadEmitter;",
                "INVOKEINTERFACE " + MESH + "QuadView.tintIndex()I"), calls);
    }

    @Test
    @DisplayName("A 1.20.1 fluid tag and a carving step reach the rebuilt helper, which the jar then carries")
    void reshapedCalls() {
        registerRuntime();
        ClassNode out = transform(
                "static itf " + VARIANT + ".of(Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/nbt/CompoundTag;)L" + VARIANT + ";",
                "itf " + VARIANT + ".getNbt()Lnet/minecraft/nbt/CompoundTag;",
                "itf " + GENERATION + ".addCarver(L" + CARVING + ";" + KEY + ")V",
                "itf " + GENERATION + ".removeCarver(L" + CARVING + ";" + KEY + ")Z");
        assertEquals(List.of(
                "INVOKESTATIC " + CALLS + ".fluidOf(Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/nbt/CompoundTag;)L" + VARIANT + ";",
                "INVOKESTATIC " + CALLS + ".fluidNbt(L" + VARIANT + ";)Lnet/minecraft/nbt/CompoundTag;",
                "INVOKESTATIC " + CALLS + ".addCarver(L" + GENERATION + ";Ljava/lang/Object;" + KEY + ")V",
                "INVOKESTATIC " + CALLS + ".removeCarver(L" + GENERATION + ";Ljava/lang/Object;" + KEY + ")Z"), calls(out));
        for (MethodNode method : out.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) assertFalse(call.itf, "the helper is a class: " + call.name);
            }
        }
        assertTrue(transformer.getSyntheticClasses().containsKey(CALLS), "the helper is embedded");
    }

    @Test
    @DisplayName("The carver rows serve only mods built before 1.21.2, also under a carving step a class redirect renamed")
    void carverRowsServeOlderModsOnly() {
        // A stand-in for the removed carving step, registered by a shim before the runtime.
        String standIn = "test/stand/CarvingStep";
        transformer.registerClassRedirect(CARVING, standIn);
        registerRuntime();
        String addCarver = "itf " + GENERATION + ".addCarver(L" + CARVING + ";" + KEY + ")V";

        transformer.setModSourceVersion("1.21.4");
        assertEquals(List.of("INVOKEINTERFACE " + GENERATION + ".addCarver(L" + standIn + ";" + KEY + ")V"),
                calls(transform(addCarver)), "a 1.21.4 mod keeps its own call");
        transformer.setModSourceVersion("1.20.1");
        assertEquals(List.of("INVOKESTATIC " + CALLS + ".addCarver(L" + GENERATION + ";Ljava/lang/Object;" + KEY + ")V"),
                calls(transform(addCarver)), "a 1.20.1 mod reaches the helper");
    }

    @Test
    @DisplayName("The old spawn settings context is the renamed one, its weightless addSpawn keeps the weight, and the removed spawn-friendly flag is a no-op")
    void spawnSettingsContext() {
        registerRuntime();
        String biome = API + "biome/v1/BiomeModificationContext";
        String old = biome + "$SpawnSettingsContext";
        String now = biome + "$MobSpawnSettingsContext";
        List<String> calls = calls(transform(
                "itf " + biome + ".getSpawnSettings()L" + old + ";",
                "itf " + old + ".addSpawn(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/world/level/biome/MobSpawnSettings$SpawnerData;)V",
                "itf " + old + ".setSpawnCost(Lnet/minecraft/world/entity/EntityType;DD)V",
                "itf " + old + ".setCreatureSpawnProbability(F)V",
                "itf " + old + ".clearSpawnCost(Lnet/minecraft/world/entity/EntityType;)V",
                "itf " + old + ".setPlayerSpawnFriendly(Z)V"));
        assertEquals(List.of("INVOKEINTERFACE " + biome + ".getMobSpawnSettings()L" + now + ";",
                "INVOKESTATIC com/retromod/shim/common/embedded/LegacySpawns.addContextSpawn(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                "INVOKEINTERFACE " + now + ".addMobCharge(Lnet/minecraft/world/entity/EntityType;DD)V",
                "INVOKEINTERFACE " + now + ".setCreatureGenerationProbability(F)V",
                "INVOKEINTERFACE " + now + ".clearMobCharge(Lnet/minecraft/world/entity/EntityType;)V",
                "INVOKESTATIC com/retromod/shim/common/embedded/LegacySpawns.setPlayerSpawnFriendly(Ljava/lang/Object;Z)V"), calls);
        assertTrue(transformer.getSyntheticClasses().containsKey("com/retromod/shim/common/embedded/LegacySpawns"));
    }

    @Test
    @DisplayName("The 1.20.1 world render context's partial tick, projection and outline flag reach the polyfill, cast back where needed")
    void worldRenderContextReads() {
        registerRuntime();
        ClassNode out = transform("itf " + OLD_WORLD_CONTEXT + ".tickDelta()F",
                "itf " + OLD_WORLD_CONTEXT + ".projectionMatrix()Lorg/joml/Matrix4f;",
                "itf " + OLD_WORLD_CONTEXT + ".blockOutlines()Z");
        assertEquals(List.of("INVOKESTATIC " + CONTEXT + ".tickDelta(Ljava/lang/Object;)F",
                "INVOKESTATIC " + CONTEXT + ".projectionMatrix(Ljava/lang/Object;)Ljava/lang/Object;",
                "INVOKESTATIC " + CONTEXT + ".blockOutlines(Ljava/lang/Object;)Z"), calls(out));
        MethodInsnNode projection = call(out, "projectionMatrix");
        assertTrue(projection.getNext() instanceof TypeInsnNode cast && cast.desc.equals("org/joml/Matrix4f"),
                "the polyfill returns Object, so the caller's Matrix4f type must be restored");
        assertTrue(transformer.getSyntheticClasses().containsKey(CONTEXT));
    }

    @Test
    @DisplayName("An old render material and fromVanilla go to the rebuilt renderer, and the mutable face normal to the quad helper")
    void quadMaterialCalls() {
        registerRuntime();
        String quad = "Lnet/minecraft/client/resources/model/geometry/BakedQuad;";
        String face = "Lnet/minecraft/core/Direction;";
        String oldEmitter = "L" + OLD_MESH + "QuadEmitter;";
        ClassNode out = transform("itf " + OLD_MESH + "QuadEmitter.material(L" + OLD_MATERIAL + ";)" + oldEmitter,
                "itf " + OLD_MESH + "QuadEmitter.fromVanilla(" + quad + "L" + OLD_MATERIAL + ";" + face + ")" + oldEmitter,
                "itf " + OLD_MESH + "QuadEmitter.faceNormal()Lorg/joml/Vector3f;");
        String self = "L" + MESH + "QuadEmitter;";
        // Batch D2 rebuilds the old material type, so the material calls take it instead of the inert stand-in.
        String quads = "com/retromod/legacyapi/fabric/client/renderer/LegacyQuadViews";
        String material = "Lcom/retromod/legacyapi/fabric/client/renderer/LegacyRenderMaterial;";
        assertEquals(List.of("INVOKESTATIC " + quads + ".material(" + self + material + ")" + self,
                "INVOKESTATIC " + quads + ".fromVanilla(" + self + quad + material + face + ")" + self,
                "INVOKESTATIC " + EMITTERS + ".faceNormal(L" + MESH + "QuadView;)Lorg/joml/Vector3f;"), calls(out));
        assertTrue(transformer.getSyntheticClasses().containsKey(EMITTERS));
    }

    // ---- fixtures ----

    /** The shims' class moves these rows build on, then the runtime as a 26.3 Fabric host registers it. */
    private void registerRuntime() {
        transformer.registerClassRedirect(OLD_WORLD_CONTEXT, API + "client/rendering/v1/level/LevelRenderContext");
        new FabricRendererApiShim().registerRedirects(transformer);
        new FabricRendererMaterialBridge().registerRedirects(transformer);
        LoaderApiRuntime.prepareForced(transformer, new LoaderApiContext("fabric", "26.3", LoaderApiContext.TransformPath.TEST, null));
    }

    /** One method per call: {@code [static] [itf] owner.name(desc)}, made with null or zero arguments. */
    private ClassNode transform(String... calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        int index = 0;
        for (String call : calls) {
            String member = call;
            boolean isStatic = member.startsWith("static ");
            if (isStatic) member = member.substring(7);
            boolean itf = member.startsWith("itf ");
            if (itf) member = member.substring(4);
            String owner = member.substring(0, member.indexOf('.'));
            String name = member.substring(member.indexOf('.') + 1, member.indexOf('('));
            String desc = member.substring(member.indexOf('('));
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call" + index++, "()V", null, null);
            mv.visitCode();
            if (!isStatic) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
            }
            for (Type argument : Type.getArgumentTypes(desc)) {
                mv.visitInsn(switch (argument.getSort()) {
                    case Type.OBJECT, Type.ARRAY -> Opcodes.ACONST_NULL;
                    case Type.DOUBLE -> Opcodes.DCONST_0;
                    case Type.FLOAT -> Opcodes.FCONST_0;
                    case Type.LONG -> Opcodes.LCONST_0;
                    default -> Opcodes.ICONST_0;
                });
            }
            int opcode = isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            mv.visitMethodInsn(opcode, owner, name, desc, itf);
            int size = Type.getReturnType(desc).getSize();
            if (size > 0) mv.visitInsn(size == 2 ? Opcodes.POP2 : Opcodes.POP);
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

    private static MethodInsnNode call(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals(name)) return call;
            }
        }
        throw new AssertionError("no call to " + name);
    }

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
}
