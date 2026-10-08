/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.util.McReflect;
import com.retromod.shim.forge.registry.LegacyRegistryAccess;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Bridges Forge 1.20.1 custom registries onto NeoForge.
 *
 * <p>A Forge mod creates its own registry through {@code NewRegistryEvent.create(RegistryBuilder)}
 * or {@code DeferredRegister.makeRegistry(Supplier<RegistryBuilder>)} and reads it back as an
 * {@code IForgeRegistry}. NeoForge kept the event but builds a vanilla {@code Registry} from a
 * keyed builder, and it deleted {@code IForgeRegistry} and Forge's registry-id buffer methods.
 *
 * <p>Forge's builder becomes {@link com.retromod.shim.forge.registry.LegacyRegistryBuilder},
 * which creates the registry through NeoForge. Every {@code IForgeRegistry} the mod then holds is
 * a vanilla registry: the remaining {@code IForgeRegistry} calls go to
 * {@link LegacyRegistryAccess}, and casts to {@code IForgeRegistry} are retargeted to
 * {@code Registry}, because nothing implements the stand-in marker interface. Forge's registry
 * callbacks, saving and override switches are accepted and ignored.
 */
public final class LegacyCustomRegistryBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ForgeRegistries");

    static final String FORGE_BUILDER = "net/minecraftforge/registries/RegistryBuilder";
    static final String IFORGE_REGISTRY = "net/minecraftforge/registries/IForgeRegistry";
    static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String COMPILED_BUILDER = "com/retromod/shim/forge/registry/LegacyRegistryBuilder";
    private static final String ACCESS = "com/retromod/shim/forge/registry/LegacyRegistryAccess";

    private static final String L_BUILDER = "L" + FORGE_BUILDER + ";";
    private static final String L_LOCATION = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String L_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String OPTIONAL = "Ljava/util/Optional;";
    private static final String SUPPLIER = "Ljava/util/function/Supplier;";
    private static final String BUFFER = "net/minecraft/network/FriendlyByteBuf";

    /** Stand-in methods whose {@code Object} parameter is retyped to Forge's declared type. */
    private static final Map<String, String> FORGE_DESCRIPTORS = Map.ofEntries(
            Map.entry("of(Ljava/lang/Object;)", "(" + L_LOCATION + ")" + L_BUILDER),
            Map.entry("setName(Ljava/lang/Object;)", "(" + L_LOCATION + ")" + L_BUILDER),
            Map.entry("setDefaultKey(Ljava/lang/Object;)", "(" + L_LOCATION + ")" + L_BUILDER),
            Map.entry("legacyName(Ljava/lang/Object;)", "(" + L_LOCATION + ")" + L_BUILDER),
            Map.entry("onAdd(Ljava/lang/Object;)", callbackDescriptor("AddCallback")),
            Map.entry("onClear(Ljava/lang/Object;)", callbackDescriptor("ClearCallback")),
            Map.entry("onCreate(Ljava/lang/Object;)", callbackDescriptor("CreateCallback")),
            Map.entry("onValidate(Ljava/lang/Object;)", callbackDescriptor("ValidateCallback")),
            Map.entry("onBake(Ljava/lang/Object;)", callbackDescriptor("BakeCallback")),
            Map.entry("missing(Ljava/lang/Object;)", callbackDescriptor("MissingFactory")));

    private LegacyCustomRegistryBridge() {}

    /**
     * NeoForge 1.21 and newer, where {@link ForgeNeoForgeApiBridge} registers this bridge. Older
     * NeoForge hosts keep the plain builder rename in {@code ForgeRegistryApiShim}.
     */
    public static boolean handlesHost() {
        return McReflect.isNeoForge()
                && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21") >= 0;
    }

    /** Called on a NeoForge host from {@link ForgeNeoForgeApiBridge}. */
    public static void register(RetromodTransformer transformer) {
        byte[] builder;
        try {
            builder = forgeShapedBuilder();
        } catch (IOException e) {
            LOGGER.warn("Could not register the Forge custom registry bridge: {}", e.toString());
            return;
        }
        transformer.registerSyntheticClass(FORGE_BUILDER, builder);
        SyntheticEmbedder.registerClassResource(transformer, ACCESS, LegacyRegistryAccess.class);

        transformer.registerClassRedirect("net/minecraftforge/registries/NewRegistryEvent",
                "net/neoforged/neoforge/registries/NewRegistryEvent");
        transformer.registerMethodRedirect("net/minecraftforge/registries/NewRegistryEvent", "create",
                "(" + L_BUILDER + ")" + SUPPLIER,
                FORGE_BUILDER, "retromod$create", "(" + OBJECT + L_BUILDER + ")" + SUPPLIER, true);
        transformer.registerMethodRedirect("net/minecraftforge/registries/NewRegistryEvent", "create",
                "(" + L_BUILDER + "Ljava/util/function/Consumer;)" + SUPPLIER,
                FORGE_BUILDER, "retromod$create",
                "(" + OBJECT + L_BUILDER + "Ljava/util/function/Consumer;)" + SUPPLIER, true);
        transformer.registerMethodRedirect("net/minecraftforge/registries/DeferredRegister",
                "makeRegistry", "(" + SUPPLIER + ")" + SUPPLIER,
                FORGE_BUILDER, "retromod$makeRegistry", "(" + OBJECT + SUPPLIER + ")" + SUPPLIER, true);

        registerRegistryCalls(transformer);
        registerBufferCalls(transformer);
        LOGGER.debug("Bridged Forge custom registries onto NeoForge registries");
    }

    /** True once {@link #register} ran, which gates the cast adapter. */
    public static boolean isRegistered(RetromodTransformer transformer) {
        return transformer.getSyntheticClasses().containsKey(FORGE_BUILDER);
    }

    private static void registerRegistryCalls(RetromodTransformer t) {
        access(t, "getHolder", "(" + OBJECT + ")" + OPTIONAL, "holderOf", OPTIONAL);
        access(t, "getDelegate", "(" + OBJECT + ")" + OPTIONAL, "holderOf", OPTIONAL);
        access(t, "getDelegateOrThrow", "(" + OBJECT + ")Lnet/minecraft/core/Holder$Reference;",
                "holderOfOrThrow", OBJECT);
        for (String key : new String[] {L_LOCATION, L_KEY}) {
            access(t, "getHolder", "(" + key + ")" + OPTIONAL, "holderByKey", OPTIONAL);
            access(t, "getDelegate", "(" + key + ")" + OPTIONAL, "holderByKey", OPTIONAL);
            access(t, "getDelegateOrThrow", "(" + key + ")Lnet/minecraft/core/Holder$Reference;",
                    "holderByKeyOrThrow", OBJECT);
        }
        access(t, "getRegistryName", "()" + L_LOCATION, "registryName", OBJECT);
        access(t, "getRegistryKey", "()" + L_KEY, "registryKey", OBJECT);
        access(t, "getResourceKey", "(" + OBJECT + ")" + OPTIONAL, "resourceKey", OPTIONAL);
        access(t, "getDefaultKey", "()" + L_LOCATION, "defaultKey", OBJECT);
        access(t, "containsValue", "(" + OBJECT + ")Z", "containsValue", "Z");
        access(t, "isEmpty", "()Z", "isEmpty", "Z");
        access(t, "getCodec", "()Lcom/mojang/serialization/Codec;", "codec", OBJECT);
        access(t, "iterator", "()Ljava/util/Iterator;", "iterator", "Ljava/util/Iterator;");
    }

    private static void registerBufferCalls(RetromodTransformer t) {
        String l_registry = "L" + IFORGE_REGISTRY + ";";
        buffer(t, "writeRegistryId", "(" + l_registry + OBJECT + ")V",
                "writeRegistryId", "(" + OBJECT + OBJECT + OBJECT + ")V");
        buffer(t, "readRegistryId", "()" + OBJECT, "readRegistryId", "(" + OBJECT + ")" + OBJECT);
        buffer(t, "readRegistryIdSafe", "(Ljava/lang/Class;)" + OBJECT,
                "readRegistryIdSafe", "(" + OBJECT + "Ljava/lang/Class;)" + OBJECT);
        buffer(t, "writeRegistryIdUnsafe", "(" + l_registry + OBJECT + ")V",
                "writeRegistryIdUnsafe", "(" + OBJECT + OBJECT + OBJECT + ")V");
        buffer(t, "writeRegistryIdUnsafe", "(" + l_registry + L_LOCATION + ")V",
                "writeRegistryIdUnsafeByName", "(" + OBJECT + OBJECT + OBJECT + ")V");
        buffer(t, "readRegistryIdUnsafe", "(" + l_registry + ")" + OBJECT,
                "readRegistryIdUnsafe", "(" + OBJECT + OBJECT + ")" + OBJECT);
    }

    /**
     * {@code IForgeRegistry.<name>} instance call to a static {@link LegacyRegistryAccess} method
     * that takes the registry first. Object-typed results are cast back by the transformer.
     */
    private static void access(RetromodTransformer t, String name, String forgeDesc,
                               String helper, String helperReturn) {
        String args = forgeDesc.substring(1, forgeDesc.indexOf(')'));
        String helperArgs = args.isEmpty() ? OBJECT : OBJECT + OBJECT;
        t.registerMethodRedirect(IFORGE_REGISTRY, name, forgeDesc,
                ACCESS, helper, "(" + helperArgs + ")" + helperReturn, true);
    }

    private static void buffer(RetromodTransformer t, String name, String forgeDesc,
                               String helper, String helperDesc) {
        t.registerMethodRedirect(BUFFER, name, forgeDesc, ACCESS, helper, helperDesc, true);
    }

    private static String callbackDescriptor(String callback) {
        return "(L" + IFORGE_REGISTRY + "$" + callback + ";)" + L_BUILDER;
    }

    /** The compiled stand-in renamed to Forge's builder, with Forge's parameter types. */
    static byte[] forgeShapedBuilder() throws IOException {
        byte[] compiled;
        try (InputStream in = LegacyCustomRegistryBridge.class.getClassLoader()
                .getResourceAsStream(COMPILED_BUILDER + ".class")) {
            if (in == null) throw new IOException("missing compiled class " + COMPILED_BUILDER);
            compiled = in.readAllBytes();
        }
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor retype = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                             String[] exceptions) {
                String forgeDesc = FORGE_DESCRIPTORS.get(name + desc.substring(0, desc.indexOf(')') + 1));
                // Generic signatures would still name Object; the descriptor is what links.
                return forgeDesc == null
                        ? super.visitMethod(access, name, desc, signature, exceptions)
                        : super.visitMethod(access, name, forgeDesc, null, exceptions);
            }
        };
        ClassVisitor rename = new ClassRemapper(retype,
                new SimpleRemapper(Map.of(COMPILED_BUILDER, FORGE_BUILDER)));
        new ClassReader(compiled).accept(rename, 0);
        return writer.toByteArray();
    }

    /**
     * Retargets {@code CHECKCAST} and {@code INSTANCEOF IForgeRegistry} to {@code Registry}. The
     * stand-in interface has no implementations, so a cast to it always failed, while the value
     * behind it is now always a vanilla registry. Frames are kept: the verifier accepts any
     * reference where an interface type is expected.
     */
    public static byte[] retargetRegistryCasts(byte[] classBytes) {
        if (!new String(classBytes, StandardCharsets.ISO_8859_1).contains(IFORGE_REGISTRY)) {
            return classBytes;
        }
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                             String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, name, desc, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if ((opcode == Opcodes.CHECKCAST || opcode == Opcodes.INSTANCEOF)
                                && type.equals(IFORGE_REGISTRY)) {
                            changed[0] = true;
                            type = REGISTRY;
                        }
                        super.visitTypeInsn(opcode, type);
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : classBytes;
    }
}
