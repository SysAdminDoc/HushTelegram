/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.api

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sign-in help on each declared build: exactly one method is Telegram's general error handler, the
 * patch puts the extension's question in front of it with the error, screen and request, and the
 * handler's own code follows unchanged.
 */
class SignInHelpFixtureTest {
    @Test
    fun `the general error handler asks the extension first on each declared build`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val hosts = FixtureDex.classesWhere(build, { true }) { isProcessError(it) }
            assertEquals("$name: one general error handler", 1, hosts.sumOf { host -> host.methods.count { isProcessError(it) } })
            val before = hosts.single().methods.single { isProcessError(it) }.controlBody()

            val context = PatchContexts.of(ExtensionDex.classes() + hosts)
            assertEquals("$name: no warning", emptyList<String>(), PatchLogCapture.warnings { signInHelpPatch.execute(context) })

            val handler = context.mutableClassDefBy(hosts.single().type).methods.single { isProcessError(it) }
            val after = handler.controlBody()
            assertEquals("$name: five instructions", before.size + 5, after.size)
            assertEquals(
                listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                after.take(5).map { it.opcode },
            )
            assertEquals(SIGN_IN_EXPLAIN, after[0].controlRef())
            // p1 to p3: the error, the screen and the request, past the locals.
            val call = after[0] as RegisterRangeInstruction
            val locals = handler.implementation!!.registerCount - 5
            assertEquals("$name: the error, screen and request", listOf(locals + 1, 3), listOf(call.startRegister, call.registerCount))
            for (i in before.indices) {
                assertEquals(
                    "$name: stock $i",
                    listOf(before[i].opcode, (before[i] as? ReferenceInstruction)?.reference?.toString()),
                    listOf(after[i + 5].opcode, (after[i + 5] as? ReferenceInstruction)?.reference?.toString()),
                )
            }
        }
    }

    @Test
    fun `a build without the handler patches on and says the message is missing`() {
        val context = PatchContexts.of(ExtensionDex.classes())
        val warnings = PatchLogCapture.warnings { signInHelpPatch.execute(context) }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), warnings.single().startsWith("Sign-in help:"))
    }
}
