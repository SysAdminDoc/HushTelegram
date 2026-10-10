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
        return PatchContexts.of(ExtensionDex.classes() + host)
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
        )
        for ((why, change) in shapes) {
            val context = context(build)
            change(context)
            val method = context.mutableClassDefBy(LOAD_OPERATION).methods.single { it.name == "updateParams" }
            val untouched = method.controlBody().map { it.opcode to it.namedRegisters() }
            try {
                fasterDownloadsPatch.execute(context)
                fail("$why: the patch went ahead")
            } catch (refused: PatchException) {
                assertTrue("$why: ${refused.message}", refused.message!!.contains(why))
            }
            assertEquals(why, untouched, method.controlBody().map { it.opcode to it.namedRegisters() })
            assertEquals(why, 0L, (context.mutableClassDefBy(SETTINGS_STATUS).status("fasterDownloads").first() as WideLiteralInstruction).wideLiteral)
        }
    }
}
