/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.restrictions

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.hostsOf
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.morphe.util.names.origName
import app.morphe.util.names.origParameterTypes
import app.morphe.util.names.origRef
import app.morphe.util.names.origReturnType
import app.morphe.util.names.tgMutableClassDefBy
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val MESSAGES_CONTROLLER = "Lorg/telegram/messenger/MessagesController;"
private const val CHAT_HOOK = "Lapp/hushtelegram/extension/telegram/misc/ChannelRestrictions;->isChatNoForwards(I)I"
private const val PEER_HOOK = "Lapp/hushtelegram/extension/telegram/misc/ChannelRestrictions;->noForwards(I)I"
private const val REASON_HOOK = "Lapp/hushtelegram/extension/telegram/misc/ChannelRestrictions;->ignoreRestrictionReason(Ljava/util/ArrayList;)Z"

/** What the protected forward's stubs reach: the forward, the sends, the message and its file. */
internal val FORWARD_HOSTS = setOf(
    "Lorg/telegram/messenger/SendMessagesHelper;", "Lorg/telegram/messenger/AccountInstance;", "Lorg/telegram/messenger/MessageObject;",
    "Lorg/telegram/tgnet/TLRPC\$Message;", "Lorg/telegram/tgnet/TLRPC\$Document;", "Lorg/telegram/messenger/FileLoader;",
    "Lorg/telegram/messenger/DialogObject;", "Lorg/telegram/tgnet/TLObject;",
)

private val FORWARD_GATE_OWNERS = ConcurrentHashMap<String, List<ClassDef>>()

/**
 * The chat screen and shared media, whose Forward first asks whether a selected message is protected
 * (#73). A test that runs Bypass channel restrictions and expects nothing to warn about adds these.
 */
internal fun forwardGateOwners(build: File): List<ClassDef> = FORWARD_GATE_OWNERS.hostsOf(build.path) {
    FixtureDex.classesWhere(build, { true }) { it.selectedNoForwardsRead() >= 0 }
}

private fun Opcode.kind() = if (this == Opcode.GOTO_16 || this == Opcode.GOTO_32) Opcode.GOTO else this

class BypassChannelRestrictionsFixtureTest {
    @Test
    fun `every restriction check asks the extension first in every declared build`() {
        for (build in Fixtures.declaredBuilds()) {
            val hosts = FixtureDex.classes(build, setOf(MESSAGES_CONTROLLER) + FORWARD_HOSTS).values
            val context = PatchContexts.of((ExtensionDex.classes() + hosts + storyOwners(build) + bannedAlertHosts(build) +
                forwardGateOwners(build)).distinctBy { it.type })
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
            val reasonCode = controller.methods.single { m ->
                m.origName == "getRestrictionReason" && m.origReturnType == "Ljava/lang/String;"
            }.implementation!!
            val reason = reasonCode.instructions.toList()
            assertEquals("${build.name}: getRestrictionReason invoke-static", REASON_HOOK, reason[0].origRef())
            assertEquals("${build.name}: getRestrictionReason hands over its list (p1)",
                reasonCode.registerCount - 1, (reason[0] as FiveRegisterInstruction).registerC)
            assertEquals("${build.name}: getRestrictionReason move-result", Opcode.MOVE_RESULT, reason[1].opcode)
            assertEquals("${build.name}: getRestrictionReason branch", Opcode.IF_EQZ, reason[2].opcode)
            assertEquals("${build.name}: getRestrictionReason answers null", Opcode.RETURN_OBJECT, reason[4].opcode)
            for (flag in listOf("bypassChannelRestrictions", "openRestrictedChats", "bannedChannelWeb")) {
                val status = context.tgMutableClassDefBy(SETTINGS_STATUS).methods
                    .single { it.origName == flag }
                    .implementation!!.instructions.toList()
                assertEquals("${build.name}: $flag set", 1, (status[0] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals(Opcode.RETURN, status[1].opcode)
            }
        }
    }

    @Test
    fun `every answer of the story viewer's check goes through the extension in every declared build`() {
        for (build in Fixtures.declaredBuilds()) {
            val owners = storyOwners(build)
            assertEquals("${build.name}: one class holds the story check", 1, owners.size)
            val owner = owners.single()
            val stockAnswers = owner.methods.single { it.isStoryCheck() }.returns().size
            assertTrue("${build.name}: the check answers more than once", stockAnswers > 1)
            val hosts = FixtureDex.classes(build, setOf(MESSAGES_CONTROLLER) + FORWARD_HOSTS).values
            val context = PatchContexts.of((ExtensionDex.classes() + hosts + owner + bannedAlertHosts(build) +
                forwardGateOwners(build)).distinctBy { it.type })
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { bypassChannelRestrictionsPatch.execute(context) })
            val check = context.tgMutableClassDefBy(owner.type).methods.single { it.isStoryCheck() }
            val body = check.implementation!!.instructions.toList()
            val answers = check.returns()
            assertEquals("${build.name}: every answer kept", stockAnswers, answers.size)
            for (at in answers) {
                val answer = (body[at] as OneRegisterInstruction).registerA
                assertEquals("${build.name}: the answer at $at asks the extension", STORY_HOOK, body[at - 2].origRef())
                assertEquals("${build.name}: with the answer it was about to give", answer,
                    (body[at - 2] as FiveRegisterInstruction).registerC)
                assertEquals(Opcode.MOVE_RESULT, body[at - 1].opcode)
                assertEquals("${build.name}: and gives the extension's back", answer, (body[at - 1] as OneRegisterInstruction).registerA)
            }
        }
    }

    @Test
    fun `Forward from a selection and from a private chat asks the extension in every declared build`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val owners = forwardGateOwners(build)
            assertEquals("$name: the chat screen and shared media", listOf("Lorg/telegram/ui", "Lorg/telegram/ui/Components"),
                owners.map { it.type.substringBeforeLast('/') }.sorted())
            val hosts = FixtureDex.classes(build, setOf(MESSAGES_CONTROLLER) + FORWARD_HOSTS).values
            val stockPrivateChat = hosts.flatMap { it.methods }.single { it.isPrivateChatCheck() }.implementation!!.instructions.toList()
            val stockSelection = owners.map { owner ->
                val check = owner.methods.single { it.selectedNoForwardsRead() >= 0 }
                Triple(owner.type, check.name, check.selectedNoForwardsRead()) to check.implementation!!.instructions.toList()
            }
            val context = PatchContexts.of((ExtensionDex.classes() + hosts + storyOwners(build) + bannedAlertHosts(build) +
                owners).distinctBy { it.type })
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { bypassChannelRestrictionsPatch.execute(context) })

