/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.branding

import app.morphe.Fixtures
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patches.telegram.misc.maps.ANDROID_NAMESPACE
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The optional icon patch on both builds' manifests, holding everything it doesn't change to the
 * original. Morphe decodes the resources before a resource patch runs, which a unit test can't do
 * on a small APK, so the files and the manifest are checked through the two steps the patch takes
 * once its checks pass; the CLI run on the real APKs covers the decode and rebuild around them.
 */
class HushTelegramIconFixtureTest {
    @Rule
    @JvmField
    val temporary = TemporaryFolder()

    @Test
    fun `the icon patch and its name are optional`() {
        assertFalse(hushTelegramIconPatch.default)
        assertEquals(setOf("appName"), hushTelegramIconPatch.options.keys)
        assertFalse(hushTelegramIconPatch.options["appName"].required)
        assertNull(hushTelegramIconPatch.options["appName"].default)
    }

    @Test
    fun `every declared build gets the icon and the name and nothing else changes`() {
        for (build in Fixtures.declaredBuilds()) {
            val original = document(fixtureManifest(build))
            val (stockApplication, stockDefault) = resolveLaunchers(original)
            assertFalse("${build.name}: Telegram's default entry shows the application's icon", stockDefault.hasAttribute("android:icon"))
            val stockLabel = stockApplication.getAttribute("android:label")
            val result = document(fixtureManifest(build))
            showIcon(result, checkedAppName("  Hush  "))

            val (application, defaultIcon) = resolveLaunchers(result)
            assertEquals(ICON_REFERENCE, application.getAttribute("android:icon"))
            assertEquals(ICON_REFERENCE, application.getAttribute("android:roundIcon"))
            assertEquals("${build.name}: trimmed", "Hush", application.getAttribute("android:label"))
            assertFalse(defaultIcon.hasAttribute("android:icon"))
            // With the three changed values set back, the two manifests are the same.
            for (element in listOf(application, stockApplication)) {
                element.setAttribute("android:icon", "")
                element.setAttribute("android:roundIcon", "")
            }
            application.setAttribute("android:label", stockLabel)
            assertEquals("${build.name}: every other attribute and element survives", state(original.documentElement), state(result.documentElement))
        }
    }

    @Test
    fun `no name keeps Telegram's`() {
        for (name in listOf(null, "", "   ")) {
            val manifest = document(manifest())
            showIcon(manifest, checkedAppName(name))
            val (application, _) = resolveLaunchers(manifest)
            assertEquals("Telegram", application.getAttribute("android:label"))
            assertEquals(ICON_REFERENCE, application.getAttribute("android:icon"))
        }
    }

    @Test
    fun `a default entry with its own icon and name gets the new ones`() {
        val manifest = document(manifest(alias = "android:icon=\"@mipmap/ic_launcher\" android:roundIcon=\"@mipmap/ic_launcher_round\" " +
            "android:label=\"@string/AppName\""))
        showIcon(manifest, checkedAppName("Hush & Co"))
        val (application, defaultIcon) = resolveLaunchers(manifest)
        for (element in listOf(application, defaultIcon)) {
            assertEquals(ICON_REFERENCE, element.getAttribute("android:icon"))
            assertEquals(ICON_REFERENCE, element.getAttribute("android:roundIcon"))
            assertEquals("Hush & Co", element.getAttribute("android:label"))
        }
        val alternate = manifest.getElementsByTagName("activity-alias").item(1) as Element
        assertEquals("an alternate icon keeps its own", "@mipmap/icon_2_launcher", alternate.getAttribute("android:icon"))
    }

