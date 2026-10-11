/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.chattypefolders

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableEncodedValue.Companion.toMutable
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.value.ImmutableIntEncodedValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The folder stubs on both declared builds: Telegram's own folder list, limit, update request and local list. */
class ChatTypeFoldersFixtureTest {
    private val hosts = setOf(MESSAGES_CONTROLLER, LOCAL_FILTER, USER_CONFIG, CONNECTIONS, SERVER_FILTER, NEW_FILTER, TITLE,
        UPDATE_FILTER, STORAGE, NOTIFICATIONS, "Lorg/telegram/messenger/support/LongSparseIntArray;")
    private val stubs = listOf("selectedAccount", "signedIn", "userId", "foldersLoaded", "folderCount", "folderId",
        "folderFlags", "folderName", "folderPlain", "folderLimit", "createFolder", "addFolder", "deleteFolder")
    private val get = "Ljava/util/ArrayList;->get(I)Ljava/lang/Object;"
    private val isEmpty = "Ljava/util/ArrayList;->isEmpty()Z"

    private fun context(build: File): BytecodePatchContext {
        val host = FixtureDex.classes(build, hosts).values.map(ImmutableClassDef::of)
        assertEquals("${build.name}: Telegram's folder classes", emptySet<String>(), hosts - host.map { it.type }.toSet())
        return PatchContexts.of(ExtensionDex.classes() + host)
    }

    private fun ClassDef.method(name: String) = methods.single { it.name == name }
    private fun BytecodePatchContext.method(name: String) = mutableClassDefBy(CHAT_TYPE_FOLDERS).method(name)
    private fun BytecodePatchContext.stub(name: String) = method(name).controlBody()
    private fun List<Instruction>.refs() = mapNotNull { it.controlRef() }
    private fun BytecodePatchContext.status() =
        (mutableClassDefBy(SETTINGS_STATUS).method("chatTypeFolders").controlBody().first() as WideLiteralInstruction).wideLiteral

    /** The register each parameter arrives in; none of these stubs takes a long. */
    private fun Method.param(index: Int) = implementation!!.registerCount - parameters.size + index

    /** The first register of the one instruction that names [reference], which carries the value or the receiver. */
    private fun BytecodePatchContext.first(stub: String, reference: String): Int =
        stub(stub).single { it.controlRef() == reference }.namedRegisters().first()

    private fun BytecodePatchContext.assertParams(name: String, stub: String, uses: List<Pair<String, Int>>) {
        val method = method(stub)
        for ((reference, index) in uses) assertEquals("$name: $stub $reference", method.param(index), first(stub, reference))
    }

