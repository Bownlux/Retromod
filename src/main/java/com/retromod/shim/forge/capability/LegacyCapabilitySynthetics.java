/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/**
 * Supplies Forge's deleted capability API to a Forge mod running on NeoForge.
 *
 * <p>The logic is ordinary compiled Java in this package. Each class is renamed to the Forge class
 * it replaces and registered as a synthetic, so the mod's own references resolve without any class
 * redirect and {@link com.retromod.core.SyntheticEmbedder} relocates the whole set into the mod.
 * The interfaces whose methods name Minecraft types ({@code ICapabilityProvider},
 * {@code INBTSerializable}) are generated here, because Retromod is not compiled against Minecraft.
 */
public final class LegacyCapabilitySynthetics {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String PKG = "com/retromod/shim/forge/capability/";
    public static final String RUNTIME = PKG + "LegacyCapabilityRuntime";

    public static final String CAPABILITY = "net/minecraftforge/common/capabilities/Capability";
    public static final String LAZY_OPTIONAL = "net/minecraftforge/common/util/LazyOptional";
    public static final String PROVIDER = "net/minecraftforge/common/capabilities/ICapabilityProvider";
    public static final String SERIALIZABLE = "net/minecraftforge/common/capabilities/ICapabilitySerializable";
    public static final String NBT_SERIALIZABLE = "net/minecraftforge/common/util/INBTSerializable";
    public static final String ATTACH_EVENT = "net/minecraftforge/event/AttachCapabilitiesEvent";

    private static final String NEO_EVENT = "net/neoforged/bus/api/Event";
    private static final String DIRECTION = "Lnet/minecraft/core/Direction;";
    private static final String TAG = "Lnet/minecraft/nbt/Tag;";

    /** Compiled stand-in to the Forge name it is embedded under. */
    static final Map<String, String> FORGE_NAMES = new LinkedHashMap<>();
    static {
        FORGE_NAMES.put(PKG + "LegacyLazyOptional", LAZY_OPTIONAL);
        FORGE_NAMES.put(PKG + "LegacyCapability", CAPABILITY);
        FORGE_NAMES.put(PKG + "LegacyCapabilityToken", "net/minecraftforge/common/capabilities/CapabilityToken");
        FORGE_NAMES.put(PKG + "LegacyCapabilityManager", "net/minecraftforge/common/capabilities/CapabilityManager");
        FORGE_NAMES.put(PKG + "LegacyForgeCapabilities", "net/minecraftforge/common/capabilities/ForgeCapabilities");
        FORGE_NAMES.put(PKG + "LegacyAttachCapabilitiesEvent", ATTACH_EVENT);
    }

    /** Runtime classes that keep their Retromod name; the embedder still relocates them per mod. */
    private static final List<String> RUNTIME_CLASSES = List.of(
            RUNTIME, RUNTIME + "$GameBus", RUNTIME + "$NeoForgeCapabilities", RUNTIME + "$Persistence");

    private LegacyCapabilitySynthetics() {}

    /** Register every capability synthetic plus the redirects Forge's functional types need. */
    public static void register(RetromodTransformer transformer) {
        try {
            for (Map.Entry<String, String> entry : FORGE_NAMES.entrySet()) {
                transformer.registerSyntheticClass(entry.getValue(), renamed(entry.getKey()));
            }
            for (String runtimeClass : RUNTIME_CLASSES) {
                transformer.registerSyntheticClass(runtimeClass, renamed(runtimeClass));
            }
            transformer.registerSyntheticClass(PROVIDER, generateProvider());
            transformer.registerSyntheticClass(NBT_SERIALIZABLE, generateNbtSerializable());
            transformer.registerSyntheticClass(SERIALIZABLE, generateSerializable());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not register the Forge capability bridge: {}", e.toString());
            return;
        }

        // Forge's NonNull functional interfaces have the same single method as the JDK ones.
        transformer.registerClassRedirect("net/minecraftforge/common/util/NonNullSupplier",
                "java/util/function/Supplier");
        transformer.registerClassRedirect("net/minecraftforge/common/util/NonNullConsumer",
                "java/util/function/Consumer");
        transformer.registerClassRedirect("net/minecraftforge/common/util/NonNullFunction",
                "java/util/function/Function");
        transformer.registerClassRedirect("net/minecraftforge/common/util/NonNullPredicate",
                "java/util/function/Predicate");

        // NeoForge kept the event name but dropped register(Class): tokens need no registration.
        String forgeRegister = "net/minecraftforge/common/capabilities/RegisterCapabilitiesEvent";
        String neoRegister = "net/neoforged/neoforge/capabilities/RegisterCapabilitiesEvent";
        transformer.registerClassRedirect(forgeRegister, neoRegister);
        transformer.registerMethodRedirect(neoRegister, "register", "(Ljava/lang/Class;)V",
                RUNTIME, "registerType", "(Ljava/lang/Object;Ljava/lang/Class;)V", true);
    }

