/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Lets pre-1.17 Fabric entity models use the model system introduced in Minecraft 1.17.
 *
 * <p>The bridge records calls from the old mutable ModelPart API and renders them through a
 * generated subclass of the modern intermediary class. Generated base classes also connect old
 * {@code super()} calls to modern model constructors.
 *
 * <p>Retromod generates these classes with ASM because Minecraft is not on its compile classpath.
 * This bridge is only registered on intermediary-named Fabric hosts before 26.1.
 */
public final class Pre1_17ModelBridge {

    private Pre1_17ModelBridge() {}

    // Intermediary names (1.21.x-stable).
    static final String MODEL_PART = "net/minecraft/class_630";
    static final String MODEL = "net/minecraft/class_3879";
    static final String POSE_STACK = "net/minecraft/class_4587";
    static final String POSE_INNER = "net/minecraft/class_4587$class_4665"; // PoseStack.Pose
    static final String VERTEX_CONSUMER = "net/minecraft/class_4588";

    /** The injected synthetic class's internal name. */
    static final String SELF = "com/retromod/generated/LegacyModelPart";

    /** ModelPart's stable 1.17+ constructor: (List<Cube>, Map<String,ModelPart>). */
    private static final String MODEL_PART_CTOR_DESC = "(Ljava/util/List;Ljava/util/Map;)V";

    private static final String L_MP = "L" + MODEL_PART + ";";
    private static final String L_MODEL = "L" + MODEL + ";";
    private static final String L_POSE = "L" + POSE_STACK + ";";
    private static final String L_POSE_INNER = "L" + POSE_INNER + ";";
    private static final String L_VC = "L" + VERTEX_CONSUMER + ";";

    // ModelPart's public mutable transform fields (intermediary IDs from the probe).
    private static final String F_X_ROT = "field_3654";
    private static final String F_Y_ROT = "field_3674";
    private static final String F_Z_ROT = "field_3675";

    // PoseStack / VertexConsumer method IDs (from the layer-2 render-API probe).
    private static final String M_PUSH      = "method_22903"; // pushPose()V
    private static final String M_POP       = "method_22909"; // popPose()V
    private static final String M_TRANSLATE = "method_46416"; // translate(FFF)V
    private static final String M_MUL_POSE  = "method_22907"; // mulPose(Quaternionfc)V
    private static final String M_LAST      = "method_23760"; // last()Pose
    private static final String M_RENDER_INT_COLOR = "method_22699"; // render(Pose, VC, int light, int overlay, int color)V
    private static final String M_ADD_VERTEX_POSE = "method_56824"; // addVertex(Pose, FFF)VC
    private static final String M_SET_COLOR_ARGB = "method_39415"; // setColor(int argb)VC
    private static final String M_SET_UV      = "method_22913"; // setUv(FF)VC
    private static final String M_SET_OVERLAY = "method_60803"; // setOverlay(I)VC
    private static final String M_SET_LIGHT   = "method_22922"; // setLight(I)VC
    private static final String M_SET_NORMAL  = "method_22914"; // setNormal(FFF)VC

    // AgeableListModel-family abstract bases, absent on 26.1, rebuilt as abstract synthetics
    // that render their part-iterable(s) through the LegacyModelPart RGBA bridge.
    static final String ANIMAL_MODEL      = "net/minecraft/class_4592"; // AnimalModel (head + body)
    static final String TINTED_MODEL      = "net/minecraft/class_4593"; // tint subclass of AnimalModel
    static final String COMPOSITE_MODEL   = "net/minecraft/class_4595"; // CompositeEntityModel (single parts list)
    static final String ENTITY_MODEL_BASE = "net/minecraft/class_583";  // shared super, PRESENT on host
    static final String GEN_ANIMAL    = "com/retromod/generated/LegacyAnimalModel";
    static final String GEN_TINTED    = "com/retromod/generated/LegacyTintedAnimalModel";
    static final String GEN_COMPOSITE = "com/retromod/generated/LegacyCompositeModel";
    private static final String M_HEAD_PARTS      = "method_22946"; // AnimalModel head parts
    private static final String M_BODY_PARTS      = "method_22948"; // AnimalModel body parts
    private static final String M_COMPOSITE_PARTS = "method_22960"; // CompositeEntityModel parts
    private static final String M_TINT_SET        = "method_22955"; // tint setter (FFF)
    private static final String M_MODEL_RENDER    = "method_2828";  // Model.render(Pose,VC,light,overlay,r,g,b,a)
    private static final String F_TINT_R = "field_20923", F_TINT_G = "field_20924", F_TINT_B = "field_20925";
    private static final String ITERABLE_DESC = "()Ljava/lang/Iterable;";
    private static final String FUNCTION_DESC = "Ljava/util/function/Function;";
    private static final String MODEL_RENDER_DESC = "(" + L_POSE + L_VC + "IIFFFF)V";

