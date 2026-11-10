/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.autoforward

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Test
import app.morphe.util.names.origName
import app.morphe.util.names.origRef
import app.morphe.util.names.origReturnType
import app.morphe.util.names.tgMutableClassDefBy

private const val NOTIFICATIONS_CONTROLLER = "Lorg/telegram/messenger/NotificationsController;"
private const val AUTO_FORWARD_HOOK =
    "Lapp/hushtelegram/extension/telegram/misc/AutoForwarding;->onNewMessages(Ljava/lang/Object;Ljava/lang/Object;)V"

class AutoForwardingFixtureTest {
    @Test
    fun `processNewMessages hands its messages to the extension in every declared build`() {
        for (build in Fixtures.declaredBuilds()) {
            val hosts = FixtureDex.classes(build, setOf(NOTIFICATIONS_CONTROLLER)).values
            val context = PatchContexts.of(ExtensionDex.classes() + hosts)
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { autoForwardingPatch.execute(context) })
            val method = context.tgMutableClassDefBy(NOTIFICATIONS_CONTROLLER).methods.single { m ->
                m.origName == "processNewMessages" && m.origReturnType == "V" &&
                    !AccessFlags.STATIC.isSet(m.accessFlags) && m.parameterTypes.size == 4
            }
            val body = method.implementation!!.instructions.toList()
            assertEquals("${build.name}: move p1 down", Opcode.MOVE_OBJECT_FROM16, body[0].opcode)
            assertEquals("${build.name}: move p0 down", Opcode.MOVE_OBJECT_FROM16, body[1].opcode)
            assertEquals("${build.name}: invoke-static at 2", Opcode.INVOKE_STATIC, body[2].opcode)
            assertEquals(AUTO_FORWARD_HOOK, body[2].origRef())
            val status = context.tgMutableClassDefBy(SETTINGS_STATUS).methods
                .single { it.origName == "autoForwarding" }
                .implementation!!.instructions.toList()
            assertEquals("${build.name}: status flag set", 1, (status[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.RETURN, status[1].opcode)
        }
    }
}
