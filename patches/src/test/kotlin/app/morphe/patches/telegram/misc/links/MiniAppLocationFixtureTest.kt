/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.links

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Both pinned builds' bot location manager, web view prompt and its dialog, gated where the switch can answer. */
class MiniAppLocationFixtureTest {
    @Test fun `both builds keep location from Mini Apps and web pages and keep every stock instruction`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = PatchContexts.of(ExtensionDex.classes() + hostClasses(build))
            val plan = context.resolveMiniAppLocation()
            assertNull("$name: ${plan.missing}", plan.missing)
            assertEquals(LocationGate.values().toSet(), plan.gates.keys)
            val original = plan.gates.mapValues { ImmutableMethod.of(it.value) }
            assertEquals("$name: no warning", emptyList<String>(), PatchLogCapture.warnings { openExternalLinksPatch.execute(context) })

            for ((gate, method) in plan.gates) {
                val where = "$name ${gate.name} ${method.definingClass}->${method.name}"
                val before = original.getValue(gate).instructions()
                val after = method.instructions()
                val size = GATE_SIZES.getValue(gate)
                assertEquals("$where: every stock instruction follows the gate", before.map(::operands), after.drop(size).map(::operands))
                assertEquals("$where: registers are kept", original.getValue(gate).implementation!!.registerCount,
                    method.implementation!!.registerCount)
                val call = after[0]
                assertTrue("$where: the gate asks the switch first", call.ref()?.startsWith("$MINI_APP_LOCATION->") == true)
                val registers = method.implementation!!.registerCount
                assertEquals("$where: the switch gets the method's own arguments", when (gate) {
                    LocationGate.GPS -> emptyList()
                    LocationGate.ANSWER -> listOf(registers - 1)
                    LocationGate.PROMPT -> listOf(registers - 2, registers - 1)
                    LocationGate.DIALOG -> listOf(registers - 1)
                }, call.namedRegisters())

                // Stock branches and handlers land where they did, one gate further on.
                val oldFlow = ControlFlow.of(original.getValue(gate))
                val newFlow = ControlFlow.of(method)
                for (index in before.indices) {
                    assertEquals("$where: stock branch $index", oldFlow.normal[index].toSet(), newFlow.normal[index + size].map { it - size }.toSet())
                    assertEquals("$where: stock handler $index", oldFlow.exceptional[index].toSet(), newFlow.exceptional[index + size].map { it - size }.toSet())
                }
                if (gate.labeled) {
                    assertEquals(Opcode.IF_EQZ, after[2].opcode)
                    assertEquals("$where: a no runs the stock method", setOf(3, size), newFlow.normal[2].toSet())
                    assertTrue("$where: a yes ends the method", after[size - 1].opcode in setOf(Opcode.RETURN, Opcode.RETURN_VOID))
                } else {
                    assertEquals("$where: the answer goes back where the method reads it", call.namedRegisters(), after[1].namedRegisters())
                }
            }
            for (flag in listOf("openExternalLinks", "externalBrowserRouting", "miniAppLocation")) assertFlag(context, flag, true)
        }
    }

    @Test fun `a build whose location paths changed keeps link routing and leaves the switch out`() {
        class Case(val name: String, val reason: String, val hosts: (MutableList<ClassDef>) -> Unit = {},
                   val context: (BytecodePatchContext) -> Unit = {})
        val cases = listOf(
            Case("no manager", "the bot location manager is missing or ambiguous", hosts = { hosts ->
                hosts.removeAll { cls -> cls.methods.any { "botlocation_" in it.strings() } }
            }),
            Case("no dialog", "the geolocation dialog's answer is missing or ambiguous", hosts = { hosts ->
                hosts.removeAll { cls -> cls.methods.any { it.name == "accept" && it.calls(GEO_INVOKE) } }
            }),
            // A branch back to the GPS check's first instruction would run the gate twice.
            Case("jump to the start", "something jumps back to the start of", context = { context ->
                val gps = context.resolveMiniAppLocation().gates.getValue(LocationGate.GPS)
                gps.addInstructionsWithLabels(gps.instructions().size, "goto :hush_back", ExternalLabel("hush_back", gps.getInstruction(0)))
            }),
        )
        for (build in Fixtures.declaredBuilds()) for (case in cases) {
            val where = "${build.name} ${case.name}"
            val reason = case.reason
            val hosts = hostClasses(build).toMutableList()
            val locationTypes = hosts.filter { cls -> cls.methods.any(::isLocationHost) }.map { it.type }.toSet()
            case.hosts(hosts)
            val context = PatchContexts.of(ExtensionDex.classes() + hosts)
            case.context(context)
            val present = locationTypes.filter { type -> hosts.any { it.type == type } }
            val before = present.associateWith { context.mutableClassDefBy(it).state() }
            val warnings = PatchLogCapture.warnings { openExternalLinksPatch.execute(context) }
            assertEquals(where, 1, warnings.size)
            assertTrue("$where: ${warnings.single()}", warnings.single().contains(reason))
            assertEquals("$where: no location host is touched", before, present.associateWith { context.mutableClassDefBy(it).state() })
            assertFlag(context, "miniAppLocation", false)
            assertFlag(context, "openExternalLinks", true)
            assertFlag(context, "externalBrowserRouting", true)
        }
    }

    @Test fun `a missing location runtime or status method refuses before anything is edited`() {
        for (build in Fixtures.declaredBuilds()) for (missing in listOf("refuse", "location", "refusePrompt", "answer", "miniAppLocation")) {
            val where = "${build.name} without $missing"
            val context = PatchContexts.of(ExtensionDex.classes() + hostClasses(build))
            val plan = context.resolveMiniAppLocation()
            val before = plan.gates.mapValues { it.value.state() }
            val owner = if (missing == "miniAppLocation") SETTINGS_STATUS else MINI_APP_LOCATION
            context.mutableClassDefBy(owner).methods.removeAll { it.name == missing }
            assertThrows(where, PatchException::class.java) { openExternalLinksPatch.execute(context) }
            assertEquals("$where: every gate is as it was", before, plan.gates.mapValues { it.value.state() })
            if (missing != "miniAppLocation") {
                for (flag in listOf("openExternalLinks", "externalBrowserRouting", "miniAppLocation")) assertFlag(context, flag, false)
            }
        }
    }

    /** The link patch's own hosts, so it runs whole, and the three classes the location switch gates. */
    private fun hostClasses(build: File): List<ClassDef> {
        val hosts = FixtureDex.classesWhere(build, HOST_CENSUS, { true }) { method ->
            (method.returnType == "V" && method.parameterTypes.size == 10 &&
                method.parameterTypes.take(2) == listOf("Landroid/content/Context;", "Landroid/net/Uri;") &&
                method.instructions().any { it.ref() == "autologin_token" }) ||
                (method.instructions().any { it.ref() == "Lorg/telegram/messenger/R\$string;->ShareLink:I" } &&
                    method.instructions().any { it.ref() == "android.intent.action.SEND" }) ||
                isLocationHost(method)
        }
        val kept = FixtureDex.classes(build, setOf(MC, UC))
        assertEquals("account protection definitions", setOf(MC, UC), kept.keys)
        assertEquals("${build.name}: the manager, the prompt and its dialog", 3, hosts.count { cls -> cls.methods.any(::isLocationHost) })
        return hosts + kept.values
    }

    private fun isLocationHost(method: Method) = "botlocation_" in method.strings() || method.name == SHOW_PROMPT ||
        (method.name == "accept" && method.calls(GEO_INVOKE))

    private fun operands(instruction: Instruction): List<Any?> = listOf(
        instruction.opcode, instruction.namedRegisters(), instruction.ref(), (instruction as? WideLiteralInstruction)?.wideLiteral,
    )

    private fun assertFlag(context: BytecodePatchContext, name: String, enabled: Boolean) {
        val body = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.instructions()
        assertEquals(Opcode.CONST_4, body[0].opcode)
        assertEquals(name, if (enabled) 1 else 0, (body[0] as NarrowLiteralInstruction).narrowLiteral)
    }

    private fun Method.state(): List<Any?> = listOf(accessFlags, implementation?.registerCount, instructions().map(::operands))
    private fun ClassDef.state() = methods.associate { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" to it.state() }
    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    // Opcode.name in Kotlin is dexlib2's smali name field ("const-string"), so compare the opcodes themselves.
    private fun Method.strings() = instructions().filter { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
        .mapNotNull { it.ref() }.toSet()
    private fun Method.calls(reference: String) = instructions().any { it.ref() == reference }
    private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        val HOST_CENSUS = FixtureDex.ClassCensus()
        const val MC = "Lorg/telegram/messenger/MessagesController;"
        const val UC = "Lorg/telegram/messenger/UserConfig;"
        val GATE_SIZES = mapOf(LocationGate.GPS to 5, LocationGate.ANSWER to 2, LocationGate.PROMPT to 4, LocationGate.DIALOG to 2)
    }
}
