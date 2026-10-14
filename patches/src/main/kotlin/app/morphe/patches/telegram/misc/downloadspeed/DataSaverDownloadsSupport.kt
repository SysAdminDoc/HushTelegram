/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.downloadspeed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.localRegisterCount
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val DATA_SAVER = "$EXTENSION_PACKAGE/misc/DataSaverDownloads;"
internal const val DOWNLOAD_CONTROLLER = "Lorg/telegram/messenger/DownloadController;"
internal const val CURRENT_MASK = "$DOWNLOAD_CONTROLLER->getCurrentDownloadMask()I"
internal const val CHECK_SETTINGS = "$DOWNLOAD_CONTROLLER->checkAutodownloadSettings()V"
private const val NETWORK_TYPE = "Lorg/telegram/messenger/ApplicationLoader;->getAutodownloadNetworkType()I"
private const val MESSAGE_OBJECT = "Lorg/telegram/messenger/MessageObject;"
private const val MESSAGE = "Lorg/telegram/tgnet/TLRPC\$Message;"
private const val INTERNAL = "canDownloadMediaInternal"
private const val FILE_LOADER = "Lorg/telegram/messenger/FileLoader;"
private const val HOLD = "invoke-static {}, $DATA_SAVER->hold()Z"

/** What an answer must still do to be the one about automatic downloads. */
internal enum class AutoDownloadCheck(val what: String) {
    /** Reads which network the phone is on to pick that network's automatic download settings. */
    NETWORK("reads the network's download settings"),
    /** Goes through canDownloadMediaInternal, which does. */
    MESSAGE("asks the message check that reads the network's download settings"),
    /** Starts the loads the download queue holds. */
    LOADS("starts the queue's downloads"),
}

/**
 * DownloadController's answers about automatic downloads, each by its [signature] in the class, and
 * the [check] that shows it's still that answer. A yes from the runtime, asked by [ask], answers no
 * at the start of each: no media types for the network, nothing to download for a chat's media, a
 * new message's media or a size, no next song or story to load ahead, and nothing started from the
 * download queue.
 */
internal enum class AutoDownloadAnswer(val signature: String, val check: AutoDownloadCheck, val ask: String = HOLD) {
    // The range form reaches p0 however many registers the method has; {p0} stops at v15.
    MASK("getCurrentDownloadMask()I", AutoDownloadCheck.NETWORK, "invoke-static/range {p0 .. p0}, $DATA_SAVER->holdMask(Ljava/lang/Object;)Z"),
    MESSAGE_MEDIA("canDownloadMedia($MESSAGE_OBJECT)Z", AutoDownloadCheck.MESSAGE),
    MESSAGE_MEDIA_TYPE("canDownloadMediaType($MESSAGE_OBJECT)I", AutoDownloadCheck.MESSAGE),
    MESSAGE_MEDIA_TYPE_AT("canDownloadMediaType(${MESSAGE_OBJECT}J)I", AutoDownloadCheck.MESSAGE),
    NEW_MESSAGE("canDownloadMedia($MESSAGE)I", AutoDownloadCheck.NETWORK),
    NEW_MESSAGE_MEDIA("canDownloadMedia(${MESSAGE}Lorg/telegram/tgnet/TLRPC\$MessageMedia;)I", AutoDownloadCheck.NETWORK),
    SIZED("canDownloadMedia(IJ)Z", AutoDownloadCheck.NETWORK),
    NEXT_TRACK("canDownloadNextTrack()Z", AutoDownloadCheck.NETWORK),
    STORIES("canPreloadStories()Z", AutoDownloadCheck.NETWORK),
    QUEUE("processDownloadObjects(ILjava/util/ArrayList;)V", AutoDownloadCheck.LOADS),
}

/** Every answer's method, found and checked before anything is edited, or [missing], why this build can't hold. */
internal class DataSaverPlan(val answers: Map<AutoDownloadAnswer, MutableMethod>, val missing: String?) {
    /** Every edit, on the methods themselves or on what [into] makes of each. */
    fun apply(into: (MutableMethod) -> MutableMethod = { it }) {
        for ((answer, method) in answers) insertHold(into(method), answer)
    }

    /** The same edits on copies, so a smali or register problem refuses before the app is touched. */
    fun rehearse() = apply { MutableMethod(ImmutableMethod.of(it)) }

    companion object {
        fun missing(why: String) = DataSaverPlan(emptyMap(), why)
    }
}

/** On a yes the answer is no: false or 0, or for the queue, nothing started. */
private fun insertHold(target: MutableMethod, answer: AutoDownloadAnswer) {
    val no = if (answer.signature.last() == 'V') "return-void" else "const/4 v0, 0x0\nreturn v0"
    target.addInstructionsWithLabels(0, """
        ${answer.ask}
        move-result v0
        if-eqz v0, :hush_stock
        $no
    """, ExternalLabel("hush_stock", target.getInstruction(0)))
}

