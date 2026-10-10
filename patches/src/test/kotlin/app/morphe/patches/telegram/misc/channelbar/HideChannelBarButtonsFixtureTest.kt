/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.channelbar

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.telegram.misc.commerce.COMMERCE
import app.morphe.patches.telegram.misc.commerce.hideCommercePatch
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A channel's bottom bar, the chat screen that fills it, the Recent actions log that reuses it, and the runtime. */
class HideChannelBarButtonsFixtureTest {
    @Test fun `the chat screen hands each bar button to the extension and Recent actions keeps its own Search`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val plan = context.resolveChannelBarButtons()
            // Search 0, Direct messages 2 and Info 3; Gift, 1, stays with Hide Premium, gifts and Stars.
            assertEquals("$name: side buttons", 1 or (1 shl 2) or (1 shl 3), plan.mask)
            val before = ImmutableMethod.of(plan.update).controlBody()
            val elsewhere = placeCallers(context, plan.place) - signature(plan.update)
            assertTrue("$name: Recent actions places a button of the same bar", elsewhere.isNotEmpty())
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { hideChannelBarButtonsPatch.execute(context) })

            val after = plan.update.controlBody()
            assertEquals("$name: one for one", before.size, after.size)
            assertEquals("$name: all four buttons", 4, plan.calls.size)
            for (i in before.indices) {
                if (i in plan.calls) {
                    assertEquals("$name: $i", Opcode.INVOKE_STATIC, after[i].opcode)
                    assertEquals("$name: $i", SET_BUTTON, after[i].controlRef())
                    assertEquals("$name: $i keeps its operands", before[i].namedRegisters(), after[i].namedRegisters())
                } else {
                    assertEquals("$name: stock $i", listOf(before[i].opcode, before[i].namedRegisters(), before[i].controlRef()),
                        listOf(after[i].opcode, after[i].namedRegisters(), after[i].controlRef()))
                }
            }
            assertEquals("$name: Recent actions untouched", elsewhere, placeCallers(context, plan.place) - signature(plan.update))

            val sides = stub(context, "sideButtons")
            assertEquals(plan.mask, (sides[0] as NarrowLiteralInstruction).narrowLiteral)
            val place = stub(context, "place")
            assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), place.map { it.opcode })
            assertEquals(plan.bar, place[0].controlRef())
            assertEquals(plan.place, place[1].controlRef())
            val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "hideChannelButtons" }.controlBody()
            assertEquals(1L, (status.first() as WideLiteralInstruction).wideLiteral)
        }
    }

    @Test fun `Hide Premium's Gift guard and this patch both land in either order`() {
        for (build in Fixtures.declaredBuilds()) {
            for (commerceFirst in listOf(true, false)) {
                val name = "${build.name} commerce first=$commerceFirst"
                val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
                val plan = context.resolveChannelBarButtons()
                if (commerceFirst) PatchLogCapture.warnings { hideCommercePatch.execute(context) }
                assertEquals(emptyList<String>(), PatchLogCapture.warnings { hideChannelBarButtonsPatch.execute(context) })
                if (!commerceFirst) PatchLogCapture.warnings { hideCommercePatch.execute(context) }
                val placer = context.mutableClassDefBy(plan.bar).methods.single { "${it.definingClass}->${it.name}(IZZ)V" == plan.place }
                assertTrue("$name: Gift guard", placer.controlBody().take(2).any { it.controlRef() == "$COMMERCE->showChannelGiftButton(IZ)Z" })
                assertEquals("$name: bar calls", 4, plan.update.controlBody().count { it.controlRef() == SET_BUTTON })
            }
        }
    }

    /** The bar's classes and every class that places one of its buttons. */
    private fun hosts(build: File): List<ClassDef> {
        val bars = FixtureDex.classesWhere(build, { true }) { m ->
            m.parameterTypes.map { it.toString() } == listOf("I", "Z", "Z") && m.controlBody().any { it.controlRef() in SIDE_LABELS }
        }
        val places = bars.flatMap { bar -> bar.methods.filter { it.parameterTypes.map { p -> p.toString() } == listOf("I", "Z", "Z") } }
            .map { "${it.definingClass}->${it.name}(IZZ)V" }.toSet()
        val users = FixtureDex.classesWhere(build, { true }) { m -> m.controlBody().any { it.controlRef() in places } }
        return (bars + users).distinctBy { it.type }
    }

    private fun placeCallers(context: BytecodePatchContext, place: String): Set<String> {
        val found = mutableSetOf<String>()
        context.classDefForEach { cls ->
            if (cls.type.startsWith("Lapp/hushtelegram/")) return@classDefForEach
            cls.methods.filter { m -> m.controlBody().any { it.controlRef() == place } }.forEach { found += signature(it) }
        }
        return found
    }

    private fun signature(m: Method) = "${m.definingClass}->${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}"

    private fun stub(context: BytecodePatchContext, name: String) =
        context.mutableClassDefBy(CHANNEL_BUTTONS).methods.single { it.name == name }.controlBody()
}
