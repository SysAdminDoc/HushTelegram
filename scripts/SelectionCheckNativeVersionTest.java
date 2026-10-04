import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22b;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Annotation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotation;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Mutates the real native initializer without rebuilding an APK or using real credentials. */
public final class SelectionCheckNativeVersionTest {
    private static int checks;

    private static Method copy(Method source, MutableMethodImplementation body, int flags) {
        return new ImmutableMethod(source.getDefiningClass(), source.getName(), source.getParameters(),
                source.getReturnType(), flags, source.getAnnotations(), source.getHiddenApiRestrictions(), body);
    }

    private static void verify(Method source, Method candidate, boolean configured, int id, boolean accept) {
        try {
            int count = SelectionCheck.nativeVersionChanges(source, candidate, configured, id);
            if (!accept || count != (configured ? 1 : 0)) throw new AssertionError("NATIVE_VERSION_FALSE_ACCEPT");
        } catch (IllegalStateException refusal) {
            if (accept) throw new AssertionError("NATIVE_VERSION_FALSE_REFUSAL", refusal);
        }
        checks++;
    }

    private static void exercise(Method source) {
        var original = new MutableMethodImplementation(source.getImplementation());
        int at = -1;
        for (int i = 0; i < original.getInstructions().size(); i++) {
            var instruction = original.getInstructions().get(i);
            if (instruction instanceof ReferenceInstruction reference
                    && reference.getReference() instanceof MethodReference call
                    && call.getName().equals("native_init")) at = i;
        }
        if (at < 0) throw new AssertionError("NATIVE_INIT_MISSING");
        int version = ((RegisterRangeInstruction) original.getInstructions().get(at)).getStartRegister() + 1;
        verify(source, source, false, 19077001, true);
        verify(source, source, true, 19077001, false);
        for (int[] pair : new int[][]{{1, -2}, {127, -128}, {128, -1}, {19077001, -10}, {Integer.MAX_VALUE, -128}}) {
            var body = new MutableMethodImplementation(source.getImplementation());
            body.addInstruction(at, new BuilderInstruction22b(Opcode.XOR_INT_LIT8, version, version, pair[1]));
            verify(source, copy(source, body, source.getAccessFlags()), true, pair[0], true);
            verify(source, copy(source, body, source.getAccessFlags()), false, pair[0], false);
        }
        for (String mutation : new String[]{"tag", "destination", "source", "duplicate", "extra", "position", "flags"}) {
            var body = new MutableMethodImplementation(source.getImplementation());
            body.addInstruction(at, new BuilderInstruction22b(Opcode.XOR_INT_LIT8, version, version, -10));
            int flags = source.getAccessFlags();
            switch (mutation) {
                case "tag" -> body.replaceInstruction(at,
                        new BuilderInstruction22b(Opcode.XOR_INT_LIT8, version, version, -11));
                case "destination" -> body.replaceInstruction(at,
                        new BuilderInstruction22b(Opcode.XOR_INT_LIT8, version + 1, version, -10));
                case "source" -> body.replaceInstruction(at,
                        new BuilderInstruction22b(Opcode.XOR_INT_LIT8, version, version + 1, -10));
                case "duplicate" -> body.addInstruction(at,
                        new BuilderInstruction22b(Opcode.XOR_INT_LIT8, version, version, -10));
                case "extra" -> body.addInstruction(0, new BuilderInstruction10x(Opcode.NOP));
                case "position" -> body.swapInstructions(at, at + 1);
                case "flags" -> flags ^= AccessFlags.SYNCHRONIZED.getValue();
                default -> throw new AssertionError(mutation);
            }
            verify(source, copy(source, body, flags), true, 19077001, false);
        }
    }

