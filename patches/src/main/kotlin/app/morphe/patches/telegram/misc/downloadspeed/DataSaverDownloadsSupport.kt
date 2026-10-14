/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.downloadspeed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.localRegisterCount
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.localcontrols.Mention
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlShape
import app.morphe.patches.telegram.misc.localcontrols.controlSingle
import app.morphe.patches.telegram.misc.localcontrols.forEachClassMentioning
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val DATA_SAVER = "$EXTENSION_PACKAGE/misc/DataSaverDownloads;"
internal const val DOWNLOAD_CONTROLLER = "Lorg/telegram/messenger/DownloadController;"
internal const val CURRENT_MASK = "$DOWNLOAD_CONTROLLER->getCurrentDownloadMask()I"
private const val NETWORK_TYPE = "Lorg/telegram/messenger/ApplicationLoader;->getAutodownloadNetworkType()I"
private const val MESSAGE_OBJECT = "Lorg/telegram/messenger/MessageObject;"
private const val INTERNAL = "canDownloadMediaInternal"
private const val FILE_LOADER = "Lorg/telegram/messenger/FileLoader;"

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
 * the [check] that shows it's still that answer. A yes from the runtime answers no at the start of
 * each: nothing to download for a chat's media, a new message's media or a size, no next song or
 * story to load ahead, and nothing started from the download queue.
 */
internal enum class AutoDownloadAnswer(val signature: String, val check: AutoDownloadCheck) {
    MESSAGE_MEDIA("canDownloadMedia($MESSAGE_OBJECT)Z", AutoDownloadCheck.MESSAGE),
    MESSAGE_MEDIA_TYPE("canDownloadMediaType($MESSAGE_OBJECT)I", AutoDownloadCheck.MESSAGE),
    NEW_MESSAGE("canDownloadMedia(Lorg/telegram/tgnet/TLRPC\$Message;)I", AutoDownloadCheck.NETWORK),
    SIZED("canDownloadMedia(IJ)Z", AutoDownloadCheck.NETWORK),
    NEXT_TRACK("canDownloadNextTrack()Z", AutoDownloadCheck.NETWORK),
    STORIES("canPreloadStories()Z", AutoDownloadCheck.NETWORK),
    QUEUE("processDownloadObjects(ILjava/util/ArrayList;)V", AutoDownloadCheck.LOADS),
}

/** A read of the network's download mask outside DownloadController, whose result lands in [register] at [index]. */
internal class MaskRead(val method: MutableMethod, val index: Int, val register: Int)

/** Every answer's method and every outside mask read, found and checked before anything is edited. */
internal class DataSaverPlan(val answers: Map<AutoDownloadAnswer, MutableMethod>, val masks: List<MaskRead>) {
    /** Every edit, on the methods themselves or on what [into] makes of each. */
    fun apply(into: (MutableMethod) -> MutableMethod = { it }) {
        for ((answer, method) in answers) insertHold(into(method), answer.signature.last())
        // From the end of each method, so no read moves another's place.
        for ((method, reads) in masks.groupBy { it.method }) {
            val target = into(method)
            for (read in reads.sortedByDescending { it.index }) insertMask(target, read)
        }
    }

    /** The same edits on copies, so a smali or register problem refuses before the app is touched. */
    fun rehearse() = apply { MutableMethod(ImmutableMethod.of(it)) }
}

/** On a yes the answer is no: false or 0, or for the queue, nothing started. */
private fun insertHold(target: MutableMethod, result: Char) {
    val answer = if (result == 'V') "return-void" else "const/4 v0, 0x0\nreturn v0"
    target.addInstructionsWithLabels(0, """
        invoke-static {}, $DATA_SAVER->hold()Z
        move-result v0
        if-eqz v0, :hush_stock
        $answer
    """, ExternalLabel("hush_stock", target.getInstruction(0)))
}

/** Right after the mask lands, the runtime hands back no media types while the hold is on. */
private fun insertMask(target: MutableMethod, read: MaskRead) {
    target.addInstructions(read.index + 1, """
        invoke-static/range {v${read.register} .. v${read.register}}, $DATA_SAVER->mask(I)I
        move-result v${read.register}
    """)
}

