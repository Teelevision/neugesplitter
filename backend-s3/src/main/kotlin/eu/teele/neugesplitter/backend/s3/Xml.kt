package eu.teele.neugesplitter.backend.s3

import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Calls [element] with the path of names to every element of [bytes] and its text. S3 answers in
 * XML only for listings and errors, both flat enough that this is all the reading they need.
 */
internal fun readXml(bytes: ByteArray, element: (path: List<String>, text: String) -> Unit) {
    val handler =
        object : DefaultHandler() {
            val path = ArrayList<String>()
            val text = StringBuilder()

            override fun startElement(
                uri: String,
                localName: String,
                qName: String,
                attributes: Attributes,
            ) {
                path += qName
                text.clear()
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                text.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                element(path, text.toString())
                path.removeAt(path.lastIndex)
                text.clear()
            }
        }
    PARSERS.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
}

/** No document S3 sends has a DTD, and a DTD is how external entities get in. */
private val PARSERS: SAXParserFactory =
    SAXParserFactory.newInstance().apply {
        for ((feature, value) in
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            )) {
            // Android's parser knows only some of these, and never fetches entities anyway.
            runCatching { setFeature(feature, value) }
        }
    }
