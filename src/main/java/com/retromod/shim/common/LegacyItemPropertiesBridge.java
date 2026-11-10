/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Rebuilds Minecraft 1.20.1's ItemProperties inside each old mod and adds the item property type Retromod's item definitions name. */
public final class LegacyItemPropertiesBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    public static final String OLD_ITEM_PROPERTIES = "net/minecraft/client/renderer/item/ItemProperties";
    static final String OLD_FUNCTION = "net/minecraft/client/renderer/item/ItemPropertyFunction";
    static final String OLD_CLAMPED = "net/minecraft/client/renderer/item/ClampedItemPropertyFunction";
    // Fabric's ItemPropertyFunction is intermediary class_1800, which the 26.x table names after its 1.21.4 successor.
    static final String RANGE_PROPERTY = "net/minecraft/client/renderer/item/properties/numeric/RangeSelectItemModelProperty";
    static final String FABRIC_REGISTRY = "net/fabricmc/fabric/api/object/builder/v1/client/model/FabricModelPredicateProviderRegistry";

    public static final String ROOT = RebuiltApiClasses.ROOT + "minecraft/client/renderer/item/";
    public static final String ITEM_PROPERTIES = ROOT + "ItemProperties";
    public static final String FUNCTION = ROOT + "ItemPropertyFunction";
    public static final String CLAMPED = ROOT + "ClampedItemPropertyFunction";
    /** The rebuilt class whose {@code install()} adds the property type; only Retromod's own runtime calls it. */
    public static final String INSTALLER = ROOT + "LegacyItemPropertyTypes";
    /** The range property type Retromod's item definitions name, and the field that holds the old property id. */
    public static final String TYPE_ID = "retromod:legacy_item_property";
    public static final String ID_FIELD = "id";
    /** Minecraft 1.21.4 removed ItemProperties, so a mod built for it or later never names it. */
    public static final String FIRST_VERSION_WITHOUT = "1.21.4";
    /** The first host the rebuilt classes are written for on every loader: properties take an item owner, ids are Identifiers. */
    public static final String FIRST_HOST = "26.1";
    /** NeoForge 1.21.11 has both already; Forge keeps ResourceLocation until 26.1 and Fabric runs intermediary names before it. */
    public static final String FIRST_NEOFORGE_HOST = "1.21.11";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String GET_DESC = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/multiplayer/ClientLevel;"
            + "Lnet/minecraft/world/entity/ItemOwner;I)F";

    private static final String ITEM = "Lnet/minecraft/world/item/Item;";
    private static final String ID = "Lnet/minecraft/resources/Identifier;";

    private LegacyItemPropertiesBridge() {}

    /** Registers the rebuilt ItemProperties and its function types where the host has no ItemProperties and the 26.x shapes. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // A server or an offline run reads no client class, and every host from 1.21.4 lacks it.
        if (host.apply(OLD_ITEM_PROPERTIES) != null || !ownerShaped(host)) return;
        RebuiltApiClasses.registerTree(t, ITEM_PROPERTIES);
        // The rebuilt classes keep the old member names, so the class redirects alone relink every call and lambda.
        for (String[] move : new String[][]{{OLD_ITEM_PROPERTIES, ITEM_PROPERTIES}, {OLD_FUNCTION, FUNCTION},
                {OLD_CLAMPED, CLAMPED}}) {
            t.registerClassRedirect(move[0], move[1]);
            t.limitClassRedirectToModsBefore(move[0], FIRST_VERSION_WITHOUT);
        }
    }

    /** The Fabric part: the intermediary function type and Fabric API's removed model predicate registry. */
    public static void registerFabric(RetromodTransformer t) {
        registerFabric(t, ClassResourceInspector::read);
    }

    static void registerFabric(RetromodTransformer t, Function<String, ClassNode> host) {
        if (host.apply(OLD_ITEM_PROPERTIES) != null || !ownerShaped(host)) return;
        // A mod built before 1.21.4 reached class_1800 only as the old function; later mods keep the host's property.
        t.registerClassRedirect(RANGE_PROPERTY, FUNCTION);
        t.limitClassRedirectToModsBefore(RANGE_PROPERTY, FIRST_VERSION_WITHOUT);
        // Fabric API dropped its registry with 1.21.4; both calls went to vanilla's ItemProperties.
        if (host.apply(FABRIC_REGISTRY) != null) return;
        String clamped = "L" + OLD_CLAMPED + ";";
        t.registerMethodRedirect(FABRIC_REGISTRY, "register", "(" + ITEM + ID + clamped + ")V",
                ITEM_PROPERTIES, "register", "(" + ITEM + ID + "L" + CLAMPED + ";)V");
        t.registerMethodRedirect(FABRIC_REGISTRY, "register", "(" + ID + clamped + ")V",
                ITEM_PROPERTIES, "fabricRegisterGeneric", "(" + ID + "L" + CLAMPED + ";)V");
    }

    /** The first host whose old model overrides Retromod's property type reads, for a NeoForge target or any other. */
    public static String firstHost(boolean neoForge) {
        return neoForge ? FIRST_NEOFORGE_HOST : FIRST_HOST;
    }

    // Whether the host names ids Identifier and its range properties take an item owner. A server or an offline run reads
    // no client class, so there the version decides; the shims that call this register from those versions on.
    private static boolean ownerShaped(Function<String, ClassNode> host) {
        ClassNode property = host.apply(RANGE_PROPERTY);
        if (property == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_NEOFORGE_HOST) >= 0;
        }
        return host.apply(IDENTIFIER) != null
                && property.methods.stream().anyMatch(m -> m.name.equals("get") && m.desc.equals(GET_DESC));
    }

    /** Adds Retromod's item property type on a client; each loader's entry point calls it while mods start. */
    public static void addPropertyType() {
        String first = firstHost(McReflect.isNeoForge());
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, first) < 0) return;
        try {
            // Retromod's own unrelocated copy registers the type; every mod's copy records into the store it reads.
            Class<?> installer = Class.forName(INSTALLER.replace('/', '.'), true, LegacyItemPropertiesBridge.class.getClassLoader());
            Object added = installer.getMethod("install").invoke(null);
            if (Boolean.TRUE.equals(added)) {
                LOGGER.debug(RetromodLog.DETAIL, "Added the item property type {} for old model overrides", TYPE_ID);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            LOGGER.warn("Retromod did not add the item property type {}, which its item definitions for old model"
                    + " overrides name: {}", TYPE_ID, failure.toString());
        }
    }

    /** A mixin target that is the removed ItemProperties, which the redirect would land on the mod's own copy; null for none. */
    public static String removedMixinTarget(List<String> targets, UnaryOperator<String> redirectForCurrentMod) {
        // The copy has none of the old private maps, so a mixin into it could only fail. A Fabric mixin still names the
        // class by intermediary here, which reaches the copy through the Mojang name, so the chain is followed.
        for (String target : targets) {
            if (!target.startsWith("net/minecraft/")) continue;
            String current = target;
            for (int hop = 0; hop < 8; hop++) {
                String next = redirectForCurrentMod.apply(current);
                if (next == null || next.equals(current)) break;
                current = next;
            }
            if (current.equals(ITEM_PROPERTIES)) return target;
        }
        return null;
    }
}
