/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyColorClasses;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.forge.ModConstructorHooks;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
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

import static com.retromod.shim.neoforge.LegacyNeoForgeColorBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** On NeoForge 26.1 and newer, old block and item color events resolve to stand-ins inside the mod that Retromod posts. */
public class LegacyNeoForgeColorBridgeTest {

    static final String HANDLERS = "mod/NeoColorHandlers";
    static final String COLOR = LegacyColorClasses.ROOT;
    static final String BLOCK_ARRAY = "[Lnet/minecraft/world/level/block/Block;";
    static final String ITEM_LIKE_ARRAY = "[Lnet/minecraft/world/level/ItemLike;";
    static final String BLOCK_COLORS = LegacyColorClasses.BLOCK_COLORS;
    static final String MINECRAFT = LegacyColorClasses.MINECRAFT;
    static final String NEO_SUBSCRIBE = "Lnet/neoforged/bus/api/SubscribeEvent;";
    static final String EVENT_BUS = "net/neoforged/bus/api/IEventBus";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void neoForgeTarget() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("The NeoForge and Forge 26.1 shims rebuild both color events, the color classes and the install on NeoForge 26.x")
    void neoForgeHostsFrom261TakeTheBridge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new NeoForge_1_21_11_to_26_1().registerRedirects(transformer);
        assertRegistered("the NeoForge chain");

