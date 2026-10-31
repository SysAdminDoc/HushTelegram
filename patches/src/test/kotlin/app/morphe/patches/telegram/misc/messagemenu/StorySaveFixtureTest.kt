/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.messagemenu

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Test

/** The story menu's real Save to Gallery checks in every declared build. */
class StorySaveFixtureTest {
    private fun operand(i: Instruction) = listOf(i.opcode, i.namedRegisters(), i.controlRef())

    @Test fun `the menu asks the extension at both checks, on the branch's own register, and is otherwise unchanged`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classesWhere(build, { true }) { it.isStorySaveMenu() }
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val plan = context.resolveStorySave()
            assertEquals("${build.name}: ${plan.missing}", null, plan.missing)
            val stock = classes.flatMap { it.methods }.single { it.isStorySaveMenu() }
            val before = stock.controlBody().map(::operand)
            plan.apply()
            val patched = context.mutableClassDefBy(stock.definingClass).methods.single { it.toString() == stock.toString() }
            val after = patched.controlBody()
            val hooks = after.indices.filter { after[it].controlRef()?.startsWith(STORY_SAVE) == true }
            assertEquals(build.name, listOf(STORY_SHAREABLE, STORY_PREMIUM), hooks.map { after[it].controlRef() })
            for (at in hooks) {
                val invoke = after[at] as RegisterRangeInstruction
                assertEquals(build.name, Opcode.INVOKE_STATIC_RANGE, after[at].opcode)
                assertEquals(build.name, 1, invoke.registerCount)
                assertEquals(build.name, Opcode.MOVE_RESULT, after[at + 1].opcode)
                assertEquals(build.name, Opcode.IF_EQZ, after[at + 2].opcode)
                assertEquals(build.name, invoke.startRegister, (after[at + 1] as OneRegisterInstruction).registerA)
                assertEquals(build.name, invoke.startRegister, (after[at + 2] as OneRegisterInstruction).registerA)
                assertEquals(build.name, invoke.startRegister, (after[at - 1] as OneRegisterInstruction).registerA)
            }
            val without = after.indices.filter { it !in hooks && (it - 1) !in hooks }.map { operand(after[it]) }
            assertEquals("${build.name}: stock code stays", before, without)
        }
    }
}
