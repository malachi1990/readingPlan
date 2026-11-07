package org.bible.reading.importer.sword

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.osis.OsisConverter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pure JVM CrossWire Sword module compiler for General Books (RawGenBook).
 * Generates `.conf`, `.bdt`, `.dat`, and `.idx` files directly in the target
 * repository directory (e.g. `~/.bibleReadingPlan`).
 */
object SwordGenBookWriter {

    data class CompilationResult(
        val initials: String,
        val confFile: File,
        val moduleDir: File,
        val bdtFile: File,
        val datFile: File,
        val idxFile: File,
        val chapterCount: Int
    )

    /**
     * Compiles an [ImportedBook] into a CrossWire Sword RawGenBook module inside [appDir].
     */
    suspend fun write(
        appDir: File,
        book: ImportedBook,
        preferredInitials: String? = null
    ): CompilationResult = withContext(Dispatchers.IO) {
        val initials = generateUniqueInitials(appDir, preferredInitials ?: book.metadata.title)
        val initialsLower = initials.lowercase()

        // 1. Prepare target directories
        val modsDir = File(appDir, "mods.d").apply { if (!exists()) mkdirs() }
        val moduleDir = File(appDir, "modules/genbook/rawgenbook/$initialsLower").apply { if (!exists()) mkdirs() }

        val bdtFile = File(moduleDir, "$initialsLower.bdt")
        val datFile = File(moduleDir, "$initialsLower.dat")
        val idxFile = File(moduleDir, "$initialsLower.idx")
        val confFile = File(modsDir, "$initialsLower.conf")

        // 2. Compile chapters to OSIS and write .bdt
        val bdtStream = ByteArrayOutputStream()
        val chapterRecords = mutableListOf<ChapterRecord>()

        for ((index, ch) in book.chapters.withIndex()) {
            val osisXml = OsisConverter.toOsisSection(ch)
            val osisBytes = osisXml.toByteArray(StandardCharsets.UTF_8)

            val offset = bdtStream.size()
            val length = osisBytes.size
            bdtStream.write(osisBytes)

            val sanitizedTitle = ch.title
                .replace("/", "-")
                .replace("\\", "-")
                .trim()
                .ifBlank { "Chapter ${index + 1}" }

            chapterRecords.add(
                ChapterRecord(
                    title = sanitizedTitle,
                    bdtOffset = offset,
                    bdtLength = length
                )
            )
        }

        // 3. Encode tree nodes to .dat and generate .idx offsets
        // Node 0: Root node
        // Nodes 1..N: Chapters
        val datStream = ByteArrayOutputStream()
        val idxOffsets = mutableListOf<Int>()

        val totalNodes = 1 + chapterRecords.size

        // Encode Root Node (Node 0)
        idxOffsets.add(datStream.size())
        val firstChildIdx = if (chapterRecords.isNotEmpty()) 1 * 4 else -1
        writeTreeNode(
            out = datStream,
            parentIdx = -1,
            nextSiblingIdx = -1,
            firstChildIdx = firstChildIdx,
            name = "",
            userData = null
        )

        // Encode Chapter Nodes (Nodes 1..N)
        for ((i, record) in chapterRecords.withIndex()) {
            val nodeIndex = i + 1
            idxOffsets.add(datStream.size())

            val parentIdx = 0 // Offset in .idx for Root
            val nextSiblingIdx = if (nodeIndex < chapterRecords.size) (nodeIndex + 1) * 4 else -1
            val firstChild = -1 // Leaves

            // 8-byte userData: 4 bytes LE offset, 4 bytes LE length
            val userData = ByteArray(8)
            encodeLE32(record.bdtOffset, userData, 0)
            encodeLE32(record.bdtLength, userData, 4)

            writeTreeNode(
                out = datStream,
                parentIdx = parentIdx,
                nextSiblingIdx = nextSiblingIdx,
                firstChildIdx = firstChild,
                name = record.title,
                userData = userData
            )
        }

        // 4. Encode .idx (array of 32-bit LE dat offsets)
        val idxBytes = ByteArray(totalNodes * 4)
        for ((idx, datOffset) in idxOffsets.withIndex()) {
            encodeLE32(datOffset, idxBytes, idx * 4)
        }

        // 5. Write binary files
        FileOutputStream(bdtFile).use { it.write(bdtStream.toByteArray()) }
        FileOutputStream(datFile).use { it.write(datStream.toByteArray()) }
        FileOutputStream(idxFile).use { it.write(idxBytes) }

        // 6. Write .conf file
        writeConfigFile(confFile, initials, initialsLower, book)

        CompilationResult(
            initials = initials,
            confFile = confFile,
            moduleDir = moduleDir,
            bdtFile = bdtFile,
            datFile = datFile,
            idxFile = idxFile,
            chapterCount = chapterRecords.size
        )
    }

