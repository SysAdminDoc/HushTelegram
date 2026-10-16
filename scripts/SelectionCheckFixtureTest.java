import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.reandroid.json.JSONArray;
import com.reandroid.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the checker's parts on the real fixtures: each BuildVars initializer read against itself
 * and against a changed copy, which Firebase reporters each build carries, and a launcher icon
 * expected where the table has none.
 */
public final class SelectionCheckFixtureTest {
    private static int checks;

    private static Method copy(Method source, MethodImplementation body) {
        return new ImmutableMethod(source.getDefiningClass(), source.getName(), source.getParameters(),
                source.getReturnType(), source.getAccessFlags(), source.getAnnotations(), source.getHiddenApiRestrictions(), body);
    }

    /** The initializer against itself has nothing changed, and one extra instruction is refused. */
    private static void exercise(Method source) {
        if (SelectionCheck.buildVarsChanges(source, source, false, 0, "") != 0) throw new AssertionError("BUILD_VARS_FALSE_CHANGE");
        checks++;
        var body = new MutableMethodImplementation(source.getImplementation());
        body.addInstruction(0, new BuilderInstruction10x(Opcode.NOP));
        try {
            SelectionCheck.buildVarsChanges(source, copy(source, body), false, 0, "");
            throw new AssertionError("BUILD_VARS_FALSE_ACCEPT");
        } catch (IllegalStateException refusal) { checks++; }
    }

    private static SelectionCheck.Expected expected(JSONObject flags, List<String> optional) {
        return new SelectionCheck.Expected(new JSONObject().put("settings", true).put("links", false)
                .put("api", false).put("maps", false).put("flags", flags).put("optional", new JSONArray(optional)));
    }

    /** A fixture against itself: nothing to expect passes, and an icon its table doesn't hold is refused. */
    private static void launcherIcon(File fixture) throws Exception {
        for (boolean icon : new boolean[]{false, true}) {
            var expected = new SelectionCheck.Expected(new JSONObject().put("settings", false).put("links", false)
                    .put("api", false).put("maps", false).put("icon", icon).put("flags", new JSONObject()));
            try {
                if (SelectionCheck.manifestChanges(fixture, fixture, expected, false) != 0 || icon) {
                    throw new AssertionError("MANIFEST_FALSE_ACCEPT: icon=" + icon);
                }
            } catch (IllegalStateException refusal) {
                if (!icon) throw new AssertionError("MANIFEST_FALSE_REFUSAL", refusal);
            }
            checks++;
        }
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
            launcherIcon(new File(path));
        }
        System.out.println("FIXTURE_CHECKS_PASSED checks=" + checks);
    }
}
