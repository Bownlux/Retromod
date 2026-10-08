/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/**
 * Follows Forge Config API Port from its {@code neoforge.v4} API to the {@code v5} API.
 *
 * <p>The port replaced {@code fuzs.forgeconfigapiport.fabric.api.neoforge.v4} with
 * {@code fuzs.forgeconfigapiport.fabric.api.v5} in its first Minecraft 1.21.5 build, and the old
 * package is gone from every later release. A mod built for 1.21.4 or older registers its config in
 * {@code onInitialize}, so on a newer host it dies there with
 * {@code NoClassDefFoundError: fuzs/forgeconfigapiport/fabric/api/neoforge/v4/NeoForgeConfigRegistry}
 * (#249, Sophisticated Core and Sophisticated Backpacks).
 *
 * <p>The event types moved with identical shapes, so they are plain class redirects. The registry
 * did not: v4's {@code register} returned the {@code ModConfig} and v5's returns nothing. Mods keep
 * that return value, so the registry is replaced by a generated stand-in with the v4 shape. It calls
 * {@code ConfigTracker.registerConfig}, which is exactly what the v4 implementation did and which
 * every v5 release still ships.
 *
 * <p>The port's {@code forge.v4} API is not bridged. Its {@code register} returns the Forge-side
 * {@code ModConfig}, and v5 no longer ships that class at all.
 */
public class ForgeConfigApiPortV5Shim implements MinecraftVersionedApiShim {

    private static final String V4 = "fuzs/forgeconfigapiport/fabric/api/neoforge/v4/";
    private static final String V5 = "fuzs/forgeconfigapiport/fabric/api/v5/";

    static final String OLD_REGISTRY = V4 + "NeoForgeConfigRegistry";

    /** Stand-in for the v4 registry interface, embedded per mod. */
    public static final String REGISTRY = "com/retromod/shim/api/fabric/embedded/LegacyNeoForgeConfigRegistry";
    /** Its implementation, which forwards to {@code ConfigTracker}. */
    public static final String REGISTRY_IMPL = REGISTRY + "Impl";

    private static final String MOD_CONFIG = "net/neoforged/fml/config/ModConfig";
    private static final String CONFIG_TYPE = MOD_CONFIG + "$Type";
    private static final String CONFIG_SPEC = "net/neoforged/fml/config/IConfigSpec";
    private static final String CONFIG_TRACKER = "net/neoforged/fml/config/ConfigTracker";

    private static final String REGISTER_DESC =
            "(Ljava/lang/String;L" + CONFIG_TYPE + ";L" + CONFIG_SPEC + ";)L" + MOD_CONFIG + ";";
    private static final String REGISTER_WITH_FILE_DESC =
            "(Ljava/lang/String;L" + CONFIG_TYPE + ";L" + CONFIG_SPEC + ";Ljava/lang/String;)L"
                    + MOD_CONFIG + ";";

    /** The v4 classes whose v5 counterparts kept the same members. */
    private static final Map<String, String> SAME_SHAPE_MOVES = Map.of(
            V4 + "NeoForgeModConfigEvents", V5 + "ModConfigEvents",
            V4 + "NeoForgeModConfigEvents$Loading", V5 + "ModConfigEvents$Loading",
            V4 + "NeoForgeModConfigEvents$Reloading", V5 + "ModConfigEvents$Reloading",
            V4 + "NeoForgeModConfigEvents$Unloading", V5 + "ModConfigEvents$Unloading",
            V4 + "client/ConfigScreenFactoryRegistry", V5 + "client/ConfigScreenFactoryRegistry");

    @Override
    public String getShimName() {
        return "Forge Config API Port v4 to v5";
    }

    @Override
    public String getSourceVersion() {
        return "1.21.4";
    }

    /**
     * The first Minecraft version whose Forge Config API Port carries only the v5 API. Below it the
     * host's port still ships v4, so nothing is rewritten.
     */
    @Override
    public String getTargetVersion() {
        return "1.21.5";
    }

    @Override
    public String getModLoaderType() {
        return "fabric";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(REGISTRY, generateRegistry());
        transformer.registerSyntheticClass(REGISTRY_IMPL, generateRegistryImpl());
        transformer.registerClassRedirect(OLD_REGISTRY, REGISTRY);
        SAME_SHAPE_MOVES.forEach(transformer::registerClassRedirect);
    }

    /** The v4 classes that move onto v5 unchanged, for tests and reporting. */
    public static Map<String, String> sameShapeMoves() {
        return SAME_SHAPE_MOVES;
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }

    /** The v4 {@code NeoForgeConfigRegistry} shape: an interface with a static INSTANCE. */
    public static byte[] generateRegistry() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, REGISTRY, null,
                "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "INSTANCE", "L" + REGISTRY + ";",
                null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "register", REGISTER_DESC, null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "register", REGISTER_WITH_FILE_DESC, null, null)
                .visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(NEW, REGISTRY_IMPL);
        init.visitInsn(DUP);
        init.visitMethodInsn(INVOKESPECIAL, REGISTRY_IMPL, "<init>", "()V", false);
        init.visitFieldInsn(PUTSTATIC, REGISTRY, "INSTANCE", "L" + REGISTRY + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Forwards both {@code register} overloads to {@code ConfigTracker.INSTANCE.registerConfig}. */
    public static byte[] generateRegistryImpl() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, REGISTRY_IMPL, null,
                "java/lang/Object", new String[] {REGISTRY});

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        emitForward(cw, REGISTER_DESC, false);
        emitForward(cw, REGISTER_WITH_FILE_DESC, true);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** register(modId, type, spec[, fileName]) becomes registerConfig(type, spec, modId[, fileName]). */
    private static void emitForward(ClassWriter cw, String desc, boolean withFileName) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "register", desc, null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, CONFIG_TRACKER, "INSTANCE", "L" + CONFIG_TRACKER + ";");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 1);
        String trackerDesc;
        if (withFileName) {
            mv.visitVarInsn(ALOAD, 4);
            trackerDesc = "(L" + CONFIG_TYPE + ";L" + CONFIG_SPEC
                    + ";Ljava/lang/String;Ljava/lang/String;)L" + MOD_CONFIG + ";";
        } else {
            trackerDesc = "(L" + CONFIG_TYPE + ";L" + CONFIG_SPEC + ";Ljava/lang/String;)L"
                    + MOD_CONFIG + ";";
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, CONFIG_TRACKER, "registerConfig", trackerDesc, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