            val privateChat = context.tgMutableClassDefBy(MESSAGES_CONTROLLER).methods.single { it.isPrivateChatCheck() }
            val body = privateChat.implementation!!.instructions.toList()
            assertEquals("$name: the private chat check sets v0", listOf(Opcode.CONST_4, listOf(0)), listOf(body[0].opcode, body[0].namedRegisters()))
            assertTrue("$name: v0 is a local, not the profile", privateChat.implementation!!.registerCount > 1)
            assertEquals(1, (body[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$name: asks the extension", CHAT_HOOK, body[1].origRef())
            assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.RETURN), body.subList(2, 5).map { it.opcode })
            assertEquals("$name: then Telegram's own check", stockPrivateChat.map { it.opcode.kind() }, body.drop(5).map { it.opcode.kind() })

            for ((site, stock) in stockSelection) {
                val (type, methodName, read) = site
                val check = context.tgMutableClassDefBy(type).methods.single {
                    it.name == methodName && it.parameterTypes.isEmpty() && it.returnType == "Z"
                }
                val after = check.implementation!!.instructions.toList()
                val flag = (stock[read] as TwoRegisterInstruction).registerA
                assertEquals("$name: $type reads the flag", MESSAGE_NO_FORWARDS, after[read].origRef())
                assertEquals("$name: $type then asks the extension", listOf(Opcode.INVOKE_STATIC_RANGE, PEER_HOOK, listOf(flag)),
                    listOf(after[read + 1].opcode, after[read + 1].origRef(), after[read + 1].namedRegisters()))
                assertEquals("$name: $type and branches on its answer", listOf(Opcode.MOVE_RESULT, listOf(flag), Opcode.IF_EQZ, listOf(flag)),
                    listOf(after[read + 2].opcode, after[read + 2].namedRegisters(), after[read + 3].opcode, after[read + 3].namedRegisters()))
                assertEquals("$name: $type keeps the rest", stock.map { it.opcode.kind() },
                    (after.take(read + 1) + after.drop(read + 3)).map { it.opcode.kind() })
            }
        }
    }

    private fun storyOwners(build: java.io.File) = FixtureDex.classesWhere(build, { true }) { it.isStoryCheck() }

    private fun Method.isPrivateChatCheck() = AccessFlags.STATIC.isSet(accessFlags) && returnType == "Z" &&
        origParameterTypes == listOf("Lorg/telegram/tgnet/TLRPC\$UserFull;") &&
        implementation?.instructions?.any { it.origRef() == "Lorg/telegram/tgnet/TLRPC\$UserFull;->noforwards_peer_enabled:Z" } == true
}
