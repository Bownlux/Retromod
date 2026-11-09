/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Gives an old {@code @SubscribeEvent} method that can cancel a Forge 26.2 event a boolean twin, whose answer EventBus 7 reads. */
public final class CancellableListenerSiblings {

    private static final String CANCEL = LegacyCancelBridge.CANCEL;
    private static final String SUBSCRIBE7 = ForgeSubscribeAnnotations.SUBSCRIBE7;
    private static final String OBJECT_ARRAY = "[Ljava/lang/Object;";
    private static final String THROWABLE = "java/lang/Throwable";
    // EventBus 7's MONITOR priority, the only one an (event, boolean) listener may have.
    private static final byte MONITOR = -128;

    private CancellableListenerSiblings() {}

    /** The class with cancel-aware twins of each old listener that can cancel its event or reaches it only through children. */
    public static byte[] apply(byte[] classBytes, String modSourceVersion) {
        return apply(classBytes, modSourceVersion, LegacyCancelBridge.HOST);
    }

    static byte[] apply(byte[] classBytes, String modSourceVersion, Function<String, ClassNode> host) {
        if (classBytes == null || !mayHaveListeners(classBytes)) return classBytes;
        // A mod built for EventBus 7 already cancels by its listeners' answers, so only its listeners on a parent
        // the host took the bus from since it was built move, onto the children.
        boolean builtFor7 = LegacyCancelBridge.builtForEventBus7(modSourceVersion);
        Set<String> movedParents = builtFor7
                ? LegacyCancelBridge.buslessSince(modSourceVersion, RetromodVersion.TARGET_MC_VERSION) : Set.of();
        if (builtFor7 && movedParents.isEmpty()) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<MethodNode> added = new ArrayList<>();
        // Every name and descriptor in the class, so overloaded handlers that reach one child get twins apart.
        Set<String> taken = new HashSet<>();
        for (MethodNode method : node.methods) taken.add(method.name + method.desc);
        for (MethodNode method : node.methods) {
            AnnotationNode subscription = subscription(method);
            if (subscription == null || (method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0
                    || method.name.startsWith("retromod$")) {
                continue;
            }
            if (builtFor7) {
                added.addAll(movedTwins(node, method, subscription, movedParents, host, taken));
            } else {
                String event = eventOf(method);
                if (event != null) added.addAll(twins(node, method, subscription, event, host, taken));
            }
        }
        if (added.isEmpty()) return classBytes;
        node.methods.addAll(added);
        // Each twin carries its own frames and the originals keep theirs, so no frame is computed here.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** A cheap text check, so a class with no EventBus 7 listener is never parsed. */
    private static boolean mayHaveListeners(byte[] classBytes) {
        return new String(classBytes, StandardCharsets.ISO_8859_1).contains("listener/SubscribeEvent");
    }

    /** The twins of one listener, after its annotation moves to them, or none when it stays as it is. */
    private static List<MethodNode> twins(ClassNode owner, MethodNode method, AnnotationNode subscription, String event,
            Function<String, ClassNode> host, Set<String> taken) {
        boolean receiveCanceled = receivesCanceled(subscription);
        // A listener that takes cancelled events needs the twin that notes it ran, whatever its body does.
        boolean cancels = receiveCanceled || mayCancel(owner.name, method);
        Twins twins = new Twins(owner, method, subscription, host, taken);
        Set<String> children = LegacyCancelBridge.cancellableChildren(event, host);
        if (LegacyCancelBridge.hasCancellableBus(event, host)) {
            // The event posts on a cancellable bus, which reads a cancel only from a listener's answer.
            if (!cancels) return List.of();
            twins.cancellable(event, "", receiveCanceled);
        } else if (!children.isEmpty()) {
            // A plain-bus parent hands its listeners to its children, so each cancellable child gets a twin of
            // its own, and the parent's twin leaves those children to them.
            if (!cancels) return List.of();
            Map<String, String> suffixes = suffixes(children);
            for (String child : children) twins.cancellable(child, suffixes.get(child), receiveCanceled);
            twins.parent(event, "", children);
        } else if (LegacyCancelBridge.splitsBusless(event)) {
            // Nothing posts a parent without a bus, so the listener reaches events only through the events below it,
            // which Forge 1.20.1 all delivered to it.
            Set<String> cancellable = LegacyCancelBridge.buslessChildren(event, host);
            Map<String, Set<String>> plain = new LinkedHashMap<>();
            for (String child : LegacyCancelBridge.buslessPlainChildren(event, host)) {
                // A plain bus below hands the listener on to cancellable events, which read a cancel only from a twin.
                plain.put(child, cancels ? LegacyCancelBridge.cancellableChildren(child, host) : Set.of());
            }
            List<String> targets = new ArrayList<>(cancellable);
            plain.forEach((child, split) -> {
                targets.add(child);
                targets.addAll(split);
            });
            Map<String, String> suffixes = suffixes(targets);
            for (String child : cancellable) {
                if (cancels) {
                    twins.cancellable(child, suffixes.get(child), receiveCanceled);
                } else {
                    twins.forward(child, suffixes.get(child), false);
                }
            }
            plain.forEach((child, split) -> {
                if (split.isEmpty()) {
                    twins.forward(child, suffixes.get(child), false);
                    return;
                }
                for (String grandchild : split) twins.cancellable(grandchild, suffixes.get(grandchild), receiveCanceled);
                twins.parent(child, suffixes.get(child), split);
            });
            if (twins.methods.isEmpty()) return List.of();
        } else {
            return List.of();
        }
        // The annotation moves to the twins, so nothing registers twice.
        removeSubscription(method);
        return twins.methods;
    }

    /**
     * The forwards of a listener of a mod built for EventBus 7 on a parent that has lost its bus since, one per child
     * that can take its shape: a void or boolean listener on each child, a monitor on each cancellable one.
     */
    private static List<MethodNode> movedTwins(ClassNode owner, MethodNode method, AnnotationNode subscription,
            Set<String> movedParents, Function<String, ClassNode> host, Set<String> taken) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        if (arguments.length == 0 || arguments[0].getSort() != Type.OBJECT) return List.of();
        String parent = arguments[0].getInternalName();
        boolean monitor = arguments.length == 2 && arguments[1].getSort() == Type.BOOLEAN;
        if (!movedParents.contains(parent) || (arguments.length != 1 && !monitor)) return List.of();
        Twins twins = new Twins(owner, method, subscription, host, taken);
        Set<String> cancellable = LegacyCancelBridge.buslessChildren(parent, host);
        Set<String> plain = monitor ? Set.of() : LegacyCancelBridge.buslessPlainChildren(parent, host);
        List<String> targets = new ArrayList<>(cancellable);
        targets.addAll(plain);
        Map<String, String> suffixes = suffixes(targets);
        for (String child : cancellable) twins.forward(child, suffixes.get(child), false);
        // A plain bus takes no answer and no monitor, so a boolean listener runs there without one.
        boolean answers = Type.getReturnType(method.desc).getSort() == Type.BOOLEAN;
        for (String child : plain) twins.forward(child, suffixes.get(child), answers);
        if (twins.methods.isEmpty()) return List.of();
        removeSubscription(method);
        return twins.methods;
    }

    /** The event a void, one-parameter listener takes, or null for any other method. */
    private static String eventOf(MethodNode method) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        if (arguments.length != 1 || arguments[0].getSort() != Type.OBJECT) return null;
        return Type.getReturnType(method.desc).getSort() == Type.VOID ? arguments[0].getInternalName() : null;
    }

