/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.savedownloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlShape
import app.morphe.patches.telegram.misc.localcontrols.controlSingle
import app.morphe.patches.telegram.misc.localcontrols.requireReachable
import app.morphe.patches.telegram.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val SAVE_DOWNLOADS = "$EXTENSION_PACKAGE/misc/SaveDownloads;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val FILE = "Ljava/io/File;"
internal const val FILE_LOADER = "Lorg/telegram/messenger/FileLoader;"
internal const val LOADER_DELEGATE = "Lorg/telegram/messenger/FileLoader\$FileLoaderDelegate;"
internal const val SET_DELEGATE = "$FILE_LOADER->setDelegate($LOADER_DELEGATE)V"
internal const val FILE_LOADED = "fileDidLoaded($STRING$FILE${OBJECT}I)V"
internal const val IMAGE_LOADER = "Lorg/telegram/messenger/ImageLoader;"
internal const val MESSAGE_OBJECT = "Lorg/telegram/messenger/MessageObject;"
internal const val DOCUMENT = "Lorg/telegram/tgnet/TLRPC\$Document;"
internal const val TL_MESSAGE = "Lorg/telegram/tgnet/TLRPC\$Message;"
internal const val DIALOG_OBJECT = "Lorg/telegram/messenger/DialogObject;"
internal const val MESSAGES_CONTROLLER = "Lorg/telegram/messenger/MessagesController;"
internal const val GET_DOCUMENT = "$MESSAGE_OBJECT->getDocument()$DOCUMENT"
internal const val IS_SECRET_MEDIA = "$MESSAGE_OBJECT->isSecretMedia()Z"
internal const val IS_MUSIC = "$MESSAGE_OBJECT->isMusic()Z"
internal const val IS_DOCUMENT = "$MESSAGE_OBJECT->isDocument()Z"
internal const val IS_GIF = "$MESSAGE_OBJECT->isGif()Z"
internal const val IS_ROUND_VIDEO = "$MESSAGE_OBJECT->isRoundVideo()Z"
internal const val IS_VOICE = "$MESSAGE_OBJECT->isVoice()Z"
internal const val MESSAGE_OWNER = "$MESSAGE_OBJECT->messageOwner:$TL_MESSAGE"
internal const val NO_FORWARDS = "$TL_MESSAGE->noforwards:Z"
internal const val GET_DIALOG_ID = "$MESSAGE_OBJECT->getDialogId()J"
internal const val CURRENT_ACCOUNT = "$MESSAGE_OBJECT->currentAccount:I"
internal const val IS_ENCRYPTED = "$DIALOG_OBJECT->isEncryptedDialog(J)Z"
internal const val CONTROLLER_INSTANCE = "$MESSAGES_CONTROLLER->getInstance(I)$MESSAGES_CONTROLLER"
internal const val PEER_NO_FORWARDS = "$MESSAGES_CONTROLLER->isPeerNoForwards(J)Z"
internal const val ATTACH_NAME = "$FILE_LOADER->getAttachFileName(Lorg/telegram/tgnet/TLObject;)$STRING"
internal const val DOCUMENT_NAME = "$FILE_LOADER->getDocumentFileName($DOCUMENT)$STRING"

@Suppress("unused")
val saveDownloadsPatch = bytecodePatch(
    name = "Save downloaded files",
    description = "Copies each file and song you download to Download/Telegram on your phone, where file managers and " +
        "other apps can open it, the way Save to Downloads does one at a time. Files from secret chats and from chats " +
        "that don't allow saving stay in Telegram. Starts off. Turn it on in HushTelegram settings > Playback.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())
    execute {
        val callback = resolveSaveDownloads()
        // Assembled on a copy first, so a refusal leaves the app untouched.
        insertSaveDownloadsHook(MutableMethod(ImmutableMethod.of(callback)))
        insertSaveDownloadsHook(callback)
        writeSaveDownloadsStubs()
        enableStatus("saveDownloads")
    }
}

/** Ahead of anything in the callback, the extension sees the downloader's name for the file, the file and its parent. */
internal fun insertSaveDownloadsHook(method: MutableMethod) {
    method.addInstructions(0, "invoke-static/range {p1 .. p3}, $SAVE_DOWNLOADS->fileLoaded($STRING$FILE$OBJECT)V")
}

