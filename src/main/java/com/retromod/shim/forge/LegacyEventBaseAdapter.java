/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SignatureRemapper;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Moves an old mod's classes off Forge 1.20.1's event base onto Retromod's rebuilt one, before the remap. */
public final class LegacyEventBaseAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String EVENT = "net/minecraftforge/eventbus/api/Event";
    static final String GENERIC_EVENT = "net/minecraftforge/eventbus/api/GenericEvent";
    static final String LEGACY_EVENT = RebuiltApiClasses.ROOT + "forge/eventbus/LegacyEvent";
    static final String LEGACY_GENERIC_EVENT = RebuiltApiClasses.ROOT + "forge/eventbus/LegacyGenericEvent";
    static final String ACCESS = RebuiltApiClasses.ROOT + "forge/eventbus/LegacyEventAccess";
    private static final String CANCELABLE = "Lnet/minecraftforge/eventbus/api/Cancelable;";
    private static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    static final String LEGACY_SUBSCRIBE = "L" + RebuiltApiClasses.ROOT + "forge/eventbus/LegacySubscribe;";
    // A handler whose event lives outside these packages listens to a mod's own event.
    private static final List<String> HOST_PACKAGES = List.of("net/minecraftforge/", "net/minecraft/", "com/mojang/", "java/");
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String OBJECT = "java/lang/Object";
    private static final String INHERITABLE = "net/minecraftforge/eventbus/api/event/InheritableEvent";
    // Removed Forge events a bridge rebuilt on the legacy base, whose handlers take the rebuilt dispatch too.
    private static final Set<String> REBUILT_HOST_EVENTS = ConcurrentHashMap.newKeySet();

    // The old base methods LegacyEventAccess answers for any event, by name and pre-remap descriptor.
    private static final Set<String> ACCESS_METHODS = Set.of("isCancelable()Z", "isCanceled()Z", "setCanceled(Z)V",
            "hasResult()Z", "getResult()L" + EVENT + "$Result;", "setResult(L" + EVENT + "$Result;)V",
            "getPhase()Lnet/minecraftforge/eventbus/api/EventPriority;",
            "setPhase(Lnet/minecraftforge/eventbus/api/EventPriority;)V", "getGenericType()Ljava/lang/reflect/Type;");

    private static final byte[] PACKAGE_FORM = "net/minecraftforge/eventbus/api/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DESCRIPTOR_FORM = ("L" + EVENT + ";").getBytes(StandardCharsets.UTF_8);
    private static final byte[] GENERIC_FORM = GENERIC_EVENT.getBytes(StandardCharsets.UTF_8);
    private static final byte[] CANCELABLE_FORM = CANCELABLE.getBytes(StandardCharsets.UTF_8);
    private static final byte[] SUBSCRIBE_FORM = OLD_SUBSCRIBE.getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLASS_ENTRY_FORM = classEntry(EVENT);

    /** Every remaining old event type becomes Object, which host and legacy events both are. */
    private static final Remapper ERASE = new Remapper() {
        @Override
        public String map(String internalName) {
            return isOldType(internalName) ? OBJECT : internalName;
        }

        @Override
        public String mapSignature(String signature, boolean typeSignature) {
            if (signature == null) return null;
            SignatureWriter writer = new SignatureWriter();
            SignatureVisitor eraser = new ErasingSignatureRemapper(writer, this);
            if (typeSignature) {
                new SignatureReader(signature).acceptType(eraser);
            } else {
                new SignatureReader(signature).accept(eraser);
            }
            return writer.toString();
        }
    };

    private LegacyEventBaseAdapter() {}

    /** Sends old handlers of these removed Forge events, rebuilt on the legacy base, to the rebuilt dispatch while the registrations last. */
    public static void dispatchAsLegacy(RetromodTransformer t, Collection<String> oldEvents) {
        List<String> events = List.copyOf(oldEvents);
        if (REBUILT_HOST_EVENTS.addAll(events)) t.onRegistrationReset(() -> REBUILT_HOST_EVENTS.removeAll(events));
    }

    /** The class moved onto the rebuilt event base, or the same array when it names no old base type or is refused. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !contains(classBytes, PACKAGE_FORM)) return classBytes;
        boolean namesOldTypes = namesOldTypes(classBytes);
        if (!namesOldTypes && !contains(classBytes, CANCELABLE_FORM) && !contains(classBytes, SUBSCRIBE_FORM)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);

        // A mixin that extends the old base only mirrors its target, and Mixin checks that shape.
        boolean mixin = hasAnnotation(node, MIXIN);
        if (mixin && isOldType(node.superName)) return classBytes;
        boolean overrideAdded = !mixin && addCancelableOverride(node);
        boolean handlersMoved = !mixin && moveModEventHandlers(node);
        // A class translated before keeps its @Cancelable and its override, and has nothing left to change.
        if (!namesOldTypes && !overrideAdded && !handlersMoved) return classBytes;
        if (!mixin) rebase(node);
        for (MethodNode method : node.methods) {
            rewriteInstructions(method);
        }
        // Event.Result and Event.HasResult become top-level classes, so the old base has no nested classes left.
        if (node.innerClasses != null) {
            node.innerClasses.removeIf(inner -> isOldType(inner.outerName));
        }
        if (!dropCollidingBridges(node)) return classBytes;

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, ERASE));
        return writer.toByteArray();
    }

    /** Whether the class can name Event or GenericEvent as a type. */
    private static boolean namesOldTypes(byte[] classBytes) {
        return contains(classBytes, DESCRIPTOR_FORM) || contains(classBytes, CLASS_ENTRY_FORM)
                || contains(classBytes, GENERIC_FORM);
    }

    // extends Event or GenericEvent becomes the rebuilt class, along with the super(...) call.
    private static void rebase(ClassNode node) {
        String rebuilt = rebuilt(node.superName);
        if (rebuilt == null) return;
        node.superName = rebuilt;
        if (node.signature != null) node.signature = rebaseSignature(node.signature, rebuilt);
        // EventBus 7 passes listeners down from InheritableEvent parents, as Forge 1.20.1 did. The class
        // declares it rather than the rebuilt base, so a mod group, which takes only mod-bus events, never
        // has to make a bus for the base.
        if (!node.interfaces.contains(INHERITABLE)) {
            node.interfaces.add(INHERITABLE);
            if (node.signature != null) node.signature += "L" + INHERITABLE + ";";
        }
    }

    // Forge 1.20.1 found @Cancelable by walking superclasses; an override inherited by subclasses does the same.
    private static boolean addCancelableOverride(ClassNode node) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || !hasAnnotation(node, CANCELABLE)) return false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("isCancelable") && method.desc.equals("()Z")) return false;
        }
        MethodNode override = new MethodNode(Opcodes.ACC_PUBLIC, "isCancelable", "()Z", null, null);
        override.instructions.add(new InsnNode(Opcodes.ICONST_1));
        override.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(override);
        return true;
    }

    // A handler of a mod's own event goes to Retromod's dispatch, which keeps Forge 1.20.1's rules;
    // EventBus 7 keeps the handlers of host events.
    private static boolean moveModEventHandlers(ClassNode node) {
        boolean moved = false;
        for (MethodNode method : node.methods) {
            if (method.visibleAnnotations == null || !handlesModEvent(method.desc)) continue;
            for (int i = 0; i < method.visibleAnnotations.size(); i++) {
                if (!OLD_SUBSCRIBE.equals(method.visibleAnnotations.get(i).desc)) continue;
                method.visibleAnnotations.set(i, legacySubscribe(method.visibleAnnotations.get(i)));
                moved = true;
            }
        }
        return moved;
    }

    // One parameter, a mod's own class, a removed Forge event rebuilt on the legacy base, or the old base itself,
    // which no EventBus 7 bus can take.
    private static boolean handlesModEvent(String descriptor) {
        Type[] parameters = Type.getArgumentTypes(descriptor);
        if (parameters.length != 1 || parameters[0].getSort() != Type.OBJECT) return false;
        String event = parameters[0].getInternalName();
        return isOldType(event) || REBUILT_HOST_EVENTS.contains(event) || HOST_PACKAGES.stream().noneMatch(event::startsWith);
    }

    /** {@code @LegacySubscribe} with the old annotation's priority constant name and receiveCanceled. */
    private static AnnotationNode legacySubscribe(AnnotationNode old) {
        AnnotationNode moved = new AnnotationNode(LEGACY_SUBSCRIBE);
        List<Object> values = old.values == null ? List.of() : old.values;
        for (int i = 0; i + 1 < values.size(); i += 2) {
            Object value = values.get(i + 1);
            if ("priority".equals(values.get(i)) && value instanceof String[] constant) {
                moved.visit("priority", constant[1]);
            } else if ("receiveCanceled".equals(values.get(i))) {
                moved.visit("receiveCanceled", value);
            }
        }
        return moved;
    }

    // Each replacement keeps the stack shape of the instruction it replaces, so the class's frames stay valid.
    private static void rewriteInstructions(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) {
                rewriteCall(call);
            } else if (insn instanceof TypeInsnNode type) {
                rewriteType(method, type);
            } else if (insn instanceof LdcInsnNode ldc) {
                rewriteClassConstant(method, ldc);
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                for (int i = 0; i < indy.bsmArgs.length; i++) {
                    if (indy.bsmArgs[i] instanceof Handle handle) indy.bsmArgs[i] = accessHandle(handle);
                }
            }
        }
    }

    private static void rewriteCall(MethodInsnNode call) {
        if (!isOldType(call.owner)) return;
        // Constructors and super calls go to the rebuilt base the class now extends.
        if (call.getOpcode() == Opcodes.INVOKESPECIAL) {
            call.owner = rebuilt(call.owner);
            return;
        }
        // A call through an Event-typed value may meet a host event or another mod's copy.
        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && ACCESS_METHODS.contains(call.name + call.desc)) {
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = ACCESS;
            call.desc = withReceiver(call.desc);
            call.itf = false;
        }
    }

    private static void rewriteType(MethodNode method, TypeInsnNode type) {
        if (!isOldType(type.desc)) return;
        switch (type.getOpcode()) {
            case Opcodes.NEW -> type.desc = rebuilt(type.desc);
            // Every value of the old type is now an Object, so the cast has nothing left to check.
            case Opcodes.CHECKCAST -> method.instructions.remove(type);
            case Opcodes.INSTANCEOF -> {
                if (type.desc.equals(EVENT)) {
                    method.instructions.set(type, new MethodInsnNode(Opcodes.INVOKESTATIC, ACCESS, "isEvent",
                            "(Ljava/lang/Object;)Z", false));
                } else {
                    type.desc = LEGACY_GENERIC_EVENT;
                }
            }
            default -> { }
        }
    }

    // Event.class is EventBus 7's root of all events, so isAssignableFrom still classifies host events.
    private static void rewriteClassConstant(MethodNode method, LdcInsnNode ldc) {
        if (ldc.cst instanceof Handle handle) {
            ldc.cst = accessHandle(handle);
        } else if (ldc.cst instanceof Type type && type.getSort() == Type.OBJECT && isOldType(type.getInternalName())) {
            if (type.getInternalName().equals(EVENT)) {
                method.instructions.set(ldc, new MethodInsnNode(Opcodes.INVOKESTATIC, ACCESS, "eventRoot",
                        "()Ljava/lang/Class;", false));
            } else {
                ldc.cst = Type.getObjectType(LEGACY_GENERIC_EVENT);
            }
        }
    }

    // A method reference such as Event::isCanceled points at the static helper instead.
    private static Handle accessHandle(Handle handle) {
        if (handle.getTag() != Opcodes.H_INVOKEVIRTUAL || !isOldType(handle.getOwner())
                || !ACCESS_METHODS.contains(handle.getName() + handle.getDesc())) {
            return handle;
        }
        return new Handle(Opcodes.H_INVOKESTATIC, ACCESS, handle.getName(), withReceiver(handle.getDesc()), false);
    }

    /** Drops bridges the erasure makes identical to their target; false when two real methods would collide. */
    private static boolean dropCollidingBridges(ClassNode node) {
        Map<String, MethodNode> seen = new HashMap<>();
        for (MethodNode method : List.copyOf(node.methods)) {
            String key = method.name + ERASE.mapMethodDesc(method.desc);
            MethodNode other = seen.putIfAbsent(key, method);
            if (other == null) continue;
            // A Consumer<Event> has accept(Event) and its bridge accept(Object), which erase to one method.
            boolean bridge = (method.access & Opcodes.ACC_BRIDGE) != 0;
            boolean otherBridge = (other.access & Opcodes.ACC_BRIDGE) != 0;
            // Any other pair that would share a descriptor leaves the class on the old base.
            if (bridge == otherBridge) {
                LOGGER.warn("Left {} on Forge's old event base: its methods {}{} and {}{} would both become {}",
                        node.name, other.name, other.desc, method.name, method.desc, key);
                return false;
            }
            node.methods.remove(bridge ? method : other);
            seen.put(key, bridge ? other : method);
        }
        return true;
    }

    /** The class signature with its superclass moved to {@code rebuilt}, keeping the type arguments. */
    static String rebaseSignature(String signature, String rebuilt) {
        for (String old : List.of(EVENT, GENERIC_EVENT)) {
            String rebased = rebaseSignature(signature, old, rebuilt);
            if (!rebased.equals(signature)) return rebased;
        }
        return signature;
    }

    /** The class signature with its superclass {@code old} moved to {@code rebuilt}, keeping the type arguments. */
    static String rebaseSignature(String signature, String old, String rebuilt) {
        int superclass = 0;
        // Skip the formal type parameters, which may nest angle brackets of their own.
        if (signature.startsWith("<")) {
            int depth = 0;
            do {
                char c = signature.charAt(superclass++);
                if (c == '<') depth++;
                if (c == '>') depth--;
            } while (depth > 0);
        }
        String prefix = "L" + old;
        if (signature.startsWith(prefix, superclass)) {
            char next = signature.charAt(superclass + prefix.length());
            if (next == ';' || next == '<') {
                return signature.substring(0, superclass + 1) + rebuilt
                        + signature.substring(superclass + prefix.length());
            }
        }
        return signature;
    }

    private static String rebuilt(String oldType) {
        if (EVENT.equals(oldType)) return LEGACY_EVENT;
        if (GENERIC_EVENT.equals(oldType)) return LEGACY_GENERIC_EVENT;
        return null;
    }

    private static boolean isOldType(String internalName) {
        return EVENT.equals(internalName) || GENERIC_EVENT.equals(internalName);
    }

    private static String withReceiver(String descriptor) {
        return "(Ljava/lang/Object;" + descriptor.substring(1);
    }

    private static boolean hasAnnotation(ClassNode node, String descriptor) {
        for (List<AnnotationNode> annotations : Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.equals(descriptor)) return true;
            }
        }
        return false;
    }

    // A class constant's name sits in the constant pool as a UTF-8 entry: tag 1, then a two-byte length.
    private static byte[] classEntry(String internalName) {
        byte[] name = internalName.getBytes(StandardCharsets.UTF_8);
        byte[] entry = new byte[name.length + 3];
        entry[0] = 1;
        entry[1] = (byte) (name.length >> 8);
        entry[2] = (byte) name.length;
        System.arraycopy(name, 0, entry, 3, name.length);
        return entry;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** Erases an old event type to Object together with its type arguments, which Object cannot take. */
    private static final class ErasingSignatureRemapper extends SignatureRemapper {

        private static final SignatureVisitor IGNORED = new SignatureVisitor(Opcodes.ASM9) { };
        private final ArrayDeque<Boolean> erased = new ArrayDeque<>();

        ErasingSignatureRemapper(SignatureVisitor out, Remapper remapper) {
            super(Opcodes.ASM9, out, remapper);
        }

        @Override
        public void visitClassType(String name) {
            erased.push(isOldType(name));
            super.visitClassType(name);
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return erased.peek() ? IGNORED : super.visitTypeArgument(wildcard);
        }

        @Override
        public void visitTypeArgument() {
            if (!erased.peek()) super.visitTypeArgument();
        }

        @Override
        public void visitEnd() {
            erased.pop();
            super.visitEnd();
        }
    }
}
