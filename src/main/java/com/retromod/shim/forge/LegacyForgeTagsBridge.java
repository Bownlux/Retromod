/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Keeps Forge 1.20.1 tag constants linking on Forge 26.2, which renamed most of them to {@code c:} tags. */
public final class LegacyForgeTagsBridge {

    static final String ITEMS = "net/minecraftforge/common/Tags$Items";
    static final String BLOCKS = "net/minecraftforge/common/Tags$Blocks";
    static final String ITEM_TAGS = "net/minecraft/tags/ItemTags";
    static final String HELPER = "com/retromod/generated/LegacyForgeTags";
    static final String TAG_KEY = "Lnet/minecraft/tags/TagKey;";

    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String FIRST = ForgeHostShape.FIRST_CHECKED_VERSION;

    /** Old owner and field, then the host's counterpart; STONE's {@code c:stones} leaves out polished and infested stone. */
    static final String[][] RENAMES = renames();

    /** Old owner, field and {@code forge:} path, then the first Forge version checked to lack it. */
    static final String[][] KEPT_IDS = keptIds();

    private LegacyForgeTagsBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // A renamed constant reads the host's field, so it holds what Forge's own tag data puts there.
        for (String[] rename : RENAMES) {
            if (ForgeHostMembers.lacksField(rename[0], rename[1], FIRST, host) && hostHas(host, rename[2], rename[3])) {
                t.registerFieldRedirect(rename[0], rename[1], TAG_KEY, rename[2], rename[3], TAG_KEY);
            }
        }
        // A constant with no counterpart keeps its old id, so a mod's own data under that id still fills it.
        List<String[]> kept = new ArrayList<>();
        for (String[] old : KEPT_IDS) {
            if (ForgeHostMembers.lacksField(old[0], old[1], old[3], host)) kept.add(old);
        }
        if (kept.isEmpty()) return;
        t.registerSyntheticClass(HELPER, generateHelper(kept));
        for (String[] old : kept) {
            t.registerFieldRedirect(old[0], old[1], TAG_KEY, HELPER, getterName(old), "()" + TAG_KEY);
        }
    }

    /** Offline the Forge classes cannot be read, and the table was checked against Forge 26.2 and 26.3. */
    private static boolean hostHas(Function<String, ClassNode> host, String owner, String field) {
        ClassNode node = host.apply(owner);
        return node == null || node.fields.stream().anyMatch(f -> f.name.equals(field));
    }

    static String getterName(String[] old) {
        return (old[0].equals(ITEMS) ? "item_" : "block_") + old[1];
    }

    /** One getter per kept constant: {@code TagKey.create(Registries.ITEM or BLOCK, forge:path)}. */
    static byte[] generateHelper(List<String[]> kept) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        for (String[] old : kept) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, getterName(old), "()" + TAG_KEY, null, null);
            mv.visitCode();
            // TagKey.create interns its keys, so every read returns the same key, as the old constant did.
            mv.visitFieldInsn(GETSTATIC, REGISTRIES, old[0].equals(ITEMS) ? "ITEM" : "BLOCK", RESOURCE_KEY);
            mv.visitLdcInsn("forge");
            mv.visitLdcInsn(old[2]);
            mv.visitMethodInsn(INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/tags/TagKey", "create",
                    "(" + RESOURCE_KEY + "L" + IDENTIFIER + ";)" + TAG_KEY, false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String[][] renames() {
        List<String[]> rows = new ArrayList<>();
        // Shared by items and blocks.
        String[][] both = {
            {"STONE", "STONES"}, {"OBSIDIAN", "OBSIDIANS_NORMAL"}, {"NETHERRACK", "NETHERRACKS"}, {"GRAVEL", "GRAVELS"},
            {"SAND", "SANDS"}, {"SAND_COLORLESS", "SANDS_COLORLESS"}, {"SAND_RED", "SANDS_RED"},
            {"SANDSTONE", "SANDSTONE_BLOCKS"}, {"COBBLESTONE", "COBBLESTONES"},
            {"COBBLESTONE_NORMAL", "COBBLESTONES_NORMAL"}, {"COBBLESTONE_INFESTED", "COBBLESTONES_INFESTED"},
            {"COBBLESTONE_MOSSY", "COBBLESTONES_MOSSY"}, {"COBBLESTONE_DEEPSLATE", "COBBLESTONES_DEEPSLATE"},
            {"GLASS", "GLASS_BLOCKS"}, {"GLASS_COLORLESS", "GLASS_BLOCKS_COLORLESS"},
            {"GLASS_SILICA", "GLASS_BLOCKS_CHEAP"}, {"GLASS_TINTED", "GLASS_BLOCKS_TINTED"},
        };
        for (String[] pair : both) {
            rows.add(new String[] {ITEMS, pair[0], ITEMS, pair[1]});
            rows.add(new String[] {BLOCKS, pair[0], BLOCKS, pair[1]});
        }
        String[][] items = {
            {"SHEARS", "TOOLS_SHEAR"}, {"STRING", "STRINGS"}, {"LEATHER", "LEATHERS"},
            {"SLIMEBALLS", "SLIME_BALLS"}, {"GUNPOWDER", "GUNPOWDERS"},
            {"INGOTS_BRICK", "BRICKS_NORMAL"}, {"INGOTS_NETHER_BRICK", "BRICKS_NETHER"},
            {"TOOLS_BOWS", "TOOLS_BOW"}, {"TOOLS_CROSSBOWS", "TOOLS_CROSSBOW"},
            {"TOOLS_FISHING_RODS", "TOOLS_FISHING_ROD"}, {"TOOLS_SHIELDS", "TOOLS_SHIELD"},
            {"TOOLS_TRIDENTS", "TOOLS_TRIDENT"},
        };
        for (String[] pair : items) rows.add(new String[] {ITEMS, pair[0], ITEMS, pair[1]});
        // Armor and heads moved to vanilla's own item tags.
        String[][] vanilla = {
            {"ARMORS_HELMETS", "HEAD_ARMOR"}, {"ARMORS_CHESTPLATES", "CHEST_ARMOR"},
            {"ARMORS_LEGGINGS", "LEG_ARMOR"}, {"ARMORS_BOOTS", "FOOT_ARMOR"}, {"HEADS", "SKULLS"},
        };
        for (String[] pair : vanilla) rows.add(new String[] {ITEMS, pair[0], ITEM_TAGS, pair[1]});
        return rows.toArray(new String[0][]);
    }

    private static String[][] keptIds() {
        List<String[]> rows = new ArrayList<>();
        // The c: dyed tags hold every dyed block, so stained glass by color has no narrower host tag.
        String[] colors = {"BLACK", "BLUE", "BROWN", "CYAN", "GRAY", "GREEN", "LIGHT_BLUE", "LIGHT_GRAY",
            "LIME", "MAGENTA", "ORANGE", "PINK", "PURPLE", "RED", "WHITE", "YELLOW"};
        for (String owner : new String[] {ITEMS, BLOCKS}) {
            rows.add(new String[] {owner, "STAINED_GLASS", "stained_glass", FIRST});
            rows.add(new String[] {owner, "STAINED_GLASS_PANES", "stained_glass_panes", FIRST});
            for (String color : colors) {
                String path = color.toLowerCase(Locale.ROOT);
                rows.add(new String[] {owner, "GLASS_" + color, "glass/" + path, FIRST});
                rows.add(new String[] {owner, "GLASS_PANES_" + color, "glass_panes/" + path, FIRST});
            }
        }
        // c:gems/prismarine holds the crystals, where forge:dusts/prismarine held the shard.
        rows.add(new String[] {ITEMS, "DUSTS_PRISMARINE", "dusts/prismarine", FIRST});
        rows.add(new String[] {BLOCKS, "CHORUS_ADDITIONALLY_GROWS_ON", "chorus_additionally_grows_on", FIRST});
        // Forge 26.3 dropped these with 26.3's data-driven tools; 26.2 still built them from the same ids.
        for (String tier : new String[] {"WOOD", "GOLD", "NETHERITE"}) {
            rows.add(new String[] {BLOCKS, "NEEDS_" + tier + "_TOOL",
                "needs_" + tier.toLowerCase(Locale.ROOT) + "_tool", "26.3"});
        }
        return rows.toArray(new String[0][]);
    }
}
