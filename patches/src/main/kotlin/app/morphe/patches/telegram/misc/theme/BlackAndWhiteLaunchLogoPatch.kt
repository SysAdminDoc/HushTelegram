/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.theme

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private const val PATCH = "Black and white launch logo"

/** The launch theme the patch adds, which BlackTheme looks up by this name. */
internal const val LOGO_THEME = "HushTelegramLaunchBlack"
internal const val LOGO = "hush_launch_logo"
internal const val LOGO_SHAPE = "hush_launch_logo_shape"

/** Telegram's animated launch logo: a blue circle that grows in with a white paper plane on it. */
internal const val SPLASH = "drawable/tg_splash_320.xml"
internal const val LOGO_STYLES = "values-v31/styles.xml"
internal const val LOGO_CIRCLE = "#ffffff"
internal const val LOGO_PLANE = "#000000"
internal const val LOGO_BACKGROUND = "#ff000000"

@Suppress("unused")
val blackAndWhiteLaunchLogoPatch = resourcePatch(
    name = PATCH,
    description = "With AMOLED black on and your phone in dark mode, the launch screen shows Telegram's logo in white " +
        "on black instead of the app icon, on Android 13 and up. It isn't selected by default, since it has Morphe " +
        "rebuild Telegram's resources. Turn on Expert mode in Morphe Manager to pick it.",
    default = false,
) {
    category("Theme")
    dependsOn(amoledBlackPatch)
    compatibleWith(*AppCompatibilities.telegram())
    execute { addLaunchLogo(this["res"]) }
}

/**
 * Adds a copy of Telegram's launch logo with a white circle and a black plane, keeping Telegram's
 * animation, and a launch theme that shows it on black. Everything is checked before the first write.
 */
internal fun addLaunchLogo(res: File) {
    if (!res.isDirectory) refuse("Telegram's resources weren't decoded")
    val splashFile = res.resolve(SPLASH)
    if (!splashFile.isFile) refuse("Telegram's launch logo $SPLASH is missing")
    val splash = parse(splashFile)
    val animated = splash.documentElement
    requireShape(animated.tagName == "animated-vector", "Telegram's launch logo is no longer an animated vector")
    val reference = animated.getAttribute("android:drawable")
    val shapeName = reference.removePrefix("@drawable/")
    requireShape(reference.startsWith("@drawable/") && shapeName.isNotEmpty() && '/' !in shapeName && ':' !in shapeName,
        "Telegram's launch logo doesn't name its picture")
    val shapeFile = res.resolve("drawable/$shapeName.xml")
    if (!shapeFile.isFile) refuse("Telegram's launch logo picture drawable/$shapeName.xml is missing")
    val shape = parse(shapeFile)
    requireShape(shape.documentElement.tagName == "vector", "Telegram's launch logo picture is no longer a vector")
    val circle = namedPath(shape, "circle")
    val plane = namedPath(shape, "plane")
    val stylesFile = res.resolve(LOGO_STYLES)
    if (!stylesFile.isFile) refuse("Telegram's $LOGO_STYLES is missing")
    val styles = parse(stylesFile)
    requireShape(styles.documentElement.tagName == "resources", "Telegram's $LOGO_STYLES has another shape")
    listOf(LOGO, LOGO_SHAPE).map { res.resolve("drawable/$it.xml") }.firstOrNull { it.exists() }?.let {
        refuse("this Telegram already has ${it.relativeTo(res).invariantSeparatorsPath}")
    }
    val named = styles.getElementsByTagName("style")
    if ((0 until named.length).any { (named.item(it) as Element).getAttribute("name") == LOGO_THEME }) {
        refuse("this Telegram already has the style $LOGO_THEME")
    }

    circle.setAttribute("android:fillColor", LOGO_CIRCLE)
    plane.setAttribute("android:fillColor", LOGO_PLANE)
    write(shape, res.resolve("drawable/$LOGO_SHAPE.xml"))
    animated.setAttribute("android:drawable", "@drawable/$LOGO_SHAPE")
    write(splash, res.resolve("drawable/$LOGO.xml"))
    styles.documentElement.appendChild(styles.createElement("style").apply {
        setAttribute("name", LOGO_THEME)
        setAttribute("parent", "@android:style/Theme.Black.NoTitleBar")
        appendChild(item(styles, "android:windowSplashScreenBackground", LOGO_BACKGROUND))
        appendChild(item(styles, "android:windowSplashScreenAnimatedIcon", "@drawable/$LOGO"))
    })
    write(styles, stylesFile)
}

/** The one path called [name] in the logo, which has to carry a fill color to change. */
private fun namedPath(shape: Document, name: String): Element {
    val paths = shape.getElementsByTagName("path")
    val found = (0 until paths.length).map { paths.item(it) as Element }.filter { it.getAttribute("android:name") == name }
    requireShape(found.size == 1 && found.single().hasAttribute("android:fillColor"),
        "Telegram's launch logo has no single $name to recolor")
    return found.single()
}

private fun item(document: Document, name: String, value: String): Element =
    document.createElement("item").apply {
        setAttribute("name", name)
        textContent = value
    }

private fun parse(file: File): Document = DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    isXIncludeAware = false
    isExpandEntityReferences = false
}.newDocumentBuilder().parse(file)

private fun write(document: Document, file: File) = file.outputStream().use { out ->
    TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.ENCODING, "utf-8")
    }.transform(DOMSource(document), StreamResult(out))
}

private fun requireShape(valid: Boolean, reason: String) {
    if (!valid) refuse(reason)
}

private fun refuse(reason: String): Nothing = throw PatchException("$PATCH: $reason. Nothing was changed.")