/** The runtime the hold calls. It's the extension's own, so a change there refuses whatever this build holds. */
internal fun BytecodePatchContext.requireDataSaverRuntime() {
    requireStatusMethod("dataSaverDownloads")
    controlHook(DATA_SAVER, "hold", listOf(), "Z")
    controlHook(DATA_SAVER, "holdMask", listOf("Ljava/lang/Object;"), "Z")
    controlHook(DATA_SAVER, "checkAgain", listOf("Ljava/lang/Object;"), "V")
}

/** Gives the runtime's checkAgain stub Telegram's own download check, run on the controller it's handed. */
internal fun BytecodePatchContext.writeCheckAgain() = writeStub(DATA_SAVER, "checkAgain", 1, """
    check-cast p0, $DOWNLOAD_CONTROLLER
    invoke-virtual {p0}, $CHECK_SETTINGS
    return-void
""")

/** Why this build's answers can't be held. */
private class Unheld(val why: String) : Exception(why)

private fun need(ok: Boolean, why: String) {
    if (!ok) throw Unheld(why)
}

/**
 * DownloadController keeps its name in every build. Each answer is found by its signature and must
 * still read the network's download settings, directly or through canDownloadMediaInternal, or for
 * the queue, start its loads. Every other canDownload or canPreload answer would go around the hold,
 * so one this list doesn't know leaves the hold out. The download mask is held at its source, so the
 * classes that read it decide with no media types, and checkAutodownloadSettings, the check Telegram
 * runs when the network changes, cancels the queue's automatic loads when the hold starts and picks
 * them up again when it lifts. The runtime runs that check through checkAgain.
 */
internal fun BytecodePatchContext.resolveDataSaverDownloads(): DataSaverPlan = try {
    DataSaverPlan(answers(), null)
} catch (unheld: Unheld) {
    DataSaverPlan.missing(unheld.why)
}

private fun BytecodePatchContext.answers(): Map<AutoDownloadAnswer, MutableMethod> {
    val controller = mutableClassDefByOrNull(DOWNLOAD_CONTROLLER)
    need(controller != null, "DownloadController is missing")
    val internal = controller!!.methods.filter { it.name == INTERNAL && !AccessFlags.STATIC.isSet(it.accessFlags) }
    need(internal.isNotEmpty() && internal.all { reads(it, NETWORK_TYPE) }, "the message check no longer reads the network's download settings")
    val known = AutoDownloadAnswer.entries.map { it.signature }.toSet()
    val unknown = controller.methods.filter { (it.name.startsWith("canDownload") || it.name.startsWith("canPreload")) && it.name != INTERNAL }
        .map { it.signature() }.filter { it !in known }.sorted()
    need(unknown.isEmpty(), "DownloadController has answers the hold doesn't know (${unknown.joinToString()})")
    val check = controller.methods.filter { it.signature() == "checkAutodownloadSettings()V" }
    need(check.size == 1 && AccessFlags.PUBLIC.isSet(check[0].accessFlags) && !AccessFlags.STATIC.isSet(check[0].accessFlags),
        "DownloadController.checkAutodownloadSettings()V is missing or no longer public")
    need(reads(check.single(), CURRENT_MASK), "DownloadController.checkAutodownloadSettings()V no longer reads the download mask")

    return AutoDownloadAnswer.entries.associateWith { answer ->
        val found = controller.methods.filter { it.signature() == answer.signature && !AccessFlags.STATIC.isSet(it.accessFlags) }
        need(found.size == 1, "DownloadController.${answer.signature} is missing or ambiguous")
        val method = found.single()
        val body = method.controlBody()
        val ok = when (answer.check) {
            AutoDownloadCheck.NETWORK -> reads(method, NETWORK_TYPE)
            AutoDownloadCheck.MESSAGE -> body.any { it.opcode == Opcode.INVOKE_DIRECT && it.controlRef()?.let { ref ->
                ref.startsWith("$DOWNLOAD_CONTROLLER->$INTERNAL(") } == true }
            AutoDownloadCheck.LOADS -> body.any { it.controlRef()?.startsWith("$FILE_LOADER->loadFile(") == true }
        }
        need(ok, "DownloadController.${answer.signature} no longer ${answer.check.what}")
        need(method.localRegisterCount() >= 1, "DownloadController.${answer.signature} has no local to answer in")
        val flow = ControlFlow.of(method)
        need(flow.normal.none { 0 in it } && flow.exceptional.none { 0 in it },
            "something jumps back to the start of DownloadController.${answer.signature}")
        method
    }
}

/** Whether [method] calls [ref] itself. */
private fun reads(method: Method, ref: String) = method.controlBody().any { it.controlRef() == ref }

private fun Method.signature() = "$name(${parameterTypes.joinToString("")})$returnType"
