/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the tool constructors that 1.20.5 changed, for Mojang-named mods built for 1.20.4 or
 * older (Forge 1.20.1 mods after the SRG remap).
 *
 * <p>Before 1.20.5 a tool took its attack damage and speed in the constructor, as in
 * {@code new SwordItem(tier, 3, -2.4F, properties)}. Since then the constructor takes only the
 * tier and properties, and the numbers become an attribute component built by
 * {@code createAttributes}. Tools are built while items register, so one stale constructor stops
 * the whole mod before the server starts.
 *
 * <p>A direct {@code new} goes through a generated factory. A mod tool class that calls the old
 * {@code super(...)} cannot hand its uninitialized self to a helper, so the old arguments are
 * converted by a static call first and read back as the new argument list. Both build the same
 * attribute component the modern constructor expects, and the tier's damage bonus is added the
 * same way as before.
 *
 * <p>Registered only where the host has the 1.20.5 constructors. 1.21.5 removed the tool classes
 * themselves, which {@code RemovedItemBaseBridge} handles.
 */
public final class LegacyToolItemBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacyToolItems";

    static final String ITEM = "net/minecraft/world/item/";
    static final String TIER = ITEM + "Tier";
    static final String PROPERTIES = ITEM + "Item$Properties";
    static final String ATTRIBUTES = ITEM + "component/ItemAttributeModifiers";
    static final String TAG_KEY = "net/minecraft/tags/TagKey";
    static final String DIGGER = ITEM + "DiggerItem";
    static final String SWORD = ITEM + "SwordItem";

    private static final String L_TIER = "L" + TIER + ";";
    private static final String L_PROPERTIES = "L" + PROPERTIES + ";";
    private static final String L_TAG = "L" + TAG_KEY + ";";

    /**
     * One tool constructor. {@code attributesOwner.createAttributes(attributesDesc)} turns the old
     * numbers into the component; an int damage widens when the factory takes floats.
     */
    record Tool(String owner, String oldDesc, String newDesc, String attributesOwner, String attributesDesc,
                boolean publicConstructor) {
        String simpleName() {
            return owner.substring(owner.lastIndexOf('/') + 1);
        }

        /** The old arguments, returning nothing: what a super call hands to the stash. */
        String stashDesc() {
            return oldDesc;
        }

        String factoryDesc() {
            return oldDesc.substring(0, oldDesc.lastIndexOf(')') + 1) + "L" + owner + ";";
        }
    }

    static List<Tool> candidates() {
        String intFactory = "(" + L_TIER + "IF)L" + ATTRIBUTES + ";";
        String floatFactory = "(" + L_TIER + "FF)L" + ATTRIBUTES + ";";
        String tierProps = "(" + L_TIER + L_PROPERTIES + ")V";
        return List.of(
            new Tool(SWORD, "(" + L_TIER + "IF" + L_PROPERTIES + ")V", tierProps, SWORD, intFactory, true),
            new Tool(ITEM + "PickaxeItem", "(" + L_TIER + "IF" + L_PROPERTIES + ")V", tierProps,
                    DIGGER, floatFactory, true),
            new Tool(ITEM + "HoeItem", "(" + L_TIER + "IF" + L_PROPERTIES + ")V", tierProps,
                    DIGGER, floatFactory, true),
            new Tool(ITEM + "AxeItem", "(" + L_TIER + "FF" + L_PROPERTIES + ")V", tierProps,
                    DIGGER, floatFactory, true),
            new Tool(ITEM + "ShovelItem", "(" + L_TIER + "FF" + L_PROPERTIES + ")V", tierProps,
                    DIGGER, floatFactory, true),
            // Forge's DiggerItem put the numbers first; the constructor is protected since 1.20.5.
            new Tool(DIGGER, "(FF" + L_TIER + L_TAG + L_PROPERTIES + ")V",
                    "(" + L_TIER + L_TAG + L_PROPERTIES + ")V", DIGGER, floatFactory, false));
    }

    private LegacyToolItemBridge() {}

    public static void register(RetromodTransformer transformer) {
        List<Tool> tools = new ArrayList<>();
        for (Tool tool : candidates()) {
            if (replacedOnHost(tool)) tools.add(tool);
        }
        if (tools.isEmpty()) {
            LOGGER.debug("Legacy tool constructor bridge not needed on this host");
            return;
        }
        registerRedirects(transformer, tools);
        LOGGER.info("Bridged {} pre-1.20.5 tool constructors", tools.size());
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, List<Tool> tools) {
        transformer.registerSyntheticClass(HELPER, generateHelper(tools));
        for (Tool tool : tools) {
            if (tool.publicConstructor()) {
                transformer.registerConstructorRedirect(tool.owner(), tool.oldDesc(), HELPER,
                        "new" + tool.simpleName(), tool.factoryDesc());
            }
            Type[] newParams = Type.getArgumentTypes(tool.newDesc());
            transformer.registerSuperConstructorAdapter(tool.owner(), tool.oldDesc(), tool.newDesc(), mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "stash" + tool.simpleName(),
                        tool.stashDesc(), false);
                for (int i = 0; i < newParams.length; i++) {
                    mv.visitLdcInsn(i);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "take", "(I)Ljava/lang/Object;", false);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, newParams[i].getInternalName());
                }
            });
        }
    }

    /** The host has the new constructor and not the old one; offline, the version decides. */
    private static boolean replacedOnHost(Tool tool) {
        ClassNode owner = ClassResourceInspector.read(tool.owner());
        if (owner == null) {
            String host = RetromodVersion.TARGET_MC_VERSION;
            return RetromodVersion.compareMcVersions(host, "1.20.5") >= 0
                    && RetromodVersion.compareMcVersions(host, "1.21.2") < 0;
        }
        ClassNode attributes = ClassResourceInspector.read(tool.attributesOwner());
        return !hasMethod(owner, "<init>", tool.oldDesc()) && hasMethod(owner, "<init>", tool.newDesc())
                && attributes != null && hasMethod(attributes, "createAttributes", tool.attributesDesc());
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    static byte[] generateHelper(List<Tool> tools) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        // A super call converts its arguments here and reads them back before the constructor
        // runs. Items can register on several threads, so each thread keeps its own list.
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "STASH",
                "Ljava/lang/ThreadLocal;", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, HELPER, "STASH", "Ljava/lang/ThreadLocal;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        MethodVisitor take = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "take",
                "(I)Ljava/lang/Object;", null, null);
        take.visitCode();
        take.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "STASH", "Ljava/lang/ThreadLocal;");
        take.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false);
        take.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        take.visitVarInsn(Opcodes.ILOAD, 0);
        take.visitInsn(Opcodes.AALOAD);
        take.visitInsn(Opcodes.ARETURN);
        take.visitMaxs(0, 0);
        take.visitEnd();

        for (Tool tool : tools) {
            emitStash(cw, tool);
            if (tool.publicConstructor()) emitFactory(cw, tool);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Stores the new argument list, with the attributes folded into the properties. */
    private static void emitStash(ClassWriter cw, Tool tool) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "stash" + tool.simpleName(),
                tool.stashDesc(), null, null);
        mv.visitCode();
        Type[] newParams = Type.getArgumentTypes(tool.newDesc());
        mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "STASH", "Ljava/lang/ThreadLocal;");
        mv.visitLdcInsn(newParams.length);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        for (int i = 0; i < newParams.length; i++) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(i);
            loadNewArgument(mv, tool, newParams[i]);
            mv.visitInsn(Opcodes.AASTORE);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitFactory(ClassWriter cw, Tool tool) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "new" + tool.simpleName(),
                tool.factoryDesc(), null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, tool.owner());
        mv.visitInsn(Opcodes.DUP);
        for (Type param : Type.getArgumentTypes(tool.newDesc())) loadNewArgument(mv, tool, param);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, tool.owner(), "<init>", tool.newDesc(), false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Pushes one new argument, read from the old arguments in the method's own slots. */
    private static void loadNewArgument(MethodVisitor mv, Tool tool, Type param) {
        Type[] old = Type.getArgumentTypes(tool.oldDesc());
        String desc = param.getDescriptor();
        if (desc.equals(L_PROPERTIES)) {
            mv.visitVarInsn(Opcodes.ALOAD, slotOf(old, L_PROPERTIES));
            emitAttributes(mv, tool, old);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROPERTIES, "attributes",
                    "(L" + ATTRIBUTES + ";)" + L_PROPERTIES, false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, slotOf(old, desc));
        }
    }

    /** {@code createAttributes(tier, damage, speed)} from the two numbers in the old arguments. */
    private static void emitAttributes(MethodVisitor mv, Tool tool, Type[] old) {
        Type[] factory = Type.getArgumentTypes(tool.attributesDesc());
        mv.visitVarInsn(Opcodes.ALOAD, slotOf(old, L_TIER));
        int slot = 0;
        int number = 1;
        for (Type param : old) {
            if (param.getSort() == Type.INT || param.getSort() == Type.FLOAT) {
                mv.visitVarInsn(param.getOpcode(Opcodes.ILOAD), slot);
                if (param.getSort() == Type.INT && factory[number].getSort() == Type.FLOAT) {
                    mv.visitInsn(Opcodes.I2F);
                }
                number++;
            }
            slot += param.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, tool.attributesOwner(), "createAttributes",
                tool.attributesDesc(), false);
    }

    private static int slotOf(Type[] params, String desc) {
        int slot = 0;
        for (Type param : params) {
            if (param.getDescriptor().equals(desc)) return slot;
            slot += param.getSize();
        }
        throw new IllegalArgumentException("No " + desc + " parameter");
    }
}
