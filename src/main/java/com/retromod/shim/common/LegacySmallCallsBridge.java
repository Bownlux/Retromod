/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.function.Function;

/** Single removed members that kept otherwise clean 1.21.1 mods from linking on 1.21.11 and 26.x, each rebuilt on its replacement. */
public final class LegacySmallCallsBridge {

    static final String CLIENT = RebuiltApiClasses.ROOT + "minecraft/client/LegacySmallClientCalls";
    static final String BOOLEAN_OPTIONS = RebuiltApiClasses.ROOT + "minecraft/client/LegacyBooleanOptions";
    static final String WORLD = RebuiltApiClasses.ROOT + "minecraft/world/LegacySmallWorldCalls";
    static final String PACKS = RebuiltApiClasses.ROOT + "minecraft/server/packs/LegacyPackOpening";
    static final String DEVICE = "com/retromod/shim/common/embedded/LegacyDeviceLimits";
    static final String CYCLE_BUTTONS = RebuiltApiClasses.ROOT + "minecraft/client/gui/LegacyCycleButtons";
    static final String USER_TYPES = RebuiltApiClasses.ROOT + "minecraft/client/LegacyUserTypes";
    static final String CONTEXT_MATRICES = RebuiltApiClasses.ROOT + "minecraft/client/LegacyLevelContextMatrices";
    static final String MINECARTS = RebuiltApiClasses.ROOT + "minecraft/world/entity/LegacyMinecarts";
    static final String COW_SOUNDS = RebuiltApiClasses.ROOT + "minecraft/sounds/LegacyCowSounds";
    static final String BOSS_EVENTS = RebuiltApiClasses.ROOT + "minecraft/server/level/LegacyBossEvents";
    static final String BLOCK_SHEETS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyBlockSheets";
    static final String CULLED_BLOCK_SHEETS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyCulledBlockSheets";
    static final String VERTEX_OFFSETS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyVertexOffsets";
    static final String PEARL_VERTEX_OFFSETS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyPearlVertexOffsets";
    static final String SOUND_EVENTS = "net/minecraft/sounds/SoundEvents";
    static final String CYCLE_BUTTON = "net/minecraft/client/gui/components/CycleButton";
    static final String USER = "net/minecraft/client/User";
    static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    static final String MINECART = "net/minecraft/world/entity/vehicle/minecart/";
    static final String LEVEL_CONTEXT = "net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderContext";

    static final String OPTIONS = "net/minecraft/client/Options";
    static final String WIDGET = "net/minecraft/client/gui/components/AbstractWidget";
    static final String STRING_WIDGET = "net/minecraft/client/gui/components/StringWidget";
    static final String TOAST = "net/minecraft/client/gui/components/toasts/SystemToast";
    static final String OPTION = "net/minecraft/client/OptionInstance";
    static final String PROVIDER = "net/minecraft/client/particle/ParticleProvider";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    static final String PLAYER_LIST = "net/minecraft/server/players/PlayerList";
    static final String PACK = "net/minecraft/server/packs/repository/Pack";
    static final String BIOME = "net/minecraft/world/level/biome/Biome";
    static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    static final String RISING_PARTICLE = "net/minecraft/client/particle/RisingParticle";
    static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";
    static final String MODEL_PART = "net/minecraft/client/model/geom/ModelPart";
    static final String LIVING_RENDERER = "net/minecraft/client/renderer/entity/LivingEntityRenderer";
    static final String BOSS_EVENT = "net/minecraft/server/level/ServerBossEvent";
    static final String PACKET_LISTENER = "net/minecraft/client/multiplayer/ClientPacketListener";
    static final String COMMON_INPUTS = "net/minecraft/client/gui/navigation/CommonInputs";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String SHEETS = "net/minecraft/client/renderer/Sheets";
    static final String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";
    static final String RENDER_TYPE = "()Lnet/minecraft/client/renderer/rendertype/RenderType;";
    static final String BLAZE_FORMAT = "com/mojang/blaze3d/vertex/VertexFormat";
    static final String PEARL_FORMAT = "com/mojang/renderpearl/api/vertex/VertexFormat";
    // The block rotation enum under its 1.21.1 package and its 26.x one.
    static final List<String> MODEL_ROTATIONS = List.of("net/minecraft/client/resources/model/BlockModelRotation",
            "net/minecraft/client/renderer/block/dispatch/BlockModelRotation");

