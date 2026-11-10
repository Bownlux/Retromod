/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * NeoForge 1.21.1 mods whose next failure on a 26.3 server was a removed loader or vanilla API: Tom's Storage's
 * DirectionalPayloadHandler, Open Parties and Claims' ChorusFruit and EntityInteractSpecific listeners, JourneyMap's ColorArgument,
 * and Sophisticated Core's container slots and inherited serializer constant.
 */
class NeoForge263ApiPathTest {

    private static final String PAYLOAD = "net/neoforged/neoforge/network/handling/";
    private static final String CONTENTS = "net/minecraft/world/item/component/ItemContainerContents";
    private static final String STACK = "net/minecraft/world/item/ItemStack";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A CLI translation for 26.3 rewrites each old API to its bridge")
    void cliTranslationRewritesEachApi() throws Exception {
        Map<String, ClassNode> classes = translate();

        List<String> payload = refs(classes.get("apis/Payloads"));
        assertTrue(payload.stream().anyMatch(ref -> ref.contains("legacyapi/neoforge/network/DirectionalPayloadHandler.<init>")),
                payload.toString());
        assertFalse(payload.stream().anyMatch(ref -> ref.contains(PAYLOAD + "DirectionalPayloadHandler")), payload.toString());

        MethodNode listener = method(classes.get("apis/Teleports"), "onChorus");
        assertEquals("(Lnet/neoforged/neoforge/event/entity/EntityTeleportEvent$ItemConsumption;)V", listener.desc,
                "the bus picks the event from the listener's parameter");
        String interact = "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent$EntityInteract";
        MethodNode onInteract = method(classes.get("apis/Teleports"), "onInteract");
        assertEquals("(L" + interact + ";)V", onInteract.desc, "26.3 merged the specific event into EntityInteract");
        assertTrue(refs(classes.get("apis/Teleports")).contains("INVOKEVIRTUAL " + interact + ".getLocation"),
                refs(classes.get("apis/Teleports")).toString());

        List<String> colors = refs(classes.get("apis/Colors"));
        assertTrue(colors.contains("INVOKESTATIC net/minecraft/commands/arguments/TeamColorArgument.teamColor"), colors.toString());
        assertTrue(colors.stream().anyMatch(ref -> ref.endsWith("LegacyColorArguments.getColor")), colors.toString());

        List<String> slots = refs(classes.get("apis/Slots"));
        assertTrue(slots.stream().anyMatch(ref -> ref.endsWith("LegacyContainerSlot.<init>")), slots.toString());
        assertTrue(slots.stream().anyMatch(ref -> ref.endsWith("LegacyContainerSlot.asSlots")), slots.toString());
        assertTrue(slots.stream().anyMatch(ref -> ref.endsWith("LegacyContainerSlot.fromSlots")), slots.toString());
        assertFalse(slots.stream().anyMatch(ref -> ref.contains("ItemContainerContents$Slot")), slots.toString());

        List<String> spawns = refs(classes.get("apis/Spawns"));
        assertTrue(spawns.stream().anyMatch(ref -> ref.endsWith("LegacyCreatureProbability.probability")), spawns.toString());
        assertTrue(spawns.stream().anyMatch(ref -> ref.endsWith("LegacyCreatureProbability.keep")), spawns.toString());
        assertFalse(spawns.stream().anyMatch(ref -> ref.contains("MobSpawnSettingsBuilder.")), spawns.toString());

        List<String> serializer = refs(classes.get("apis/OwnSerializer"));
        assertTrue(serializer.contains("GETSTATIC net/minecraft/world/item/crafting/ShapedRecipe.SERIALIZER"),
                "a constant read through the mod's own serializer class: " + serializer);
    }

    @Test
    @DisplayName("The copies in the translated jar hand each payload to its side and keep each stack at its slot")
    void embeddedCopiesRun() throws Exception {
        translate();
        Path output = dir.resolve("out/apis.jar");
        try (URLClassLoader host = standIns(); URLClassLoader mod = new URLClassLoader(new URL[]{output.toUri().toURL()}, host)) {
            Class<?> payloads = mod.loadClass("apis.Payloads");
            assertEquals("client", payloads.getMethod("handled", boolean.class).invoke(null, true));
            assertEquals("server", payloads.getMethod("handled", boolean.class).invoke(null, false));
            assertEquals("stone@0,dirt@2,size3", mod.loadClass("apis.Slots").getMethod("roundTrip").invoke(null));
            assertEquals("RED", mod.loadClass("apis.Colors").getMethod("read").invoke(null).toString());
        }
    }

