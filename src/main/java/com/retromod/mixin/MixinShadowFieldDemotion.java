/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.util.RetromodLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Repairs a mixin whose {@code @Shadow @Final} field targets a vanilla field that a
 * later MC removed, but whose <em>value</em> is still reachable as a constructor
 * parameter the mixin's own {@code @Inject} handler already captures.
 *
 * <p>Canonical case (worldgen): YUNG's API {@code NoiseChunkMixin} shadows
 * {@code NoiseChunk.noiseSettings}. The 1.21.5 worldgen refactor deleted that field
 * from {@code NoiseChunk} (verified: absent on 1.21.11 and 26.x, present on 1.21.1),
 * so on a modern host the {@code @Shadow} resolves to nothing, the mixin fails to
 * apply, and {@code NoiseChunk} class-load is poisoned, killing ALL world generation
 * (vanilla {@code /place} / {@code /locate} included). Yet the data is still live: the
 * {@code NoiseSettings} arrives as the 5th constructor argument (descriptor unchanged),
 * and the mixin's {@code <init>}-RETURN handler already receives it.
 *
 * <p>The repair demotes the field from {@code @Shadow @Final} to a plain {@code @Unique}
 * mixin-owned field (so the framework <em>adds</em> it to the target instead of trying
 * to bind a removed one) and prepends {@code this.<field> = <capturedParam>} to the
 * handler, so the mixin's later {@code field.height()}/{@code field.minY()} reads keep
 * working against the value the vanilla constructor computed. The companion dead
 * {@code @Accessor} interface stays neutralized via the blocklist.
 *
 * <p>This is a curated, version-anchored escape hatch like {@link MixinBlocklist} and
 * {@link MixinHandlerResignature}: it fires only for the named mixin classes, and the
 * caller gates it to hosts that actually removed the field (1.21.5+). On an older host
 * the {@code @Shadow} still resolves, so the field is left untouched.
 */
public final class MixinShadowFieldDemotion {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-mixin");

    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String FINAL_DESC = "Lorg/spongepowered/asm/mixin/Final;";
    private static final String UNIQUE_DESC = "Lorg/spongepowered/asm/mixin/Unique;";

    /**
     * One field to demote and the handler whose captured parameter seeds it. The
     * parameter is located by matching {@code fieldDesc} against the handler's argument
     * types (there must be exactly one such argument), so the rule survives a mod
     * rebuild that leaves the field type and handler name intact.
     */
    record Demotion(String fieldName, String fieldDesc, String handlerName) {}

    /** Mixin internal name -&gt; the field demotions to apply. */
    private static final Map<String, List<Demotion>> RULES = Map.of(
            "com/yungnickyoung/minecraft/yungsapi/mixin/NoiseChunkMixin",
            List.of(new Demotion(
                    "noiseSettings",
                    "Lnet/minecraft/world/level/levelgen/NoiseSettings;",
                    "yungsapi_attachNoiseChunkToBeardifier")));

    private MixinShadowFieldDemotion() {}

    /** Whether any demotion rule targets this mixin class (cheap pre-check). */
    public static boolean handles(String mixinInternalName) {
        return RULES.containsKey(mixinInternalName);
    }

    /**
     * Apply the demotion rules for {@code cn}, if any. Mutates {@code cn} in place.
     *
     * @return {@code true} if the class was modified.
     */
    public static boolean apply(ClassNode cn) {
        List<Demotion> rules = RULES.get(cn.name);
        if (rules == null) return false;

        boolean modified = false;
        for (Demotion d : rules) {
            FieldNode field = findField(cn, d.fieldName(), d.fieldDesc());
            // Only act on a still-shadowed field: if it isn't @Shadow anymore (already
            // demoted, or the mod changed) there is nothing safe to do.
            if (field == null || !hasAnnotation(field, SHADOW_DESC)) continue;

            MethodNode handler = findMethod(cn, d.handlerName());
            if (handler == null) {
                LOGGER.debug("Shadow demotion: handler {} not found on {}, skipping",
                        d.handlerName(), cn.name);
                continue;
            }
            int slot = uniqueParamSlot(handler.desc, d.fieldDesc());
            if (slot < 0) {
                LOGGER.debug("Shadow demotion: no unique {} parameter in {}.{}, skipping",
                        d.fieldDesc(), cn.name, d.handlerName());
                continue;
            }

            // 1) Demote the field: drop @Shadow/@Final, mark @Unique, clear any ACC_FINAL
            //    (we now write it outside <init>).
            removeAnnotation(field, SHADOW_DESC);
            removeAnnotation(field, FINAL_DESC);
            addInvisibleAnnotation(field, UNIQUE_DESC);
            field.access &= ~Opcodes.ACC_FINAL;

            // 2) Seed it from the captured constructor parameter at handler entry:
            //    this.<field> = <param>;
            Type ft = Type.getType(d.fieldDesc());
            InsnList seed = new InsnList();
            seed.add(new VarInsnNode(Opcodes.ALOAD, 0));
            seed.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), slot));
            seed.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, d.fieldName(), d.fieldDesc()));
            handler.instructions.insert(seed);
            // Straight-line prepend, net-zero stack, no new locals or branch targets, so the
            // existing stack-map frames stay valid; only ensure maxStack covers this + param.
            handler.maxStack = Math.max(handler.maxStack, 1 + ft.getSize());

            modified = true;
            LOGGER.debug(RetromodLog.DETAIL, "Mixin shadow-demotion: {}.{} demoted to @Unique and seeded from {}() "
                    + "(the shadowed vanilla field was removed on this MC; worldgen would "
                    + "otherwise crash)", cn.name, d.fieldName(), d.handlerName());
        }
        return modified;
    }

    private static FieldNode findField(ClassNode cn, String name, String desc) {
        for (FieldNode f : cn.fields) {
            if (f.name.equals(name) && f.desc.equals(desc)) return f;
        }
        return null;
    }

    private static MethodNode findMethod(ClassNode cn, String name) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    /**
     * Local-variable slot of the sole parameter whose descriptor equals {@code typeDesc}
     * in an instance method {@code desc}, or {@code -1} if there is not exactly one such
     * parameter. Accounts for {@code long}/{@code double} taking two slots.
     */
    private static int uniqueParamSlot(String desc, String typeDesc) {
        Type[] args = Type.getArgumentTypes(desc);
        int slot = 1; // instance method: slot 0 is 'this'
        int found = -1;
        int count = 0;
        for (Type a : args) {
            if (a.getDescriptor().equals(typeDesc)) {
                found = slot;
                count++;
            }
            slot += a.getSize();
        }
        return count == 1 ? found : -1;
    }

    private static boolean hasAnnotation(FieldNode field, String desc) {
        return listHas(field.visibleAnnotations, desc) || listHas(field.invisibleAnnotations, desc);
    }

    private static boolean listHas(List<AnnotationNode> list, String desc) {
        if (list == null) return false;
        for (AnnotationNode a : list) {
            if (desc.equals(a.desc)) return true;
        }
        return false;
    }

    private static void removeAnnotation(FieldNode field, String desc) {
        if (field.visibleAnnotations != null) field.visibleAnnotations.removeIf(a -> desc.equals(a.desc));
        if (field.invisibleAnnotations != null) field.invisibleAnnotations.removeIf(a -> desc.equals(a.desc));
    }

    private static void addInvisibleAnnotation(FieldNode field, String desc) {
        if (listHas(field.invisibleAnnotations, desc)) return;
        if (field.invisibleAnnotations == null) field.invisibleAnnotations = new ArrayList<>();
        field.invisibleAnnotations.add(new AnnotationNode(desc));
    }
}
