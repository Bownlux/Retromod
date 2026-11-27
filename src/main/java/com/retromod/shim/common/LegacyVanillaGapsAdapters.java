/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/** Class passes behind the vanilla gap bridges: an old furnace's burn time override, and the GUI frame old tooltip calls draw into. */
public final class LegacyVanillaGapsAdapters {

    /** Post-process flag: the host's furnaces ask the burn time with the world's fuel values (1.21.2 to 26.2). */
    public static final String FURNACE_FUEL_FLAG = "legacy-furnace-fuel-values";
    /** Post-process flag: the host's furnaces ask the burn time with the server level (26.3). */
    public static final String FURNACE_LEVEL_FLAG = "legacy-furnace-server-level";
    /** Post-process flag: old screen tooltips are set on the GUI frame an old render method last saw. */
    public static final String GUI_FRAME_FLAG = "legacy-gui-frame";
    /** Post-process flag: the host splits attacks from interactions, so a class that builds an attack sends it apart. */
    public static final String ATTACK_FLAG = "legacy-attack-packet";

    static final String FURNACE = "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity";
    /** The host's furnace classes a mod's furnace extends, and whose inherited burn time its super call names. */
    static final java.util.List<String> HOST_FURNACES = java.util.List.of(FURNACE, "net/minecraft/world/level/block/entity/FurnaceBlockEntity",
            "net/minecraft/world/level/block/entity/BlastFurnaceBlockEntity", "net/minecraft/world/level/block/entity/SmokerBlockEntity");
    static final String SUPER_BURN = "retromod$superBurnDuration";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String OLD_BURN = "(Lnet/minecraft/world/item/ItemStack;)I";
    static final String FUEL_BURN = "(Lnet/minecraft/world/level/block/entity/FuelValues;Lnet/minecraft/world/item/ItemStack;)I";
    static final String LEVEL_BURN = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)I";
    static final String GRAPHICS = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
    static final String SEND = "(Lnet/minecraft/network/protocol/Packet;)V";
    /** The client connection types an old mod sends a packet through. */
    static final java.util.List<String> SENDERS = java.util.List.of("net/minecraft/client/multiplayer/ClientPacketListener",
            "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl", "net/minecraft/network/Connection");
    /** Burn times changed shape in 1.21.2, and screen tooltips moved to the GUI frame in 1.21.6. */
    static final String FIRST_FUEL_SHAPE = "1.21.2";
    static final String FIRST_FRAME_TOOLTIPS = "1.21.6";

    private LegacyVanillaGapsAdapters() {}

    /** Runs each pass whose flag a bridge set; {@code modVersion} is null when the mod declares none. */
    public static byte[] apply(byte[] bytes, Predicate<String> flags, String modVersion) {
        if (bytes == null) return null;
        boolean fuel = flags.test(FURNACE_FUEL_FLAG);
        boolean level = flags.test(FURNACE_LEVEL_FLAG);
        boolean frame = flags.test(GUI_FRAME_FLAG) && builtBefore(modVersion, FIRST_FRAME_TOOLTIPS);
        boolean furnace = (fuel || level) && builtBefore(modVersion, FIRST_FUEL_SHAPE);
        boolean attack = flags.test(ATTACK_FLAG) && builtBefore(modVersion, "26.1");
        if (!furnace && !frame && !attack) return bytes;
        // Most classes name none of these, so a look at the constant pool skips the full read.
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        furnace &= text.contains("getBurnDuration");
        frame &= text.contains("GuiGraphicsExtractor");
        attack &= text.contains("createAttackPacket");
        if (!furnace && !frame && !attack) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        // A mixin's members land on its target, and Retromod's own classes need neither pass.
        if (OldRenderShapes.isMixin(node) || node.name.startsWith("com/retromod/")) return bytes;
        boolean changed = false;
        if (furnace) changed |= furnaceOverride(node, level ? LEVEL_BURN : FUEL_BURN);
        if (frame) changed |= frameCapture(node);
        if (attack) changed |= attackSends(node);
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** An old furnace's {@code getBurnDuration(stack)} override gets the host form the furnace calls, which runs it. */
    static boolean furnaceOverride(ClassNode node, String hostDesc) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || !extendsFurnace(node.superName)) return false;
        MethodNode old = declared(node, "getBurnDuration", OLD_BURN);
        if (old == null || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0 || declared(node, "getBurnDuration", hostDesc) != null) {
            return false;
        }
        // Its own call to the inherited burn time goes to the host form, through a helper method that finds the context.
        boolean superCalls = false;
        for (MethodNode method : node.methods) superCalls |= superBurnCalls(node, method);
        if (superCalls) node.methods.add(superBurn(hostDesc));
        node.methods.add(hostForm(node, hostDesc));
        return true;
    }

    private static boolean extendsFurnace(String superName) {
        if (superName == null) return false;
        if (HOST_FURNACES.contains(superName)) return true;
        return !superName.startsWith("net/minecraft/") && RetromodTransformer.currentJarClassInheritsFrom(superName, FURNACE);
    }

    /** {@code super.getBurnDuration(stack)} calls the helper method that asks the host form. */
    private static boolean superBurnCalls(ClassNode node, MethodNode method) {
        boolean found = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            // Only the call to the host's own burn time: a mod superclass that overrides it keeps the old form.
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL || !HOST_FURNACES.contains(call.owner)
                    || !call.name.equals("getBurnDuration") || !call.desc.equals(OLD_BURN)) continue;
            call.owner = node.name;
            call.name = SUPER_BURN;
            call.itf = false;
            found = true;
        }
        return found;
    }

    /** The inherited burn time in the host form, with the furnace's fuel values, or its server level and none on a client. */
    private static MethodNode superBurn(String hostDesc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, SUPER_BURN, OLD_BURN, null, null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/world/level/block/entity/BlockEntity", "level", "Lnet/minecraft/world/level/Level;"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        LabelNode noServer = new LabelNode();
        if (hostDesc.equals(LEVEL_BURN)) {
            // 26.3 keeps fuel on the server, so a client furnace asking its own burn time gets none instead of a bad cast.
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new TypeInsnNode(Opcodes.INSTANCEOF, SERVER_LEVEL));
            code.add(new JumpInsnNode(Opcodes.IFEQ, noServer));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        if (hostDesc.equals(LEVEL_BURN)) {
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, SERVER_LEVEL));
        } else {
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyVanillaGapsBridge.FUELS, "fuelValues",
                    "(Ljava/lang/Object;)Lnet/minecraft/world/level/block/entity/FuelValues;", false));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, FURNACE, "getBurnDuration", hostDesc, false));
        code.add(new InsnNode(Opcodes.IRETURN));
        if (hostDesc.equals(LEVEL_BURN)) {
            code.add(noServer);
            code.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"net/minecraft/world/level/Level"}, 0, null));
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new InsnNode(Opcodes.IRETURN));
        }
        method.maxStack = 3;
        method.maxLocals = 3;
        return method;
    }

    /** {@code getBurnDuration(context, stack)}: the old override's answer, or the host's own when the override fails. */
    private static MethodNode hostForm(ClassNode node, String hostDesc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PROTECTED | Opcodes.ACC_SYNTHETIC, "getBurnDuration", hostDesc, null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode fallback = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getBurnDuration", OLD_BURN, false));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        // The furnace never ran that override before, so a missing member or a runtime error there falls back.
        method.instructions.add(fallback);
        method.instructions.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"}));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, FURNACE, "getBurnDuration", hostDesc, false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, fallback, "java/lang/LinkageError"));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, fallback, "java/lang/RuntimeException"));
        method.maxStack = 3;
        method.maxLocals = 3;
        return method;
    }

    /** Each method that takes the GUI frame hands it to the client helper first, for the old tooltip calls it makes. */
    static boolean frameCapture(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.name.startsWith("<")) continue;
            int slot = frameSlot(method);
            if (slot < 0) continue;
            InsnList capture = new InsnList();
            capture.add(new VarInsnNode(Opcodes.ALOAD, slot));
            capture.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyVanillaGapsBridge.FRAMES, "frame", "(Ljava/lang/Object;)V", false));
            method.instructions.insert(capture);
            changed = true;
        }
        return changed;
    }

    /** Each client send in a class that builds attack packets goes through the helper, which sends an attack as one. */
    static boolean attackSends(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESPECIAL || !call.name.equals("send")
                        || !call.desc.equals(SEND) || !SENDERS.contains(call.owner)) continue;
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = LegacyVanillaGapsBridge.CLIENT;
                call.desc = "(Ljava/lang/Object;Ljava/lang/Object;)V";
                call.itf = false;
                changed = true;
            }
        }
        return changed;
    }

    /** The local slot of the method's first GUI frame parameter, or -1. */
    private static int frameSlot(MethodNode method) {
        int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.getDescriptor().equals(GRAPHICS)) return slot;
            slot += argument.getSize();
        }
        return -1;
    }

    private static MethodNode declared(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    /** Whether the mod was built before {@code version}; a mod that declares none counts as old. */
    private static boolean builtBefore(String modVersion, String version) {
        return modVersion == null || RetromodVersion.compareMcVersions(modVersion, version) < 0;
    }
}
