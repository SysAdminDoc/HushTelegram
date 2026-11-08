/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.api

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.localcontrols.Mention
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlShape
import app.morphe.patches.telegram.misc.localcontrols.controlSingle
import app.morphe.patches.telegram.misc.localcontrols.forEachClassMentioning
import app.morphe.patches.telegram.misc.settings.settingsPatch
import app.morphe.util.ControlFlow
import app.morphe.util.names.addInstructions
import app.morphe.util.names.origType
import app.morphe.util.names.tgMutableClassDefBy
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val TEST_SERVERS = "$EXTENSION_PACKAGE/misc/TestServers;"
internal const val TEST_SERVERS_SHOWN = "$TEST_SERVERS->shown(I)Z"
internal const val DEBUG_TEST_BACKEND = "Lorg/telegram/messenger/R\$string;->DebugTestBackend:I"
internal const val IS_TEST_BACKEND = "Lorg/telegram/tgnet/ConnectionsManager;->isTestBackend()Z"
private const val CELLS_PACKAGE = "Lorg/telegram/ui/Cells/"
private const val CELL_INIT = "-><init>(Landroid/content/Context;I)V"

/**
 * Where the sign-in screen decides whether to add its Test Backend checkbox: the method that builds
 * the phone number screen, the index of the if-eqz testing ConnectionsManager.isTestBackend()'s
 * answer, and the register that answer sits in.
 */
internal class TestBackendCheck(val method: MutableMethod, val test: Int, val register: Int)

@Suppress("unused")
val testServerSignInPatch = bytecodePatch(
    name = "Test server sign-in",
    description = "Shows Telegram's Test Backend box on the sign-in screen for every new account. Tick it to sign that " +
        "account in on Telegram's test servers, kept apart from your other accounts. Starts off. Turn it on in " +
        "HushTelegram settings > Chat list.",
    default = true,
) {
    category("Chats")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())
    execute {
        val check = resolveTestServerSignIn()
        // Assembled on a copy first, so a refusal leaves the app untouched.
        insertTestServersAnswer(MutableMethod(ImmutableMethod.of(check.method)), check)
        insertTestServersAnswer(check.method, check)
        enableStatus("testServerSignIn")
    }
}

/**
 * The extension gets Telegram's answer to whether this account is on the test servers, right before
 * the screen tests it, and its answer goes back in the same register. That register also tells the
 * checkbox's tap listener what the account was on, and a yes there is what Telegram itself holds for a
 * test account, so ticking moves just this account to the test servers and unticking moves it back.
 */
internal fun insertTestServersAnswer(target: MutableMethod, check: TestBackendCheck) {
    target.addInstructions(
        check.test,
        """
            invoke-static/range { v${check.register} .. v${check.register} }, $TEST_SERVERS_SHOWN
            move-result v${check.register}
        """,
    )
}

/**
 * The sign-in screen's Test Backend checkbox. Telegram reads the account's isTestBackend(), skips the
 * checkbox when that's false or the screen isn't signing in, and otherwise builds a cell from
 * org.telegram.ui.Cells labelled with the DebugTestBackend string. Every piece of that is checked
 * here before anything is edited.
 */
internal fun BytecodePatchContext.resolveTestServerSignIn(): TestBackendCheck {
    requireStatusMethod("testServerSignIn")
    controlHook(TEST_SERVERS, "shown", listOf("I"), "Z")
    val hosts = mutableListOf<Pair<String, Method>>()
    forEachClassMentioning(Mention.reference(DEBUG_TEST_BACKEND) and Mention.reference(IS_TEST_BACKEND)) { cls ->
        if (cls.origType.startsWith("Lapp/hushtelegram/")) return@forEachClassMentioning
        for (method in cls.methods) {
            val body = method.controlBody()
            if (body.any { it.controlRef() == DEBUG_TEST_BACKEND } && body.any { it.controlRef() == IS_TEST_BACKEND }) {
                hosts += cls.type to method
            }
        }
    }
    val (type, found) = hosts.controlSingle("the sign-in screen's Test Backend checkbox")
    val signature = found.name + found.parameterTypes.joinToString("", "(", ")") + found.returnType
    val method = tgMutableClassDefBy(type).methods.single {
        it.name + it.parameterTypes.joinToString("", "(", ")") + it.returnType == signature
    }
    val body = method.controlBody()
    val label = body.indices.filter { body[it].controlRef() == DEBUG_TEST_BACKEND }.controlSingle("the Test Backend label")
    val cell = (label - 1 downTo 0).firstOrNull { body[it].opcode == Opcode.NEW_INSTANCE }
    val cellType = cell?.let { body[it].controlRef() }
    controlShape(cell != null && cellType != null && cellType.startsWith(CELLS_PACKAGE) &&
        body[cell + 1].opcode == Opcode.INVOKE_DIRECT && body[cell + 1].controlRef() == cellType + CELL_INIT,
        "the Test Backend label no longer goes on a checkbox cell")
    val test = (cell!! - 1 downTo 2).firstOrNull { body[it].opcode == Opcode.IF_EQZ }
    controlShape(test != null, "the Test Backend checkbox is no longer behind a test")
    val register = (body[test!!] as OneRegisterInstruction).registerA
    val result = body[test - 1]
    controlShape(result.opcode == Opcode.MOVE_RESULT && (result as OneRegisterInstruction).registerA == register &&
        body[test - 2].controlRef() == IS_TEST_BACKEND, "the Test Backend checkbox no longer waits on isTestBackend()")
    val flow = ControlFlow.of(method)
    val skip = flow.normal[test].filter { it != test + 1 }.controlSingle("the Test Backend test's way past the checkbox")
    controlShape(skip > label, "the Test Backend test doesn't skip the checkbox")
    // Telegram's own second test keeps the box to signing in, so changing a number never shows it.
    controlShape((test + 1 until cell).any { body[it].opcode == Opcode.IF_NEZ && skip in flow.normal[it] },
        "the Test Backend checkbox is no longer kept to signing in")
    // Nothing but the answer's own move-result may reach the test, or that way in would skip the hook.
    val otherEntry = flow.normal.indices.any { it != test - 1 && test in flow.normal[it] } ||
        flow.exceptional.any { test in it }
    controlShape(!otherEntry, "something else jumps onto the Test Backend test")
    return TestBackendCheck(method, test, register)
}