        transformer.clearRedirectsForTesting();
        new Forge_1_21_11_to_26_1().registerRedirects(transformer);
        assertRegistered("the chain a Forge 1.20.1 mod takes to NeoForge");
    }

    private void assertRegistered(String chain) {
        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals(BLOCK_EVENT, redirects.get(NEO_BLOCK), chain);
        assertEquals(ITEM_EVENT, redirects.get(NEO_ITEM), chain);
        assertEquals(BLOCK_EVENT, redirects.get(FORGE_BLOCK), chain);
        assertEquals(ITEM_EVENT, redirects.get(FORGE_ITEM), chain);
        assertEquals(COLOR + "BlockColor", redirects.get(LegacyColorClasses.OLD_BLOCK_COLOR), chain);
        assertEquals(COLOR + "ItemColors", redirects.get(LegacyColorClasses.OLD_ITEM_COLORS), chain);
        Set<String> synthetics = transformer.getSyntheticClasses().keySet();
        for (String rebuilt : List.of(BLOCK_EVENT, ITEM_EVENT, INSTALLER, INSTALLER.replace("LegacyNeoForgeColors", "LegacyColorEventPost"),
                LegacyColorClasses.CALLS, COLOR + "LegacyBlockTint", COLOR + "LegacyItemTint", COLOR + "ItemColors")) {
            assertTrue(synthetics.contains(rebuilt), rebuilt + " is registered for embedding by " + chain);
        }
    }

    @Test
    @DisplayName("NeoForge 1.21.x, which still has the block event, Forge, and a host without item tint sources keep the old names")
    void otherHostsAreRefused() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyNeoForgeColorBridge.register(transformer, host(ANCHOR, NEO_BLOCK, ITEM_TINT_SOURCES));
        assertNull(transformer.getClassRedirects().get(NEO_BLOCK), "a host that still posts the old block event");
        LegacyNeoForgeColorBridge.register(transformer, host(ANCHOR, BLOCK_TINT_SOURCES));
        assertNull(transformer.getClassRedirects().get(NEO_BLOCK), "a host with no item tint event to add the tint type on");

        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyNeoForgeColorBridge.register(transformer, host());
        assertNull(transformer.getClassRedirects().get(NEO_BLOCK), "offline, NeoForge 1.21.11");
        LegacyNeoForgeColorBridge.register(transformer, host(ANCHOR));
        assertNull(transformer.getClassRedirects().get(NEO_BLOCK), "a 1.21.11 host whose client events cannot be read");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        LegacyNeoForgeColorBridge.register(transformer, host());
        assertNull(transformer.getClassRedirects().get(NEO_BLOCK), "Forge keeps its own color bridge");

        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        LegacyNeoForgeColorBridge.register(transformer, host(ANCHOR, BLOCK_TINT_SOURCES, ITEM_TINT_SOURCES));
        assertEquals(BLOCK_EVENT, transformer.getClassRedirects().get(NEO_BLOCK), "a host with both tint source events");
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"21.1.252", "21.11.42", "26.1.2.60-beta", "26.2.0.0-beta", "26.3.0.43-beta"})
    @DisplayName("The real NeoForge jars: 1.21.1 and 1.21.11 keep the block event, 26.1.2, 26.2 and 26.3 replaced it")
    void realNeoForgeJars(String version) throws Exception {
        Path jar = neoForgeJar(version);
        Assumptions.assumeTrue(jar != null, "no NeoForge " + version + " jar");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Function<String, ClassNode> host = name -> {
                ZipEntry entry = zip.getEntry(name + ".class");
                if (entry == null) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return node(in.readAllBytes());
                } catch (IOException unreadable) {
                    return null;
                }
            };
            assertEquals(version.startsWith("26."), hostTakesBridge(host), version);
        }
    }

    @Test
    @DisplayName("Old listeners, an addListener method reference and late color calls name the copy rebuilt in the mod; 1.21 mods keep alpha")
    void oldCallsTakeTheStandIns() {
        register("1.21.1");
        ClassNode out = node(transformer.transformClass(neoHandlers(HANDLERS), HANDLERS + ".class"));

        MethodNode blocks = method(out, "onBlockColors");
        assertEquals("(L" + BLOCK_EVENT + ";)V", blocks.desc, "a @SubscribeEvent method takes the block stand-in");
        assertEquals(List.of("virtual " + BLOCK_EVENT + ".getBlockColors()L" + BLOCK_COLORS + ";",
                "virtual " + BLOCK_EVENT + ".register(L" + COLOR + "BlockColor;" + BLOCK_ARRAY + ")V"), calls(blocks));
        assertEquals("L" + COLOR + "BlockColor;", lambdaType(blocks), "a block color lambda implements the rebuilt interface");
        assertEquals(List.of(NEO_SUBSCRIBE), blocks.visibleAnnotations.stream().map(a -> a.desc).toList(),
                "NeoForge's own bus subscribes it, as before");

        MethodNode items = method(out, "onItemColors");
        assertEquals("(L" + ITEM_EVENT + ";)V", items.desc);
        assertEquals(List.of("virtual " + ITEM_EVENT + ".register(L" + COLOR + "ItemColor;" + ITEM_LIKE_ARRAY + ")V"), calls(items));

        // The bus resolves a method reference's event type from the method it names.
        InvokeDynamicInsnNode reference = indy(method(out, "subscribe"));
        Handle target = (Handle) reference.bsmArgs[1];
        assertEquals(HANDLERS + ".onItemColors(L" + ITEM_EVENT + ";)V", target.getOwner() + "." + target.getName() + target.getDesc());
        assertEquals("(L" + ITEM_EVENT + ";)V", reference.bsmArgs[2].toString());

        assertEquals(List.of("static " + MINECRAFT + ".getInstance()L" + MINECRAFT + ";",
                        "static " + COLOR + "ItemColors.legacyOf(L" + MINECRAFT + ";)L" + COLOR + "ItemColors;",
                        "virtual " + COLOR + "ItemColors.register(L" + COLOR + "ItemColor;" + ITEM_LIKE_ARRAY + ")V",
                        "static " + LegacyColorClasses.CALLS + ".register(L" + BLOCK_COLORS + ";L" + COLOR + "BlockColor;" + BLOCK_ARRAY + ")V",
                        "static " + LegacyColorClasses.CALLS + ".getColor(L" + BLOCK_COLORS + ";" + LegacyColorClasses.COLOR_ARGS + ")I"),
                calls(method(out, "lateSetup")));
    }

    @Test
    @DisplayName("A Forge 1.20.1 mod's color events on NeoForge reach the same stand-ins, with its item colors drawn opaque")
    void forgeNamesTakeTheStandIns() {
        register("1.20.1");
        String forge = "mod/ForgeColorHandlers";
        ClassNode out = node(transformer.transformClass(forgeHandlers(forge), forge + ".class"));
        assertEquals("(L" + BLOCK_EVENT + ";)V", method(out, "onBlockColors").desc);
        assertEquals("(L" + ITEM_EVENT + ";)V", method(out, "onItemColors").desc);
        assertEquals(List.of("virtual " + BLOCK_EVENT + ".getBlockColors()L" + BLOCK_COLORS + ";",
                "virtual " + BLOCK_EVENT + ".register(L" + COLOR + "BlockColor;" + BLOCK_ARRAY + ")V"), calls(method(out, "onBlockColors")));
        // A mod built before 1.21 had its item tints drawn opaque and got RGB from BlockColors.getColor.
        assertEquals(List.of("virtual " + ITEM_EVENT + ".registerOpaque(L" + COLOR + "ItemColor;" + ITEM_LIKE_ARRAY + ")V"),
                calls(method(out, "onItemColors")));
        assertEquals(List.of("static " + MINECRAFT + ".getInstance()L" + MINECRAFT + ";",
                        "static " + COLOR + "ItemColors.legacyOf(L" + MINECRAFT + ";)L" + COLOR + "ItemColors;",
                        "virtual " + COLOR + "ItemColors.registerOpaque(L" + COLOR + "ItemColor;" + ITEM_LIKE_ARRAY + ")V",
                        "static " + LegacyColorClasses.CALLS + ".register(L" + BLOCK_COLORS + ";L" + COLOR + "BlockColor;" + BLOCK_ARRAY + ")V",
                        "static " + LegacyColorClasses.CALLS + ".getColorRgb(L" + BLOCK_COLORS + ";" + LegacyColorClasses.COLOR_ARGS + ")I"),
                calls(method(out, "lateSetup")));
    }

    @Test
    @DisplayName("A mod built for NeoForge 26.1 or newer keeps the names the redirects are limited to")
    void modsBuiltFor261KeepTheNames() {
        register("26.1");
        ClassNode out = node(transformer.transformClass(neoHandlers(HANDLERS), HANDLERS + ".class"));
        assertEquals("(L" + NEO_BLOCK + ";)V", method(out, "onBlockColors").desc);
        assertEquals("(L" + NEO_ITEM + ";)V", method(out, "onItemColors").desc);
    }

    @Test
    @DisplayName("Each @Mod constructor of a jar naming an old color event or item colors installs them; block colors alone and 26.x tint events do not")
    void constructorsInstallTheColors() {
        register("1.21.1");
        byte[] mod = modClass("mod/ColorMod", "colormod");
        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, List.of(mod, neoHandlers(HANDLERS)));
        assertNotNull(hooks);
        List<String> installs = new ArrayList<>();
        for (MethodNode method : node(hooks.apply(mod)).methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("install")) installs.add(call.owner);
            }
        }
        assertEquals(List.of(INSTALLER), installs);

        assertNull(plan(Map.of("mod/Native", classNaming("mod/Native", BLOCK_TINT_SOURCES, ITEM_TINT_SOURCES)), Map.of()),
                "a 26.x mod's own tint events are not the old block event");
        assertNull(plan(Map.of("mod/Late", classNaming("mod/Late", LegacyColorClasses.OLD_BLOCK_COLOR, BLOCK_COLORS)), Map.of()),
                "a jar that only registers on BlockColors needs no post");
        assertNotNull(plan(Map.of("mod/Items", classNaming("mod/Items", LegacyColorClasses.OLD_ITEM_COLORS)), Map.of()),
                "Minecraft.getItemColors() callers need their tint type");
    }

    // ---- helpers ----

    private void register(String modVersion) {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyNeoForgeColorBridge.register(transformer, host());
        transformer.setModSourceVersion(modVersion);
    }

    static Function<String, ClassNode> host(String... classes) {
        Set<String> present = Set.of(classes);
        return name -> present.contains(name) ? new ClassNode() : null;
    }

    private static Path neoForgeJar(String version) {
        Path libraries = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
        Path prism = libraries.resolve("net/neoforged/neoforge/" + version + "/neoforge-" + version + "-universal.jar");
        if (Files.isRegularFile(prism)) return prism;
        Path server = Path.of(System.getProperty("user.home"), "Development/Minecraft/RetroMod/.retromod-work/nf1211/libraries",
                "net/neoforged/neoforge/" + version + "/neoforge-" + version + "-universal.jar");
        return Files.isRegularFile(server) ? server : null;
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    static List<String> calls(MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                found.add((call.getOpcode() == INVOKESTATIC ? "static " : "virtual ") + call.owner + "." + call.name + call.desc);
            }
        }
        return found;
    }

    private static InvokeDynamicInsnNode indy(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) return indy;
        }
        throw new AssertionError("no lambda in " + method.name);
    }

    private static String lambdaType(MethodNode method) {
        return Type.getReturnType(indy(method).desc).getDescriptor();
    }

    // A class whose constant pool names each of the given classes.
    private static byte[] classNaming(String name, String... named) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "names", "()V", null, null);
        mv.visitCode();
        for (String type : named) {
            mv.visitLdcInsn(Type.getObjectType(type));
            mv.visitInsn(POP);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final Handle METAFACTORY = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);

    /**
     * NeoForge 1.21.1 color handlers, as old mods write them: @SubscribeEvent block and item handlers that register lambdas,
     * a modBus.addListener(Handlers::onItemColors) method reference, and late calls on Minecraft's item colors and BlockColors.
     */
    public static byte[] neoHandlers(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        String blockColor = LegacyColorClasses.OLD_BLOCK_COLOR;
        String itemColor = LegacyColorClasses.OLD_ITEM_COLOR;

        MethodVisitor block = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onBlockColors", "(L" + NEO_BLOCK + ";)V", null, null);
        block.visitAnnotation(NEO_SUBSCRIBE, true).visitEnd();
        block.visitCode();
        block.visitVarInsn(ALOAD, 0);
        block.visitMethodInsn(INVOKEVIRTUAL, NEO_BLOCK, "getBlockColors", "()L" + BLOCK_COLORS + ";", false);
        block.visitInsn(POP);
        block.visitVarInsn(ALOAD, 0);
        String blockSam = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;"
                + "Lnet/minecraft/core/BlockPos;I)I";
        block.visitInvokeDynamicInsn("getColor", "()L" + blockColor + ";", METAFACTORY, Type.getType(blockSam),
                new Handle(H_INVOKESTATIC, name, "leaves", blockSam, false), Type.getType(blockSam));
        block.visitInsn(ICONST_0);
        block.visitTypeInsn(ANEWARRAY, "net/minecraft/world/level/block/Block");
        block.visitMethodInsn(INVOKEVIRTUAL, NEO_BLOCK, "register", "(L" + blockColor + ";" + BLOCK_ARRAY + ")V", false);
        block.visitInsn(RETURN);
        block.visitMaxs(0, 0);
        block.visitEnd();
        constant(cw, "leaves", blockSam, 0x48B518);

        MethodVisitor items = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onItemColors", "(L" + NEO_ITEM + ";)V", null, null);
        items.visitAnnotation(NEO_SUBSCRIBE, true).visitEnd();
        items.visitCode();
        items.visitVarInsn(ALOAD, 0);
        String itemSam = "(Lnet/minecraft/world/item/ItemStack;I)I";
        items.visitInvokeDynamicInsn("getColor", "()L" + itemColor + ";", METAFACTORY, Type.getType(itemSam),
                new Handle(H_INVOKESTATIC, name, "white", itemSam, false), Type.getType(itemSam));
        items.visitInsn(ICONST_0);
        items.visitTypeInsn(ANEWARRAY, "net/minecraft/world/level/ItemLike");
        items.visitMethodInsn(INVOKEVIRTUAL, NEO_ITEM, "register", "(L" + itemColor + ";" + ITEM_LIKE_ARRAY + ")V", false);
        items.visitInsn(RETURN);
        items.visitMaxs(0, 0);
        items.visitEnd();
        constant(cw, "white", itemSam, -1);

        MethodVisitor subscribe = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "subscribe", "(L" + EVENT_BUS + ";)V", null, null);
        subscribe.visitCode();
        subscribe.visitVarInsn(ALOAD, 0);
        subscribe.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;", METAFACTORY,
                Type.getType("(Ljava/lang/Object;)V"), new Handle(H_INVOKESTATIC, name, "onItemColors", "(L" + NEO_ITEM + ";)V", false),
                Type.getType("(L" + NEO_ITEM + ";)V"));
        subscribe.visitMethodInsn(INVOKEINTERFACE, EVENT_BUS, "addListener", "(Ljava/util/function/Consumer;)V", true);
        subscribe.visitInsn(RETURN);
        subscribe.visitMaxs(0, 0);
        subscribe.visitEnd();

        MethodVisitor late = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "lateSetup", "(L" + BLOCK_COLORS + ";)I", null, null);
        late.visitCode();
        late.visitMethodInsn(INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false);
        late.visitMethodInsn(INVOKEVIRTUAL, MINECRAFT, "getItemColors", "()L" + LegacyColorClasses.OLD_ITEM_COLORS + ";", false);
        late.visitInsn(ACONST_NULL);
        late.visitInsn(ICONST_0);
        late.visitTypeInsn(ANEWARRAY, "net/minecraft/world/level/ItemLike");
        late.visitMethodInsn(INVOKEVIRTUAL, LegacyColorClasses.OLD_ITEM_COLORS, "register",
                "(L" + itemColor + ";" + ITEM_LIKE_ARRAY + ")V", false);
        late.visitVarInsn(ALOAD, 0);
        late.visitInsn(ACONST_NULL);
        late.visitInsn(ICONST_0);
        late.visitTypeInsn(ANEWARRAY, "net/minecraft/world/level/block/Block");
        late.visitMethodInsn(INVOKEVIRTUAL, BLOCK_COLORS, "register", "(L" + blockColor + ";" + BLOCK_ARRAY + ")V", false);
        late.visitVarInsn(ALOAD, 0);
        late.visitInsn(ACONST_NULL);
        late.visitInsn(ACONST_NULL);
        late.visitInsn(ACONST_NULL);
        late.visitInsn(ICONST_0);
        late.visitMethodInsn(INVOKEVIRTUAL, BLOCK_COLORS, "getColor", "(" + LegacyColorClasses.COLOR_ARGS + ")I", false);
        late.visitInsn(IRETURN);
        late.visitMaxs(0, 0);
        late.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The same handlers under Forge 1.20.1's names, as a Forge mod brings them to NeoForge. */
    public static byte[] forgeHandlers(String name) {
        Map<String, String> forge = Map.of(NEO_BLOCK, FORGE_BLOCK, NEO_ITEM, FORGE_ITEM,
                NEO_SUBSCRIBE.substring(1, NEO_SUBSCRIBE.length() - 1), "net/minecraftforge/eventbus/api/SubscribeEvent",
                EVENT_BUS, "net/minecraftforge/eventbus/api/IEventBus", HANDLERS, name);
        ClassWriter out = new ClassWriter(0);
        new ClassReader(neoHandlers(HANDLERS)).accept(new ClassRemapper(out, new SimpleRemapper(forge)), 0);
        return out.toByteArray();
    }

    /** A NeoForge {@code @Mod} class with an empty constructor. */
    public static byte[] modClass(String name, String modId) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        var mod = cw.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
        mod.visit("value", modId);
        mod.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constant(ClassWriter cw, String name, String desc, int color) {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitLdcInsn(color);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
