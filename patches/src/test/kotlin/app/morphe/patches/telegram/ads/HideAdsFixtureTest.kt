/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.ads

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide ads on each declared build: the messages controller's `getSponsoredMessages(long)` and
 * `VideoAds.load()` are both there, found by what they build rather than by name, and the patch,
 * run over the build's own classes, puts the extension's question in front of each one and leaves
 * the rest of the method alone.
 */
class HideAdsFixtureTest {
    private val videoAds = "Lorg/telegram/messenger/video/VideoAds;"
    private val ads = "Lapp/hushtelegram/extension/telegram/ads/Ads;"

    @Test
    fun `each declared build has both ad requests, and the patch hooks both with nothing left to warn about`() {
        for (build in Fixtures.declaredBuilds()) {
            val where = build.name
            val classes = FixtureDex.classes(build, setOf(MESSAGES_CONTROLLER, videoAds))
            assertEquals("$where: the messages controller and VideoAds", setOf(MESSAGES_CONTROLLER, videoAds), classes.keys)

            val sponsored = classes.getValue(MESSAGES_CONTROLLER).methods.single { method ->
                method.returnType == "Lorg/telegram/messenger/MessagesController\$SponsoredMessagesInfo;" &&
                    method.parameterTypes == listOf("J")
            }
            val video = classes.getValue(videoAds).methods.single { it.name == "load" && it.parameterTypes.isEmpty() }

            val context = PatchContexts.of(ExtensionDex.classes() + classes.values)
            val warnings = PatchLogCapture.warnings { hideAdsPatch.execute(context) }
            assertEquals("$where: the patch log", emptyList<String>(), warnings)

            assertHooked(
                "$where: getSponsoredMessages", sponsored,
                context.mutableClassDefBy(MESSAGES_CONTROLLER).methods.single { it.sameSignatureAs(sponsored) },
                "$ads->skipSponsoredMessages()Z", listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT),
            )
            assertHooked(
                "$where: VideoAds.load", video,
                context.mutableClassDefBy(videoAds).methods.single { it.sameSignatureAs(video) },
                "$ads->skipVideoAds()Z", listOf(Opcode.RETURN_VOID),
            )

            val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "hideAds" }.instructions()
            assertEquals("$where: SettingsStatus.hideAds() answers true first", Opcode.CONST_4, status[0].opcode)
            assertEquals(1, (status[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.RETURN, status[1].opcode)
        }
    }

    /**
     * [patched] starts with a call to [hook], reads its answer, skips the early return when it's
     * false, and otherwise runs [earlyReturn] before falling into every instruction [original] had,
     * unmoved.
     */
    private fun assertHooked(where: String, original: Method, patched: Method, hook: String, earlyReturn: List<Opcode>) {
        val before = original.instructions()
        val after = patched.instructions()
        val head = earlyReturn.size + 3
        assertEquals("$where: instructions added", before.size + head, after.size)
        assertEquals("$where: asks the extension first", Opcode.INVOKE_STATIC, after[0].opcode)
        assertEquals(hook, (after[0] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals(Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$where: the early return", earlyReturn, after.subList(3, head).map { it.opcode })
        assertEquals("$where: nothing else moved", before.map { it.opcode }, after.subList(head, after.size).map { it.opcode })
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }
}