    /** True once {@link #register} has supplied the runtime, which gates the call adapter. */
    public static boolean isRegistered(RetromodTransformer transformer) {
        return transformer.getSyntheticClasses().containsKey(RUNTIME);
    }

    static byte[] renamed(String compiledName) throws IOException {
        byte[] compiled = readCompiled(compiledName);
        ClassReader reader = new ClassReader(compiled);
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor visitor = new ClassRemapper(writer, new SimpleRemapper(FORGE_NAMES));
        if (compiledName.equals(PKG + "LegacyAttachCapabilitiesEvent")) {
            visitor = new AttachEventShape(visitor);
        }
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private static byte[] readCompiled(String internalName) throws IOException {
        try (InputStream in = LegacyCapabilitySynthetics.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new IOException("missing compiled class " + internalName);
            return in.readAllBytes();
        }
    }

    /**
     * Rebases the attach event onto NeoForge's {@code Event}, the only type its bus accepts as a
     * listener parameter, and gives {@code addCapability} Forge's descriptor. Widening the two
     * parameters is safe because the body only stores them as {@code Object}.
     */
    private static final class AttachEventShape extends ClassVisitor {
        AttachEventShape(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            super.visit(version, access, name, "<T:Ljava/lang/Object;>L" + NEO_EVENT + ";",
                    NEO_EVENT, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            if (name.equals("addCapability")) {
                descriptor = "(Lnet/minecraft/resources/ResourceLocation;L" + PROVIDER + ";)V";
                signature = null;
            }
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!name.equals("<init>")) return next;
            return new MethodVisitor(Opcodes.ASM9, next) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String method, String desc,
                        boolean itf) {
                    if (opcode == INVOKESPECIAL && owner.equals("java/lang/Object") && method.equals("<init>")) {
                        owner = NEO_EVENT;
                    }
                    super.visitMethodInsn(opcode, owner, method, desc, itf);
                }
            };
        }
    }

    private static final String CAP2 = "(L" + CAPABILITY + ";" + DIRECTION + ")L" + LAZY_OPTIONAL + ";";
    private static final String CAP1 = "(L" + CAPABILITY + ";)L" + LAZY_OPTIONAL + ";";

    /** {@code interface ICapabilityProvider { getCapability(cap, side); default getCapability(cap) }}. */
    static byte[] generateProvider() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, PROVIDER, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getCapability", CAP2, null, null).visitEnd();
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "getCapability", CAP1, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKEINTERFACE, PROVIDER, "getCapability", CAP2, true);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Forge's {@code INBTSerializable}: the no-argument form NeoForge replaced with a lookup-taking one. */
    static byte[] generateNbtSerializable() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, NBT_SERIALIZABLE,
                "<T::" + TAG + ">Ljava/lang/Object;", "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "serializeNBT", "()" + TAG, null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "deserializeNBT", "(" + TAG + ")V", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] generateSerializable() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, SERIALIZABLE, null, "java/lang/Object",
                new String[]{PROVIDER, NBT_SERIALIZABLE});
        cw.visitEnd();
        return cw.toByteArray();
    }
}