    static final String COMPONENT = "Lnet/minecraft/network/chat/Component;";
    static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    static final String SET_KEY = "(Lnet/minecraft/client/KeyMapping;Lcom/mojang/blaze3d/platform/InputConstants$Key;)V";
    static final String MULTILINE = "(Lnet/minecraft/client/Minecraft;L" + TOAST + "$SystemToastId;" + COMPONENT + COMPONENT + ")L" + TOAST + ";";
    static final String CREATE_BOOLEAN = "(Ljava/lang/String;ZLjava/util/function/Consumer;)L" + OPTION + ";";
    static final String OLD_PARTICLE = "(Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDD)"
            + "Lnet/minecraft/client/particle/Particle;";
    static final String SPAWN = "(" + STACK + ")Lnet/minecraft/world/entity/item/ItemEntity;";
    static final String IS_OP = "(Lcom/mojang/authlib/GameProfile;)Z";
    static final String OLD_OPEN = "()Lnet/minecraft/server/packs/PackResources;";
    static final String STREAM_OPEN = "()Ljava/util/stream/Stream;";
    static final String TEMPERATURE = "(Lnet/minecraft/core/BlockPos;)F";

    // Owners old code names for entity calls, under their 1.21.1 and current names; mod subclasses, and host classes
    // when the game jar is indexed, are matched through the class hierarchy as well.
    static final List<String> LIVING_OWNERS = List.of(LIVING, "net/minecraft/world/entity/Mob",
            "net/minecraft/world/entity/PathfinderMob", "net/minecraft/world/entity/monster/Monster",
            "net/minecraft/world/entity/animal/Animal", "net/minecraft/world/entity/monster/EnderMan",
            "net/minecraft/world/entity/monster/Enderman", "net/minecraft/world/entity/player/Player",
            "net/minecraft/world/entity/animal/ShoulderRidingEntity",
            "net/minecraft/world/entity/animal/parrot/ShoulderRidingEntity");
    static final List<String> ENTITY_OWNERS = java.util.stream.Stream.of(List.of(ENTITY), LIVING_OWNERS, List.of(
            "net/minecraft/world/entity/vehicle/Boat", "net/minecraft/world/entity/vehicle/boat/Boat",
            "net/minecraft/world/entity/vehicle/ChestBoat", "net/minecraft/world/entity/vehicle/boat/ChestBoat",
            "net/minecraft/world/entity/item/FallingBlockEntity",
            "net/minecraft/world/entity/projectile/ThrowableItemProjectile",
            "net/minecraft/world/entity/projectile/throwableitemprojectile/ThrowableItemProjectile"))
            .flatMap(List::stream).toList();
    static final String ITEM_LIKE = "Lnet/minecraft/world/level/ItemLike;";
    static final String ITEM_ENTITY = "Lnet/minecraft/world/entity/item/ItemEntity;";

    private LegacySmallCallsBridge() {}