    private static AnnotationNode subscription(MethodNode method) {
        for (List<AnnotationNode> annotations : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (SUBSCRIBE7.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static void removeSubscription(MethodNode method) {
        for (List<AnnotationNode> annotations : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotations != null) annotations.removeIf(annotation -> SUBSCRIBE7.equals(annotation.desc));
        }
    }

    private static boolean receivesCanceled(AnnotationNode subscription) {
        List<Object> values = subscription.values == null ? List.of() : subscription.values;
        for (int i = 0; i + 1 < values.size(); i += 2) {
            if ("receiveCanceled".equals(values.get(i))) return Boolean.TRUE.equals(values.get(i + 1));
        }
        return false;
    }

    /** Each twin's name ending: the event's last name segment, or more where two events share it, as Init.Pre and Render.Pre do. */
    static Map<String, String> suffixes(Collection<String> targets) {
        Map<String, String> suffixes = new HashMap<>();
        for (String target : targets) {
            int count = 1;
            while (count < segments(target).length && sharesTail(target, count, targets)) count++;
            suffixes.put(target, tail(target, count));
        }
        return suffixes;
    }

    private static boolean sharesTail(String target, int count, Collection<String> targets) {
        String own = tail(target, count);
        return targets.stream().anyMatch(other -> !other.equals(target) && tail(other, count).equals(own));
    }

    private static String tail(String type, int count) {
        String[] segments = segments(type);
        return "$" + String.join("$", Arrays.asList(segments).subList(Math.max(0, segments.length - count), segments.length));
    }

    private static String lastSegment(String type) {
        String[] segments = segments(type);
        return segments[segments.length - 1];
    }

    private static String[] segments(String type) {
        return type.split("[/$]");
    }

    /**
     * Whether the listener may cancel its event: it calls LegacyCancel's setCanceled or cancel, or the event escapes,
     * being passed anywhere but as the receiver of a no-argument call, stored, or captured by an invokedynamic.
     */
    static boolean mayCancel(String owner, MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(CANCEL)
                    && (call.name.equals("setCanceled") || call.name.equals("cancel"))) {
                return true;
            }
        }
        int eventSlot = (method.access & ACC_STATIC) != 0 ? 0 : 1;
        try {
            Frame<Tracked>[] frames = new Analyzer<>(new EventTracker(eventSlot)).analyze(owner, method);
            InsnList instructions = method.instructions;
            for (int i = 0; i < frames.length; i++) {
                if (frames[i] != null && escapes(instructions.get(i), frames[i])) return true;
            }
            return false;
        } catch (AnalyzerException | RuntimeException unreadable) {
            // A wrong "cannot cancel" would drop a cancel without a word, so a body that cannot be read is kept.
            return true;
        }
    }

