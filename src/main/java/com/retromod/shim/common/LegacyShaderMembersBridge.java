/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.function.Function;

/** Old shader program and uniform members, kept linking on 26.x where the values they set are dropped. */
public final class LegacyShaderMembersBridge {

    static final String UNIFORM = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyUniform";
    static final String PROGRAMS = "com/retromod/generated/LegacyShaderPrograms";
    static final String LEGACY_SHADER = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyShaderInstance";
    static final String SHADER_INSTANCE = "net/minecraft/client/renderer/ShaderInstance";
    static final String OLD_ABSTRACT_UNIFORM = "com/mojang/blaze3d/shaders/AbstractUniform";
    static final String OLD_UNIFORM = "com/mojang/blaze3d/shaders/Uniform";
    static final String BLAZE_PROGRAM = "com/mojang/blaze3d/opengl/GlProgram";
    static final String PEARL_PROGRAM = "com/mojang/renderpearl/backend/opengl/GlProgram";
    static final String OBJECT = "Ljava/lang/Object;";
    static final String STRING = "Ljava/lang/String;";
    /** The uniform fields 1.21.1's ShaderInstance had for the values every vanilla shader shares. */
    static final List<String> UNIFORM_FIELDS = List.of("MODEL_VIEW_MATRIX", "PROJECTION_MATRIX", "INVERSE_VIEW_ROTATION_MATRIX", "TEXTURE_MATRIX", "SCREEN_SIZE",
            "COLOR_MODULATOR", "LIGHT0_DIRECTION", "LIGHT1_DIRECTION", "GLINT_ALPHA", "FOG_START", "FOG_END", "FOG_COLOR",
            "FOG_SHAPE", "LINE_WIDTH", "GAME_TIME", "CHUNK_OFFSET");
    /** The old uniform setters a Fabric mod calls on what is now the host's uniform interface. */
    static final String[] UNIFORM_SETTERS = {"(F)V", "(FF)V", "(FFF)V", "(FFFF)V", "(I)V", "([F)V", "(Lorg/joml/Matrix4f;)V",
            "(Lorg/joml/Matrix3f;)V"};

    private LegacyShaderMembersBridge() {}

