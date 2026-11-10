/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Rewrites GeckoLib 4's texture, buffer and Molang code that calls render APIs Minecraft removed, onto Retromod's rebuilt helpers. */
public final class LegacyGeckoLib4Adapter {

    static final String PACKAGE = "software/bernie/geckolib/";
    static final String TEXTURE_PACKAGE = PACKAGE + "cache/texture/";
    static final String ANIMATABLE = TEXTURE_PACKAGE + "AnimatableTexture";
    static final String CONTENTS = ANIMATABLE + "$AnimationContents";
    static final String CONTENTS_TEXTURE = CONTENTS + "$Texture";
    static final String GLOWING = TEXTURE_PACKAGE + "AutoGlowingTexture";
    static final String ABSTRACT = TEXTURE_PACKAGE + "GeoAbstractTexture";
    static final String GLOW_META = PACKAGE + "resource/GeoGlowingTextureMeta";
    static final String GEO_RENDERER = PACKAGE + "renderer/GeoRenderer";
    static final String ARMOR_LAYER = PACKAGE + "renderer/layer/ItemArmorGeoLayer";
    static final String INTERNAL_UTIL = PACKAGE + "util/InternalUtil";
    static final String NEOFORGE_CLIENT = PACKAGE + "platform/GeckoLibClientNeoForge";
    static final String FORGE_CLIENT = PACKAGE + "platform/GeckoLibClientForge";
    // GeckoLib 5 starts with Minecraft 1.21.5 and has its own texture code.
    static final String FIRST_GECKOLIB_5_VERSION = "1.21.5";

    static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";
    static final String RENDER_TYPE = "Lnet/minecraft/client/renderer/rendertype/RenderType;";
    static final String RESOURCE_MANAGER = "Lnet/minecraft/server/packs/resources/ResourceManager;";
    static final String SERIALIZER = "net/minecraft/server/packs/metadata/MetadataSectionSerializer";
    static final String VERTEX_CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    static final String BUFFER_BUILDER = "com/mojang/blaze3d/vertex/BufferBuilder";
    static final List<String> REMOVED_BUFFERS = List.of("com/mojang/blaze3d/vertex/VertexMultiConsumer$Double",
            "net/minecraft/client/renderer/OutlineBufferSource$EntityOutlineGenerator");
    static final String HUMANOID_MODEL = "net/minecraft/client/model/HumanoidModel";
    // The animation state 1.21.2 moved off HumanoidModel into its render state; GeckoLib's armor model still copies it.
    static final List<String> REMOVED_MODEL_FIELDS = List.of("young", "crouching", "riding", "leftArmPose", "rightArmPose",
            "scaleHead", "babyHeadScale", "babyYHeadOffset", "babyZHeadOffset", "babyBodyScale", "bodyYOffset");
    // The entity outline checks 26.2 removed, with what an old call returned when outlines were off.
    static final Map<String, Integer> REMOVED_OUTLINE_CALLS = Map.of(
            "net/minecraft/client/renderer/LevelRenderer.shouldShowEntityOutlines()Z", Opcodes.ICONST_0,
            "net/minecraft/client/renderer/RenderBuffers.outlineBufferSource()Lnet/minecraft/client/renderer/OutlineBufferSource;",
            Opcodes.ACONST_NULL);

    /** A removed interface GeckoLib's Molang queries test for, and the helpers that answer for it. */
    record RemovedType(String type, String test, String method, String helper) {}

    static final List<RemovedType> QUERY_TYPES = List.of(
            new RemovedType("net/minecraft/world/entity/Saddleable", "isSaddleable", "isSaddled", "isSaddled"),
            new RemovedType("net/minecraft/world/entity/PowerableMob", "isPowerable", "isPowered", "isPowered"));

    // GeckoLib's texture methods whose job the rebuilt textures took over, by class, then name and descriptor.
    static final Map<String, Map<String, String>> DELEGATES = Map.of(
            ANIMATABLE, Map.of("setAndUpdate(" + IDENTIFIER + "I)V", "setAndUpdate"),
            GLOWING, Map.of(
                    "getEmissiveResource(" + IDENTIFIER + ")" + IDENTIFIER, "emissiveResource",
                    "getRenderType(" + IDENTIFIER + ")" + RENDER_TYPE, "glowingRenderType",
                    "getOutlineRenderType(" + IDENTIFIER + ")" + RENDER_TYPE, "glowingRenderType"));