    @Test fun `the stubs reach Telegram's folders and do what its own folder screens do`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = context(build)
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { chatTypeFoldersPatch.execute(context) })

            assertEquals(listOf(SELECTED_ACCOUNT), context.stub("selectedAccount").refs())
            assertEquals(listOf(USER_CONFIG_INSTANCE, CLIENT_ACTIVATED), context.stub("signedIn").refs())
            assertEquals(listOf(USER_CONFIG_INSTANCE, CLIENT_USER_ID), context.stub("userId").refs())
            assertEquals(Opcode.MOVE_RESULT_WIDE, context.stub("userId")[3].opcode)
            assertEquals(listOf(CONTROLLER_INSTANCE, FILTERS_LOADED), context.stub("foldersLoaded").refs())
            assertEquals(listOf(CONTROLLER_INSTANCE, FILTERS, "Ljava/util/ArrayList;->size()I"), context.stub("folderCount").refs())
            for ((stub, read) in listOf("folderId" to LOCAL_ID, "folderFlags" to LOCAL_FLAGS, "folderName" to LOCAL_NAME)) {
                assertEquals("$name: $stub", listOf(CONTROLLER_INSTANCE, FILTERS, get, LOCAL_FILTER, read), context.stub(stub).refs())
                context.assertParams(name, stub, listOf(CONTROLLER_INSTANCE to 0))
                assertEquals("$name: $stub index", context.method(stub).param(1), context.stub(stub).single { it.controlRef() == get }.namedRegisters()[1])
            }
            assertEquals(listOf(CONTROLLER_INSTANCE, FILTERS, get, LOCAL_FILTER, LOCAL_COLOR, LOCAL_ENTITIES, isEmpty,
                LOCAL_ALWAYS, isEmpty, LOCAL_NEVER, isEmpty, LOCAL_PINNED, PINS_SIZE), context.stub("folderPlain").refs())
            // A set color is 0 or more; no color is -1, as Telegram reads a folder without one.
            assertTrue(name, context.stub("folderPlain").any { it.opcode == Opcode.IF_GEZ })
            assertEquals(listOf(CONTROLLER_INSTANCE, USER_CONFIG_INSTANCE, IS_PREMIUM, LIMIT_PREMIUM, LIMIT_DEFAULT),
                context.stub("folderLimit").refs())

            assertEquals(listOf(NEW_FILTER, NEW_FILTER_INIT, FILTER_ID, TITLE, "$TITLE-><init>()V", TITLE_TEXT, FILTER_TITLE,
                FILTER_CONTACTS, FILTER_NON_CONTACTS, FILTER_GROUPS, FILTER_BROADCASTS, FILTER_BOTS, UPDATE_FILTER, UPDATE_INIT,
                UPDATE_ID, UPDATE_FLAGS, UPDATE_FLAGS, UPDATE_FILTER_FIELD, CONNECTIONS_INSTANCE, SEND_REQUEST),
                context.stub("createFolder").refs())
            context.assertParams(name, "createFolder", listOf(FILTER_ID to 1, TITLE_TEXT to 2, FILTER_CONTACTS to 3,
                FILTER_NON_CONTACTS to 4, FILTER_GROUPS to 5, FILTER_BROADCASTS to 6, FILTER_BOTS to 7, UPDATE_ID to 1,
                CONNECTIONS_INSTANCE to 0))
            // The filter flag is set before the filter goes in, so the request carries it.
            assertTrue(name, context.stub("createFolder").any { it.opcode == Opcode.OR_INT_LIT8 })

            val add = context.stub("addFolder").refs()
            assertEquals(listOf(LOCAL_FILTER, LOCAL_INIT, LOCAL_ID, LOCAL_NAME, LOCAL_FLAGS, LOCAL_COLOR, LOCAL_UNREAD,
                LOCAL_PENDING_UNREAD, CONTROLLER_INSTANCE, ADD_FILTER, STORAGE_INSTANCE, SAVE_FILTER, NOTIFICATIONS_INSTANCE,
                FILTERS_UPDATED, "[Ljava/lang/Object;"), add.dropLast(1))
            val post = context.mutableClassDefBy(MESSAGES_CONTROLLER).methods.single { it.toString() == REMOVE_FILTER }
                .controlBody().refs().first { it.startsWith("$NOTIFICATIONS->") && it.endsWith("(I[Ljava/lang/Object;)V") }
            assertEquals(name, post, add.last())
            context.assertParams(name, "addFolder", listOf(LOCAL_ID to 1, LOCAL_NAME to 2, LOCAL_FLAGS to 3,
                CONTROLLER_INSTANCE to 0, STORAGE_INSTANCE to 0, NOTIFICATIONS_INSTANCE to 0))

            assertEquals(listOf(UPDATE_FILTER, UPDATE_INIT, UPDATE_ID, CONNECTIONS_INSTANCE, SEND_REQUEST, CONTROLLER_INSTANCE,
                FILTERS_BY_ID, "Landroid/util/SparseArray;->get(I)Ljava/lang/Object;", LOCAL_FILTER, REMOVE_FILTER,
                STORAGE_INSTANCE, DELETE_FILTER), context.stub("deleteFolder").refs())
            context.assertParams(name, "deleteFolder", listOf(UPDATE_ID to 1, CONNECTIONS_INSTANCE to 0,
                CONTROLLER_INSTANCE to 0, STORAGE_INSTANCE to 0))
            assertEquals(name, context.method("deleteFolder").param(1),
                context.stub("deleteFolder").single { it.controlRef()?.startsWith("Landroid/util/SparseArray;") == true }.namedRegisters()[1])

            for (stub in stubs) {
                val method = context.method(stub)
                assertTrue("$name: $stub", AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
                // Every register a stub names fits the register count it was written with.
                for (instruction in method.controlBody()) {
                    assertTrue("$name: $stub ${instruction.opcode}",
                        instruction.namedRegisters().all { it < method.implementation!!.registerCount })
                }
            }
            assertEquals(1L, context.status())
        }
    }

    /** Makes the bots bit something else, wherever this build keeps its starting value. */
    private fun BytecodePatchContext.changeBotsFlag() {
        val controller = mutableClassDefBy(MESSAGES_CONTROLLER)
        val bots = TYPE_FLAGS.last().first
        val field = controller.fields.single { "$MESSAGES_CONTROLLER->${it.name}:${it.type}" == bots }
        if (field.initialValue is IntEncodedValue) field.initialValue = ImmutableIntEncodedValue(32).toMutable()
        val init = controller.methods.singleOrNull { it.name == "<clinit>" } ?: return
        val body = init.controlBody()
        val put = body.indexOfFirst { it.opcode == Opcode.SPUT && it.controlRef() == bots }
        if (put < 0) return
        val register = (body[put] as OneRegisterInstruction).registerA
        val set = (put - 1 downTo 0).first { (body[it] as? OneRegisterInstruction)?.registerA == register }
        init.replaceInstruction(set, "const/16 v$register, 0x20")
    }

    @Test fun `changed folder flags, a changed list update or an unreachable member refuse before anything is written`() {
        val build = Fixtures.declaredBuilds().first()
        val shapes = listOf<Pair<String, (BytecodePatchContext) -> Unit>>(
            "Telegram's folder chat type flags changed" to { context -> context.changeBotsFlag() },
            "Telegram's folder list update changed" to { context ->
                val remove = context.mutableClassDefBy(MESSAGES_CONTROLLER).methods.single { it.toString() == REMOVE_FILTER }
                val at = remove.controlBody().indexOfFirst { it.opcode == Opcode.SGET && it.controlRef() == FILTERS_UPDATED }
                remove.replaceInstruction(at, "const/4 v0, 0x0")
            },
            "$REMOVE_FILTER isn't reachable from the extension" to { context ->
                val remove = context.mutableClassDefBy(MESSAGES_CONTROLLER).methods.single { it.toString() == REMOVE_FILTER }
                remove.accessFlags = remove.accessFlags and AccessFlags.PUBLIC.value.inv() or AccessFlags.PRIVATE.value
            },
            "$UPDATE_FILTER_FIELD isn't reachable from the extension" to { context ->
                val update = context.mutableClassDefBy(UPDATE_FILTER)
                val field = update.fields.single { "$UPDATE_FILTER->${it.name}:${it.type}" == UPDATE_FILTER_FIELD }
                field.accessFlags = field.accessFlags and AccessFlags.PUBLIC.value.inv() or AccessFlags.PRIVATE.value
            },
        )
        for ((why, change) in shapes) {
            val context = context(build)
            val untouched = stubs.associateWith { stub -> context.stub(stub).map { it.opcode } }
            change(context)
            try {
                chatTypeFoldersPatch.execute(context)
                fail("$why: the patch went ahead")
            } catch (refused: PatchException) {
                assertTrue("$why: ${refused.message}", refused.message!!.contains(why))
            }
            assertEquals(why, untouched, stubs.associateWith { stub -> context.stub(stub).map { it.opcode } })
            assertEquals(why, 0L, context.status())
        }
    }
}
