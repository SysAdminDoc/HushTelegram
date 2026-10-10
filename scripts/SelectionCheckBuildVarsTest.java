import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.reandroid.json.JSONArray;
import com.reandroid.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Puts the beta-logs gate into each fixture's real BuildVars initializer, and reads which Firebase reporters each carries. */
public final class SelectionCheckBuildVarsTest {
    private static final String BETA_LOGS = "Lapp/hushtelegram/extension/telegram/misc/BetaLogs;";
    private static int checks;

    private static Method copy(Method source, MethodImplementation body) {
        return new ImmutableMethod(source.getDefiningClass(), source.getName(), source.getParameters(),
                source.getReturnType(), source.getAccessFlags(), source.getAnnotations(), source.getHiddenApiRestrictions(), body);
    }

    private static void verify(Method source, Method candidate, boolean gate, boolean accept) {
        try {
            int count = SelectionCheck.buildVarsChanges(source, candidate, false, 0, "", gate);
            if (!accept || count != 0) throw new AssertionError("BUILD_VARS_FALSE_ACCEPT");
        } catch (IllegalStateException refusal) {
            if (accept) throw new AssertionError("BUILD_VARS_FALSE_REFUSAL", refusal);
        }
        checks++;
    }

    private static int call(MutableMethodImplementation body) {
        int at = -1;
        for (int i = 0; i < body.getInstructions().size(); i++) {
            if (body.getInstructions().get(i) instanceof ReferenceInstruction reference
                    && reference.getReference() instanceof MethodReference method
                    && method.getDefiningClass().equals(BETA_LOGS)) {
                if (at != -1) throw new AssertionError("GATE_TWICE");
                at = i;
            }
        }
        if (at < 1) throw new AssertionError("GATE_MISSING");
        return at;
    }

    private static void exercise(Method source) {
        verify(source, source, false, true);
        verify(source, source, true, false);
        var gated = SelectionCheck.withBetaLogsGate(source.getImplementation());
        verify(source, copy(source, gated), true, true);
        verify(source, copy(source, gated), false, false);
        // The call now sits where the if-nez was, so a second gate has nothing to go in front of.
        try {
            SelectionCheck.withBetaLogsGate(copy(source, gated).getImplementation());
            throw new AssertionError("GATE_ON_GATE_ACCEPTED");
        } catch (IllegalStateException expected) { checks++; }
        var forceLogs = new ImmutableMethodReference(BETA_LOGS, "forceLogs", List.of("Z"), "Z");
        for (String mutation : new String[]{"register", "result", "missing", "plain", "method", "duplicate",
                "before", "order", "extra"}) {
            var body = SelectionCheck.withBetaLogsGate(source.getImplementation());
            int at = call(body);
            int register = ((OneRegisterInstruction) body.getInstructions().get(at + 1)).getRegisterA();
            switch (mutation) {
                case "register" -> body.replaceInstruction(at,
                        new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register + 1, 1, forceLogs));
                case "result" -> body.replaceInstruction(at + 1, new BuilderInstruction11x(Opcode.MOVE_RESULT, register + 1));
                case "missing" -> body.removeInstruction(at + 1);
                case "plain" -> body.replaceInstruction(at,
                        new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, register, 0, 0, 0, 0, forceLogs));
                case "method" -> body.replaceInstruction(at, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1,
                        new ImmutableMethodReference(BETA_LOGS, "keepLogs", List.of("Z"), "Z")));
                case "duplicate" -> {
                    body.addInstruction(at + 2, new BuilderInstruction11x(Opcode.MOVE_RESULT, register));
                    body.addInstruction(at + 2, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1, forceLogs));
                }
                case "before" -> {
                    body.removeInstruction(at + 1);
                    body.removeInstruction(at);
                    body.addInstruction(at - 1, new BuilderInstruction11x(Opcode.MOVE_RESULT, register));
                    body.addInstruction(at - 1, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1, forceLogs));
                }
                case "order" -> body.swapInstructions(at, at + 1);
                case "extra" -> body.addInstruction(0, new BuilderInstruction10x(Opcode.NOP));
                default -> throw new AssertionError(mutation);
            }
            verify(source, copy(source, body), true, false);
        }
    }

    private static SelectionCheck.Expected expected(JSONObject flags, List<String> optional) {
        return new SelectionCheck.Expected(new JSONObject().put("settings", true).put("links", false)
                .put("api", false).put("maps", false).put("flags", flags).put("optional", new JSONArray(optional)));
    }

    private static void optionalNames() {
        JSONObject flags = new JSONObject().put("crashReports", true).put("sessionReports", false).put("channelAds", true);
        if (!expected(flags, List.of("crashReports", "sessionReports")).optional.equals(Set.of("crashReports", "sessionReports"))) {
            throw new AssertionError("OPTIONAL_NAMES_LOST");
        }
        checks++;
        JSONObject partial = new JSONObject().put("crashReports", true);
        for (var bad : List.of(Map.entry(flags, List.of("channelAds")), Map.entry(flags, List.of("crashReports", "crashReports")),
                Map.entry(flags, List.of("unknownReports")), Map.entry(partial, List.of("sessionReports")))) {
            try {
                expected(bad.getKey(), bad.getValue());
                throw new AssertionError("OPTIONAL_NAME_ACCEPTED: " + bad.getValue());
            } catch (IllegalStateException refusal) { checks++; }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new AssertionError("FIXTURES_REQUIRED");
        optionalNames();
        for (String path : args) {
            Map<String, ClassDef> classes = SelectionCheck.classes(DexFileFactory.loadDexContainer(new File(path), Opcodes.getDefault()));
            ClassDef buildVars = classes.get("Lorg/telegram/messenger/BuildVars;");
            if (buildVars == null) throw new AssertionError("BUILD_VARS_MISSING");
            Method initializer = null;
            for (Method method : buildVars.getMethods()) {
                if (method.getName().equals("<clinit>")) initializer = method;
            }
            if (initializer == null) throw new AssertionError("BUILD_VARS_INITIALIZER_MISSING");
            exercise(initializer);
            // Only Telegram Beta ships Crashlytics and Firebase Sessions.
            boolean beta = new File(path).getName().startsWith("telegram-beta-");
            for (String marker : SelectionCheck.OPTIONAL_MARKERS.values()) {
                if (SelectionCheck.carries(classes, marker) != beta) throw new AssertionError("MARKER_MISREAD: " + marker);
                checks++;
            }
        }
        System.out.println("BUILD_VARS_CHECKS_PASSED checks=" + checks);
    }
}
