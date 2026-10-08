/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Repairs Mixin members whose target member is still on the host but changed shape in a way the
 * annotation alone cannot express. Every repair is proven against the indexed host jar, so a mod
 * already built for the host is left byte-identical.
 *
 * <p>The repairs are:
 * <ul>
 *   <li>A {@code @Shadow} or injector selector that names a method the host renamed is renamed
 *       through the registered method redirects, but only when the redirect's target exists on
 *       the host with the same descriptor.</li>
 *   <li>A {@code @Shadow} field the mixin only reads, whose host field kept its name but widened
 *       to a supertype (26.1 retyped {@code Camera.FORWARDS} from {@code Vector3f} to the
 *       read-only {@code Vector3fc}), is retyped and each read is cast back to the old type. The
 *       cast fails loudly if the host ever stores a different implementation.</li>
 *   <li>An {@code @Inject} handler at {@code HEAD}, {@code TAIL}, or {@code RETURN} whose target
 *       dropped, added, or retyped parameters is retyped when the handler never reads the changed
 *       slots. Unread captures are dropped, pure insertions are added, and an unread callback is
 *       switched between {@code CallbackInfo} and {@code CallbackInfoReturnable} to match the new
 *       return type. A handler that never touches {@code this} becomes static when its target did.</li>
 *   <li>A {@code @Shadow} method the host replaced, whose old calls Retromod already sends to a
 *       static adapter, becomes a mixin-owned method that calls the same adapter. The 1.20.5
 *       {@code getAttribute(Attribute)} shadow in Porting Lib's {@code LivingEntityMixin} is one;
 *       its failure stopped the whole mixin from applying. Only class mixins qualify, because an
 *       accessor interface is not merged into its target.</li>
 *   <li>A {@code @Shadow} field the mixin never reads or writes is dropped when the host has no
 *       field of that name and type. Such a declaration does nothing but fail validation, which
 *       took Palladium's whole entity selector mixin down when 1.20.5 turned
 *       {@code EntitySelectorParser.predicate} into a list.</li>
 *   <li>The removed {@code LivingEntity.getCurrentSwingDuration()} shadow becomes a mixin-owned
 *       method that reads the 26.3 swing state.</li>
 *   <li>A {@code Gui} selector or shadow follows the 26.1 {@code render...} to {@code extract...}
 *       HUD rename, and a {@code Gui} mixin moves to {@code Hud} on 26.2 when every member it
 *       uses moved there.</li>
 * </ul>
 *
 * <p>It declines anything it cannot prove: overloaded targets, multiple targets, a handler that
 * reads a dropped capture, {@code locals} capture, sliced or ordinal injection points, and
 * {@code remap = false} scopes.
 */
