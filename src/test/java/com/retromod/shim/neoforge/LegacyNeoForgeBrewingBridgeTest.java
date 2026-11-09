/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.LegacyBrewingBridge;
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

import static com.retromod.shim.neoforge.LegacyNeoForgeBrewingBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** On NeoForge 26.3, which removed brewing's registration event, an old brewing listener gets the event rebuilt inside its mod. */
class LegacyNeoForgeBrewingBridgeTest {

    static final String HANDLERS = "mod/BrewingHandlers";
    static final String BUS = "net/neoforged/bus/api/IEventBus";
    static final String HOLDER = "Lnet/minecraft/core/Holder;";
    static final String ITEM = "Lnet/minecraft/world/item/Item;";

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

    @Test
    @DisplayName("The NeoForge 26.3 shim rebuilds the event, its builder, the recipe interface and the install")
    void neoForge263TakesTheBridge() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        new NeoForge_26_2_to_26_3().registerRedirects(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals(EVENT, redirects.get(OLD_EVENT));
        assertEquals(BUILDER, redirects.get(OLD_BUILDER));
        assertEquals(LegacyBrewingBridge.recipeInterface(), redirects.get(OLD_RECIPE),
                "a NeoForge mod's recipes implement the interface the store reads");
        Set<String> synthetics = transformer.getSyntheticClasses().keySet();
        for (String rebuilt : List.of(EVENT, BUILDER, INSTALLER, ROOT + "LegacyBrewingMix", STORE, PACK_SOURCE)) {
            assertTrue(synthetics.contains(rebuilt), rebuilt + " is registered for embedding");
        }
    }

    @Test
    @DisplayName("A host that still posts the event, Forge, and an offline target before 26.3 keep the old names")
    void otherHostsAreRefused() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyNeoForgeBrewingBridge.register(transformer, host(ANCHOR, OLD_EVENT));
        assertNull(transformer.getClassRedirects().get(OLD_EVENT), "NeoForge 26.2 and older still post it");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyNeoForgeBrewingBridge.register(transformer, host());
        assertNull(transformer.getClassRedirects().get(OLD_EVENT), "offline, NeoForge 26.2");

