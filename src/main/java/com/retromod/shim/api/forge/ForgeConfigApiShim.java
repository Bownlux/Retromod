/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge -> NeoForge config API migration (ForgeConfigSpec, ModConfig, config events).
 */
public class ForgeConfigApiShim implements MinecraftVersionedApiShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ForgeConfigApiShim");

    @Override
    public String getShimName() {
        return "Forge Config API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.19.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "forge";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // redirect targets only exist on NeoForge
        if (!McReflect.isNeoForge()) {
            LOGGER.debug("Skipping Forge → NeoForge config API migration (runtime is not NeoForge)");
            return;
        }

        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec",
            "net/neoforged/neoforge/common/ModConfigSpec"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$Builder",
            "net/neoforged/neoforge/common/ModConfigSpec$Builder"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$ConfigValue",
            "net/neoforged/neoforge/common/ModConfigSpec$ConfigValue"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$BooleanValue",
            "net/neoforged/neoforge/common/ModConfigSpec$BooleanValue"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$IntValue",
            "net/neoforged/neoforge/common/ModConfigSpec$IntValue"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$DoubleValue",
            "net/neoforged/neoforge/common/ModConfigSpec$DoubleValue"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$LongValue",
            "net/neoforged/neoforge/common/ModConfigSpec$LongValue"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$EnumValue",
            "net/neoforged/neoforge/common/ModConfigSpec$EnumValue"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/config/ModConfig",
            "net/neoforged/fml/config/ModConfig"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/config/ModConfig$Type",
            "net/neoforged/fml/config/ModConfig$Type"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/event/config/ModConfigEvent",
            "net/neoforged/fml/event/config/ModConfigEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/event/config/ModConfigEvent$Loading",
            "net/neoforged/fml/event/config/ModConfigEvent$Loading"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/event/config/ModConfigEvent$Reloading",
            "net/neoforged/fml/event/config/ModConfigEvent$Reloading"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/ModLoadingContext",
            "net/neoforged/fml/ModLoadingContext"
        );
        
        // FancyModLoader 12.0.8 (NeoForge 26.3) renamed the config types. COMMON, loaded on each
        // side and never synced, became LOCAL, and SERVER, synced per world, became SYNCED.
        if (!hostConfigTypeHas("COMMON")) {
            String type = "net/neoforged/fml/config/ModConfig$Type";
            transformer.registerFieldRedirect(type, "COMMON", type, "LOCAL");
            transformer.registerFieldRedirect(type, "SERVER", type, "SYNCED");
        }

        // Forge's IConfigSpec was generic; NeoForge's is not, and ModConfigSpec implements it.
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/config/IConfigSpec",
            NEO_CONFIG_SPEC
        );

        // NeoForge moved registerConfig from ModLoadingContext to ModContainer, so a Forge mod's
        // ModLoadingContext.get().registerConfig(type, spec) failed with NoSuchMethodError in its
        // constructor. The bridge forwards to the active container, which FML sets while the mod
        // constructs. The keys use NeoForge names because the class moves above apply first.
        transformer.registerSyntheticClass(CONFIG_BRIDGE, generateConfigBridge());
        transformer.registerMethodRedirect(
            NEO_LOADING_CONTEXT, "registerConfig", "(" + L_TYPE + L_SPEC + ")V",
            CONFIG_BRIDGE, "registerConfig", "(" + L_CONTEXT + L_TYPE + L_SPEC + ")V", true);
        transformer.registerMethodRedirect(
            NEO_LOADING_CONTEXT, "registerConfig", "(" + L_TYPE + L_SPEC + "Ljava/lang/String;)V",
            CONFIG_BRIDGE, "registerConfig",
            "(" + L_CONTEXT + L_TYPE + L_SPEC + "Ljava/lang/String;)V", true);
    }

    /** Whether the running FancyModLoader's ModConfig.Type declares this constant. */
    private static boolean hostConfigTypeHas(String constant) {
        Class<?> type;
        try {
            type = Class.forName("net.neoforged.fml.config.ModConfig$Type", false,
                    ForgeConfigApiShim.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError unknownHost) {
            // Offline: keep the names every loader before 12.0.8 used.
            return true;
        }
        try {
            type.getField(constant);
            return true;
        } catch (NoSuchFieldException renamed) {
            return false;
        }
    }

    static final String CONFIG_BRIDGE = "com/retromod/shim/forge/embedded/ForgeConfigBridge";
    private static final String NEO_LOADING_CONTEXT = "net/neoforged/fml/ModLoadingContext";
    private static final String NEO_CONFIG_SPEC = "net/neoforged/fml/config/IConfigSpec";
    private static final String MOD_CONTAINER = "net/neoforged/fml/ModContainer";
    private static final String L_CONTEXT = "L" + NEO_LOADING_CONTEXT + ";";
    private static final String L_TYPE = "Lnet/neoforged/fml/config/ModConfig$Type;";
    private static final String L_SPEC = "L" + NEO_CONFIG_SPEC + ";";

    /** {@code registerConfig(context, type, spec[, fileName])}: context.getActiveContainer().registerConfig(...). */
    static byte[] generateConfigBridge() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, CONFIG_BRIDGE, null,
                "java/lang/Object", null);
        emitForward(cw, L_TYPE + L_SPEC, 2);
        emitForward(cw, L_TYPE + L_SPEC + "Ljava/lang/String;", 3);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitForward(ClassWriter cw, String params, int paramCount) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerConfig",
                "(" + L_CONTEXT + params + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NEO_LOADING_CONTEXT, "getActiveContainer",
                "()L" + MOD_CONTAINER + ";", false);
        for (int i = 1; i <= paramCount; i++) m.visitVarInsn(Opcodes.ALOAD, i);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOD_CONTAINER, "registerConfig",
                "(" + params + ")V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {};
    }
}
