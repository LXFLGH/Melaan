package com.melavonmen.an.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal data class EpubInfo(
    val title: String?,
    val authors: List<String>,
    val tags: List<String>,
    val chapters: List<ChapterRef>,
    val coverEntryName: String?,
)

/**
 * Minimal EPUB 2/3 parser backed by [ArchiveReader]. It reads only package metadata,
 * spine order and the declared cover; chapter XHTML is streamed on demand by the reader.
 */
internal object EpubParser {

    fun parse(reader: ArchiveReader): EpubInfo? {
        val containerBytes = runCatching {
            reader.readEntryBytes("META-INF/container.xml")
        }.getOrNull() ?: return null
        val opfPath = parseXml(containerBytes)?.let(::findRootFilePath) ?: return null
        val opfBytes = runCatching { reader.readEntryBytes(opfPath) }.getOrNull() ?: return null
        val opf = parseXml(opfBytes) ?: return null
        val opfDirectory = opfPath.substringBeforeLast('/', "")
        val metadata = descendants(opf, "metadata").firstOrNull() ?: return null
        val manifest = descendants(opf, "manifest").firstOrNull() ?: return null
        val spine = descendants(opf, "spine").firstOrNull() ?: return null

        val title = childText(metadata, "title")?.trim()?.takeIf(String::isNotBlank)
        val authors = childTexts(metadata, "creator")
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val tags = childTexts(metadata, "subject")
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

        val manifestItems = children(manifest, "item")
            .mapNotNull { item ->
                val id = item.getAttribute("id").trim()
                val href = item.getAttribute("href").trim()
                if (id.isBlank() || href.isBlank()) null else {
                    id to EpubManifestItem(
                        href = resolveHref(opfDirectory, href),
                        mediaType = item.getAttribute("media-type").trim(),
                        properties = item.getAttribute("properties").trim()
                            .split(Regex("\\s+"))
                            .filter(String::isNotBlank)
                            .toSet(),
                    )
                }
            }
            .toMap()
        if (manifestItems.isEmpty()) return null

        val coverItem = manifestItems.values.firstOrNull { "cover-image" in it.properties }
            ?: childNodes(metadata)
                .filterIsInstance<Element>()
                .firstOrNull { it.localName == "meta" && it.getAttribute("name") == "cover" }
                ?.getAttribute("content")
                ?.trim()
                ?.let { id -> manifestItems[id] }
                ?.takeIf { it.mediaType.startsWith("image/", ignoreCase = true) }
        val spineIds = children(spine, "itemref")
            .mapNotNull { it.getAttribute("idref").trim().takeIf(String::isNotBlank) }
        val chapters = spineIds.mapNotNull { id ->
            val item = manifestItems[id] ?: return@mapNotNull null
            if (!isHtmlMediaType(item.mediaType)) return@mapNotNull null
            ChapterRef(
                name = chapterTitle(item.href),
                pageEntryPaths = listOf(item.href),
                nestedArchiveEntryPath = null,
            )
        }
        if (chapters.isEmpty()) return null

        return EpubInfo(
            title = title,
            authors = authors,
            tags = tags,
            chapters = chapters,
            coverEntryName = coverItem?.href,
        )
    }

    private fun findRootFilePath(container: Node): String? =
        descendants(container, "rootfile")
            .mapNotNull { it.getAttributeOrNull("full-path") }
            .firstOrNull(String::isNotBlank)

    private fun parseXml(bytes: ByteArray): Node? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val builder = factory.newDocumentBuilder()
        builder.parse(ByteArrayInputStream(bytes)).documentElement
    }.getOrNull()

    private fun childNodes(node: Node): List<Node> {
        val list = node.childNodes
        return (0 until list.length).mapNotNull(list::item)
    }

    private fun children(node: Node, localName: String): List<Element> =
        childNodes(node).filterIsInstance<Element>()
            .filter { it.localName == localName }

    private fun descendants(node: Node, localName: String): List<Element> =
        generateSequence(listOf(node)) { level ->
            level.flatMap(::childNodes).ifEmpty { null }
        }
            .take(32)
            .flatMap { level -> level.filterIsInstance<Element>().filter { it.localName == localName } }
            .toList()

    private fun childText(node: Node, localName: String): String? =
        children(node, localName).firstOrNull()?.textContent

    private fun childTexts(node: Node, localName: String): List<String> =
        children(node, localName).mapNotNull { it.textContent }

    private fun isHtmlMediaType(mediaType: String): Boolean =
        mediaType.equals("application/xhtml+xml", ignoreCase = true) ||
            mediaType.equals("text/html", ignoreCase = true)

    private fun chapterTitle(href: String): String {
        val raw = href.substringAfterLast('/')
            .ifBlank { href }
            .substringBeforeLast('.')
        return URL_DECODER.decode(raw)
    }

    private fun resolveHref(baseDirectory: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val stack = ArrayDeque(baseDirectory.split('/').filter { it.isNotEmpty() && it != "." })
        for (encodedPart in clean.split('/')) {
            // OPF hrefs are URIs; ZIP entry names are decoded paths.
            val part = URL_DECODER.decode(encodedPart)
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun Node.getAttributeOrNull(name: String): String? =
        (this as? Element)?.getAttribute(name)?.takeIf(String::isNotBlank)

    private object URL_DECODER {
        fun decode(value: String): String = runCatching {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
        }.getOrDefault(value)
    }
}

private data class EpubManifestItem(
    val href: String,
    val mediaType: String,
    val properties: Set<String>,
)
