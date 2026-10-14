/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.downloadspeed

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** FileLoadOperation.updateParams on both declared builds, where Telegram picks a download's piece size. */
class FasterDownloadsFixtureTest {
    private fun context(build: File): BytecodePatchContext {
        val host = FixtureDex.classes(build, setOf(LOAD_OPERATION)).values.map(ImmutableClassDef::of)
        assertEquals("${build.name}: FileLoadOperation", 1, host.size)
        // DownloadController and every class that reads its download mask, which only a dex calling it can hold.
        val downloads = FixtureDex.classesWhere(build, { dex ->
            dex.methodSection.any { it.definingClass == DOWNLOAD_CONTROLLER && it.name == "getCurrentDownloadMask" }
        }) { m -> m.definingClass == DOWNLOAD_CONTROLLER || m.implementation != null && m.controlBody().any { it.controlRef() == CURRENT_MASK } }
        assertEquals("${build.name}: DownloadController", 1, downloads.count { it.type == DOWNLOAD_CONTROLLER })
        return PatchContexts.of(ExtensionDex.classes() + host + downloads)
    }

    private fun ClassDef.status(name: String) = methods.single { it.name == name }.controlBody()

    private fun literal(after: List<Instruction>, size: Long) =
        after.indices.single { (after[it] as? WideLiteralInstruction)?.wideLiteral == size }

