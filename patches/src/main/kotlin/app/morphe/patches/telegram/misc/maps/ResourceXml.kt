/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.maps

import app.morphe.patcher.patch.PatchException
import org.w3c.dom.Document
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val XINCLUDE_NAMESPACE = "http://www.w3.org/2001/XInclude"

/**
 * Android's DOM factory has no Xerces security features or XInclude accessors. Reject
 * declarations and XInclude elements before building the DOM, using namespace-aware XMLPull
 * with DTD processing off. Both passes read the same snapshot to prevent validation races.
 */
internal fun readResourceXml(
    file: File,
    patch: String,
    factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance(),
): Document {
    val bytes = file.readBytes()
    val tokens = XmlPullParserFactory.newInstance().newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        if (!getFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES)) {
            throw PatchException("$patch: resource XML namespaces couldn't be enabled. Nothing was changed.")
        }
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        if (getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
            throw PatchException("$patch: resource XML DTD processing couldn't be disabled. Nothing was changed.")
        }
    }
    try {
        bytes.inputStream().use { input ->
            tokens.setInput(input, null)
            while (tokens.nextToken() != XmlPullParser.END_DOCUMENT) {
                if (tokens.eventType == XmlPullParser.DOCDECL) {
                    throw PatchException("$patch: resource XML can't contain a DOCTYPE declaration. Nothing was changed.")
                }
                if (tokens.eventType == XmlPullParser.START_TAG && tokens.namespace == XINCLUDE_NAMESPACE) {
                    throw PatchException("$patch: resource XML can't contain XInclude elements. Nothing was changed.")
                }
            }
        }
    } catch (error: XmlPullParserException) {
        // Some providers reject unsupported DTD syntax before returning its token. Keep that
        // refusal and its cause; never retry the document with a less restrictive parser.
        throw PatchException("$patch: resource XML is malformed or has an unsupported declaration. Nothing was changed.", error)
    }
    factory.isValidating = false
    factory.isExpandEntityReferences = false
    val builder = factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw SAXException("External entities aren't allowed in resource XML.") }
        setErrorHandler(object : DefaultHandler() {
            override fun error(error: SAXParseException) = throw error
            override fun fatalError(error: SAXParseException) = throw error
        })
    }
    return bytes.inputStream().use { builder.parse(it) }
}
