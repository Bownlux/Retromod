/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.shim.forge.Forge_1_20_to_NeoForge_1_21;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.util.McReflect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The Forge -> NeoForge migration shim only registers its redirects on a live NeoForge runtime
 * ({@code McReflect.isNeoForge()}), so the migration could never be exercised offline (no NeoForge
 * on the CLI classpath). {@code McReflect.setForceNeoForge(true)} is the offline override the CLI's
 * {@code --target-loader neoforge} flag flips; this verifies that with it on, the shim actually
 * rewrites a Forge class reference to its NeoForge equivalent, and that it stays OFF by default.
 */
class OfflineNeoForgeTargetTest {

    @AfterEach
    void reset() {
        // never leak the global override to another test
        McReflect.setForceNeoForge(false);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    /** A tiny class that references net/minecraftforge/common/MinecraftForge in its bytecode. */
    private static byte[] forgeReferencingClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/TestMod", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "init", "()V", null, null);
        mv.visitCode();
        // read net/minecraftforge/common/MinecraftForge.EVENT_BUS (a real Forge reference)
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/common/MinecraftForge",
                "EVENT_BUS", "Lnet/minecraftforge/eventbus/api/IEventBus;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void forceNeoForgeMakesTheMigrationShimRewriteForgeReferences() {
        McReflect.setForceNeoForge(true);
        assertTrue(McReflect.isNeoForge(),
                "forceNeoForge should make isNeoForge() report true offline");

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        // The shim's registerRedirects returns early off NeoForge; with the override on it registers.
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);