    // Reads the operands the instruction is about to consume from the frame before it.
    private static boolean escapes(AbstractInsnNode insn, Frame<Tracked> frame) {
        int top = frame.getStackSize();
        if (insn instanceof MethodInsnNode call) {
            int arguments = Type.getArgumentTypes(call.desc).length;
            // LegacyCancel's reads take the event without keeping it.
            boolean read = call.owner.equals(CANCEL) && (call.name.equals("isCanceled") || call.name.equals("isCancelable"));
            for (int i = top - arguments; i < top; i++) {
                if (frame.getStack(i).event() && !read) return true;
            }
            // A getter on the event is safe; a call with arguments may hand on what it was given.
            return call.getOpcode() != INVOKESTATIC && arguments > 0 && frame.getStack(top - arguments - 1).event();
        }
        if (insn instanceof InvokeDynamicInsnNode indy) {
            int arguments = Type.getArgumentTypes(indy.desc).length;
            for (int i = top - arguments; i < top; i++) {
                if (frame.getStack(i).event()) return true;
            }
            return false;
        }
        return switch (insn.getOpcode()) {
            case PUTFIELD, PUTSTATIC, AASTORE, ARETURN -> top > 0 && frame.getStack(top - 1).event();
            default -> false;
        };
    }

    /** A value and whether it is the listener's event, or a cast of it. */
    private record Tracked(BasicValue basic, boolean event) implements Value {
        @Override
        public int getSize() {
            return basic.getSize();
        }
    }

    /** Follows the listener's event through locals, the stack and casts; every other value is plain. */
    private static final class EventTracker extends Interpreter<Tracked> {
        private final BasicInterpreter basic = new BasicInterpreter();
        private final int eventSlot;

        EventTracker(int eventSlot) {
            super(Opcodes.ASM9);
            this.eventSlot = eventSlot;
        }

        private static Tracked wrap(BasicValue value, boolean event) {
            return value == null ? null : new Tracked(value, event);
        }

