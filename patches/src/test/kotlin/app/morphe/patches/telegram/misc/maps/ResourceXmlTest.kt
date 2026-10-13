/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.maps

import app.morphe.patcher.patch.PatchException
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

class ResourceXmlTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test
    fun `Android's factory rejects the old features but the shared reader preserves ordinary XML`() {
        val factory = AndroidResourceXmlFactory()
        for (feature in listOf("http://apache.org/xml/features/disallow-doctype-decl",
            "http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) {
            assertThrows(ParserConfigurationException::class.java) { factory.setFeature(feature, false) }
        }
        assertThrows(UnsupportedOperationException::class.java) { factory.isXIncludeAware = false }
        assertThrows(UnsupportedOperationException::class.java) { factory.isXIncludeAware = true }
        assertThrows(UnsupportedOperationException::class.java) { factory.isXIncludeAware }
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <!-- <!DOCTYPE text in a comment is harmless> -->
            <resources xmlns:android="$ANDROID_NAMESPACE">
                <string name="label" android:other="kept">Café &amp; &#x1f600;</string>
                <string name="literal"><![CDATA[<!DOCTYPE text in CDATA>]]></string>
            </resources>""".trimIndent()
        val file = temporary.newFile().apply { writeText(xml) }
        val document = readResourceXml(file, "test", factory)
        assertEquals("resources", document.documentElement.tagName)
        val strings = document.getElementsByTagName("string")
        assertEquals("Café & \uD83D\uDE00", strings.item(0).textContent)
        assertEquals("kept", strings.item(0).attributes.getNamedItem("android:other").nodeValue)
        assertEquals("<!DOCTYPE text in CDATA>", strings.item(1).textContent)
        assertArrayEquals(xml.toByteArray(), file.readBytes())
    }

    @Test
    fun `ordinary Unicode resources parse with UTF-8 and both UTF-16 byte orders`() {
        for ((encoding, bom) in listOf(Charsets.UTF_8 to byteArrayOf(),
            Charsets.UTF_8 to byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
            Charsets.UTF_16LE to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
            Charsets.UTF_16BE to byteArrayOf(0xfe.toByte(), 0xff.toByte()))) {
            val bytes = bom + "<resources><item>Café &amp; &#x1f600;</item></resources>".toByteArray(encoding)
            val file = temporary.newFile().apply { writeBytes(bytes) }
            assertEquals("Café & \uD83D\uDE00", readResourceXml(file, "test", AndroidResourceXmlFactory()).documentElement.textContent)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun `DTD external entities and internal expansion refuse before any DOM exists in every XML encoding`() {
        for (declaration in resourceXmlDeclarations("resources")) {
            for ((encoding, bom) in listOf(Charsets.UTF_8 to byteArrayOf(),
                Charsets.UTF_8 to byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
                Charsets.UTF_16LE to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
                Charsets.UTF_16BE to byteArrayOf(0xfe.toByte(), 0xff.toByte()))) {
                val bytes = bom + (declaration + "<resources>&payload;</resources>").toByteArray(encoding)
                val file = temporary.newFile().apply { writeBytes(bytes) }
                val factory = AndroidResourceXmlFactory()
                val error = assertThrows(PatchException::class.java) { readResourceXml(file, "test", factory) }
                assertTrue(error.message.orEmpty().contains("resource XML"))
                assertTrue(error.message.orEmpty().contains("Nothing was changed"))
                assertEquals("no DOM can read an entity", 0, factory.buildersCreated)
                assertArrayEquals(bytes, file.readBytes())
            }
        }
    }

    @Test
    fun `external DTD and entity addresses receive no request during either parsing pass`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.incrementAndGet()
                val content = "<!ENTITY payload 'must not be fetched'>".toByteArray()
                exchange.sendResponseHeaders(200, content.size.toLong())
                exchange.responseBody.use { it.write(content) }
            }
            start()
        }
        try {
            val address = "http://127.0.0.1:${server.address.port}/entity"
            for (declaration in listOf("<!DOCTYPE resources SYSTEM \"$address\">",
                "<!DOCTYPE resources [<!ENTITY payload SYSTEM \"$address\">]>",
                "<!DOCTYPE resources [<!ENTITY % external SYSTEM \"$address\">%external;]>")) {
                val file = temporary.newFile().apply { writeText(declaration + "<resources>&payload;</resources>") }
                assertThrows(PatchException::class.java) { readResourceXml(file, "test") }
            }
            assertEquals("declaration validation also performs no external I/O", 0, requests.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `malformed input and an unavailable DOM parser stay errors`() {
        val file = temporary.newFile().apply { writeText("<resources><item></resources>") }
        val before = file.readBytes()
        val factory = AndroidResourceXmlFactory()
        val malformed = assertThrows(PatchException::class.java) { readResourceXml(file, "test", factory) }
        assertTrue("the parser error remains the cause", malformed.cause is XmlPullParserException)
        assertEquals(0, factory.buildersCreated)
        assertArrayEquals(before, file.readBytes())

        file.writeText("<resources />")
        val failure = ParserConfigurationException("provider unavailable")
        val broken = object : AndroidResourceXmlFactory() {
            override fun newDocumentBuilder(): DocumentBuilder = throw failure
        }
        assertSame(failure, assertThrows(ParserConfigurationException::class.java) { readResourceXml(file, "test", broken) })
    }

    @Test
    fun `DOM parsing uses the bytes that passed the declaration check`() {
        val file = temporary.newFile().apply { writeText("<resources><item>original</item></resources>") }
        val factory = object : AndroidResourceXmlFactory() {
            override fun newDocumentBuilder(): DocumentBuilder {
                file.writeText(resourceXmlDeclarations("resources").last() + "<resources>&payload;</resources>")
                return super.newDocumentBuilder()
            }
        }
        assertEquals("original", readResourceXml(file, "test", factory).documentElement.textContent)
    }

    @Test
    fun `ordinary namespaces preserve the caller's DOM namespace setting`() {
        val xml = """<resources xmlns="urn:ordinary" xmlns:android="$ANDROID_NAMESPACE">
            <include href="file:///never-read" android:other="kept" />
        </resources>""".trimIndent()
        for (namespaceAware in listOf(false, true)) {
            val file = temporary.newFile().apply { writeText(xml) }
            val factory = AndroidResourceXmlFactory().apply { isNamespaceAware = namespaceAware }
            val document = readResourceXml(file, "test", factory)
            assertEquals(namespaceAware, factory.isNamespaceAware)
            assertEquals(if (namespaceAware) "urn:ordinary" else null, document.documentElement.namespaceURI)
            val include = document.getElementsByTagName("include").item(0)
            assertEquals("kept", include.attributes.getNamedItem("android:other").nodeValue)
            assertArrayEquals(xml.toByteArray(), file.readBytes())
        }
    }

    @Test
    fun `XInclude refuses every prefix and encoding before DOM parsing or local mutation`() {
        val root = temporary.newFolder()
        val secret = File(root, "secret.xml").apply { writeText("<secret>not included</secret>") }
        File(root, "unrelated.bin").writeBytes(byteArrayOf(1, 7, 23, 42))
        val namespace = "http://www.w3.org/2001/XInclude"
        for (xml in listOf(
            "<resources xmlns:xi=\"$namespace\"><xi:include href=\"${secret.toURI()}\" /></resources>",
            "<resources><include xmlns=\"$namespace\" href=\"${secret.toURI()}\" /></resources>",
            "<resources xmlns:any=\"http://www.w3.org/2001/XInclu&#100;e\"><any:include href=\"${secret.toURI()}\" /></resources>",
            "<resources xmlns:xi=\"$namespace\"><xi:fallback /></resources>")) {
            for ((encoding, bom) in listOf(Charsets.UTF_8 to byteArrayOf(),
                Charsets.UTF_8 to byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
                Charsets.UTF_16LE to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
                Charsets.UTF_16BE to byteArrayOf(0xfe.toByte(), 0xff.toByte()))) {
                val file = File(root, "resource.xml").apply { writeBytes(bom + xml.toByteArray(encoding)) }
                val before = resourceTree(root)
                val factory = AndroidResourceXmlFactory().apply { isNamespaceAware = true }
                val error = assertThrows(PatchException::class.java) { readResourceXml(file, "test", factory) }
                assertTrue(error.message.orEmpty().contains("XInclude"))
                assertTrue(error.message.orEmpty().contains("Nothing was changed"))
                assertEquals("no DOM can process an inclusion", 0, factory.buildersCreated)
                assertResourceTreeUnchanged(before, root)
            }
        }
    }

    @Test
    fun `an XInclude-enabled DOM provider never opens a local or HTTP address`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.incrementAndGet()
                val content = "<secret>not included</secret>".toByteArray()
                exchange.sendResponseHeaders(200, content.size.toLong())
                exchange.responseBody.use { it.write(content) }
            }
            start()
        }
        try {
            val root = temporary.newFolder()
            val local = File(root, "secret.xml").apply { writeText("<secret>not included</secret>") }
            for (address in listOf(local.toURI().toString(), "http://127.0.0.1:${server.address.port}/include")) {
                val file = File(root, "resource.xml").apply {
                    writeText("<resources xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\"$address\" /></resources>")
                }
                val before = resourceTree(root)
                val factory = DocumentBuilderFactory.newDefaultInstance().apply { isNamespaceAware = true; isXIncludeAware = true }
                assertThrows(PatchException::class.java) { readResourceXml(file, "test", factory) }
                assertTrue("the caller's DOM configuration wasn't changed", factory.isXIncludeAware)
                assertResourceTreeUnchanged(before, root)
            }
            assertEquals("XInclude performs no external I/O", 0, requests.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `shipped patches reach an XML parser only through the shared reader`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val parsers = Regex("DocumentBuilderFactory|SAXParserFactory|XMLInputFactory|XMLReaderFactory")
        val own = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "ResourceXml.kt" }
            .filter { parsers.containsMatchIn(it.readText()) }.map { it.name }.sorted().toList()
        // v0.0.13's icon, launch logo and Maps patches each set Xerces features on their own factory,
        // which Android's factory refuses, so all three stopped a Morphe Manager run.
        assertEquals("a patch that builds its own parser fails on a phone", emptyList<String>(), own)
    }
}

/** Android's provider also inherits unsupported XInclude accessors, even when disabling it. */
open class AndroidResourceXmlFactory : DocumentBuilderFactory() {
    var buildersCreated = 0
        private set

    override fun newDocumentBuilder(): DocumentBuilder {
        buildersCreated++
        return newDefaultInstance().apply {
            isNamespaceAware = this@AndroidResourceXmlFactory.isNamespaceAware
            isValidating = this@AndroidResourceXmlFactory.isValidating
            isExpandEntityReferences = this@AndroidResourceXmlFactory.isExpandEntityReferences
        }.newDocumentBuilder()
    }

    override fun setXIncludeAware(state: Boolean): Unit = throw UnsupportedOperationException("setXIncludeAware")
    override fun isXIncludeAware(): Boolean = throw UnsupportedOperationException("isXIncludeAware")
    override fun setAttribute(name: String, value: Any) = throw IllegalArgumentException(name)
    override fun getAttribute(name: String): Any = throw IllegalArgumentException(name)
    override fun setFeature(name: String, value: Boolean) = when (name) {
        "http://xml.org/sax/features/namespaces" -> isNamespaceAware = value
        "http://xml.org/sax/features/validation" -> isValidating = value
        else -> throw ParserConfigurationException(name)
    }
    override fun getFeature(name: String): Boolean = when (name) {
        "http://xml.org/sax/features/namespaces" -> isNamespaceAware
        "http://xml.org/sax/features/validation" -> isValidating
        else -> throw ParserConfigurationException(name)
    }
}

internal fun <T> withAndroidResourceXmlFactory(action: () -> T): T = synchronized(AndroidResourceXmlFactory::class.java) {
    val property = "javax.xml.parsers.DocumentBuilderFactory"
    val previous = System.getProperty(property)
    System.setProperty(property, AndroidResourceXmlFactory::class.java.name)
    try { action() } finally {
        if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
    }
}

internal fun resourceXmlDeclarations(root: String) = listOf(
    "<!DOCTYPE $root>",
    "<!DOCTYPE $root SYSTEM \"https://127.0.0.1:9/never-read\">",
    "<!DOCTYPE $root [<!ENTITY payload SYSTEM \"file:///never-read\">]>",
    "<!DOCTYPE $root [<!ENTITY % external SYSTEM \"https://127.0.0.1:9/never-read\">%external;]>",
    "<!DOCTYPE $root [<!ENTITY a \"expanded\"><!ENTITY b \"&a;&a;&a;&a;\"><!ENTITY payload \"&b;&b;&b;&b;\">]>",
)

internal fun resourceTree(root: File): Map<String, ByteArray> = root.walkTopDown().filter { it.isFile }
    .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }

internal fun assertResourceTreeUnchanged(before: Map<String, ByteArray>, root: File) {
    val after = resourceTree(root)
    assertEquals("no file was added or removed", before.keys, after.keys)
    for ((path, bytes) in before) assertArrayEquals(path, bytes, after.getValue(path))
}
