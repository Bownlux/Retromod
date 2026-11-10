/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Host-proven Mixin repairs for 26.x member drift (Hold My Items, #281/#312). */
class MixinHostDriftRepairTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
    private static final String SECTIONS = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;";
    private static final String DELTA_TRACKER = "Lnet/minecraft/client/DeltaTracker;";
    private static final String FEATURES = "net/minecraft/client/renderer/feature/FeatureRenderDispatcher";
    private static final String CAMERA = "net/minecraft/client/Camera";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String HAND = "Lnet/minecraft/world/InteractionHand;";
    private static final String ANIMATION = "Lnet/minecraft/world/item/component/SwingAnimation;";
    private static final String SWING = "net/minecraft/world/entity/LivingEntity$SwingDescription";
    private static final String RENDERER = "net/minecraft/client/renderer/ItemInHandRenderer";
    private static final String OLD_SELECTION = "net/minecraft/client/renderer/ItemInHandRenderer$HandRenderSelection";
    private static final String NEW_SELECTION =
            "net/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState$HandRenderSelection";

    // 26.2's ReloadableServerResources.loadResources, and the 1.20.1 arguments Balm's handler captures.
    private static final String RELOADABLE = "net/minecraft/server/ReloadableServerResources";
    private static final String RESOURCE_MANAGER = "Lnet/minecraft/server/packs/resources/ResourceManager;";
    private static final String EXECUTOR = "Ljava/util/concurrent/Executor;";
    private static final String LOAD_RESOURCES_ARGS = RESOURCE_MANAGER + "Lnet/minecraft/core/LayeredRegistryAccess;"
            + "Ljava/util/List;Lnet/minecraft/world/flag/FeatureFlagSet;Lnet/minecraft/commands/Commands$CommandSelection;"
            + "Lnet/minecraft/server/permissions/PermissionSet;" + EXECUTOR + EXECUTOR;
    private static final String LOAD_RESOURCES = "(" + LOAD_RESOURCES_ARGS + ")Ljava/util/concurrent/CompletableFuture;";
    private static final String BALM_CAPTURES = RESOURCE_MANAGER + "Lnet/minecraft/core/RegistryAccess$Frozen;"
            + "Lnet/minecraft/world/flag/FeatureFlagSet;Lnet/minecraft/commands/Commands$CommandSelection;I"
            + EXECUTOR + EXECUTOR;
    // A target whose host appended two flags, and one that gained a middle and a trailing parameter and a result.
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String DESTROY_ARGS = "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;L" + STACK + ";";
    private static final String PLAYER_DESTROY = "(" + DESTROY_ARGS + "ZZ)V";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String SERVER_LEVEL = "Lnet/minecraft/server/level/ServerLevel;";
    private static final String TELEPORT_TO = "(" + SERVER_LEVEL + "DDDLjava/util/Set;FFZ)Z";
    // 26.3's VillagerType.byBiome returns the type's key, where 1.21.1 returned the type.
    private static final String VILLAGER_TYPE = "net/minecraft/world/entity/npc/villager/VillagerType";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String MODIFY_RETURN_VALUE = "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
    // 26.3's ChunkGenerator.getMobsAt takes the Level where 1.21.1 took the Holder<Biome> (Moonlight).
    private static final String CHUNK_GENERATOR = "net/minecraft/world/level/chunk/ChunkGenerator";
    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String MOBS_AT_REST = "Lnet/minecraft/world/level/StructureManager;"
            + "Lnet/minecraft/world/entity/MobCategory;" + POS;
    private static final String WEIGHTED = "Lnet/minecraft/util/random/WeightedList;";
    private static final String GET_MOBS_AT = "(" + LEVEL + MOBS_AT_REST + ")" + WEIGHTED;
    private static final String STRUCTURE = "Lnet/minecraft/world/level/levelgen/structure/Structure;";
    private static final String ENTRY = "Ljava/util/Map$Entry;";
    private static final String MOONLIGHT_DESC = "(" + HOLDER + MOBS_AT_REST + CIR + STRUCTURE + ENTRY + ")V";
    private static final String COERCE = "Lorg/spongepowered/asm/mixin/injection/Coerce;";
    private static final String SPAWN_OVERRIDES = STRUCTURE + "spawnOverrides()Ljava/util/Map;";
    private static final String SHARE = "Lcom/llamalad7/mixinextras/sugar/Share;";
    private static final String LOCAL_REF = "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;";
    private static final String LOCAL_INT_REF = "Lcom/llamalad7/mixinextras/sugar/ref/LocalIntRef;";

    @TempDir
    Path tempDir;

    private MixinHostDriftRepair repair;
    private FuzzyMethodResolver host;

    @BeforeEach
    void indexHost() throws IOException {
        Path jar = tempDir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(GAME_RENDERER, "java/lang/Object", c -> method(c, 0, "render", "()V")));
            put(out, hostClass(LEVEL_RENDERER, "java/lang/Object",
                    c -> method(c, 0, "render", "(" + DELTA_TRACKER + "Z)V")));
            put(out, hostClass(FEATURES, "java/lang/Object",
                    c -> method(c, Opcodes.ACC_STATIC, "renderAllFeatures", "(Ljava/lang/Object;)V")));
            put(out, hostClass(CAMERA, "java/lang/Object", c -> {
                c.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "FORWARDS",
                        "Lorg/joml/Vector3fc;", null, null).visitEnd();
                method(c, 0, "setRotation", "(FF)V");
            }));
            put(out, hostClass(LIVING, "java/lang/Object", c -> {
                method(c, 0, "swing", "(" + HAND + ANIMATION + "Z)Z");
                method(c, Opcodes.ACC_PRIVATE, "getModifiedSwingDuration", "(" + ANIMATION + ")I");
                method(c, 0, "getCurrentSwing", "()L" + SWING + ";");
                method(c, 0, "getMainHandItem", "()L" + STACK + ";");
            }));
            put(out, hostClass(LOCAL_PLAYER, LIVING, c -> {}));
            put(out, hostClass(STACK, "java/lang/Object",
                    c -> method(c, 0, "getAttackAnimation", "()" + ANIMATION)));
            put(out, hostClass(MINECRAFT, "java/lang/Object", c -> {
                method(c, 0, "startUseItem", "()V");
                method(c, 0, "isPaused", "()Z");
                method(c, 0, "getTitle", "()Ljava/lang/String;");
            }));
            put(out, hostClass(RENDERER, "java/lang/Object",
                    c -> method(c, 0, "submitArmWithItem", "(F)V")));
            put(out, hostClass(NEW_SELECTION, "java/lang/Object", c -> {}));
            put(out, hostClass("net/minecraft/world/InteractionHand", "java/lang/Object", c -> {}));
            put(out, hostClass(RELOADABLE, "java/lang/Object",
                    c -> method(c, Opcodes.ACC_STATIC, "loadResources", LOAD_RESOURCES)));
            put(out, hostClass(BLOCK, "java/lang/Object", c -> {
                method(c, 0, "playerDestroy", PLAYER_DESTROY);
                method(c, 0, "onPlace", "(" + POS + POS + ")V");
            }));
            put(out, hostClass(SERVER_PLAYER, LIVING, c -> method(c, 0, "teleportTo", TELEPORT_TO)));
            put(out, hostClass(CHUNK_GENERATOR, "java/lang/Object", c -> method(c, 0, "getMobsAt", GET_MOBS_AT)));
            put(out, hostClass(VILLAGER_TYPE, "java/lang/Object",
                    c -> method(c, Opcodes.ACC_STATIC, "byBiome", "(" + HOLDER + ")" + RESOURCE_KEY)));
        }
        host = new FuzzyMethodResolver();
        host.indexJar(jar);
        assertTrue(host.isIndexed(), "the synthetic host jar must be indexed");
        repair = new MixinHostDriftRepair(host,
                Map.of(new RetromodTransformer.MethodKey(RENDERER, "renderArmWithItem", "(F)V"),
                        new RetromodTransformer.MethodTarget(RENDERER, "submitArmWithItem", "(F)V", false)),
                Map.of(OLD_SELECTION, NEW_SELECTION));
    }

    @Test
    void dropsCapturesTheHandlerNeverReadsWhenTheTargetLostThem() {
        byte[] mixin = mixin("GameRendererMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "deltaTime", "(" + DELTA_TRACKER + "Z" + CI + ")V",
                    "render", "HEAD", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 4);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "deltaTime");

        assertEquals("(" + CI + ")V", handler.desc,
                "GameRenderer.render() lost both parameters, so only the callback may remain");
    }

    @Test
    void dropsAnUnreadTrailingCaptureAndKeepsTheLeadingOnes() {
        byte[] mixin = mixin("LevelRendererMixin", LEVEL_RENDERER, c -> {
            MethodVisitor handler = inject(c, "beforeRender",
                    "(" + DELTA_TRACKER + "Z" + SECTIONS + CI + ")V", "render", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitVarInsn(Opcodes.ILOAD, 2);
            handler.visitInsn(Opcodes.POP);
            handler.visitVarInsn(Opcodes.ALOAD, 4);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 5);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "beforeRender");

        assertEquals("(" + DELTA_TRACKER + "Z" + CI + ")V", handler.desc,
                "26.2 dropped render's trailing parameter; the unread capture goes, the rest stay");
        VarInsnNode callback = (VarInsnNode) handler.instructions.get(4);
        assertEquals(3, callback.var, "the callback moves down into the dropped capture's slot");
    }

    @Test
    void keepsATrailingDropWhoseTypeAKeptCaptureShares() {
        byte[] mixin = mixin("AmbiguousDropMixin", LEVEL_RENDERER, c -> {
            MethodVisitor handler = inject(c, "beforeRender",
                    "(" + DELTA_TRACKER + "ZZ" + CI + ")V", "render", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ILOAD, 2);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 5);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin),
                "the host may have dropped the first boolean, so the read one could change meaning");
    }

    @Test
    void appendsTheHostsTrailingParametersWhenTheHandlerReadsAKeptCapture() {
        byte[] mixin = mixin("DestroyMixin", BLOCK, c -> {
            MethodVisitor handler = inject(c, "onDestroy", "(" + DESTROY_ARGS + CI + ")V", "playerDestroy", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 3);
            handler.visitVarInsn(Opcodes.ASTORE, 8);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 9);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "onDestroy");

        assertEquals("(" + DESTROY_ARGS + "ZZ" + CI + ")V", handler.desc,
                "Mixin refuses a prefix of the arguments, so the two appended flags join the kept captures");
        assertMixinAccepts(handler, PLAYER_DESTROY);
        assertEquals(List.of(3, 10), varSlots(handler), "the body local moves past the appended slots");
    }

    @Test
    void takesOnlyTheCallbackWhenTheHandlerReadsNoKeptCapture() {
        byte[] mixin = mixin("DestroyNoticeMixin", BLOCK, c -> {
            MethodVisitor handler = inject(c, "onDestroy", "(" + DESTROY_ARGS + CI + ")V", "playerDestroy", "HEAD", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 8);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "onDestroy");

        assertEquals("(" + CI + ")V", handler.desc, "a handler that reads no capture needs none");
        assertMixinAccepts(handler, PLAYER_DESTROY);
    }

    @Test
    void completesAnInsertedAndAnAppendedParameterAroundReadCaptures() {
        byte[] mixin = mixin("TeleportMixin", SERVER_PLAYER, c -> {
            MethodVisitor handler = inject(c, "onTeleport", "(" + SERVER_LEVEL + "DDDFF" + CI + ")V",
                    "teleportTo", "HEAD", 0);
            handler.visitVarInsn(Opcodes.DLOAD, 4);
            handler.visitInsn(Opcodes.POP2);
            handler.visitVarInsn(Opcodes.FLOAD, 9);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(2, 11);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "onTeleport");

        assertEquals("(" + SERVER_LEVEL + "DDDLjava/util/Set;FFZ" + CIR + ")V", handler.desc,
                "the inserted Set and the appended flag both join, and the unread callback becomes returnable");
        assertMixinAccepts(handler, TELEPORT_TO);
        assertEquals(List.of(4, 10), varSlots(handler), "y stays put and pitch moves past the inserted Set");
    }

    @Test
    void completesAKeptLeadingCaptureWithEveryHostParameter() {
        byte[] mixin = mixin("ReloadReaderMixin", RELOADABLE, c -> {
            MethodVisitor handler = inject(c, "onLoad", "(" + BALM_CAPTURES + CIR + ")V", "loadResources", "RETURN",
                    Opcodes.ACC_STATIC);
            handler.visitVarInsn(Opcodes.ALOAD, 0);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 8);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "onLoad");

        assertEquals("(" + LOAD_RESOURCES_ARGS + CIR + ")V", handler.desc,
                "the read ResourceManager stays first, and every 26.2 argument follows it");
        assertMixinAccepts(handler, LOAD_RESOURCES);
        assertEquals(List.of(0), varSlots(handler));
    }

    @Test
    void keepsAReadCaptureThatAlsoFitsALaterArgument() {
        // The host's onPlace(BlockPos, BlockPos) may have put the old position first or second.
        byte[] mixin = mixin("PlaceMixin", BLOCK, c -> {
            MethodVisitor handler = inject(c, "onPlace", "(" + POS + CI + ")V", "onPlace", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 3);
            handler.visitEnd();
        });
        byte[] unread = mixin("PlaceNoticeMixin", BLOCK, c -> {
            MethodVisitor handler = inject(c, "onPlace", "(" + POS + CI + ")V", "onPlace", "HEAD", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 3);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin), "the read position could bind to either argument");
        assertEquals("(" + CI + ")V", method(repair.apply(unread), "onPlace").desc,
                "an unread capture binds to nothing, so the callback alone is safe");
    }

    @Test
    void keepsAHandlerThatNamesTheSecondHalfOfAWideCapture() {
        byte[] mixin = mixin("WideMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "onRender", "(ZJ" + CI + ")V", "render", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ILOAD, 3);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 5);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin), "slot 3 has no place in the new layout");
    }

    @Test
    void dropsTheStaleMethodParametersAttribute() {
        ClassNode node = read(mixin("NamedMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "onRender", "(" + DELTA_TRACKER + "Z" + CI + ")V", "render", "HEAD", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 4);
            handler.visitEnd();
        }));
        MethodNode named = method(node, "onRender");
        named.parameters = new java.util.ArrayList<>(List.of(new org.objectweb.asm.tree.ParameterNode("tracker", 0),
                new org.objectweb.asm.tree.ParameterNode("flag", 0), new org.objectweb.asm.tree.ParameterNode("ci", 0)));
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);

        MethodNode handler = method(repair.apply(writer.toByteArray()), "onRender");

        assertEquals("(" + CI + ")V", handler.desc);
        assertNull(handler.parameters, "three names for one parameter make reflection throw MalformedParametersException");
    }

    @Test
    void switchesAReadCallbackToReturnableWhenTheTargetGainedAResult() {
        byte[] mixin = mixin("PauseMixin", MINECRAFT, c -> {
            MethodVisitor handler = inject(c, "onPaused", "(" + CI + ")V", "isPaused", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CI.substring(1, CI.length() - 1), "cancel", "()V", false);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 2);
            handler.visitEnd();
        });
        byte[] losing = mixin("RenderResultMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "onRender", "(" + CIR + ")V", "render", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 2);
            handler.visitEnd();
        });

        assertEquals("(" + CIR + ")V", method(repair.apply(mixin), "onPaused").desc,
                "CallbackInfoReturnable extends CallbackInfo, so cancel() still works");
        assertArrayEquals(losing, repair.apply(losing), "a read returnable callback cannot become a plain one");
    }

    @Test
    void removesACancellingHandlerWhenTheTargetGainedAnObjectResult() {
        byte[] mixin = mixin("TitleMixin", MINECRAFT, c -> {
            MethodVisitor handler = inject(c, "onTitle", "(" + CI + ")V", "getTitle", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CI.substring(1, CI.length() - 1), "cancel", "()V", false);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 2);
            handler.visitEnd();
            MethodVisitor other = inject(c, "onTick", "(" + CI + ")V", "tick", "HEAD", 0);
            other.visitInsn(Opcodes.RETURN);
            other.visitMaxs(0, 2);
            other.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));

        assertNull(method(repaired, "onTitle"), "Mixin refuses its CallbackInfo, and a CallbackInfoReturnable "
                + "cancel() would make getTitle() return null to its callers");
        assertNotNull(method(repaired, "onTick"), "the mixin's other handlers stay");
    }

    @Test
    void retypesAReadButNeverCancelledCallbackAndItsLocalEntry() {
        byte[] mixin = mixin("TitleCheckMixin", MINECRAFT, c -> {
            MethodVisitor handler = inject(c, "onTitle", "(" + CI + ")V", "getTitle", "HEAD", 0);
            org.objectweb.asm.Label start = new org.objectweb.asm.Label();
            org.objectweb.asm.Label end = new org.objectweb.asm.Label();
            handler.visitLabel(start);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CI.substring(1, CI.length() - 1), "isCancelled", "()Z", false);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitLabel(end);
            handler.visitLocalVariable("callbackInfo", CI, null, start, end, 1);
            handler.visitMaxs(1, 2);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "onTitle");

        assertEquals("(" + CIR + ")V", handler.desc);
        assertEquals(CIR, handler.localVariables.stream().filter(local -> local.index == 1).findFirst()
                .orElseThrow().desc, "the local entry names the callback's new type");
    }

    @Test
    void repairsBalmsReloadHandlerToTheCallbackAlone() {
        // Balm 1.20.1 captures every old loadResources argument and reads only the callback.
        byte[] mixin = mixin("ReloadableServerResourcesMixin", RELOADABLE, c -> {
            MethodVisitor handler = inject(c, "loadResources", "(" + BALM_CAPTURES + CIR + ")V", "loadResources",
                    "RETURN", Opcodes.ACC_STATIC);
            handler.visitVarInsn(Opcodes.ALOAD, 7);
            handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR.substring(1, CIR.length() - 1), "getReturnValue",
                    "()Ljava/lang/Object;", false);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 8);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "loadResources");

        assertEquals("(" + CIR + ")V", handler.desc,
                "only the ResourceManager lines up with 26.2, and the handler never reads it");
        assertMixinAccepts(handler, LOAD_RESOURCES);
        assertTrue((handler.access & Opcodes.ACC_STATIC) != 0);
        assertEquals(List.of(0), varSlots(handler), "the callback moves to the first slot");
        assertEquals(List.of("loadResources"), selectors(handler, INJECT));
    }

    @Test
    void keepsAHandlerThatReadsACaptureTheTargetLost() {
        byte[] mixin = mixin("ReadsCaptureMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "deltaTime", "(" + DELTA_TRACKER + "Z" + CI + ")V",
                    "render", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 4);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin),
                "a handler that uses a removed capture has no safe layout and must be left alone");
    }

    @Test
    void leavesASelectorWrittenInRefmapSourceNamesAlone() {
        // Yarn source text: Mixin resolves it through the mod's refmap, which may name a
        // different host method than the same-named GameRenderer.render().
        byte[] mixin = mixin("YarnSelectorMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "deltaTime", "(" + DELTA_TRACKER + "Z" + CI + ")V",
                    "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", "HEAD", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 4);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin),
                "a selector naming classes the host lacks is a refmap key, not proof of the target");
    }

    @Test
    void makesAHandlerStaticWhenItsTargetBecameStatic() {
        byte[] mixin = mixin("FeatureMixin", FEATURES, c -> {
            MethodVisitor handler = inject(c, "tester", "(" + CI + ")V", "renderAllFeatures", "TAIL", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 2);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "tester");

        assertTrue((handler.access & Opcodes.ACC_STATIC) != 0,
                "the handler never reads this, so it can follow its target to static");
        assertEquals("(" + CI + ")V", handler.desc);
    }

    @Test
    void retypesAReadOnlyJomlShadowAndCastsEachRead() {
        byte[] mixin = mixin("CameraMixin", CAMERA, c -> {
            FieldVisitor field = c.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "FORWARDS",
                    "Lorg/joml/Vector3f;", null, null);
            field.visitAnnotation(SHADOW, true).visitEnd();
            field.visitEnd();
            MethodVisitor reader = c.visitMethod(Opcodes.ACC_PUBLIC, "read", "()Ljava/lang/Object;", null, null);
            reader.visitCode();
            reader.visitFieldInsn(Opcodes.GETSTATIC, "test/mixin/CameraMixin", "FORWARDS", "Lorg/joml/Vector3f;");
            reader.visitInsn(Opcodes.ARETURN);
            reader.visitMaxs(1, 1);
            reader.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));
        FieldNode forwards = repaired.fields.get(0);
        MethodNode reader = method(repaired, "read");
        FieldInsnNode get = first(reader, FieldInsnNode.class);

        assertEquals("Lorg/joml/Vector3fc;", forwards.desc, "the shadow must match the host's read-only type");
        assertEquals("Lorg/joml/Vector3fc;", get.desc);
        assertTrue(get.getNext() instanceof TypeInsnNode cast && cast.desc.equals("org/joml/Vector3f"),
                "each read must be cast back to the type the mixin body uses");
    }

    @Test
    void bridgesTheRemovedSwingDurationShadowToTheSwingState() {
        byte[] mixin = mixin("LivingEntityMixin", LIVING, c -> {
            MethodVisitor shadow = c.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT,
                    "getCurrentSwingDuration", "()I", null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitEnd();
        }, true);

        ClassNode repaired = read(repair.apply(mixin));
        MethodNode bridge = method(repaired, "getCurrentSwingDuration");

        assertTrue(hasAnnotation(bridge, UNIQUE), "the old shadow must become mixin-owned");
        assertFalse(hasAnnotation(bridge, SHADOW));
        assertTrue(calls(bridge, "getCurrentSwing") && calls(bridge, "getModifiedSwingDuration"),
                "the bridge must read the running swing and fall back to the modified duration");
        assertNotNull(method(repaired, "getModifiedSwingDuration"),
                "the private host method needs a shadow so the bridge can call it");
    }

    @Test
    void retypesAnInjectForATargetThatGainedAParameterAndAReturnValue() {
        byte[] mixin = mixin("SwingMixin", LIVING, c -> {
            MethodVisitor handler = inject(c, "onSwingHand", "(" + HAND + "Z" + CI + ")V",
                    "swing(" + HAND + "Z)V", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitVarInsn(Opcodes.ILOAD, 2);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 4);
            handler.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));
        MethodNode handler = method(repaired, "onSwingHand");

        assertEquals("(" + HAND + ANIMATION + "Z" + CIR + ")V", handler.desc,
                "the new SwingAnimation slot is inserted and the unread callback becomes returnable");
        assertEquals(List.of("swing"), selectors(handler, INJECT));
        assertEquals(3, ((org.objectweb.asm.tree.VarInsnNode) handler.instructions.toArray()[2]).var,
                "the boolean capture moves past the inserted SwingAnimation slot");
    }

    @Test
    void regrowsARedirectWhoseCallGainedTrailingParameters() {
        String oldCall = "L" + LOCAL_PLAYER + ";swing(" + HAND + ")V";
        byte[] mixin = mixin("UseItemMixin", MINECRAFT, c -> {
            MethodVisitor handler = c.visitMethod(Opcodes.ACC_PRIVATE, "doItemUse",
                    "(L" + LOCAL_PLAYER + ";" + HAND + ")V", null, null);
            AnnotationVisitor redirect = handler.visitAnnotation(REDIRECT, true);
            AnnotationVisitor methods = redirect.visitArray("method");
            methods.visit(null, "startUseItem");
            methods.visitEnd();
            AnnotationVisitor at = redirect.visitAnnotation("at", AT);
            at.visit("value", "INVOKE");
            at.visit("target", oldCall);
            at.visitEnd();
            redirect.visitEnd();
            handler.visitCode();
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitVarInsn(Opcodes.ALOAD, 2);
            handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOCAL_PLAYER, "swing", "(" + HAND + ")V", false);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(2, 3);
            handler.visitEnd();
        });

        MethodNode handler = method(read(repair.apply(mixin)), "doItemUse");
        MethodInsnNode passThrough = first(handler, MethodInsnNode.class);

        assertEquals("(L" + LOCAL_PLAYER + ";" + HAND + ANIMATION + "Z)Z", handler.desc,
                "the handler must accept and return what the new call does");
        assertEquals("(" + HAND + ANIMATION + "Z)Z", passThrough.desc,
                "the pass-through call must forward the new arguments");
    }

    @Test
    void renamesAShadowThroughAHostProvenMethodRedirect() {
        byte[] mixin = mixin("ArmMixin", RENDERER, c -> {
            MethodVisitor shadow = c.visitMethod(Opcodes.ACC_PRIVATE, "renderArmWithItem", "(F)V", null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitCode();
            shadow.visitInsn(Opcodes.RETURN);
            shadow.visitMaxs(0, 2);
            shadow.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));

        assertNotNull(method(repaired, "submitArmWithItem"), "26.2 renamed the arm call to submitArmWithItem");
        assertNull(method(repaired, "renderArmWithItem"));
    }

    @Test
    void turnsAShadowWhoseCallsGoToAnAdapterIntoAnAdapterCall() {
        String attribute = "Lnet/minecraft/world/entity/ai/attributes/Attribute;";
        String instance = "Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;";
        String oldDesc = "(" + attribute + ")" + instance;
        String adapter = "com/retromod/generated/LegacyRegistryHolders";
        String adapterDesc = "(L" + LIVING + ";Ljava/lang/Object;)" + instance;
        MixinHostDriftRepair adapting = new MixinHostDriftRepair(host,
                Map.of(new RetromodTransformer.MethodKey(LIVING, "getAttribute", oldDesc),
                        new RetromodTransformer.MethodTarget(adapter, "LivingEntity$getAttribute", adapterDesc)),
                Map.of());
        byte[] mixin = mixin("AttributeMixin", LIVING, c -> {
            MethodVisitor shadow = c.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    "getAttribute", oldDesc, null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitEnd();
        }, true);

        MethodNode bridged = method(adapting.apply(mixin), "getAttribute");

        assertFalse(hasAnnotation(bridged, SHADOW), "the host has no getAttribute(Attribute) to shadow");
        assertTrue(hasAnnotation(bridged, UNIQUE));
        assertEquals(0, bridged.access & Opcodes.ACC_ABSTRACT);
        MethodInsnNode call = first(bridged, MethodInsnNode.class);
        assertEquals(adapter, call.owner);
        assertEquals(adapterDesc, call.desc, "the mixin must call the same adapter its callers use");
    }

    @Test
    void leavesAnAccessorInterfaceShadowAlone() {
        String oldDesc = "(Ljava/lang/Object;)V";
        MixinHostDriftRepair adapting = new MixinHostDriftRepair(host,
                Map.of(new RetromodTransformer.MethodKey(LIVING, "tick", oldDesc),
                        new RetromodTransformer.MethodTarget("com/retromod/generated/Adapters",
                                "tick", "(L" + LIVING + ";Ljava/lang/Object;)V")),
                Map.of());
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "test/mixin/TickAccessor", null, "java/lang/Object", null);
        AnnotationVisitor annotation = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = annotation.visitArray("value");
        value.visit(null, Type.getObjectType(LIVING));
        value.visitEnd();
        annotation.visitEnd();
        MethodVisitor shadow = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "tick", oldDesc,
                null, null);
        shadow.visitAnnotation(SHADOW, true).visitEnd();
        shadow.visitEnd();
        writer.visitEnd();
        byte[] mixin = writer.toByteArray();

        assertArrayEquals(mixin, adapting.apply(mixin), "an interface mixin is not merged into its target");
    }

    @Test
    void dropsAShadowFieldTheMixinNeverUsesWhenTheHostChangedIt() {
        byte[] mixin = mixin("SelectorMixin", LIVING, c -> {
            FieldVisitor field = c.visitField(Opcodes.ACC_PRIVATE, "predicate",
                    "Ljava/util/function/Predicate;", null, null);
            field.visitAnnotation(SHADOW, true).visitEnd();
            field.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));

        assertTrue(repaired.fields.isEmpty(),
                "an unused shadow of a field the host no longer has in that shape only fails validation");
    }

    @Test
    void keepsAShadowFieldTheMixinReads() {
        byte[] mixin = mixin("ReadsSelectorMixin", LIVING, c -> {
            FieldVisitor field = c.visitField(Opcodes.ACC_PRIVATE, "predicate",
                    "Ljava/util/function/Predicate;", null, null);
            field.visitAnnotation(SHADOW, true).visitEnd();
            field.visitEnd();
            MethodVisitor reader = c.visitMethod(Opcodes.ACC_PRIVATE, "read", "()V", null, null);
            reader.visitCode();
            reader.visitVarInsn(Opcodes.ALOAD, 0);
            reader.visitFieldInsn(Opcodes.GETFIELD, "test/mixin/ReadsSelectorMixin", "predicate",
                    "Ljava/util/function/Predicate;");
            reader.visitInsn(Opcodes.POP);
            reader.visitInsn(Opcodes.RETURN);
            reader.visitMaxs(1, 1);
            reader.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin), "a shadow the mixin uses must keep failing loudly");
    }

    @Test
    void removesAReturnModifierWhoseTargetNowReturnsAnotherType() {
        // Lithostitched's handler: Mixin refuses it, and the refusal left VillagerType failing verification.
        byte[] mixin = mixin("VillagerTypeMixin", VILLAGER_TYPE, c -> {
            MethodVisitor handler = returnModifier(c, "useBiomeTags",
                    "(L" + VILLAGER_TYPE + ";" + HOLDER + ")L" + VILLAGER_TYPE + ";", "byBiome");
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitVarInsn(Opcodes.ALOAD, 0);
            handler.visitInsn(Opcodes.ARETURN);
            handler.visitMaxs(1, 2);
            handler.visitEnd();
            MethodVisitor other = inject(c, "onRender", "(" + CI + ")V", "byBiome", "HEAD", Opcodes.ACC_STATIC);
            other.visitInsn(Opcodes.RETURN);
            other.visitMaxs(0, 1);
            other.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));

        assertNull(method(repaired, "useBiomeTags"), "byBiome now returns a key, so the handler can never apply");
        assertNotNull(method(repaired, "onRender"), "the mixin's other handlers stay");
    }

    @Test
    void keepsAReturnModifierBuiltForTheHostsReturnType() {
        byte[] mixin = mixin("CurrentVillagerTypeMixin", VILLAGER_TYPE, c -> {
            MethodVisitor handler = returnModifier(c, "useBiomeTags",
                    "(" + RESOURCE_KEY + HOLDER + ")" + RESOURCE_KEY, "byBiome");
            handler.visitVarInsn(Opcodes.ALOAD, 0);
            handler.visitInsn(Opcodes.ARETURN);
            handler.visitMaxs(1, 2);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin));
    }

    @Test
    void movesAStringMixinTargetThroughAClassRedirect() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/SelectionMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("targets");
        targets.visit(null, OLD_SELECTION);
        targets.visitEnd();
        mixin.visitEnd();
        writer.visitEnd();

        ClassNode repaired = read(repair.apply(writer.toByteArray()));

        assertEquals(List.of(NEW_SELECTION), annotationValue(repaired.invisibleAnnotations.get(0), "targets"),
                "a string target is annotation text, so the class move must be applied explicitly");
    }

    @Test
    void leavesAMixinAlreadyBuiltForTheHostByteIdentical() {
        byte[] mixin = mixin("CurrentMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "onRender", "(" + CI + ")V", "render", "HEAD", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 2);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin));
    }

    @Test
    void retypesAnInvokePointHandlerWithLocalsWhenItsTargetRetypedAParameterItNeverReads() throws Exception {
        MethodNode handler = method(repair.apply(localCaptureMixin(MOONLIGHT_DESC, "", 1, 2, 3, 4, 5, 6)),
                "ml$getSpecialSpawnsOverrides");

        assertEquals("(" + LEVEL + MOBS_AT_REST + CIR + STRUCTURE + ENTRY + ")V", handler.desc,
                "the unread Holder<Biome> becomes the Level and the @Local captures stay after the callback");
        assertMixinAccepts(MixinHostDriftRepair.checkedArguments(handler), GET_MOBS_AT);
        assertEquals(List.of(LOCAL), descs(handler.invisibleParameterAnnotations[5]));
        assertEquals(List.of(LOCAL), descs(handler.invisibleParameterAnnotations[6]));
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8), varSlots(handler), "every slot keeps its meaning");
        new Analyzer<>(new BasicVerifier()).analyze("test/mixin/ChunkGeneratorMixin", handler);
    }

    @Test
    void movesALongLocalPastAWiderRetypedParameter() throws Exception {
        // The host's first parameter became a double, so every later slot moves up by one.
        String wide = "(D" + MOBS_AT_REST + ")" + WEIGHTED;
        MixinHostDriftRepair wideHost = hostOf("wide.jar", hostClass(CHUNK_GENERATOR, "java/lang/Object",
                c -> method(c, 0, "getMobsAt", wide)));
        String desc = "(" + HOLDER + MOBS_AT_REST + CIR + "J" + STRUCTURE + ")V";

        MethodNode handler = method(wideHost.apply(localCaptureMixin(desc, "", 1, 2, 3, 4, 5, 6)),
                "ml$getSpecialSpawnsOverrides");

        assertEquals("(D" + MOBS_AT_REST + CIR + "J" + STRUCTURE + ")V", handler.desc);
        assertEquals(List.of(3, 4, 5, 6, 7, 9, 10), varSlots(handler),
                "captures, callback, the two-slot @Local long and the structure each move up by one");
        new Analyzer<>(new BasicVerifier()).analyze("test/mixin/ChunkGeneratorMixin", handler);
    }

    @Test
    void doesNotRetypeWhenALocalHasTheTypeOfAChangedParameter() {
        // MixinExtras counts the target's parameters as locals, so a @Local Level could now be the new parameter.
        String desc = "(" + HOLDER + MOBS_AT_REST + CIR + LEVEL + ")V";
        ClassNode repaired = read(repair.apply(localCaptureMixin(desc, "", 1, 2, 3, 4, 5)));

        assertNull(method(repaired, "ml$getSpecialSpawnsOverrides"),
                "not retyped, and as Mixin refuses it as it is, removed instead");
    }

    @Test
    void doesNotRetypeAReadCaptureThatSharesItsTypeWithAnotherOldCapture() throws IOException {
        // Old onPlace(BlockPos, BlockPos) against a host onPlace(BlockPos, Level): the read first position may be the second now.
        MixinHostDriftRepair placeHost = hostOf("place.jar", hostClass(BLOCK, "java/lang/Object",
                c -> method(c, 0, "onPlace", "(" + POS + LEVEL + ")V")));
        byte[] mixin = mixin("PlaceMixin", BLOCK, c -> {
            MethodVisitor handler = inject(c, "onPlace", "(" + POS + POS + CI + ")V", "onPlace", "INVOKE", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 4);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, placeHost.apply(mixin));
    }

    @Test
    void removesALocalCaptureHandlerWhoseTargetStillRefusesIt() {
        // The handler reads the Holder<Biome> the host retyped, so no layout fits and MixinExtras would leave it half applied.
        ClassNode repaired = read(repair.apply(localCaptureMixin(MOONLIGHT_DESC, "", 0, 1, 2)));

        assertNull(method(repaired, "ml$getSpecialSpawnsOverrides"), "the refused handler must not reach Mixin");
        assertNotNull(method(repaired, "keep"), "the mixin's other members stay");
    }

    @Test
    void removesOnlyWithAnIndexThatHasTheLoadersPatches() throws IOException {
        byte[] mixin = localCaptureMixin(MOONLIGHT_DESC, "", 0, 1, 2);
        MixinHostDriftRepair vanilla = hostOf("vanilla.jar",
                hostClass(CHUNK_GENERATOR, "java/lang/Object", c -> method(c, 0, "getMobsAt", GET_MOBS_AT)),
                item(null));
        MixinHostDriftRepair patched = hostOf("patched.jar",
                hostClass(CHUNK_GENERATOR, "java/lang/Object", c -> method(c, 0, "getMobsAt", GET_MOBS_AT)),
                item("net/neoforged/neoforge/common/extensions/IItemExtension"));
        com.retromod.util.McReflect.setForceNeoForge(true);
        try {
            assertArrayEquals(mixin, vanilla.apply(mixin),
                    "a vanilla jar cannot speak for a NeoForge-patched target, so the handler stays");
            assertNull(method(read(patched.apply(mixin)), "ml$getSpecialSpawnsOverrides"));
        } finally {
            com.retromod.util.McReflect.setForceNeoForge(false);
        }
    }

    @Test
    void keepsALocalCaptureHandlerWithACoercedParameter() {
        byte[] mixin = localCaptureMixin(MOONLIGHT_DESC, COERCE, 0, 1, 2);

        assertArrayEquals(mixin, repair.apply(mixin), "@Coerce may let Mixin accept it, so nothing is proven");
    }

    @Test
    void leavesALocalCaptureHandlerBuiltForTheHostByteIdentical() {
        byte[] mixin = localCaptureMixin("(" + LEVEL + MOBS_AT_REST + CIR + STRUCTURE + ")V", "", 0, 1, 5);

        assertArrayEquals(mixin, repair.apply(mixin));
    }

    @Test
    void picksTheOneOverloadALocalCaptureHandlerFits() throws Exception {
        MixinHostDriftRepair overloaded = hostOf("overloaded.jar", hostClass(CHUNK_GENERATOR, "java/lang/Object", c -> {
            method(c, 0, "getMobsAt", GET_MOBS_AT);
            method(c, 0, "getMobsAt", "(Lnet/minecraft/world/entity/MobCategory;)" + WEIGHTED);
        }));

        MethodNode handler = method(overloaded.apply(localCaptureMixin(MOONLIGHT_DESC, "", 1, 2, 3, 4, 5, 6)),
                "ml$getSpecialSpawnsOverrides");

        assertEquals("(" + LEVEL + MOBS_AT_REST + CIR + STRUCTURE + ENTRY + ")V", handler.desc);
        assertEquals(List.of("getMobsAt" + GET_MOBS_AT), selectors(handler, INJECT), "the selector names the overload");
        assertEquals(List.of(LOCAL), descs(handler.invisibleParameterAnnotations[6]));
        new Analyzer<>(new BasicVerifier()).analyze("test/mixin/ChunkGeneratorMixin", handler);
    }

    @Test
    void leavesALocalCaptureHandlerTwoOverloadsFitAlone() throws IOException {
        MixinHostDriftRepair overloaded = hostOf("ambiguous.jar", hostClass(CHUNK_GENERATOR, "java/lang/Object", c -> {
            method(c, 0, "getMobsAt", GET_MOBS_AT);
            method(c, 0, "getMobsAt", "(Lnet/minecraft/server/level/ServerLevel;" + MOBS_AT_REST + ")" + WEIGHTED);
        }));
        byte[] mixin = localCaptureMixin(MOONLIGHT_DESC, "", 1, 2, 3, 4, 5, 6);

        assertArrayEquals(mixin, overloaded.apply(mixin),
                "either overload fits, so it is neither retyped nor removed");
    }

    @Test
    void doesNotRetypeAHandlerWithASharedGenericLocalRef() {
        // MixinExtras reads LocalRef<T>'s T from the Signature, which a retype drops.
        String desc = "(" + HOLDER + MOBS_AT_REST + CIR + LOCAL_REF + ")V";
        String signature = "(Lnet/minecraft/core/Holder<Lnet/minecraft/world/level/biome/Biome;>;" + MOBS_AT_REST
                + CIR + "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef<Ljava/lang/String;>;)V";
        ClassNode repaired = read(repair.apply(sugarMixin(desc, signature, "", SHARE, null, 1, 2, 3, 4, 5)));

        assertNull(method(repaired, "ml$getSpecialSpawnsOverrides"),
                "not retyped without its type argument, and as Mixin refuses it as it is, removed instead");
    }

    @Test
    void doesNotRetypeACallbackOnlyHandlerWithALocalGenericLocalRef() {
        // A handler written for a void target, whose target now returns a list: only the callback would change.
        String desc = "(" + CI + LOCAL_REF + ")V";
        String signature = "(" + CI + "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef<" + STRUCTURE + ">;)V";
        ClassNode repaired = read(repair.apply(sugarMixin(desc, signature, "", LOCAL, null, 1)));

        assertNull(method(repaired, "ml$getSpecialSpawnsOverrides"),
                "not retyped without its type argument, and as Mixin refuses it as it is, removed instead");
    }

    @Test
    void retypesAHandlerWhoseTypedLocalRefNamesAnUnchangedType() throws Exception {
        String desc = "(" + HOLDER + MOBS_AT_REST + CIR + LOCAL_INT_REF + ")V";
        MethodNode handler = method(repair.apply(sugarMixin(desc, null, "", LOCAL, null, 1, 2, 3, 4, 5)),
                "ml$getSpecialSpawnsOverrides");

        assertEquals("(" + LEVEL + MOBS_AT_REST + CIR + LOCAL_INT_REF + ")V", handler.desc,
                "a LocalIntRef states its type, and no int parameter changed");
        new Analyzer<>(new BasicVerifier()).analyze("test/mixin/ChunkGeneratorMixin", handler);
    }

    @Test
    void retypesALocalWithAnIndexOnlyWhileTheParametersKeepTheirSlots() throws IOException {
        String desc = "(" + HOLDER + MOBS_AT_REST + CIR + STRUCTURE + ")V";
        MixinHostDriftRepair wideHost = hostOf("wide-index.jar", hostClass(CHUNK_GENERATOR, "java/lang/Object",
                c -> method(c, 0, "getMobsAt", "(D" + MOBS_AT_REST + ")" + WEIGHTED)));

        MethodNode sameSlots = method(repair.apply(sugarMixin(desc, null, "", LOCAL, 8, 1, 2, 3, 4, 5)),
                "ml$getSpecialSpawnsOverrides");
        ClassNode movedSlots = read(wideHost.apply(sugarMixin(desc, null, "", LOCAL, 8, 1, 2, 3, 4, 5)));

        assertEquals("(" + LEVEL + MOBS_AT_REST + CIR + STRUCTURE + ")V", sameSlots.desc, "a Level takes the Holder's one slot");
        assertNull(method(movedSlots, "ml$getSpecialSpawnsOverrides"), "a double takes two slots, so index 8 may name another local");
    }

    @Test
    void doesNotMakeAHandlerStaticWhenALocalSetsAnIndex() throws IOException {
        // The target lost its this slot, so index 8 would name the next local down.
        String desc = "(" + LEVEL + MOBS_AT_REST + CIR + STRUCTURE + ")V";
        MixinHostDriftRepair staticHost = hostOf("static-index.jar", hostClass(CHUNK_GENERATOR, "java/lang/Object",
                c -> method(c, Opcodes.ACC_STATIC, "getMobsAt", GET_MOBS_AT)));

        MethodNode withoutIndex = method(staticHost.apply(sugarMixin(desc, null, "", LOCAL, null, 1, 2, 3, 4, 5)),
                "ml$getSpecialSpawnsOverrides");
        ClassNode withIndex = read(staticHost.apply(sugarMixin(desc, null, "", LOCAL, 8, 1, 2, 3, 4, 5)));

        assertTrue((withoutIndex.access & Opcodes.ACC_STATIC) != 0, "a @Local found by type follows the target");
        assertNull(method(withIndex, "ml$getSpecialSpawnsOverrides"),
                "not made static, and as Mixin refuses it as it is, removed instead");
    }

    // ---- fixtures ------------------------------------------------------------------------

    /** {@link #localCaptureMixin} with a handler Signature, the sugar annotation, and a {@code @Local} index. */
    private static byte[] sugarMixin(String desc, String signature, String captureAnnotation, String sugar,
            Integer localIndex, int... reads) {
        return mixin("ChunkGeneratorMixin", CHUNK_GENERATOR, c -> {
            MethodVisitor handler = c.visitMethod(Opcodes.ACC_PRIVATE, "ml$getSpecialSpawnsOverrides", desc, signature, null);
            AnnotationVisitor inject = handler.visitAnnotation(INJECT, true);
            AnnotationVisitor methods = inject.visitArray("method");
            methods.visit(null, "getMobsAt");
            methods.visitEnd();
            AnnotationVisitor ats = inject.visitArray("at");
            AnnotationVisitor at = ats.visitAnnotation(null, AT);
            at.visit("value", "INVOKE");
            at.visit("target", SPAWN_OVERRIDES);
            at.visitEnd();
            ats.visitEnd();
            inject.visit("cancellable", true);
            inject.visitEnd();
            Type[] args = Type.getArgumentTypes(desc);
            int callback = 0;
            while (!args[callback].getInternalName().startsWith("org/spongepowered/")) callback++;
            if (!captureAnnotation.isEmpty()) handler.visitParameterAnnotation(0, captureAnnotation, false).visitEnd();
            for (int i = callback + 1; i < args.length; i++) {
                AnnotationVisitor annotation = handler.visitParameterAnnotation(i, sugar, false);
                if (sugar.equals(SHARE)) annotation.visit("value", "spawns");
                if (localIndex != null) annotation.visit("index", localIndex);
                annotation.visitEnd();
            }
            handler.visitCode();
            int[] slots = new int[args.length + 1];
            slots[0] = 1;
            for (int i = 0; i < args.length; i++) slots[i + 1] = slots[i] + args[i].getSize();
            for (int read : reads) {
                handler.visitVarInsn(args[read].getOpcode(Opcodes.ILOAD), slots[read]);
                handler.visitInsn(args[read].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
            }
            handler.visitInsn(Opcodes.ACONST_NULL);
            handler.visitVarInsn(Opcodes.ASTORE, slots[args.length]);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(2, slots[args.length] + 1);
            handler.visitEnd();
            MethodVisitor keep = c.visitMethod(Opcodes.ACC_PRIVATE, "keep", "()V", null, null);
            keep.visitCode();
            keep.visitInsn(Opcodes.RETURN);
            keep.visitMaxs(0, 1);
            keep.visitEnd();
        });
    }

    /**
     * Moonlight's 1.21.1 {@code ChunkGeneratorMixin} shape: an {@code INVOKE} point inside {@code getMobsAt},
     * every parameter after the callback a {@code @Local}. The body reads the parameters at {@code reads},
     * by index, then stores one local; {@code captureAnnotation} marks the first parameter when set.
     */
    private static byte[] localCaptureMixin(String desc, String captureAnnotation, int... reads) {
        return sugarMixin(desc, null, captureAnnotation, LOCAL, null, reads);
    }

    /** {@code Item}, implementing {@code extension} when it is not null, as a loader's patched client does. */
    private static byte[] item(String extension) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/world/item/Item", null, "java/lang/Object",
                extension == null ? null : new String[] {extension});
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A repair over a host jar that holds only {@code classes}. */
    private MixinHostDriftRepair hostOf(String jarName, byte[]... classes) throws IOException {
        Path jar = tempDir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (byte[] hostClass : classes) put(out, hostClass);
        }
        FuzzyMethodResolver index = new FuzzyMethodResolver();
        index.indexJar(jar);
        return new MixinHostDriftRepair(index, Map.of(), Map.of());
    }

    /** Mixin's callback rule for checked arguments: every target argument then the callback, or the callback alone. */
    private static void assertMixinAccepts(List<Type> arguments, String targetDesc) {
        Type callback = Type.getType(Type.getReturnType(targetDesc).getSort() == Type.VOID ? CI : CIR);
        List<Type> every = new java.util.ArrayList<>(Arrays.asList(Type.getArgumentTypes(targetDesc)));
        every.add(callback);
        assertTrue(arguments.equals(every) || arguments.equals(List.of(callback)), arguments + " fits neither layout");
    }

    private static List<String> descs(List<AnnotationNode> annotations) {
        return annotations.stream().map(a -> a.desc).toList();
    }


    /** Mixin's callback rule without locals: every target argument then the callback, or the callback alone. */
    private static void assertMixinAccepts(MethodNode handler, String targetDesc) {
        Type returnType = Type.getReturnType(targetDesc);
        String callback = returnType.getSort() == Type.VOID ? CI : CIR;
        String arguments = targetDesc.substring(1, targetDesc.indexOf(')'));
        String every = "(" + arguments + callback + ")V";
        String alone = "(" + callback + ")V";
        assertTrue(handler.desc.equals(every) || handler.desc.equals(alone),
                handler.name + handler.desc + " is neither " + every + " nor " + alone);
    }

    /** The local slot of each load or store, in order. */
    private static List<Integer> varSlots(MethodNode method) {
        List<Integer> slots = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var) slots.add(var.var);
        }
        return slots;
    }

    private static void put(JarOutputStream out, byte[] classBytes) throws IOException {
        out.putNextEntry(new JarEntry(new ClassReader(classBytes).getClassName() + ".class"));
        out.write(classBytes);
        out.closeEntry();
    }

    private static byte[] hostClass(String name, String superName, Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, superName, null);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(ClassWriter writer, int access, String name, String desc) {
        if ((access & Opcodes.ACC_STATIC) == 0) {
            writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | access, name, desc, null, null)
                    .visitEnd();
            return;
        }
        MethodVisitor body = writer.visitMethod(Opcodes.ACC_PUBLIC | access, name, desc, null, null);
        body.visitCode();
        body.visitInsn(Opcodes.RETURN);
        body.visitMaxs(0, Type.getArgumentsAndReturnSizes(desc) >> 2);
        body.visitEnd();
    }

    private static byte[] mixin(String simpleName, String target, Consumer<ClassWriter> members) {
        return mixin(simpleName, target, members, false);
    }

    private static byte[] mixin(String simpleName, String target, Consumer<ClassWriter> members,
            boolean isAbstract) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | (isAbstract ? Opcodes.ACC_ABSTRACT : 0),
                "test/mixin/" + simpleName, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodVisitor inject(ClassWriter writer, String name, String desc, String selector,
            String point, int extraAccess) {
        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE | extraAccess, name, desc, null, null);
        AnnotationVisitor inject = handler.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, AT);
        at.visit("value", point);
        at.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        return handler;
    }

    /** A static {@code @ModifyReturnValue} handler whose second parameter is a MixinExtras {@code @Local}. */
    private static MethodVisitor returnModifier(ClassWriter writer, String name, String desc, String selector) {
        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, desc, null, null);
        AnnotationVisitor modifier = handler.visitAnnotation(MODIFY_RETURN_VALUE, true);
        AnnotationVisitor methods = modifier.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor ats = modifier.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, AT);
        at.visit("value", "RETURN");
        at.visitEnd();
        ats.visitEnd();
        modifier.visitEnd();
        AnnotationVisitor local = handler.visitParameterAnnotation(1, LOCAL, false);
        local.visit("argsOnly", true);
        local.visitEnd();
        handler.visitCode();
        return handler;
    }

    private static ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(byte[] classBytes, String name) {
        return method(read(classBytes), name);
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
    }

    private static <T extends AbstractInsnNode> T first(MethodNode method, Class<T> type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (type.isInstance(insn)) return type.cast(insn);
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + method.name);
    }

    private static boolean calls(MethodNode method, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean hasAnnotation(MethodNode method, String desc) {
        for (List<AnnotationNode> list : java.util.Arrays.asList(method.visibleAnnotations,
                method.invisibleAnnotations)) {
            if (list == null) continue;
            for (AnnotationNode annotation : list) if (annotation.desc.equals(desc)) return true;
        }
        return false;
    }

    private static List<Object> selectors(MethodNode method, String injectorDesc) {
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (annotation.desc.equals(injectorDesc)) return annotationValue(annotation, "method");
        }
        throw new AssertionError("no " + injectorDesc + " on " + method.name);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> annotationValue(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return (List<Object>) annotation.values.get(i + 1);
        }
        throw new AssertionError("no " + key + " on " + annotation.desc);
    }
}
