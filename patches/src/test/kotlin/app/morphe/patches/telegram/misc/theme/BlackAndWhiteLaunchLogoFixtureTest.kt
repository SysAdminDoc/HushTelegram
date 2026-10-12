/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.theme

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.telegram.misc.maps.resourceXmlDeclarations
import app.morphe.patches.telegram.misc.maps.withAndroidResourceXmlFactory
import com.reandroid.apk.ApkModule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The optional launch logo patch on both builds' own logo files. Morphe decodes the resources
 * before a resource patch runs, which a unit test can't do, so the logo and its picture are read
 * out of each fixture into a folder laid out the way Morphe decodes; the CLI run on the real APKs
 * covers the decode and the rebuild around it.
 */
class BlackAndWhiteLaunchLogoFixtureTest {
    @Rule
    @JvmField
    val temporary = TemporaryFolder()

    @Test
    fun `the patch is optional and brings AMOLED black`() {
        assertFalse(blackAndWhiteLaunchLogoPatch.default)
        assertTrue(blackAndWhiteLaunchLogoPatch.dependencies.contains(amoledBlackPatch))
    }

    @Test
    fun `every declared build gets a white circle and a black plane on Android's DOM factory`() = withAndroidResourceXmlFactory {
        for (build in Fixtures.declaredBuilds()) {
            val res = stockRes(build)
            val stock = res.walkTopDown().filter { it.isFile }.associate { it.relativeTo(res).invariantSeparatorsPath to it.readBytes() }
            val splash = document(res.resolve(SPLASH))
            val pictureName = splash.documentElement.getAttribute("android:drawable").removePrefix("@drawable/")
            val picture = document(res.resolve("drawable/$pictureName.xml"))

            addLaunchLogo(res)

            for ((path, bytes) in stock) {
                if (path != LOGO_STYLES) assertArrayEquals("${build.name}: $path is untouched", bytes, res.resolve(path).readBytes())
            }
            val logo = document(res.resolve("drawable/$LOGO.xml"))
            assertEquals("@drawable/$LOGO_SHAPE", logo.documentElement.getAttribute("android:drawable"))
            logo.documentElement.setAttribute("android:drawable", "@drawable/$pictureName")
            assertEquals("${build.name}: the animation is Telegram's", state(splash.documentElement), state(logo.documentElement))

            val shape = document(res.resolve("drawable/$LOGO_SHAPE.xml"))
            assertEquals(LOGO_CIRCLE, path(shape, "circle").getAttribute("android:fillColor"))
            assertEquals(LOGO_PLANE, path(shape, "plane").getAttribute("android:fillColor"))
            assertTrue("${build.name}: Telegram's circle was blue", path(picture, "circle").getAttribute("android:fillColor").lowercase().endsWith("2aabee"))
            for (name in listOf("circle", "plane")) {
                path(shape, name).setAttribute("android:fillColor", "")
                path(picture, name).setAttribute("android:fillColor", "")
            }
            assertEquals("${build.name}: only the two colors differ", state(picture.documentElement), state(shape.documentElement))

            val styles = document(res.resolve(LOGO_STYLES)).documentElement
            val all = elements(styles, "style")
            assertEquals("Telegram's style stays first", "Theme.TMessages.Start", all.first().getAttribute("name"))
            val added = all.single { it.getAttribute("name") == LOGO_THEME }
            assertEquals("@android:style/Theme.Black.NoTitleBar", added.getAttribute("parent"))
            assertEquals(
                listOf("android:windowSplashScreenBackground" to LOGO_BACKGROUND, "android:windowSplashScreenAnimatedIcon" to "@drawable/$LOGO"),
                elements(added, "item").map { it.getAttribute("name") to it.textContent },
            )
            assertEquals(2, all.size)
        }
    }

    @Test
    fun `a declaration in any input refuses before any resource changes`() = withAndroidResourceXmlFactory {
        val build = Fixtures.declaredBuilds().first()
        for (input in listOf("splash", "picture", "styles")) for (declaration in resourceXmlDeclarations("resources")) {
            val res = stockRes(build)
            val file = when (input) {
                "splash" -> res.resolve(SPLASH)
                "picture" -> pictureOf(res)
                else -> res.resolve(LOGO_STYLES)
            }
            rewrite(file) { it.replaceFirst(Regex("(<\\?xml[^?]*\\?>)?"), "$1$declaration") }
            refuses(res, "resource XML")
        }
    }