    @Test
    fun `the pictures and the adaptive icon go in as mipmaps`() {
        val res = temporary.newFolder("res")
        res.resolve("mipmap-xxhdpi/ic_launcher.png").apply { parentFile.mkdirs() }.writeText("stock")
        addIconFiles(res, bundledIconPictures())
        assertEquals("Telegram's own icon stays", "stock", res.resolve("mipmap-xxhdpi/ic_launcher.png").readText())
        for (density in ICON_DENSITIES) for (picture in ICON_PICTURES) {
            val file = res.resolve("mipmap-$density/$picture.png")
            assertArrayEquals("$file", bundledIconPictures().getValue("mipmap-$density/$picture.png"), file.readBytes())
        }
        val icon = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(res.resolve("mipmap-anydpi-v26/$ICON.xml")).documentElement
        assertEquals("adaptive-icon", icon.tagName)
        assertEquals(ANDROID_NAMESPACE, icon.getAttribute("xmlns:android"))
        val layers = (0 until icon.childNodes.length).map { icon.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(listOf("background", "foreground", "monochrome"), layers.map { it.tagName })
        val color = layers[0].getElementsByTagName("color").item(0) as Element
        assertEquals(ICON_BACKGROUND, color.getAttribute("android:color"))
        assertEquals("@mipmap/${ICON}_foreground", layers[1].getAttribute("android:drawable"))
        assertEquals("@mipmap/${ICON}_monochrome", layers[2].getAttribute("android:drawable"))
    }

    @Test
    fun `undecoded resources or an icon already there refuse before anything is written`() {
        val missing = File(temporary.root, "not-decoded")
        refusesFiles(missing, "weren't decoded")
        assertFalse(missing.exists())
        for (existing in listOf("mipmap-xxhdpi/${ICON}_foreground.png", "mipmap-anydpi-v26/$ICON.xml")) {
            val res = temporary.newFolder()
            res.resolve(existing).apply { parentFile.mkdirs() }.writeText("stock")
            refusesFiles(res, "already has")
            assertEquals(listOf(existing), res.walkTopDown().filter { it.isFile }.map { it.relativeTo(res).invariantSeparatorsPath }.toList())
        }
    }

    @Test
    fun `a name that can't be used refuses before anything is read`() {
        for (name in listOf("x".repeat(MAX_APP_NAME + 1), "@string/app_name", "?attr/name", "Hush \"TG\"", "Hush's", "a\\b", "line\nbreak")) {
            refusal(manifest(), name) { message -> assertTrue("$name: $message", message.contains("appName")) }
        }
        assertEquals("x".repeat(MAX_APP_NAME), checkedAppName("x".repeat(MAX_APP_NAME)))
    }

    @Test
    fun `a manifest of another shape refuses before anything is written`() {
        refusal(manifest().replace(ANDROID_NAMESPACE, "https://example.invalid/android"))
        refusal(manifest().replace("</manifest>", "<application /></manifest>"))
        refusal(manifest().replace(DEFAULT_ICON, "org.telegram.messenger.OtherIcon"))
        refusal(manifest().replace("</application>", "<activity-alias android:name=\"$DEFAULT_ICON\" /></application>"))
        refusal(manifest().replace("android:icon=\"@mipmap/ic_launcher\" ", ""))
    }

    @Test
    fun `each bundled picture is the size its density needs`() {
        val factors = mapOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)
        val pictures = bundledIconPictures()
        assertEquals(ICON_DENSITIES.size * ICON_PICTURES.size, pictures.size)
        for ((path, bytes) in pictures) {
            val density = path.substringAfter("mipmap-").substringBefore('/')
            val side = Math.round((if (path.endsWith("/$ICON.png")) 48 else 108) * factors.getValue(density)).toInt()
            assertEquals(path, side to side, pngSize(bytes))
        }
    }

    private fun refusesFiles(res: File, reason: String) {
        try {
            addIconFiles(res, bundledIconPictures())
            fail("accepted")
        } catch (expected: PatchException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(reason))
            assertTrue(expected.message.orEmpty().contains("Nothing was changed"))
        }
    }

    /** Runs the patch itself, which has to refuse while it's still checking, before it reads the resources. */
    private fun refusal(xml: String, name: String? = "Hush", check: (String) -> Unit = {}) {
        val work = temporary.newFolder()
        val apk = File(work, "input.apk")
        ApkModule().use { module ->
            module.setManifest(AndroidManifestBlock.empty().apply {
                setPackageName("org.telegram.messenger.web")
                setVersionName("13.0.1")
                setVersionCode(71679)
            })
            module.writeApk(apk)
        }
        val config = PatcherConfig(apkFile = apk, temporaryFilesPath = File(work, "patcher"))
        ResourcePatchContext::class.java.getConstructor(PatcherConfig::class.java).newInstance(config).use { context ->
            val manifest = context["AndroidManifest.xml"]
            manifest.parentFile.mkdirs()
            manifest.writeText(xml)
            val before = manifest.readBytes()
            hushTelegramIconPatch.options["appName"] = name
            try {
                hushTelegramIconPatch.execute(context)
                fail("accepted")
            } catch (expected: PatchException) {
                assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("Nothing was changed"))
                check(expected.message.orEmpty())
            } finally {
                hushTelegramIconPatch.options.values.forEach { it.reset() }
            }
            assertArrayEquals("the manifest's bytes are untouched", before, manifest.readBytes())
        }
    }

    private fun fixtureManifest(build: File): String = ApkModule.loadApkFile(build).use { module ->
        module.tableBlock // Attach the resource package the manifest decoder names references with.
        val xml = StringWriter()
        val serializer = XmlPullParserFactory.newInstance().newSerializer().apply { setOutput(xml) }
        module.androidManifest.serialize(serializer)
        xml.toString()
    }

    private fun document(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())

    private fun manifest(alias: String = "") = "<manifest xmlns:android=\"$ANDROID_NAMESPACE\" package=\"org.telegram.messenger.web\">" +
        "<application android:label=\"Telegram\" android:icon=\"@mipmap/ic_launcher\" android:roundIcon=\"@mipmap/ic_launcher_round\">" +
        "<activity android:name=\"org.telegram.ui.LaunchActivity\" />" +
        "<activity-alias android:name=\"$DEFAULT_ICON\" android:targetActivity=\"org.telegram.ui.LaunchActivity\" $alias />" +
        "<activity-alias android:name=\"org.telegram.messenger.VintageIcon\" android:icon=\"@mipmap/icon_2_launcher\" " +
        "android:targetActivity=\"org.telegram.ui.LaunchActivity\" />" +
        "</application></manifest>"

    private fun state(node: Node): List<Any?> {
        val attributes = (0 until (node.attributes?.length ?: 0)).map { node.attributes.item(it) }
            .map { it.nodeName to it.nodeValue }.sortedBy { it.first }
        val children = (0 until node.childNodes.length).map { node.childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }
        return listOf(node.nodeName, attributes, children.map { state(it) })
    }

    /** The width and height a PNG's header gives. */
    private fun pngSize(bytes: ByteArray): Pair<Int, Int> {
        fun int(at: Int) = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
        assertEquals("IHDR", String(bytes, 12, 4, Charsets.ISO_8859_1))
        return int(16) to int(20)
    }
}