    /**
     * Concrete vanilla model bases a mod extends, paired with the old (1.16-era) super() ctor
     * descriptor(s) each is called with. The modern host ctor is uniformly (ModelPart). Row
     * format: {baseInternalName, oldSuperDesc...}.
     */
    private static final String[][] CONCRETE_BASES = {
        {"net/minecraft/class_560", "()V"},          // CowModel
        {"net/minecraft/class_583", "()V"},          // quadruped-family base
        {"net/minecraft/class_597", "(IFZFFFFI)V"},  // quadruped-family base
        {"net/minecraft/class_601", "()V"},          // SheepModel
        {"net/minecraft/class_620", "(FII)V"},
        {"net/minecraft/class_623", "(FFII)V"},      // ZombieModel
    };

    /** Inject the synthetics and rewrite the old construction call sites. Pre-26.1 Fabric only. */
    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SELF, generateLegacyModelPart());

        // new class_630(...) -> LegacyModelPart.create(...)
        transformer.registerConstructorRedirect(MODEL_PART, "(" + L_MODEL + "II)V",
                SELF, "create", "(" + L_MODEL + "II)" + L_MP);
        transformer.registerConstructorRedirect(MODEL_PART, "(" + L_MODEL + ")V",
                SELF, "create", "(" + L_MODEL + ")" + L_MP);

        // Build/transform/render call sites -> static targets on the synthetic.
        // addBox family
        devirtual(transformer, "method_2856", "(FFFFFFF)V",  "addBox",        "(" + L_MP + "FFFFFFF)V");
        devirtual(transformer, "method_2849", "(FFFFFFFZ)V", "addBoxMirror",  "(" + L_MP + "FFFFFFFZ)V");
        devirtual(transformer, "method_2844", "(FFFFFF)" + L_MP,  "addBoxR",       "(" + L_MP + "FFFFFF)" + L_MP);
        devirtual(transformer, "method_2854", "(FFFFFFZ)" + L_MP, "addBoxRMirror", "(" + L_MP + "FFFFFFZ)" + L_MP);
        // texture offset / size (chainable)
        devirtual(transformer, "method_2850", "(II)" + L_MP, "texOffs",    "(" + L_MP + "II)" + L_MP);
        devirtual(transformer, "method_2853", "(II)" + L_MP, "setTexSize", "(" + L_MP + "II)" + L_MP);
        // transform setters / child wiring
        devirtual(transformer, "method_2851", "(FFF)V",       "setPos",   "(" + L_MP + "FFF)V");
        devirtual(transformer, "method_2845", "(" + L_MP + ")V", "addChild", "(" + L_MP + L_MP + ")V");
        // old RGBA-float render (pre-1.21.2 signature), delegates to the int-color override
        devirtual(transformer, "method_22699",
                "(" + L_POSE + L_VC + "IIFFFF)V",
                "render",
                "(" + L_MP + L_POSE + L_VC + "IIFFFF)V");

        // Rebase only the inheritance edge. A full class redirect would also rewrite unrelated
        // method parameters and break modern mixins that still use the base class.
        for (String[] spec : CONCRETE_BASES) {
            String base = spec[0];
            String[] oldDescs = new String[spec.length - 1];
            System.arraycopy(spec, 1, oldDescs, 0, oldDescs.length);
            String legacy = "com/retromod/generated/LegacyModelBase_"
                    + base.substring(base.lastIndexOf('_') + 1);
            transformer.registerSyntheticClass(legacy, generateLegacyBase(legacy, base, oldDescs));
            transformer.registerSuperclassRebase(base, legacy);
        }

        // These abstract bases were removed entirely, so all references must follow the generated
        // replacement class.
        transformer.registerSyntheticClass(GEN_ANIMAL, generateAgeableBase(GEN_ANIMAL,
                new String[]{"()V", "(ZFF)V", "(ZFFFFF)V", "(" + FUNCTION_DESC + "ZFFFFF)V"},
                new String[]{M_HEAD_PARTS, M_BODY_PARTS}, Opcodes.ACC_PROTECTED));
        transformer.registerSyntheticClass(GEN_COMPOSITE, generateAgeableBase(GEN_COMPOSITE,
                new String[]{"()V", "(" + FUNCTION_DESC + ")V"},
                new String[]{M_COMPOSITE_PARTS}, Opcodes.ACC_PUBLIC));
        transformer.registerSyntheticClass(GEN_TINTED, generateTintedBase(GEN_TINTED, GEN_ANIMAL));
        transformer.registerClassRedirect(ANIMAL_MODEL, GEN_ANIMAL);
        transformer.registerClassRedirect(COMPOSITE_MODEL, GEN_COMPOSITE);
        transformer.registerClassRedirect(TINTED_MODEL, GEN_TINTED);

    }

    private static void devirtual(RetromodTransformer t, String oldName, String oldDesc,
                                  String newName, String newDesc) {
        t.registerMethodRedirect(MODEL_PART, oldName, oldDesc, SELF, newName, newDesc, true);
    }

    // Probe results, host MC 1.21.11, that the synthetics above are built against:
    //   class_630 (ModelPart): <init>(List,Map)V; getChild=method_32086(String)class_630;
    //     hasChild=method_41919(String)Z; render=method_22699(class_4587,class_4588,III)V.
    //   class_3879 (Model): <init>(class_630,Function)V.
    //   Concrete bases: <init>(class_630)V uniformly; class_583 also (class_630,Function)V.
    //   class_4592/4593/4595 absent on 1.21.11 (abstract bases, synthetic reimpls; structures
    //     recovered from the remapped 1.16.5 client jar). Each part renders via
    //     class_630.method_22699(pose,vc,light,overlay,packedARGB) with (r,g,b,a) packed into
    //     the int; the 1.16 baby head-scale path is dropped (babies render adult-scale):
    //     - class_4592 (AnimalModel) extends class_583; method_22946()=headParts,
    //       method_22948()=bodyParts (both ()Iterable); ctors ()V,(ZFF)V,(ZFFFFF)V,(Function,ZFFFFF)V.
    //     - class_4595 (CompositeEntityModel) extends class_583; method_22960()=parts (()Iterable);
    //       ctors ()V,(Function)V. Explicit Iterator loop, not the 1.16 forEach+lambda.
    //     - class_4593 extends class_4592; method_22955(FFF) stores tint into field_20923/24/25;
    //       render multiplies r/g/b by them then super.method_2828.
    //   Render API: PoseStack class_4587 push=method_22903 pop=method_22909
    //     translate=method_46416 mulPose=method_22907 last=method_23760. VertexConsumer
    //     class_4588 (chainable) addVertex=method_56824 setColor=method_39415 setUv=method_22913
    //     setOverlay=method_60803 setLight=method_22922 setNormal=method_22914.

    /** Generate com/retromod/generated/LegacyModelPart extends class_630. */
    static byte[] generateLegacyModelPart() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                SELF, null, MODEL_PART, null);

        // Instance fields holding the model's mutable state.
        cw.visitField(Opcodes.ACC_PUBLIC, "boxes",       "Ljava/util/List;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "children",    "Ljava/util/List;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "currentTexU", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "currentTexV", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "texW",        "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "texH",        "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "posX",        "F", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "posY",        "F", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "posZ",        "F", null, null).visitEnd();

        // Constructors: super(empty,empty) + init our own fields.
        emitBridgingCtor(cw, "(" + L_MODEL + "II)V");
        emitBridgingCtor(cw, "(" + L_MODEL + ")V");
        emitBridgingCtor(cw, "()V");

        // Shared forgiving EMPTY_ROOT (used by layer-3 base bridges).
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "EMPTY_ROOT", L_MP, null, null).visitEnd();
        emitEmptyRootClinit(cw);
        emitForgivingGetChild(cw);
        emitForgivingHasChild(cw);

        // Static factories the constructor redirects target.
        emitFactory(cw, "(" + L_MODEL + "II)" + L_MP, "(" + L_MODEL + "II)V");
        emitFactory(cw, "(" + L_MODEL + ")" + L_MP, "(" + L_MODEL + ")V");

        // Layer-2 recorders: de-virtualized statics that capture cube + transform state.
        emitAddBox(cw, "addBox",        "(" + L_MP + "FFFFFFF)V",   /*numFloats*/7, /*hasMirror*/false, /*returnsSelf*/false);
        emitAddBox(cw, "addBoxMirror",  "(" + L_MP + "FFFFFFFZ)V",  7, true,  false);
        emitAddBox(cw, "addBoxR",       "(" + L_MP + "FFFFFF)" + L_MP,  6, false, true);
        emitAddBox(cw, "addBoxRMirror", "(" + L_MP + "FFFFFFZ)" + L_MP, 6, true,  true);
        emitTwoIntFieldsRetSelf(cw, "texOffs",    "currentTexU", "currentTexV");
        emitTwoIntFieldsRetSelf(cw, "setTexSize", "texW",        "texH");
        emitSetPos(cw);
        emitAddChild(cw);

        // Old RGBA-float render bridge delegates to the int-color instance override.
        emitStaticRenderBridge(cw);

        // Instance render override (method_22699 int-color), draws recorded cubes.
        emitInstanceRender(cw);

        // Per-cube + per-vertex helpers used by the instance render.
        emitDrawBox(cw);
        emitEmitVertex(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** super(emptyList, emptyMap); init boxes/children to ArrayList; texW=64; texH=32. */
    private static void emitBridgingCtor(ClassWriter cw, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        // super(new ArrayList(), new HashMap())
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MODEL_PART, "<init>", MODEL_PART_CTOR_DESC, false);
        // this.boxes = new ArrayList()
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, SELF, "boxes", "Ljava/util/List;");
        // this.children = new ArrayList()
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, SELF, "children", "Ljava/util/List;");
        // this.texW = 64; this.texH = 32;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, 64);
        mv.visitFieldInsn(Opcodes.PUTFIELD, SELF, "texW", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, 32);
        mv.visitFieldInsn(Opcodes.PUTFIELD, SELF, "texH", "I");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitFactory(ClassWriter cw, String factoryDesc, String ctorDesc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "create", factoryDesc, null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, SELF);
        mv.visitInsn(Opcodes.DUP);
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(factoryDesc);
        int slot = 0;
        for (org.objectweb.asm.Type a : args) {
            mv.visitVarInsn(a.getOpcode(Opcodes.ILOAD), slot);
            slot += a.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, "<init>", ctorDesc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitEmptyRootClinit(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, SELF);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, SELF, "EMPTY_ROOT", L_MP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** getChild(String) returns a fresh LegacyModelPart instead of throwing. */
    private static void emitForgivingGetChild(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "method_32086",
                "(Ljava/lang/String;)" + L_MP, null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, SELF);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** hasChild(String) always returns true. */
    private static void emitForgivingHasChild(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "method_41919",
                "(Ljava/lang/String;)Z", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emit an addBox-family recorder. Appends a 10-float spec
     * [x,y,z,w,h,d,delta,mirror,texU,texV] to self.boxes, reading texU/texV off the receiver.
     *
     * @param numFloats 6 (no delta) or 7 (with delta)
     * @param hasMirror whether a trailing Z (mirror) arg is present
     * @param returnsSelf whether the method returns the receiver (chained variants)
     */
    private static void emitAddBox(ClassWriter cw, String name, String desc,
                                   int numFloats, boolean hasMirror, boolean returnsSelf) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, desc, null, null);
        mv.visitCode();
        // float[] arr = new float[10]
        mv.visitIntInsn(Opcodes.BIPUSH, 10);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
        // arr[0..5] = x,y,z,w,h,d
        for (int i = 0; i < 6; i++) {
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, i);
            mv.visitVarInsn(Opcodes.FLOAD, 1 + i);
            mv.visitInsn(Opcodes.FASTORE);
        }
        // arr[6] = delta (or 0)
        mv.visitInsn(Opcodes.DUP);
        pushInt(mv, 6);
        if (numFloats == 7) mv.visitVarInsn(Opcodes.FLOAD, 7);
        else mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FASTORE);
        // arr[7] = mirror (as float, 0 or 1)
        mv.visitInsn(Opcodes.DUP);
        pushInt(mv, 7);
        if (hasMirror) {
            mv.visitVarInsn(Opcodes.ILOAD, 1 + numFloats);
            mv.visitInsn(Opcodes.I2F);
        } else {
            mv.visitInsn(Opcodes.FCONST_0);
        }
        mv.visitInsn(Opcodes.FASTORE);
        // arr[8] = currentTexU (as float)
        mv.visitInsn(Opcodes.DUP);
        pushInt(mv, 8);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SELF);
        mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "currentTexU", "I");
        mv.visitInsn(Opcodes.I2F);
        mv.visitInsn(Opcodes.FASTORE);
        // arr[9] = currentTexV (as float)
        mv.visitInsn(Opcodes.DUP);
        pushInt(mv, 9);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SELF);
        mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "currentTexV", "I");
        mv.visitInsn(Opcodes.I2F);
        mv.visitInsn(Opcodes.FASTORE);
        // self.boxes.add(arr)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SELF);
        mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "boxes", "Ljava/util/List;");
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(Opcodes.POP);
        if (returnsSelf) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.ARETURN);
        } else {
            mv.visitInsn(Opcodes.RETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Two-int setters that return the receiver: {@code self.A = arg1; self.B = arg2; return self;}. */
    private static void emitTwoIntFieldsRetSelf(ClassWriter cw, String name, String fieldA, String fieldB) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, "(" + L_MP + "II)" + L_MP, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SELF);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, SELF, fieldA, "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SELF);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, SELF, fieldB, "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code setPos(self, x, y, z) { self.posX = x; self.posY = y; self.posZ = z; }} */
    private static void emitSetPos(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "setPos", "(" + L_MP + "FFF)V", null, null);
        mv.visitCode();
        String[] f = {"posX", "posY", "posZ"};
        for (int i = 0; i < 3; i++) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, SELF);
            mv.visitVarInsn(Opcodes.FLOAD, 1 + i);
            mv.visitFieldInsn(Opcodes.PUTFIELD, SELF, f[i], "F");
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code addChild(self, child) { self.children.add(child); }} */
    private static void emitAddChild(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "addChild", "(" + L_MP + L_MP + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SELF);
        mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "children", "Ljava/util/List;");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Old RGBA-float render call sites delegate to the int-color instance override with white
     * (0xFFFFFFFF). Legacy callers pass 1,1,1,1, so the RGBA-to-int conversion is skipped.
     */
    private static void emitStaticRenderBridge(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "render", "(" + L_MP + L_POSE + L_VC + "IIFFFF)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0); // self (class_630)
        mv.visitVarInsn(Opcodes.ALOAD, 1); // pose
        mv.visitVarInsn(Opcodes.ALOAD, 2); // vc
        mv.visitVarInsn(Opcodes.ILOAD, 3); // light
        mv.visitVarInsn(Opcodes.ILOAD, 4); // overlay
        mv.visitLdcInsn(0xFFFFFFFF);       // white ARGB
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODEL_PART, M_RENDER_INT_COLOR,
                "(" + L_POSE + L_VC + "III)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Instance override of class_630.method_22699 (the 1.21.2+ int-color render). Pushes the
     * pose, applies translate + ZYX rotation from this part's pos and inherited rotation fields,
     * draws each box via drawBox, recurses into children, then pops.
     */
    private static void emitInstanceRender(ClassWriter cw) {
        // Local layout (instance method, slot 0 = this):
        //   0 this, 1 pose, 2 vc, 3 light, 4 overlay, 5 color,
        //   6 innerPose (Pose), 7 boxesList, 8 i (boxes loop), 9 childrenList, 10 j (children loop)
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, M_RENDER_INT_COLOR,
                "(" + L_POSE + L_VC + "III)V", null, null);
        mv.visitCode();

        // pose.pushPose()
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POSE_STACK, M_PUSH, "()V", false);

        // pose.translate(posX/16, posY/16, posZ/16)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        for (String f : new String[]{"posX", "posY", "posZ"}) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, SELF, f, "F");
            mv.visitLdcInsn(16.0F);
            mv.visitInsn(Opcodes.FDIV);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POSE_STACK, M_TRANSLATE, "(FFF)V", false);

        // pose.mulPose(new Quaternionf().rotationZYX(zRot, yRot, xRot)); rotation fields are
        // inherited from class_630, set by the mod via PUTFIELD.
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.NEW, "org/joml/Quaternionf");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/joml/Quaternionf", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, MODEL_PART, F_Z_ROT, "F");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, MODEL_PART, F_Y_ROT, "F");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, MODEL_PART, F_X_ROT, "F");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/joml/Quaternionf", "rotationZYX",
                "(FFF)Lorg/joml/Quaternionf;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POSE_STACK, M_MUL_POSE,
                "(Lorg/joml/Quaternionfc;)V", false);

        // innerPose = pose.last()
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POSE_STACK, M_LAST, "()" + L_POSE_INNER, false);
        mv.visitVarInsn(Opcodes.ASTORE, 6);

        // Loop over boxes: for (i=0; i<boxes.size(); i++) drawBox(innerPose, vc, boxes.get(i), texW, texH, color, light, overlay)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "boxes", "Ljava/util/List;");
        mv.visitVarInsn(Opcodes.ASTORE, 7);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 8);
        Label boxLoopStart = new Label(), boxLoopEnd = new Label();
        mv.visitLabel(boxLoopStart);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, boxLoopEnd);
        // drawBox(innerPose, vc, (float[]) boxes.get(i), this.texW, this.texH, color, light, overlay)
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get",
                "(I)Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[F");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "texW", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "texH", "I");
        mv.visitVarInsn(Opcodes.ILOAD, 5); // color
        mv.visitVarInsn(Opcodes.ILOAD, 3); // light
        mv.visitVarInsn(Opcodes.ILOAD, 4); // overlay
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "drawBox",
                "(" + L_POSE_INNER + L_VC + "[FIIIII)V", false);
        mv.visitIincInsn(8, 1);
        mv.visitJumpInsn(Opcodes.GOTO, boxLoopStart);
        mv.visitLabel(boxLoopEnd);

        // Loop over children: for (j=0; j<children.size(); j++) ((class_630)children.get(j)).method_22699(pose, vc, light, overlay, color)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "children", "Ljava/util/List;");
        mv.visitVarInsn(Opcodes.ASTORE, 9);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 10);
        Label childLoopStart = new Label(), childLoopEnd = new Label();
        mv.visitLabel(childLoopStart);
        mv.visitVarInsn(Opcodes.ILOAD, 10);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, childLoopEnd);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitVarInsn(Opcodes.ILOAD, 10);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get",
                "(I)Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MODEL_PART);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODEL_PART, M_RENDER_INT_COLOR,
                "(" + L_POSE + L_VC + "III)V", false);
        mv.visitIincInsn(10, 1);
        mv.visitJumpInsn(Opcodes.GOTO, childLoopStart);
        mv.visitLabel(childLoopEnd);

        // pose.popPose()
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POSE_STACK, M_POP, "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * drawBox(innerPose, vc, box[10], texW, texH, color, light, overlay): emit 6 faces x 4
     * vertices for one cube. The 8 corner positions are precomputed into locals so each face
     * reloads only the 4 corners it needs.
     *
     * <p>UVs are placeholder (0,0)-(1,1) per face; positions and normals are correct, so cubes
     * are shaped and lit right but textured wrong until the old-ModelRenderer T-layout is encoded.
     */
    private static void emitDrawBox(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "drawBox",
                "(" + L_POSE_INNER + L_VC + "[FIIIII)V", null, null);
        mv.visitCode();
        // delta = box[6] into local 8, then the 8 corner extents (low/high per axis, /16)
        // into locals 9..14: x0,x1,y0,y1,z0,z1.
        mv.visitVarInsn(Opcodes.ALOAD, 2); pushInt(mv, 6); mv.visitInsn(Opcodes.FALOAD);
        mv.visitVarInsn(Opcodes.FSTORE, 8);
        loadCornerLow(mv, 0, 8); mv.visitVarInsn(Opcodes.FSTORE, 9);
        loadCornerHigh(mv, 0, 3, 8); mv.visitVarInsn(Opcodes.FSTORE, 10);
        loadCornerLow(mv, 1, 8); mv.visitVarInsn(Opcodes.FSTORE, 11);
        loadCornerHigh(mv, 1, 4, 8); mv.visitVarInsn(Opcodes.FSTORE, 12);
        loadCornerLow(mv, 2, 8); mv.visitVarInsn(Opcodes.FSTORE, 13);
        loadCornerHigh(mv, 2, 5, 8); mv.visitVarInsn(Opcodes.FSTORE, 14);

        // Locals: 9=x0 10=x1 11=y0 12=y1 13=z0 14=z1. Faces in order DOWN/UP/NORTH/SOUTH/
        // WEST/EAST, vertices CCW from outside, placeholder UVs cycling 0/0,1/0,1/1,0/1.

        // DOWN (-Y, normal 0,-1,0): vertices (x0,y0,z0), (x0,y0,z1), (x1,y0,z1), (x1,y0,z0)
        emitFace(mv,  9,11,13,  9,11,14,  10,11,14,  10,11,13,   0f,-1f,0f);
        // UP   (+Y, normal 0,+1,0): (x0,y1,z1), (x0,y1,z0), (x1,y1,z0), (x1,y1,z1)
        emitFace(mv,  9,12,14,  9,12,13,  10,12,13,  10,12,14,   0f, 1f,0f);
        // NORTH (-Z, normal 0,0,-1): (x1,y0,z0), (x0,y0,z0), (x0,y1,z0), (x1,y1,z0)
        emitFace(mv, 10,11,13,  9,11,13,   9,12,13,  10,12,13,   0f, 0f,-1f);
        // SOUTH (+Z, normal 0,0,+1): (x0,y0,z1), (x1,y0,z1), (x1,y1,z1), (x0,y1,z1)
        emitFace(mv,  9,11,14, 10,11,14,  10,12,14,   9,12,14,   0f, 0f, 1f);
        // WEST  (-X, normal -1,0,0): (x0,y0,z1), (x0,y0,z0), (x0,y1,z0), (x0,y1,z1)
        emitFace(mv,  9,11,14,  9,11,13,   9,12,13,   9,12,14,  -1f, 0f,0f);
        // EAST  (+X, normal +1,0,0): (x1,y0,z0), (x1,y0,z1), (x1,y1,z1), (x1,y1,z0)
        emitFace(mv, 10,11,13, 10,11,14,  10,12,14,  10,12,13,   1f, 0f,0f);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Push {@code (box[idx] - box[delta]) / 16f}. */
    private static void loadCornerLow(MethodVisitor mv, int idx, int deltaLocal) {
        mv.visitVarInsn(Opcodes.ALOAD, 2); pushInt(mv, idx); mv.visitInsn(Opcodes.FALOAD);
        mv.visitVarInsn(Opcodes.FLOAD, deltaLocal);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitLdcInsn(16.0F);
        mv.visitInsn(Opcodes.FDIV);
    }

    /** Push {@code (box[idx] + box[lenIdx] + box[delta]) / 16f}. */
    private static void loadCornerHigh(MethodVisitor mv, int idx, int lenIdx, int deltaLocal) {
        mv.visitVarInsn(Opcodes.ALOAD, 2); pushInt(mv, idx); mv.visitInsn(Opcodes.FALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, 2); pushInt(mv, lenIdx); mv.visitInsn(Opcodes.FALOAD);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FLOAD, deltaLocal);
        mv.visitInsn(Opcodes.FADD);
        mv.visitLdcInsn(16.0F);
        mv.visitInsn(Opcodes.FDIV);
    }

    /**
     * Emit one face as 4 vertices (CCW from outside), each taking its (x,y,z) from the named
     * locals, with placeholder UVs (0,0)/(1,0)/(1,1)/(0,1) and the face normal on all 4.
     */
    private static void emitFace(MethodVisitor mv,
            int ax, int ay, int az,
            int bx, int by, int bz,
            int cx, int cy, int cz,
            int dx, int dy, int dz,
            float nx, float ny, float nz) {
        emitVertexCall(mv, ax, ay, az, 0.0F, 0.0F, nx, ny, nz);
        emitVertexCall(mv, bx, by, bz, 1.0F, 0.0F, nx, ny, nz);
        emitVertexCall(mv, cx, cy, cz, 1.0F, 1.0F, nx, ny, nz);
        emitVertexCall(mv, dx, dy, dz, 0.0F, 1.0F, nx, ny, nz);
    }

    /** Emit an INVOKESTATIC emitVertex(pose, vc, x, y, z, color, u, v, light, overlay, nx, ny, nz). */
    private static void emitVertexCall(MethodVisitor mv,
            int xLocal, int yLocal, int zLocal,
            float u, float v,
            float nx, float ny, float nz) {
        mv.visitVarInsn(Opcodes.ALOAD, 0); // pose
        mv.visitVarInsn(Opcodes.ALOAD, 1); // vc
        mv.visitVarInsn(Opcodes.FLOAD, xLocal);
        mv.visitVarInsn(Opcodes.FLOAD, yLocal);
        mv.visitVarInsn(Opcodes.FLOAD, zLocal);
        mv.visitVarInsn(Opcodes.ILOAD, 5); // color
        mv.visitLdcInsn(u);
        mv.visitLdcInsn(v);
        mv.visitVarInsn(Opcodes.ILOAD, 6); // light
        mv.visitVarInsn(Opcodes.ILOAD, 7); // overlay
        mv.visitLdcInsn(nx);
        mv.visitLdcInsn(ny);
        mv.visitLdcInsn(nz);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "emitVertex",
                "(" + L_POSE_INNER + L_VC + "FFFIFFIIFFF)V", false);
    }

    /**
     * emitVertex via the modern VertexConsumer chain: addVertex, setColor, setUv, setOverlay,
     * setLight, setNormal. Each step returns VertexConsumer; the final one is POPped.
     */
    private static void emitEmitVertex(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "emitVertex",
                "(" + L_POSE_INNER + L_VC + "FFFIFFIIFFF)V", null, null);
        mv.visitCode();
        // vc.addVertex(pose, x, y, z)
        mv.visitVarInsn(Opcodes.ALOAD, 1); // vc
        mv.visitVarInsn(Opcodes.ALOAD, 0); // pose
        mv.visitVarInsn(Opcodes.FLOAD, 2); // x
        mv.visitVarInsn(Opcodes.FLOAD, 3); // y
        mv.visitVarInsn(Opcodes.FLOAD, 4); // z
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, VERTEX_CONSUMER, M_ADD_VERTEX_POSE,
                "(" + L_POSE_INNER + "FFF)" + L_VC, true);
        // .setColor(color)
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, VERTEX_CONSUMER, M_SET_COLOR_ARGB,
                "(I)" + L_VC, true);
        // .setUv(u, v)
        mv.visitVarInsn(Opcodes.FLOAD, 6);
        mv.visitVarInsn(Opcodes.FLOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, VERTEX_CONSUMER, M_SET_UV,
                "(FF)" + L_VC, true);
        // .setOverlay(overlay)
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, VERTEX_CONSUMER, M_SET_OVERLAY,
                "(I)" + L_VC, true);
        // .setLight(light)
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, VERTEX_CONSUMER, M_SET_LIGHT,
                "(I)" + L_VC, true);
        // .setNormal(nx, ny, nz)
        mv.visitVarInsn(Opcodes.FLOAD, 10);
        mv.visitVarInsn(Opcodes.FLOAD, 11);
        mv.visitVarInsn(Opcodes.FLOAD, 12);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, VERTEX_CONSUMER, M_SET_NORMAL,
                "(FFF)" + L_VC, true);
        mv.visitInsn(Opcodes.POP); // discard final chain return
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Push a small non-negative int via the cheapest encoding (ICONST/BIPUSH/SIPUSH). */
    private static void pushInt(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + v);
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, v);
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, v);
        } else {
            mv.visitLdcInsn(v);
        }
    }

    /**
     * Generate Legacy&lt;base&gt; extends &lt;base&gt; for a vanilla model base whose 1.16-era
     * ctor(s) the mod calls via super(). Each old ctor descriptor gets a ctor that ignores its
     * args and calls the host's modern (ModelPart) ctor with EMPTY_ROOT. A modern (ModelPart)
     * ctor is also added so a mod constructing the base the new way still works.
     */
    static byte[] generateLegacyBase(String legacyName, String baseInternal, String[] oldDescs) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                legacyName, null, baseInternal, null);

        final String modernDesc = "(" + L_MP + ")V";
        boolean hasModern = false;
        for (String oldDesc : oldDescs) {
            if (oldDesc.equals(modernDesc)) hasModern = true;
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", oldDesc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETSTATIC, SELF, "EMPTY_ROOT", L_MP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, baseInternal, "<init>", modernDesc, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        if (!hasModern) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", modernDesc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, baseInternal, "<init>", modernDesc, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A frame-computing ClassWriter that falls back to Object for unresolvable MC classes. */
    private static ClassWriter framedWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
    }

    /**
     * Generate an abstract AgeableListModel-family base (class_4592 AnimalModel / class_4595
     * CompositeEntityModel). Extends {@link #ENTITY_MODEL_BASE}, keeps the part-iterable
     * getter(s) abstract for the mod to override, and renders every part through
     * LegacyModelPart.render. The 1.16 baby head-scale path is dropped (babies render adult-scale).
     */
    static byte[] generateAgeableBase(String genName, String[] ctorDescs,
                                      String[] getterNames, int getterAccess) {
        ClassWriter cw = framedWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                genName, null, ENTITY_MODEL_BASE, null);

        // Constructors mirror the 1.16 signatures, each forwarding to class_583's modern
        // (ModelPart) ctor with EMPTY_ROOT. Function-taking forms keep their render-layer
        // Function so the host picks the right RenderType.
        for (String desc : ctorDescs) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETSTATIC, SELF, "EMPTY_ROOT", L_MP);
            if (desc.startsWith("(" + FUNCTION_DESC)) {
                mv.visitVarInsn(Opcodes.ALOAD, 1); // the render-layer Function (first param)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ENTITY_MODEL_BASE, "<init>",
                        "(" + L_MP + FUNCTION_DESC + ")V", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ENTITY_MODEL_BASE, "<init>",
                        "(" + L_MP + ")V", false);
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // Abstract part getters; the mod implements these and may widen the access.
        for (String getter : getterNames) {
            cw.visitMethod(getterAccess | Opcodes.ACC_ABSTRACT, getter, ITERABLE_DESC, null, null).visitEnd();
        }

        // Concrete render: walk each getter's parts and draw them.
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, M_MODEL_RENDER, MODEL_RENDER_DESC, null, null);
        mv.visitCode();
        for (String getter : getterNames) {
            emitPartLoop(mv, genName, getter);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate the abstract tint subclass (class_4593): extends the synthetic AnimalModel,
     * stores a 3-float tint via method_22955(FFF) (default 1,1,1), and multiplies the render
     * color by it before delegating to super.render.
     */
    static byte[] generateTintedBase(String genName, String animalGenName) {
        ClassWriter cw = framedWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                genName, null, animalGenName, null);

        final String[] fs = {F_TINT_R, F_TINT_G, F_TINT_B};
        for (String f : fs) {
            cw.visitField(Opcodes.ACC_PROTECTED, f, "F", null, null).visitEnd();
        }

        // super() then default each tint to 1.0f.
        MethodVisitor c = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, animalGenName, "<init>", "()V", false);
        for (String f : fs) {
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitInsn(Opcodes.FCONST_1);
            c.visitFieldInsn(Opcodes.PUTFIELD, genName, f, "F");
        }
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        // method_22955(FFF) stores the tint.
        MethodVisitor s = cw.visitMethod(Opcodes.ACC_PUBLIC, M_TINT_SET, "(FFF)V", null, null);
        s.visitCode();
        for (int i = 0; i < 3; i++) {
            s.visitVarInsn(Opcodes.ALOAD, 0);
            s.visitVarInsn(Opcodes.FLOAD, i + 1);
            s.visitFieldInsn(Opcodes.PUTFIELD, genName, fs[i], "F");
        }
        s.visitInsn(Opcodes.RETURN);
        s.visitMaxs(0, 0);
        s.visitEnd();

        // render: super.render(pose, vc, light, overlay, tintR*r, tintG*g, tintB*b, a).
        MethodVisitor r = cw.visitMethod(Opcodes.ACC_PUBLIC, M_MODEL_RENDER, MODEL_RENDER_DESC, null, null);
        r.visitCode();
        r.visitVarInsn(Opcodes.ALOAD, 0); // this (super receiver)
        r.visitVarInsn(Opcodes.ALOAD, 1); // pose
        r.visitVarInsn(Opcodes.ALOAD, 2); // vertexConsumer
        r.visitVarInsn(Opcodes.ILOAD, 3); // light
        r.visitVarInsn(Opcodes.ILOAD, 4); // overlay
        int[] rgb = {5, 6, 7};
        for (int i = 0; i < 3; i++) {
            r.visitVarInsn(Opcodes.ALOAD, 0);
            r.visitFieldInsn(Opcodes.GETFIELD, genName, fs[i], "F");
            r.visitVarInsn(Opcodes.FLOAD, rgb[i]);
            r.visitInsn(Opcodes.FMUL);
        }
        r.visitVarInsn(Opcodes.FLOAD, 8); // a (unchanged)
        r.visitMethodInsn(Opcodes.INVOKESPECIAL, animalGenName, M_MODEL_RENDER, MODEL_RENDER_DESC, false);
        r.visitInsn(Opcodes.RETURN);
        r.visitMaxs(0, 0);
        r.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Emit a loop rendering every {@code class_630} from {@code this.<getter>()}. */
    private static void emitPartLoop(MethodVisitor mv, String genName, String getterName) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, genName, getterName, ITERABLE_DESC, false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Iterable", "iterator",
                "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 9);
        Label loop = new Label(), end = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, end);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MODEL_PART);
        mv.visitVarInsn(Opcodes.ALOAD, 1); // pose
        mv.visitVarInsn(Opcodes.ALOAD, 2); // vertexConsumer
        mv.visitVarInsn(Opcodes.ILOAD, 3); // light
        mv.visitVarInsn(Opcodes.ILOAD, 4); // overlay
        mv.visitVarInsn(Opcodes.FLOAD, 5); // r
        mv.visitVarInsn(Opcodes.FLOAD, 6); // g
        mv.visitVarInsn(Opcodes.FLOAD, 7); // b
        mv.visitVarInsn(Opcodes.FLOAD, 8); // a
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "render",
                "(" + L_MP + L_POSE + L_VC + "IIFFFF)V", false);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(end);
    }
}
