/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.List;

/** Answers event, hook and registry members that Forge 1.20.1 and NeoForge 1.21.1 mods call and later NeoForge hosts renamed or retyped. */
public final class LegacyNeoForgeEventDrift {

    static final String NEO = "net/neoforged/neoforge/";
    static final String CLIENT_EVENT = NEO + "client/event/";
    static final String CLIENT_READS = RebuiltApiClasses.ROOT + "neoforge/client/event/LegacyClientEventReads";
    // Kept apart from the client reads, which 1.21 and 1.21.1 hosts embed, since these name the 1.21.2 render state.
    static final String NAME_TAG_READS = RebuiltApiClasses.ROOT + "neoforge/client/event/LegacyNameTagReads";
    static final String HOOK_READS = RebuiltApiClasses.ROOT + "neoforge/event/LegacyHookReads";
    static final String RENDER_MEMBERS = RebuiltApiClasses.ROOT + "neoforge/client/event/RenderLivingMembers";
    static final String REGISTRY_OBJECTS = RebuiltApiClasses.ROOT + "neoforge/registries/LegacyRegistryObjects";
    static final String WORKERS = RebuiltApiClasses.ROOT + "neoforge/common/LegacyWorldWorkerManager";

    static final String VIEWPORT = CLIENT_EVENT + "ViewportEvent";
    static final List<String> VIEWPORT_EVENTS = List.of(VIEWPORT, VIEWPORT + "$ComputeCameraAngles", VIEWPORT + "$ComputeFov",
            VIEWPORT + "$RenderFog", VIEWPORT + "$ComputeFogColor");
    static final String CHARACTER = CLIENT_EVENT + "ScreenEvent$CharacterTyped";
    static final String GUI = CLIENT_EVENT + "RenderGuiEvent";
    static final String OVERLAY = CLIENT_EVENT + "CustomizeGuiOverlayEvent";
    // DebugText is left out: NeoForge 26.x has no such event, and LegacyNeoForgeClientApiBridge rebuilds it.
    static final List<String> OVERLAY_EVENTS = List.of(OVERLAY, OVERLAY + "$BossEventProgress", OVERLAY + "$Chat");
    static final String LIVING = CLIENT_EVENT + "RenderLivingEvent";
    static final String ARM = CLIENT_EVENT + "RenderArmEvent";
    static final String NAME_TAG = CLIENT_EVENT + "RenderNameTagEvent";
    static final String ADD_LAYERS = CLIENT_EVENT + "EntityRenderersEvent$AddLayers";
    static final String KEY_MODIFIER = NEO + "client/settings/KeyModifier";
    static final String KEY_MODIFIERS = RebuiltApiClasses.ROOT + "forge/client/settings/LegacyKeyModifiers";
    static final String GUI_LAYERS = NEO + "client/gui/VanillaGuiLayers";
    static final String TOOL_EVENT = NEO + "event/level/BlockEvent$BlockToolModificationEvent";
    static final String TARGET_EVENT = NEO + "event/entity/living/LivingChangeTargetEvent";
    static final String TRAMPLE = NEO + "event/level/BlockEvent$FarmlandTrampleEvent";
    static final String EVENT_HOOKS = NEO + "event/EventHooks";
    static final String COMMON_HOOKS = NEO + "common/CommonHooks";

    private static final String LIVING_ENTITY = "Lnet/minecraft/world/entity/LivingEntity;";
    private static final String MODEL_TYPE = "(Lnet/minecraft/world/entity/player/PlayerModelType;)";

    private LegacyNeoForgeEventDrift() {}

    /** Registers each member only where the running NeoForge, or offline the target version, no longer has it. */
    public static void register(RetromodTransformer t) {
        registerRenames(t);
        registerNumberDrift(t);
        registerClientReads(t);
        registerHooks(t);
        registerGuiLayers(t);
        registerRegistryObjects(t);
        registerWorldWorkers(t);
        LegacyEffectCureBridge.register(t);
        LegacyTabEntriesBridge.register(t);
        LegacyModConfigTypeBridge.registerOldConstruction(t);
    }

