/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.maintabs

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.telegram.misc.extension.localRegisterCount
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.ControlFlow
import app.morphe.util.branchTarget
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Telegram's main screen, its bar and tab views, its pager and the runtime. */
class HideContactsTabFixtureTest {
    @Test fun `the Contacts tab is hidden each time the bar is set, and a swipe asks before landing on its page`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val plan = context.resolveMainTabs()
            assertEquals("$name: one swipe question each way", listOf(true, false), plan.gates.map { it.forward })

            val oldShown = ImmutableMethod.of(plan.shown)
            val shownBefore = oldShown.controlBody()
            val oldShownFlow = ControlFlow.of(oldShown)
            val shownWays = shownBefore.indices.filter { plan.shownReturn in oldShownFlow.normal[it] }
            assertTrue("$name: the Calls or Settings method reaches its end more than one way", shownWays.size >= 2)
            val oldGates = plan.gates.map { ImmutableMethod.of(it.method) }
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { hideContactsTabPatch.execute(context) })

            // The bar's method: the hook in front of its one return, on every way there.
            val shownAfter = plan.shown.controlBody()
            assertEquals("$name: one instruction", shownBefore.size + 1, shownAfter.size)
            val hook = shownAfter[plan.shownReturn]
            assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
            assertEquals(TABS_SHOWN, hook.controlRef())
            assertEquals("$name: this and the animated flag",
                listOf(oldShown.localRegisterCount(), oldShown.localRegisterCount() + 2), hook.namedRegisters())
            assertEquals(Opcode.RETURN_VOID, shownAfter[plan.shownReturn + 1].opcode)
            val shownFlow = ControlFlow.of(plan.shown)
            for (way in shownWays) assertTrue("$name: way $way", plan.shownReturn in shownFlow.normal[way])
            assertStock(name, shownBefore, shownAfter) { if (it < plan.shownReturn) it else it + 1 }

            // Each swipe question: the page and the way to the extension, a no on true, Telegram's own question on false.
            for ((gate, old) in plan.gates.zip(oldGates)) {
                val what = "$name ${gate.method.name}"
                val before = old.controlBody()
                val after = gate.method.controlBody()
                val self = old.localRegisterCount()
                assertEquals(what, before.size + 7, after.size)
                assertEquals(what, listOf(Opcode.IGET, Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                    Opcode.CONST_4, Opcode.RETURN), after.take(7).map { it.opcode })
                assertTrue(what, gate.position != gate.direction && gate.position < self && gate.direction < self)
                assertEquals(what, listOf(gate.position, self), after[0].namedRegisters())
                assertEquals(what, plan.position, after[0].controlRef())
                assertEquals(what, listOf(gate.direction), after[1].namedRegisters())
                assertEquals(what, if (gate.forward) 1 else 0, (after[1] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals(what, BLOCKS_SWIPE, after[2].controlRef())
                assertEquals(what, listOf(gate.position, gate.direction), after[2].namedRegisters())
                assertEquals(what, listOf(gate.position), after[3].namedRegisters())
                assertEquals(what, listOf(gate.position), after[4].namedRegisters())
                assertEquals("$what: false goes on to Telegram's question", 7, after.branchTarget(4) { throw AssertionError(it) })
                assertEquals(what, 0, (after[5] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals(what, listOf(gate.position), after[6].namedRegisters())
                assertStock(what, before, after) { it + 7 }
            }

            // The stubs reach the bar, the kept tabs and the pager the way Telegram does.
            val runtime = context.mutableClassDefBy(MAIN_TABS).methods
            fun stub(method: String) = runtime.single { it.name == method }.controlBody()
            val hide = stub("hideContacts")
            assertEquals(plan.screen, hide.first().controlRef())
            assertEquals(Opcode.CHECK_CAST, hide.first().opcode)
            assertEquals(listOf(plan.bar, plan.tabs), hide.filter { it.opcode == Opcode.IGET_OBJECT }.map { it.controlRef() })
            // The tab taken from the kept tabs is the Contacts one.
            val aget = hide.indexOfFirst { it.opcode == Opcode.AGET_OBJECT }
            val contactsIndex = hide[(aget - 1 downTo 0).first { hide[it].namedRegisters().firstOrNull() == hide[aget].namedRegisters()[2] }]
            assertEquals(Opcode.CONST_4, contactsIndex.opcode)
            assertEquals(CONTACTS, (contactsIndex as NarrowLiteralInstruction).narrowLiteral)
            val call = hide.indexOfFirst { it.opcode == Opcode.INVOKE_VIRTUAL }
            assertEquals(plan.setTab, hide[call].controlRef())
            assertEquals(hide[aget].namedRegisters()[0], hide[call].namedRegisters()[1])
            // Hidden, with the animation the bar's own change has (p1, the last of six registers).
            val visible = (aget + 1 until call).single { hide[it].opcode == Opcode.CONST_4 && hide[it].namedRegisters()[0] == hide[call].namedRegisters()[2] }
            assertEquals(0, (hide[visible] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(5, hide[call].namedRegisters()[3])

            val page = stub("page")
            assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.IGET, Opcode.RETURN, Opcode.CONST_4, Opcode.RETURN),
                page.map { it.opcode })
            assertEquals(listOf(plan.screen, plan.pager, plan.position), listOf(page[0], page[1], page[3]).map { it.controlRef() })
            assertEquals(-1, (page[5] as NarrowLiteralInstruction).narrowLiteral)

            val chats = stub("showChats")
            assertEquals(plan.screen, chats.first().controlRef())
            assertEquals(plan.pager, chats.single { it.opcode == Opcode.IGET_OBJECT }.controlRef())
            assertEquals(listOf(plan.slide, plan.set, plan.mark), chats.filter { it.opcode == Opcode.INVOKE_VIRTUAL }.map { it.controlRef() })
            val first = chats.single { it.opcode == Opcode.CONST_4 }
            assertEquals(0, (first as NarrowLiteralInstruction).narrowLiteral)
            for (call in chats.filter { it.opcode == Opcode.INVOKE_VIRTUAL }) assertEquals(first.namedRegisters()[0], call.namedRegisters()[1])
            // The screen marks Chats with the animation it was given (p0 and p1, the last of four registers).
            assertEquals(listOf(2, first.namedRegisters()[0], 3), chats.filter { it.opcode == Opcode.INVOKE_VIRTUAL }.last().namedRegisters())

            val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "hideContactsTab" }.controlBody()
            assertEquals(1L, (status.first() as WideLiteralInstruction).wideLiteral)
        }
    }

    /** Every stock instruction is still there, at [moved] of its old index, unchanged. */
    private fun assertStock(what: String, before: List<Instruction>, after: List<Instruction>, moved: (Int) -> Int) {
        for (i in before.indices) {
            val j = moved(i)
            assertEquals("$what: stock $i", listOf(before[i].opcode, before[i].namedRegisters(), before[i].controlRef()),
                listOf(after[j].opcode, after[j].namedRegisters(), after[j].controlRef()))
        }
    }

    /**
     * The main screen, found the way the patch finds it, with the classes it names that the patch
     * reads: its superclass, the types of their fields, the tab views, and each one's superclasses.
     */
    private fun hosts(build: File): Collection<ClassDef> {
        val screens = FixtureDex.classesWhere(build, { true }, ::isMainBuild)
        assertEquals("${build.name}: one main screen", 1, screens.size)
        val loaded = LinkedHashMap<String, ClassDef>()
        screens.forEach { loaded[it.type] = it }
        val asked = HashSet<String>()
        var wanted = setOfNotNull(screens.single().superclass)
        while (wanted.isNotEmpty()) {
            asked += wanted
            loaded.putAll(FixtureDex.classes(build, wanted))
            val holders = listOfNotNull(loaded[screens.single().type], loaded[screens.single().superclass])
            val named = holders.flatMap { cls -> cls.fields.map { it.type.trimStart('[') } } +
                loaded.values.mapNotNull { it.superclass }
            wanted = named.filter { it.startsWith("L") && isAppType(it) && it !in asked && it !in loaded }.toSet()
        }
        return loaded.values
    }

    private fun isAppType(type: String) = !type.startsWith("Landroid") && !type.startsWith("Ljava") && !type.startsWith("Lkotlin")

    private fun isMainBuild(method: Method) = method.name == "createView" && method.controlBody().let { body ->
        listOf(CONTACTS_LABEL, CALLS_LABEL, SHOW_CALLS_TAB).all { ref -> body.any { it.controlRef() == ref } }
    }
}