private fun BytecodePatchContext.writeSaveDownloadsStubs() {
    writeStub(SAVE_DOWNLOADS, "messageDocument", 2, """
        instance-of v0, p0, $MESSAGE_OBJECT
        if-eqz v0, :hush_none
        check-cast p0, $MESSAGE_OBJECT
        invoke-virtual {p0}, $GET_DOCUMENT
        move-result-object v0
        return-object v0
        :hush_none
        const/4 v0, 0x0
        return-object v0
    """)
    // MessageObject's own isDocument already leaves out stickers, videos, songs and voice messages.
    writeStub(SAVE_DOWNLOADS, "fileOrSong", 2, """
        check-cast p0, $MESSAGE_OBJECT
        invoke-virtual {p0}, $IS_SECRET_MEDIA
        move-result v0
        if-nez v0, :hush_no
        invoke-virtual {p0}, $IS_MUSIC
        move-result v0
        if-nez v0, :hush_yes
        invoke-virtual {p0}, $IS_DOCUMENT
        move-result v0
        if-eqz v0, :hush_no
        invoke-virtual {p0}, $IS_GIF
        move-result v0
        if-nez v0, :hush_no
        invoke-virtual {p0}, $IS_ROUND_VIDEO
        move-result v0
        if-nez v0, :hush_no
        invoke-virtual {p0}, $IS_VOICE
        move-result v0
        if-nez v0, :hush_no
        :hush_yes
        const/4 v0, 0x1
        return v0
        :hush_no
        const/4 v0, 0x0
        return v0
    """)
    // FileLoader.canSaveToPublicStorage's rules: the message, a secret chat, then the chat's own setting.
    writeStub(SAVE_DOWNLOADS, "savingForbidden", 4, """
        check-cast p0, $MESSAGE_OBJECT
        iget-object v0, p0, $MESSAGE_OWNER
        if-eqz v0, :hush_forbidden
        iget-boolean v0, v0, $NO_FORWARDS
        if-nez v0, :hush_forbidden
        invoke-virtual {p0}, $GET_DIALOG_ID
        move-result-wide v1
        invoke-static {v1, v2}, $IS_ENCRYPTED
        move-result v0
        if-nez v0, :hush_forbidden
        iget v0, p0, $CURRENT_ACCOUNT
        invoke-static {v0}, $CONTROLLER_INSTANCE
        move-result-object v0
        invoke-virtual {v0, v1, v2}, $PEER_NO_FORWARDS
        move-result v0
        return v0
        :hush_forbidden
        const/4 v0, 0x1
        return v0
    """)
    writeStub(SAVE_DOWNLOADS, "attachName", 1, """
        check-cast p0, $DOCUMENT
        invoke-static {p0}, $ATTACH_NAME
        move-result-object p0
        return-object p0
    """)
    writeStub(SAVE_DOWNLOADS, "documentName", 1, """
        check-cast p0, $DOCUMENT
        invoke-static {p0}, $DOCUMENT_NAME
        move-result-object p0
        return-object p0
    """)
}

/** The types ImageLoader creates in the method that hands FileLoader its delegate. */
internal fun ClassDef.delegateCandidates(): List<String> =
    methods.filter { method -> method.controlBody().any { it.controlRef() == SET_DELEGATE } }
        .flatMap { method -> method.controlBody().filter { it.opcode == Opcode.NEW_INSTANCE }.mapNotNull { it.controlRef() } }
        .distinct()

/**
 * ImageLoader gives each account's FileLoader a delegate, and FileLoader calls its fileDidLoaded
 * with the downloader's name for the file, the finished file, what it was downloaded for and its
 * type, on FileLoader's own queue, once a download is done and before Telegram's UI hears of it.
 * The stubs call Telegram's own checks from the extension's package, so each one has to be public.
 */
internal fun BytecodePatchContext.resolveSaveDownloads(): MutableMethod {
    requireStatusMethod("saveDownloads")
    controlHook(SAVE_DOWNLOADS, "fileLoaded", listOf(STRING, FILE, OBJECT), "V")
    controlHook(SAVE_DOWNLOADS, "messageDocument", listOf(OBJECT), OBJECT)
    controlHook(SAVE_DOWNLOADS, "fileOrSong", listOf(OBJECT), "Z")
    controlHook(SAVE_DOWNLOADS, "savingForbidden", listOf(OBJECT), "Z")
    controlHook(SAVE_DOWNLOADS, "attachName", listOf(OBJECT), STRING)
    controlHook(SAVE_DOWNLOADS, "documentName", listOf(OBJECT), STRING)

    val images = mutableClassDefByOrNull(IMAGE_LOADER)
    controlShape(images != null, "ImageLoader is missing")
    val delegate = images!!.delegateCandidates()
        .mapNotNull { mutableClassDefByOrNull(it) }
        .filter { LOADER_DELEGATE in it.interfaces }
        .controlSingle("ImageLoader's download callback")
    val callback = delegate.methods.filter { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == FILE_LOADED }
        .controlSingle("the finished-download callback")
    controlShape(!AccessFlags.STATIC.isSet(callback.accessFlags) && callback.controlBody().isNotEmpty(),
        "the finished-download callback has no body of its own")

    // Telegram's own Save to Downloads rules read the same three facts the stubs do.
    val loader = mutableClassDefByOrNull(FILE_LOADER)
    controlShape(loader != null && loader.methods.any { method ->
        val refs = method.controlBody().mapNotNull { it.controlRef() }
        PEER_NO_FORWARDS in refs && IS_ENCRYPTED in refs && NO_FORWARDS in refs
    }, "Telegram's rules for saving a file outside the app changed")

    for ((reference, static) in listOf(GET_DOCUMENT to false, IS_SECRET_MEDIA to false, IS_MUSIC to false,
        IS_DOCUMENT to false, IS_GIF to false, IS_ROUND_VIDEO to false, IS_VOICE to false, MESSAGE_OWNER to false,
        NO_FORWARDS to false, GET_DIALOG_ID to false, CURRENT_ACCOUNT to false, IS_ENCRYPTED to true,
        CONTROLLER_INSTANCE to true, PEER_NO_FORWARDS to false, ATTACH_NAME to true, DOCUMENT_NAME to true)) {
        requireReachable(reference, static)
    }
    return callback
}