    @Test
    fun `undecoded or changed resources refuse before anything is written`() {
        val build = Fixtures.declaredBuilds().first()
        refuses(File(temporary.root, "not-decoded"), "weren't decoded")
        refuses(stockRes(build).apply { resolve(SPLASH).delete() }, "launch logo $SPLASH is missing")
        refuses(stockRes(build).apply { rewrite(resolve(SPLASH)) { it.replace("animated-vector", "vector") } }, "no longer an animated vector")
        refuses(stockRes(build).apply { rewrite(resolve(SPLASH)) { it.replace(Regex("android:drawable=\"[^\"]+\""), "android:drawable=\"@android:drawable/x\"") } },
            "doesn't name its picture")
        refuses(stockRes(build).apply { pictureOf(this).delete() }, "picture drawable/")
        refuses(stockRes(build).apply { val file = pictureOf(this); rewrite(file) { it.replace("\"plane\"", "\"glider\"") } }, "no single plane")
        refuses(stockRes(build).apply { resolve(LOGO_STYLES).delete() }, "$LOGO_STYLES is missing")
        refuses(stockRes(build).apply { resolve("drawable/$LOGO_SHAPE.xml").writeText("<vector />") }, "already has drawable/$LOGO_SHAPE.xml")
        refuses(stockRes(build).apply { rewrite(resolve(LOGO_STYLES)) { it.replace("</resources>", "<style name=\"$LOGO_THEME\" /></resources>") } },
            "already has the style $LOGO_THEME")
    }

    private fun refuses(res: File, reason: String) {
        val before = if (res.exists()) res.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes() } else emptyMap()
        try {
            addLaunchLogo(res)
            fail("accepted")
        } catch (expected: PatchException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(reason))
            assertTrue(expected.message.orEmpty().contains("Nothing was changed"))
        }
        val after = if (res.exists()) res.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes() } else emptyMap()
        assertEquals("$reason: no file added or removed", before.keys, after.keys)
        for ((path, bytes) in before) assertArrayEquals("$reason: $path", bytes, after.getValue(path))
    }

    /** The build's launch logo and its four parts as Morphe lays them out, and a v31 styles file with Telegram's start theme. */
    private fun stockRes(build: File): File {
        val res = temporary.newFolder()
        ApkModule.loadApkFile(build).use { module ->
            module.tableBlock // Attach the resource package the decoder names references with.
            val files = module.listResFiles().filter { it.pickOne().typeName == "drawable" && it.pickOne().name.contains("tg_splash_320") }
            assertEquals("${build.name}: the logo and its four parts", 5, files.size)
            for (file in files) {
                val xml = file.readAsXmlDocument().apply { setPackageBlock(file.packageBlock) }.serializeToXml()
                res.resolve("drawable/${file.pickOne().name}.xml").apply { parentFile.mkdirs() }.writeText(xml)
            }
        }
        res.resolve(LOGO_STYLES).apply { parentFile.mkdirs() }.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                "  <style name=\"Theme.TMessages.Start\" parent=\"@style/Theme.AppCompat.Light\">\n" +
                "    <item name=\"android:windowSplashScreenAnimatedIcon\">@drawable/tg_splash_320</item>\n" +
                "  </style>\n</resources>\n")
        return res
    }

    private fun pictureOf(res: File): File =
        res.resolve("drawable/" + document(res.resolve(SPLASH)).documentElement.getAttribute("android:drawable").removePrefix("@drawable/") + ".xml")

    private fun rewrite(file: File, change: (String) -> String) = file.writeText(change(file.readText()))

    private fun path(document: org.w3c.dom.Document, name: String): Element =
        elements(document.documentElement, "path").single { it.getAttribute("android:name") == name }

    private fun elements(root: Element, tag: String): List<Element> =
        root.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun document(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun state(node: Node): List<Any?> {
        val attributes = (0 until (node.attributes?.length ?: 0)).map { node.attributes.item(it) }
            .map { it.nodeName to it.nodeValue }.sortedBy { it.first }
        val children = (0 until node.childNodes.length).map { node.childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }
        return listOf(node.nodeName, attributes, children.map { state(it) })
    }
}
