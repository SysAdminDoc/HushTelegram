/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import app.morphe.patches.telegram.misc.extension.patchLog
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlString
import app.morphe.util.call
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val APP_THEME_DARK = "appThemeDark"

/** The built-in theme names ThemeInfo.isDark answers for without reading a theme file. */
internal val BUILT_IN_THEME_NAMES = setOf("Dark Blue", "Night", "Blue", "Arctic Blue", "Day")

/** ThemeInfo.isDark: an instance check with no parameters that names every built-in day and night theme. */
internal fun isThemeInfoDark(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z" || method.parameterTypes.isNotEmpty() ||
        !method.definingClass.startsWith("Lorg/telegram/")
    ) return false
    return method.controlBody().mapNotNull { it.controlString() }.toSet().containsAll(BUILT_IN_THEME_NAMES)
}

/**
 * Theme.isCurrentThemeDark: static, no parameters, and nothing but the current theme's answer. It
 * reads the current theme from a static field, asks it [isDark], and returns that.
 * isCurrentThemeDay looks the same with a flip before the return, so it doesn't match.
 */
internal fun asksCurrentThemeDark(method: Method, isDark: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z" || method.parameterTypes.isNotEmpty()) return false
    val body = method.controlBody()
    if (body.map { it.opcode } != listOf(Opcode.SGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.RETURN)) return false
    val current = (body[0] as ReferenceInstruction).reference as? FieldReference ?: return false
    val asked = body[1].call() ?: return false
    return current.type == isDark.definingClass && asked.definingClass == isDark.definingClass &&
        asked.name == isDark.name && asked.returnType == "Z" && asked.parameterTypes.isEmpty()
}

/** Telegram's Theme.isCurrentThemeDark as a method reference the extension can call, or null. */
internal fun BytecodePatchContext.currentThemeDark(): String? {
    val infos = mutableListOf<Method>()
    val candidates = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { cls ->
        if (cls.type.startsWith("Lapp/hushtelegram/")) return@classDefForEach
        for (method in cls.methods) {
            if (isThemeInfoDark(method)) infos += method
            else if (method.controlBody().size == 4) candidates += cls to method
        }
    }
    val isDark = infos.singleOrNull() ?: return null
    val (owner, check) = candidates.filter { (_, method) -> asksCurrentThemeDark(method, isDark) }.singleOrNull() ?: return null
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(check.accessFlags)) return null
    return "${check.definingClass}->${check.name}()Z"
}

/**
 * HushTelegram's own dialogs (Message details, Forward to, the Ask before questions, sign-in help)
 * pick light or dark from the shared extension's dark mode check, which went by the phone's night
 * mode, so they came out light over Telegram's Night theme on a light phone. The check now asks
 * Telegram's theme first. Part of HushTelegram settings, which every selection carries, and it
 * doesn't stop patching when a build moves the theme check: the phone's night mode stays the answer.
 */
internal val appThemePatch = bytecodePatch {
    dependsOn(telegramExtensionPatch)

    execute {
        controlHook(EXTENSION_CLASS_DESCRIPTOR, APP_THEME_DARK, emptyList(), "I")
        val check = currentThemeDark()
        if (check == null) {
            patchLog.warning("Dialogs: Telegram's theme check wasn't found, so HushTelegram's dialogs follow the " +
                "phone's dark mode instead of Telegram's theme.")
            return@execute
        }
        writeStub(EXTENSION_CLASS_DESCRIPTOR, APP_THEME_DARK, 1, """
            invoke-static {}, $check
            move-result v0
            return v0
        """)
    }
}