    /** Registers each bridge whose old member the host lacks; offline, where nothing is readable, all of them. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        RebuiltApiClasses.registerTree(t, CLIENT);
        RebuiltApiClasses.registerTree(t, WORLD);
        if (lacks(host.apply(OPTIONS), "setKey", SET_KEY)) {
            t.registerMethodRedirect(OPTIONS, "setKey", SET_KEY, CLIENT, "setKey", "(L" + OPTIONS + ";" + SET_KEY.substring(1), true);
        }
        if (lacks(host.apply(WIDGET), "clicked", "(DD)Z")) registerClicked(t);
        // These two changed in 26.2, so an unreadable host decides by the target version.
        if (lacks(host.apply(TOAST), "multiline", MULTILINE, "26.2")) {
            t.registerMethodRedirect(TOAST, "multiline", MULTILINE, CLIENT, "multiline", MULTILINE);
        }
        if (lacks(host.apply(OPTION), "createBoolean", CREATE_BOOLEAN, "26.2")) {
            RebuiltApiClasses.registerTree(t, BOOLEAN_OPTIONS);
            t.registerMethodRedirect(OPTION, "createBoolean", CREATE_BOOLEAN, BOOLEAN_OPTIONS, "createBoolean", CREATE_BOOLEAN);
        }
        String setColor = "(I)L" + STRING_WIDGET + ";";
        if (lacks(host.apply(STRING_WIDGET), "setColor", setColor)) {
            t.registerMethodRedirect(STRING_WIDGET, "setColor", setColor, CLIENT, "setColor", "(L" + STRING_WIDGET + ";I)L"
                    + STRING_WIDGET + ";", true);
        }
        if (lacks(host.apply(PROVIDER), "createParticle", OLD_PARTICLE)) {
            t.registerMethodRedirect(PROVIDER, "createParticle", OLD_PARTICLE, CLIENT, "createParticle",
                    "(L" + PROVIDER + ";" + OLD_PARTICLE.substring(1), true);
        }
        // Each old spawnAtLocation form, with what it drops after the entity: a stack, a raised stack, an item, a raised item.
        for (String dropped : new String[]{STACK, STACK + "F", ITEM_LIKE, ITEM_LIKE + "I"}) {
            String desc = "(" + dropped + ")" + ITEM_ENTITY;
            if (lacks(host.apply(ENTITY), "spawnAtLocation", desc)) {
                entityCall(t, ENTITY_OWNERS, "spawnAtLocation", desc, "(L" + ENTITY + ";" + dropped + ")" + ITEM_ENTITY);
            }
        }
        if (lacks(host.apply(LIVING), "getArmorSlots", "()Ljava/lang/Iterable;")) {
            entityCall(t, LIVING_OWNERS, "getArmorSlots", "()Ljava/lang/Iterable;", "(L" + LIVING + ";)Ljava/lang/Iterable;");
        }
        if (lacks(host.apply(LIVING), "getAllSlots", "()Ljava/lang/Iterable;")) {
            entityCall(t, LIVING_OWNERS, "getAllSlots", "()Ljava/lang/Iterable;", "(L" + LIVING + ";)Ljava/lang/Iterable;");
        }
        if (lacks(host.apply(PLAYER_LIST), "isOp", IS_OP)) {
            t.registerMethodRedirect(PLAYER_LIST, "isOp", IS_OP, WORLD, "isOp", "(L" + PLAYER_LIST + ";" + IS_OP.substring(1), true);
        }
        if (lacks(host.apply(BIOME), "getTemperature", TEMPERATURE)) {
            t.registerMethodRedirect(BIOME, "getTemperature", TEMPERATURE, WORLD, "getTemperature",
                    "(L" + BIOME + ";" + TEMPERATURE.substring(1), true);
        }
        if (packOpensAsStream(host.apply(PACK))) {
            RebuiltApiClasses.registerTree(t, PACKS);
            t.registerMethodRedirect(PACK, "open", OLD_OPEN, PACKS, "open", "(L" + PACK + ";)Lnet/minecraft/server/packs/PackResources;", true);
        }
        if (lacks(host.apply(RENDER_SYSTEM), "maxSupportedTextureSize", "()I")) {
            t.registerMethodRedirect(RENDER_SYSTEM, "maxSupportedTextureSize", "()I", DEVICE, "maxSupportedTextureSize", "()I");
        }
        registerSecondRound(t, host);
    }

    /** The second round of single removed members, each the last link gap of a jar. */
    static void registerSecondRound(RetromodTransformer t, Function<String, ClassNode> host) {
        // A knot breaking plays LEAD_UNTIED now and a knot placed LEAD_TIED; LEAD_BREAK is a lead snapping.
        if (lacksField(host.apply(SOUND_EVENTS), "LEASH_KNOT_BREAK")) {
            t.registerFieldRedirect(SOUND_EVENTS, "LEASH_KNOT_BREAK", SOUND_EVENTS, "LEAD_UNTIED");
            t.registerFieldRedirect(SOUND_EVENTS, "LEASH_KNOT_PLACE", SOUND_EVENTS, "LEAD_TIED");
        }
        String component = "Lnet/minecraft/network/chat/Component;";
        String builder = "L" + CYCLE_BUTTON + "$Builder;";
        String booleanBuilder = "(" + component + component + ")" + builder;
        if (lacks(host.apply(CYCLE_BUTTON), "booleanBuilder", booleanBuilder)) {
            RebuiltApiClasses.registerTree(t, CYCLE_BUTTONS);
            t.registerMethodRedirect(CYCLE_BUTTON, "booleanBuilder", booleanBuilder, CYCLE_BUTTONS, "booleanBuilder", booleanBuilder);
        }
        if (host.apply(USER + "$Type") == null) {
            RebuiltApiClasses.registerTree(t, USER_TYPES);
            t.registerClassRedirect(USER + "$Type", USER_TYPES);
            t.registerMethodRedirect(USER, "getType", "()L" + USER + "$Type;", USER_TYPES, "getType",
                    "(L" + USER + ";)L" + USER_TYPES + ";", true);
        }
        registerMinecarts(t, host);
        registerTooltipRender(t, host.apply(SCREEN));
        // Fabric API's level render context lost positionMatrix(); the camera render state holds the same rotation.
        if (lacks(host.apply(LEVEL_CONTEXT), "positionMatrix", "()Lorg/joml/Matrix4f;", "26.1")) {
            RebuiltApiClasses.registerTree(t, CONTEXT_MATRICES);
            t.registerMethodRedirect(LEVEL_CONTEXT, "positionMatrix", "()Lorg/joml/Matrix4f;", CONTEXT_MATRICES, "positionMatrix",
                    "(Ljava/lang/Object;)Lorg/joml/Matrix4f;", true);
        }
        registerThirdRound(t, host);
        registerFourthRound(t, host);
    }

