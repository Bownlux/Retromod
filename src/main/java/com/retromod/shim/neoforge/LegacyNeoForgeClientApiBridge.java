/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.RenderStateLookupHost;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.forge.LegacyArmorHooksBridge;
import com.retromod.shim.forge.LegacyAvatarRendererBridge;
import com.retromod.shim.forge.ModConstructorHooks;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

/** Rebuilds the client events, fluid reads, fluid stack tag, spawn event constructor and armor hooks that NeoForge 26.x removed or renamed, for old mods. */
public final class LegacyNeoForgeClientApiBridge {

    private static final String NEO = "net/neoforged/neoforge/";
    private static final String FORGE = "net/minecraftforge/";
    static final String SHADERS = "client/event/RegisterShadersEvent";
    static final String CONTAINER = "client/event/ContainerScreenEvent";
    static final String ANGER = "event/entity/living/EnderManAngerEvent";
    static final String FLUID_EXT = "client/extensions/common/IClientFluidTypeExtensions";
    static final String FLUID_STACK = "fluids/FluidStack";
    static final String RENDER_PLAYER = "client/event/RenderPlayerEvent";
    static final String MODIFY_BAKING = "client/event/ModelEvent$ModifyBakingResult";
    static final String DEBUG_TEXT = "client/event/CustomizeGuiOverlayEvent$DebugText";
    static final String NEO_FINALIZE = NEO + "event/entity/living/FinalizeSpawnEvent";
    static final String FORGE_FINALIZE = FORGE + "event/entity/living/MobSpawnEvent$FinalizeSpawn";
    // Every NeoForge host ships this class, so reading it tells a running NeoForge host from an offline run.
    static final String ANCHOR = NEO + "common/NeoForge";

    static final String ROOT = RebuiltApiClasses.ROOT + "neoforge/";
    static final String REBUILT_SHADERS = ROOT + "client/event/LegacyRegisterShadersEvent";
    static final String REBUILT_CONTAINER = ROOT + "client/event/LegacyContainerScreenEvent";
    // NeoForge 26.3 renamed the anger event with Minecraft's Enderman class and kept its shape and its post.
    static final String RENAMED_ANGER = NEO + "event/entity/living/EndermanAngerEvent";
    static final String FLUID_READS = ROOT + "client/extensions/LegacyFluidTypeReads";
    // Beside the other rebuilt common classes; neoforge/fluids holds the rebuilt transfer API.
    static final String STACK_TAGS = ROOT + "common/LegacyFluidStackTags";
    static final String STACK_CODECS = ROOT + "common/LegacyFluidStackCodecs";
    static final String FINALIZE_FACTORY = ROOT + "event/LegacyFinalizeSpawn";
    static final String RENDER_MEMBERS = ROOT + "client/event/RenderLivingMembers";
    static final String MODEL_MAPS = ROOT + "client/model/LegacyNeoForgeModelMaps";
    static final String REBUILT_DEBUG_TEXT = ROOT + "client/event/LegacyDebugTextEvent";
    static final String DEBUG_TEXT_INSTALL = ROOT + "client/event/LegacyDebugTextInstall";
    static final String DEBUG_TEXT_HOOK_KEY = "legacy-debug-text-neoforge";
    /** The rebuilt class whose {@code install()} posts the old client events and registers old fluid models. */
    public static final String INSTALLER = ROOT + "client/event/LegacyNeoForgeClientEvents";
    // 1.21.9 renamed PlayerRenderer to AvatarRenderer, which LegacyAvatarRendererBridge gives old mods.
    static final String PLAYER_RENDERER = "net/minecraft/client/renderer/entity/player/PlayerRenderer";
    static final String AVATAR_RENDERER = "net/minecraft/client/renderer/entity/player/AvatarRenderer";