    /** Registers the members the host's program lacks; from the 26.1 shims, where every host lost the old shader classes. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean client = host.apply(HostBuffers.CLIENT_ANCHOR) != null;
        if (client ? host.apply(OLD_ABSTRACT_UNIFORM) != null : !targetAtLeast("1.21.5")) return;
        // 26.3 moved the GL backend into renderpearl.
        boolean pearl = client ? host.apply(PEARL_PROGRAM) != null : targetAtLeast("26.3");
        String program = pearl ? PEARL_PROGRAM : BLAZE_PROGRAM;
        String hostUniform = pearl ? "com/mojang/renderpearl/backend/opengl/Uniform" : "com/mojang/blaze3d/opengl/Uniform";
        String format = pearl ? "com/mojang/renderpearl/api/vertex/VertexFormat" : "com/mojang/blaze3d/vertex/VertexFormat";
        ClassNode hostProgram = host.apply(program);

        RebuiltApiClasses.registerTree(t, UNIFORM);
        t.registerClassRedirect(OLD_ABSTRACT_UNIFORM, UNIFORM);
        t.registerClassRedirect(OLD_UNIFORM, UNIFORM);
        // Forge's chain never renamed ShaderInstance, so a Forge target gets a stand-in that keeps the old constructors for
        // subclasses. The target loader decides, as it is the same in the game, the CLI and AOT.
        boolean standIn = host.apply(SHADER_INSTANCE) == null && McReflect.isForgeTarget();
        if (standIn) {
            RebuiltApiClasses.registerTree(t, LEGACY_SHADER);
            t.registerClassRedirect(SHADER_INSTANCE, LEGACY_SHADER);
            // A game call that still takes the old shader, such as RenderSystem.setupShaderLights, is dropped.
            t.registerStandInType(LEGACY_SHADER, null);
        }

        String inert = "(" + OBJECT + STRING + ")L" + UNIFORM + ";";
        for (String owner : new String[]{program, SHADER_INSTANCE}) {
            for (String type : new String[]{OLD_ABSTRACT_UNIFORM, OLD_UNIFORM, UNIFORM}) {
                t.registerMethodRedirect(owner, "safeGetUniform", "(" + STRING + ")L" + type + ";", UNIFORM, "inert", inert, true);
                t.registerMethodRedirect(owner, "getUniform", "(" + STRING + ")L" + type + ";", UNIFORM, "inert", inert, true);
                for (String field : UNIFORM_FIELDS) {
                    t.registerExactFieldRedirect(owner, field, "L" + type + ";", UNIFORM, "inertField", "(" + OBJECT + ")L" + UNIFORM + ";");
                }
            }
            // A Fabric mod's old uniform type is the host's uniform interface, which only a null satisfies.
            String hostGetter = "(" + STRING + ")L" + hostUniform + ";";
            if (lacks(hostProgram, "getUniform", hostGetter)) {
                t.registerMethodRedirect(owner, "getUniform", hostGetter, UNIFORM, "none", "(" + OBJECT + STRING + ")" + OBJECT, true);
            }
            for (String field : UNIFORM_FIELDS) {
                t.registerExactFieldRedirect(owner, field, "L" + hostUniform + ";", UNIFORM, "noField", "(" + OBJECT + ")" + OBJECT);
            }
            // The program was bound and its samplers set for an immediate draw, which 26.x skips.
            for (String[] call : new String[][]{{"apply", "()V"}, {"clear", "()V"}, {"attachToProgram", "()V"},
                    {"setSampler", "(" + STRING + OBJECT + ")V"}}) {
                if (lacks(hostProgram, call[0], call[1])) t.registerRemovedMethodNeutralize(owner, call[0], call[1]);
            }
            // The old program parts and parsed uniform list, which shader mods read, have no counterpart.
            for (String part : new String[]{"getVertexProgram", "getFragmentProgram"}) {
                t.registerMethodRedirect(owner, part, "()Lcom/mojang/blaze3d/shaders/Program;", UNIFORM, "noProgram", "(" + OBJECT + ")" + OBJECT, true);
            }
            t.registerFieldRedirect(owner, "uniforms", "Ljava/util/List;", UNIFORM, "noUniforms", "(" + OBJECT + ")Ljava/util/List;");
            if (lacks(hostProgram, "getId", "()I")) {
                t.registerMethodRedirect(owner, "getId", "()I", UNIFORM, "programId", "(" + OBJECT + ")I", true);
            }
        }
        registerUniformSetters(t, host.apply(hostUniform), hostUniform);
        // The stand-in builds itself, so only a host program's constructor goes to the factory.
        registerConstructors(t, program, format, standIn ? new String[]{program} : new String[]{program, SHADER_INSTANCE});
    }

    /** The old setters on the host's uniform interface, which has none, are dropped through static forms. */
    static void registerUniformSetters(RetromodTransformer t, ClassNode hostUniform, String owner) {
        for (String setter : UNIFORM_SETTERS) {
            if (!lacks(hostUniform, "set", setter)) continue;
            t.registerMethodRedirect(owner, "set", setter, UNIFORM, "set", "(" + OBJECT + setter.substring(1), true);
        }
        if (lacks(hostUniform, "upload", "()V")) t.registerMethodRedirect(owner, "upload", "()V", UNIFORM, "upload", "(" + OBJECT + ")V", true);
        if (lacks(hostUniform, "getLocation", "()I")) {
            t.registerMethodRedirect(owner, "getLocation", "()I", UNIFORM, "getLocation", "(" + OBJECT + ")I", true);
        }
    }

    /** {@code new ShaderInstance(provider, name, format)}: no program, as 26.x links shaders only for its own pipelines. */
    static void registerConstructors(RetromodTransformer t, String program, String format, String[] owners) {
        t.registerSyntheticClass(PROGRAMS, generatePrograms(program));
        String factory = "(" + OBJECT + OBJECT + OBJECT + ")L" + program + ";";
        for (String owner : owners) {
            for (String name : new String[]{STRING, "Lnet/minecraft/resources/Identifier;", "Lnet/minecraft/resources/ResourceLocation;"}) {
                for (String vertexFormat : new String[]{format, "com/mojang/blaze3d/vertex/VertexFormat"}) {
                    t.registerConstructorRedirect(owner, "(Lnet/minecraft/server/packs/resources/ResourceProvider;" + name + "L"
                            + vertexFormat + ";)V", PROGRAMS, "create", factory);
                }
            }
        }
    }

    /** {@code static GlProgram create(Object provider, Object name, Object format)}, answering null. */
    static byte[] generatePrograms(String program) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, PROGRAMS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "(" + OBJECT + OBJECT + OBJECT + ")L" + program + ";", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // An unread class lacks everything, so an offline run registers by the target alone.
    private static boolean lacks(ClassNode owner, String name, String desc) {
        if (owner == null) return true;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return false;
        }
        return true;
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
