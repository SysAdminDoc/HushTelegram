/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.api

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.ControlFlow
import app.morphe.util.insertAtControlFlowLabel
import app.morphe.util.names.origName
import app.morphe.util.names.origRef
import app.morphe.util.names.replaceInstruction
import app.morphe.util.names.tgMutableClassDefBy
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The sign-in screen's Test Backend checkbox on each declared build: the extension gets
 * isTestBackend()'s answer right before the screen tests it and hands its own back in the same
 * register, and the rest of the screen is Telegram's code unchanged.
 */
class TestServerSignInFixtureTest {
    @Test
    fun `the checkbox test asks the extension on each declared build`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val check = context.resolveTestServerSignIn()
            val stock = ImmutableMethod.of(check.method)
            assertEquals("$name: no warning", emptyList<String>(), PatchLogCapture.warnings { testServerSignInPatch.execute(context) })

            val before = stock.controlBody()
            assertEquals("$name: the test reads isTestBackend()'s answer", listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ),
                listOf(before[check.test - 1].opcode, before[check.test].opcode))
            assertEquals("$name: that answer comes from isTestBackend()", IS_TEST_BACKEND, before[check.test - 2].controlRef())
            val expected = mutableListOf<List<Any?>>()
            for ((index, instruction) in before.withIndex()) {
                if (instruction.opcode == Opcode.NOP) continue
                if (index == check.test) {
                    expected += listOf(Opcode.INVOKE_STATIC_RANGE, TEST_SERVERS_SHOWN)
                    expected += listOf(Opcode.MOVE_RESULT, null)
                }
                expected += instruction.shape()
            }
            val after = check.method.controlBody().filter { it.opcode != Opcode.NOP }
            assertEquals("$name: hooked right before the test, stock otherwise", expected, after.map { it.shape() })

            val at = after.indexOfFirst { it.controlRef() == TEST_SERVERS_SHOWN }
            val call = after[at] as RegisterRangeInstruction
            assertEquals("$name: the call takes v${check.register}", listOf(check.register, 1), listOf(call.startRegister, call.registerCount))
            assertEquals("$name: the answer goes back to v${check.register}", check.register, (after[at + 1] as OneRegisterInstruction).registerA)
            assertEquals("$name: the stock test reads the answer", check.register, (after[at + 2] as OneRegisterInstruction).registerA)
            assertEquals("$name: right after isTestBackend()'s move-result", listOf(Opcode.MOVE_RESULT, IS_TEST_BACKEND),
                listOf(after[at - 1].opcode, after[at - 2].controlRef()))
            val flow = ControlFlow.of(check.method)
            assertTrue("$name: only the hook's move-result reaches the test",
                flow.normal.indices.none { it != check.test + 1 && check.test + 2 in flow.normal[it] })
            assertEquals("$name: switch reported", 1L, statusFact(context))
        }
    }

    @Test
    fun `a changed sign-in screen refuses before anything is edited`() {
        val changes = mapOf(
            "way in" to "something else jumps onto the Test Backend test",
            "answer" to "the Test Backend checkbox no longer waits on isTestBackend()",
            "sign-in check" to "the Test Backend checkbox is no longer kept to signing in",
        )
        for (build in Fixtures.declaredBuilds()) for ((change, why) in changes) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val check = context.resolveTestServerSignIn()
            val method = check.method
            val body = method.controlBody()
            when (change) {
                "way in" -> {
                    method.insertAtControlFlowLabel(check.test - 4, "goto/32 :hush_way_in", ExternalLabel("hush_way_in", body[check.test]))
                    assertTrue("the jump really lands on the test", check.test + 1 in ControlFlow.of(method).normal[check.test - 4])
                }
                "answer" -> method.replaceInstruction(check.test - 1, "const/16 v${check.register}, 0x1")
                "sign-in check" -> method.replaceInstruction((check.test + 1 until body.size).first { body[it].opcode == Opcode.IF_NEZ }, "nop")
            }
            val changed = method.controlBody().map { it.shape() }
            try {
                testServerSignInPatch.execute(context)
                fail("${build.name}: $change was accepted")
            } catch (expected: PatchException) {
                assertTrue("${build.name}: $change: ${expected.message}", expected.message.orEmpty().contains("$why (before editing)"))
            }
            assertEquals("${build.name}: $change leaves the screen alone", changed, method.controlBody().map { it.shape() })
            assertEquals("${build.name}: $change reports no switch", 0L, statusFact(context))
        }
    }

    /** Opcode and Telegram's name for what it touches, with a widened goto read as a goto. */
    private fun Instruction.shape(): List<Any?> = listOf(
        when (opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> opcode
        },
        origRef(),
    )

    private fun hosts(build: File): List<ClassDef> =
        FixtureDex.classesWhere(build, census, { true }) { method -> method.controlBody().any { it.controlRef() == DEBUG_TEST_BACKEND } }

    private fun statusFact(context: BytecodePatchContext): Long =
        (context.tgMutableClassDefBy(SETTINGS_STATUS).methods.single { it.origName == "testServerSignIn" }.controlBody().first()
            as WideLiteralInstruction).wideLiteral

    private companion object {
        val census = FixtureDex.ClassCensus()
    }
}
