/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft-fail bridge for Fabric API's removed renderer-material subsystem
 * ({@code RenderMaterial}, {@code MaterialFinder}, {@code BlendMode}, {@code Renderer},
 * {@code RendererAccess}, dropped around the 0.110 relocation). We inject inert stand-ins under
 * {@code com/retromod/generated/legacyrenderer/*} and redirect the old paths to them so references
 * resolve at load time; the stub methods leave custom rendering dark but the client launches.
 */
public class FabricRendererMaterialBridge implements AuxiliaryVersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String OLD_RENDERER_ACCESS  = "net/fabricmc/fabric/api/renderer/v1/RendererAccess";
    private static final String OLD_RENDERER         = "net/fabricmc/fabric/api/renderer/v1/Renderer";
    private static final String OLD_MATERIAL_FINDER  = "net/fabricmc/fabric/api/renderer/v1/material/MaterialFinder";
    private static final String OLD_RENDER_MATERIAL  = "net/fabricmc/fabric/api/renderer/v1/material/RenderMaterial";
    private static final String OLD_BLEND_MODE       = "net/fabricmc/fabric/api/renderer/v1/material/BlendMode";
    private static final String OLD_MESH_BUILDER     = "net/fabricmc/fabric/api/renderer/v1/mesh/MeshBuilder";
    private static final String OLD_RENDER_CONTEXT   = "net/fabricmc/fabric/api/renderer/v1/render/RenderContext";
    private static final String OLD_QUAD_TRANSFORM   = "net/fabricmc/fabric/api/renderer/v1/render/RenderContext$QuadTransform";
    private static final String OLD_SPRITE_FINDER    = "net/fabricmc/fabric/api/renderer/v1/model/SpriteFinder";
    private static final String OLD_FORWARDING_BAKED = "net/fabricmc/fabric/api/renderer/v1/model/ForwardingBakedModel";

    private static final String NEW_RENDERER_ACCESS  = "com/retromod/generated/legacyrenderer/RendererAccess";
    private static final String NEW_RENDERER         = "com/retromod/generated/legacyrenderer/Renderer";
    private static final String NEW_MATERIAL_FINDER  = "com/retromod/generated/legacyrenderer/MaterialFinder";
    private static final String NEW_RENDER_MATERIAL  = "com/retromod/generated/legacyrenderer/RenderMaterial";
    private static final String NEW_BLEND_MODE       = "com/retromod/generated/legacyrenderer/BlendMode";
    private static final String IMPL_ACCESS          = "com/retromod/generated/legacyrenderer/RendererAccessImpl";
    private static final String IMPL_RENDERER        = "com/retromod/generated/legacyrenderer/RendererImpl";
    private static final String IMPL_MATERIAL_FINDER = "com/retromod/generated/legacyrenderer/MaterialFinderImpl";
    private static final String IMPL_RENDER_MATERIAL = "com/retromod/generated/legacyrenderer/RenderMaterialImpl";
    private static final String NEW_MESH_BUILDER     = "com/retromod/generated/legacyrenderer/MeshBuilder";
    private static final String NEW_RENDER_CONTEXT   = "com/retromod/generated/legacyrenderer/RenderContext";
    private static final String NEW_QUAD_TRANSFORM   = "com/retromod/generated/legacyrenderer/RenderContext$QuadTransform";
    private static final String NEW_SPRITE_FINDER    = "com/retromod/generated/legacyrenderer/SpriteFinder";
    private static final String NEW_FORWARDING_BAKED = "com/retromod/generated/legacyrenderer/ForwardingBakedModel";

    private static final String L_RENDERER_ACCESS  = "L" + NEW_RENDERER_ACCESS + ";";
    private static final String L_RENDERER         = "L" + NEW_RENDERER + ";";
    private static final String L_MATERIAL_FINDER  = "L" + NEW_MATERIAL_FINDER + ";";
    private static final String L_RENDER_MATERIAL  = "L" + NEW_RENDER_MATERIAL + ";";
    private static final String L_BLEND_MODE       = "L" + NEW_BLEND_MODE + ";";

    // 1.16-era order; ordinals must match what old jars saw
    private static final String[] BLEND_MODE_CONSTANTS = {
        "DEFAULT", "SOLID", "CUTOUT", "CUTOUT_MIPPED", "TRANSLUCENT",
    };

    @Override public String getShimName() { return "Fabric Renderer Material API Soft-Fail"; }
    @Override public String getSourceVersion() { return "0.50.0"; }
    @Override public String getTargetVersion() { return "0.110.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(NEW_BLEND_MODE,       generateBlendMode());
        transformer.registerSyntheticClass(NEW_RENDER_MATERIAL,  generateMarkerInterface(NEW_RENDER_MATERIAL));
        transformer.registerSyntheticClass(NEW_MATERIAL_FINDER,  generateMaterialFinderInterface());
        transformer.registerSyntheticClass(NEW_RENDERER,         generateRendererInterface());
        transformer.registerSyntheticClass(NEW_RENDERER_ACCESS,  generateRendererAccessInterface());

        transformer.registerSyntheticClass(IMPL_RENDER_MATERIAL, generateRenderMaterialImpl());
        transformer.registerSyntheticClass(IMPL_MATERIAL_FINDER, generateMaterialFinderImpl());
        transformer.registerSyntheticClass(IMPL_RENDERER,        generateRendererImpl());
        transformer.registerSyntheticClass(IMPL_ACCESS,          generateRendererAccessImpl());

        // Marker stubs for the removed types: enough shape to resolve at class-load;
        // a runtime call on one throws AbstractMethodError.
        transformer.registerSyntheticClass(NEW_MESH_BUILDER,     generateMarkerInterface(NEW_MESH_BUILDER));
        transformer.registerSyntheticClass(NEW_RENDER_CONTEXT,   generateMarkerInterface(NEW_RENDER_CONTEXT));
        transformer.registerSyntheticClass(NEW_QUAD_TRANSFORM,   generateMarkerInterface(NEW_QUAD_TRANSFORM));
        transformer.registerSyntheticClass(NEW_SPRITE_FINDER,    generateMarkerInterface(NEW_SPRITE_FINDER));
        // ForwardingBakedModel was subclassed by mods, so a concrete class, not an interface
        transformer.registerSyntheticClass(NEW_FORWARDING_BAKED, generateEmptyClass(NEW_FORWARDING_BAKED));

        transformer.registerClassRedirect(OLD_BLEND_MODE,      NEW_BLEND_MODE);
        transformer.registerClassRedirect(OLD_RENDER_MATERIAL, NEW_RENDER_MATERIAL);
        transformer.registerClassRedirect(OLD_MATERIAL_FINDER, NEW_MATERIAL_FINDER);
        transformer.registerClassRedirect(OLD_RENDERER,        NEW_RENDERER);
        transformer.registerClassRedirect(OLD_RENDERER_ACCESS, NEW_RENDERER_ACCESS);
        transformer.registerClassRedirect(OLD_MESH_BUILDER,     NEW_MESH_BUILDER);
        transformer.registerClassRedirect(OLD_RENDER_CONTEXT,   NEW_RENDER_CONTEXT);
        transformer.registerClassRedirect(OLD_QUAD_TRANSFORM,   NEW_QUAD_TRANSFORM);
        transformer.registerClassRedirect(OLD_SPRITE_FINDER,    NEW_SPRITE_FINDER);
        transformer.registerClassRedirect(OLD_FORWARDING_BAKED, NEW_FORWARDING_BAKED);

        LOGGER.warn("Loaded support for the removed Fabric renderer material API. Mods can start, "
                + "but custom rendering through that API will be inactive.");
    }

    private static byte[] generateEmptyClass(String name) {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        emitDefaultCtor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateBlendMode() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_ENUM,
                NEW_BLEND_MODE,
                "Ljava/lang/Enum<" + L_BLEND_MODE + ">;",
                "java/lang/Enum", null);

        for (String name : BLEND_MODE_CONSTANTS) {
            cw.visitField(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                    name, L_BLEND_MODE, null, null).visitEnd();
        }
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "$VALUES", "[" + L_BLEND_MODE, null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>",
                "(Ljava/lang/String;I)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitVarInsn(Opcodes.ILOAD, 2);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>",
                "(Ljava/lang/String;I)V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        emitEnumValues(cw, NEW_BLEND_MODE, L_BLEND_MODE);
        emitEnumValueOf(cw, NEW_BLEND_MODE, L_BLEND_MODE);
        emitEnumClinit(cw, NEW_BLEND_MODE, L_BLEND_MODE, BLEND_MODE_CONSTANTS);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateMarkerInterface(String name) {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateMaterialFinderInterface() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                NEW_MATERIAL_FINDER, null, "java/lang/Object", null);
        abstractMethod(cw, "clear",      "()" + L_MATERIAL_FINDER);
        abstractMethod(cw, "blendMode",  "(" + L_BLEND_MODE + ")" + L_MATERIAL_FINDER);
        abstractMethod(cw, "blendMode",  "(I" + L_BLEND_MODE + ")" + L_MATERIAL_FINDER);
        abstractMethod(cw, "disableAo",  "(IZ)" + L_MATERIAL_FINDER);
        abstractMethod(cw, "disableColorIndex", "(IZ)" + L_MATERIAL_FINDER);
        abstractMethod(cw, "disableDiffuse",    "(IZ)" + L_MATERIAL_FINDER);
        abstractMethod(cw, "emissive",   "(IZ)" + L_MATERIAL_FINDER);
        abstractMethod(cw, "copyFrom",   "(" + L_RENDER_MATERIAL + ")" + L_MATERIAL_FINDER);
        abstractMethod(cw, "find",       "()" + L_RENDER_MATERIAL);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateRendererInterface() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                NEW_RENDERER, null, "java/lang/Object", null);
        abstractMethod(cw, "materialFinder", "()" + L_MATERIAL_FINDER);
        abstractMethod(cw, "materialById",   "(Lnet/minecraft/class_2960;)" + L_RENDER_MATERIAL);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateRendererAccessInterface() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                NEW_RENDERER_ACCESS, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "INSTANCE", L_RENDERER_ACCESS, null, null).visitEnd();
        abstractMethod(cw, "getRenderer",            "()" + L_RENDERER);
        abstractMethod(cw, "registerRenderer",       "(" + L_RENDERER + ")V");
        abstractMethod(cw, "hasRenderer",            "()Z");

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, IMPL_ACCESS);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, IMPL_ACCESS, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, NEW_RENDERER_ACCESS, "INSTANCE", L_RENDERER_ACCESS);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateRenderMaterialImpl() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                IMPL_RENDER_MATERIAL, null, "java/lang/Object",
                new String[]{NEW_RENDER_MATERIAL});
        emitDefaultCtor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateMaterialFinderImpl() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                IMPL_MATERIAL_FINDER, null, "java/lang/Object",
                new String[]{NEW_MATERIAL_FINDER});
        emitDefaultCtor(cw);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STUB_MATERIAL", L_RENDER_MATERIAL, null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, IMPL_RENDER_MATERIAL);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, IMPL_RENDER_MATERIAL, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, IMPL_MATERIAL_FINDER, "STUB_MATERIAL", L_RENDER_MATERIAL);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        returnThis(cw, "clear",                "()" + L_MATERIAL_FINDER);
        returnThis(cw, "blendMode",            "(" + L_BLEND_MODE + ")" + L_MATERIAL_FINDER);
        returnThis(cw, "blendMode",            "(I" + L_BLEND_MODE + ")" + L_MATERIAL_FINDER);
        returnThis(cw, "disableAo",            "(IZ)" + L_MATERIAL_FINDER);
        returnThis(cw, "disableColorIndex",    "(IZ)" + L_MATERIAL_FINDER);
        returnThis(cw, "disableDiffuse",       "(IZ)" + L_MATERIAL_FINDER);
        returnThis(cw, "emissive",             "(IZ)" + L_MATERIAL_FINDER);
        returnThis(cw, "copyFrom",             "(" + L_RENDER_MATERIAL + ")" + L_MATERIAL_FINDER);

        MethodVisitor find = cw.visitMethod(Opcodes.ACC_PUBLIC, "find", "()" + L_RENDER_MATERIAL, null, null);
        find.visitCode();
        find.visitFieldInsn(Opcodes.GETSTATIC, IMPL_MATERIAL_FINDER, "STUB_MATERIAL", L_RENDER_MATERIAL);
        find.visitInsn(Opcodes.ARETURN);
        find.visitMaxs(0, 0);
        find.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateRendererImpl() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                IMPL_RENDERER, null, "java/lang/Object",
                new String[]{NEW_RENDERER});
        emitDefaultCtor(cw);

        MethodVisitor mf = cw.visitMethod(Opcodes.ACC_PUBLIC, "materialFinder",
                "()" + L_MATERIAL_FINDER, null, null);
        mf.visitCode();
        mf.visitTypeInsn(Opcodes.NEW, IMPL_MATERIAL_FINDER);
        mf.visitInsn(Opcodes.DUP);
        mf.visitMethodInsn(Opcodes.INVOKESPECIAL, IMPL_MATERIAL_FINDER, "<init>", "()V", false);
        mf.visitInsn(Opcodes.ARETURN);
        mf.visitMaxs(0, 0);
        mf.visitEnd();

        // materialById: null is a valid lookup miss
        MethodVisitor mbi = cw.visitMethod(Opcodes.ACC_PUBLIC, "materialById",
                "(Lnet/minecraft/class_2960;)" + L_RENDER_MATERIAL, null, null);
        mbi.visitCode();
        mbi.visitInsn(Opcodes.ACONST_NULL);
        mbi.visitInsn(Opcodes.ARETURN);
        mbi.visitMaxs(0, 0);
        mbi.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateRendererAccessImpl() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                IMPL_ACCESS, null, "java/lang/Object",
                new String[]{NEW_RENDERER_ACCESS});
        emitDefaultCtor(cw);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STUB_RENDERER", L_RENDERER, null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, IMPL_RENDERER);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, IMPL_RENDERER, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, IMPL_ACCESS, "STUB_RENDERER", L_RENDERER);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        MethodVisitor gr = cw.visitMethod(Opcodes.ACC_PUBLIC, "getRenderer", "()" + L_RENDERER, null, null);
        gr.visitCode();
        gr.visitFieldInsn(Opcodes.GETSTATIC, IMPL_ACCESS, "STUB_RENDERER", L_RENDERER);
        gr.visitInsn(Opcodes.ARETURN);
        gr.visitMaxs(0, 0);
        gr.visitEnd();

        // registerRenderer ignored: getRenderer always hands back the stub
        MethodVisitor rr = cw.visitMethod(Opcodes.ACC_PUBLIC, "registerRenderer",
                "(" + L_RENDERER + ")V", null, null);
        rr.visitCode();
        rr.visitInsn(Opcodes.RETURN);
        rr.visitMaxs(0, 0);
        rr.visitEnd();

        MethodVisitor hr = cw.visitMethod(Opcodes.ACC_PUBLIC, "hasRenderer", "()Z", null, null);
        hr.visitCode();
        hr.visitInsn(Opcodes.ICONST_1);
        hr.visitInsn(Opcodes.IRETURN);
        hr.visitMaxs(0, 0);
        hr.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter newClassWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String t1, String t2) {
                // fall back to Object when MC isn't on the classpath (unit tests)
                try { return super.getCommonSuperClass(t1, t2); }
                catch (Throwable t) { return "java/lang/Object"; }
            }
        };
    }

    private static void abstractMethod(ClassWriter cw, String name, String desc) {
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, desc, null, null).visitEnd();
    }

    private static void emitDefaultCtor(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    // chainable builder method whose body is `return this;`
    private static void returnThis(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitEnumValues(ClassWriter cw, String self, String lSelf) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "values", "()[" + lSelf, null, null);
        m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC, self, "$VALUES", "[" + lSelf);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[" + lSelf, "clone", "()Ljava/lang/Object;", false);
        m.visitTypeInsn(Opcodes.CHECKCAST, "[" + lSelf);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitEnumValueOf(ClassWriter cw, String self, String lSelf) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "valueOf", "(Ljava/lang/String;)" + lSelf, null, null);
        m.visitCode();
        m.visitLdcInsn(org.objectweb.asm.Type.getType(lSelf));
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        m.visitTypeInsn(Opcodes.CHECKCAST, self);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitEnumClinit(ClassWriter cw, String self, String lSelf, String[] names) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        m.visitCode();
        for (int i = 0; i < names.length; i++) {
            m.visitTypeInsn(Opcodes.NEW, self);
            m.visitInsn(Opcodes.DUP);
            m.visitLdcInsn(names[i]);
            pushInt(m, i);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, self, "<init>", "(Ljava/lang/String;I)V", false);
            m.visitFieldInsn(Opcodes.PUTSTATIC, self, names[i], lSelf);
        }
        pushInt(m, names.length);
        m.visitTypeInsn(Opcodes.ANEWARRAY, self);
        for (int i = 0; i < names.length; i++) {
            m.visitInsn(Opcodes.DUP);
            pushInt(m, i);
            m.visitFieldInsn(Opcodes.GETSTATIC, self, names[i], lSelf);
            m.visitInsn(Opcodes.AASTORE);
        }
        m.visitFieldInsn(Opcodes.PUTSTATIC, self, "$VALUES", "[" + lSelf);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void pushInt(MethodVisitor mv, int n) {
        if (n >= -1 && n <= 5) mv.visitInsn(Opcodes.ICONST_0 + n);
        else if (n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, n);
        else if (n >= Short.MIN_VALUE && n <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, n);
        else mv.visitLdcInsn(n);
    }
}
