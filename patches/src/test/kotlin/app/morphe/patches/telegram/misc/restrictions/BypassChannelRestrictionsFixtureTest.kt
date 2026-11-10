/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.restrictions

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.morphe.util.names.origName
import app.morphe.util.names.origRef
import app.morphe.util.names.origReturnType
import app.morphe.util.names.tgMutableClassDefBy

private const val MESSAGES_CONTROLLER = "Lorg/telegram/messenger/MessagesController;"
private const val CHAT_HOOK = "Lapp/hushtelegram/extension/telegram/misc/ChannelRestrictions;->isChatNoForwards(I)I"
private const val PEER_HOOK = "Lapp/hushtelegram/extension/telegram/misc/ChannelRestrictions;->noForwards(I)I"

class BypassChannelRestrictionsFixtureTest {
    @Test
    fun `both restriction checks ask the extension first in every declared build`() {
        for (build in Fixtures.declaredBuilds()) {
            val hosts = FixtureDex.classes(build, setOf(MESSAGES_CONTROLLER)).values
            val context = PatchContexts.of(ExtensionDex.classes() + hosts)
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { bypassChannelRestrictionsPatch.execute(context) })
            val controller = context.tgMutableClassDefBy(MESSAGES_CONTROLLER)
            for ((methodName, hook) in listOf(
                "isChatNoForwards" to CHAT_HOOK,
                "isPeerNoForwards" to PEER_HOOK,
            )) {
                val method = controller.methods.single { m ->
                    m.origName == methodName && m.origReturnType == "Z"
                }
                val body = method.implementation!!.instructions.toList()
                assertTrue("${build.name}: $methodName starts with const/4", body[0].opcode == Opcode.CONST_4)
                assertEquals(1, (body[0] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("${build.name}: $methodName invoke-static", hook, body[1].origRef())
                assertEquals("${build.name}: $methodName move-result", Opcode.MOVE_RESULT, body[2].opcode)
                assertEquals("${build.name}: $methodName branch", Opcode.IF_NEZ, body[3].opcode)
            }
            val status = context.tgMutableClassDefBy(SETTINGS_STATUS).methods
                .single { it.origName == "bypassChannelRestrictions" }
                .implementation!!.instructions.toList()
            assertEquals("${build.name}: status flag set", 1, (status[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.RETURN, status[1].opcode)
        }
    }
}