    @Test
    @DisplayName("A 26.3 host with the old payload handler, or a 1.21.6 target, keeps the class")
    void olderHostsKeepThePayloadHandler() {
        McReflect.setForceNeoForge(true);
        RetromodVersion.TARGET_MC_VERSION = "1.21.6";
        transformer.clearRedirectsForTesting();
        LegacyPayloadHandlerBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(LegacyPayloadHandlerBridge.OLD));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyPayloadHandlerBridge.register(transformer);
        assertEquals(LegacyPayloadHandlerBridge.REBUILT, transformer.getClassRedirects().get(LegacyPayloadHandlerBridge.OLD));
    }

    private Map<String, ClassNode> translate() throws Exception {
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        Path input = Files.createDirectories(dir.resolve("in")).resolve("apis.jar");
        Files.write(input, modJar());
        Path output = Files.createDirectories(dir.resolve("out")).resolve("apis.jar");
        CliHarness.transform(input, output, "26.3");
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            for (var entry : java.util.Collections.list(jar.entries())) {
                if (!entry.getName().startsWith("apis/") || !entry.getName().endsWith(".class")) continue;
                ClassNode node = new ClassNode();
                new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
                classes.put(node.name, node);
            }
        }
        return classes;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    /** Each call, field read, new and method reference, as "OPCODE owner.name". */
    private static List<String> refs(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) refs.add(opcode(c.getOpcode()) + " " + c.owner + "." + c.name);
                if (insn instanceof FieldInsnNode f) refs.add(opcode(f.getOpcode()) + " " + f.owner + "." + f.name);
                if (insn instanceof TypeInsnNode t) refs.add("NEW " + t.desc);
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle h) refs.add("HANDLE " + h.getOwner() + "." + h.getName());
                    }
                }
            }
        }
        return refs;
    }

    private static String opcode(int opcode) {
        return switch (opcode) {
            case GETSTATIC -> "GETSTATIC";
            case INVOKESTATIC -> "INVOKESTATIC";
            case INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case INVOKEINTERFACE -> "INVOKEINTERFACE";
            case INVOKESPECIAL -> "INVOKESPECIAL";
            default -> "OP" + opcode;
        };
    }

    /** A NeoForge 1.21.1 mod using each API the way the reported mods do. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"apis\"\nversion=\"1.0.0\"\n[[dependencies.apis]]\nmodId=\"minecraft\"\n"
                + "type=\"required\"\nversionRange=\"[1.21.1,1.21.2)\"\n").getBytes(StandardCharsets.UTF_8));
        mod.put("apis/Payloads.class", payloads());
        mod.put("apis/Teleports.class", teleports());
        mod.put("apis/Colors.class", colors());
        mod.put("apis/Slots.class", slots());
        mod.put("apis/Serializers.class", serializerBase());
        mod.put("apis/OwnSerializer.class", ownSerializer());
        mod.put("apis/Spawns.class", spawns());
        return LoaderApiFixtures.jar(mod);
    }

    /** {@code handled(client)}: a payload handled through a DirectionalPayloadHandler, as Tom's Storage registers one. */
    private static byte[] payloads() {
        String handler = PAYLOAD + "IPayloadHandler";
        String context = PAYLOAD + "IPayloadContext";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "apis/Payloads", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "handled", "(Z)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, PAYLOAD + "DirectionalPayloadHandler");
        mv.visitInsn(DUP);
        mv.visitTypeInsn(NEW, "standin/Recorder");
        mv.visitInsn(DUP);
        mv.visitLdcInsn("client");
        mv.visitMethodInsn(INVOKESPECIAL, "standin/Recorder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitTypeInsn(NEW, "standin/Recorder");
        mv.visitInsn(DUP);
        mv.visitLdcInsn("server");
        mv.visitMethodInsn(INVOKESPECIAL, "standin/Recorder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitMethodInsn(INVOKESPECIAL, PAYLOAD + "DirectionalPayloadHandler", "<init>",
                "(L" + handler + ";L" + handler + ";)V", false);
        mv.visitInsn(ACONST_NULL);
        mv.visitVarInsn(ILOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, "standin/Context", "of", "(Z)Lstandin/Context;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, handler, "handle",
                "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;L" + context + ";)V", true);
        mv.visitFieldInsn(GETSTATIC, "standin/Recorder", "last", "Ljava/lang/String;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Listeners for the chorus fruit teleport and the specific entity interaction, as Open Parties and Claims has. */
    private static byte[] teleports() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "apis/Teleports", null, "java/lang/Object", null);
        String specific = "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent$EntityInteractSpecific";
        MethodVisitor interact = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onInteract", "(L" + specific + ";)V", null, null);
        interact.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true).visitEnd();
        interact.visitCode();
        interact.visitVarInsn(ALOAD, 0);
        interact.visitMethodInsn(INVOKEVIRTUAL, specific, "getLocalPos", "()Lnet/minecraft/world/phys/Vec3;", false);
        interact.visitInsn(POP);
        interact.visitInsn(RETURN);
        interact.visitMaxs(0, 0);
        interact.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onChorus",
                "(Lnet/neoforged/neoforge/event/entity/EntityTeleportEvent$ChorusFruit;)V", null, null);
        mv.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true).visitEnd();
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code read()}: a color argument built and read back, as JourneyMap's waypoint command does. */
    private static byte[] colors() {
        String argument = "net/minecraft/commands/arguments/ColorArgument";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "apis/Colors", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, argument, "color", "()L" + argument + ";", false);
        mv.visitInsn(POP);
        mv.visitInsn(ACONST_NULL);
        mv.visitLdcInsn("color");
        mv.visitMethodInsn(INVOKESTATIC, argument, "getColor",
                "(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)Lnet/minecraft/ChatFormatting;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code roundTrip()}: stacks into slots and back, through a method reference, as Sophisticated Core's codec does. */
    private static byte[] slots() {
        String slot = CONTENTS + "$Slot";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "apis/Slots", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "roundTrip", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        // List.of(new Slot(0, stone), new Slot(2, dirt))
        for (Object[] entry : new Object[][]{{0, "stone"}, {2, "dirt"}}) {
            mv.visitTypeInsn(NEW, slot);
            mv.visitInsn(DUP);
            mv.visitLdcInsn(entry[0]);
            mv.visitTypeInsn(NEW, STACK);
            mv.visitInsn(DUP);
            mv.visitLdcInsn(entry[1]);
            mv.visitMethodInsn(INVOKESPECIAL, STACK, "<init>", "(Ljava/lang/String;)V", false);
            mv.visitMethodInsn(INVOKESPECIAL, slot, "<init>", "(IL" + STACK + ";)V", false);
        }
        mv.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", true);
        mv.visitMethodInsn(INVOKESTATIC, CONTENTS, "fromSlots", "(Ljava/util/List;)L" + CONTENTS + ";", false);
        mv.visitVarInsn(ASTORE, 0);
        // contents -> contents.asSlots(), through a method reference
        mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(H_INVOKEVIRTUAL, CONTENTS, "asSlots", "()Ljava/util/List;", false),
                Type.getType("(L" + CONTENTS + ";)Ljava/util/List;"));
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "java/util/List");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, "apis/Slots", "describe", "(Ljava/util/List;L" + CONTENTS + ";)Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // describe(slots, contents): "item@index,..." and the container's size
        MethodVisitor describe = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "describe",
                "(Ljava/util/List;L" + CONTENTS + ";)Ljava/lang/String;", null, null);
        describe.visitCode();
        describe.visitTypeInsn(NEW, "java/lang/StringBuilder");
        describe.visitInsn(DUP);
        describe.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        describe.visitVarInsn(ASTORE, 2);
        for (int i = 0; i < 2; i++) {
            describe.visitVarInsn(ALOAD, 2);
            describe.visitVarInsn(ALOAD, 0);
            describe.visitLdcInsn(i);
            describe.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
            describe.visitTypeInsn(CHECKCAST, slot);
            describe.visitVarInsn(ASTORE, 3);
            describe.visitVarInsn(ALOAD, 3);
            describe.visitMethodInsn(INVOKEVIRTUAL, slot, "item", "()L" + STACK + ";", false);
            describe.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
            describe.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            describe.visitLdcInsn("@");
            describe.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            describe.visitVarInsn(ALOAD, 3);
            describe.visitMethodInsn(INVOKEVIRTUAL, slot, "index", "()I", false);
            describe.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
            describe.visitLdcInsn(",");
            describe.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            describe.visitInsn(POP);
        }
        describe.visitVarInsn(ALOAD, 2);
        describe.visitLdcInsn("size");
        describe.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        describe.visitVarInsn(ALOAD, 1);
        describe.visitMethodInsn(INVOKEVIRTUAL, CONTENTS, "getSlots", "()I", false);
        describe.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
        describe.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        describe.visitInsn(ARETURN);
        describe.visitMaxs(0, 0);
        describe.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A biome modifier that scales the creature spawn probability, as Citadel's does. */
    private static byte[] spawns() {
        String builder = "net/neoforged/neoforge/common/world/MobSpawnSettingsBuilder";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "apis/Spawns", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "scale", "(L" + builder + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "getProbability", "()F", false);
        mv.visitLdcInsn(2f);
        mv.visitInsn(FMUL);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "creatureGenerationProbability",
                "(F)Lnet/minecraft/world/level/biome/MobSpawnSettings$Builder;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod's serializer base that implements RecipeSerializer, as Sophisticated Core's RecipeWrapperSerializer does. */
    private static byte[] serializerBase() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_ABSTRACT, "apis/Serializers", null, "java/lang/Object",
                new String[]{"net/minecraft/world/item/crafting/RecipeSerializer"});
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Its subclass reading SHAPED_RECIPE by simple name, which javac writes through the subclass. */
    private static byte[] ownSerializer() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "apis/OwnSerializer", null, "apis/Serializers", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "shaped", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, "apis/OwnSerializer", "SHAPED_RECIPE",
                "Lnet/minecraft/world/item/crafting/RecipeSerializer;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 26.3 stand-ins for the host classes the translated code and the embedded copies call. */
    private URLClassLoader standIns() throws Exception {
        Path src = dir.resolve("standin-src");
        Path classes = dir.resolve("standin-classes");
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "--release", "17"));
        for (Map.Entry<String, String> source : STAND_INS.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test needs a JDK to compile the 26.3 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the stand-ins must compile");
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static final Map<String, String> STAND_INS = Map.ofEntries(
            Map.entry("net/minecraft/network/protocol/common/custom/CustomPacketPayload.java", """
                    package net.minecraft.network.protocol.common.custom;
                    public interface CustomPacketPayload {}"""),
            Map.entry("net/minecraft/network/protocol/PacketFlow.java", """
                    package net.minecraft.network.protocol;
                    public enum PacketFlow { SERVERBOUND, CLIENTBOUND }"""),
            Map.entry("net/neoforged/neoforge/network/handling/IPayloadContext.java", """
                    package net.neoforged.neoforge.network.handling;
                    public interface IPayloadContext { net.minecraft.network.protocol.PacketFlow flow(); }"""),
            Map.entry("net/neoforged/neoforge/network/handling/IPayloadHandler.java", """
                    package net.neoforged.neoforge.network.handling;
                    public interface IPayloadHandler<T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> {
                        void handle(T payload, IPayloadContext context);
                    }"""),
            Map.entry("standin/Recorder.java", """
                    package standin;
                    public class Recorder implements net.neoforged.neoforge.network.handling.IPayloadHandler<
                            net.minecraft.network.protocol.common.custom.CustomPacketPayload> {
                        public static String last;
                        private final String side;
                        public Recorder(String side) { this.side = side; }
                        public void handle(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload,
                                net.neoforged.neoforge.network.handling.IPayloadContext context) { last = side; }
                    }"""),
            Map.entry("standin/Context.java", """
                    package standin;
                    import net.minecraft.network.protocol.PacketFlow;
                    public record Context(PacketFlow flow) implements net.neoforged.neoforge.network.handling.IPayloadContext {
                        public static Context of(boolean toClient) {
                            return new Context(toClient ? PacketFlow.CLIENTBOUND : PacketFlow.SERVERBOUND);
                        }
                    }"""),
            Map.entry("net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    public final class ItemStack {
                        public static final ItemStack EMPTY = new ItemStack("air");
                        private final String item;
                        public ItemStack(String item) { this.item = item; }
                        public boolean isEmpty() { return this == EMPTY; }
                        public String toString() { return item; }
                    }"""),
            Map.entry("net/minecraft/world/item/component/ItemContainerContents.java", """
                    package net.minecraft.world.item.component;
                    import net.minecraft.world.item.ItemStack;
                    import java.util.List;
                    public final class ItemContainerContents {
                        public static final ItemContainerContents EMPTY = new ItemContainerContents(List.of());
                        private final List<ItemStack> items;
                        private ItemContainerContents(List<ItemStack> items) { this.items = List.copyOf(items); }
                        public static ItemContainerContents fromItems(List<ItemStack> items) { return new ItemContainerContents(items); }
                        public int getSlots() { return items.size(); }
                        public ItemStack getStackInSlot(int slot) { return items.get(slot); }
                    }"""),
            Map.entry("com/mojang/brigadier/context/CommandContext.java", """
                    package com.mojang.brigadier.context;
                    public class CommandContext<S> {}"""),
            Map.entry("net/minecraft/ChatFormatting.java", """
                    package net.minecraft;
                    public enum ChatFormatting { BLACK, RED, WHITE, BOLD, RESET }"""),
            Map.entry("net/minecraft/world/scores/TeamColor.java", """
                    package net.minecraft.world.scores;
                    public enum TeamColor { BLACK, RED, WHITE }"""),
            Map.entry("net/minecraft/commands/arguments/TeamColorArgument.java", """
                    package net.minecraft.commands.arguments;
                    public class TeamColorArgument {
                        public static TeamColorArgument teamColor() { return new TeamColorArgument(); }
                        public static net.minecraft.world.scores.TeamColor getTeamColor(
                                com.mojang.brigadier.context.CommandContext<?> context, String name) {
                            return net.minecraft.world.scores.TeamColor.RED;
                        }
                    }"""));
}