    /** Members that kept their meaning under a new name. */
    private static void registerRenames(RetromodTransformer t) {
        // Forge 1.20.1 named the ability a tool action; NeoForge calls it an item ability.
        String ability = "()Lnet/neoforged/neoforge/common/ItemAbility;";
        rename(t, TOOL_EVENT, "getToolAction", ability, "getItemAbility", ability, "1.21");
        // Forge 1.20.1's target names; NeoForge says which target is about to be set.
        rename(t, TARGET_EVENT, "getNewTarget", "()" + LIVING_ENTITY, "getNewAboutToBeSetTarget", "()" + LIVING_ENTITY, "1.20.2");
        rename(t, TARGET_EVENT, "setNewTarget", "(" + LIVING_ENTITY + ")V", "setNewAboutToBeSetTarget",
                "(" + LIVING_ENTITY + ")V", "1.20.2");
        rename(t, TARGET_EVENT, "getOriginalTarget", "()" + LIVING_ENTITY, "getOriginalAboutToBeSetTarget",
                "()" + LIVING_ENTITY, "1.20.2");
        // 1.21.9 named the player renderer after the avatar it draws.
        rename(t, ADD_LAYERS, "getSkin", MODEL_TYPE + "Lnet/minecraft/client/renderer/entity/EntityRenderer;",
                "getPlayerRenderer", MODEL_TYPE + "Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;", "1.21.9");
        rename(t, ARM, "getPackedLight", "()I", "getLightCoords", "()I", "26.3");
        // 26.3 calls a mob's spawn charge its spawn cost; javac names NeoForge's builder when a biome modifier holds one.
        String vanillaBuilder = "net/minecraft/world/level/biome/MobSpawnSettings$Builder";
        String charge = "(Lnet/minecraft/world/entity/EntityType;DD)L" + vanillaBuilder + ";";
        if (NeoForgeMovedMembers.moved(vanillaBuilder, "addMobCharge", charge, vanillaBuilder, "addMobSpawnCost", charge, "26.3")) {
            String builder = NEO + "common/world/MobSpawnSettingsBuilder";
            t.registerMethodRedirect(builder, "addMobCharge", charge, builder, "addMobSpawnCost", charge);
        }
    }

    /** Members that kept their name and changed a number type; the call converts what it passes or gets. */
    private static void registerNumberDrift(RetromodTransformer t) {
        // NeoForge 26.3 gives the viewport events a float partial tick.
        if (NeoForgeMovedMembers.moved(VIEWPORT, "getPartialTick", "()D", VIEWPORT, "getPartialTick", "()F", "26.3")) {
            for (String event : VIEWPORT_EVENTS) {
                t.registerConvertingRedirect(event, "getPartialTick", "()D", event, "getPartialTick", "()F", 0, Opcodes.F2D);
            }
        }
        String fov = VIEWPORT + "$ComputeFov";
        if (NeoForgeMovedMembers.moved(fov, "getFOV", "()D", fov, "getFOV", "()F", "1.21.2")) {
            t.registerConvertingRedirect(fov, "getFOV", "()D", fov, "getFOV", "()F", 0, Opcodes.F2D);
            t.registerConvertingRedirect(fov, "setFOV", "(D)V", fov, "setFOV", "(F)V", Opcodes.D2F, 0);
        }
        // 1.21.9 passes a typed character as an int code point.
        if (NeoForgeMovedMembers.moved(CHARACTER, "getCodePoint", "()C", CHARACTER, "getCodePoint", "()I", "1.21.9")) {
            for (String event : List.of(CHARACTER, CHARACTER + "$Pre", CHARACTER + "$Post")) {
                t.registerConvertingRedirect(event, "getCodePoint", "()C", event, "getCodePoint", "()I", 0, Opcodes.I2C);
            }
        }
        // 1.21.5 measures falls in doubles.
        if (NeoForgeMovedMembers.moved(TRAMPLE, "getFallDistance", "()F", TRAMPLE, "getFallDistance", "()D", "1.21.5")) {
            t.registerConvertingRedirect(TRAMPLE, "getFallDistance", "()F", TRAMPLE, "getFallDistance", "()D", 0, Opcodes.D2F);
        }
    }

    /** The HUD and overlay events' float partial tick and the HUD event's window, which NeoForge 1.21 dropped. */
    public static void registerGuiReads(RetromodTransformer t) {
        if (guiReads(t)) RebuiltApiClasses.registerTree(t, CLIENT_READS);
    }