    // Methods only the old upload path reached; no 1.21.11 or newer host calls them, so they become empty.
    static final Map<String, List<String>> EMPTIED = Map.of(
            ANIMATABLE, List.of("load(" + RESOURCE_MANAGER + ")V", "setAnimationFrame(I)V", "lambda$load$", "access$"),
            CONTENTS, List.of("<init>", "generateAnimatedTexture"),
            CONTENTS_TEXTURE, List.of("setCurrentFrame(I)V", "generateInterpolatedFrame", "setGlowMaskTexture",
                    "lambda$setCurrentFrame$"),
            GLOWING, List.of("<clinit>", "loadTexture", "lambda$loadTexture$", "lambda$getEmissiveResource$",
                    "lambda$static$"),
            // A generated GeckoLib texture would never load on these hosts, so the request is dropped.
            ABSTRACT, List.of("generateTexture", "load(" + RESOURCE_MANAGER + ")V", "uploadSimple"),
            // Vanilla armor and skulls on a GeckoLib mob read the armor material layers and skull models 1.21.2
            // replaced, so the layer draws nothing for them instead of failing mid-frame.
            ARMOR_LAYER, List.of("renderVanillaArmorPiece", "renderSkullAsArmor", "getVanillaArmorBuffer"),
            INTERNAL_UTIL, List.of("tryRenderGeoArmorPiece"),
            NEOFORGE_CLIENT, List.of("getGeoModelForArmor"),
            FORGE_CLIENT, List.of("getGeoModelForArmor"));

    private LegacyGeckoLib4Adapter() {}

