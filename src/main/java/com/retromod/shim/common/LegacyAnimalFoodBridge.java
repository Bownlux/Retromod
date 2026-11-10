/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyAnimalFoodTags;
import com.retromod.shim.common.embedded.LegacyTagItemSet;
import com.retromod.shim.forge.embedded.LegacyDataPackSource;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.List;
import java.util.function.Function;

/** Keeps the animal and villager food fields that mods read and extend working after they became item tags and a component. */
public final class LegacyAnimalFoodBridge {

    static final String HELPER = "com/retromod/shim/common/embedded/LegacyAnimalFoodTags";
    static final String ITEM_SET = "com/retromod/shim/common/embedded/LegacyTagItemSet";
    static final String PACK_SOURCE = "com/retromod/shim/forge/embedded/LegacyDataPackSource";
    static final String GETTER_DESC = "()Ljava/lang/Object;";
    static final String SETTER_DESC = "(Ljava/lang/Object;)V";
    private static final String ENTITY = "net/minecraft/world/entity/";
    private static final String ITEM_TAGS = "net/minecraft/tags/ItemTags";

    /**
     * One removed field: the owner before and after 26.1 moved it, its Mojang name and SRG id, the
     * helper's accessors, what replaced it on the host, and the first version without it, used
     * when the host's classes cannot be read.
     */
    record RemovedField(String owner, String movedOwner, String name, String srg, String getter, String setter,
            String replacement, String firstWithout) {}

    static final List<RemovedField> FIELDS = List.of(
            new RemovedField(ENTITY + "animal/Chicken", ENTITY + "animal/chicken/Chicken", "FOOD_ITEMS", "f_28233_",
                    "chickenFood", "setChickenFood", ITEM_TAGS, "1.20.5"),
            new RemovedField(ENTITY + "animal/Pig", ENTITY + "animal/pig/Pig", "FOOD_ITEMS", "f_29458_",
                    "pigFood", "setPigFood", ITEM_TAGS, "1.20.5"),
            new RemovedField(ENTITY + "animal/Cat", ENTITY + "animal/feline/Cat", "TEMPT_INGREDIENT", "f_28103_",
                    "catFood", "setCatFood", ITEM_TAGS, "1.20.5"),
            new RemovedField(ENTITY + "animal/Parrot", ENTITY + "animal/parrot/Parrot", "TAME_FOOD", "f_29357_",
                    "parrotFood", "setParrotFood", ITEM_TAGS, "1.20.5"),
            // The Mojang mappings have it through 1.21.1 and VILLAGER_PICKS_UP in its place from 1.21.2.
            new RemovedField(ENTITY + "npc/Villager", ENTITY + "npc/villager/Villager", "WANTED_ITEMS", "f_35372_",
                    "villagerWantedItems", "setVillagerWantedItems", ITEM_TAGS, "1.21.2"),
            new RemovedField(ENTITY + "npc/Villager", ENTITY + "npc/villager/Villager", "FOOD_POINTS", "f_35369_",
                    "villagerFoodPoints", "setVillagerFoodPoints", "net/minecraft/world/food/VillagerFood", "26.3"));

    private LegacyAnimalFoodBridge() {}

    public static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector::read, RetromodVersion.TARGET_MC_VERSION);
    }

    static void register(RetromodTransformer transformer, Function<String, ClassNode> host, String target) {
        boolean any = false;
        for (RemovedField field : FIELDS) {
            if (!hostDropped(field, host, target)) continue;
            // A read becomes the helper's view of the replacement, and a write adds to it.
            for (String owner : List.of(field.owner(), field.movedOwner())) {
                for (String name : List.of(field.name(), field.srg())) {
                    transformer.registerStaticFieldBridge(owner, name, HELPER,
                            field.getter(), GETTER_DESC, field.setter(), SETTER_DESC);
                }
            }
            any = true;
        }
        if (!any) return;
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyAnimalFoodTags.class);
        SyntheticEmbedder.registerClassResource(transformer, ITEM_SET, LegacyTagItemSet.class);
        SyntheticEmbedder.registerClassResource(transformer, PACK_SOURCE, LegacyDataPackSource.class);
    }

    /** Whether the host lacks the field and has what replaced it; with no host classes to read, the version decides. */
    static boolean hostDropped(RemovedField field, Function<String, ClassNode> host, String target) {
        ClassNode moved = host.apply(field.movedOwner());
        ClassNode owner = moved != null ? moved : host.apply(field.owner());
        if (owner == null) {
            return target != null && !RetromodVersion.mcVersionExceeds(field.firstWithout(), target);
        }
        for (FieldNode declared : owner.fields) {
            if (declared.name.equals(field.name())) return false;
        }
        return host.apply(field.replacement()) != null;
    }
}
