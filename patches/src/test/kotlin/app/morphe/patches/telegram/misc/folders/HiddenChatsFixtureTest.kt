/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.folders

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.chattypefolders.CLIENT_USER_ID
import app.morphe.patches.telegram.misc.chattypefolders.CONTROLLER_INSTANCE
import app.morphe.patches.telegram.misc.chattypefolders.MESSAGES_CONTROLLER
import app.morphe.patches.telegram.misc.chattypefolders.SELECTED_ACCOUNT
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both pinned builds' chat sort, which hands its full lists to the extension before it drops empty folders. */
class HiddenChatsFixtureTest {
    @Test fun `the sort hands the controller over once, right before the empty folder walk`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = PatchContexts.of(ExtensionDex.classes() + launchFolderHosts(build))
            context.requireHiddenChatsRuntime()
            val plan = context.resolveHiddenChats()
            assertNull("$name: ${plan.missing}", plan.missing)
            val method = plan.sort!!
            val stock = method.body().map(::operands)
            val stockEntries = ControlFlow.of(method).normal.withIndex().count { (_, next) -> plan.at in next }
            plan.apply()
            val body = method.body()
            assertEquals("$name: the call is one instruction", stock.size + HIDDEN_CHATS_SIZE, body.size)
            val hook = body[plan.at]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
            assertEquals("$HIDDEN_CHATS->prune(Ljava/lang/Object;)V", hook.ref())
            assertEquals(listOf(plan.controller), hook.namedRegisters())
            // Then the walk's start and its head: dialogsByFolder read off the same register and its size asked.
            val head = body[plan.at + 2]
            assertEquals(BY_FOLDER, head.ref())
            assertEquals(plan.controller, (head as TwoRegisterInstruction).registerB)
            assertEquals("Landroid/util/SparseArray;->size()I", body[plan.at + 3].ref())
            assertEquals("$name: Telegram's own code is unchanged around it", stock,
                (body.take(plan.at) + body.drop(plan.at + 1)).map(::operands))
            // The real sort jumps onto the walk's start past the forward list's own entry. That jump lands on the call.
            assertTrue("$name: the start is a jump target", stockEntries >= 2)
            val entries = ControlFlow.of(method).normal.withIndex().count { (_, next) -> plan.at in next }
            assertEquals("$name: every way into the walk runs the call", stockEntries, entries)
            assertEquals("$name: nothing jumps past the call to the start", 1,
                ControlFlow.of(method).normal.withIndex().count { (_, next) -> plan.at + 1 in next })
        }
    }

    @Test fun `the whole patch fills every stub and turns the switch on`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = PatchContexts.of((ExtensionDex.classes() + folderCounterHosts(build) + launchFolderHosts(build)).distinctBy { it.type })
            assertEquals(name, emptyList<String>(), PatchLogCapture.warnings { hideFolderCountersPatch.execute(context) })
            val stubs = context.mutableClassDefBy(HIDDEN_CHATS).methods.associateBy { it.name }
            val sort = context.mutableClassDefBy(MESSAGES_CONTROLLER).methods.single { it.name == "sortDialogs" }.toString()
            // Each one whole: smali drops what it can't build, and the count shows it.
            val expected = mapOf(
                "controllerUser" to (6 to listOf(GET_USER_CONFIG, CLIENT_USER_ID)),
                "folders" to (3 to listOf(BY_FOLDER)),
                "selectedFilters" to (3 to listOf(SELECTED_FILTERS)),
                "filterDialogs" to (3 to listOf(FILTER_DIALOGS)),
                "dialogId" to (3 to listOf(DIALOG_ID)),
                "selectedAccount" to (2 to listOf(SELECTED_ACCOUNT)),
                "userId" to (5 to listOf(CLIENT_USER_ID)),
                "dialogs" to (5 to listOf(CONTROLLER_INSTANCE, GET_DIALOGS)),
                "peerName" to (5 to listOf(CONTROLLER_INSTANCE, PEER_NAME)),
                "resort" to (11 to listOf(CONTROLLER_INSTANCE, sort, NOTIFICATIONS_INSTANCE, DIALOGS_NEED_RELOAD, POST_NOTIFICATION)),
            )
            for ((stub, want) in expected) {
                val body = stubs.getValue(stub).body()
                assertEquals("$name: $stub", want.first, body.size)
                val calls = body.mapNotNull { it.ref() }
                for (reference in want.second) assertTrue("$name: $stub reaches $reference", reference in calls)
            }
            // sortDialogs(null): the argument is a zero.
            val resort = stubs.getValue("resort").body()
            val call = resort.single { it.ref() == sort }
            assertEquals(Opcode.CONST_4, resort[resort.indexOf(call) - 1].opcode)
            assertEquals(1L, statusOf(context, "hiddenChats"))
            assertEquals(1L, statusOf(context, "launchFolder"))
            assertEquals(1, context.mutableClassDefBy(MESSAGES_CONTROLLER).methods.single { it.name == "sortDialogs" }.body()
                .count { it.ref() == "$HIDDEN_CHATS->prune(Ljava/lang/Object;)V" })
        }
    }

    @Test fun `a sort that changed leaves the switch out and the method as it was`() {
        class Case(val name: String, val reason: String, val edit: (MutableMethod) -> Unit)
        val cases = listOf(
            Case("no empty folder drop", "no longer drops empty folders") { sort ->
                sort.removeInstruction(sort.body().indexOfFirst { it.ref() == "Landroid/util/SparseArray;->remove(I)V" })
            },
            Case("the walk's start written twice", "the empty folder walk's start changed") { sort ->
                val head = headOf(sort)
                sort.addInstructions(head, "nop")
            },
        )
        for (build in Fixtures.declaredBuilds()) for (case in cases) {
            val where = "${build.name} ${case.name}"
            val context = PatchContexts.of(ExtensionDex.classes() + launchFolderHosts(build))
            val sort = context.sortDialogs()
            case.edit(sort)
            val before = sort.body().map(::operands)
            val plan = context.resolveHiddenChats()
            assertTrue("$where: ${plan.missing}", plan.missing.orEmpty().contains(case.reason))
            assertNull(where, plan.sort)
            plan.apply()
            assertEquals("$where: nothing is edited", before, context.sortDialogs().body().map(::operands))
        }
    }

    @Test fun `without the notification centre the switch is left out`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + launchFolderHosts(build)
                .filter { it.type != "Lorg/telegram/messenger/NotificationCenter;" })
            val plan = context.resolveHiddenChats()
            assertTrue("${build.name}: ${plan.missing}", plan.missing.orEmpty().contains("isn't reachable from the extension"))
        }
    }

    /** The empty folder walk's head: the read of dialogsByFolder that a goto after its remove jumps back to. */
    private fun headOf(sort: Method): Int {
        val body = sort.body()
        val remove = body.indexOfFirst { it.ref() == "Landroid/util/SparseArray;->remove(I)V" }
        val back = (remove + 1 until body.size).first { body[it].opcode == Opcode.GOTO || body[it].opcode == Opcode.GOTO_16 }
        return ControlFlow.of(sort).normal[back].single()
    }

    private fun BytecodePatchContext.sortDialogs(): MutableMethod =
        mutableClassDefBy(MESSAGES_CONTROLLER).methods.single { it.name == "sortDialogs" }

    private fun statusOf(context: BytecodePatchContext, flag: String) =
        (context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }.body()[0] as WideLiteralInstruction).wideLiteral

    private fun operands(instruction: Instruction): List<Any?> = listOf(instruction.opcode, instruction.namedRegisters(), instruction.ref())

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference?.toString()
}