        @Override
        public Tracked newValue(Type type) {
            return wrap(basic.newValue(type), false);
        }

        @Override
        public Tracked newParameterValue(boolean isInstanceMethod, int local, Type type) {
            return wrap(basic.newValue(type), local == eventSlot);
        }

        @Override
        public Tracked newOperation(AbstractInsnNode insn) throws AnalyzerException {
            return wrap(basic.newOperation(insn), false);
        }

        @Override
        public Tracked copyOperation(AbstractInsnNode insn, Tracked value) {
            return value;
        }

        @Override
        public Tracked unaryOperation(AbstractInsnNode insn, Tracked value) throws AnalyzerException {
            return wrap(basic.unaryOperation(insn, value.basic()), insn.getOpcode() == CHECKCAST && value.event());
        }

        @Override
        public Tracked binaryOperation(AbstractInsnNode insn, Tracked first, Tracked second) throws AnalyzerException {
            return wrap(basic.binaryOperation(insn, first.basic(), second.basic()), false);
        }

        @Override
        public Tracked ternaryOperation(AbstractInsnNode insn, Tracked first, Tracked second, Tracked third)
                throws AnalyzerException {
            return wrap(basic.ternaryOperation(insn, first.basic(), second.basic(), third.basic()), false);
        }

        @Override
        public Tracked naryOperation(AbstractInsnNode insn, List<? extends Tracked> values) throws AnalyzerException {
            return wrap(basic.naryOperation(insn, values.stream().map(Tracked::basic).toList()), false);
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, Tracked value, Tracked expected) {
            // Listeners return void, and a returned event counts as an escape in escapes().
        }

        @Override
        public Tracked merge(Tracked first, Tracked second) {
            BasicValue merged = basic.merge(first.basic(), second.basic());
            boolean event = first.event() || second.event();
            return merged.equals(first.basic()) && event == first.event() ? first : new Tracked(merged, event);
        }
    }

    /** Builds one listener's twins, each calling the original with the event it is given. */
    private static final class Twins {
        private final ClassNode owner;
        private final MethodNode original;
        private final AnnotationNode subscription;
        private final boolean isStatic;
        private final boolean isInterface;
        // Class files before Java 6 carry no stack map frames.
        private final boolean frames;
        private final Function<String, ClassNode> host;
        private final Set<String> taken;
        final List<MethodNode> methods = new ArrayList<>();

        Twins(ClassNode owner, MethodNode original, AnnotationNode subscription, Function<String, ClassNode> host,
                Set<String> taken) {
            this.owner = owner;
            this.original = original;
            this.subscription = subscription;
            this.host = host;
            this.taken = taken;
            this.isStatic = (original.access & ACC_STATIC) != 0;
            this.isInterface = (owner.access & ACC_INTERFACE) != 0;
            this.frames = (owner.version & 0xFFFF) >= V1_6;
        }

        /** A boolean twin on {@code target}, and with {@code receiveCanceled} its monitor. */
        void cancellable(String target, String suffix, boolean receiveCanceled) {
            MethodNode twin = twin("retromod$cancellable$", suffix, "(L" + target + ";)Z", null);
            int event = firstSlot();
            int frame = event + 1;
            int failure = frame + 1;
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            twin.visitTryCatchBlock(start, end, handler, THROWABLE);
            // Object[] frame = LegacyCancel.enter(event), noting the run for a listener that takes cancelled events.
            twin.visitVarInsn(ALOAD, event);
            if (receiveCanceled) {
                loadReceiver(twin);
                twin.visitLdcInsn(key(suffix));
                twin.visitMethodInsn(INVOKESTATIC, CANCEL, "enter",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)" + OBJECT_ARRAY, false);
            } else {
                twin.visitMethodInsn(INVOKESTATIC, CANCEL, "enter", "(Ljava/lang/Object;)" + OBJECT_ARRAY, false);
            }
            twin.visitVarInsn(ASTORE, frame);
            twin.visitLabel(start);
            callOriginal(twin, event);
            twin.visitLabel(end);
            // return LegacyCancel.leave(frame), the event's own flag where it keeps one.
            twin.visitVarInsn(ALOAD, frame);
            twin.visitMethodInsn(INVOKESTATIC, CANCEL, "leave", "(" + OBJECT_ARRAY + ")Z", false);
            twin.visitInsn(IRETURN);
            exitAndRethrow(twin, handler, frame, failure, target, OBJECT_ARRAY);
            if (!receiveCanceled) return;
            monitor(target, suffix, suffix);
            // A child bus made after registration copies the boolean twin but not the monitor, so each gets its own.
            for (String heir : LegacyCancelBridge.heirs(target, host)) monitor(heir, "$" + lastSegment(heir), suffix);
        }

