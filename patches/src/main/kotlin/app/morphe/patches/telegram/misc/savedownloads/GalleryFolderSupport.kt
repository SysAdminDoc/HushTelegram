/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.savedownloads

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlString
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val GALLERY_FOLDER = "$EXTENSION_PACKAGE/misc/GalleryFolder;"
internal const val GALLERY_MEDIA_CONTROLLER = "Lorg/telegram/messenger/MediaController;"
internal const val GALLERY_VALUES = "Landroid/content/ContentValues;"
internal const val GALLERY_VALUES_PUT = "$GALLERY_VALUES->put(Ljava/lang/String;Ljava/lang/String;)V"
internal const val GALLERY_PUT = "$GALLERY_FOLDER->put(${GALLERY_VALUES}Ljava/lang/String;Ljava/lang/String;)V"
internal const val GALLERY_PICTURES = "Landroid/os/Environment;->DIRECTORY_PICTURES:Ljava/lang/String;"
internal const val GALLERY_RELATIVE_PATH = "relative_path"

/** Each gallery save and the ContentValues string puts in it, or [missing], why this build keeps Telegram's folders. */
internal class GalleryFolderPlan(val saves: List<Pair<MutableMethod, List<Int>>>, val missing: String?) {
    /** Every put goes through the extension, on the methods themselves or on what [into] makes of each. */
    fun apply(into: (MutableMethod) -> MutableMethod = { it }) {
        for ((method, puts) in saves) {
            val target = into(method)
            for (at in puts) {
                val registers = target.getInstruction(at).namedRegisters().joinToString { "v$it" }
                target.replaceInstruction(at, "invoke-static {$registers}, $GALLERY_PUT")
            }
        }
    }

    /** The same swaps on copies, so a smali problem refuses before the app is touched. */
    fun rehearse() = apply { MutableMethod(ImmutableMethod.of(it)) }

    companion object {
        fun missing(why: String) = GalleryFolderPlan(emptyList(), why)
    }
}

/** The runtime the swaps call. It's the extension's own, so a change there refuses the whole patch. */
internal fun BytecodePatchContext.requireGalleryFolderRuntime() {
    requireStatusMethod("galleryFolder")
    controlHook(GALLERY_FOLDER, "put", listOf(GALLERY_VALUES, "Ljava/lang/String;", "Ljava/lang/String;"), "V")
}

/**
 * On Android 10 and later MediaController writes a photo or video Save to Gallery makes through
 * MediaStore, with its folder in the ContentValues' relative_path: Pictures/Telegram/ or
 * Movies/Telegram/. saveFileInternal is the ordinary save and the other method with relative_path
 * and DIRECTORY_PICTURES is the live photo's. Every ContentValues string put in both goes through
 * the extension, which changes only that folder, so the collection, the name and the type stay
 * Telegram's. Anything else about them, or a third such method, keeps Telegram's folders.
 */
internal fun BytecodePatchContext.resolveGalleryFolder(): GalleryFolderPlan {
    val owner = mutableClassDefByOrNull(GALLERY_MEDIA_CONTROLLER) ?: return GalleryFolderPlan.missing("MediaController is missing")
    val saves = owner.methods.filter { method ->
        val body = method.controlBody()
        body.any { it.controlString() == GALLERY_RELATIVE_PATH } &&
            body.any { it.opcode == Opcode.SGET_OBJECT && it.controlRef() == GALLERY_PICTURES }
    }
    if (saves.size != 2 || saves.count { it.name == "saveFileInternal" } != 1) {
        return GalleryFolderPlan.missing("Telegram's gallery saves changed (${saves.map { it.name }.sorted().joinToString()})")
    }
    val plan = saves.map { method ->
        val body = method.controlBody()
        val puts = body.indices.filter { body[it].controlRef() == GALLERY_VALUES_PUT }
        if (puts.isEmpty() || puts.any { body[it].opcode != Opcode.INVOKE_VIRTUAL || body[it].namedRegisters().size != 3 }) {
            return GalleryFolderPlan.missing("MediaController.${method.name} no longer puts its folder the way the swap expects")
        }
        method to puts
    }
    return GalleryFolderPlan(plan, null)
}
