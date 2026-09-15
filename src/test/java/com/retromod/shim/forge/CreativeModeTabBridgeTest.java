/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.jupiter.api.Assumptions;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #264 (Pyrologer And Friends): a 1.19.2 mod whose creative tab dies on its own {@code super(...)}.
 *
 * <p>Up to 1.19.2 a tab was made by subclassing: {@code new CreativeModeTab("name") { ItemStack
 * makeIcon() {...} }}. 1.19.3 replaced that with a builder and deleted the {@code String}
 * constructor. The subclass still loads, so nothing complains until it runs, and then it throws
 * {@code NoSuchMethodError: CreativeModeTab.<init>(String)} during mod construction and Forge
 * refuses every later event for the broken mod. Every MCreator mod of that era generates this shape.
 */
class CreativeModeTabBridgeTest {

    private static final String TAB = "net/minecraft/world/item/CreativeModeTab";
    private static final String GENERATED = "com/retromod/generated/LegacyCreativeModeTab";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String MOD_TAB = "test/tabs/ModTabs$1";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        CreativeModeTabBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    /** The anonymous subclass an MCreator 1.19.2 mod emits. */
    private static byte[] modTab() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_SUPER, MOD_TAB, null, TAB, null);
        MethodVisitor ctor = cw.visitMethod(0, "<init>", "(Ljava/lang/String;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, TAB, "<init>", "(Ljava/lang/String;)V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // The override the mod supplies for the tab's icon.
        MethodVisitor icon = cw.visitMethod(Opcodes.ACC_PUBLIC, "makeIcon", "()L" + STACK + ";",
                null, null);
        icon.visitCode();
        icon.visitInsn(Opcodes.ACONST_NULL);
        icon.visitInsn(Opcodes.ARETURN);
        icon.visitMaxs(0, 0);
        icon.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassNode transformed() {
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(modTab(), MOD_TAB)).accept(cn, 0);
        return cn;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    @Test
    @DisplayName("the tab subclass and its super call both move to the bridge")
    void rebasesTheTab() {
        ClassNode cn = transformed();
        assertEquals(GENERATED, cn.superName,
                "the mod must extend the bridge, which still has a String constructor");

        MethodNode ctor = cn.methods.stream()
                .filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        MethodInsnNode superCall = null;
        for (AbstractInsnNode insn : ctor.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("<init>")) {
                superCall = call;
                break;
            }
        }
        assertNotNull(superCall, "the constructor lost its super() call");
        assertEquals(GENERATED, superCall.owner);
        assertEquals("(Ljava/lang/String;)V", superCall.desc,
                "the descriptor is unchanged; the bridge is what supplies that shape now");
    }

    @Test
    @DisplayName("the bridge builds its tab through the builder and titles it")
    void bridgeUsesTheBuilder() {
        ClassNode bridge = read(transformer.getSyntheticClasses().get(GENERATED));
        assertEquals(TAB, bridge.superName);

        MethodNode ctor = bridge.methods.stream()
                .filter(m -> m.name.equals("<init>") && m.desc.equals("(Ljava/lang/String;)V"))
                .findFirst().orElseThrow();

        boolean builds = false, titles = false, callsSuper = false;
        for (AbstractInsnNode insn : ctor.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.name.equals("builder") && call.owner.equals(TAB)) builds = true;
            if (call.name.equals("title")) titles = true;
            if (call.name.equals("<init>") && call.owner.equals(TAB)) {
                callsSuper = true;
                assertTrue(call.desc.endsWith("Builder;)V"),
                        "the Builder constructor is the only one a subclass outside the package can "
                        + "reach; vanilla's is package-private and this one is a Forge patch");
            }
        }
        assertTrue(builds, "the tab has to come from CreativeModeTab.builder()");
        assertTrue(titles, "the name the mod passed has to become the tab's title");
        assertTrue(callsSuper, "the bridge must actually initialise its superclass");
    }

    @Test
    @DisplayName("the mod's icon override still answers, through the current hook")
    void iconOverrideStaysLive() {
        ClassNode bridge = read(transformer.getSyntheticClasses().get(GENERATED));

        assertTrue(bridge.methods.stream().anyMatch(m -> m.name.equals("makeIcon")),
                "the bridge must declare the old hook, or the mod's override overrides nothing");

        MethodNode current = bridge.methods.stream()
                .filter(m -> m.name.equals("getIconItem")).findFirst()
                .orElseGet(() -> fail("the bridge must answer the current icon hook"));
        boolean delegates = false;
        for (AbstractInsnNode insn : current.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("makeIcon")) {
                delegates = true;
                assertEquals(Opcodes.INVOKEVIRTUAL, call.getOpcode(),
                        "the call has to be virtual so the mod's override wins");
            }
        }
        assertTrue(delegates, "getIconItem must route to makeIcon or every bridged tab shows empty");
    }

    @Test
    @DisplayName("the bridge and a mod tab both link against a real Forge jar")
    void linksAgainstForge() throws Exception {
        Path forge = forgeJar();
        Assumptions.assumeTrue(forge != null,
                "needs a Forge 1.20.1 jar with Mojang names; the Builder constructor this relies on "
                + "is a Forge patch, so vanilla cannot answer the question");

        Map<String, byte[]> defined = new HashMap<>();
        defined.put("com.retromod.generated.LegacyCreativeModeTab",
                transformer.getSyntheticClasses().get(GENERATED));
        defined.put("test.tabs.ModTabs$1",
                transformer.transformClass(modTab(), MOD_TAB));

        List<URL> classpath = new ArrayList<>();
        classpath.add(forge.toUri().toURL());
        classpath.addAll(minecraftLibraries());

        try (URLClassLoader forgeLoader = new URLClassLoader(
                classpath.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            ClassLoader loader = new ClassLoader(forgeLoader) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = defined.get(name);
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };

            Class<?> bridge = Class.forName(
                    "com.retromod.generated.LegacyCreativeModeTab", false, loader);
            assertEquals("net.minecraft.world.item.CreativeModeTab",
                    bridge.getSuperclass().getName(),
                    "the bridge has to be a real creative tab, not a lookalike");

            Class<?> modTabClass = Class.forName("test.tabs.ModTabs$1", false, loader);
            assertEquals(bridge, modTabClass.getSuperclass(),
                    "the mod's tab has to sit on the bridge");

            // Resolving the constructor is what proves the super() call has somewhere to land;
            // that resolution is exactly what threw NoSuchMethodError before.
            assertNotNull(modTabClass.getDeclaredConstructor(String.class),
                    "the String constructor the mod calls must resolve");
        }
    }

    /**
     * The handful of libraries Minecraft's own classes reference.
     *
     * <p>Resolving a constructor on {@code CreativeModeTab} pulls in {@code Component}, which
     * reaches Brigadier, so the Forge jar alone cannot answer the question this test asks.
     */
    private static List<URL> minecraftLibraries() {
        Path root = Path.of(System.getProperty("user.home", ""),
                "Library/Application Support/minecraft/libraries");
        if (!Files.isDirectory(root)) return List.of();
        List<String> wanted = List.of("brigadier", "datafixerupper", "guava", "joml", "authlib",
                "fastutil", "commons-lang3", "gson", "slf4j");
        List<URL> found = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path jar : paths.filter(p -> p.toString().endsWith(".jar")).toList()) {
                String name = jar.getFileName().toString();
                if (wanted.stream().anyMatch(name::startsWith)) found.add(jar.toUri().toURL());
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return found;
    }

    private static Path forgeJar() {
        Path candidate = Path.of(System.getProperty("user.home", ""),
                ".gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge",
                "1.20.1-47.3.22_mapped_official_1.20.1",
                "forge-1.20.1-47.3.22_mapped_official_1.20.1.jar");
        return Files.isRegularFile(candidate) ? candidate : null;
    }
}