    /** Client reads the events no longer answer themselves, served by Retromod's rebuilt helper. */
    private static void registerClientReads(RetromodTransformer t) {
        boolean helper = guiReads(t);
        // 26.3 draws the first person arm from a player render state.
        if (lacks(ARM, "getPlayer", "()Lnet/minecraft/client/player/AbstractClientPlayer;", "26.3")) {
            read(t, ARM, "getPlayer", "()Lnet/minecraft/client/player/AbstractClientPlayer;", "armPlayer", ARM,
                    "Lnet/minecraft/client/player/AbstractClientPlayer;");
            helper = true;
        }
        // 1.21.11 builds name tags from the entity's render state.
        if (lacks(NAME_TAG, "getContent", "()Lnet/minecraft/network/chat/Component;", "1.21.11")
                && hostHasOrOffline(NAME_TAG, "getEntityRenderState")) {
            nameTagRead(t, "getContent", "Lnet/minecraft/network/chat/Component;", "nameTagContent");
            nameTagRead(t, "getEntity", "Lnet/minecraft/world/entity/Entity;", "nameTagEntity");
            nameTagRead(t, "getPackedLight", "I", "nameTagLight");
            nameTagRead(t, "getPoseStack", "Lcom/mojang/blaze3d/vertex/PoseStack;", "nameTagPoseStack");
            RebuiltApiClasses.registerTree(t, NAME_TAG_READS);
        }
        if (helper) RebuiltApiClasses.registerTree(t, CLIENT_READS);
        // The same answer the Forge 1.20.1 bridge gives, for NeoForge 1.21.1 mods, from the list of held modifiers.
        if (lacks(KEY_MODIFIER, "getActiveModifier", "()L" + KEY_MODIFIER + ";", "1.21.2")) {
            RebuiltApiClasses.registerTree(t, KEY_MODIFIERS);
            t.registerMethodRedirect(KEY_MODIFIER, "getActiveModifier", "()L" + KEY_MODIFIER + ";", KEY_MODIFIERS,
                    "getActiveModifier", "()L" + KEY_MODIFIER + ";");
        }
        // A living render event's light is the render state's, which the player render events already read this way.
        if (lacks(LIVING, "getPackedLight", "()I", "1.21.2")) {
            RebuiltApiClasses.registerTree(t, RENDER_MEMBERS);
            for (String event : List.of(LIVING, LIVING + "$Pre", LIVING + "$Post")) {
                t.registerMethodRedirect(event, "getPackedLight", "()I", RENDER_MEMBERS, "getPackedLight", "(L" + LIVING + ";)I", true);
            }
        }
    }

    /** Registers the GUI reads where the host lacks them, and says whether any was registered. */
    private static boolean guiReads(RetromodTransformer t) {
        boolean helper = false;
        // Forge 1.20.1's GUI events handed out the partial tick as a float; NeoForge hands out the delta tracker.
        if (lacks(GUI, "getPartialTick", "()F", "1.21")) {
            for (String event : List.of(GUI, GUI + "$Pre", GUI + "$Post")) {
                read(t, event, "getPartialTick", "()F", "guiPartialTick", GUI, "F");
            }
            helper = true;
        }
        // The HUD event also stopped handing out the window, which MCreator overlays size themselves from.
        String window = "()Lcom/mojang/blaze3d/platform/Window;";
        if (lacks(GUI, "getWindow", window, "1.21")) {
            for (String event : List.of(GUI, GUI + "$Pre", GUI + "$Post")) {
                read(t, event, "getWindow", window, "guiWindow", GUI, "Lcom/mojang/blaze3d/platform/Window;");
            }
            helper = true;
        }
        if (lacks(OVERLAY, "getPartialTick", "()F", "1.21")) {
            for (String event : OVERLAY_EVENTS) {
                read(t, event, "getPartialTick", "()F", "overlayPartialTick", OVERLAY, "F");
            }
            helper = true;
        }
        return helper;
    }

    /** Hooks that took any level and now take a server level. */
    private static void registerHooks(RetromodTransformer t) {
        String level = "Lnet/minecraft/world/level/Level;";
        String grief = "(" + level + "Lnet/minecraft/world/entity/Entity;)Z";
        String destroy = "(" + level + "Lnet/minecraft/core/BlockPos;" + LIVING_ENTITY + ")Z";
        boolean helper = false;
        if (lacks(EVENT_HOOKS, "canEntityGrief", grief, "1.21.2")) {
            t.registerMethodRedirect(EVENT_HOOKS, "canEntityGrief", grief, HOOK_READS, "canEntityGrief", grief);
            helper = true;
        }
        if (lacks(COMMON_HOOKS, "canEntityDestroy", destroy, "1.21.2")) {
            t.registerMethodRedirect(COMMON_HOOKS, "canEntityDestroy", destroy, HOOK_READS, "canEntityDestroy", destroy);
            helper = true;
        }
        // 1.21 enchantments are data, so the drops event no longer computes a looting level.
        String drops = NEO + "event/entity/living/LivingDropsEvent";
        if (lacks(drops, "getLootingLevel", "()I", "1.21")) {
            t.registerMethodRedirect(drops, "getLootingLevel", "()I", HOOK_READS, "lootingLevel", "(L" + drops + ";)I", true);
            helper = true;
        }
        if (helper) RebuiltApiClasses.registerTree(t, HOOK_READS);
    }