        RetromodVersion.TARGET_MC_VERSION = "26.3";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        LegacyNeoForgeBrewingBridge.register(transformer, host());
        assertNull(transformer.getClassRedirects().get(OLD_EVENT), "Forge has its own brewing event");

        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        LegacyNeoForgeBrewingBridge.register(transformer, host(ANCHOR));
        assertEquals(EVENT, transformer.getClassRedirects().get(OLD_EVENT), "a NeoForge host without the event");
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"21.11.42", "26.1.2.60-beta", "26.2.0.0-beta", "26.3.0.43-beta"})
    @DisplayName("The real NeoForge jars: 1.21.11 to 26.2 post the event, 26.3 removed it")
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
            assertEquals(version.startsWith("26.3"), hostTakesBridge(host), version);
        }
    }

    @Test
    @DisplayName("An addListener reference and the builder calls of a 1.21.1 mod name the copy rebuilt in the mod")
    void oldListenerTakesTheStandIn() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyNeoForgeBrewingBridge.register(transformer, host(ANCHOR));
        transformer.setModSourceVersion("1.21.1");
        ClassNode out = node(transformer.transformClass(handlers(), HANDLERS + ".class"));

        MethodNode brew = method(out, "onBrew");
        assertEquals("(L" + EVENT + ";)V", brew.desc, "the listener takes the rebuilt event");
        assertEquals(List.of("virtual " + EVENT + ".getBuilder()L" + BUILDER + ";",
                "virtual " + BUILDER + ".addRecipe(Ljava/lang/Object;)V",
                "virtual " + EVENT + ".getBuilder()L" + BUILDER + ";",
                "virtual " + BUILDER + ".addMix(" + HOLDER + ITEM + HOLDER + ")V"), calls(brew));

        // NeoForge's bus reads a method reference's event type from the method it names.
        InvokeDynamicInsnNode reference = indy(method(out, "subscribe"));
        Handle target = (Handle) reference.bsmArgs[1];
        assertEquals("(L" + EVENT + ";)V", target.getDesc());
        assertTrue(calls(method(out, "subscribe")).contains("interface " + BUS + ".addListener(Ljava/util/function/Consumer;)V"),
                "the listener is still added");
    }

    @Test
    @DisplayName("Only a jar that names the old event gets the install call")
    void installOnlyWhereTheEventIsNamed() {
        assertNotNull(plan(Map.of(HANDLERS, handlers()), Map.of()));
        assertNull(plan(Map.of("mod/Other", plainClass("mod/Other")), Map.of()));
        assertFalse(namesOldEvent((OLD_EVENT + "Extra;").getBytes()), "a longer class name is another class");
    }

    /** A 1.21.1 class: subscribe() adds onBrew by reference, and onBrew adds a recipe and a mix. */
    static byte[] handlers() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, HANDLERS, null, "java/lang/Object", null);
        String eventDesc = "(L" + OLD_EVENT + ";)V";

        MethodVisitor subscribe = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "subscribe", "(L" + BUS + ";)V", null, null);
        subscribe.visitCode();
        subscribe.visitVarInsn(ALOAD, 0);
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        subscribe.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;", metafactory,
                Type.getType("(Ljava/lang/Object;)V"), new Handle(H_INVOKESTATIC, HANDLERS, "onBrew", eventDesc, false),
                Type.getType(eventDesc));
        subscribe.visitMethodInsn(INVOKEINTERFACE, BUS, "addListener", "(Ljava/util/function/Consumer;)V", true);
        subscribe.visitInsn(RETURN);
        subscribe.visitMaxs(0, 0);
        subscribe.visitEnd();

        MethodVisitor brew = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onBrew", eventDesc, null, null);
        brew.visitCode();
        brew.visitVarInsn(ALOAD, 0);
        brew.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "getBuilder", "()L" + OLD_BUILDER + ";", false);
        brew.visitInsn(ACONST_NULL);
        brew.visitTypeInsn(CHECKCAST, OLD_RECIPE);
        brew.visitMethodInsn(INVOKEVIRTUAL, OLD_BUILDER, "addRecipe", "(L" + OLD_RECIPE + ";)V", false);
        brew.visitVarInsn(ALOAD, 0);
        brew.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "getBuilder", "()L" + OLD_BUILDER + ";", false);
        brew.visitInsn(ACONST_NULL);
        brew.visitInsn(ACONST_NULL);
        brew.visitInsn(ACONST_NULL);
        brew.visitMethodInsn(INVOKEVIRTUAL, OLD_BUILDER, "addMix", "(" + HOLDER + ITEM + HOLDER + ")V", false);
        brew.visitInsn(RETURN);
        brew.visitMaxs(0, 0);
        brew.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] plainClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static Function<String, ClassNode> host(String... present) {
        Set<String> names = Set.of(present);
        return name -> names.contains(name) ? new ClassNode() : null;
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    static InvokeDynamicInsnNode indy(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) return indy;
        }
        throw new AssertionError("no invokedynamic in " + method.name);
    }

    static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            String kind = switch (call.getOpcode()) {
                case INVOKESTATIC -> "static";
                case INVOKEINTERFACE -> "interface";
                default -> "virtual";
            };
            calls.add(kind + " " + call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    /** NeoForge's universal jar from the launcher's library folder, or null. */
    static Path neoForgeJar(String version) throws IOException {
        Path folder = Path.of(System.getProperty("user.home"), "Library", "Application Support", "PrismLauncher",
                "libraries", "net", "neoforged", "neoforge", version);
        Path jar = folder.resolve("neoforge-" + version + "-universal.jar");
        return Files.isRegularFile(jar) ? jar : null;
    }
}