        /** The (event, boolean) twin EventBus 7 calls after each post, which runs the original once after a cancel skipped it. */
        private void monitor(String target, String suffix, String keySuffix) {
            MethodNode twin = twin("retromod$cancelled$", suffix, "(L" + target + ";Z)V", MONITOR);
            int event = firstSlot();
            int cancelled = event + 1;
            int frame = cancelled + 1;
            int failure = frame + 1;
            Label run = new Label();
            Label end = new Label();
            Label handler = new Label();
            twin.visitTryCatchBlock(run, end, handler, THROWABLE);
            // Object[] frame = LegacyCancel.enterCancelled(event, cancelled, receiver, key); if (frame == null) return;
            twin.visitVarInsn(ALOAD, event);
            twin.visitVarInsn(ILOAD, cancelled);
            loadReceiver(twin);
            twin.visitLdcInsn(key(keySuffix));
            twin.visitMethodInsn(INVOKESTATIC, CANCEL, "enterCancelled",
                    "(Ljava/lang/Object;ZLjava/lang/Object;Ljava/lang/String;)" + OBJECT_ARRAY, false);
            twin.visitVarInsn(ASTORE, frame);
            twin.visitVarInsn(ALOAD, frame);
            twin.visitJumpInsn(IFNONNULL, run);
            twin.visitInsn(RETURN);
            twin.visitLabel(run);
            frame(twin, null, target, INTEGER, OBJECT_ARRAY);
            callOriginal(twin, event);
            twin.visitLabel(end);
            twin.visitVarInsn(ALOAD, frame);
            twin.visitMethodInsn(INVOKESTATIC, CANCEL, "exit", "(" + OBJECT_ARRAY + ")Z", false);
            twin.visitInsn(POP);
            twin.visitInsn(RETURN);
            exitAndRethrow(twin, handler, frame, failure, target, INTEGER, OBJECT_ARRAY);
        }

        /** A twin on a child that passes every argument to the original, and its answer unless {@code dropAnswer}. */
        void forward(String target, String suffix, boolean dropAnswer) {
            Type[] arguments = Type.getArgumentTypes(original.desc);
            arguments[0] = Type.getObjectType(target);
            Type answer = Type.getReturnType(original.desc);
            Type returned = dropAnswer ? Type.VOID_TYPE : answer;
            MethodNode twin = twin("retromod$", suffix, Type.getMethodDescriptor(returned, arguments), null);
            if (!isStatic) twin.visitVarInsn(ALOAD, 0);
            int slot = firstSlot();
            for (Type argument : arguments) {
                twin.visitVarInsn(argument.getOpcode(ILOAD), slot);
                slot += argument.getSize();
            }
            invokeOriginal(twin);
            if (dropAnswer && answer.getSort() != Type.VOID) twin.visitInsn(answer.getSize() == 2 ? POP2 : POP);
            twin.visitInsn(returned.getOpcode(IRETURN));
        }

        /** A void twin on the plain-bus {@code target}, which leaves events of the given children to their own twins. */
        void parent(String target, String suffix, Set<String> children) {
            MethodNode twin = twin("retromod$plain$", suffix, "(L" + target + ";)V", null);
            int event = firstSlot();
            Label skip = new Label();
            for (String child : children) {
                twin.visitVarInsn(ALOAD, event);
                twin.visitTypeInsn(INSTANCEOF, child);
                twin.visitJumpInsn(IFNE, skip);
            }
            callOriginal(twin, event);
            twin.visitInsn(RETURN);
            twin.visitLabel(skip);
            frame(twin, null, target);
            twin.visitInsn(RETURN);
        }