    private static void declarations(ClassDef source, Method initializer) {
        SelectionCheck.declarations(source, source, false);
        checks++;
        var changed = new ArrayList<Method>();
        boolean mutated = false;
        for (Method method : source.getMethods()) {
            if (!mutated && !method.toString().equals(initializer.toString())) {
                changed.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                        method.getReturnType(), method.getAccessFlags() ^ AccessFlags.SYNCHRONIZED.getValue(),
                        method.getAnnotations(), method.getHiddenApiRestrictions(), method.getImplementation()));
                mutated = true;
            } else changed.add(method);
        }
        if (!mutated) throw new AssertionError("UNRELATED_METHOD_MISSING");
        var replacement = new ImmutableClassDef(source.getType(), source.getAccessFlags(), source.getSuperclass(),
                source.getInterfaces(), source.getSourceFile(), source.getAnnotations(), source.getFields(), changed);
        try {
            SelectionCheck.declarations(source, replacement, false);
            throw new AssertionError("UNRELATED_METHOD_FLAGS_ACCEPTED");
        } catch (IllegalStateException expected) { checks++; }
        for (String target : new String[]{"class", "field", "method", "parameter"}) {
            Set<Annotation> annotations = new HashSet<>(source.getAnnotations());
            Annotation marker = new ImmutableAnnotation(1, "Lselection/Canary;", Set.of());
            if (target.equals("class")) annotations.add(marker);
            var fields = new ArrayList<Field>();
            for (Field field : source.getFields()) {
                if (target.equals("field") && fields.isEmpty()) {
                    Set<Annotation> values = new HashSet<>(field.getAnnotations());
                    values.add(marker);
                    fields.add(new ImmutableField(field.getDefiningClass(), field.getName(), field.getType(),
                            field.getAccessFlags(), field.getInitialValue(), values, field.getHiddenApiRestrictions()));
                } else fields.add(field);
            }
            var methods = new ArrayList<Method>();
            boolean changedAnnotation = false;
            for (Method method : source.getMethods()) {
                Set<Annotation> values = new HashSet<>(method.getAnnotations());
                var parameters = new ArrayList<MethodParameter>(method.getParameters());
                if (!changedAnnotation && target.equals("method")) {
                    values.add(marker);
                    changedAnnotation = true;
                } else if (!changedAnnotation && target.equals("parameter") && !parameters.isEmpty()) {
                    var parameter = parameters.get(0);
                    Set<Annotation> parameterAnnotations = new HashSet<>(parameter.getAnnotations());
                    parameterAnnotations.add(marker);
                    parameters.set(0, new ImmutableMethodParameter(parameter.getType(), parameterAnnotations, parameter.getName()));
                    changedAnnotation = true;
                }
                methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), parameters,
                        method.getReturnType(), method.getAccessFlags(), values, method.getHiddenApiRestrictions(), method.getImplementation()));
            }
            if (target.equals("field") && fields.isEmpty() ||
                    (target.equals("method") || target.equals("parameter")) && !changedAnnotation) {
                throw new AssertionError("ANNOTATION_CONTROL_UNCHANGED");
            }
            var annotated = new ImmutableClassDef(source.getType(), source.getAccessFlags(), source.getSuperclass(),
                    source.getInterfaces(), source.getSourceFile(), annotations, fields, methods);
            for (boolean remove : new boolean[]{false, true}) {
                try {
                    SelectionCheck.declarations(remove ? annotated : source, remove ? source : annotated, false);
                    throw new AssertionError("ANNOTATION_CHANGE_ACCEPTED: " + target + "/remove=" + remove);
                } catch (IllegalStateException expected) { checks++; }
            }
        }
        var added = new ArrayList<Method>();
        source.getMethods().forEach(added::add);
        added.add(new ImmutableMethod(source.getType(), "unexpectedAbstractMethod", java.util.List.of(), "V",
                AccessFlags.PUBLIC.getValue() | AccessFlags.ABSTRACT.getValue(), Set.of(), Set.of(), null));
        var withAbstract = new ImmutableClassDef(source.getType(), source.getAccessFlags(), source.getSuperclass(),
                source.getInterfaces(), source.getSourceFile(), source.getAnnotations(), source.getFields(), added);
        SelectionCheck.declarations(source, withAbstract, true);
        checks++;
        try {
            SelectionCheck.declarations(source, withAbstract, false);
            throw new AssertionError("UNRELATED_ABSTRACT_METHOD_ACCEPTED");
        } catch (IllegalStateException expected) { checks++; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new AssertionError("FIXTURES_REQUIRED");
        for (String path : args) {
            var dex = DexFileFactory.loadDexContainer(new File(path), Opcodes.getDefault());
            Method initializer = null;
            for (String entry : dex.getDexEntryNames()) {
                for (var type : dex.getEntry(entry).getDexFile().getClasses()) {
                    if (!type.getType().equals("Lorg/telegram/tgnet/ConnectionsManager;")) continue;
                    if (initializer != null) throw new AssertionError("DUPLICATE_CONNECTIONS");
                    initializer = SelectionCheck.nativeInitializer(type);
                    declarations(type, initializer);
                }
            }
            if (initializer == null) throw new AssertionError("CONNECTIONS_MISSING");
            exercise(initializer);
        }
        System.out.println("NATIVE_VERSION_CHECKS_PASSED checks=" + checks);
    }
}
