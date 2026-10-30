/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.doubletap

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real reaction row and long-press menu builder of every declared build. */
class HideReactionsFixtureTest {
    private fun hosts(build: java.io.File): List<ClassDef> =
        FixtureDex.classesWhere(build, { true }) { it.isRowSetter() || it.isMenuBuilder() }

    private fun operand(i: Instruction) = listOf(i.opcode, i.namedRegisters(), i.controlRef(), (i as? WideLiteralInstruction)?.wideLiteral)

    @Test fun `the row setter gets its message through the hook and keeps every stock instruction`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hosts(build)
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val plan = context.resolveReactionRows()
            assertEquals("${build.name}: ${plan.missing}", null, plan.missing)
            val stock = classes.flatMap { it.methods }.single { it.isRowSetter() }
            val before = stock.controlBody()
            plan.apply()
            val patched = context.mutableClassDefBy(stock.definingClass).methods.single { it.toString() == stock.toString() }
            val after = patched.controlBody()
            assertEquals("${build.name}: register count", stock.implementation!!.registerCount, patched.implementation!!.registerCount)
            assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST), after.take(3).map { it.opcode })
            assertEquals("$DOUBLE_TAP_REACTIONS->rows(Ljava/lang/Object;ZZ)Ljava/lang/Object;", after[0].controlRef())
            assertEquals(MESSAGE_OBJECT, after[2].controlRef())
            assertEquals(after[1].namedRegisters(), after[2].namedRegisters())
            assertEquals("${build.name}: stock code follows unchanged", before.map(::operand), after.drop(3).map(::operand))
        }
    }

    @Test fun `the menu builder filters its one reactions-available register where both paths merge`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hosts(build)
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val plan = context.resolveReactionPicker()
            assertEquals("${build.name}: ${plan.missing}", null, plan.missing)
            val stock = classes.flatMap { it.methods }.single { it.isMenuBuilder() }
            val before = stock.controlBody()
            plan.apply()
            val patched = context.mutableClassDefBy(stock.definingClass).methods.single { it.toString() == stock.toString() }
            val after = patched.controlBody()
            val at = after.indexOfFirst { it.controlRef() == "$DOUBLE_TAP_REACTIONS->picker(Z)Z" }
            assertTrue("${build.name}: hook inserted", at > 0)
            assertEquals(Opcode.INVOKE_STATIC_RANGE, after[at].opcode)
            assertEquals(Opcode.MOVE_RESULT, after[at + 1].opcode)
            assertEquals("${build.name}: the hook reads and rewrites one register", after[at].namedRegisters().toSet(), after[at + 1].namedRegisters().toSet())
            assertEquals("${build.name}: stock code unchanged", before.map(::operand), (after.take(at) + after.drop(at + 2)).map(::operand))
            val flow = ControlFlow.of(patched)
            // The 1 path jumps to the hook and the 0 path falls into it.
            assertTrue(after[at - 2].opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32))
            assertEquals(listOf(at), flow.normal[at - 2])
            assertEquals(listOf(at), flow.normal[at - 1])
        }
    }

}
