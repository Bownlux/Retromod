/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.minecraft.block.BlockPolyfill;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc.2 regressions for two removed/renamed classes that stopped a mod loading.
 *
 * <p>#145 (Isle of Berk, 1.18.2 Forge on a 1.20.1 host): {@code net/minecraft/world/level/block/OreBlock}
 * was renamed to {@code DropExperienceBlock} in 1.19, so a 1.18.x mod that extends it died
 * {@code NoClassDefFoundError} on a 1.19+ host. {@link BlockPolyfill} now redirects it.
 *
 * <p>#147 (VillagerViewer, Fabric for 1.21.9-1.21.10 on 26.2): the 1.21.11->26.1 Fabric shim
 * redirected the outer {@code ExtendedScreenHandlerType} to {@code ExtendedMenuType} but not the
 * nested {@code $ExtendedFactory}, so a mod referencing the nested class still crashed. The nested
 * redirect is now registered (target verified present in fabric-menu-api-v1 on 26.2).
 */
class Rc2RemovedClassRedirectTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    // #145: OreBlock -> DropExperienceBlock

    /** A block class that {@code extends OreBlock}, the shape Isle of Berk's ore blocks use. */
    private static byte[] extendsOreBlock() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/MyOre", null,
                "net/minecraft/world/level/block/OreBlock", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("#145: extends OreBlock -> extends DropExperienceBlock")
    void oreBlockRedirectsToDropExperienceBlock() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new BlockPolyfill().registerPolyfills(t);

        byte[] out = t.transformClass(extendsOreBlock(), "test/mod/MyOre.class");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);

        assertEquals("net/minecraft/world/level/block/DropExperienceBlock", cn.superName,
                "OreBlock must be redirected to DropExperienceBlock (removed in 1.19)");
    }

    // #147: nested ExtendedScreenHandlerType$ExtendedFactory

    private static final String OLD_OUTER = "net/fabricmc/fabric/api/screenhandler/v1/ExtendedScreenHandlerType";
    private static final String OLD_NESTED = OLD_OUTER + "$ExtendedFactory";
    private static final String NEW_OUTER = "net/fabricmc/fabric/api/menu/v1/ExtendedMenuType";
    private static final String NEW_NESTED = NEW_OUTER + "$ExtendedFactory";

    /** A class holding a field of the nested factory type (and the outer type), as a mod would. */
    private static byte[] referencesNestedFactory() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Menus", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "factory", "L" + OLD_NESTED + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "type", "L" + OLD_OUTER + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("#147: nested ExtendedScreenHandlerType$ExtendedFactory redirects too")
    void extendedScreenHandlerFactoryNestedRedirect() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new Fabric_1_21_11_to_26_1().registerRedirects(t);

        byte[] out = t.transformClass(referencesNestedFactory(), "test/mod/Menus.class");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);

        String factoryDesc = fieldDesc(cn, "factory");
        String typeDesc = fieldDesc(cn, "type");
        assertEquals("L" + NEW_NESTED + ";", factoryDesc,
                "the nested $ExtendedFactory must be redirected, not left dangling");
        assertEquals("L" + NEW_OUTER + ";", typeDesc, "the outer type redirect must still fire");
        assertFalse(factoryDesc.contains("screenhandler"), "no stale screenhandler reference should remain");
        assertTrue(NEW_NESTED.endsWith("$ExtendedFactory"), "sanity: target keeps the ExtendedFactory name");
    }

    private static String fieldDesc(ClassNode cn, String name) {
        for (FieldNode f : cn.fields) if (f.name.equals(name)) return f.desc;
        throw new AssertionError("field not found: " + name);
    }
}