        byte[] out = transformer.transformClass(forgeReferencingClass(),
                "com/example/TestMod.class");
        String text = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertTrue(text.contains("net/neoforged/neoforge/common/NeoForge"),
                "MinecraftForge should be rewritten to NeoForge");
        assertFalse(text.contains("net/minecraftforge/common/MinecraftForge"),
                "no net/minecraftforge/common/MinecraftForge reference should survive");
    }

    @Test
    void offByDefaultTheShimNoOpsAndForgeReferencesSurvive() {
        // default state: no override, not on a NeoForge runtime in the test JVM
        assertFalse(McReflect.isForceNeoForge());
        assertFalse(McReflect.isNeoForge(),
                "isNeoForge() should be false in the test JVM without the override");

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);

        byte[] out = transformer.transformClass(forgeReferencingClass(),
                "com/example/TestMod.class");
        String text = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertTrue(text.contains("net/minecraftforge/common/MinecraftForge"),
                "with the migration shim gated off, the Forge reference must be untouched");
        assertFalse(text.contains("net/neoforged/neoforge/common/NeoForge"),
                "nothing should have been rewritten to NeoForge");
    }

    /** A class carrying a class-level @Mod annotation (Lnet/minecraftforge/fml/common/Mod;). */
    private static byte[] modAnnotatedClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/AnnMod", null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        av.visit("value", "examplemod");
        av.visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void classLevelModAnnotationIsRemapped() {
        McReflect.setForceNeoForge(true);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);

        byte[] out = transformer.transformClass(modAnnotatedClass(), "com/example/AnnMod.class");
        String text = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertTrue(text.contains("net/neoforged/fml/common/Mod"),
                "class-level @Mod annotation type must be rewritten to NeoForge (else FML won't recognize the mod)");
        assertFalse(text.contains("net/minecraftforge/fml/common/Mod"),
                "no forge @Mod annotation should survive");
    }

    @Test
    void tomlContentPromotionRepointsForgeDepAndRelaxesLoaderVersion() {
        String forgeToml = "modLoader=\"javafml\"\n"
                + "loaderVersion=\"[47,)\"\n"
                + "license=\"MIT\"\n"
                + "[[dependencies.examplemod]]\n"
                + "modId=\"forge\"\n"
                + "mandatory=true\n"
                + "versionRange=\"[47,)\"\n";
        String promoted = ForgeModTransformer.promoteTomlContentForNeoForge(forgeToml);

        assertTrue(promoted.contains("modId=\"neoforge\""),
                "the mandatory forge loader dependency should be repointed at neoforge");
        assertFalse(promoted.contains("modId=\"forge\""),
                "no forge loader dependency id should survive");
        assertTrue(promoted.contains("loaderVersion=\"[1,)\""),
                "loaderVersion should be relaxed to [1,)");
    }

    /** A class that implements net/minecraftforge/common/extensions/IForgeItem (a mod's custom Item). */
    private static byte[] forgeItemImplementingClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/CustomItem", null,
                "java/lang/Object", new String[]{"net/minecraftforge/common/extensions/IForgeItem"});
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Regression for the Macaw's-on-NeoForge-26.2 crash surfaced in-game after the FMLJavaModLoadingContext
     * fix: a Forge mod's custom Item implements {@code net/minecraftforge/common/extensions/IForgeItem},
     * and on a NeoForge host BOTH the Forge->NeoForge migration ({@code Forge_1_20_to_NeoForge_1_21},
     * IForgeItem -> NeoForge {@code IItemExtension}) AND the Forge 1.21.11->26.1 rename
     * ({@code Forge_1_21_11_to_26_1}, IForgeItem -> forge-packaged {@code ForgeItem}) are ServiceLoader-
     * discovered and run. The redirect map is last-writer-wins, so the 26.1 forge rename could clobber the
     * migration and leave a {@code net/minecraftforge/common/extensions/ForgeItem} reference that exists on
     * NO NeoForge classpath -> NoClassDefFoundError at construct. The 26.1 forge-extension renames are now
     * gated to Forge hosts only, so on NeoForge the migration wins regardless of registration order.
     */
    @Test
    void forgeItemExtensionMapsToNeoForgeNotForgeRenameOnNeoForge() {
        McReflect.setForceNeoForge(true);
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        // Register in the order that previously LOST the race (26.1 forge rename registered last).
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(t);
        new Forge_1_21_11_to_26_1().registerRedirects(t);

        byte[] out = t.transformClass(forgeItemImplementingClass(), "com/example/CustomItem.class");
        String text = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertTrue(text.contains("net/neoforged/neoforge/common/extensions/IItemExtension"),
                "IForgeItem must map to NeoForge's IItemExtension on a NeoForge host");
        assertFalse(text.contains("net/minecraftforge/common/extensions/ForgeItem"),
                "the Forge-packaged ForgeItem rename must be gated off on NeoForge (it exists nowhere there)");
        assertFalse(text.contains("net/minecraftforge/common/extensions/IForgeItem"),
                "no original IForgeItem reference should survive");
    }

    /**
     * Regression for the third Macaw's-on-NeoForge domino: {@code ForgeCorePolyfill} runs AFTER the
     * migration shim and class-redirected {@code net/minecraftforge/common/MinecraftForge} to the
     * embedded {@code ForgeCapabilitiesShim}, which has NO static EVENT_BUS field. So a Forge mod's
     * {@code MinecraftForge.EVENT_BUS.register(this)} became {@code ForgeCapabilitiesShim.EVENT_BUS}
     * and died at construct with NoSuchFieldError. That polyfill redirect is now gated off on NeoForge,
     * where the migration's {@code MinecraftForge -> NeoForge} (a real IEventBus EVENT_BUS) stands.
     */
    @Test
    void minecraftForgeEventBusMapsToNeoForgeNotCapabilitiesShimOnNeoForge() {
        McReflect.setForceNeoForge(true);
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        // RetromodNeoForge order: migration shims first, then polyfills (which previously clobbered).
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(t);
        new com.retromod.polyfill.forge.ForgeCorePolyfill().registerPolyfills(t);

        byte[] out = t.transformClass(forgeReferencingClass(), "com/example/TestMod.class");
        String text = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertTrue(text.contains("net/neoforged/neoforge/common/NeoForge"),
                "MinecraftForge.EVENT_BUS must map to NeoForge, which has the real EVENT_BUS field");
        assertFalse(text.contains("ForgeCapabilitiesShim"),
                "the polyfill's MinecraftForge->ForgeCapabilitiesShim (no EVENT_BUS) must be gated off on NeoForge");
    }

    /** On a Forge host the 26.1 forge-extension rename still applies (drop the "I", stay forge-packaged). */
    @Test
    void forgeItemExtensionKeepsForgeRenameOnForgeHost() {
        // default: not NeoForge. Forge_1_20_to_NeoForge_1_21 no-ops off NeoForge; the 26.1 rename applies.
        assertFalse(McReflect.isNeoForge());
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(t);
        new Forge_1_21_11_to_26_1().registerRedirects(t);

        byte[] out = t.transformClass(forgeItemImplementingClass(), "com/example/CustomItem.class");
        String text = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertTrue(text.contains("net/minecraftforge/common/extensions/ForgeItem"),
                "on a Forge host IForgeItem must still drop the I to the forge-packaged ForgeItem");
        assertFalse(text.contains("net/neoforged/"),
                "nothing should be rewritten to NeoForge on a Forge host");
    }
}
