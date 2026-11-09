/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import com.retromod.util.McReflect;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
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
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
 *   <li>An {@code @Inject} handler whose target dropped, added, or retyped parameters is retyped
 *       when the handler never reads the changed slots, at any injection point, since every point
 *       passes the target's own arguments. Unread captures are dropped, inserted and appended
 *       parameters are added, and an unread callback is switched between {@code CallbackInfo} and
 *       {@code CallbackInfoReturnable} to match the new return type. The result is one of the two
 *       layouts Mixin accepts: every target argument, or the callback alone. MixinExtras sugar
 *       parameters after the callback, such as {@code @Local}, keep their place. A handler that
 *       never touches {@code this} becomes static when its target did.</li>
 *   <li>A {@code @Shadow} method the host replaced, whose old calls Retromod already sends to a
 *       static adapter, becomes a mixin-owned method that calls the same adapter. The 1.20.5
 *       {@code getAttribute(Attribute)} shadow in Porting Lib's {@code LivingEntityMixin} is one;
 *       its failure stopped the whole mixin from applying. Only class mixins qualify, because an
 *       accessor interface is not merged into its target.</li>
 *   <li>A {@code @Shadow} field the mixin never reads or writes is dropped when the host has no
 *       field of that name and type. Such a declaration does nothing but fail validation, which
 *       took Palladium's whole entity selector mixin down when 1.20.5 turned
 *       {@code EntitySelectorParser.predicate} into a list. A mixin with nested classes keeps it,
 *       because Mixin copies those into the target with their accesses.</li>
 *   <li>The removed {@code LivingEntity.getCurrentSwingDuration()} shadow becomes a mixin-owned
 *       method that reads the 26.3 swing state.</li>
 *   <li>The removed {@code BuiltInRegistries.registerSimple(key, lifecycle, bootstrap)} shadow
 *       becomes a mixin-owned method that registers through {@code internalRegister}, unless the
 *       mixin's own code, or a class of its mod that code initializes, needs something the host
 *       lacks.</li>
 *   <li>A {@code @Shadow} method the mixin never calls is dropped when the host has no method of
 *       that name and descriptor, unless the mixin adds interfaces or has nested classes.</li>
 *   <li>A {@code Gui} selector or shadow follows the 26.1 {@code render...} to {@code extract...}
 *       HUD rename, and a {@code Gui} mixin moves to {@code Hud} on 26.2 when every member it
 *       uses moved there.</li>
 *   <li>A selector or shadow follows the renames {@link MixinSelectorRenames} proves: the rows of
 *       its table and the same 26.1 {@code extract...} rename across the GUI package.</li>
 *   <li>A handler on an overloaded name is retyped to the one overload its captured arguments
 *       fit, and the selector names that overload.</li>
 *   <li>A {@code @ModifyReturnValue} handler whose only target now returns another type is
 *       removed, because Mixin refuses it and the refusal leaves the target class half changed.</li>
 *   <li>An {@code @Inject} handler written for a void target that may cancel is removed when its only
 *       target now returns an object, for the same reason.</li>
 *   <li>An {@code @Inject} handler with MixinExtras sugar parameters whose only target still refuses
 *       its layout is removed. MixinExtras strips the sugar before Mixin checks the layout, so the
 *       refusal leaves a merged handler that reads parameters it no longer has, and the target class
 *       fails verification when it loads (Moonlight's {@code getMobsAt} handler on 26.3).</li>
 * </ul>
 *
 * <p>It declines anything it cannot prove: overloaded targets, multiple targets, a handler that
 * reads a dropped capture or a capture two host arguments could fill, {@code locals} capture,
 * sliced injection points, parameter annotations other than sugar after the callback, a
 * {@code @Local} whose type a changed parameter has, a generic {@code LocalRef} sugar parameter
 * (its type argument lives in the Signature a retype drops), a {@code @Local} index the new
 * parameters may move, and {@code remap = false} scopes.
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
    private static final String SUGAR_PREFIX = "Lcom/llamalad7/mixinextras/sugar/";
    private static final String LOCAL_DESC = SUGAR_PREFIX + "Local;";
    private static final String REF_PACKAGE = "com/llamalad7/mixinextras/sugar/ref/";
    private static final String GENERIC_REF = REF_PACKAGE + "LocalRef";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String MODIFY_RETURN_VALUE_DESC =
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
    private static final String COERCE_DESC = "Lorg/spongepowered/asm/mixin/injection/Coerce;";

    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String SWING_ANIMATION = "net/minecraft/world/item/component/SwingAnimation";
    private static final String SWING_DESCRIPTION = "net/minecraft/world/entity/LivingEntity$SwingDescription";
    private static final String MODIFIED_SWING_DURATION = "(L" + SWING_ANIMATION + ";)I";

    private final FuzzyMethodResolver host;
    private final Map<RetromodTransformer.MethodKey, RetromodTransformer.MethodTarget> redirects;
    private final Map<String, String> classRedirects;
    private final Function<String, byte[]> jarClasses;

    public MixinHostDriftRepair(FuzzyMethodResolver host,
            Map<RetromodTransformer.MethodKey, RetromodTransformer.MethodTarget> redirects,
            Map<String, String> classRedirects) {
        this(host, redirects, classRedirects, null);
    }

    /** As above, with the classes of the mixin's jar, before translation, for the repairs that check what the mixin loads. */
    public MixinHostDriftRepair(FuzzyMethodResolver host,
            Map<RetromodTransformer.MethodKey, RetromodTransformer.MethodTarget> redirects,
            Map<String, String> classRedirects, Function<String, byte[]> jarClasses) {
        this.host = host;
        this.redirects = redirects == null ? Map.of() : redirects;
        this.classRedirects = classRedirects == null ? Map.of() : classRedirects;
        this.jarClasses = jarClasses;
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
                + dropRefusedReturnModifiers(classNode, target)
                + dropCancellingHandlersOnObjectResults(classNode, target)
                + widenReadOnlyShadowFields(classNode, target)
                + dropUnreferencedShadowFields(classNode, target)
                + bridgeSwingDuration(classNode, target)
                + bridgeLifecycleRegisterSimple(classNode, target)
                // Last, so every bridge above has claimed the shadows it can still serve.
                + dropUnreferencedShadowMethods(classNode, target);
        for (MethodNode method : classNode.methods) {
            if (repairInjectHandler(method, target)) repairs++;
            if (repairGrownInvokeTarget(method)) repairs++;
        }
        // After the retype, so only a handler it could not fit is removed.
        repairs += dropRefusedSugarInjects(classNode, target);
        if (repairs == 0) return classBytes;
        return write(classNode, classBytes, repairs, target);
    }

    private static byte[] write(ClassNode classNode, byte[] original, int repairs, String target) {
        try {
            ClassWriter writer = new com.retromod.util.SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
            classNode.accept(writer);
            LOGGER.debug(RetromodLog.DETAIL, "Repaired {} Mixin member(s) in {} for the host's current {} shape",
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
                    if (replacement == null) {
                        replacement = tableRenamedSelector(target, selector);
                        // An @Inject handler must fit the renamed method, as it is or once retyped.
                        if (replacement != null && INJECT_DESC.equals(injector.desc)
                                && !handlerFollows(method, target, selector, replacement)) {
                            continue;
                        }
                    }
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

    /**
     * Whether an {@code @Inject} handler fits the method {@code replacement} names: unchanged when
     * the descriptor stayed, otherwise as it is or after {@link #planInject}.
     */
    private boolean handlerFollows(MethodNode handler, String target, String selector, String replacement) {
        int paren = replacement.indexOf('(');
        String name = paren < 0 ? replacement : replacement.substring(0, paren);
        String desc = paren < 0 ? null : replacement.substring(paren);
        FuzzyMethodResolver.MethodInfo live = null;
        for (FuzzyMethodResolver.MethodInfo method : host.getDeclaredMethods(target)) {
            if (!method.name().equals(name) || (desc != null && !method.descriptor().equals(desc))) continue;
            if (live != null) return false;
            live = method;
        }
        if (live == null) return false;
        int oldParen = selector.indexOf('(');
        if (oldParen >= 0 && selector.substring(oldParen).equals(live.descriptor())) return true;
        boolean fits = MixinRefmapTargetRepair.fits(checkedArguments(handler),
                (handler.access & Opcodes.ACC_STATIC) != 0, live);
        return fits || (retypableInject(handler) != null && planInject(handler, live) != null);
    }

    /** The handler's parameters without MixinExtras sugar, which is what Mixin checks against the target. */
    static List<Type> checkedArguments(MethodNode handler) {
        List<Type> arguments = new ArrayList<>();
        Type[] declared = Type.getArgumentTypes(handler.desc);
        for (int i = 0; i < declared.length; i++) {
            if (!isSugarParameter(handler, i)) arguments.add(declared[i]);
        }
        return arguments;
    }

    private static boolean isSugarParameter(MethodNode handler, int index) {
        return hasSugar(handler.visibleParameterAnnotations, index)
                || hasSugar(handler.invisibleParameterAnnotations, index);
    }

    private static boolean hasSugar(List<AnnotationNode>[] annotations, int index) {
        if (annotations == null || index >= annotations.length || annotations[index] == null) return false;
        for (AnnotationNode annotation : annotations[index]) {
            if (annotation.desc.startsWith(SUGAR_PREFIX)) return true;
        }
        return false;
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
     * A selector moved by {@link MixinSelectorRenames}: the new name, or name and descriptor when
     * the descriptor changed or the name is overloaded. The handler is retyped to it below.
     */
    private String tableRenamedSelector(String target, String selector) {
        int paren = selector.indexOf('(');
        String name = paren < 0 ? selector : selector.substring(0, paren);
        String desc = paren < 0 ? null : selector.substring(paren);
        if (name.isEmpty() || name.startsWith("<") || name.chars().anyMatch(c -> c == ';' || c == '*' || c == ':')) {
            return null;
        }
        if (desc != null && (!namesOnlyHostTypes(desc) || host.hasMethod(target, name, desc))) return null;
        if (desc == null && host.hasMethodName(target, name)) return null;
        FuzzyMethodResolver.MethodInfo renamed = MixinSelectorRenames.renamed(host, target, name, desc);
        if (renamed == null) return null;
        // A bare name picks the first overload, so a renamed overload keeps its descriptor.
        long sameName = host.getDeclaredMethods(target).stream().filter(m -> m.name().equals(renamed.name())).count();
        return desc == null && sameName == 1 ? renamed.name() : renamed.name() + renamed.descriptor();
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
        String extract = guiExtractName(owner, holder, name, desc);
        if (extract != null || !owner.equals(holder)) return extract;
        // Shadows and qualified selectors keep their descriptor, so only a same-descriptor rename fits.
        FuzzyMethodResolver.MethodInfo renamed = MixinSelectorRenames.renamed(host, owner, name, desc);
        return renamed != null && renamed.descriptor().equals(desc) ? renamed.name() : null;
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
        // A nested class can use a shadow this class never names. Mixin copies it into the target
        // and points it there, so dropping the shadow would trade a clean rejection for a
        // NoSuchFieldError or NoSuchMethodError at run time.
        if (hasNestedClasses(classNode)) return 0;
        int before = classNode.fields.size();
        classNode.fields.removeIf(field ->
                hasAnnotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW_DESC)
                        && !host.hasField(target, field.name, field.desc)
                        && !referencesField(classNode, field));
        return before - classNode.fields.size();
    }

    /** Drops a {@code @Shadow} method the mixin never calls when the host has no such method. */
    private int dropUnreferencedShadowMethods(ClassNode classNode, String target) {
        // Such a shadow only fails validation, which takes the whole mixin down: Farmer's Delight
        // 1.20.1 declares getDispenseMethod(ItemStack), which later gained a Level parameter.
        // A shadow can also implement a method of an interface the mixin adds, which callers outside
        // the class reach, so a class that adds interfaces keeps every shadow, as one with nested
        // classes does for the reason given on the field drop above.
        if ((classNode.access & Opcodes.ACC_INTERFACE) != 0
                || (classNode.interfaces != null && !classNode.interfaces.isEmpty())
                || hasNestedClasses(classNode)) {
            return 0;
        }
        int before = classNode.methods.size();
        classNode.methods.removeIf(method ->
                hasAnnotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW_DESC)
                        && !host.hasMethod(target, method.name, method.desc)
                        && !referencesMethod(classNode, method));
        return before - classNode.methods.size();
    }

    /** Whether the mixin has inner or anonymous classes, which Mixin copies into the target. */
    private static boolean hasNestedClasses(ClassNode classNode) {
        if (classNode.nestMembers != null && !classNode.nestMembers.isEmpty()) return true;
        if (classNode.innerClasses == null) return false;
        String prefix = classNode.name + "$";
        return classNode.innerClasses.stream().anyMatch(inner -> inner.name.startsWith(prefix));
    }

    /** Whether any code in the class calls the method or takes a handle to it. */
    private static boolean referencesMethod(ClassNode classNode, MethodNode shadow) {
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(classNode.name)
                        && call.name.equals(shadow.name) && call.desc.equals(shadow.desc)) {
                    return true;
                }
                if (insn instanceof InvokeDynamicInsnNode indy && namesMethod(indy.bsmArgs, classNode, shadow)) {
                    return true;
                }
                if (insn instanceof LdcInsnNode ldc && namesMethod(new Object[] {ldc.cst}, classNode, shadow)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean namesMethod(Object[] constants, ClassNode classNode, MethodNode shadow) {
        if (constants == null) return false;
        for (Object constant : constants) {
            if (constant instanceof Handle handle && handle.getOwner().equals(classNode.name)
                    && handle.getName().equals(shadow.name) && handle.getDesc().equals(shadow.desc)) {
                return true;
            }
        }
        return false;
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

    // ---- BuiltInRegistries.registerSimple with a Lifecycle ----------------------------------

    private static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String MAPPED_REGISTRY = "net/minecraft/core/MappedRegistry";
    private static final String L_RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String L_LIFECYCLE = "Lcom/mojang/serialization/Lifecycle;";
    private static final String L_BOOTSTRAP = "Lnet/minecraft/core/registries/BuiltInRegistries$RegistryBootstrap;";
    private static final String LIFECYCLE_REGISTER_SIMPLE =
            "(" + L_RESOURCE_KEY + L_LIFECYCLE + L_BOOTSTRAP + ")Lnet/minecraft/core/Registry;";
    private static final String INTERNAL_REGISTER = "(" + L_RESOURCE_KEY
            + "Lnet/minecraft/core/WritableRegistry;" + L_BOOTSTRAP + ")Lnet/minecraft/core/WritableRegistry;";
    private static final String MAPPED_REGISTRY_CTOR = "(" + L_RESOURCE_KEY + L_LIFECYCLE + "Z)V";

    /** Turns a shadow of the removed {@code registerSimple(key, lifecycle, bootstrap)} into a mixin-owned copy. */
    private int bridgeLifecycleRegisterSimple(ClassNode classNode, String target) {
        if (!BUILT_IN_REGISTRIES.equals(target) || (classNode.access & Opcodes.ACC_INTERFACE) != 0) return 0;
        MethodNode shadow = findMethod(classNode, "registerSimple", LIFECYCLE_REGISTER_SIMPLE);
        if (shadow == null || (shadow.access & Opcodes.ACC_STATIC) == 0
                || !hasAnnotation(shadow.visibleAnnotations, shadow.invisibleAnnotations, SHADOW_DESC)
                || host.hasMethod(BUILT_IN_REGISTRIES, "registerSimple", LIFECYCLE_REGISTER_SIMPLE)
                || !host.hasMethod(BUILT_IN_REGISTRIES, "internalRegister", INTERNAL_REGISTER)
                || !host.hasMethod(MAPPED_REGISTRY, "<init>", MAPPED_REGISTRY_CTOR)) {
            return 0;
        }
        // The repaired mixin runs in Bootstrap, before any mod loads, so a call or class that cannot
        // link ends the game there. Mixin rejected the unrepaired mixin and the game ran without it,
        // as it still does when this host lacks something its code, or a mod class it loads, uses.
        String missing = new MixinLinkCheck(host, classRedirects, jarClasses).firstMissing(classNode, shadow);
        if (missing != null) {
            LOGGER.debug(RetromodLog.DETAIL, "Left {} unrepaired: its code needs {}, which this host lacks",
                    classNode.name, missing);
            return 0;
        }

        // Shadow the private internalRegister, unless the mixin already does.
        if (findMethod(classNode, "internalRegister", INTERNAL_REGISTER) == null) {
            MethodNode internal = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "internalRegister", INTERNAL_REGISTER, null, null);
            internal.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(SHADOW_DESC)));
            // A static shadow cannot be abstract, so it gets a body Mixin discards.
            internal.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            internal.instructions.add(new InsnNode(Opcodes.ARETURN));
            internal.maxStack = 1;
            internal.maxLocals = 3;
            classNode.methods.add(internal);
        }

        // TerraBlender 1.20.1 calls the removed overload from its own injection at the head of the
        // kept one, so delegating to the kept one would recurse. The copy builds the registry the
        // way the removed overload did. Mixin merges a static unique method only when it is private.
        removeAnnotation(shadow, SHADOW_DESC);
        addAnnotation(shadow, UNIQUE_DESC);
        shadow.access = (shadow.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT))
                | Opcodes.ACC_PRIVATE;
        shadow.instructions = lifecycleRegisterSimpleBody(classNode.name);
        shadow.tryCatchBlocks = new ArrayList<>();
        shadow.localVariables = null;
        shadow.maxStack = 6;
        shadow.maxLocals = 3;
        return 1;
    }

    /** {@code return internalRegister(key, new MappedRegistry<>(key, lifecycle, false), bootstrap);} */
    private static InsnList lifecycleRegisterSimpleBody(String mixinName) {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new TypeInsnNode(Opcodes.NEW, MAPPED_REGISTRY));
        body.add(new InsnNode(Opcodes.DUP));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new InsnNode(Opcodes.ICONST_0));
        body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MAPPED_REGISTRY, "<init>", MAPPED_REGISTRY_CTOR, false));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mixinName, "internalRegister", INTERNAL_REGISTER, false));
        body.add(new InsnNode(Opcodes.ARETURN));
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

    // ---- refused return-value modifiers -------------------------------------------------

    /**
     * Removes each {@code @ModifyReturnValue} handler whose only target now returns another type.
     * MixinExtras refuses such a handler whatever it does, and in a config Retromod made optional
     * the refusal leaves the target half changed: a handler with {@code @Local} parameters is already
     * merged without them, so the target class fails verification when it loads (26.3's
     * {@code VillagerType.byBiome} now returns a key). Removing it lets the mixin's other members apply.
     */
    private int dropRefusedReturnModifiers(ClassNode classNode, String target) {
        int dropped = 0;
        for (java.util.Iterator<MethodNode> methods = classNode.methods.iterator(); methods.hasNext(); ) {
            MethodNode handler = methods.next();
            AnnotationNode modifier = annotation(handler, MODIFY_RETURN_VALUE_DESC);
            if (modifier == null || Boolean.FALSE.equals(value(modifier, "remap")) || mayCoerce(handler)) {
                continue;
            }
            FuzzyMethodResolver.MethodInfo live = soleLiveTarget(modifier, target);
            if (live == null) continue;
            // The handler returns, and takes first, exactly the type the target returns.
            Type liveReturn = Type.getReturnType(live.descriptor());
            if (liveReturn.equals(Type.getReturnType(handler.desc)) || isCalledIn(classNode, handler)) continue;
            methods.remove();
            dropped++;
            // INFO, not a detail line: the mod loses what this handler did to the value.
            LOGGER.info("Removed the Mixin handler {}.{} because its target {}.{} now returns {}, which Mixin refuses. "
                    + "The mod no longer changes that value.", classNode.name.replace('/', '.'), handler.name,
                    target.replace('/', '.'), live.name(), liveReturn.getClassName());
        }
        return dropped;
    }

    /**
     * Removes each {@code @Inject} handler written for a void target that may cancel, when its only
     * target now returns an object. Mixin refuses its {@code CallbackInfo}, and that refusal can drop
     * or half apply the whole mixin; retyping it would make a cancel() return null to the target's callers.
     */
    private int dropCancellingHandlersOnObjectResults(ClassNode classNode, String target) {
        int dropped = 0;
        for (java.util.Iterator<MethodNode> methods = classNode.methods.iterator(); methods.hasNext(); ) {
            MethodNode handler = methods.next();
            AnnotationNode inject = retypableInject(handler);
            if (inject == null) continue;
            FuzzyMethodResolver.MethodInfo live = soleLiveTarget(inject, target);
            if (live == null) continue;
            Type liveReturn = Type.getReturnType(live.descriptor());
            if (liveReturn.getSort() != Type.OBJECT && liveReturn.getSort() != Type.ARRAY) continue;
            Type[] args = Type.getArgumentTypes(handler.desc);
            int index = callbackIndex(args);
            int callback = slots(args, (handler.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1)[index];
            if (!args[index].getInternalName().equals(CALLBACK_INFO) || !mayCancel(handler, callback)
                    || isCalledIn(classNode, handler)) {
                continue;
            }
            methods.remove();
            dropped++;
            // INFO, not a detail line: the mod loses what this handler did.
            LOGGER.info("Removed the Mixin handler {}.{} because its target {}.{} now returns {} and the handler "
                    + "may cancel it, which Mixin refuses. The mod no longer runs that handler.",
                    classNode.name.replace('/', '.'), handler.name, target.replace('/', '.'), live.name(),
                    liveReturn.getClassName());
        }
        return dropped;
    }

    /** Whether {@code @Coerce} on the handler or a parameter lets Mixin accept a related type. */
    private static boolean mayCoerce(MethodNode handler) {
        if (hasAnnotation(handler.visibleAnnotations, handler.invisibleAnnotations, COERCE_DESC)) return true;
        for (List<AnnotationNode>[] parameters : Arrays.asList(handler.visibleParameterAnnotations,
                handler.invisibleParameterAnnotations)) {
            if (parameters == null) continue;
            for (List<AnnotationNode> annotations : parameters) {
                if (hasAnnotation(annotations, null, COERCE_DESC)) return true;
            }
        }
        return false;
    }

    private static boolean isCalledIn(ClassNode classNode, MethodNode handler) {
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(classNode.name)
                        && call.name.equals(handler.name) && call.desc.equals(handler.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- @Inject handler layout ------------------------------------------------------------

    /**
     * Retypes one {@code @Inject} handler to its live target's layout when the handler provably
     * does not depend on the parts that changed.
     */
    private boolean repairInjectHandler(MethodNode handler, String target) {
        AnnotationNode inject = retypableInject(handler);
        if (inject == null) return false;
        FuzzyMethodResolver.MethodInfo live = soleLiveTarget(inject, target);
        if (live != null) {
            InjectPlan plan = planInject(handler, live);
            if (plan == null || !plan.changesHandler()) return false;
            applyInject(handler, inject, plan, false);
            return true;
        }
        // On an overloaded name, the handler's captured arguments must fit exactly one overload.
        List<FuzzyMethodResolver.MethodInfo> overloads = overloads(inject, target);
        InjectPlan only = null;
        for (FuzzyMethodResolver.MethodInfo overload : overloads) {
            InjectPlan plan = planInject(handler, overload);
            if (plan == null || !plan.keepsCaptures()) continue;
            if (only != null) return false;
            only = plan;
        }
        // A handler that already fits the overload Mixin picks for a bare name needs nothing.
        if (only == null || (!only.changesHandler() && only.live().equals(overloads.get(0)))) return false;
        applyInject(handler, inject, only, true);
        return true;
    }

    /** How a handler is retyped to {@code live}: its new descriptor and where each parameter slot moves. */
    private record InjectPlan(FuzzyMethodResolver.MethodInfo live, String newDesc, boolean keepsCaptures,
            boolean makeStatic, boolean callbackRetyped, Type liveCallback, Map<Integer, Integer> slotMap,
            int oldParamEnd, int newParamEnd, int callbackSlot, boolean changesHandler,
            int oldCallback, int newCallback, int sugarCount, int newCount) {}

    /** The retyping of {@code handler} to {@code live}, or {@code null} when the handler depends on what changed. */
    private InjectPlan planInject(MethodNode handler, FuzzyMethodResolver.MethodInfo live) {
        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        int callback = callbackIndex(handlerArgs);
        // Sugar parameters after the callback are filled by MixinExtras from the target, so they stay as they are.
        Type[] sugar = Arrays.copyOfRange(handlerArgs, callback + 1, handlerArgs.length);

        boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
        boolean targetStatic = (live.access() & Opcodes.ACC_STATIC) != 0;
        if (handlerStatic && !targetStatic) return null;
        boolean makeStatic = targetStatic && !handlerStatic;
        if (makeStatic && readsSlot(handler, 0)) return null;

        Type[] liveArgs = Type.getArgumentTypes(live.descriptor());
        Type liveReturn = Type.getReturnType(live.descriptor());
        Type liveCallback = Type.getObjectType(liveReturn.getSort() == Type.VOID
                ? CALLBACK_INFO : CALLBACK_INFO_RETURNABLE);
        Type[] captures = Arrays.copyOf(handlerArgs, callback);

        int base = handlerStatic ? 0 : 1;
        int[] oldSlots = slots(handlerArgs, base);
        int[] captureMap = alignCaptures(captures, liveArgs);
        boolean inserted = captureMap != null;
        if (captureMap == null) captureMap = alignedPrefix(captures, liveArgs);
        if (captureMap == null) captureMap = alignRetyped(captures, liveArgs, handler, oldSlots);
        int kept = captureMap == null ? 0 : captureMap.length;
        boolean callbackRetyped = !handlerArgs[callback].equals(liveCallback);
        // Without a pure insertion, a read capture whose type another old capture shares may now be that one's argument.
        if (!inserted) {
            for (int i = 0; i < kept; i++) {
                if (readsSlot(handler, oldSlots[i]) && count(captures, captures[i]) > 1) return null;
            }
        }
        // MixinExtras counts the target's parameters among its locals, so a @Local could bind to a changed one.
        if (localMeetsChangedParameter(handler, callback, sugar, captures, liveArgs)) return null;
        // Captures past the kept prefix are dropped, so the handler must never read them.
        for (int i = kept; i < captures.length; i++) {
            if (readsSlot(handler, oldSlots[i])) return null;
        }
        // A capture that also fits a later argument could mean either, so the handler must not read it.
        if (kept > 0) {
            int[] latest = alignCapturesFromRight(Arrays.copyOf(captures, kept), liveArgs);
            for (int i = 0; i < kept; i++) {
                if (latest[i] != captureMap[i] && readsSlot(handler, oldSlots[i])) return null;
            }
        }
        // A read callback can become returnable, a subtype, unless a cancel() would make the target
        // return null for an object result. It cannot lose its return value.
        if (callbackRetyped && readsSlot(handler, oldSlots[callback])) {
            boolean gainsReturn = liveCallback.getInternalName().equals(CALLBACK_INFO_RETURNABLE);
            boolean nullOnCancel = liveReturn.getSort() == Type.OBJECT || liveReturn.getSort() == Type.ARRAY;
            if (!gainsReturn || (nullOnCancel && mayCancel(handler, oldSlots[callback]))) return null;
        }

        // Mixin accepts every target argument or the callback alone, never a prefix. Kept captures
        // bring the whole list when the handler reads one or they already end at the last argument.
        boolean keepCaptures = kept > 0
                && (readsAnySlot(handler, oldSlots, kept) || captureMap[kept - 1] == liveArgs.length - 1);
        List<Type> newArgs = new ArrayList<>();
        if (keepCaptures) newArgs.addAll(Arrays.asList(liveArgs));
        newArgs.add(liveCallback);
        int newCallback = newArgs.size() - 1;
        newArgs.addAll(Arrays.asList(sugar));
        Type[] newTypes = newArgs.toArray(new Type[0]);
        String newDesc = Type.getMethodDescriptor(Type.VOID_TYPE, newTypes);
        int newBase = (handlerStatic || makeStatic) ? 0 : 1;
        int[] newSlots = slots(newTypes, newBase);

        Map<Integer, Integer> slotMap = new LinkedHashMap<>();
        if (!handlerStatic && !makeStatic) slotMap.put(0, 0);
        if (keepCaptures) {
            // A retyped capture is never read, so its slot gets no new home and its old debug entry goes.
            for (int i = 0; i < captureMap.length; i++) {
                if (captures[i].equals(liveArgs[captureMap[i]])) slotMap.put(oldSlots[i], newSlots[captureMap[i]]);
            }
        }
        slotMap.put(oldSlots[callback], newSlots[newCallback]);
        for (int i = 0; i < sugar.length; i++) slotMap.put(oldSlots[callback + 1 + i], newSlots[newCallback + 1 + i]);
        int oldParamEnd = parameterEnd(handlerArgs, oldSlots);
        int newParamEnd = parameterEnd(newTypes, newSlots);
        // Any other parameter slot the code names, such as the second half of a long, has no new home.
        if (namesUnmappedSlot(handler, slotMap, oldParamEnd)) return null;
        boolean changes = !newDesc.equals(handler.desc) || makeStatic;
        // MixinExtras reads a LocalRef's type argument from the Signature a retype drops, and a set
        // @Local index points at a slot the host's parameters may have moved.
        if (changes && (hasGenericRef(sugar) || localIndexMayMove(handler, callback, sugar, captures, liveArgs, makeStatic))) {
            return null;
        }
        return new InjectPlan(live, newDesc, keepCaptures, makeStatic, callbackRetyped, liveCallback, slotMap,
                oldParamEnd, newParamEnd, newSlots[newCallback], changes, callback, newCallback, sugar.length,
                newTypes.length);
    }

    /** Retypes the handler as planned; {@code pinOverload} names the chosen overload in the selector. */
    private static void applyInject(MethodNode handler, AnnotationNode inject, InjectPlan plan, boolean pinOverload) {
        remapLocals(handler, plan.slotMap(), plan.oldParamEnd(), plan.newParamEnd() - plan.oldParamEnd());
        // The callback's local entry names its new type, as the descriptor does.
        if (plan.callbackRetyped() && handler.localVariables != null) {
            for (LocalVariableNode local : handler.localVariables) {
                if (local.index == plan.callbackSlot()) {
                    local.desc = plan.liveCallback().getDescriptor();
                    local.signature = null;
                }
            }
        }
        moveSugarAnnotations(handler, plan);
        handler.desc = plan.newDesc();
        handler.signature = null;
        // The optional MethodParameters attribute still lists the old parameters.
        handler.parameters = null;
        if (pinOverload) {
            List<Object> selectors = new ArrayList<>((List<?>) value(inject, "method"));
            selectors.set(0, plan.live().name() + plan.live().descriptor());
            put(inject, "method", selectors);
        } else {
            bareSelector(inject, plan.live());
        }
        if (plan.makeStatic()) handler.access |= Opcodes.ACC_STATIC;
        handler.maxLocals = Math.max(0, handler.maxLocals + plan.newParamEnd() - plan.oldParamEnd());
    }

    /** The host overloads a lone selector names, when its name is declared more than once. */
    private List<FuzzyMethodResolver.MethodInfo> overloads(AnnotationNode inject, String target) {
        String selector = soleSelector(inject);
        if (selector == null) return List.of();
        int paren = selector.indexOf('(');
        String name = paren < 0 ? selector : selector.substring(0, paren);
        if (name.isEmpty() || name.contains(";") || name.contains("*") || name.startsWith("<")) return List.of();
        if (paren >= 0 && !namesOnlyHostTypes(selector.substring(paren))) return List.of();
        // A selector naming an overload the host still declares is already exact.
        String desc = paren < 0 ? null : selector.substring(paren);
        if (desc != null && host.getDeclaredMethods(target).stream()
                .anyMatch(method -> method.name().equals(name) && method.descriptor().equals(desc))) {
            return List.of();
        }
        Map<String, FuzzyMethodResolver.MethodInfo> found = new LinkedHashMap<>();
        for (FuzzyMethodResolver.MethodInfo method : host.getDeclaredMethods(target)) {
            if (method.name().equals(name)) found.putIfAbsent(method.descriptor(), method);
        }
        return found.size() > 1 ? List.copyOf(found.values()) : List.of();
    }

    /**
     * The {@code @Inject} of a handler this repair may retype, or {@code null}: no locals capture or
     * slice, no parameter annotations up to the callback, and only MixinExtras sugar after it. The
     * refmap repair applies the same rule, so both sides decline alike.
     */
    static AnnotationNode retypableInject(MethodNode handler) {
        AnnotationNode inject = annotation(handler, INJECT_DESC);
        if (inject == null || Boolean.FALSE.equals(value(inject, "remap"))
                || value(inject, "locals") != null || value(inject, "slice") != null
                || atPoints(inject).isEmpty()) {
            return null;
        }
        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        int callback = callbackIndex(handlerArgs);
        return callback >= 0 && onlySugarFollows(handler, callback, handlerArgs.length) ? inject : null;
    }

    /** Whether no parameter up to the callback is annotated and each one after it carries only MixinExtras sugar. */
    private static boolean onlySugarFollows(MethodNode handler, int callback, int count) {
        // An annotable count other than the descriptor's shifts the annotation indexes, so it is not read.
        if (!coversEveryParameter(handler.visibleAnnotableParameterCount, count)
                || !coversEveryParameter(handler.invisibleAnnotableParameterCount, count)) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            List<AnnotationNode> annotations = parameterAnnotations(handler, i);
            boolean sugarOnly = isSugarParameter(handler, i)
                    && annotations.stream().allMatch(a -> a.desc.startsWith(SUGAR_PREFIX));
            if (i <= callback ? !annotations.isEmpty() : !sugarOnly) return false;
        }
        return true;
    }

    private static boolean coversEveryParameter(int annotableCount, int count) {
        return annotableCount == 0 || annotableCount == count;
    }

    private static List<AnnotationNode> parameterAnnotations(MethodNode handler, int index) {
        List<AnnotationNode> all = new ArrayList<>();
        for (List<AnnotationNode>[] lists : Arrays.asList(handler.visibleParameterAnnotations,
                handler.invisibleParameterAnnotations)) {
            if (lists != null && index < lists.length && lists[index] != null) all.addAll(lists[index]);
        }
        return all;
    }

    /**
     * Whether a {@code @Local} sugar parameter has a type whose count among the target's parameters
     * changed, where MixinExtras could now bind it to a parameter instead of the local it meant.
     */
    private static boolean localMeetsChangedParameter(MethodNode handler, int callback, Type[] sugar,
            Type[] captures, Type[] liveArgs) {
        List<Type> changed = changedParameterTypes(captures, liveArgs);
        if (changed.isEmpty()) return false;
        for (int i = 0; i < sugar.length; i++) {
            if (!parameterAnnotations(handler, callback + 1 + i).stream().anyMatch(a -> LOCAL_DESC.equals(a.desc))) {
                continue;
            }
            // The generic LocalRef hides the local's type, so any change may reach it; a typed one names it.
            Type local = localType(sugar[i]);
            if (local == null || changed.contains(local)) return true;
        }
        return false;
    }

    /** The type a @Local parameter captures, or {@code null} for the generic LocalRef, whose type is not in the descriptor. */
    private static Type localType(Type parameter) {
        if (parameter.getSort() != Type.OBJECT || !parameter.getInternalName().startsWith(REF_PACKAGE)) return parameter;
        return switch (parameter.getInternalName().substring(REF_PACKAGE.length())) {
            case "LocalBooleanRef" -> Type.BOOLEAN_TYPE;
            case "LocalByteRef" -> Type.BYTE_TYPE;
            case "LocalCharRef" -> Type.CHAR_TYPE;
            case "LocalShortRef" -> Type.SHORT_TYPE;
            case "LocalIntRef" -> Type.INT_TYPE;
            case "LocalLongRef" -> Type.LONG_TYPE;
            case "LocalFloatRef" -> Type.FLOAT_TYPE;
            case "LocalDoubleRef" -> Type.DOUBLE_TYPE;
            default -> null;
        };
    }

    private static boolean hasGenericRef(Type[] sugar) {
        for (Type type : sugar) {
            if (type.getSort() == Type.OBJECT && type.getInternalName().equals(GENERIC_REF)) return true;
        }
        return false;
    }

    /** Whether a {@code @Local(index = ...)} may name another slot, because the target's parameters take a different number of slots. */
    private static boolean localIndexMayMove(MethodNode handler, int callback, Type[] sugar, Type[] captures,
            Type[] liveArgs, boolean becameStatic) {
        // Without captures the handler never said what the old parameters were, and a target that became
        // static lost its this slot, which moves every local down by one.
        boolean slotsChanged = captures.length == 0 || slotCount(captures) != slotCount(liveArgs) || becameStatic;
        if (!slotsChanged) return false;
        for (int i = 0; i < sugar.length; i++) {
            for (AnnotationNode annotation : parameterAnnotations(handler, callback + 1 + i)) {
                if (LOCAL_DESC.equals(annotation.desc) && value(annotation, "index") != null) return true;
            }
        }
        return false;
    }

    private static int slotCount(Type[] types) {
        int slots = 0;
        for (Type type : types) slots += type.getSize();
        return slots;
    }

    /** The types whose count differs between the handler's captures and the target's parameters. */
    private static List<Type> changedParameterTypes(Type[] captures, Type[] liveArgs) {
        List<Type> changed = new ArrayList<>();
        for (Type type : concat(captures, liveArgs)) {
            if (count(captures, type) != count(liveArgs, type) && !changed.contains(type)) changed.add(type);
        }
        return changed;
    }

    private static Type[] concat(Type[] first, Type[] second) {
        Type[] all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static int count(Type[] types, Type type) {
        int count = 0;
        for (Type each : types) if (each.equals(type)) count++;
        return count;
    }

    /** Moves the sugar annotations to the parameters' new indexes; nothing up to the callback has any. */
    private static void moveSugarAnnotations(MethodNode handler, InjectPlan plan) {
        handler.visibleParameterAnnotations = movedAnnotations(handler.visibleParameterAnnotations, plan);
        handler.invisibleParameterAnnotations = movedAnnotations(handler.invisibleParameterAnnotations, plan);
        // Zero lets the writer count the new descriptor's parameters.
        handler.visibleAnnotableParameterCount = 0;
        handler.invisibleAnnotableParameterCount = 0;
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationNode>[] movedAnnotations(List<AnnotationNode>[] old, InjectPlan plan) {
        if (old == null) return null;
        List<AnnotationNode>[] moved = (List<AnnotationNode>[]) new List<?>[plan.newCount()];
        for (int i = 0; i < plan.sugarCount(); i++) moved[plan.newCallback() + 1 + i] = old[plan.oldCallback() + 1 + i];
        return moved;
    }

    /** The first local slot after the parameters. */
    private static int parameterEnd(Type[] args, int[] slots) {
        return args.length == 0 ? 0 : slots[args.length - 1] + args[args.length - 1].getSize();
    }

    // ---- refused sugar handlers -----------------------------------------------------------

    /**
     * Removes each {@code @Inject} handler with MixinExtras sugar parameters whose only target refuses
     * its layout. MixinExtras strips the sugar before Mixin checks the layout, and in a config Retromod
     * made optional the refusal keeps the merged handler, which then reads parameters it no longer has.
     */
    private int dropRefusedSugarInjects(ClassNode classNode, String target) {
        // A vanilla jar cannot speak for a method Forge or NeoForge patched, so only the right index removes.
        if (!indexMatchesTargetLoader()) return 0;
        int dropped = 0;
        for (java.util.Iterator<MethodNode> methods = classNode.methods.iterator(); methods.hasNext(); ) {
            MethodNode handler = methods.next();
            AnnotationNode inject = annotation(handler, INJECT_DESC);
            if (inject == null || Boolean.FALSE.equals(value(inject, "remap"))
                    || value(inject, "locals") != null || mayCoerce(handler)) {
                continue;
            }
            Type[] args = Type.getArgumentTypes(handler.desc);
            int callback = callbackIndex(args);
            if (callback < 0 || callback == args.length - 1 || !onlySugarFollows(handler, callback, args.length)) {
                continue;
            }
            FuzzyMethodResolver.MethodInfo live = soleLiveTarget(inject, target);
            boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
            if (live == null || MixinRefmapTargetRepair.fits(checkedArguments(handler), isStatic, live)
                    || isCalledIn(classNode, handler)) {
                continue;
            }
            methods.remove();
            dropped++;
            // INFO, not a detail line: the mod loses what this handler did.
            LOGGER.info("Removed the Mixin handler {}.{} because its target {}.{} now takes ({}), which Mixin "
                    + "refuses. The mod no longer runs that handler.", classNode.name.replace('/', '.'),
                    handler.name, target.replace('/', '.'), live.name(), readableArguments(live.descriptor()));
        }
        return dropped;
    }

    /**
     * Whether the index shows the classes the mod will run against: the running game's, a vanilla
     * jar for Fabric and Quilt, or for Forge and NeoForge a client their patches went into.
     */
    private boolean indexMatchesTargetLoader() {
        if (!McReflect.isNeoForge() && !McReflect.isForge() && !McReflect.isForgeTarget()) return true;
        // Both loaders patch Item to implement their own extension interface; a vanilla jar lacks it.
        for (String type : host.directSupertypes(ITEM)) {
            if (type.startsWith("net/neoforged/") || type.startsWith("net/minecraftforge/")) return true;
        }
        return false;
    }

    private static String readableArguments(String descriptor) {
        List<String> names = new ArrayList<>();
        for (Type type : Type.getArgumentTypes(descriptor)) names.add(type.getClassName());
        return String.join(", ", names);
    }

    /** The one selector of {@code inject}, or {@code null} when it has several or none. */
    static String soleSelector(AnnotationNode inject) {
        return value(inject, "method") instanceof List<?> list && list.size() == 1
                && list.get(0) instanceof String selector ? selector : null;
    }

    /**
     * Maps each captured parameter to its earliest live index when the live parameters are the
     * captured ones with others inserted, in order. Returns {@code null} when they cannot be aligned.
     */
    private static int[] alignCaptures(Type[] captures, Type[] liveArgs) {
        int[] map = new int[captures.length];
        int live = 0;
        for (int i = 0; i < captures.length; i++) {
            while (live < liveArgs.length && !liveArgs[live].equals(captures[i])) live++;
            if (live == liveArgs.length) return null;
            map[i] = live++;
        }
        return map;
    }

    /**
     * The same alignment taken from the right, so each capture gets its latest live index. A
     * capture whose two indexes differ could be either argument: old {@code foo(BlockPos)} against
     * a host {@code foo(BlockPos, BlockPos)}. Callers pass captures that align from the left.
     */
    private static int[] alignCapturesFromRight(Type[] captures, Type[] liveArgs) {
        int[] map = new int[captures.length];
        int live = liveArgs.length - 1;
        for (int i = captures.length - 1; i >= 0; i--) {
            while (live >= 0 && !liveArgs[live].equals(captures[i])) live--;
            map[i] = live--;
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

    /**
     * Maps each capture to its own position when the target kept its parameter count and retyped
     * only parameters the handler never reads: 26.3 turned {@code ChunkGenerator.getMobsAt}'s
     * {@code Holder<Biome>} into a {@code Level}. Returns {@code null} when a read capture changed
     * type or could also be another argument of the same type.
     */
    private static int[] alignRetyped(Type[] captures, Type[] liveArgs, MethodNode handler, int[] slots) {
        if (captures.length == 0 || captures.length != liveArgs.length) return null;
        int[] map = new int[captures.length];
        for (int i = 0; i < captures.length; i++) {
            boolean read = readsSlot(handler, slots[i]);
            if (read && (!captures[i].equals(liveArgs[i]) || count(liveArgs, captures[i]) > 1)) return null;
            map[i] = i;
        }
        return map;
    }

    private static boolean droppedTypesAreDistinct(Type[] captures, int kept) {
        List<Type> keptTypes = Arrays.asList(captures).subList(0, kept);
        for (int i = kept; i < captures.length; i++) {
            if (keptTypes.contains(captures[i])) return false;
        }
        return true;
    }

    /** Whether the method reads any of the first {@code count} parameter slots. */
    private static boolean readsAnySlot(MethodNode method, int[] slots, int count) {
        for (int i = 0; i < count; i++) {
            if (readsSlot(method, slots[i])) return true;
        }
        return false;
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

    /** Whether the handler may cancel through the callback in {@code slot}, by calling cancel() or by passing it on. */
    private static boolean mayCancel(MethodNode handler, int slot) {
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode call && isCallbackOwner(call.owner) && call.name.equals("cancel")) {
                return true;
            }
            if (insn instanceof VarInsnNode var && var.var == slot) {
                // Only a direct no-argument call on the callback is seen through; anything else may reach a cancel().
                AbstractInsnNode next = var.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (!(next instanceof MethodInsnNode use) || !isCallbackOwner(use.owner)
                        || Type.getArgumentTypes(use.desc).length != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCallbackOwner(String owner) {
        return owner.equals(CALLBACK_INFO) || owner.equals(CALLBACK_INFO_RETURNABLE);
    }

    /** Whether any instruction names a parameter slot below {@code oldParamEnd} that {@code slotMap} does not move. */
    private static boolean namesUnmappedSlot(MethodNode method, Map<Integer, Integer> slotMap, int oldParamEnd) {
        for (AbstractInsnNode insn : method.instructions) {
            int slot = insn instanceof VarInsnNode var ? var.var
                    : insn instanceof IincInsnNode inc ? inc.var : -1;
            if (slot >= 0 && slot < oldParamEnd && !slotMap.containsKey(slot)) return true;
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
    static String soleRemappedTarget(ClassNode classNode) {
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
