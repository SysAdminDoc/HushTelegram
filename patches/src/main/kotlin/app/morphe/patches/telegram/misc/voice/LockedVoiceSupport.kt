/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.voice

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.localRegisterCount
import app.morphe.patches.telegram.misc.extension.parameterRegisterNumber
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val LOCKED_VOICE = "$EXTENSION_PACKAGE/misc/LockedVoice;"
internal const val LOCKED_REFUSE = "$LOCKED_VOICE->refuse(Ljava/lang/Object;Ljava/lang/Object;Z)Z"
private const val MESSAGE = "Lorg/telegram/messenger/MessageObject;"
/** Telegram's one play method: the message and whether to play it without sound. */
internal const val PLAY_MESSAGE = "$MEDIA_CONTROLLER->playMessage(${MESSAGE}Z)Z"
/** The overload every other caller uses, which hands over to [PLAY_MESSAGE]. */
private const val PLAY_MESSAGE_PLAIN = "$MEDIA_CONTROLLER->playMessage($MESSAGE)Z"
/** The player's resume, which only [PLAY_MESSAGE] may reach. */
private const val RESUME_AUDIO = "$MEDIA_CONTROLLER->resumeAudio($MESSAGE)Z"
internal const val PLAYING_MESSAGE = "$MEDIA_CONTROLLER->playingMessageObject:$MESSAGE"
internal const val PLAYER_PAUSED = "$MEDIA_CONTROLLER->isPaused:Z"
private val INVOKES = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

/**
 * The locked phone guard's hook in [play], Telegram's play method, or [missing], why this build
 * can't take it and nothing to hook.
 */
internal class LockedVoiceGuard(val play: MutableMethod?, val missing: String?) {
    /** The same edit on a copy, so a smali problem refuses before the app is touched. */
    fun rehearse() = insert(MutableMethod(ImmutableMethod.of(play!!)))

    fun apply() = insert(play!!)

    /**
     * Borrows v0 to v2, which hold nothing before the method's first instruction, to read the player's
     * message and paused flag, which the runtime can't read from outside. On a refusal the method
     * answers false, as it does for no message.
     */
    private fun insert(target: MutableMethod) {
        val self = target.localRegisterCount()
        val message = target.parameterRegisterNumber(0)
        target.addInstructionsWithLabels(0, """
            move-object/from16 v0, v$self
            iget-object v1, v0, $PLAYING_MESSAGE
            iget-boolean v2, v0, $PLAYER_PAUSED
            move-object/from16 v0, v$message
            invoke-static {v0, v1, v2}, $LOCKED_REFUSE
            move-result v0
            if-eqz v0, :hush_play
            const/4 v0, 0x0
            return v0
        """.trimIndent(), ExternalLabel("hush_play", target.getInstruction(0)))
    }

    companion object {
        fun missing(why: String) = LockedVoiceGuard(null, why)
    }
}

/** The runtime's stub, Telegram's own voice and round video checks. On its own nothing calls it. */
internal fun BytecodePatchContext.writeLockedVoiceStub() {
    writeStub(LOCKED_VOICE, "voiceOrRound", 2, """
        check-cast p0, $MESSAGE
        invoke-virtual {p0}, $MESSAGE->isVoice()Z
        move-result v0
        if-nez v0, :hush_yes
        invoke-virtual {p0}, $MESSAGE->isRoundVideo()Z
        move-result v0
        :hush_yes
        return v0
    """.trimIndent())
}

/**
 * MediaController plays a message through one method, which both a tap and every outside control
 * reach: the notification, the lock screen, a headset or a car, all through the media session
 * Telegram's music service keeps. The overload without the silent flag hands over to it, and the
 * player's resume is private to it. It reads the message the player holds and whether that's
 * paused, which the hook passes on, so the runtime can let what's already playing carry on.
 *
 * A build whose player changed keeps Play voice messages one at a time and gets no guard.
 */
internal fun BytecodePatchContext.resolveLockedVoiceGuard(): LockedVoiceGuard {
    controlHook(LOCKED_VOICE, "refuse", listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Z"), "Z")
    controlHook(LOCKED_VOICE, "voiceOrRound", listOf("Ljava/lang/Object;"), "Z")
    val controller = classDefByOrNull(MEDIA_CONTROLLER) ?: return LockedVoiceGuard.missing("MediaController is missing")
    val message = classDefByOrNull(MESSAGE)
    if (message == null || listOf("isVoice", "isRoundVideo").any { name -> message.methods.none {
            it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Z" && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                !AccessFlags.STATIC.isSet(it.accessFlags) } }) {
        return LockedVoiceGuard.missing("a message no longer says whether it's voice or a round video")
    }
    if (!controller.hasField("playingMessageObject", MESSAGE) || !controller.hasField("isPaused", "Z")) {
        return LockedVoiceGuard.missing("the player no longer keeps its message and paused flag")
    }
    val plays = controller.methods.filter { signature(it) == PLAY_MESSAGE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    if (plays.size != 1 || plays.single().implementation == null) {
        return LockedVoiceGuard.missing("the player no longer has one play method (${plays.size})")
    }
    val found = plays.single()
    val plain = controller.methods.filter { signature(it) == PLAY_MESSAGE_PLAIN }
    if (plain.size != 1 || plain.single().controlBody().none { it.opcode in INVOKES && it.controlRef() == PLAY_MESSAGE }) {
        return LockedVoiceGuard.missing("the play method without a silent flag no longer hands over to it")
    }
    val body = found.controlBody()
    if (body.none { it.opcode in INVOKES && it.controlRef() == RESUME_AUDIO }) {
        return LockedVoiceGuard.missing("the play method no longer resumes a paused message itself")
    }
    val resumers = controller.methods.filter { method -> method.controlBody().any { it.opcode in INVOKES && it.controlRef() == RESUME_AUDIO } }
    val resume = controller.methods.filter { signature(it) == RESUME_AUDIO }
    if (resumers.map(::signature) != listOf(PLAY_MESSAGE) || resume.size != 1 || !AccessFlags.PRIVATE.isSet(resume.single().accessFlags)) {
        return LockedVoiceGuard.missing("a paused message can be resumed around the play method")
    }
    if (body.none { it.opcode == Opcode.IGET_OBJECT && it.controlRef() == PLAYING_MESSAGE } ||
        body.none { it.opcode == Opcode.IGET_BOOLEAN && it.controlRef() == PLAYER_PAUSED }) {
        return LockedVoiceGuard.missing("the play method no longer reads the player's message and paused flag")
    }
    val flow = ControlFlow.of(found)
    if (flow.normal.any { 0 in it } || flow.exceptional.any { 0 in it }) {
        return LockedVoiceGuard.missing("something jumps back to the start of the play method")
    }
    if (found.localRegisterCount() < 3) {
        return LockedVoiceGuard.missing("the play method has no three registers the hook can borrow")
    }
    return LockedVoiceGuard(mutableClassDefBy(MEDIA_CONTROLLER).methods.single { signature(it) == PLAY_MESSAGE }, null)
}

private fun ClassDef.hasField(name: String, type: String) =
    fields.any { it.name == name && it.type == type && !AccessFlags.STATIC.isSet(it.accessFlags) }

private fun signature(method: Method) = "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