    /** Forge 1.20.1's registry object and register event members, which the class moves point at NeoForge's holder and event. */
    private static void registerRegistryObjects(RetromodTransformer t) {
        String register = NEO + "registries/RegisterEvent";
        rename(t, register, "getVanillaRegistry", "()Lnet/minecraft/core/Registry;", "getRegistry", "()Lnet/minecraft/core/Registry;", "1.20.2");
        String holder = NEO + "registries/DeferredHolder";
        boolean helper = false;
        if (lacks(holder, "getHolder", "()Ljava/util/Optional;", "1.20.2")) {
            t.registerMethodRedirect(holder, "getHolder", "()Ljava/util/Optional;", REGISTRY_OBJECTS, "getHolder",
                    "(L" + holder + ";)Ljava/util/Optional;", true);
            helper = true;
        }
        String create = "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/resources/ResourceKey;Ljava/lang/String;)L" + holder + ";";
        if (lacks(holder, "create", create, "1.20.2")) {
            t.registerMethodRedirect(holder, "create", create, REGISTRY_OBJECTS, "create", create);
            helper = true;
        }
        if (helper) RebuiltApiClasses.registerTree(t, REGISTRY_OBJECTS);
    }

    /** The world worker manager, which NeoForge 1.21.2 removed and old mods use to spread a search over server ticks. */
    private static void registerWorldWorkers(RetromodTransformer t) {
        String manager = NEO + "common/WorldWorkerManager";
        boolean gone = ClassResourceInspector.read(NEO + "common/NeoForge") == null
                ? !RetromodVersion.mcVersionExceeds("1.21.2", RetromodVersion.TARGET_MC_VERSION)
                : ClassResourceInspector.read(manager) == null;
        if (!gone) return;
        RebuiltApiClasses.registerTree(t, WORKERS);
        for (String old : List.of(manager, "net/minecraftforge/common/WorldWorkerManager")) {
            t.registerClassRedirect(old, WORKERS);
            t.registerClassRedirect(old + "$IWorker", WORKERS + "$IWorker");
        }
    }

    /** GUI layer ids 1.21.6 merged or dropped, read as the layer that now stands where they stood. */
    private static void registerGuiLayers(RetromodTransformer t) {
        ClassNode layers = ClassResourceInspector.read(GUI_LAYERS);
        // The jump meter and experience bar became one contextual bar; the debug overlay draws outside the layers,
        // where it sat just above the demo overlay.
        String[][] moves = {{"JUMP_METER", "CONTEXTUAL_INFO_BAR"}, {"EXPERIENCE_BAR", "CONTEXTUAL_INFO_BAR"},
                {"DEBUG_OVERLAY", "DEMO_OVERLAY"}};
        for (String[] move : moves) {
            boolean gone = layers == null ? !RetromodVersion.mcVersionExceeds("1.21.6", RetromodVersion.TARGET_MC_VERSION)
                    : !hasField(layers, move[0]) && hasField(layers, move[1]);
            if (gone) t.registerFieldRedirect(GUI_LAYERS, move[0], GUI_LAYERS, move[1]);
        }
    }

    private static void rename(RetromodTransformer t, String owner, String name, String desc, String newName, String newDesc,
            String since) {
        if (NeoForgeMovedMembers.moved(owner, name, desc, owner, newName, newDesc, since)) {
            t.registerMethodRedirect(owner, name, desc, owner, newName, newDesc);
        }
    }

    /** Sends {@code owner.name desc} to the client helper, which takes the event as {@code eventType}. */
    private static void read(RetromodTransformer t, String owner, String name, String desc, String helperName, String eventType,
            String returnType) {
        t.registerMethodRedirect(owner, name, desc, CLIENT_READS, helperName, "(L" + eventType + ";)" + returnType, true);
    }

    /** Sends {@code RenderNameTagEvent.name()} to the name tag helper. */
    private static void nameTagRead(RetromodTransformer t, String name, String returnType, String helperName) {
        t.registerMethodRedirect(NAME_TAG, name, "()" + returnType, NAME_TAG_READS, helperName, "(L" + NAME_TAG + ";)" + returnType, true);
    }

    /** Whether the host lacks the member; offline, whether the target is at least {@code since}. */
    static boolean lacks(String owner, String name, String desc, String since) {
        ClassNode host = ClassResourceInspector.read(owner);
        if (host == null) return !RetromodVersion.mcVersionExceeds(since, RetromodVersion.TARGET_MC_VERSION);
        return host.methods.stream().noneMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static boolean hostHasOrOffline(String owner, String name) {
        ClassNode host = ClassResourceInspector.read(owner);
        return host == null || host.methods.stream().anyMatch(m -> m.name.equals(name));
    }

    private static boolean hasField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name)) return true;
        }
        return false;
    }
}