    @Test fun `the extension answers for the server's flag and the small-piece fallback still has the last word`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = context(build)
            val plan = context.resolveFasterDownloads()
            val old = ImmutableMethod.of(plan.method)
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { fasterDownloadsPatch.execute(context) })

            val before = old.controlBody()
            val after = plan.method.controlBody()
            assertEquals("$name: two instructions", before.size + 2, after.size)
            val read = plan.read
            assertEquals(EXPERIMENTAL_PARAMS, after[read].controlRef())
            assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ), after.subList(read + 1, read + 4).map { it.opcode })
            assertEquals("$DOWNLOAD_SPEED->fast(Z)Z", after[read + 1].controlRef())
            for (i in read + 1..read + 3) assertEquals("$name: $i", listOf(plan.register), after[i].namedRegisters())

            // A no still goes to the 128 KB tier, and forceSmallChunk still sends a yes there too.
            val flow = ControlFlow.of(plan.method)
            val small = literal(after, SMALL_PIECE)
            val big = literal(after, BIG_PIECE)
            assertEquals("$name: the stock branch", setOf(read + 4, small), flow.normal[read + 3].toSet())
            assertEquals(FORCE_SMALL_CHUNK, after[read + 4].controlRef())
            assertEquals("$name: the fallback", setOf(read + 6, small), flow.normal[read + 5].toSet())
            assertEquals("$name: a yes reaches the 512 KB tier", read + 6, big)
            assertEquals(CHUNK_SIZE, after[big + 1].controlRef())
            assertEquals(CHUNK_SIZE, after[small + 1].controlRef())

            for (i in before.indices) {
                val j = if (i <= read) i else i + 2
                assertEquals("$name: stock $i", listOf(before[i].opcode, before[i].namedRegisters(), (before[i] as? ReferenceInstruction)?.reference?.toString()),
                    listOf(after[j].opcode, after[j].namedRegisters(), (after[j] as? ReferenceInstruction)?.reference?.toString()))
            }
            val status = context.mutableClassDefBy(SETTINGS_STATUS).status("fasterDownloads")
            assertEquals(1L, (status.first() as WideLiteralInstruction).wideLiteral)
        }
    }

    @Test fun `a changed piece size choice refuses before anything is edited`() {
        val build = Fixtures.declaredBuilds().first()
        val shapes = listOf<Pair<String, (BytecodePatchContext) -> Unit>>(
            "the server's flag no longer picks the piece size" to { context ->
                val plan = context.resolveFasterDownloads()
                plan.method.replaceInstruction(plan.read + 1, "nop")
            },
            "the server's big-piece flag is missing or ambiguous" to { context ->
                val plan = context.resolveFasterDownloads()
                plan.method.addInstruction(0, "iget-boolean v${plan.register}, v${plan.register}, $EXPERIMENTAL_PARAMS")
            },
            "Telegram's small-piece fallback no longer follows the server's flag" to { context ->
                val plan = context.resolveFasterDownloads()
                plan.method.replaceInstruction(plan.read + 3, "nop")
            },
            // The piece size still resolves, so the Data Saver hold alone stops the patch before any edit.
            "DownloadController.canPreloadStories()Z no longer reads the network's download settings" to { context ->
                val method = context.mutableClassDefBy(DOWNLOAD_CONTROLLER).methods.single { it.name == "canPreloadStories" }
                val body = method.controlBody()
                method.replaceInstruction(body.indices.single { body[it].controlRef() == NETWORK_TYPE_READ },
                    "invoke-static {}, Lorg/telegram/messenger/ApplicationLoader;->isNetworkOnline()Z")
            },
        )
        for ((why, change) in shapes) {
            val context = context(build)
            change(context)
            val untouched = snapshot(context)
            try {
                fasterDownloadsPatch.execute(context)
                fail("$why: the patch went ahead")
            } catch (refused: PatchException) {
                assertTrue("$why: ${refused.message}", refused.message!!.contains(why))
            }
            assertEquals(why, untouched, snapshot(context))
            assertEquals(why, 0L, (context.mutableClassDefBy(SETTINGS_STATUS).status("fasterDownloads").first() as WideLiteralInstruction).wideLiteral)
            assertEquals(why, 0L, (context.mutableClassDefBy(SETTINGS_STATUS).status("dataSaverDownloads").first() as WideLiteralInstruction).wideLiteral)
        }
    }

    /** Every method body the patch may edit, as opcodes and registers. */
    private fun snapshot(context: BytecodePatchContext) = listOf(LOAD_OPERATION, DOWNLOAD_CONTROLLER, *maskReaders(context))
        .flatMap { type -> context.mutableClassDefBy(type).methods.filter { it.implementation != null }
            .map { m -> "$type->${m.name}" to m.controlBody().map { it.opcode to it.namedRegisters() } } }

    private fun maskReaders(context: BytecodePatchContext) = mutableListOf<String>().also { types ->
        context.classDefForEach { cls ->
            if (cls.type != DOWNLOAD_CONTROLLER && cls.methods.any { m -> m.implementation != null && m.controlBody().any { it.controlRef() == CURRENT_MASK } }) types += cls.type
        }
    }.toTypedArray()

    @Test fun `automatic downloads ask the Data Saver hold first and the mask reads outside DownloadController pass through it`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = context(build)
            val saver = context.resolveDataSaverDownloads()
            assertEquals("$name: every answer", AutoDownloadAnswer.entries.toSet(), saver.answers.keys)
            // Two rich message media blocks and one chat cell's layout each read the mask once.
            assertEquals("$name: the outside mask reads", 3, saver.masks.size)
            assertEquals("$name: one read per class", 3, saver.masks.map { it.method.definingClass }.toSet().size)
            val stock = saver.answers.mapValues { ImmutableMethod.of(it.value).controlBody() }
            val stockMasks = saver.masks.map { ImmutableMethod.of(it.method).controlBody() }
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { fasterDownloadsPatch.execute(context) })

            for ((answer, method) in saver.answers) {
                val where = "$name: ${answer.signature}"
                val before = stock.getValue(answer)
                val after = method.controlBody()
                val void = answer.signature.endsWith("V")
                val gate = if (void) 4 else 5
                assertEquals(where, before.size + gate, after.size)
                assertEquals(where, listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ), after.subList(0, 3).map { it.opcode })
                assertEquals(where, "$DATA_SAVER->hold()Z", after[0].controlRef())
                val held = after[1].namedRegisters()
                assertEquals(where, held, after[2].namedRegisters())
                if (void) {
                    assertEquals(where, Opcode.RETURN_VOID, after[3].opcode)
                } else {
                    assertEquals(where, Opcode.CONST_4, after[3].opcode)
                    assertEquals(where, 0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals(where, Opcode.RETURN, after[4].opcode)
                    assertEquals(where, held, after[4].namedRegisters())
                }
                // A no carries on into Telegram's own answer, untouched.
                assertEquals(where, setOf(3, gate), ControlFlow.of(method).normal[2].toSet())
                for (i in before.indices) {
                    assertEquals("$where: stock $i", listOf(before[i].opcode, before[i].namedRegisters(), (before[i] as? ReferenceInstruction)?.reference?.toString()),
                        listOf(after[i + gate].opcode, after[i + gate].namedRegisters(), (after[i + gate] as? ReferenceInstruction)?.reference?.toString()))
                }
            }

            for ((read, before) in saver.masks.zip(stockMasks)) {
                val where = "$name: ${read.method.definingClass}->${read.method.name}"
                val after = read.method.controlBody()
                assertEquals(where, before.size + 2, after.size)
                assertEquals(where, CURRENT_MASK, after[read.index - 1].controlRef())
                assertEquals(where, listOf(Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT),
                    after.subList(read.index, read.index + 3).map { it.opcode })
                assertEquals(where, "$DATA_SAVER->mask(I)I", after[read.index + 1].controlRef())
                for (i in read.index..read.index + 2) assertEquals("$where: $i", listOf(read.register), after[i].namedRegisters())
                for (i in before.indices) {
                    val j = if (i <= read.index) i else i + 2
                    assertEquals("$where: stock $i", listOf(before[i].opcode, before[i].namedRegisters(), (before[i] as? ReferenceInstruction)?.reference?.toString()),
                        listOf(after[j].opcode, after[j].namedRegisters(), (after[j] as? ReferenceInstruction)?.reference?.toString()))
                }
            }
            // DownloadController's own mask reads, the network change check among them, stay Telegram's.
            val controller = context.mutableClassDefBy(DOWNLOAD_CONTROLLER)
            assertTrue(name, controller.methods.filter { it.implementation != null }.none { m ->
                m.controlBody().any { it.controlRef() == "$DATA_SAVER->mask(I)I" } })
            val status = context.mutableClassDefBy(SETTINGS_STATUS).status("dataSaverDownloads")
            assertEquals(1L, (status.first() as WideLiteralInstruction).wideLiteral)
        }
    }
}

private const val NETWORK_TYPE_READ = "Lorg/telegram/messenger/ApplicationLoader;->getAutodownloadNetworkType()I"
