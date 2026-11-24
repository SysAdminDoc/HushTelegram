/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.stickers

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlString
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import app.morphe.util.names.replaceInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.morphe.util.names.origRef
import app.morphe.util.names.loadsInt
import app.morphe.util.names.origDefiningClass
import app.morphe.util.names.origName
import app.morphe.util.names.tgMutableClassDefBy

/** The message bubble's content setter, Telegram's message model and the runtime. */
class StickerSizeFixtureTest {
    private val contentLabel = "Lorg/telegram/messenger/R\$string;->SponsoredMessageAdWhatIsThis:I"

    @Test fun `the extension gets a sticker's largest side right after the bubble works it out`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = PatchContexts.of((ExtensionDex.classes() + sizeHosts(build) + cacheHosts(build)).distinctBy { it.type })
            val plan = context.resolveStickerSize()
            val old = ImmutableMethod.of(plan.method)
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { stickerSizePatch.execute(context) })

            val before = old.controlBody()
            val after = plan.method.controlBody()
            val at = plan.multiply
            assertEquals("$name: two instructions", before.size + 2, after.size)
            assertEquals(Opcode.MUL_FLOAT_2ADDR, after[at].opcode)
            // Plus Messenger adds its own size setting to the 0.5 right before the multiply.
            val entry = if (after[at - 1].opcode == Opcode.ADD_FLOAT_2ADDR) at - 1 else at
            assertEquals(Opcode.CONST_HIGH16, after[entry - 1].opcode)
            assertEquals(SIZE_HOOK, after[at + 1].controlRef())
            assertEquals("$name: the message and the size", listOf(plan.message, plan.size), after[at + 1].namedRegisters())
            assertEquals(Opcode.MOVE_RESULT, after[at + 2].opcode)
            assertEquals(listOf(plan.size), after[at + 2].namedRegisters())
            assertEquals("$name: Telegram rounds what the extension answers", Opcode.FLOAT_TO_INT, after[at + 3].opcode)
            assertEquals(plan.size, after[at + 3].namedRegisters().last())
            for (i in before.indices) {
                val shift = if (i > at) 2 else 0
                assertEquals("$name: stock $i", listOf(before[i].opcode, before[i].namedRegisters(), before[i].origRef()),
                    listOf(after[i + shift].opcode, after[i + shift].namedRegisters(), after[i + shift].origRef()))
            }
            // The phone's share falls into the multiply and the tablet's jumps to it, so both reach the hook.
            val flow = ControlFlow.of(plan.method)
            assertEquals("$name: the phone and the tablet", 2, flow.normal.indices.count { entry in flow.normal[it] })
            if (entry != at) assertEquals("$name: and only the add reaches the multiply", listOf(entry), flow.normal.indices.filter { at in flow.normal[it] })
            assertEquals("$name: only the multiply reaches the hook", listOf(at), flow.normal.indices.filter { at + 1 in flow.normal[it] })

            val stub = context.tgMutableClassDefBy(STICKER_SIZE).methods.single { it.origName == "emoji" }.controlBody().map { it.controlRef() }
            assertTrue("$name: the stub asks about animated emoji", "$MESSAGE_OBJECT->isAnimatedEmoji()Z" in stub)
            assertTrue("$name: and about dice", "$MESSAGE_OBJECT->isDice()Z" in stub)
            assertEquals(1L, statusOf(context, "stickerSize"))
            assertEquals(1L, statusOf(context, "keepStickerCache"))
        }
    }

    @Test fun `the cache cleanup asks the runtime whether each file is in use`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = PatchContexts.of(ExtensionDex.classes() + cacheHosts(build))
            val plan = context.resolveStickerCache()
            assertNull("$name: ${plan.missing}", plan.missing)
            val methods = plan.sites.map { it.first }.distinct()
            val stock = methods.associateWith { m -> m.controlBody().map(::operands) }
            plan.rehearse()
            for (m in methods) assertEquals("$name: a rehearsal edits nothing", stock.getValue(m), m.controlBody().map(::operands))
            plan.apply()
            var walks = 0
            var passes = 0
            for (m in methods) {
                val before = stock.getValue(m)
                val after = m.controlBody().map(::operands)
                assertEquals(name, before.size, after.size)
                val changed = before.indices.filter { before[it] != after[it] }
                for (at in changed) {
                    assertEquals("$name: ${m.name} $at", listOf(Opcode.INVOKE_INTERFACE, SET_CONTAINS_REF), listOf(before[at][0], before[at][2]))
                    assertEquals("$name: ${m.name} $at", listOf(Opcode.INVOKE_STATIC, before[at][1], CACHE_KEPT), after[at])
                    assertEquals("$name: the answer lands where Telegram's did", Opcode.MOVE_RESULT, after[at + 1][0])
                }
                if (m.isWalk()) walks += changed.size else passes += changed.size
            }
            // AutoDeleteMediaTask: once as the size limit's walk lists a file, and twice in the daily
            // pass, before it lists a file and again before it deletes one.
            assertEquals("$name: the size limit's walk", 1, walks)
            assertEquals("$name: the daily pass", 2, passes)
            // Every one of them asks about the same set, the one the walk reads.
            val sets = methods.flatMap { m -> m.controlBody().filter { it.opcode == Opcode.SGET_OBJECT && it.origRef()?.endsWith(":Ljava/util/Set;") == true }
                .map { it.origRef() } }.toSet()
            val walkSet = methods.single { it.isWalk() }.controlBody().single { it.opcode == Opcode.SGET_OBJECT }.origRef()
            assertTrue("$name: $sets", walkSet in sets && methods.all { m -> m.controlBody().any { it.origRef() == walkSet } })
        }
    }

    @Test fun `a cleanup walk that no longer asks about files in use leaves only Keep downloaded stickers out`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val hosts = cacheHosts(build)
            val context = PatchContexts.of((ExtensionDex.classes() + sizeHosts(build) + hosts).distinctBy { it.type })
            val walk = hosts.flatMap { cls -> context.tgMutableClassDefBy(cls.type).methods.filter { it.isWalk() } }.single()
            val ask = walk.controlBody().indexOfFirst { it.controlRef() == SET_CONTAINS_REF }
            val (set, path) = walk.controlBody()[ask].namedRegisters()
            walk.replaceInstruction(ask, "invoke-interface {v$set, v$path}, Ljava/util/Collection;->contains(Ljava/lang/Object;)Z")
            val stock = hosts.associate { cls -> cls.type to context.tgMutableClassDefBy(cls.type).methods.map { m -> m.controlBody().map(::operands) } }

            assertEquals(name, listOf("Change sticker size: the cache cleanup's walk no longer asks once whether a file is in use (0). " +
                "The patch goes on without Keep downloaded stickers."), PatchLogCapture.warnings { stickerSizePatch.execute(context) })
            assertEquals(1L, statusOf(context, "stickerSize"))
            assertEquals(0L, statusOf(context, "keepStickerCache"))
            for (cls in hosts) {
                assertEquals("$name: ${cls.type} stays as it was", stock.getValue(cls.type),
                    context.tgMutableClassDefBy(cls.type).methods.map { m -> m.controlBody().map(::operands) })
            }
        }
    }

    private fun sizeHosts(build: File) = FixtureDex.classesWhere(build, { true }) { m ->
        m.origDefiningClass == MESSAGE_OBJECT ||
            // 13.2 loads the label's id as a number, without the R field.
            m.controlBody().any { it.loadsInt() && it.controlRef() == contentLabel }
    }.map(ImmutableClassDef::of)

    /** AutoDeleteMediaTask's classes: the daily pass reads each Keep media time, and the walk passes over .nomedia. */
    private fun cacheHosts(build: File) = FixtureDex.classesWhere(build, { true }) { m ->
        m.controlBody().any { it.controlString() == "keep_media_type_" || it.controlString() == ".nomedia" }
    }.map(ImmutableClassDef::of)

    private fun statusOf(context: BytecodePatchContext, flag: String) =
        (context.tgMutableClassDefBy(SETTINGS_STATUS).methods.single { it.origName == flag }.controlBody().first() as WideLiteralInstruction).wideLiteral

    private fun operands(instruction: Instruction): List<Any?> = listOf(instruction.opcode, instruction.namedRegisters(), instruction.origRef())

    /** fillFilesRecursive(File, ArrayList). */
    private fun Method.isWalk() = AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.map(CharSequence::toString) == listOf("Ljava/io/File;", "Ljava/util/ArrayList;") &&
        controlBody().any { it.controlString() == ".nomedia" }

    private companion object {
        const val SET_CONTAINS_REF = "Ljava/util/Set;->contains(Ljava/lang/Object;)Z"
    }
}