        // Shared tail of the boolean twin and the monitor: a listener that throws still leaves its frame.
        private void exitAndRethrow(MethodNode twin, Label handler, int frame, int failure, Object... locals) {
            twin.visitLabel(handler);
            frame(twin, THROWABLE, locals);
            twin.visitVarInsn(ASTORE, failure);
            twin.visitVarInsn(ALOAD, frame);
            twin.visitMethodInsn(INVOKESTATIC, CANCEL, "exit", "(" + OBJECT_ARRAY + ")Z", false);
            twin.visitInsn(POP);
            twin.visitVarInsn(ALOAD, failure);
            twin.visitInsn(ATHROW);
        }

        /** A non-synthetic method with the original's access and a normalized copy of its annotation, which EventBus 7 reads. */
        private MethodNode twin(String prefix, String suffix, String descriptor, Byte priority) {
            int access = original.access & (ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED | ACC_STATIC);
            MethodNode twin = new MethodNode(access, freeName(prefix, suffix, descriptor), descriptor, null, null);
            AnnotationNode copy = new AnnotationNode(SUBSCRIBE7);
            copy.values = withPriority(ForgeSubscribeAnnotations.normalizedValues(subscription.values), priority);
            twin.visibleAnnotations = new ArrayList<>(List.of(copy));
            methods.add(twin);
            return twin;
        }

        // Overloads such as event(PlayerEvent) and event(LivingEvent) reach the same child, so a name the class already
        // has takes the handler's own event as well, and then a count.
        private String freeName(String prefix, String suffix, String descriptor) {
            String name = prefix + original.name + suffix;
            if (taken.add(name + descriptor)) return name;
            String own = Type.getArgumentTypes(original.desc)[0].getInternalName();
            String apart = prefix + original.name + "$" + own.substring(own.lastIndexOf('/') + 1) + suffix;
            name = apart;
            for (int count = 2; !taken.add(name + descriptor); count++) name = apart + "$" + count;
            return name;
        }

        private static List<Object> withPriority(List<Object> values, Byte priority) {
            if (priority == null) return values;
            List<Object> replaced = new ArrayList<>();
            for (int i = 0; i + 1 < values.size(); i += 2) {
                if ("priority".equals(values.get(i))) continue;
                replaced.add(values.get(i));
                replaced.add(values.get(i + 1));
            }
            replaced.add("priority");
            replaced.add(priority);
            return replaced;
        }

        private int firstSlot() {
            return isStatic ? 0 : 1;
        }

        private void loadReceiver(MethodNode twin) {
            if (isStatic) {
                twin.visitInsn(ACONST_NULL);
            } else {
                twin.visitVarInsn(ALOAD, 0);
            }
        }

        // Names the listener for its monitor: unique per class, and per child for a parent's twins.
        private String key(String suffix) {
            return owner.name + "." + original.name + original.desc + suffix;
        }

        private void callOriginal(MethodNode twin, int event) {
            if (!isStatic) twin.visitVarInsn(ALOAD, 0);
            twin.visitVarInsn(ALOAD, event);
            invokeOriginal(twin);
        }

        // The same dispatch the class would use to call the method itself, so an override still runs.
        private void invokeOriginal(MethodNode twin) {
            int opcode = isStatic ? INVOKESTATIC
                    : (original.access & ACC_PRIVATE) != 0 ? INVOKESPECIAL
                    : isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL;
            twin.visitMethodInsn(opcode, owner.name, original.name, original.desc, isInterface);
        }

        // An explicit full frame, so the class needs no common-superclass lookups.
        private void frame(MethodNode twin, String stackTop, Object... locals) {
            if (!frames) return;
            List<Object> all = new ArrayList<>();
            if (!isStatic) all.add(owner.name);
            all.addAll(Arrays.asList(locals));
            Object[] stack = stackTop == null ? new Object[0] : new Object[]{stackTop};
            twin.visitFrame(F_FULL, all.size(), all.toArray(), stack.length, stack);
        }
    }
}