    /** The third round of single removed members, each the last link gap of a jar. */
    static void registerThirdRound(RetromodTransformer t, Function<String, ClassNode> host) {
        // 26.1 moved the cow sounds into sound variants; an old read gets the classic cow's sound.
        if (lacksField(host.apply(SOUND_EVENTS), "COW_AMBIENT", "26.1")) {
            RebuiltApiClasses.registerTree(t, COW_SOUNDS);
            String sound = "()Lnet/minecraft/sounds/SoundEvent;";
            for (String[] cow : new String[][]{{"COW_AMBIENT", "ambient"}, {"COW_HURT", "hurt"}, {"COW_DEATH", "death"},
                    {"COW_STEP", "step"}}) {
                t.registerStaticFieldBridge(SOUND_EVENTS, cow[0], COW_SOUNDS, cow[1], sound, null, null);
            }
        }
        // A rising particle now takes its sprite when built; the old flow set it afterwards, so a subclass passes none.
        String level = "(Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDD";
        if (lacks(host.apply(RISING_PARTICLE), "<init>", level + ")V")) {
            t.registerSuperConstructorRedirect(RISING_PARTICLE, level + ")V",
                    level + "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V");
        }
        String customTag = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;L" + ENTITY
                + ";Lnet/minecraft/world/item/component/CustomData;)V";
        if (lacks(host.apply(ENTITY_TYPE), "updateCustomEntityTag", customTag)) {
            t.registerMethodRedirect(ENTITY_TYPE, "updateCustomEntityTag", customTag, WORLD, "updateCustomEntityTag", customTag);
        }
        if (lacks(host.apply(ENTITY), "canBeCollidedWith", "()Z")) {
            entityCall(t, ENTITY_OWNERS, "canBeCollidedWith", "()Z", "(L" + ENTITY + ";)Z");
        }
        if (lacks(host.apply(MODEL_PART), "getAllParts", STREAM_OPEN)) {
            t.registerMethodRedirect(MODEL_PART, "getAllParts", STREAM_OPEN, CLIENT, "getAllParts",
                    "(L" + MODEL_PART + ";)Ljava/util/stream/Stream;", true);
        }
        // The overlay is worked out from the render state now; the old static form read the entity itself.
        String overlay = "(L" + LIVING + ";F)I";
        if (lacks(host.apply(LIVING_RENDERER), "getOverlayCoords", overlay)) {
            t.registerInheritedMethodRedirect(LIVING_RENDERER, "getOverlayCoords", overlay, CLIENT, "getOverlayCoords", overlay);
            t.registerMethodRedirect(LIVING_RENDERER, "getOverlayCoords", overlay, CLIENT, "getOverlayCoords", overlay);
        }
    }

