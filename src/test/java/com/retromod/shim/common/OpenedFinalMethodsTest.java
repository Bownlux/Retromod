/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Mods drop final from host methods through the access files their loader applies, then override them. */
class OpenedFinalMethodsTest {

    private static final String DISPENSE = "net/minecraft/core/dispenser/DefaultDispenseItemBehavior.dispense";
    private static final String SELECTION_LIST_CT = "classTweaker v1 intermediary\n"
            + "extendable method net/minecraft/class_350 method_25396 ()Ljava/util/List;\n"
            + "extendable method net/minecraft/class_350 method_25308 (DD)Lnet/minecraft/class_350$class_351;\n";

    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
    }

    @Test
    @DisplayName("Forge reads its access transformer as Retromod translates it, and never a widener")
    void forgeAccessTransformer() {
        McReflect.setForceForge(true);
        // Farmer's Delight 1.20.1's own line. Its descriptor still names the 1.20.1 BlockSource,
        // so on 26.2 it opens nothing and the override has to be renamed.
        Map<String, String> jar = Map.of(OpenedFinalMethods.DEFAULT_AT,
                "public-f net.minecraft.core.dispenser.DefaultDispenseItemBehavior m_6115_(Lnet/minecraft/core/BlockSource;"
                        + "Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack; # dispense\n"
                        + "public-f net.minecraft.world.level.block.DispenserBlock f_52661_ # a field, not a method\n"
                        + "protected-f net.minecraft.world.entity.ai.sensing.Sensor *() # every method\n"
                        + "public-f net.minecraft.world.level.biome.OverworldBiomeBuilder * # every field\n",
                "kitchen.accesswidener", "accessWidener v2 named\n"
                        + "extendable method net/minecraft/client/resources/sounds/AbstractTickableSoundInstance stop ()V\n");

        Set<String> opened = read(jar, "26.2");

        assertEquals(Set.of(DISPENSE + "(Lnet/minecraft/core/BlockSource;Lnet/minecraft/world/item/ItemStack;)"
                + "Lnet/minecraft/world/item/ItemStack;", "net/minecraft/world/entity/ai/sensing/Sensor.*"), opened);
        assertTrue(OpenedFinalMethods.opens(opened, "net/minecraft/world/entity/ai/sensing/Sensor", "tick", "()V"));
    }

    @Test
    @DisplayName("NeoForge reads the access transformers neoforge.mods.toml names, as owo-lib's is named")
    void neoForgeDeclaredAccessTransformer() {
        McReflect.setForceNeoForge(true);
        Map<String, String> jar = Map.of("META-INF/neoforge.mods.toml",
                "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n"
                        + "[[accessTransformers]]\nfile=\"META-INF/owo.accesstransformer.cfg\"\n"
                        + "[[mods]]\nmodId=\"owo\"\n",
                "META-INF/owo.accesstransformer.cfg",
                "public-f net.minecraft.client.gui.components.AbstractSelectionList children()Ljava/util/List;\n",
                OpenedFinalMethods.DEFAULT_AT,
                "public-f net.minecraft.data.tags.TagsProvider getName()Ljava/lang/String;\n");

        assertEquals(Set.of("net/minecraft/client/gui/components/AbstractSelectionList.children()Ljava/util/List;"),
                read(jar, "26.2"), "a declared list replaces the default file");
    }

    @Test
    @DisplayName("Forge's accessTransformers = [...] string list names the files too")
    void forgeAccessTransformerList() {
        McReflect.setForceForge(true);
        Map<String, String> jar = Map.of("META-INF/mods.toml",
                "modLoader=\"javafml\"\naccessTransformers = [\"META-INF/a.cfg\", \"META-INF/b.cfg\"]\n[[mods]]\nmodId=\"x\"\n",
                "META-INF/a.cfg", "public-f net.minecraft.data.tags.TagsProvider getName()Ljava/lang/String;\n",
                "META-INF/b.cfg", "public-f net.minecraft.client.resources.sounds.AbstractTickableSoundInstance stop()V\n");

        assertEquals(Set.of("net/minecraft/data/tags/TagsProvider.getName()Ljava/lang/String;",
                "net/minecraft/client/resources/sounds/AbstractTickableSoundInstance.stop()V"), read(jar, "26.2"));
    }

    @Test
    @DisplayName("Quilt reads the widener quilt.mod.json names under access_widener")
    void quiltAccessWidener() {
        Map<String, String> jar = Map.of("quilt.mod.json",
                "{\"schema_version\":1,\"quilt_loader\":{\"id\":\"q\"},\"access_widener\":\"q.accesswidener\"}",
                "q.accesswidener", SELECTION_LIST_CT.replace("classTweaker v1", "accessWidener v2"));

        assertEquals(Set.of("net/minecraft/class_350.method_25396()Ljava/util/List;",
                "net/minecraft/class_350.method_25308(DD)Lnet/minecraft/class_350$class_351;"), read(jar, "1.21.11"));
    }

    @Test
    @DisplayName("Fabric reads the class tweaker fabric.mod.json declares whatever its extension, as YACL's .ct is")
    void fabricDeclaredClassTweaker() {
        Map<String, String> jar = Map.of("fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"yet_another_config_lib_v3\",\"classTweakers\":[\"yet_another_config_lib_v3.ct\"]}",
                "yet_another_config_lib_v3.ct", SELECTION_LIST_CT,
                "undeclared.accesswidener", "accessWidener v2 intermediary\n"
                        + "extendable method net/minecraft/class_1297 method_5722 (Lnet/minecraft/class_1297;)Z\n");

        // 1.21.11 runs intermediary names, so Fabric applies the file as shipped.
        assertEquals(Set.of("net/minecraft/class_350.method_25396()Ljava/util/List;",
                "net/minecraft/class_350.method_25308(DD)Lnet/minecraft/class_350$class_351;"), read(jar, "1.21.11"));
        // On 26.2 Retromod remaps every declared file, the .ct included, so it stays declared in official names.
        assertEquals(Set.of("net/minecraft/client/gui/components/AbstractSelectionList.children()Ljava/util/List;",
                "net/minecraft/client/gui/components/AbstractSelectionList.getEntryAtPosition(DD)"
                        + "Lnet/minecraft/client/gui/components/AbstractSelectionList$Entry;"), read(jar, "26.2"));
    }

    @Test
    @DisplayName("a file that cannot be read leaves the other access transformers counted")
    void oneBrokenFileLeavesTheRest() {
        McReflect.setForceNeoForge(true);
        Map<String, byte[]> jar = new HashMap<>();
        jar.put("META-INF/neoforge.mods.toml", ("[[accessTransformers]]\nfile=\"META-INF/broken.cfg\"\n"
                + "[[accessTransformers]]\nfile=\"META-INF/good.cfg\"\n").getBytes(StandardCharsets.UTF_8));
        jar.put("META-INF/good.cfg", "public-f net.minecraft.data.tags.TagsProvider getName()Ljava/lang/String;\n"
                .getBytes(StandardCharsets.UTF_8));

        Set<String> opened = OpenedFinalMethods.read(name -> {
            if (name.equals("META-INF/broken.cfg")) throw new IllegalStateException("corrupt entry");
            return jar.get(name);
        }, "26.2");

        assertEquals(Set.of("net/minecraft/data/tags/TagsProvider.getName()Ljava/lang/String;"), opened);
    }

    private static Set<String> read(Map<String, String> files, String target) {
        return OpenedFinalMethods.read(name -> {
            String content = files.get(name);
            return content == null ? null : content.getBytes(StandardCharsets.UTF_8);
        }, target);
    }
}