    /** The first NeoForge host whose events these are rebuilt for; 26.1 moved fluid textures into fluid models. */
    public static final String FIRST_HOST = "26.1";
    /** NeoForge 26.3 removed the container screen event and renamed the enderman anger event. */
    static final String CONTAINER_GONE = "26.3";
    /** The types an old shader instance has after each host's renames, which registerShader's descriptor carries. */
    static final List<String> SHADER_TYPES = List.of("net/minecraft/client/renderer/ShaderInstance",
            "net/minecraft/client/renderer/CompiledShaderProgram", "com/mojang/blaze3d/opengl/GlProgram",
            "com/mojang/renderpearl/backend/opengl/GlProgram");
    static final String HOOK_KEY = "legacy-client-events-neoforge";
    private static final ModConstructorHooks.Call INSTALL =
            new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, INSTALLER, "install", "()V", List.of());
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private static final String ID = "Lnet/minecraft/resources/Identifier;";
    private static final String AT = "Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
            + "Lnet/minecraft/core/BlockPos;";
    private static final String TAG = "Lnet/minecraft/nbt/CompoundTag;";

    private LegacyNeoForgeClientApiBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!McReflect.isNeoForge() || RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) < 0) return;
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        // First, so the player render reads below know the old renderer type has a name on this host.
        if (host.apply(ANCHOR) == null || host.apply(AVATAR_RENDERER) != null && host.apply(PLAYER_RENDERER) == null) {
            LegacyAvatarRendererBridge.register(t);
        }
        boolean install = false;
        if (gone(host, NEO + SHADERS, FIRST_HOST)) {
            registerShaders(t);
            install = true;
        }
        if (gone(host, NEO + CONTAINER, CONTAINER_GONE)) {
            redirectNested(t, CONTAINER, REBUILT_CONTAINER, List.of("", "$Render", "$Render$Background", "$Render$Foreground"));
            install = true;
        }
        if (gone(host, NEO + ANGER, CONTAINER_GONE) && (host.apply(ANCHOR) == null || host.apply(RENAMED_ANGER) != null)) {
            t.registerClassRedirect(NEO + ANGER, RENAMED_ANGER);
            t.registerClassRedirect(FORGE + ANGER, RENAMED_ANGER);
        }
        if (lacks(host, NEO + FLUID_EXT, "getStillTexture", "()" + ID)) {
            registerFluidReads(t);
            install = true;
        }
        if (gone(host, NEO + DEBUG_TEXT, FIRST_HOST)) registerDebugText(t);
        if (lacks(host, NEO + FLUID_STACK, "getTag", "()" + TAG)) registerStackTags(t);
        if (lacks(host, NEO + RENDER_PLAYER, "getPackedLight", "()I") && RenderStateLookupHost.supported()) registerPlayerReads(t);
        if (lacks(host, NEO_FINALIZE, "<init>", oldFinalize())) registerFinalizeFactory(t);
        if (lacks(host, NEO + MODIFY_BAKING, "getModels", "()Ljava/util/Map;")) {
            // An old BakedModel is the host's block state model on 26.x, so the old map is a view of the bake's block models.
            RebuiltApiClasses.registerTree(t, MODEL_MAPS);
            for (String root : List.of(NEO, FORGE)) {
                t.registerMethodRedirect(root + MODIFY_BAKING, "getModels", "()Ljava/util/Map;", MODEL_MAPS, "getModels",
                        "(L" + NEO + MODIFY_BAKING + ";)Ljava/util/Map;", true);
            }
        }
        // The install also gives old armor items that choose their texture in code an extension.
        if (registerArmor(t, host)) install = true;
        if (install) {
            RebuiltApiClasses.registerTree(t, INSTALLER);
            ModConstructorHooks.register(t, HOOK_KEY, LegacyNeoForgeClientApiBridge::plan);
        }
    }

    /** The old armor and disguise hooks; true when the armor items need the install. */
    private static boolean registerArmor(RetromodTransformer t, Function<String, ClassNode> host) {
        String ext = NEO + "client/extensions/common/IClientItemExtensions";
        boolean armorGone = lacks(host, ext, "getHumanoidArmorModel", LegacyArmorHooksBridge.OLD_MODEL_ARGS
                + "Lnet/minecraft/client/model/HumanoidModel;");
        String stack = NEO + "common/extensions/IItemStackExtension";
        String oldMask = "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/monster/EnderMan;)Z";
        String gaze = "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/LivingEntity;)Z";
        boolean disguiseGone = lacks(host, stack, "isEnderMask", oldMask) && (host.apply(ANCHOR) == null || !lacks(host, stack, "isGazeDisguise", gaze));
        // 26.3 gave the hand transform the player's render state; offline the target version answers.
        String hand = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/entity/HumanoidArm;"
                + "Lnet/minecraft/world/item/ItemStack;FFF)Z";
        boolean handGone = host.apply(ANCHOR) == null ? RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, CONTAINER_GONE) >= 0
                : lacks(host, ext, "applyForgeHandTransform", hand);
        LegacyArmorHooksBridge.registerNeoForge(t, armorGone, disguiseGone, handGone);
        return armorGone;
    }

    // ---- the parts ----

    private static void registerShaders(RetromodTransformer t) {
        redirectNested(t, SHADERS, REBUILT_SHADERS, List.of(""));
        String helper = "(L" + REBUILT_SHADERS + ";Ljava/lang/Object;Ljava/util/function/Consumer;)V";
        // The shader's type in the call depends on how far the mod's chain renamed it, so each name is keyed.
        for (String owner : List.of(NEO + SHADERS, FORGE + SHADERS, REBUILT_SHADERS)) {
            for (String type : SHADER_TYPES) {
                t.registerMethodRedirect(owner, "registerShader", "(L" + type + ";Ljava/util/function/Consumer;)V",
                        REBUILT_SHADERS, "registerShader", helper, true);
            }
        }
    }

    private static void registerFluidReads(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, FLUID_READS);
        for (String owner : List.of(NEO + FLUID_EXT, FORGE + FLUID_EXT)) {
            String self = "(L" + NEO + FLUID_EXT + ";";
            for (String read : List.of("getStillTexture", "getFlowingTexture", "getOverlayTexture")) {
                redirect(t, owner, read, "()" + ID, self + ")" + ID);
                redirect(t, owner, read, "(" + AT + ")" + ID, self + AT + ")" + ID);
                for (String stack : List.of(NEO, FORGE)) {
                    redirect(t, owner, read, "(L" + stack + FLUID_STACK + ";)" + ID, self + "L" + NEO + FLUID_STACK + ";)" + ID);
                }
            }
            redirect(t, owner, "getTintColor", "()I", self + ")I");
            redirect(t, owner, "getTintColor", "(" + AT + ")I", self + AT + ")I");
            for (String stack : List.of(NEO, FORGE)) {
                redirect(t, owner, "getTintColor", "(L" + stack + FLUID_STACK + ";)I", self + "L" + NEO + FLUID_STACK + ";)I");
            }
            redirect(t, owner, "renderOverlay", "(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
                    self + "Ljava/lang/Object;Ljava/lang/Object;)V");
            // An override's super call of the old fluid draw hook, which no longer exists or is called.
            redirect(t, owner, "renderFluid", "(" + AT + "Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;)Z",
                    self + AT + "Ljava/lang/Object;Lnet/minecraft/world/level/block/state/BlockState;)Z");
        }
    }

    private static void redirect(RetromodTransformer t, String owner, String name, String desc, String helperDesc) {
        t.registerMethodRedirect(owner, name, desc, FLUID_READS, name, helperDesc, true);
    }

    private static void registerStackTags(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, STACK_TAGS);
        RebuiltApiClasses.registerTree(t, STACK_CODECS);
        String stack = "L" + NEO + FLUID_STACK + ";";
        String[][] members = {{"getTag", "()" + TAG}, {"getOrCreateTag", "()" + TAG}, {"hasTag", "()Z"}, {"setTag", "(" + TAG + ")V"},
                {"getChildTag", "(Ljava/lang/String;)" + TAG}, {"getOrCreateChildTag", "(Ljava/lang/String;)" + TAG},
                {"removeChildTag", "(Ljava/lang/String;)V"}};
        for (String owner : List.of(NEO + FLUID_STACK, FORGE + FLUID_STACK)) {
            for (String[] member : members) {
                t.registerMethodRedirect(owner, member[0], member[1], STACK_TAGS, member[0], "(" + stack + member[1].substring(1), true);
            }
        }
        // The old constructors with a tag or a source stack, and the old name of the description id.
        String fluid = "Lnet/minecraft/world/level/material/Fluid;";
        for (String owner : List.of(NEO + FLUID_STACK, FORGE + FLUID_STACK)) {
            t.registerConstructorRedirect(owner, "(" + fluid + "I" + TAG + ")V", STACK_TAGS, "create", "(" + fluid + "I" + TAG + ")" + stack);
            for (String source : List.of(stack, "L" + FORGE + FLUID_STACK + ";")) {
                t.registerConstructorRedirect(owner, "(" + source + "I)V", STACK_TAGS, "copyWithAmount", "(" + stack + "I)" + stack);
            }
            t.registerMethodRedirect(owner, "getTranslationKey", "()Ljava/lang/String;", STACK_TAGS, "getTranslationKey",
                    "(" + stack + ")Ljava/lang/String;", true);
            // NeoForge 1.21.1's tag reads and writes, which 26.x left to the stack's codecs.
            String provider = "Lnet/minecraft/core/HolderLookup$Provider;";
            String nbt = "Lnet/minecraft/nbt/Tag;";
            t.registerMethodRedirect(owner, "parseOptional", "(" + provider + TAG + ")" + stack, STACK_CODECS, "parseOptional",
                    "(" + provider + TAG + ")" + stack);
            t.registerMethodRedirect(owner, "parse", "(" + provider + nbt + ")Ljava/util/Optional;", STACK_CODECS, "parse",
                    "(" + provider + nbt + ")Ljava/util/Optional;");
            t.registerMethodRedirect(owner, "saveOptional", "(" + provider + ")" + nbt, STACK_CODECS, "saveOptional",
                    "(" + stack + provider + ")" + nbt, true);
            t.registerMethodRedirect(owner, "save", "(" + provider + ")" + nbt, STACK_CODECS, "save", "(" + stack + provider + ")" + nbt, true);
        }
    }

    /** The old debug text event, posted from a debug screen entry per mod, which the player turns on in the debug options. */
    private static void registerDebugText(RetromodTransformer t) {
        redirectNested(t, DEBUG_TEXT, REBUILT_DEBUG_TEXT, List.of(""));
        // Forge 1.20.1 handed out the partial tick as a float; the rebuilt event keeps NeoForge 1.21.1's delta tracker.
        // Its columns are Forge 1.20.1's ArrayList, which NeoForge 1.21.1 handed out as a List.
        for (String root : List.of(NEO, FORGE)) {
            t.registerMethodRedirect(root + DEBUG_TEXT, "getPartialTick", "()F", REBUILT_DEBUG_TEXT, "partialTickFloat",
                    "(L" + REBUILT_DEBUG_TEXT + ";)F", true);
            t.registerMethodRedirect(root + DEBUG_TEXT, "getLeft", "()Ljava/util/List;", REBUILT_DEBUG_TEXT, "leftList",
                    "(L" + REBUILT_DEBUG_TEXT + ";)Ljava/util/List;", true);
            t.registerMethodRedirect(root + DEBUG_TEXT, "getRight", "()Ljava/util/List;", REBUILT_DEBUG_TEXT, "rightList",
                    "(L" + REBUILT_DEBUG_TEXT + ";)Ljava/util/List;", true);
        }
        RebuiltApiClasses.registerTree(t, DEBUG_TEXT_INSTALL);
        ModConstructorHooks.Call install = new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, DEBUG_TEXT_INSTALL,
                "install", "()V", List.of());
        byte[] neoName = bytes(NEO + DEBUG_TEXT);
        byte[] forgeName = bytes(FORGE + DEBUG_TEXT);
        ModConstructorHooks.register(t, DEBUG_TEXT_HOOK_KEY, (classes, modIds) -> {
            for (byte[] original : classes.values()) {
                if (contains(original, neoName) || contains(original, forgeName)) return (modClass, modId) -> List.of(install);
            }
            return null;
        });
    }

    private static void registerPlayerReads(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, RENDER_MEMBERS);
        String living = "(L" + NEO + "client/event/RenderLivingEvent;)";
        for (String root : List.of(NEO, FORGE)) {
            for (String event : List.of("", "$Pre", "$Post")) {
                String owner = root + RENDER_PLAYER + event;
                t.registerMethodRedirect(owner, "getEntity", "()Lnet/minecraft/world/entity/player/Player;",
                        RENDER_MEMBERS, "getPlayer", living + "Lnet/minecraft/world/entity/player/Player;", true);
                t.registerMethodRedirect(owner, "getPackedLight", "()I", RENDER_MEMBERS, "getPackedLight", living + "I", true);
            }
        }
    }

    /** The names an old shader instance can carry in a mod by the time its calls are matched. */
    public static List<String> shaderTypes() {
        return SHADER_TYPES;
    }

    private static void registerFinalizeFactory(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, FINALIZE_FACTORY);
        String factory = "(" + oldFinalize().substring(1, oldFinalize().length() - 2) + ")L" + NEO_FINALIZE + ";";
        for (String owner : List.of(NEO_FINALIZE, FORGE_FINALIZE)) {
            t.registerConstructorRedirect(owner, oldFinalize(), FINALIZE_FACTORY, "create", factory);
        }
    }

    /** The old constructor: mob, level, position, difficulty, reason, group data, spawn tag and spawner logic. */
    public static String oldFinalize() {
        return "(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;DDDLnet/minecraft/world/DifficultyInstance;"
                + "Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/SpawnGroupData;" + TAG
                + "Lnet/minecraft/world/level/BaseSpawner;)V";
    }

    // The old classes under both loaders' names go to the rebuilt ones; a subscriber naming a missing event loses every listener.
    private static void redirectNested(RetromodTransformer t, String path, String rebuilt, List<String> nested) {
        RebuiltApiClasses.registerTree(t, rebuilt);
        for (String suffix : nested) {
            t.registerClassRedirect(NEO + path + suffix, rebuilt + suffix);
            t.registerClassRedirect(FORGE + path + suffix, rebuilt + suffix);
        }
    }

    // ---- the install call ----

    // A jar that names one of these needs the install: it posts the rebuilt events and builds the old fluid models.
    private static final List<byte[]> INSTALL_NAMES = List.of(bytes(NEO + SHADERS), bytes(FORGE + SHADERS), bytes(NEO + CONTAINER),
            bytes(FORGE + CONTAINER), bytes(NEO + FLUID_EXT), bytes(FORGE + FLUID_EXT));
    // A jar that names the old armor texture hook may have an armor item that chooses its texture in code.
    private static final byte[] ARMOR_TEXTURE = bytes("getArmorTexture");

    /** The install call in each {@code @Mod} constructor of a jar whose original classes name a removed client API, or null. */
    static ModConstructorHooks.JarPlan plan(java.util.Map<String, byte[]> classes, java.util.Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            for (byte[] name : INSTALL_NAMES) {
                if (namesClass(original, name)) return (modClass, modId) -> List.of(INSTALL);
            }
            if (contains(original, ARMOR_TEXTURE)) return (modClass, modId) -> List.of(INSTALL);
        }
        return null;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[start + i] != needle[i]) continue outer;
            }
            return true;
        }
        return false;
    }

    // The class name or a nested class of it, so NeoForge's own longer names in the same package do not count.
    static boolean namesClass(byte[] haystack, byte[] name) {
        outer:
        for (int start = 0; start <= haystack.length - name.length; start++) {
            for (int i = 0; i < name.length; i++) {
                if (haystack[start + i] != name[i]) continue outer;
            }
            int next = start + name.length;
            if (next == haystack.length) return true;
            byte b = haystack[next];
            if (b == '$' || !((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '_')) return true;
        }
        return false;
    }

    private static byte[] bytes(String name) {
        return name.getBytes(StandardCharsets.ISO_8859_1);
    }

    // ---- host checks ----

    /** Whether the host lacks the class; offline or unreadable, a target from {@code since} lacks it. */
    static boolean gone(Function<String, ClassNode> host, String type, String since) {
        if (host.apply(ANCHOR) == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        return host.apply(type) == null;
    }

    /** Whether the host class lacks the member; offline, every 26.x target lacks it. */
    static boolean lacks(Function<String, ClassNode> host, String owner, String name, String desc) {
        if (host.apply(ANCHOR) == null) return true;
        ClassNode node = host.apply(owner);
        if (node == null) return false;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return false;
        }
        return true;
    }
}
