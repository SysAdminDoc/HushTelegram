/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.api

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.patchLog
import app.morphe.patches.telegram.misc.extension.requireLocals
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val SIGN_IN_HELP = "$EXTENSION_PACKAGE/misc/SignInHelp;"
private const val OBJECT = "Ljava/lang/Object;"
internal const val SIGN_IN_EXPLAIN = "$SIGN_IN_HELP->explain($OBJECT$OBJECT$OBJECT)Z"
internal const val TL_ERROR = "Lorg/telegram/tgnet/TLRPC\$TL_error;"
internal const val TL_OBJECT = "Lorg/telegram/tgnet/TLObject;"

/** The error texts that only Telegram's general error handler checks for, which keep it apart from any other method. */
internal val PROCESS_ERROR_ANCHORS = setOf("BALANCE_TOO_LOW", "CHANNELS_TOO_MUCH", "PEER_FLOOD")

/**
 * Whether [method] is Telegram's general error handler (AlertsCreator.processError): static, taking
 * the account, the error, the screen, the request and a vararg array, in a renamed UI class.
 */
internal fun isProcessError(method: Method): Boolean {
    val types = method.parameterTypes.map { it.toString() }
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || types.size != 5 || types[0] != "I" || types[1] != TL_ERROR ||
        types[3] != TL_OBJECT || types[4] != "[$OBJECT" || !method.definingClass.startsWith("Lorg/telegram/ui/")
    ) return false
    val strings = method.implementation?.instructions
        ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet().orEmpty()
    return strings.containsAll(PROCESS_ERROR_ANCHORS)
}

/** No access flags here: the handler is varargs too, and a flag list has to match exactly. */
internal object ProcessErrorFingerprint : Fingerprint(
    custom = { method, _ -> isProcessError(method) },
)

/** Asks the extension first, with the error, screen and request; on true the handler returns null at once. */
internal fun MutableMethod.askSignInHelpFirst() {
    requireLocals("Sign-in help", 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p1 .. p3 }, $SIGN_IN_EXPLAIN
            move-result v0
            if-eqz v0, :hush_stock
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("hush_stock", getInstruction(0)),
    )
}

/**
 * Says why sign-in stopped when Telegram refuses the app's API ID. On Telegram 13 the phone screen
 * names a few sendCode errors and hands the rest to the general error handler, which has no case for
 * sendCode, so API_ID_PUBLISHED_FLOOD left the screen sitting there with nothing said. The handler
 * now asks the extension first; it answers only for API_ID_PUBLISHED_FLOOD and API_ID_INVALID on
 * sendCode and leaves every other error to Telegram. Part of HushTelegram settings, which every
 * selection carries, and it doesn't stop patching when a build moves the handler.
 */
internal val signInHelpPatch = bytecodePatch {
    dependsOn(telegramExtensionPatch)

    execute {
        controlHook(SIGN_IN_HELP, "explain", listOf(OBJECT, OBJECT, OBJECT), "Z")
        val handler = ProcessErrorFingerprint.methodOrNull
        if (handler == null || handler.returnType.startsWith("[") || !handler.returnType.startsWith("L")) {
            patchLog.warning("Sign-in help: Telegram's general error handler wasn't found, so a refused API ID " +
                "still shows no message.")
            return@execute
        }
        handler.askSignInHelpFirst()
    }
}