    /** The fourth round of single removed members, each small and spread over several jars. */
    static void registerFourthRound(RetromodTransformer t, Function<String, ClassNode> host) {
        // 26.1 gave the boss bar its id from the caller; the old constructor picked a random one itself.
        String boss = "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/world/BossEvent$BossBarColor;"
                + "Lnet/minecraft/world/BossEvent$BossBarOverlay;)";
        if (lacks(host.apply(BOSS_EVENT), "<init>", boss + "V", "26.1")) {
            RebuiltApiClasses.registerTree(t, BOSS_EVENTS);
            t.registerConstructorRedirect(BOSS_EVENT, boss + "V", BOSS_EVENTS, "create", boss + "L" + BOSS_EVENT + ";");
            // A subclass's super(name, color, overlay) gets a random id slid under its three arguments.
            t.registerSuperConstructorAdapter(BOSS_EVENT, boss + "V", "(Ljava/util/UUID;" + boss.substring(1) + "V",
                    LegacySmallCallsBridge::pushRandomIdUnderThree);
        }
        if (lacks(host.apply(PACKET_LISTENER), "sendUnsignedCommand", "(Ljava/lang/String;)Z")) {
            t.registerMethodRedirect(PACKET_LISTENER, "sendUnsignedCommand", "(Ljava/lang/String;)Z", CLIENT, "sendUnsignedCommand",
                    "(L" + PACKET_LISTENER + ";Ljava/lang/String;)Z", true);
        }
        if (host.apply(COMMON_INPUTS) == null) {
            t.registerMethodRedirect(COMMON_INPUTS, "selected", "(I)Z", CLIENT, "selected", "(I)Z");
        }
        if (lacks(host.apply(ITEM_STACK), "getDescriptionId", "()Ljava/lang/String;")) {
            t.registerMethodRedirect(ITEM_STACK, "getDescriptionId", "()Ljava/lang/String;", WORLD, "getDescriptionId",
                    "(L" + ITEM_STACK + ";)Ljava/lang/String;", true);
        }
        String self = "()L" + STRING_WIDGET + ";";
        for (String align : new String[]{"alignLeft", "alignCenter", "alignRight"}) {
            if (lacks(host.apply(STRING_WIDGET), align, self)) {
                t.registerMethodRedirect(STRING_WIDGET, align, self, CLIENT, align, "(L" + STRING_WIDGET + ";)L" + STRING_WIDGET + ";", true);
            }
        }
        // The identity rotation is the only one old mods name, and it kept its meaning under a new name.
        for (String rotation : MODEL_ROTATIONS) {
            ClassNode node = host.apply(rotation);
            if (node == null || lacksField(node, "X0_Y0")) t.registerFieldRedirect(rotation, "X0_Y0", rotation, "IDENTITY");
        }
        registerBlockSheets(t, host);
        registerVertexOffsets(t, host);
    }