/**
 * DownloadController keeps its name in every build. Each answer is found by its signature and must
 * still read the network's download settings, directly or through canDownloadMediaInternal, or for
 * the queue, start its loads. Outside it, the few classes that read getCurrentDownloadMask() decide
 * automatic downloads of their own with it; DownloadController's own reads, the check it runs when
 * the network changes among them, stay Telegram's, so its queue is cancelled or picked up as always.
 */
internal fun BytecodePatchContext.resolveDataSaverDownloads(): DataSaverPlan {
    requireStatusMethod("dataSaverDownloads")
    controlHook(DATA_SAVER, "hold", listOf(), "Z")
    controlHook(DATA_SAVER, "mask", listOf("I"), "I")
    val controller = mutableClassDefByOrNull(DOWNLOAD_CONTROLLER)
    controlShape(controller != null, "DownloadController is missing")
    val internal = controller!!.methods.filter { it.name == INTERNAL && !AccessFlags.STATIC.isSet(it.accessFlags) }
    controlShape(internal.isNotEmpty() && internal.all { reads(it, NETWORK_TYPE) }, "the message check no longer reads the network's download settings")

    val answers = AutoDownloadAnswer.entries.associateWith { answer ->
        val method = controller.methods.filter { it.signature() == answer.signature && !AccessFlags.STATIC.isSet(it.accessFlags) }
            .controlSingle("DownloadController.${answer.signature}")
        val body = method.controlBody()
        val ok = when (answer.check) {
            AutoDownloadCheck.NETWORK -> reads(method, NETWORK_TYPE)
            AutoDownloadCheck.MESSAGE -> body.any { it.opcode == Opcode.INVOKE_DIRECT && it.controlRef()?.let { ref ->
                ref.startsWith("$DOWNLOAD_CONTROLLER->$INTERNAL(") } == true }
            AutoDownloadCheck.LOADS -> body.any { it.controlRef()?.startsWith("$FILE_LOADER->loadFile(") == true }
        }
        controlShape(ok, "DownloadController.${answer.signature} no longer ${answer.check.what}")
        controlShape(method.localRegisterCount() >= 1, "DownloadController.${answer.signature} has no local to answer in")
        val flow = ControlFlow.of(method)
        controlShape(flow.normal.none { 0 in it } && flow.exceptional.none { 0 in it },
            "something jumps back to the start of DownloadController.${answer.signature}")
        method
    }

    val masks = mutableListOf<MaskRead>()
    forEachClassMentioning(Mention.member(DOWNLOAD_CONTROLLER, "getCurrentDownloadMask")) { cls ->
        if (cls.type == DOWNLOAD_CONTROLLER || cls.type.startsWith("Lapp/hushtelegram/")) return@forEachClassMentioning
        for (method in mutableClassDefBy(cls.type).methods) {
            if (method.implementation == null) continue
            val body = method.controlBody()
            val reads = body.indices.filter { body[it].controlRef() == CURRENT_MASK }
            if (reads.isEmpty()) continue
            val flow = ControlFlow.of(method)
            for (at in reads) {
                val landed = body.getOrNull(at + 1)
                controlShape(landed?.opcode == Opcode.MOVE_RESULT && flow.into(at + 2) == listOf(at + 1),
                    "${cls.type}->${method.name} no longer takes the download mask straight into a register")
                masks += MaskRead(method, at + 1, landed!!.namedRegisters().single())
            }
        }
    }
    controlShape(masks.isNotEmpty(), "nothing outside DownloadController reads the download mask")
    return DataSaverPlan(answers, masks)
}

/** Whether [method] calls [ref] itself. */
private fun reads(method: Method, ref: String) = method.controlBody().any { it.controlRef() == ref }

private fun Method.signature() = "$name(${parameterTypes.joinToString("")})$returnType"

/** Every instruction that can go on to [index], by a branch, a fall through or a handler. */
private fun ControlFlow.into(index: Int): List<Int> = instructions.indices.filter { index in normal[it] || index in exceptional[it] }
