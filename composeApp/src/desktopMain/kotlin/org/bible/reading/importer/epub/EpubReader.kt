package org.bible.reading.importer.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bible.reading.importer.BookImporter
import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.model.ImportedBookMetadata
import org.bible.reading.importer.model.ImportedChapter
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Implements [BookImporter] for EPUB files (.epub), supporting both EPUB 2 and EPUB 3 standards.
 */
class EpubImporter : BookImporter {
    override val formatName: String = "EPUB"
    override val supportedExtensions: List<String> = listOf("epub")

    override suspend fun parse(file: File): ImportedBook {
        return EpubReader.parse(file)
    }
}

/**
 * Archive reader and metadata/spine extractor for EPUB containers.
 */
object EpubReader {

    private val docBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        try {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Exception) {}
    }

    suspend fun parse(file: File): ImportedBook = withContext(Dispatchers.IO) {
        require(file.exists()) { "EPUB file does not exist: ${file.absolutePath}" }

        ZipFile(file).use { zip ->
            // 1. Read META-INF/container.xml to find the OPF package file path
            val opfPath = findOpfPath(zip)
                ?: throw IllegalArgumentException("Invalid EPUB: META-INF/container.xml not found or missing rootfile in ${file.name}")

            val opfBaseDir = getParentPath(opfPath)

            // 2. Read and parse OPF package file
            val opfEntry = findEntryIgnoreCase(zip, opfPath)
                ?: throw IllegalArgumentException("Invalid EPUB: Package document '$opfPath' not found in archive")

            val opfBytes = zip.getInputStream(opfEntry).use { it.readBytes() }
            val opfDoc = docBuilderFactory.newDocumentBuilder().parse(ByteArrayInputStream(opfBytes))

            // 3. Extract metadata
            val metadata = parseMetadata(opfDoc.documentElement)

            // 4. Extract manifest: id -> href
            val manifestMap = parseManifest(opfDoc.documentElement)

            // 5. Extract spine: ordered itemref IDs
            val spineIds = parseSpine(opfDoc.documentElement)

            // 6. Extract chapter documents in spine reading order
            val chapters = mutableListOf<ImportedChapter>()
            var order = 1

            for (itemId in spineIds) {
                val rawHref = manifestMap[itemId] ?: continue
                // Exclude non-HTML items if any slipped into spine (e.g. image or ncx)
                val cleanHref = rawHref.substringBefore('#')
                if (!cleanHref.endsWith(".xhtml", ignoreCase = true) &&
                    !cleanHref.endsWith(".html", ignoreCase = true) &&
                    !cleanHref.endsWith(".htm", ignoreCase = true) &&
                    !cleanHref.endsWith(".xml", ignoreCase = true)
                ) {
                    continue
                }

                val fullPath = resolveRelativePath(opfBaseDir, cleanHref)
                val entry = findEntryIgnoreCase(zip, fullPath) ?: continue

                val content = zip.getInputStream(entry).use { stream ->
                    stream.readBytes().toString(StandardCharsets.UTF_8)
                }

                val title = extractChapterTitle(content, fallbackNumber = order)

                chapters.add(
                    ImportedChapter(
                        id = itemId,
                        title = title,
                        rawContent = content,
                        order = order++
                    )
                )
            }

            if (chapters.isEmpty()) {
                throw IllegalArgumentException("EPUB contains no readable chapter documents in spine reading order: ${file.name}")
            }

            ImportedBook(metadata = metadata, chapters = chapters)
        }
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val containerEntry = findEntryIgnoreCase(zip, "META-INF/container.xml") ?: return null
        return zip.getInputStream(containerEntry).use { stream ->
            val doc = docBuilderFactory.newDocumentBuilder().parse(stream)
            val rootfileNodes = doc.getElementsByTagName("rootfile")
            for (i in 0 until rootfileNodes.length) {
                val elem = rootfileNodes.item(i) as? Element ?: continue
                val fullPath = elem.getAttribute("full-path")
                if (fullPath.isNotBlank()) {
                    return@use fullPath.trim()
                }
            }
            null
        }
    }

    private fun parseMetadata(packageElem: Element): ImportedBookMetadata {
        val metadataList = packageElem.getElementsByTagName("metadata")
        if (metadataList.length == 0) {
            return ImportedBookMetadata(title = "Untitled Book")
        }

        val metaElem = metadataList.item(0) as Element
        var title = "Untitled Book"
        var author = "Unknown"
        var identifier = ""
        var language = "en"
        var description = ""

        val childNodes = metaElem.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val elem = node as Element
            val localName = elem.localName ?: elem.nodeName.substringAfter(':')
            val text = elem.textContent?.trim() ?: ""

            when (localName.lowercase()) {
                "title" -> if (text.isNotBlank() && title == "Untitled Book") title = text
                "creator" -> if (text.isNotBlank() && author == "Unknown") author = text
                "identifier" -> if (text.isNotBlank() && identifier.isBlank()) identifier = text
                "language" -> if (text.isNotBlank()) language = text
                "description" -> if (text.isNotBlank() && description.isBlank()) description = text
            }
        }

        val about = buildString {
            append("Imported from EPUB: ").append(title)
            if (author != "Unknown") append(" by ").append(author)
            if (description.isNotBlank()) append("\n\n").append(description)
        }

        return ImportedBookMetadata(
            title = title,
            author = author,
            identifier = identifier,
            language = language,
            description = description,
            about = about
        )
    }

    private fun parseManifest(packageElem: Element): Map<String, String> {
        val manifestList = packageElem.getElementsByTagName("manifest")
        if (manifestList.length == 0) return emptyMap()

        val manifestElem = manifestList.item(0) as Element
        val items = manifestElem.getElementsByTagName("item")
        val result = mutableMapOf<String, String>()

        for (i in 0 until items.length) {
            val item = items.item(i) as? Element ?: continue
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isNotBlank() && href.isNotBlank()) {
                val decodedHref = try {
                    URLDecoder.decode(href, "UTF-8")
                } catch (_: Exception) {
                    href
                }
                result[id] = decodedHref
            }
        }
        return result
    }

    private fun parseSpine(packageElem: Element): List<String> {
        val spineList = packageElem.getElementsByTagName("spine")
        if (spineList.length == 0) return emptyList()

        val spineElem = spineList.item(0) as Element
        val itemrefs = spineElem.getElementsByTagName("itemref")
        val result = mutableListOf<String>()

        for (i in 0 until itemrefs.length) {
            val itemref = itemrefs.item(i) as? Element ?: continue
            val idref = itemref.getAttribute("idref")
            val linear = itemref.getAttribute("linear")
            // Include linear items (or unspecified, which defaults to yes)
            if (idref.isNotBlank() && (linear.isBlank() || linear.equals("yes", ignoreCase = true))) {
                result.add(idref)
            }
        }
        return result
    }

    private fun extractChapterTitle(htmlContent: String, fallbackNumber: Int): String {
        // 1. Try <title>...</title>
        val titleMatch = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(htmlContent)
        val titleText = titleMatch?.groupValues?.get(1)?.let { stripTags(it).trim() }
        if (!titleText.isNullOrBlank() && !titleText.equals("Untitled", ignoreCase = true)) {
            return titleText
        }

        // 2. Try <h1>...</h1>
        val h1Match = Regex("""<h1[^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(htmlContent)
        val h1Text = h1Match?.groupValues?.get(1)?.let { stripTags(it).trim() }
        if (!h1Text.isNullOrBlank()) {
            return h1Text
        }

        // 3. Try <h2>...</h2>
        val h2Match = Regex("""<h2[^>]*>(.*?)</h2>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(htmlContent)
        val h2Text = h2Match?.groupValues?.get(1)?.let { stripTags(it).trim() }
        if (!h2Text.isNullOrBlank()) {
            return h2Text
        }

        return "Chapter $fallbackNumber"
    }

    private fun stripTags(html: String): String {
        return html.replace(Regex("""<[^>]*>"""), " ").replace(Regex("""\s+"""), " ").trim()
    }

    private fun findEntryIgnoreCase(zip: ZipFile, path: String): ZipEntry? {
        val exact = zip.getEntry(path)
        if (exact != null) return exact

        val normalized = path.replace('\\', '/')
        val exactNorm = zip.getEntry(normalized)
        if (exactNorm != null) return exactNorm

        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name.equals(path, ignoreCase = true) || entry.name.replace('\\', '/').equals(normalized, ignoreCase = true)) {
                return entry
            }
        }
        return null
    }

    private fun getParentPath(path: String): String {
        val norm = path.replace('\\', '/')
        val lastSlash = norm.lastIndexOf('/')
        return if (lastSlash >= 0) norm.substring(0, lastSlash) else ""
    }

    private fun resolveRelativePath(baseDir: String, relativePath: String): String {
        val normRel = relativePath.replace('\\', '/')
        if (normRel.startsWith("/")) return normRel.removePrefix("/")
        if (baseDir.isBlank()) return normRel

        val parts = (baseDir.split("/") + normRel.split("/")).filter { it.isNotEmpty() && it != "." }
        val resolved = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
            } else {
                resolved.add(part)
            }
        }
        return resolved.joinToString("/")
    }
}
