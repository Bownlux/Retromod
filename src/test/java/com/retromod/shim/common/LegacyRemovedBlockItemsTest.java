/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A mod's sign and bed items, built on SignItem and BedItem, still register on 26.3, which removed both. */
class LegacyRemovedBlockItemsTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Each old SignItem constructor builds a standing and wall item with sign text and the block's name")
    void signItemsKeepTheirConstructors(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("standing=oak wall=oak_wall down=true text=true blockName=true", run.call("mod/Items", "sign"));
        assertEquals("standing=oak wall=oak_wall down=true text=true blockName=true", run.call("mod/Items", "blocksFirstSign"));
        assertEquals("standing=oak wall=oak_wall down=false text=true blockName=true", run.call("mod/Items", "hangingSign"));
    }

    @Test
    @DisplayName("A mod's own bed item still extends a block item for its bed, named after the block")
    void bedItemSubclassesStillRegister(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("block=bed blockName=true bed=true", run.call("mod/Items", "bed"));
    }

    @Test
    @DisplayName("An old instanceof check matches vanilla's signs and beds as well as the mod's own")
    void instanceofMatchesEverySignAndBed(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("sign=true hangingSign=true bed=true stone=false", run.call("mod/Items", "vanilla"));
    }

    @Test
    @DisplayName("A mod built for 26.3 keeps the names, and only a host without the classes gets the bridge")
    void newerModsAndOlderHostsAreLeftAlone() {
        Function<String, ClassNode> oldHost = name -> name.endsWith("SignItem") || name.endsWith("BlockItem") ? new ClassNode() : null;
        assertFalse(LegacyRemovedBlockItems.hostLacks(oldHost, "net/minecraft/world/item/SignItem"));
        assertTrue(LegacyRemovedBlockItems.hostLacks(name -> name.endsWith("/BlockItem") ? new ClassNode() : null,
                "net/minecraft/world/item/SignItem"));
        assertFalse(LegacyRemovedBlockItems.hostLacks(name -> null, "net/minecraft/world/item/BedItem", "26.2"));
        assertTrue(LegacyRemovedBlockItems.hostLacks(name -> null, "net/minecraft/world/item/BedItem", "26.3"));
    }

    @Test
    @DisplayName("The redirect stops at mods built for 26.3")
    void redirectIsLimitedToOlderMods(@TempDir Path dir) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> {
            LegacyRemovedBlockItems.register(t, reader(host));
            t.setModSourceVersion("26.3");
        }, BridgeHarness.only(old, "mod/"));
        assertTrue(BridgeHarness.calls(mod.get("mod/Items"), "sign").stream().anyMatch(c -> c.startsWith("net/minecraft/world/item/SignItem.<init>")));
    }

    private static BridgeHarness.Run run(Path dir, String source) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> {
            LegacyRemovedBlockItems.register(t, reader(host));
            t.setModSourceVersion(source);
        }, BridgeHarness.only(old, "mod/"), host);
        return new BridgeHarness.Run(host, mod);
    }

    private static Function<String, ClassNode> reader(Map<String, byte[]> host) {
        return name -> {
            byte[] bytes = host.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
    }

    // 1.21.1's shape, plus the blocks-first constructor 1.21.2 to 26.2 added.
    private static Map<String, String> oldWorld() {
        Map<String, String> s = new HashMap<>(shared());
        s.put("net/minecraft/world/item/SignItem.java", """
                package net.minecraft.world.item;
                import net.minecraft.world.level.block.Block;
                public class SignItem extends StandingAndWallBlockItem {
                    public SignItem(Item.Properties p, Block standing, Block wall) { super(standing, wall, net.minecraft.core.Direction.DOWN, p); }
                    public SignItem(Item.Properties p, Block standing, Block wall, net.minecraft.core.Direction d) { super(standing, wall, d, p); }
                    public SignItem(Block standing, Block wall, Item.Properties p) { super(standing, wall, net.minecraft.core.Direction.DOWN, p); }
                }""");
        s.put("net/minecraft/world/item/BedItem.java", """
                package net.minecraft.world.item;
                public class BedItem extends BlockItem {
                    public BedItem(net.minecraft.world.level.block.Block block, Item.Properties p) { super(block, p); }
                }""");
        s.put("mod/Items.java", """
                package mod;
                import net.minecraft.world.item.*;
                import net.minecraft.world.level.block.Block;
                public final class Items {
                    static String describe(Item item) { return item.toString(); }
                    public static String sign() { return describe(new SignItem(new Item.Properties(), new Block("oak"), new Block("oak_wall"))); }
                    public static String blocksFirstSign() { return describe(new SignItem(new Block("oak"), new Block("oak_wall"), new Item.Properties())); }
                    public static String hangingSign() { return describe(new SignItem(new Item.Properties(), new Block("oak"), new Block("oak_wall"), net.minecraft.core.Direction.UP)); }
                    public static String bed() {
                        Item bed = new Bed(new net.minecraft.world.level.block.BedBlock("bed"), new Item.Properties());
                        return bed + " bed=" + (bed instanceof BedItem);
                    }
                    public static String vanilla() {
                        Item sign = new StandingAndWallBlockItem(new net.minecraft.world.level.block.SignBlock("oak_sign"), new Block("oak_wall_sign"), net.minecraft.core.Direction.DOWN, new Item.Properties());
                        Item hanging = new StandingAndWallBlockItem(new net.minecraft.world.level.block.SignBlock("oak_hanging_sign"), new Block("oak_wall_hanging_sign"), net.minecraft.core.Direction.UP, new Item.Properties());
                        Item bed = new BlockItem(new net.minecraft.world.level.block.BedBlock("red_bed"), new Item.Properties());
                        Item stone = new BlockItem(new Block("stone"), new Item.Properties());
                        return "sign=" + (sign instanceof SignItem) + " hangingSign=" + (hanging instanceof SignItem)
                                + " bed=" + (bed instanceof BedItem) + " stone=" + (stone instanceof BedItem || stone instanceof SignItem);
                    }
                    static final class Bed extends BedItem { Bed(Block block, Item.Properties p) { super(block, p); } }
                }""");
        return s;
    }

    // 26.3: no SignItem or BedItem; the properties carry the sign text and the block's name.
    private static Map<String, String> newWorld() {
        return new HashMap<>(shared());
    }

    private static Map<String, String> shared() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/core/Direction.java", """
                package net.minecraft.core;
                public enum Direction { DOWN, UP }""");
        s.put("net/minecraft/world/level/block/Block.java", """
                package net.minecraft.world.level.block;
                public class Block { final String name; public Block(String name) { this.name = name; } public String toString() { return name; } }""");
        s.put("net/minecraft/world/item/Item.java", """
                package net.minecraft.world.item;
                public class Item {
                    protected final Properties properties;
                    public Item(Properties properties) { this.properties = properties; }
                    public static class Properties {
                        boolean signText, blockName;
                        public Properties signText() { signText = true; return this; }
                        public Properties useBlockDescriptionPrefix() { blockName = true; return this; }
                    }
                }""");
        s.put("net/minecraft/world/item/BlockItem.java", """
                package net.minecraft.world.item;
                import net.minecraft.world.level.block.Block;
                public class BlockItem extends Item {
                    final Block block;
                    public BlockItem(Block block, Item.Properties p) { super(p); this.block = block; }
                    public Block getBlock() { return block; }
                    public static boolean updateCustomBlockEntityTag(net.minecraft.world.level.Level l, net.minecraft.world.entity.player.Player p, net.minecraft.core.BlockPos pos, ItemStack s) { return false; }
                    public String toString() { return "block=" + block + " blockName=" + properties.blockName; }
                }""");
        s.put("net/minecraft/world/item/StandingAndWallBlockItem.java", """
                package net.minecraft.world.item;
                import net.minecraft.world.level.block.Block;
                public class StandingAndWallBlockItem extends BlockItem {
                    final Block wall; final net.minecraft.core.Direction attachment;
                    public StandingAndWallBlockItem(Block block, Block wall, net.minecraft.core.Direction attachment, Item.Properties p) { super(block, p); this.wall = wall; this.attachment = attachment; }
                    public String toString() { return "standing=" + block + " wall=" + wall + " down=" + (attachment == net.minecraft.core.Direction.DOWN) + " text=" + properties.signText + " blockName=" + properties.blockName; }
                }""");
        s.put("net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public final class ItemStack {}");
        s.put("net/minecraft/world/level/Level.java", "package net.minecraft.world.level; public class Level {}");
        s.put("net/minecraft/world/level/block/SignBlock.java", "package net.minecraft.world.level.block; public class SignBlock extends Block { public SignBlock(String name) { super(name); } }");
        s.put("net/minecraft/world/level/block/BedBlock.java", "package net.minecraft.world.level.block; public class BedBlock extends Block { public BedBlock(String name) { super(name); } }");
        s.put("net/minecraft/world/entity/player/Player.java", "package net.minecraft.world.entity.player; public class Player {}");
        s.put("net/minecraft/core/BlockPos.java", "package net.minecraft.core; public class BlockPos {}");
        s.put("net/minecraft/world/level/block/state/BlockState.java", "package net.minecraft.world.level.block.state; public class BlockState {}");
        return s;
    }
}