    /** Turns {@code [a, b, c]} on the stack into {@code [randomUUID, a, b, c]} with stack moves only, below an unready receiver. */
    static void pushRandomIdUnderThree(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;", false);
        // [a, b, c, id] to [a, id, b, c], then to [b, c, a, id], [b, c, id, a] and finally [id, a, b, c].
        mv.visitInsn(Opcodes.DUP_X2);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.DUP2_X2);
        mv.visitInsn(Opcodes.POP2);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitInsn(Opcodes.DUP2_X2);
        mv.visitInsn(Opcodes.POP2);
    }

    /** The block-atlas render types Sheets stopped handing out, each from 1.21.11 or 26.2 on. */
    static void registerBlockSheets(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode sheets = host.apply(SHEETS);
        // 26.1 dropped the solid one and 26.2 the cutout one.
        for (String[] sheet : new String[][]{{"solidBlockSheet", "26.1"}, {"cutoutBlockSheet", "26.2"}}) {
            if (!lacks(sheets, sheet[0], RENDER_TYPE, sheet[1])) continue;
            RebuiltApiClasses.registerTree(t, BLOCK_SHEETS);
            t.registerMethodRedirect(SHEETS, sheet[0], RENDER_TYPE, BLOCK_SHEETS, sheet[0], RENDER_TYPE);
        }
        if (!lacks(sheets, "translucentCullBlockSheet", RENDER_TYPE)) return;
        // 26.3 has a culled translucent entity type again; 1.21.11 to 26.2 only the unculled one.
        String culled = "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;";
        ClassNode renderTypes = host.apply(RENDER_TYPES);
        boolean hostCulls = renderTypes == null ? targetAtLeast("26.3") : !lacks(renderTypes, "entityTranslucentCull", culled);
        if (hostCulls) {
            RebuiltApiClasses.registerTree(t, CULLED_BLOCK_SHEETS);
            t.registerMethodRedirect(SHEETS, "translucentCullBlockSheet", RENDER_TYPE, CULLED_BLOCK_SHEETS, "translucentCullBlockSheet",
                    RENDER_TYPE);
        } else {
            RebuiltApiClasses.registerTree(t, BLOCK_SHEETS);
            t.registerMethodRedirect(SHEETS, "translucentCullBlockSheet", RENDER_TYPE, BLOCK_SHEETS, "translucentBlockSheet", RENDER_TYPE);
        }
    }

    /** VertexFormat.getOffset, gone from 26.2 where each format's elements carry their offsets; 26.3 moved the class. */
    static void registerVertexOffsets(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode pearl = host.apply(PEARL_FORMAT);
        ClassNode blaze = host.apply(BLAZE_FORMAT);
        boolean offline = pearl == null && blaze == null;
        if (pearl != null || (offline && targetAtLeast("26.3"))) {
            if (lacks(pearl, "getOffset", offsetDesc(PEARL_FORMAT))) registerOffset(t, PEARL_FORMAT, PEARL_VERTEX_OFFSETS);
        } else if (blaze != null || targetAtLeast("26.2")) {
            if (lacks(blaze, "getOffset", offsetDesc(BLAZE_FORMAT))) registerOffset(t, BLAZE_FORMAT, VERTEX_OFFSETS);
        }
    }

    private static String offsetDesc(String format) {
        return "(L" + format + "Element;)I";
    }

    private static void registerOffset(RetromodTransformer t, String format, String helper) {
        RebuiltApiClasses.registerTree(t, helper);
        t.registerMethodRedirect(format, "getOffset", offsetDesc(format), helper, "getOffset", "(L" + format + ";" + offsetDesc(format).substring(1), true);
    }

    /** The old minecart constructors that took a level and a position. */
    static void registerMinecarts(RetromodTransformer t, Function<String, ClassNode> host) {
        String position = "(Lnet/minecraft/world/level/Level;DDD)";
        for (String[] cart : new String[][]{{"MinecartChest", "chest"}, {"MinecartTNT", "tnt"}}) {
            String type = MINECART + cart[0];
            if (!lacks(host.apply(type), "<init>", position + "V")) continue;
            RebuiltApiClasses.registerTree(t, MINECARTS);
            // 1.21.1 kept the carts one package up; the class move renames them, so both names are keyed.
            for (String name : new String[]{type, "net/minecraft/world/entity/vehicle/" + cart[0]}) {
                t.registerConstructorRedirect(name, position + "V", MINECARTS, cart[1], position + "L" + type + ";");
            }
        }
    }

    /** Screen.renderWithTooltip, renamed to renderWithTooltipAndSubtitles in 1.21.11 and to the extract form in 26.1. */
    static void registerTooltipRender(RetromodTransformer t, ClassNode screen) {
        String[] target = tooltipTarget(screen);
        if (target == null) return;
        for (String name : new String[]{"renderWithTooltip", "renderWithTooltipAndSubtitles", "extractWithTooltip",
                "extractWithTooltipAndSubtitles"}) {
            if (name.equals(target[0])) continue;
            for (String graphics : new String[]{"net/minecraft/client/gui/GuiGraphics", "net/minecraft/client/gui/GuiGraphicsExtractor"}) {
                String desc = "(L" + graphics + ";IIF)V";
                // Mods call it on their own screen class, so subclasses are matched through the hierarchy.
                t.registerInheritedMethodRedirect(SCREEN, name, desc, SCREEN, target[0], target[1]);
            }
        }
    }

    /** The host's tooltip-render method name and descriptor; offline, the target version decides. */
    static String[] tooltipTarget(ClassNode screen) {
        String[][] candidates = {
            {"extractRenderStateWithTooltipAndSubtitles", "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"},
            {"renderWithTooltipAndSubtitles", "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"}};
        if (screen == null) {
            if (targetAtLeast("26.1")) return candidates[0];
            return targetAtLeast("1.21.11") ? candidates[1] : null;
        }
        for (String[] candidate : candidates) {
            if (!lacks(screen, candidate[0], candidate[1])) return candidate;
        }
        return null;
    }

    /** The same, where only hosts from {@code since} lack the field when the class cannot be read. */
    private static boolean lacksField(ClassNode owner, String name, String since) {
        if (owner == null) return targetAtLeast(since);
        return lacksField(owner, name);
    }

    private static boolean lacksField(ClassNode owner, String name) {
        if (owner == null) return true;
        for (org.objectweb.asm.tree.FieldNode field : owner.fields) {
            if (field.name.equals(name)) return false;
        }
        return true;    }

    /** AbstractWidget.clicked(x, y) was the active, visible and under-the-mouse check that isMouseOver makes now. */
    static void registerClicked(RetromodTransformer t) {
        t.registerInheritedMethodRedirect(WIDGET, "clicked", "(DD)Z", WIDGET, "isMouseOver", "(DD)Z");
        for (String owner : List.of(WIDGET, "net/minecraft/client/gui/components/AbstractButton",
                "net/minecraft/client/gui/components/Button")) {
            t.registerMethodRedirect(owner, "clicked", "(DD)Z", owner, "isMouseOver", "(DD)Z");
        }
    }

    private static void entityCall(RetromodTransformer t, List<String> owners, String name, String desc, String helperDesc) {
        // Mod subclasses through the hierarchy, Minecraft's own subclasses by name.
        t.registerInheritedMethodRedirect(owners.get(0), name, desc, WORLD, name, helperDesc);
        for (String owner : owners) t.registerMethodRedirect(owner, name, desc, WORLD, name, helperDesc, true);
    }

    /** Whether the host class lacks the member; an unreadable class counts as lacking it, since the shim gate starts at 1.21.11. */
    static boolean lacks(ClassNode owner, String name, String desc) {
        if (owner == null) return true;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return false;
        }
        return true;
    }

    /** The same, where only hosts from {@code since} lack the member when the class cannot be read. */
    static boolean lacks(ClassNode owner, String name, String desc, String since) {
        if (owner == null) return targetAtLeast(since);
        return lacks(owner, name, desc);
    }

    private static boolean targetAtLeast(String version) {
        return com.retromod.core.RetromodVersion.compareMcVersions(com.retromod.core.RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }

    /** 26.3's Pack.open() returns a stream of layers; a host whose open() still returns one resources object keeps it. */
    static boolean packOpensAsStream(ClassNode pack) {
        if (pack == null) return targetAtLeast("26.3");
        return lacks(pack, "open", OLD_OPEN) && !lacks(pack, "open", STREAM_OPEN);
    }
}
