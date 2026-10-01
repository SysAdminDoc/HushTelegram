/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.analytics

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.ads.MESSAGES_CONTROLLER
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Disable analytics on each declared build: the messages controller's `logDeviceStats()` is there,
 * found by the field it reads and the request it builds rather than by name, and the patch, run
 * over the build's own classes, puts the extension's question in front of it and leaves the rest of
 * the method alone.
 */
class DisableAnalyticsFixtureTest {
    private val analytics = "Lapp/hushtelegram/extension/telegram/misc/Analytics;"

    @Test
    fun `each declared build has logDeviceStats, and the patch hooks it`() {
        for (build in Fixtures.declaredBuilds()) {
            val where = build.name
            val classes = FixtureDex.classes(build, setOf(MESSAGES_CONTROLLER))
            assertEquals("$where: the messages controller", setOf(MESSAGES_CONTROLLER), classes.keys)

            val original = classes.getValue(MESSAGES_CONTROLLER).methods.single {
                it.name == "logDeviceStats" && it.parameterTypes.isEmpty() && it.returnType == "V"
            }

            val context = PatchContexts.of(ExtensionDex.classes() + classes.values)
            disableAnalyticsPatch.execute(context)

            val patched = context.mutableClassDefBy(MESSAGES_CONTROLLER).methods.single { it.sameSignatureAs(original) }
            val before = original.instructions()
            val after = patched.instructions()
            assertEquals("$where: instructions added", before.size + 4, after.size)
            assertEquals("$where: asks the extension first", Opcode.INVOKE_STATIC, after[0].opcode)
            assertEquals("$analytics->skipDeviceStats()Z", (after[0] as ReferenceInstruction).reference.toString())
            assertEquals(Opcode.MOVE_RESULT, after[1].opcode)
            assertEquals(Opcode.IF_EQZ, after[2].opcode)
            assertEquals("$where: the early return", Opcode.RETURN_VOID, after[3].opcode)
            assertEquals("$where: nothing else moved", before.map { it.opcode }, after.subList(4, after.size).map { it.opcode })

            val status = context.mutableClassDefBy(SETTINGS_STATUS).methods
                .single { it.name == "disableAnalytics" }.instructions()
            assertEquals("$where: SettingsStatus.disableAnalytics() answers true first", Opcode.CONST_4, status[0].opcode)
            assertEquals(1, (status[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.RETURN, status[1].opcode)
        }
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }
}
