/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyColorClasses;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old block and item color calls point at the colors rebuilt inside the mod, on Forge hosts that dropped the item color event only. */
class LegacyForgeColorBridgeTest {

    static final String OLD_BLOCK_COLOR = LegacyForgeColorBridge.OLD_BLOCK_COLOR;
    static final String OLD_ITEM_COLOR = LegacyForgeColorBridge.OLD_ITEM_COLOR;
    static final String OLD_ITEM_COLORS = LegacyForgeColorBridge.OLD_ITEM_COLORS;
    static final String OLD_ITEM_EVENT = LegacyForgeColorBridge.OLD_ITEM_EVENT;
    static final String BLOCK_EVENT = LegacyForgeColorBridge.BLOCK_EVENT;
    static final String BLOCK_COLORS = LegacyForgeColorBridge.BLOCK_COLORS;
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String ROOT = RebuiltApiClasses.ROOT + "forge/client/color/";
    static final String HELPERS = ROOT + "LegacyBlockColors";
    // The color classes and BlockColors helpers both Forge-family loaders share.
    static final String COLOR = LegacyColorClasses.ROOT;
    static final String CALLS = LegacyColorClasses.CALLS;
    static final String ITEM_EVENT = RebuiltApiClasses.ROOT + "forge/client/event/LegacyItemColorsEvent";
    static final String BLOCK_ARRAY = "[Lnet/minecraft/world/level/block/Block;";
    static final String ITEM_LIKE_ARRAY = "[Lnet/minecraft/world/level/ItemLike;";
    static final String COLOR_ARGS = "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;I";
    static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    static final String LEGACY_SUBSCRIBE = "L" + RebuiltApiClasses.ROOT + "forge/eventbus/LegacySubscribe;";
    static final String HANDLERS = "mod/ColorHandlers";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge 26.2 shim rebuilds the color classes and their install, which carries on to Forge 26.3")
    void forgeHostsFrom262TakeTheBridge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals(COLOR + "BlockColor", redirects.get(OLD_BLOCK_COLOR));
        assertEquals(COLOR + "ItemColor", redirects.get(OLD_ITEM_COLOR));
        assertEquals(COLOR + "ItemColors", redirects.get(OLD_ITEM_COLORS));
        assertEquals(ITEM_EVENT, redirects.get(OLD_ITEM_EVENT));
        assertNull(redirects.get(BLOCK_EVENT), "the host keeps its block color event");
        Set<String> synthetics = transformer.getSyntheticClasses().keySet();
        for (String rebuilt : List.of(HELPERS, CALLS, ITEM_EVENT, LegacyForgeColorBridge.INSTALLER, COLOR + "LegacyBlockTint",
                COLOR + "LegacyItemTint", ROOT + "LegacyItemColorPost", COLOR + "ItemColors")) {
            assertTrue(synthetics.contains(rebuilt), rebuilt + " is registered for embedding");
        }
    }

    @Test
    @DisplayName("NeoForge, other loaders, Forge 1.21.11 and 26.1, and a Forge host that still has the item event keep the old names")
    void otherHostsAreRefused() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        LegacyForgeColorBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_ITEM_EVENT), "NeoForge keeps its own color events");

        McReflect.setForceNeoForge(false);
        LegacyForgeColorBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_ITEM_EVENT), "a Fabric or Quilt run takes no Forge bridge");

        McReflect.setForceForge(true);
        for (String older : List.of("1.21.11", "26.1")) {
            RetromodVersion.TARGET_MC_VERSION = older;
            LegacyForgeColorBridge.register(transformer);
            assertNull(transformer.getClassRedirects().get(OLD_ITEM_EVENT), "Forge " + older + " is not a host the bridge was checked on");
        }

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyForgeColorBridge.register(transformer, LegacyForgeRenderBridgeTest.host(ForgeHostShape.ANCHOR, BLOCK_EVENT, OLD_ITEM_EVENT));
        assertNull(transformer.getClassRedirects().get(OLD_ITEM_EVENT), "a host that still posts the item event keeps it");
        LegacyForgeColorBridge.register(transformer, LegacyForgeRenderBridgeTest.host(ForgeHostShape.ANCHOR));
        assertNull(transformer.getClassRedirects().get(OLD_BLOCK_COLOR), "a host with no block color event has nothing to register on");
        LegacyForgeColorBridge.register(transformer, LegacyForgeRenderBridgeTest.host(ForgeHostShape.ANCHOR, BLOCK_EVENT));
        assertEquals(ITEM_EVENT, transformer.getClassRedirects().get(OLD_ITEM_EVENT), "a host with only the block color event");
    }

    @Test
    @DisplayName("Old block color registrations and lookups go to the embedded helpers, and item colors to the rebuilt table, drawn opaque")
    void oldCallsTakeTheRebuiltColors() {
        register("1.20.1");
        ClassNode out = LegacyForgeRenderBridgeTest.node(transformer.transformClass(handlers(HANDLERS), HANDLERS + ".class"));

        assertEquals(List.of("static " + HELPERS + ".register(L" + BLOCK_EVENT + ";L" + COLOR + "BlockColor;" + BLOCK_ARRAY + ")V"),
                calls(method(out, "onBlockColors")));
        // Forge 1.20.1's item renderer drew every tint opaque, and its BlockColors.getColor answered RGB.
        assertEquals(List.of("static " + MINECRAFT + ".getInstance()L" + MINECRAFT + ";",
                        "static " + COLOR + "ItemColors.legacyOf(L" + MINECRAFT + ";)L" + COLOR + "ItemColors;",
                        "virtual " + COLOR + "ItemColors.registerOpaque(L" + COLOR + "ItemColor;" + ITEM_LIKE_ARRAY + ")V",
                        "static " + CALLS + ".register(L" + BLOCK_COLORS + ";L" + COLOR + "BlockColor;" + BLOCK_ARRAY + ")V",
                        "static " + CALLS + ".getColorRgb(L" + BLOCK_COLORS + ";" + COLOR_ARGS + ")I"),
                calls(method(out, "lateSetup")));

        MethodNode items = method(out, "onItemColors");
        assertEquals("(L" + ITEM_EVENT + ";)V", items.desc);
        assertEquals(List.of(LEGACY_SUBSCRIBE), items.visibleAnnotations.stream().map(a -> a.desc).toList(),
                "the item event keeps Forge 1.20.1's priority and order rules in Retromod's dispatch");
        assertEquals(List.of("virtual " + ITEM_EVENT + ".getBlockColors()L" + BLOCK_COLORS + ";",
                "virtual " + ITEM_EVENT + ".registerOpaque(L" + COLOR + "ItemColor;" + ITEM_LIKE_ARRAY + ")V"), calls(items));
        assertEquals("L" + COLOR + "ItemColor;", lambdaType(items), "an item color lambda implements the rebuilt interface");
    }

    @Test
    @DisplayName("A Forge 1.21.1 mod's item colors keep the alpha they answer, and its BlockColors.getColor answers it too")
    void forge1211ModsKeepTheirAlpha() {
        register("1.21.1");
        ClassNode out = LegacyForgeRenderBridgeTest.node(transformer.transformClass(handlers(HANDLERS), HANDLERS + ".class"));
        // Minecraft 1.21's item renderer read the alpha an item color answered, as 26.x does.
        assertEquals(List.of("static " + MINECRAFT + ".getInstance()L" + MINECRAFT + ";",
                        "static " + COLOR + "ItemColors.legacyOf(L" + MINECRAFT + ";)L" + COLOR + "ItemColors;",
                        "virtual " + COLOR + "ItemColors.register(L" + COLOR + "ItemColor;" + ITEM_LIKE_ARRAY + ")V",
                        "static " + CALLS + ".register(L" + BLOCK_COLORS + ";L" + COLOR + "BlockColor;" + BLOCK_ARRAY + ")V",
                        "static " + CALLS + ".getColor(L" + BLOCK_COLORS + ";" + COLOR_ARGS + ")I"),
                calls(method(out, "lateSetup")));
        assertEquals(List.of("virtual " + ITEM_EVENT + ".getBlockColors()L" + BLOCK_COLORS + ";",
                "virtual " + ITEM_EVENT + ".register(L" + COLOR + "ItemColor;" + ITEM_LIKE_ARRAY + ")V"), calls(method(out, "onItemColors")));
    }

    @Test
    @DisplayName("A mod built for Forge 26.2 or newer keeps the names and calls the redirects are limited to")
    void modsBuiltFor262KeepTheNames() {
        register("26.2");
        ClassNode out = LegacyForgeRenderBridgeTest.node(transformer.transformClass(handlers(HANDLERS), HANDLERS + ".class"));
        assertEquals(List.of("virtual " + BLOCK_EVENT + ".register(L" + OLD_BLOCK_COLOR + ";" + BLOCK_ARRAY + ")V"),
                calls(method(out, "onBlockColors")));
        assertEquals("(L" + OLD_ITEM_EVENT + ";)V", method(out, "onItemColors").desc);
    }

    @Test
    @DisplayName("Each @Mod constructor of a jar that names item colors installs them; block colors alone need no install")
    void constructorsInstallItemColors() {
        register("1.20.1");
        byte[] mod = ModConstructorHooksTest.modClass("mod/ColorMod", "colormod", LegacyAutoSubscribeBridgeTest.OBJECT,
                (writer, owner) -> { });
        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, List.of(mod, handlers(HANDLERS)));
        assertNotNull(hooks);
        List<String> installs = new ArrayList<>();
        for (AbstractInsnNode insn : ModConstructorHooksTest.constructor(ModConstructorHooksTest.node(hooks.apply(mod)), "()V").instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("install")) installs.add(call.owner);
        }
        assertEquals(Set.of(LegacyClientRenderBootstrapInjector.BOOTSTRAP, LegacyForgeColorBridge.INSTALLER), Set.copyOf(installs));

        assertNull(LegacyClientRenderBootstrapInjector.plan(List.of(new LegacyClientRenderBootstrapInjector.Part("item-colors",
                List.of(OLD_ITEM_EVENT, OLD_ITEM_COLOR), LegacyForgeColorBridge.INSTALLER)),
                Map.of(HANDLERS, blockOnly())), "a jar with block colors only adds no constructor call");
    }

    @Test
    @DisplayName("A host whose ItemTintSources keeps no id mapper Retromod can add to gets the color redirects but no item install")
    void hostWithoutTintIdsGetsNoInstall() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer.setModSourceVersion("1.20.1");
        String sources = LegacyForgeColorBridge.ITEM_TINT_SOURCES;
        LegacyForgeColorBridge.register(transformer, LegacyForgeRenderBridgeTest.host(ForgeHostShape.ANCHOR, BLOCK_EVENT, sources));
        assertEquals(ITEM_EVENT, transformer.getClassRedirects().get(OLD_ITEM_EVENT), "the redirects stay");
        byte[] mod = ModConstructorHooksTest.modClass("mod/ColorMod", "colormod", LegacyAutoSubscribeBridgeTest.OBJECT,
                (writer, owner) -> { });
        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, List.of(mod, handlers(HANDLERS)));
        assertNull(hooks, "no install, so Retromod's item definitions name no tint type nobody registers");

        assertFalse(LegacyForgeColorBridge.itemTintTypesReachable(LegacyForgeRenderBridgeTest.host(sources,
                LegacyForgeColorBridge.ID_MAPPER)), "a mapper class alone, with no field holding it");
        assertTrue(LegacyForgeColorBridge.itemTintTypesReachable(LegacyForgeRenderBridgeTest.host()),
                "a host whose client classes cannot be read keeps the version rule");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar", "minecraft/26.3/minecraft-26.3-client.jar"})
    @DisplayName("Minecraft 26.2 and 26.3 keep exactly one static tint id mapper with put(Object, Object), as the install reads it")
    void realClientsKeepTheTintIdMapper(String jar) throws Exception {
        Path libraries = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
        Path client = libraries.resolve(jar.startsWith("forge") ? "net/minecraftforge/" + jar : "com/mojang/" + jar);
        Assumptions.assumeTrue(Files.isRegularFile(client), "no client jar at " + client);
        try (ZipFile zip = new ZipFile(client.toFile())) {
            Function<String, ClassNode> host = name -> {
                ZipEntry entry = zip.getEntry(name + ".class");
                if (entry == null) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return LegacyForgeRenderBridgeTest.node(in.readAllBytes());
                } catch (IOException unreadable) {
                    return null;
                }
            };
            ClassNode sources = host.apply(LegacyForgeColorBridge.ITEM_TINT_SOURCES);
            assertNotNull(sources);
            assertEquals(1, sources.fields.stream().filter(field -> (field.access & ACC_STATIC) != 0
                    && field.desc.equals("L" + LegacyForgeColorBridge.ID_MAPPER + ";")).count());
            assertTrue(LegacyForgeColorBridge.itemTintTypesReachable(host));
        }
    }

    // ---- helpers ----

    private void register(String modVersion) {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.setModSourceVersion(modVersion);
    }

    private static MethodNode method(ClassNode node, String name) {
        return LegacyForgeRenderBridgeTest.method(node, name);
    }

    private static List<String> calls(MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                found.add((call.getOpcode() == INVOKESTATIC ? "static " : "virtual ") + call.owner + "." + call.name + call.desc);
            }
        }
        return found;
    }

    private static String lambdaType(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) return Type.getReturnType(indy.desc).getDescriptor();
        }
        throw new AssertionError("no lambda in " + method.name);
    }

    /**
     * Forge 1.20.1 color handlers, as old mods write them: a block handler on the event, an item handler that reads
     * the block colors and registers a lambda, and late calls on Minecraft's item colors and the block colors.
     */
    static byte[] handlers(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);

        MethodVisitor block = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onBlockColors", "(L" + BLOCK_EVENT + ";)V", null, null);
        block.visitCode();
        block.visitVarInsn(ALOAD, 0);
        block.visitInsn(ACONST_NULL);
        block.visitInsn(ICONST_0);
        block.visitTypeInsn(ANEWARRAY, "net/minecraft/world/level/block/Block");
        block.visitMethodInsn(INVOKEVIRTUAL, BLOCK_EVENT, "register", "(L" + OLD_BLOCK_COLOR + ";" + BLOCK_ARRAY + ")V", false);
        block.visitInsn(RETURN);
        block.visitMaxs(0, 0);
        block.visitEnd();

        MethodVisitor items = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onItemColors", "(L" + OLD_ITEM_EVENT + ";)V", null, null);
        items.visitAnnotation(OLD_SUBSCRIBE, true).visitEnd();
        items.visitCode();
        items.visitVarInsn(ALOAD, 0);
        items.visitMethodInsn(INVOKEVIRTUAL, OLD_ITEM_EVENT, "getBlockColors", "()L" + BLOCK_COLORS + ";", false);
        items.visitInsn(POP);
        items.visitVarInsn(ALOAD, 0);
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        String sam = "(Lnet/minecraft/world/item/ItemStack;I)I";
        items.visitInvokeDynamicInsn("getColor", "()L" + OLD_ITEM_COLOR + ";", metafactory, Type.getType(sam),
                new Handle(H_INVOKESTATIC, name, "white", sam, false), Type.getType(sam));
        items.visitInsn(ICONST_0);
        items.visitTypeInsn(ANEWARRAY, "net/minecraft/world/level/ItemLike");
        items.visitMethodInsn(INVOKEVIRTUAL, OLD_ITEM_EVENT, "register", "(L" + OLD_ITEM_COLOR + ";" + ITEM_LIKE_ARRAY + ")V", false);
        items.visitInsn(RETURN);
        items.visitMaxs(0, 0);
        items.visitEnd();

        MethodVisitor white = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "white", sam, null, null);
        white.visitCode();
        white.visitInsn(ICONST_M1);
        white.visitInsn(IRETURN);
        white.visitMaxs(0, 0);
        white.visitEnd();

        MethodVisitor late = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "lateSetup", "(L" + BLOCK_COLORS + ";)I", null, null);
        late.visitCode();
        late.visitMethodInsn(INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false);
        late.visitMethodInsn(INVOKEVIRTUAL, MINECRAFT, "getItemColors", "()L" + OLD_ITEM_COLORS + ";", false);
        late.visitInsn(ACONST_NULL);
        late.visitInsn(ICONST_0);
        late.visitTypeInsn(ANEWARRAY, "net/minecraft/world/level/ItemLike");
        late.visitMethodInsn(INVOKEVIRTUAL, OLD_ITEM_COLORS, "register", "(L" + OLD_ITEM_COLOR + ";" + ITEM_LIKE_ARRAY + ")V", false);
        late.visitVarInsn(ALOAD, 0);
        late.visitInsn(ACONST_NULL);
        late.visitInsn(ICONST_0);
        late.visitTypeInsn(ANEWARRAY, "net/minecraft/world/level/block/Block");
        late.visitMethodInsn(INVOKEVIRTUAL, BLOCK_COLORS, "register", "(L" + OLD_BLOCK_COLOR + ";" + BLOCK_ARRAY + ")V", false);
        late.visitVarInsn(ALOAD, 0);
        late.visitInsn(ACONST_NULL);
        late.visitInsn(ACONST_NULL);
        late.visitInsn(ACONST_NULL);
        late.visitInsn(ICONST_0);
        late.visitMethodInsn(INVOKEVIRTUAL, BLOCK_COLORS, "getColor", "(" + COLOR_ARGS + ")I", false);
        late.visitInsn(IRETURN);
        late.visitMaxs(0, 0);
        late.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that only registers a block color. */
    private static byte[] blockOnly() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/BlockOnly", null, "java/lang/Object", null);
        MethodVisitor block = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onBlockColors", "(L" + BLOCK_EVENT + ";)V", null, null);
        block.visitCode();
        block.visitVarInsn(ALOAD, 0);
        block.visitInsn(ACONST_NULL);
        block.visitInsn(ICONST_0);
        block.visitTypeInsn(ANEWARRAY, "net/minecraft/world/level/block/Block");
        block.visitMethodInsn(INVOKEVIRTUAL, BLOCK_EVENT, "register", "(L" + OLD_BLOCK_COLOR + ";" + BLOCK_ARRAY + ")V", false);
        block.visitInsn(RETURN);
        block.visitMaxs(0, 0);
        block.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