    /**
     * Rewrites one class of GeckoLib 4, built for a Minecraft version before 1.21.5; anything else is returned as it is.
     * {@code outlinesGone} is true on a host without the outline and paired buffers, 26.2 and newer.
     */
    public static byte[] apply(byte[] bytes, String modSourceVersion, boolean outlinesGone) {
        if (modSourceVersion == null
                || RetromodVersion.compareMcVersions(modSourceVersion, FIRST_GECKOLIB_5_VERSION) >= 0) return bytes;
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getClassName().startsWith(PACKAGE)) return bytes;
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean changed = rewriteTextures(node);
        changed |= rewriteGlowMeta(node);
        for (MethodNode method : node.methods) {
            if (outlinesGone && node.name.equals(GEO_RENDERER)) changed |= rewriteBufferChecks(method);
            if (outlinesGone) changed |= dropOutlineCalls(method);
            if (isOldArmorModelLookup(node, method)) {
                replaceBody(method, lastArgumentBody(method));
                changed = true;
            }
            changed |= dropRemovedModelFields(node, method);
            changed |= rewriteQueries(method);
        }
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean rewriteTextures(ClassNode node) {
        Map<String, String> delegates = DELEGATES.getOrDefault(node.name, Map.of());
        List<String> emptied = EMPTIED.getOrDefault(node.name, List.of());
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            String helper = delegates.get(method.name + method.desc);
            if (helper != null && (method.access & Opcodes.ACC_STATIC) != 0) {
                replaceBody(method, delegateBody(method, helper));
                changed = true;
            } else if (matchesAny(method, emptied)) {
                replaceBody(method, defaultBody(node, method));
                changed = true;
            }
        }
        // AutoGlowingTexture's render state fields name classes the host removed, and nothing reads them now.
        if (node.name.equals(GLOWING)) {
            changed |= node.fields.removeIf(field -> field.desc.startsWith("Lnet/minecraft/client/renderer/RenderStateShard"));
        }
        return changed;
    }

    // An entry is an exact name and descriptor, a bare name, or a lambda prefix ending in '$'.
    private static boolean matchesAny(MethodNode method, List<String> entries) {
        for (String entry : entries) {
            if (entry.contains("(") ? entry.equals(method.name + method.desc)
                    : entry.endsWith("$") ? method.name.startsWith(entry) : entry.equals(method.name)) {
                return true;
            }
        }
        return false;
    }

    private static InsnList delegateBody(MethodNode method, String helper) {
        InsnList body = new InsnList();
        int slot = 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            body.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyGeckoLib4Bridge.TEXTURES, helper, method.desc, false));
        body.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        return body;
    }

    private static InsnList defaultBody(ClassNode owner, MethodNode method) {
        InsnList body = new InsnList();
        // A constructor still has to run its superclass's.
        if (method.name.equals("<init>")) {
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.superName, "<init>", "()V", false));
            body.add(new InsnNode(Opcodes.RETURN));
            return body;
        }
        Type result = Type.getReturnType(method.desc);
        switch (result.getSort()) {
            case Type.VOID -> { }
            case Type.LONG -> body.add(new InsnNode(Opcodes.LCONST_0));
            case Type.FLOAT -> body.add(new InsnNode(Opcodes.FCONST_0));
            case Type.DOUBLE -> body.add(new InsnNode(Opcodes.DCONST_0));
            case Type.OBJECT, Type.ARRAY -> body.add(new InsnNode(Opcodes.ACONST_NULL));
            default -> body.add(new InsnNode(Opcodes.ICONST_0));
        }
        body.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        return body;
    }

    private static void replaceBody(MethodNode method, InsnList body) {
        method.instructions = body;
        method.tryCatchBlocks = new ArrayList<>();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        int argumentSlots = (Type.getArgumentsAndReturnSizes(method.desc) >> 2);
        method.maxLocals = Math.max(method.maxLocals, argumentSlots);
        method.maxStack = Math.max(method.maxStack, argumentSlots + 2);
    }

    // GeoGlowingTextureMeta's .mcmeta reader implements a serializer interface the host removed; only replaced code used it.
    private static boolean rewriteGlowMeta(ClassNode node) {
        boolean changed = false;
        if (node.interfaces.remove(SERIALIZER)) {
            node.signature = null;
            changed = true;
        }
        if (!node.name.equals(GLOW_META)) return changed;
        String old = "L" + SERIALIZER + ";";
        for (FieldNode field : node.fields) {
            if (field.desc.equals(old)) {
                field.desc = "Ljava/lang/Object;";
                field.signature = null;
                changed = true;
            }
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.owner.equals(GLOW_META) && field.desc.equals(old)) {
                    field.desc = "Ljava/lang/Object;";
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * checkAndRefreshBuffer and bufferNeedsRefresh type-switch over two buffer classes 26.2 removed, which fails as
     * soon as the switch links. No buffer of those types can exist on such a host, so only the BufferBuilder case is kept.
     */
    private static boolean rewriteBufferChecks(MethodNode method) {
        if (!mentionsAny(method, REMOVED_BUFFERS)) return false;
        if (method.name.equals("checkAndRefreshBuffer")) return rewriteBufferCheck(method);
        if (!method.name.equals("bufferNeedsRefresh") || !method.desc.equals("(L" + VERTEX_CONSUMER + ";)Z")) return false;
        InsnList body = new InsnList();
        LabelNode no = new LabelNode();
        // Only a BufferBuilder that has finished building needs a fresh buffer.
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new TypeInsnNode(Opcodes.INSTANCEOF, BUFFER_BUILDER));
        body.add(new JumpInsnNode(Opcodes.IFEQ, no));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new TypeInsnNode(Opcodes.CHECKCAST, BUFFER_BUILDER));
        body.add(new FieldInsnNode(Opcodes.GETFIELD, BUFFER_BUILDER, "building", "Z"));
        body.add(new JumpInsnNode(Opcodes.IFNE, no));
        body.add(new InsnNode(Opcodes.ICONST_1));
        body.add(new InsnNode(Opcodes.IRETURN));
        body.add(no);
        body.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        body.add(new InsnNode(Opcodes.ICONST_0));
        body.add(new InsnNode(Opcodes.IRETURN));
        replaceBody(method, body);
        return true;
    }

    private static boolean rewriteBufferCheck(MethodNode method) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        if (arguments.length != 4 || arguments[0].getSort() != Type.BOOLEAN
                || !arguments[1].getInternalName().equals(VERTEX_CONSUMER)
                || !Type.getReturnType(method.desc).getInternalName().equals(VERTEX_CONSUMER)) return false;
        String bufferSource = arguments[2].getInternalName();
        String renderType = arguments[3].getDescriptor();
        InsnList body = new InsnList();
        LabelNode check = new LabelNode();
        LabelNode keep = new LabelNode();
        // A re-render keeps the buffer it was given.
        body.add(new VarInsnNode(Opcodes.ILOAD, 1));
        body.add(new JumpInsnNode(Opcodes.IFEQ, check));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new InsnNode(Opcodes.ARETURN));
        body.add(check);
        body.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        // A finished BufferBuilder is swapped for a fresh buffer of the same render type.
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new TypeInsnNode(Opcodes.INSTANCEOF, BUFFER_BUILDER));
        body.add(new JumpInsnNode(Opcodes.IFEQ, keep));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new TypeInsnNode(Opcodes.CHECKCAST, BUFFER_BUILDER));
        body.add(new FieldInsnNode(Opcodes.GETFIELD, BUFFER_BUILDER, "building", "Z"));
        body.add(new JumpInsnNode(Opcodes.IFNE, keep));
        body.add(new VarInsnNode(Opcodes.ALOAD, 3));
        body.add(new VarInsnNode(Opcodes.ALOAD, 4));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, bufferSource, "getBuffer",
                "(" + renderType + ")L" + VERTEX_CONSUMER + ";", true));
        body.add(new InsnNode(Opcodes.ARETURN));
        body.add(keep);
        body.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new InsnNode(Opcodes.ARETURN));
        replaceBody(method, body);
        return true;
    }

    // The outline checks become the answer they gave with outlines off; the receiver is dropped.
    private static boolean dropOutlineCalls(MethodNode method) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            Integer result = REMOVED_OUTLINE_CALLS.get(call.owner + "." + call.name + call.desc);
            if (result == null) continue;
            method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
            method.instructions.set(call, new InsnNode(result));
            changed = true;
        }
        return changed;
    }

    /** GeckoLib's NeoForge and Forge armor model lookup, which calls the pre-1.21.2 armor model hook. */
    private static boolean isOldArmorModelLookup(ClassNode node, MethodNode method) {
        if (!(node.name.equals(NEOFORGE_CLIENT) || node.name.equals(FORGE_CLIENT))
                || !method.name.equals("getArmorModelForItem")) return false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("getHumanoidArmorModel")
                    && call.desc.startsWith("(Lnet/minecraft/world/entity/LivingEntity;")) return true;
        }
        return false;
    }

    // The hook is gone, so the item never offers its own model and the default the caller passed is the answer.
    private static InsnList lastArgumentBody(MethodNode method) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (int i = 0; i < arguments.length - 1; i++) slot += arguments[i].getSize();
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, slot));
        body.add(new InsnNode(Opcodes.ARETURN));
        return body;
    }

    // Reads of the humanoid animation flags 1.21.2 removed give their defaults, and writes are dropped.
    private static boolean dropRemovedModelFields(ClassNode node, MethodNode method) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof FieldInsnNode field) || !REMOVED_MODEL_FIELDS.contains(field.name)
                    || !(field.owner.equals(HUMANOID_MODEL) || inheritsField(node, field))) continue;
            Type type = Type.getType(field.desc);
            InsnList replacement = new InsnList();
            if (insn.getOpcode() == Opcodes.GETFIELD) {
                replacement.add(new InsnNode(Opcodes.POP));
                replacement.add(new InsnNode(defaultConstant(type)));
            } else if (insn.getOpcode() == Opcodes.PUTFIELD) {
                replacement.add(new InsnNode(type.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                replacement.add(new InsnNode(Opcodes.POP));
            } else {
                continue;
            }
            method.instructions.insertBefore(insn, replacement);
            method.instructions.remove(insn);
            changed = true;
        }
        return changed;
    }

    // A field named on the class itself that the class does not declare is one it inherited from the model.
    private static boolean inheritsField(ClassNode node, FieldInsnNode field) {
        if (!field.owner.equals(node.name)) return false;
        for (FieldNode own : node.fields) {
            if (own.name.equals(field.name)) return false;
        }
        return true;
    }

    private static int defaultConstant(Type type) {
        return switch (type.getSort()) {
            case Type.FLOAT -> Opcodes.FCONST_0;
            case Type.LONG -> Opcodes.LCONST_0;
            case Type.DOUBLE -> Opcodes.DCONST_0;
            case Type.OBJECT, Type.ARRAY -> Opcodes.ACONST_NULL;
            default -> Opcodes.ICONST_0;
        };
    }

    private static boolean mentionsAny(MethodNode method, List<String> types) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && types.contains(type.desc)) return true;
        }
        return false;
    }

    // instanceof, the cast and the call on a removed Molang interface become helper calls on the plain object.
    private static boolean rewriteQueries(MethodNode method) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            for (RemovedType removed : QUERY_TYPES) {
                if (insn instanceof TypeInsnNode type && type.desc.equals(removed.type())) {
                    if (insn.getOpcode() == Opcodes.INSTANCEOF) {
                        method.instructions.set(insn, helperCall(removed.test()));
                    } else if (insn.getOpcode() == Opcodes.CHECKCAST) {
                        method.instructions.remove(insn);
                    }
                    changed = true;
                } else if (insn instanceof MethodInsnNode call && call.owner.equals(removed.type())
                        && call.name.equals(removed.method()) && call.desc.equals("()Z")) {
                    method.instructions.set(insn, helperCall(removed.helper()));
                    changed = true;
                } else if (insn instanceof FrameNode frame) {
                    // A frame that names the removed type would make the verifier load it, so it holds an Object.
                    changed |= retype(frame.local, removed.type()) | retype(frame.stack, removed.type());
                }
            }
        }
        return changed;
    }

    private static boolean retype(List<Object> types, String removed) {
        if (types == null) return false;
        boolean changed = false;
        for (int i = 0; i < types.size(); i++) {
            if (removed.equals(types.get(i))) {
                types.set(i, "java/lang/Object");
                changed = true;
            }
        }
        return changed;
    }

    private static MethodInsnNode helperCall(String name) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyGeckoLib4Bridge.QUERIES, name, "(Ljava/lang/Object;)Z", false);
    }
}
