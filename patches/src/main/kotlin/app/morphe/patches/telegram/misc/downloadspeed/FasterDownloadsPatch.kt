/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.downloadspeed

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlShape
import app.morphe.patches.telegram.misc.localcontrols.controlSingle
import app.morphe.patches.telegram.misc.settings.settingsPatch
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val DOWNLOAD_SPEED = "$EXTENSION_PACKAGE/misc/DownloadSpeed;"
internal const val LOAD_OPERATION = "Lorg/telegram/messenger/FileLoadOperation;"
internal const val EXPERIMENTAL_PARAMS = "Lorg/telegram/messenger/MessagesController;->getfileExperimentalParams:Z"
internal const val FORCE_SMALL_CHUNK = "$LOAD_OPERATION->forceSmallChunk:Z"
internal const val CHUNK_SIZE = "$LOAD_OPERATION->downloadChunkSizeBig:I"
internal const val MAX_REQUESTS = "$LOAD_OPERATION->maxDownloadRequests:I"
internal const val MAX_REQUESTS_BIG = "$LOAD_OPERATION->maxDownloadRequestsBig:I"
internal const val BIG_PIECE = 0x80000L
internal const val SMALL_PIECE = 0x20000L

@Suppress("unused")
val fasterDownloadsPatch = bytecodePatch(
    name = "Faster downloads",
    description = "Downloads files in 512 KB pieces, 8 at a time, the way Telegram already does for accounts its servers " +
        "pick, instead of 128 KB pieces 4 at a time. If a big piece fails, Telegram still drops back to small ones. " +
        "Starts off. Turn it on in HushTelegram settings > Playback.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())
    execute {
        val plan = resolveFasterDownloads()
        // Assembled on a copy first, so a refusal leaves the app untouched.
        insertDownloadSpeedGate(MutableMethod(ImmutableMethod.of(plan.method)), plan)
        insertDownloadSpeedGate(plan.method, plan)
        enableStatus("fasterDownloads")
    }
}

/** Where updateParams reads the server's big-piece flag, and the register it reads into. */
internal class FasterDownloadsPlan(val method: MutableMethod, val read: Int, val register: Int)

/** The flag goes through the extension, and its answer is what Telegram picks the piece size with. */
internal fun insertDownloadSpeedGate(target: MutableMethod, plan: FasterDownloadsPlan) {
    target.addInstructionsAtControlFlowLabel(plan.read + 1, """
        invoke-static/range {v${plan.register} .. v${plan.register}}, $DOWNLOAD_SPEED->fast(Z)Z
        move-result v${plan.register}
    """)
}

/**
 * FileLoadOperation.updateParams reads MessagesController.getfileExperimentalParams, and an if-eqz
 * on it jumps to the 128 KB, 4-request tier. Otherwise it reads forceSmallChunk, whose if-nez
 * jumps to the same tier, and falls through to the 512 KB, 8-request one. The one read of the flag
 * is the only thing that changes, so forceSmallChunk keeps the last word.
 */
internal fun BytecodePatchContext.resolveFasterDownloads(): FasterDownloadsPlan {
    requireStatusMethod("fasterDownloads")
    controlHook(DOWNLOAD_SPEED, "fast", listOf("Z"), "Z")
    val owner = mutableClassDefByOrNull(LOAD_OPERATION)
    controlShape(owner != null, "Telegram's file downloader is missing")
    val method = owner!!.methods.filter { it.name == "updateParams" && it.parameterTypes.isEmpty() && it.returnType == "V" && it.implementation != null }
        .controlSingle("the download piece size choice")
    val body = method.controlBody()
    val read = body.indices.filter { body[it].opcode == Opcode.IGET_BOOLEAN && body[it].controlRef() == EXPERIMENTAL_PARAMS }
        .controlSingle("the server's big-piece flag")
    val register = (body[read] as TwoRegisterInstruction).registerA
    val small = body.indices.filter { body[it].piece(SMALL_PIECE) && body.getOrNull(it + 1)?.controlRef() == CHUNK_SIZE }
        .controlSingle("the small-piece tier")
    val flow = ControlFlow.of(method)
    val branch = body.getOrNull(read + 1)
    controlShape(branch?.opcode == Opcode.IF_EQZ && branch.namedRegisters() == listOf(register) &&
        flow.normal[read + 1].toSet() == setOf(read + 2, small), "the server's flag no longer picks the piece size")
    controlShape(flow.normal.indices.none { it != read && (read + 1) in flow.normal[it] },
        "something else jumps to the server's flag check")
    val fallback = body.getOrNull(read + 3)
    controlShape(body.getOrNull(read + 2)?.let { it.opcode == Opcode.IGET_BOOLEAN && it.controlRef() == FORCE_SMALL_CHUNK } == true &&
        fallback?.opcode == Opcode.IF_NEZ && flow.normal[read + 3].toSet() == setOf(read + 4, small),
        "Telegram's small-piece fallback no longer follows the server's flag")
    val big = body.subList(read + 4, small)
    controlShape(big.firstOrNull()?.piece(BIG_PIECE) == true && big.getOrNull(1)?.controlRef() == CHUNK_SIZE &&
        big.any { it.controlRef() == MAX_REQUESTS } && big.any { it.controlRef() == MAX_REQUESTS_BIG },
        "the big-piece tier no longer sets 512 KB pieces and its request counts")
    return FasterDownloadsPlan(method, read, register)
}

private fun Instruction.piece(size: Long) = (this as? WideLiteralInstruction)?.wideLiteral == size