public final class MixinHostDriftRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-mixin");

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String UNIQUE_DESC = "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String INJECTION_PREFIX = "Lorg/spongepowered/asm/mixin/injection/";
    private static final String MIXINEXTRAS_INJECTOR_PREFIX = "Lcom/llamalad7/mixinextras/injector/";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String CALLBACK_INFO =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CALLBACK_INFO_RETURNABLE =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final Set<String> ZERO_CAPTURE_POINTS = Set.of("HEAD", "TAIL", "RETURN");

    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String SWING_ANIMATION = "net/minecraft/world/item/component/SwingAnimation";
    private static final String SWING_DESCRIPTION = "net/minecraft/world/entity/LivingEntity$SwingDescription";
    private static final String MODIFIED_SWING_DURATION = "(L" + SWING_ANIMATION + ";)I";

    private final FuzzyMethodResolver host;
    private final Map<RetromodTransformer.MethodKey, RetromodTransformer.MethodTarget> redirects;
    private final Map<String, String> classRedirects;

    public MixinHostDriftRepair(FuzzyMethodResolver host,
            Map<RetromodTransformer.MethodKey, RetromodTransformer.MethodTarget> redirects,
            Map<String, String> classRedirects) {
        this.host = host;
        this.redirects = redirects == null ? Map.of() : redirects;
        this.classRedirects = classRedirects == null ? Map.of() : classRedirects;
    }

    /** Applies every proven repair and returns the input unchanged when none applies. */
    public byte[] apply(byte[] classBytes) {
        if (classBytes == null) return classBytes;
        // Runs even without an index, so a deferred ItemInHandRenderer mixin is still disabled.
        classBytes = MixinFirstPersonRendererAdapter.adapt(classBytes, host);
        if (host == null || !host.isIndexed()) return classBytes;
        ClassNode classNode = new ClassNode();
        try {
            new ClassReader(classBytes).accept(classNode, 0);
        } catch (RuntimeException e) {
            return classBytes;
        }
        int retargeted = redirectStringTargets(classNode);
        String target = soleRemappedTarget(classNode);
        if (target == null || !host.hasClass(target)) {
            return retargeted == 0 ? classBytes : write(classNode, classBytes, retargeted, String.valueOf(target));
        }

        int split = retargetSplitSuccessor(classNode, target);
        if (split > 0) target = soleRemappedTarget(classNode);

        int repairs = retargeted + split + renameShadowMethods(classNode, target)
                + bridgeAdaptedShadowMethods(classNode, target)
                + renameInjectorSelectors(classNode, target)
                + widenReadOnlyShadowFields(classNode, target)
                + dropUnreferencedShadowFields(classNode, target)
                + bridgeSwingDuration(classNode, target);
        for (MethodNode method : classNode.methods) {
            if (repairInjectHandler(method, target)) repairs++;
            if (repairGrownInvokeTarget(method)) repairs++;
        }
        if (repairs == 0) return classBytes;
        return write(classNode, classBytes, repairs, target);
    }

    private static byte[] write(ClassNode classNode, byte[] original, int repairs, String target) {
        try {
            ClassWriter writer = new com.retromod.util.SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
            classNode.accept(writer);
            LOGGER.info("Repaired {} Mixin member(s) in {} for the host's current {} shape",
                    repairs, classNode.name, target);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.debug("Could not verify host-drift repair in {} ({})", classNode.name, t.toString());
            return original;
        }
    }

    /**
     * Applies class redirects to string {@code @Mixin(targets = ...)} entries. The main remap
     * rewrites {@code Class} targets because they are type constants, but a target string is
     * plain annotation text, so a class that moved after the intermediary namespace (26.3's
     * {@code ItemInHandRenderer$HandRenderSelection}) kept its old name.
     */
    private int redirectStringTargets(ClassNode classNode) {
        int redirected = 0;
        for (List<AnnotationNode> list : Arrays.asList(classNode.visibleAnnotations,
                classNode.invisibleAnnotations)) {
            if (list == null) continue;
            for (AnnotationNode mixin : list) {
                if (!MIXIN_DESC.equals(mixin.desc) || !(value(mixin, "targets") instanceof List<?> names)) {
                    continue;
                }
                List<Object> rewritten = new ArrayList<>(names);
                for (int i = 0; i < rewritten.size(); i++) {
                    if (!(rewritten.get(i) instanceof String name)) continue;
                    String internal = name.replace('.', '/');
                    String moved = classRedirects.get(internal);
                    if (moved != null && !host.hasClass(internal) && host.hasClass(moved)) {
                        rewritten.set(i, moved);
                        redirected++;
                    }
                }
                if (redirected > 0) put(mixin, "targets", rewritten);
            }
        }
        return redirected;
    }

    // ---- selector and shadow renames -------------------------------------------------------

    /** Renames a {@code @Shadow} method whose host name moved and every in-class call to it. */
    private int renameShadowMethods(ClassNode classNode, String target) {
        int renamed = 0;
        for (MethodNode method : classNode.methods) {
            if (!hasAnnotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW_DESC)) {
                continue;
            }
            if (host.hasMethod(target, method.name, method.desc)) continue;
            String newName = renamedOnHost(target, method.name, method.desc);
            if (newName == null) continue;
            String oldName = method.name;
            method.name = newName;
            for (MethodNode caller : classNode.methods) {
                for (AbstractInsnNode insn : caller.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals(classNode.name)
                            && call.name.equals(oldName) && call.desc.equals(method.desc)) {
                        call.name = newName;
                    }
                }
            }
            renamed++;
        }
        return renamed;
    }

    /**
     * Turns a {@code @Shadow} of a replaced host method into a call to the static adapter that
     * already serves the mod's ordinary calls to that method, so the shadow stops failing
     * validation and both paths behave the same.
     */
    private int bridgeAdaptedShadowMethods(ClassNode classNode, String target) {
        if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) return 0;
        int bridged = 0;
        for (MethodNode method : classNode.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0
                    || !hasAnnotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW_DESC)
                    || host.hasMethod(target, method.name, method.desc)) {
                continue;
            }
            RetromodTransformer.MethodTarget adapter =
                    redirects.get(new RetromodTransformer.MethodKey(target, method.name, method.desc));
            if (adapter == null || !adapter.owner().startsWith("com/retromod/")
                    || !takesReceiverThenArguments(adapter.desc(), method.desc)) {
                continue;
            }
            makeAdapterCall(method, adapter);
            bridged++;
        }
        return bridged;
    }

    /** Whether {@code adapterDesc} is {@code (receiver, original arguments...)} with the same return. */
    private static boolean takesReceiverThenArguments(String adapterDesc, String methodDesc) {
        Type[] adapterArgs = Type.getArgumentTypes(adapterDesc);
        Type[] methodArgs = Type.getArgumentTypes(methodDesc);
        if (adapterArgs.length != methodArgs.length + 1
                || adapterArgs[0].getSort() != Type.OBJECT
                || !Type.getReturnType(adapterDesc).equals(Type.getReturnType(methodDesc))) {
            return false;
        }
        for (int i = 0; i < methodArgs.length; i++) {
            Type wanted = adapterArgs[i + 1];
            boolean widenedToObject = wanted.getDescriptor().equals("Ljava/lang/Object;")
                    && (methodArgs[i].getSort() == Type.OBJECT || methodArgs[i].getSort() == Type.ARRAY);
            if (!wanted.equals(methodArgs[i]) && !widenedToObject) return false;
        }
        return true;
    }

    private static void makeAdapterCall(MethodNode method, RetromodTransformer.MethodTarget adapter) {
        method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        if (method.visibleAnnotations != null) method.visibleAnnotations.removeIf(a -> SHADOW_DESC.equals(a.desc));
        if (method.invisibleAnnotations != null) method.invisibleAnnotations.removeIf(a -> SHADOW_DESC.equals(a.desc));
        if (method.invisibleAnnotations == null) method.invisibleAnnotations = new ArrayList<>();
        method.invisibleAnnotations.add(new AnnotationNode(UNIQUE_DESC));
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();

        InsnList body = new InsnList();
        // Once merged, this is the target, so the cast to the adapter's receiver type holds.
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getArgumentTypes(adapter.desc())[0].getInternalName()));
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            body.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, adapter.owner(), adapter.name(), adapter.desc(), false));
        body.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        method.instructions = body;
        method.maxStack = slot + 1;
        method.maxLocals = slot;
    }

    /** Rewrites {@code method = "old(desc)"} selectors whose host method was renamed. */
    private int renameInjectorSelectors(ClassNode classNode, String target) {
        int renamed = 0;
        for (MethodNode method : classNode.methods) {
            for (AnnotationNode injector : injectors(method)) {
                if (Boolean.FALSE.equals(value(injector, "remap"))) continue;
                Object selectors = value(injector, "method");
                if (!(selectors instanceof List<?> list)) continue;
                List<Object> rewritten = new ArrayList<>(list);
                boolean changed = false;
                for (int i = 0; i < rewritten.size(); i++) {
                    if (!(rewritten.get(i) instanceof String selector)) continue;
                    String replacement = renamedSelector(target, selector);
                    if (replacement != null) {
                        rewritten.set(i, replacement);
                        changed = true;
                    }
                }
                if (changed) {
                    put(injector, "method", rewritten);
                    renamed++;
                }
                for (AnnotationNode point : atPoints(injector)) {
                    if (value(point, "target") instanceof String invoke) {
                        String replacement = renamedInvokeTarget(invoke);
                        if (replacement != null) {
                            put(point, "target", replacement);
                            renamed++;
                        }
                    }
                }
            }
        }
        return renamed;
    }

    /** Rewrites an {@code INVOKE} target {@code Lowner;name(desc)} whose host method was renamed. */
    private String renamedInvokeTarget(String invoke) {
        int semicolon = invoke.indexOf(';');
        int paren = invoke.indexOf('(');
        if (!invoke.startsWith("L") || semicolon < 0 || paren < semicolon) return null;
        String owner = invoke.substring(1, semicolon);
        String name = invoke.substring(semicolon + 1, paren);
        String desc = invoke.substring(paren);
        if (host.hasMethod(owner, name, desc)) return null;
        String newName = renamedOnHost(owner, name, desc);
        return newName == null ? null : "L" + owner + ";" + newName + desc;
    }

    private String renamedSelector(String target, String selector) {
        int paren = selector.indexOf('(');
        if (paren < 0) return renamedBareSelector(target, target, selector);
        if (paren == 0 || selector.indexOf(';') >= 0 && selector.indexOf(';') < paren) return null;
        String name = selector.substring(0, paren);
        String desc = selector.substring(paren);
        if (host.hasMethod(target, name, desc)) return null;
        String newName = renamedOnHost(target, name, desc);
        return newName == null ? null : newName + desc;
    }

    /**
     * A bare selector name that {@code holder} no longer declares at all, renamed through the
     * redirects registered on {@code owner}. Only a same-descriptor rename that {@code holder}
     * proves counts, and every candidate must agree. That result carries the descriptor, so the
     * renamed selector cannot drift onto a different overload. Only a name no redirect covers
     * falls back to the {@code Gui} extract rule, which stays bare like the selector it replaces.
     */
    private String renamedBareSelector(String owner, String holder, String name) {
        boolean plainName = !name.isEmpty() && !name.startsWith("<")
                && name.chars().noneMatch(c -> c == ';' || c == ':' || c == '*' || c == '/');
        if (!plainName || host.hasMethodName(holder, name)) return null;
        String found = null;
        boolean registered = false;
        for (RetromodTransformer.MethodKey key : redirects.keySet()) {
            if (!key.owner().equals(owner) || !key.name().equals(name)) continue;
            registered = true;
            String newName = renamedName(owner, holder, name, key.desc());
            if (newName == null) continue;
            String qualified = newName + key.desc();
            if (found != null && !found.equals(qualified)) return null;
            found = qualified;
        }
        // A registered redirect names the descriptor, so when none resolves on holder the
        // name-only rule must not either: 26.2 Gui still declares extractRenderState, but with
        // (DeltaTracker, boolean, boolean) in place of the HUD entry point's parameters.
        if (found != null || registered) return found;
        return guiExtractName(owner, holder, name, null);
    }

    // ---- split target classes ----------------------------------------------------------------

    /**
     * Classes the host split in two, mapped to the class that received the members mods hook.
     * 26.2 moved the in-game HUD out of {@code Gui} into the new {@code Hud}, including the
     * extract entry point and {@code getFont}, with identical descriptors.
     */
    private static final Map<String, String> SPLIT_SUCCESSORS =
            Map.of(GUI, "net/minecraft/client/gui/Hud");

    /** Mixin member annotations the split retarget understands besides injectors. */
    private static final Set<String> SPLIT_PLAIN_MEMBERS = Set.of(SHADOW_DESC, UNIQUE_DESC,
            "Lorg/spongepowered/asm/mixin/Final;", "Lorg/spongepowered/asm/mixin/Mutable;");

    /**
     * Retargets a mixin onto the successor of a split class when every shadow and selector it
     * uses resolves on the successor and at least one of them left the old class. A shadow both
     * classes declare may stay. A mixin that injects into a method the old class still declares,
     * needs a shadow only the old class has, casts {@code this} to it, implements an interface,
     * or carries an accessor, invoker, overwrite or injector type this repair does not read is
     * left alone, so a split can never move a mixin halfway.
     */
    private int retargetSplitSuccessor(ClassNode classNode, String target) {
        String successor = SPLIT_SUCCESSORS.get(target);
        if (successor == null || !host.hasClass(successor)) return 0;
        // An interface the mixin implements is added to its target, and other mod code reaches it
        // by casting an instance of the old class (minecraft.gui). Moving the mixin would make
        // that cast fail at runtime, so such a mixin stays on the old class.
        if (classNode.interfaces != null && !classNode.interfaces.isEmpty()) return 0;

        Map<MethodNode, String> shadowRenames = new LinkedHashMap<>();
        Map<AnnotationNode, List<Object>> selectorRewrites = new LinkedHashMap<>();
        Map<AnnotationNode, String> pointRewrites = new LinkedHashMap<>();
        int moved = 0;
        for (FieldNode field : classNode.fields) {
            if (!hasAnnotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW_DESC)) continue;
            // A field both classes declare (such as the minecraft handle) does not block the move.
            if (!host.hasField(successor, field.name, field.desc)) return 0;
            if (!host.hasField(target, field.name, field.desc)) moved++;
        }
        for (MethodNode method : classNode.methods) {
            if (castsTo(method, target)) return 0;
            List<AnnotationNode> injectors = injectors(method);
            for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations,
                    method.invisibleAnnotations)) {
                if (list == null) continue;
                for (AnnotationNode annotation : list) {
                    boolean mixinMember = annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/")
                            || annotation.desc.startsWith("Lcom/llamalad7/mixinextras/");
                    if (mixinMember && !SPLIT_PLAIN_MEMBERS.contains(annotation.desc)
                            && !injectors.contains(annotation)) {
                        return 0;
                    }
                }
            }
            if (hasAnnotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW_DESC)) {
                if (!host.hasMethod(successor, method.name, method.desc)) {
                    String renamed = renamedName(target, successor, method.name, method.desc);
                    if (renamed == null) return 0;
                    shadowRenames.put(method, renamed);
                    moved++;
                } else if (!host.hasMethod(target, method.name, method.desc)) {
                    moved++;
                }
            }
            for (AnnotationNode injector : injectors) {
                if (Boolean.FALSE.equals(value(injector, "remap"))
                        || !(value(injector, "method") instanceof List<?> selectors)
                        || selectors.isEmpty()) {
                    return 0;
                }
                List<Object> rewritten = new ArrayList<>();
                for (Object selector : selectors) {
                    String relocated = selector instanceof String text
                            ? selectorOnSuccessor(target, successor, text) : null;
                    if (relocated == null) return 0;
                    rewritten.add(relocated);
                }
                selectorRewrites.put(injector, rewritten);
                for (AnnotationNode point : atPoints(injector)) {
                    if (!(value(point, "target") instanceof String reference)
                            || !reference.startsWith("L" + target + ";")) {
                        continue;
                    }
                    String relocated = referenceOnSuccessor(target, successor, reference);
                    if (relocated == null) return 0;
                    if (!relocated.equals(reference)) pointRewrites.put(point, relocated);
                }
                // selectorOnSuccessor declines any selector the old class still declares.
                moved++;
            }
        }
        if (moved == 0) return 0;

        retargetMixinValue(classNode, target, successor);
        selectorRewrites.forEach((injector, selectors) -> put(injector, "method", selectors));
        pointRewrites.forEach((point, reference) -> put(point, "target", reference));
        shadowRenames.forEach((shadow, newName) -> {
            String oldName = shadow.name;
            shadow.name = newName;
            for (MethodNode caller : classNode.methods) {
                for (AbstractInsnNode insn : caller.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals(classNode.name)
                            && call.name.equals(oldName) && call.desc.equals(shadow.desc)) {
                        call.name = newName;
                    }
                }
            }
        });
        return 1;
    }

    /** The selector as the successor spells it, or {@code null} when it does not move cleanly. */
    private String selectorOnSuccessor(String target, String successor, String selector) {
        int paren = selector.indexOf('(');
        if (paren < 0) {
            if (host.hasMethodName(target, selector)) return null;
            if (host.hasMethodName(successor, selector)) return selector;
            return renamedBareSelector(target, successor, selector);
        }
        if (paren == 0 || selector.indexOf(';') >= 0 && selector.indexOf(';') < paren) return null;
        String name = selector.substring(0, paren);
        String desc = selector.substring(paren);
        if (host.hasMethod(target, name, desc)) return null;
        if (host.hasMethod(successor, name, desc)) return selector;
        String renamed = renamedName(target, successor, name, desc);
        return renamed == null ? null : renamed + desc;
    }

    /**
     * An {@code @At} target {@code Lowner;member} on the split class, as it resolves after the
     * split: unchanged when the old class still declares it, moved to the successor when only the
     * successor does, and {@code null} when neither does.
     */
    private String referenceOnSuccessor(String target, String successor, String reference) {
        String member = reference.substring(target.length() + 2);
        int colon = member.indexOf(':');
        if (colon > 0) {
            String name = member.substring(0, colon);
            String desc = member.substring(colon + 1);
            if (host.hasField(target, name, desc)) return reference;
            return host.hasField(successor, name, desc) ? "L" + successor + ";" + member : null;
        }
        int paren = member.indexOf('(');
        if (paren <= 0) return null;
        String name = member.substring(0, paren);
        String desc = member.substring(paren);
        if (host.hasMethod(target, name, desc)) return reference;
        String moved = host.hasMethod(successor, name, desc)
                ? name : renamedName(target, successor, name, desc);
        return moved == null ? null : "L" + successor + ";" + moved + desc;
    }

    private static boolean castsTo(MethodNode method, String type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode cast && insn.getOpcode() == Opcodes.CHECKCAST
                    && cast.desc.equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static void retargetMixinValue(ClassNode classNode, String from, String to) {
        for (List<AnnotationNode> list : Arrays.asList(classNode.visibleAnnotations,
                classNode.invisibleAnnotations)) {
            if (list == null) continue;
            for (AnnotationNode mixin : list) {
                if (!MIXIN_DESC.equals(mixin.desc)) continue;
                if (value(mixin, "value") instanceof List<?> types) {
                    List<Object> rewritten = new ArrayList<>(types);
                    rewritten.replaceAll(type -> type instanceof Type t && t.getInternalName().equals(from)
                            ? Type.getObjectType(to) : type);
                    put(mixin, "value", rewritten);
                }
                if (value(mixin, "targets") instanceof List<?> names) {
                    List<Object> rewritten = new ArrayList<>(names);
                    rewritten.replaceAll(name -> name instanceof String s
                            && s.replace('.', '/').equals(from) ? to : name);
                    put(mixin, "targets", rewritten);
                }
            }
        }
    }

    /** The redirect table's new name for a same-owner, same-descriptor rename the host proves. */
    private String renamedOnHost(String owner, String name, String desc) {
        return renamedName(owner, owner, name, desc);
    }

    /**
     * The new name a same-owner, same-descriptor redirect registered on {@code owner} gives this
     * method, when {@code holder} (the owner itself, or the successor of a split) declares it.
     */
    private String renamedName(String owner, String holder, String name, String desc) {
        RetromodTransformer.MethodTarget redirect =
                redirects.get(new RetromodTransformer.MethodKey(owner, name, desc));
        if (redirect != null && !redirect.devirtualize() && redirect.owner().equals(owner)
                && redirect.desc().equals(desc) && !redirect.name().equals(name)
                && host.hasMethod(holder, redirect.name(), desc)) {
            return redirect.name();
        }
        return guiExtractName(owner, holder, name, desc);
    }

    /**
     * 26.1 renamed every in-game HUD step on {@code Gui} from {@code render...} to
     * {@code extract...} and kept the parameters ({@code renderFood} became {@code extractFood},
     * {@code renderSlot} became {@code extractSlot}, and the entry point {@code render} became
     * {@code extractRenderState}). Most of them are private, so no call redirect covers the HUD
     * mixins that target them. The new name counts only when {@code holder} declares it with the
     * same descriptor, or under that name at all for a bare selector ({@code desc == null}).
     */
    private String guiExtractName(String owner, String holder, String name, String desc) {
        if (!GUI.equals(owner) || !name.startsWith("render")) return null;
        String candidate = name.equals("render")
                ? "extractRenderState" : "extract" + name.substring("render".length());
        boolean declared = desc == null
                ? host.hasMethodName(holder, candidate) : host.hasMethod(holder, candidate, desc);
        return declared ? candidate : null;
    }

    // ---- read-only shadow field widening ---------------------------------------------------

    private int widenReadOnlyShadowFields(ClassNode classNode, String target) {
        int widened = 0;
        for (FieldNode field : classNode.fields) {
            if (!hasAnnotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW_DESC)) {
                continue;
            }
            if (host.hasField(target, field.name, field.desc)) continue;
            String widerDesc = widenedHostDescriptor(target, field.name, field.desc);
            if (widerDesc == null || writesField(classNode, field)) continue;

            String narrowType = Type.getType(field.desc).getInternalName();
            String oldDesc = field.desc;
            field.desc = widerDesc;
            field.signature = null;
            for (MethodNode method : classNode.methods) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof FieldInsnNode read && read.owner.equals(classNode.name)
                            && read.name.equals(field.name) && read.desc.equals(oldDesc)) {
                        read.desc = widerDesc;
                        method.instructions.insert(read, new TypeInsnNode(Opcodes.CHECKCAST, narrowType));
                    }
                }
            }
            widened++;
        }
        return widened;
    }

    /**
     * The host descriptor of a same-named field whose type is a proven supertype of the mixin's.
     * JOML's read-only views follow one fixed convention ({@code Vector3f} implements
     * {@code Vector3fc}), and JOML is not in the indexed Minecraft jar, so that pairing is
     * accepted by name. Game types must be proven by the indexed hierarchy.
     */
    private String widenedHostDescriptor(String owner, String name, String desc) {
        if (!desc.startsWith("L")) return null;
        String type = Type.getType(desc).getInternalName();
        if (type.startsWith("org/joml/") && !type.endsWith("c")) {
            String readOnly = "L" + type + "c;";
            if (host.hasField(owner, name, readOnly)) return readOnly;
        }
        for (String parent : host.directSupertypes(type)) {
            String parentDesc = "L" + parent + ";";
            if (host.hasField(owner, name, parentDesc) && host.isSubtypeOf(type, parent)) {
                return parentDesc;
            }
        }
        return null;
    }

    private int dropUnreferencedShadowFields(ClassNode classNode, String target) {
        int before = classNode.fields.size();
        classNode.fields.removeIf(field ->
                hasAnnotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW_DESC)
                        && !host.hasField(target, field.name, field.desc)
                        && !referencesField(classNode, field));
        return before - classNode.fields.size();
    }

    private static boolean referencesField(ClassNode classNode, FieldNode field) {
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode access && access.owner.equals(classNode.name)
                        && access.name.equals(field.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean writesField(ClassNode classNode, FieldNode field) {
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode access
                        && (access.getOpcode() == Opcodes.PUTFIELD || access.getOpcode() == Opcodes.PUTSTATIC)
                        && access.owner.equals(classNode.name) && access.name.equals(field.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- LivingEntity swing duration -------------------------------------------------------

    /**
     * 26.3 moved swing timing into {@code LivingEntity$SwingState} and replaced the private
     * {@code getCurrentSwingDuration()} with {@code getModifiedSwingDuration(SwingAnimation)}.
     * The bridge returns the running swing's duration, and otherwise the main-hand attack
     * animation's duration with the same haste and fatigue adjustment the old method applied.
     */
    private int bridgeSwingDuration(ClassNode classNode, String target) {
        if (!LIVING_ENTITY.equals(target) || (classNode.access & Opcodes.ACC_INTERFACE) != 0) return 0;
        MethodNode shadow = findMethod(classNode, "getCurrentSwingDuration", "()I");
        if (shadow == null
                || !hasAnnotation(shadow.visibleAnnotations, shadow.invisibleAnnotations, SHADOW_DESC)
                || host.hasMethod(LIVING_ENTITY, "getCurrentSwingDuration", "()I")
                || !host.hasMethod(LIVING_ENTITY, "getModifiedSwingDuration", MODIFIED_SWING_DURATION)
                || !host.hasMethod(LIVING_ENTITY, "getCurrentSwing", "()L" + SWING_DESCRIPTION + ";")
                || !host.hasMethod(LIVING_ENTITY, "getMainHandItem", "()L" + ITEM_STACK + ";")
                || !host.hasMethod(ITEM_STACK, "getAttackAnimation", "()L" + SWING_ANIMATION + ";")) {
            return 0;
        }

        if (findMethod(classNode, "getModifiedSwingDuration", MODIFIED_SWING_DURATION) == null) {
            MethodNode modified = new MethodNode(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT,
                    "getModifiedSwingDuration", MODIFIED_SWING_DURATION, null, null);
            modified.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(SHADOW_DESC)));
            if ((classNode.access & Opcodes.ACC_ABSTRACT) == 0) {
                // A concrete mixin cannot declare an abstract shadow, so give it a body Mixin discards.
                modified.access = Opcodes.ACC_PROTECTED;
                modified.instructions.add(new InsnNode(Opcodes.ICONST_0));
                modified.instructions.add(new InsnNode(Opcodes.IRETURN));
                modified.maxStack = 1;
                modified.maxLocals = 2;
            }
            classNode.methods.add(modified);
        }

        removeAnnotation(shadow, SHADOW_DESC);
        addAnnotation(shadow, UNIQUE_DESC);
        shadow.access &= ~Opcodes.ACC_ABSTRACT;
        shadow.instructions = swingDurationBody(classNode.name);
        shadow.tryCatchBlocks = new ArrayList<>();
        shadow.localVariables = null;
        shadow.maxStack = 2;
        shadow.maxLocals = 2;
        return 1;
    }

    private static InsnList swingDurationBody(String mixinName) {
        InsnList body = new InsnList();
        LabelNode idle = new LabelNode();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mixinName, "getCurrentSwing",
                "()L" + SWING_DESCRIPTION + ";", false));
        body.add(new VarInsnNode(Opcodes.ASTORE, 1));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new JumpInsnNode(Opcodes.IFNULL, idle));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SWING_DESCRIPTION, "durationTicks", "()I", false));
        body.add(new InsnNode(Opcodes.IRETURN));
        body.add(idle);
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mixinName, "getMainHandItem",
                "()L" + ITEM_STACK + ";", false));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getAttackAnimation",
                "()L" + SWING_ANIMATION + ";", false));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mixinName, "getModifiedSwingDuration",
                MODIFIED_SWING_DURATION, false));
        body.add(new InsnNode(Opcodes.IRETURN));
        return body;
    }

    // ---- INVOKE targets that gained trailing parameters -------------------------------------

    private static final String REDIRECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

    /**
     * Retargets an {@code @Inject} or {@code @Redirect} whose {@code INVOKE} target gained
     * trailing parameters, for example 26.3's {@code swing(InteractionHand)} becoming
     * {@code swing(InteractionHand, SwingAnimation, boolean)}. An {@code @Inject} handler never
     * sees the invoked call's arguments, so only the target text changes. A {@code @Redirect}
     * handler also receives the new arguments, forwards them on its own pass-through call, and
     * returns that call's result where the old method returned nothing.
     */
    private boolean repairGrownInvokeTarget(MethodNode handler) {
        AnnotationNode injector = annotation(handler, INJECT_DESC);
        boolean redirect = false;
        if (injector == null) {
            injector = annotation(handler, REDIRECT_DESC);
            redirect = injector != null;
        }
        if (injector == null || Boolean.FALSE.equals(value(injector, "remap"))) return false;
        List<AnnotationNode> points = atPoints(injector);
        if (points.size() != 1 || !"INVOKE".equals(value(points.get(0), "value"))
                || !(value(points.get(0), "target") instanceof String invoke)) {
            return false;
        }
        int semicolon = invoke.indexOf(';');
        int paren = invoke.indexOf('(');
        if (!invoke.startsWith("L") || semicolon < 0 || paren < semicolon) return false;
        String owner = invoke.substring(1, semicolon);
        String name = invoke.substring(semicolon + 1, paren);
        String oldDesc = invoke.substring(paren);
        if (!host.hasClass(owner) || host.hasMethod(owner, name, oldDesc)) return false;

        String newDesc = grownDescriptor(owner, name, oldDesc);
        if (newDesc == null) return false;
        if (redirect && !regrowRedirectHandler(handler, owner, name, oldDesc, newDesc)) return false;
        put(points.get(0), "target", "L" + owner + ";" + name + newDesc);
        return true;
    }

    /** The host's only same-named descriptor that extends {@code oldDesc} with trailing parameters. */
    private String grownDescriptor(String owner, String name, String oldDesc) {
        Set<String> candidates = new java.util.LinkedHashSet<>();
        for (FuzzyMethodResolver.MethodInfo method : host.getMethodsInHierarchy(owner)) {
            if (method.name().equals(name) && (method.access() & Opcodes.ACC_STATIC) == 0) {
                candidates.add(method.descriptor());
            }
        }
        if (candidates.size() != 1) return null;
        String newDesc = candidates.iterator().next();
        Type[] oldArgs = Type.getArgumentTypes(oldDesc);
        Type[] newArgs = Type.getArgumentTypes(newDesc);
        Type oldReturn = Type.getReturnType(oldDesc);
        if (newArgs.length <= oldArgs.length
                || !Arrays.equals(oldArgs, Arrays.copyOf(newArgs, oldArgs.length))
                || (oldReturn.getSort() != Type.VOID && !oldReturn.equals(Type.getReturnType(newDesc)))) {
            return null;
        }
        return newDesc;
    }

    private static boolean regrowRedirectHandler(MethodNode handler, String owner, String name,
            String oldDesc, String newDesc) {
        if ((handler.access & Opcodes.ACC_STATIC) != 0
                || handler.visibleParameterAnnotations != null
                || handler.invisibleParameterAnnotations != null) {
            return false;
        }
        Type[] oldArgs = Type.getArgumentTypes(oldDesc);
        Type[] newArgs = Type.getArgumentTypes(newDesc);
        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        if (handlerArgs.length != oldArgs.length + 1
                || !Arrays.equals(oldArgs, Arrays.copyOfRange(handlerArgs, 1, handlerArgs.length))
                || !Type.getReturnType(handler.desc).equals(Type.getReturnType(oldDesc))) {
            return false;
        }

        Type[] extras = Arrays.copyOfRange(newArgs, oldArgs.length, newArgs.length);
        int oldParamEnd = 1 + handlerArgs[0].getSize();
        for (Type arg : oldArgs) oldParamEnd += arg.getSize();
        int extraSize = 0;
        for (Type extra : extras) extraSize += extra.getSize();
        remapLocals(handler, Map.of(), oldParamEnd, extraSize);

        Type newReturn = Type.getReturnType(newDesc);
        boolean gainsResult = Type.getReturnType(oldDesc).getSort() == Type.VOID
                && newReturn.getSort() != Type.VOID;
        int resultSlot = handler.maxLocals + extraSize;
        for (AbstractInsnNode insn : handler.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(oldDesc)) {
                InsnList extraArgs = new InsnList();
                int slot = oldParamEnd;
                for (Type extra : extras) {
                    extraArgs.add(new VarInsnNode(extra.getOpcode(Opcodes.ILOAD), slot));
                    slot += extra.getSize();
                }
                handler.instructions.insertBefore(call, extraArgs);
                call.desc = newDesc;
                if (gainsResult) {
                    handler.instructions.insert(call,
                            new VarInsnNode(newReturn.getOpcode(Opcodes.ISTORE), resultSlot));
                }
            } else if (gainsResult && insn.getOpcode() == Opcodes.RETURN) {
                handler.instructions.insertBefore(insn,
                        new VarInsnNode(newReturn.getOpcode(Opcodes.ILOAD), resultSlot));
                handler.instructions.set(insn, new InsnNode(newReturn.getOpcode(Opcodes.IRETURN)));
            }
        }
        if (gainsResult) {
            // The result defaults to the type's zero value when the handler skips the call.
            InsnList init = new InsnList();
            init.add(new InsnNode(zeroConstant(newReturn)));
            init.add(new VarInsnNode(newReturn.getOpcode(Opcodes.ISTORE), resultSlot));
            handler.instructions.insert(init);
        }

        Type[] regrown = Arrays.copyOf(handlerArgs, handlerArgs.length + extras.length);
        System.arraycopy(extras, 0, regrown, handlerArgs.length, extras.length);
        handler.desc = Type.getMethodDescriptor(gainsResult ? newReturn : Type.getReturnType(oldDesc), regrown);
        handler.signature = null;
        handler.maxLocals = resultSlot + newReturn.getSize();
        return true;
    }

    private static int zeroConstant(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ICONST_0;
            case Type.LONG -> Opcodes.LCONST_0;
            case Type.FLOAT -> Opcodes.FCONST_0;
            case Type.DOUBLE -> Opcodes.DCONST_0;
            default -> Opcodes.ACONST_NULL;
        };
    }

    // ---- @Inject handler layout ------------------------------------------------------------

    /**
     * Retypes one {@code @Inject} handler to its live target's layout when the handler provably
     * does not depend on the parts that changed.
     */
    private boolean repairInjectHandler(MethodNode handler, String target) {
        AnnotationNode inject = annotation(handler, INJECT_DESC);
        if (inject == null || Boolean.FALSE.equals(value(inject, "remap"))
                || value(inject, "locals") != null || value(inject, "slice") != null
                || !atPointsAreZeroCapture(inject)) {
            return false;
        }
        FuzzyMethodResolver.MethodInfo live = soleLiveTarget(inject, target);
        if (live == null) return false;

        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        int callback = callbackIndex(handlerArgs);
        if (callback < 0 || callback != handlerArgs.length - 1) return false;
        if (handler.visibleParameterAnnotations != null || handler.invisibleParameterAnnotations != null) {
            return false;
        }

        boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
        boolean targetStatic = (live.access() & Opcodes.ACC_STATIC) != 0;
        if (handlerStatic && !targetStatic) return false;
        boolean makeStatic = targetStatic && !handlerStatic;
        if (makeStatic && readsSlot(handler, 0)) return false;

        Type[] liveArgs = Type.getArgumentTypes(live.descriptor());
        Type liveReturn = Type.getReturnType(live.descriptor());
        Type liveCallback = Type.getObjectType(liveReturn.getSort() == Type.VOID
                ? CALLBACK_INFO : CALLBACK_INFO_RETURNABLE);
        Type[] captures = Arrays.copyOf(handlerArgs, callback);

        int[] captureMap = alignCaptures(captures, liveArgs);
        if (captureMap == null) captureMap = alignedPrefix(captures, liveArgs);
        boolean keepCaptures = captureMap != null;
        if (!keepCaptures) captureMap = new int[captures.length];
        boolean callbackRetyped = !handlerArgs[callback].equals(liveCallback);


        int base = handlerStatic ? 0 : 1;
        int[] oldSlots = slots(handlerArgs, base);
        // Captures past the kept prefix are dropped, so the handler must never read them.
        int firstDropped = keepCaptures ? captureMap.length : 0;
        for (int i = firstDropped; i < captures.length; i++) {
            if (readsSlot(handler, oldSlots[i])) return false;
        }
        if (callbackRetyped && readsSlot(handler, oldSlots[callback])) return false;

        List<Type> newArgs = new ArrayList<>();
        if (keepCaptures) newArgs.addAll(Arrays.asList(liveArgs).subList(0, lastMapped(captureMap) + 1));
        newArgs.add(liveCallback);
        String newDesc = Type.getMethodDescriptor(Type.VOID_TYPE, newArgs.toArray(new Type[0]));
        if (newDesc.equals(handler.desc) && !makeStatic) return false;
        int newBase = (handlerStatic || makeStatic) ? 0 : 1;
        int[] newSlots = slots(newArgs.toArray(new Type[0]), newBase);

        Map<Integer, Integer> slotMap = new LinkedHashMap<>();
        if (!handlerStatic && !makeStatic) slotMap.put(0, 0);
        if (keepCaptures) {
            for (int i = 0; i < captureMap.length; i++) slotMap.put(oldSlots[i], newSlots[captureMap[i]]);
        }
        slotMap.put(oldSlots[callback], newSlots[newSlots.length - 1]);
        int oldParamEnd = oldSlots[callback] + 1;
        int newParamEnd = newSlots[newSlots.length - 1] + 1;

        remapLocals(handler, slotMap, oldParamEnd, newParamEnd - oldParamEnd);
        handler.desc = newDesc;
        handler.signature = null;
        bareSelector(inject, live);
        if (makeStatic) handler.access |= Opcodes.ACC_STATIC;
        handler.maxLocals = Math.max(0, handler.maxLocals + newParamEnd - oldParamEnd);
        return true;
    }

    /**
     * Maps each captured parameter to its live index when the live parameters are the captured
     * ones with others inserted, in order. Returns {@code null} when they cannot be aligned.
     */
    private static int[] alignCaptures(Type[] captures, Type[] liveArgs) {
        int[] map = new int[captures.length];
        int live = 0;
        for (int i = 0; i < captures.length; i++) {
            while (live < liveArgs.length && !liveArgs[live].equals(captures[i])) live++;
            if (live == liveArgs.length) return null;
            map[i] = live++;
        }
        // The alignment must be unique, otherwise a capture could bind to the wrong parameter.
        for (int i = 0; i < captures.length; i++) {
            int lower = i == 0 ? 0 : map[i - 1] + 1;
            for (int j = lower; j < map[i]; j++) {
                if (liveArgs[j].equals(captures[i])) return null;
            }
        }
        return map;
    }

    /**
     * The alignment of the longest leading run of captures, when only trailing captures fail to
     * align. 26.2 dropped {@code LevelRenderer.render}'s last parameter, and a handler that
     * captured every argument still reads the leading ones (#251, Immersive Vehicles). Returns
     * {@code null} when no leading capture aligns.
     *
     * <p>Types alone cannot say which parameter the host removed. When a dropped capture shares a
     * type with a kept one, the host may have removed the earlier one, and the kept capture would
     * then bind to a different argument than it did before. That case is declined.
     */
    private static int[] alignedPrefix(Type[] captures, Type[] liveArgs) {
        for (int kept = captures.length - 1; kept > 0; kept--) {
            int[] map = alignCaptures(Arrays.copyOf(captures, kept), liveArgs);
            if (map != null) return droppedTypesAreDistinct(captures, kept) ? map : null;
        }
        return null;
    }

    private static boolean droppedTypesAreDistinct(Type[] captures, int kept) {
        List<Type> keptTypes = Arrays.asList(captures).subList(0, kept);
        for (int i = kept; i < captures.length; i++) {
            if (keptTypes.contains(captures[i])) return false;
        }
        return true;
    }

    private static int lastMapped(int[] map) {
        return map.length == 0 ? -1 : map[map.length - 1];
    }

    private FuzzyMethodResolver.MethodInfo soleLiveTarget(AnnotationNode inject, String target) {
        Object selectors = value(inject, "method");
        if (!(selectors instanceof List<?> list) || list.size() != 1
                || !(list.get(0) instanceof String selector)) {
            return null;
        }
        int paren = selector.indexOf('(');
        String name = paren < 0 ? selector : selector.substring(0, paren);
        if (name.isEmpty() || name.contains(";") || name.contains("*") || name.startsWith("<")) return null;
        if (paren >= 0 && !namesOnlyHostTypes(selector.substring(paren))) return null;
        List<FuzzyMethodResolver.MethodInfo> matches = new ArrayList<>();
        for (FuzzyMethodResolver.MethodInfo method : host.getDeclaredMethods(target)) {
            if (method.name().equals(name)) matches.add(method);
        }
        if (matches.size() != 1) return null;
        return matches.get(0);
    }

    /**
     * Whether every class a selector descriptor names exists on the host. A Fabric mod's selector
     * can be Yarn source text that only its refmap resolves; such text is a lookup key, not a host
     * name, so a same-named host method proves nothing and the selector is left alone.
     */
    private boolean namesOnlyHostTypes(String descriptor) {
        Type[] types;
        try {
            Type method = Type.getMethodType(descriptor);
            types = Arrays.copyOf(method.getArgumentTypes(), method.getArgumentTypes().length + 1);
            types[types.length - 1] = method.getReturnType();
        } catch (RuntimeException malformed) {
            return false;
        }
        for (Type type : types) {
            Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
            if (element.getSort() != Type.OBJECT) continue;
            String internal = element.getInternalName();
            if (internal.startsWith("net/minecraft/") && !hostHasClassOrMove(internal)) return false;
        }
        return true;
    }

    /** A class the host has, directly or through the registered class moves. */
    private boolean hostHasClassOrMove(String internal) {
        String name = internal;
        for (int hop = 0; hop < 8 && name != null; hop++) {
            if (host.hasClass(name)) return true;
            String next = classRedirects.get(name);
            name = name.equals(next) ? null : next;
        }
        return false;
    }

    /** Drops a stale descriptor from the selector once the handler matches the live layout. */
    private static void bareSelector(AnnotationNode inject, FuzzyMethodResolver.MethodInfo live) {
        List<Object> selectors = new ArrayList<>((List<?>) value(inject, "method"));
        String selector = (String) selectors.get(0);
        int paren = selector.indexOf('(');
        if (paren >= 0 && !selector.substring(paren).equals(live.descriptor())) {
            selectors.set(0, live.name());
            put(inject, "method", selectors);
        }
    }

    private static List<AnnotationNode> atPoints(AnnotationNode injector) {
        Object at = value(injector, "at");
        List<AnnotationNode> points = new ArrayList<>();
        if (at instanceof AnnotationNode single) points.add(single);
        if (at instanceof List<?> list) {
            for (Object entry : list) if (entry instanceof AnnotationNode node) points.add(node);
        }
        return points;
    }

    private static boolean atPointsAreZeroCapture(AnnotationNode inject) {
        List<AnnotationNode> points = atPoints(inject);
        if (points.isEmpty()) return false;
        for (AnnotationNode point : points) {
            Object kind = value(point, "value");
            if (!(kind instanceof String s) || !ZERO_CAPTURE_POINTS.contains(s.toUpperCase(Locale.ROOT))
                    || value(point, "target") != null || value(point, "ordinal") != null) {
                return false;
            }
        }
        return true;
    }

    private static int callbackIndex(Type[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].getSort() == Type.OBJECT && (CALLBACK_INFO.equals(args[i].getInternalName())
                    || CALLBACK_INFO_RETURNABLE.equals(args[i].getInternalName()))) {
                return i;
            }
        }
        return -1;
    }

    private static int[] slots(Type[] args, int base) {
        int[] slots = new int[args.length];
        int slot = base;
        for (int i = 0; i < args.length; i++) {
            slots[i] = slot;
            slot += args[i].getSize();
        }
        return slots;
    }

    private static boolean readsSlot(MethodNode method, int slot) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.var == slot) return true;
            if (insn instanceof IincInsnNode inc && inc.var == slot) return true;
        }
        return false;
    }

    /** Moves parameter slots through {@code slotMap} and shifts every body local by {@code delta}. */
    private static void remapLocals(MethodNode method, Map<Integer, Integer> slotMap,
            int oldParamEnd, int delta) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var) {
                var.var = remapSlot(var.var, slotMap, oldParamEnd, delta);
            } else if (insn instanceof IincInsnNode inc) {
                inc.var = remapSlot(inc.var, slotMap, oldParamEnd, delta);
            }
        }
        if (method.localVariables != null) {
            List<LocalVariableNode> kept = new ArrayList<>();
            for (LocalVariableNode local : method.localVariables) {
                if (local.index < oldParamEnd && !slotMap.containsKey(local.index)) continue;
                local.index = remapSlot(local.index, slotMap, oldParamEnd, delta);
                kept.add(local);
            }
            method.localVariables = kept;
        }
    }

    private static int remapSlot(int slot, Map<Integer, Integer> slotMap, int oldParamEnd, int delta) {
        if (slot >= oldParamEnd) return slot + delta;
        Integer mapped = slotMap.get(slot);
        return mapped != null ? mapped : slot;
    }

    // ---- shared helpers --------------------------------------------------------------------

    /** The single {@code @Mixin} target, or {@code null} for several targets or none. */
    private static String soleRemappedTarget(ClassNode classNode) {
        AnnotationNode mixin = null;
        for (List<AnnotationNode> list : Arrays.asList(classNode.visibleAnnotations,
                classNode.invisibleAnnotations)) {
            if (list == null) continue;
            for (AnnotationNode annotation : list) {
                if (MIXIN_DESC.equals(annotation.desc)) mixin = annotation;
            }
        }
        if (mixin == null || Boolean.FALSE.equals(value(mixin, "remap"))) return null;
        List<String> targets = new ArrayList<>();
        if (value(mixin, "value") instanceof List<?> types) {
            for (Object type : types) if (type instanceof Type t) targets.add(t.getInternalName());
        }
        if (value(mixin, "targets") instanceof List<?> names) {
            for (Object name : names) if (name instanceof String s) targets.add(s.replace('.', '/'));
        }
        return targets.size() == 1 ? targets.get(0) : null;
    }

    private static List<AnnotationNode> injectors(MethodNode method) {
        List<AnnotationNode> found = new ArrayList<>();
        for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (list == null) continue;
            for (AnnotationNode annotation : list) {
                boolean injector = annotation.desc.startsWith(INJECTION_PREFIX)
                        || annotation.desc.startsWith(MIXINEXTRAS_INJECTOR_PREFIX);
                if (injector && !annotation.desc.contains("/callback/")) {
                    found.add(annotation);
                }
            }
        }
        return found;
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (list == null) continue;
            for (AnnotationNode annotation : list) if (desc.equals(annotation.desc)) return annotation;
        }
        return null;
    }

    private static boolean hasAnnotation(List<AnnotationNode> visible, List<AnnotationNode> invisible,
            String desc) {
        for (List<AnnotationNode> list : Arrays.asList(visible, invisible)) {
            if (list == null) continue;
            for (AnnotationNode annotation : list) if (desc.equals(annotation.desc)) return true;
        }
        return false;
    }

    private static void removeAnnotation(MethodNode method, String desc) {
        if (method.visibleAnnotations != null) method.visibleAnnotations.removeIf(a -> desc.equals(a.desc));
        if (method.invisibleAnnotations != null) method.invisibleAnnotations.removeIf(a -> desc.equals(a.desc));
    }

    private static void addAnnotation(MethodNode method, String desc) {
        if (method.visibleAnnotations == null) method.visibleAnnotations = new ArrayList<>();
        method.visibleAnnotations.add(new AnnotationNode(desc));
    }

    private static MethodNode findMethod(ClassNode classNode, String name, String desc) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static void put(AnnotationNode annotation, String key, Object newValue) {
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                annotation.values.set(i + 1, newValue);
                return;
            }
        }
        annotation.values.add(key);
        annotation.values.add(newValue);
    }
}