    private fun writeTreeNode(
        out: ByteArrayOutputStream,
        parentIdx: Int,
        nextSiblingIdx: Int,
        firstChildIdx: Int,
        name: String,
        userData: ByteArray?
    ) {
        val header = ByteArray(12)
        encodeLE32(parentIdx, header, 0)
        encodeLE32(nextSiblingIdx, header, 4)
        encodeLE32(firstChildIdx, header, 8)
        out.write(header)

        // Null-terminated UTF-8 string for name
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        out.write(nameBytes)
        out.write(0) // Null terminator

        // User data: 2 bytes LE size, then bytes
        if (userData != null && userData.isNotEmpty()) {
            val sizeBytes = ByteArray(2)
            encodeLE16(userData.size, sizeBytes, 0)
            out.write(sizeBytes)
            out.write(userData)
        } else {
            val sizeBytes = ByteArray(2)
            encodeLE16(0, sizeBytes, 0)
            out.write(sizeBytes)
        }
    }

    private fun writeConfigFile(
        confFile: File,
        initials: String,
        initialsLower: String,
        book: ImportedBook
    ) {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val description = book.metadata.title.replace("\n", " ").trim()
        val lang = if (book.metadata.language.isNotBlank()) book.metadata.language.take(2).lowercase() else "en"
        val author = book.metadata.author
        val about = book.metadata.about.ifBlank {
            "Imported from EPUB: ${book.metadata.title}${if (author != "Unknown") " by $author" else ""}"
        }.replace("\n", "\\par\n")

        val confContent = buildString {
            append("[$initials]\n")
            append("DataPath=./modules/genbook/rawgenbook/$initialsLower/$initialsLower\n")
            append("ModDrv=RawGenBook\n")
            append("Encoding=UTF-8\n")
            append("SourceType=OSIS\n")
            append("Description=$description\n")
            append("Lang=$lang\n")
            append("Category=General Books\n")
            append("DistributionLicense=Public Domain\n")
            append("About=$about\n")
            append("SwordVersionDate=$today\n")
            append("Version=1.0.0\n")
        }

        confFile.writeText(confContent, StandardCharsets.UTF_8)
    }

    fun generateUniqueInitials(appDir: File, rawTitle: String): String {
        // Strip non-alphanumeric, uppercase
        val alphaOnly = rawTitle.filter { it.isLetterOrDigit() }.uppercase()
        val baseInitials = if (alphaOnly.length >= 4) {
            alphaOnly.take(10)
        } else if (alphaOnly.isNotEmpty()) {
            (alphaOnly + "BOOK").take(10)
        } else {
            "IMPORTED"
        }

        var candidate = baseInitials
        var counter = 2
        val modsDir = File(appDir, "mods.d")

        while (File(modsDir, "${candidate.lowercase()}.conf").exists()) {
            candidate = "${baseInitials.take(8)}$counter"
            counter++
        }
        return candidate
    }

    private fun encodeLE32(valInt: Int, data: ByteArray, offset: Int) {
        data[offset + 0] = (valInt and 0xFF).toByte()
        data[offset + 1] = ((valInt shr 8) and 0xFF).toByte()
        data[offset + 2] = ((valInt shr 16) and 0xFF).toByte()
        data[offset + 3] = ((valInt shr 24) and 0xFF).toByte()
    }

    private fun encodeLE16(valInt: Int, data: ByteArray, offset: Int) {
        data[offset + 0] = (valInt and 0xFF).toByte()
        data[offset + 1] = ((valInt shr 8) and 0xFF).toByte()
    }

    private data class ChapterRecord(
        val title: String,
        val bdtOffset: Int,
        val bdtLength: Int
    )
}
