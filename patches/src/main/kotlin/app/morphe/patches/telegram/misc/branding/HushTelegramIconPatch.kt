/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.branding

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.maps.ANDROID_NAMESPACE
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PATCH = "HushTelegram icon and name"
internal const val ICON = "hush_launcher"
internal const val ICON_REFERENCE = "@mipmap/$ICON"

/** The ring's outer dark blue, behind the badge wherever a launcher's shape shows past it. */
internal const val ICON_BACKGROUND = "#FF00218A"
internal val ICON_DENSITIES = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

/** The badge for launchers older than Android 8, and an adaptive icon's two picture layers. */
internal val ICON_PICTURES = listOf(ICON, "${ICON}_foreground", "${ICON}_monochrome")
internal const val MAX_APP_NAME = 30
internal const val DEFAULT_ICON = "org.telegram.messenger.DefaultIcon"

/** Where scripts/gen-launcher-icon.py writes the pictures, inside the patch file. */
private const val BUNDLED = "/hushtelegram/icon"

/**
 * The adaptive icon, for Android 8 and up: the badge over a color that matches its edge, and the
 * H and plane alone for Android 13's themed icons, which older versions skip.
 */
internal val ADAPTIVE_ICON = """
    <?xml version="1.0" encoding="utf-8"?>
    <adaptive-icon xmlns:android="$ANDROID_NAMESPACE">
        <background>
            <color android:color="$ICON_BACKGROUND" />
        </background>
        <foreground android:drawable="@mipmap/${ICON}_foreground" />
        <monochrome android:drawable="@mipmap/${ICON}_monochrome" />
    </adaptive-icon>
""".trimIndent() + "\n"

@Suppress("unused")
val hushTelegramIconPatch = resourcePatch(
    name = PATCH,
    description = "Gives your patched Telegram HushTelegram's own home screen icon, with a themed version on Android 13 " +
        "and up, and can change the name under it. An alternate icon picked in Telegram's settings keeps its own " +
        "look. It isn't selected by default. Turn on Expert mode in Morphe Manager to pick it and name the app. " +
        "Only patching again without it puts Telegram's icon back.",
    default = false,
) {
    category("Interface")
    compatibleWith(*AppCompatibilities.telegram())
    val appName by stringOption(
        key = "appName", default = null, title = "App name",
        description = "The name under the icon, up to $MAX_APP_NAME characters. Leave it empty to keep Telegram.",
        required = false,
    )
    execute { applyHushTelegramIcon(appName) }
}

/** The name as it goes in the manifest, or null to keep Telegram's. */
internal fun checkedAppName(raw: String?): String? {
    val name = raw?.trim().orEmpty()
    if (name.isEmpty()) return null
    fun refuse(why: String): Nothing = throw PatchException("$PATCH: appName $why Nothing was changed.")
    if (name.length > MAX_APP_NAME) refuse("is longer than $MAX_APP_NAME characters.")
    // A leading @ or ? would be read as a resource reference, and aapt2 reads quotes and
    // backslashes in a manifest value as escapes.
    if (name[0] == '@' || name[0] == '?') refuse("can't start with @ or ?.")
    if (name.any { it.isISOControl() || it == '"' || it == '\'' || it == '\\' }) {
        refuse("can't hold quotes, backslashes or control characters.")
    }
    return name
}

/** The pictures the patch adds, by their path under res, read before anything is written. */
internal fun bundledIconPictures(): Map<String, ByteArray> = ICON_DENSITIES.flatMap { density ->
    ICON_PICTURES.map { picture ->
        val path = "mipmap-$density/$picture.png"
        val bytes = HushTelegramIconFiles::class.java.getResourceAsStream("$BUNDLED/$path")?.use { it.readBytes() }
            ?: throw PatchException("$PATCH: the patch file is missing its icon picture $path. Nothing was changed.")
        path to bytes
    }
}.toMap()

private object HushTelegramIconFiles

internal fun ResourcePatchContext.applyHushTelegramIcon(rawName: String?) {
    val name = checkedAppName(rawName)
    val pictures = bundledIconPictures()
    // Checked on a read-only copy first, so a refusal leaves the manifest's bytes alone.
    resolveLaunchers(readOnly(this["AndroidManifest.xml"]))
    addIconFiles(this["res"], pictures)
    document("AndroidManifest.xml").use { showIcon(it, name) }
}

/** Writes the pictures and the adaptive icon under [res], refusing before the first write when it can't. */
internal fun addIconFiles(res: File, pictures: Map<String, ByteArray>) {
    if (!res.isDirectory) throw PatchException("$PATCH: Telegram's resources weren't decoded. Nothing was changed.")
    val adaptive = res.resolve("mipmap-anydpi-v26/$ICON.xml")
    (pictures.keys.map { res.resolve(it) } + adaptive).firstOrNull { it.exists() }?.let {
        throw PatchException("$PATCH: this Telegram already has ${it.relativeTo(res).invariantSeparatorsPath}. Nothing was changed.")
    }
    for ((path, bytes) in pictures) res.resolve(path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    adaptive.apply { parentFile.mkdirs() }.writeText(ADAPTIVE_ICON)
}

/**
 * Points the application at the icon, and at [name] when there is one. The default launcher entry
 * shows the application's icon and name unless it names its own, and then it gets the new ones too.
 */
internal fun showIcon(manifest: Document, name: String?) {
    val (application, defaultIcon) = resolveLaunchers(manifest)
    application.setAttribute("android:icon", ICON_REFERENCE)
    application.setAttribute("android:roundIcon", ICON_REFERENCE)
    for (attribute in listOf("android:icon", "android:roundIcon")) {
        if (defaultIcon.hasAttribute(attribute)) defaultIcon.setAttribute(attribute, ICON_REFERENCE)
    }
    if (name != null) {
        application.setAttribute("android:label", name)
        if (defaultIcon.hasAttribute("android:label")) defaultIcon.setAttribute("android:label", name)
    }
}

/** Telegram's application element and its default launcher entry. */
internal fun resolveLaunchers(manifest: Document): Pair<Element, Element> {
    val root = manifest.documentElement
    requireShape(root.tagName == "manifest" && root.getAttribute("xmlns:android") == ANDROID_NAMESPACE,
        "the manifest's Android namespace changed")
    val applications = manifest.getElementsByTagName("application")
    requireShape(applications.length == 1 && applications.item(0).parentNode === root,
        "the manifest's application is missing or ambiguous")
    val application = applications.item(0) as Element
    requireShape(application.hasAttribute("android:icon"), "the application has no icon to replace")
    val aliases = manifest.getElementsByTagName("activity-alias")
    val defaults = (0 until aliases.length).map { aliases.item(it) as Element }
        .filter { it.getAttribute("android:name") == DEFAULT_ICON }
    requireShape(defaults.size == 1 && defaults.single().parentNode === application,
        "Telegram's default launcher entry is missing or ambiguous")
    return application to defaults.single()
}

private fun readOnly(file: File): Document = DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    isXIncludeAware = false
    isExpandEntityReferences = false
}.newDocumentBuilder().parse(file)

private fun requireShape(valid: Boolean, reason: String) {
    if (!valid) throw PatchException("$PATCH: $reason. Nothing was changed.")
}
