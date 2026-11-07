package org.bible.reading.audio.strategy

import org.bible.reading.dto.Bible

/**
 * Normalizes book names, abbreviations, and numerical book indices
 * from audio file names to the corresponding [Bible] enum.
 */
object BibleBookMatcher {

    private val aliasMap: Map<String, Bible> = buildMap {
        for (b in Bible.entries) {
            // Canonical book name (e.g. "Genesis", "1 John")
            put(normalizeKey(b.bookName), b)
            // Enum name (e.g. "GENESIS", "FIRST_JOHN")
            put(normalizeKey(b.name), b)
            // Order number as string (e.g. "1", "01", "40")
            put(b.order.toString(), b)
            put(b.order.toString().padStart(2, '0'), b)
        }

        // Standard abbreviations
        val abbreviations = mapOf(
            "gen" to Bible.GENESIS, "gn" to Bible.GENESIS,
            "exo" to Bible.EXODUS, "exod" to Bible.EXODUS, "ex" to Bible.EXODUS,
            "lev" to Bible.LEVITICUS, "lv" to Bible.LEVITICUS,
            "num" to Bible.NUMBERS, "nm" to Bible.NUMBERS,
            "deu" to Bible.DEUTERONOMY, "deut" to Bible.DEUTERONOMY, "dt" to Bible.DEUTERONOMY,
            "jos" to Bible.JOSHUA, "josh" to Bible.JOSHUA,
            "jdg" to Bible.JUDGES, "judg" to Bible.JUDGES, "jud" to Bible.JUDGES,
            "rut" to Bible.RUTH, "rth" to Bible.RUTH,
            "1sa" to Bible.FIRST_SAMUEL, "1sam" to Bible.FIRST_SAMUEL, "1samuel" to Bible.FIRST_SAMUEL,
            "2sa" to Bible.SECOND_SAMUEL, "2sam" to Bible.SECOND_SAMUEL, "2samuel" to Bible.SECOND_SAMUEL,
            "1ki" to Bible.FIRST_KINGS, "1kgs" to Bible.FIRST_KINGS, "1kings" to Bible.FIRST_KINGS,
            "2ki" to Bible.SECOND_KINGS, "2kgs" to Bible.SECOND_KINGS, "2kings" to Bible.SECOND_KINGS,
            "1ch" to Bible.FIRST_CHRONICLES, "1chr" to Bible.FIRST_CHRONICLES, "1chronicles" to Bible.FIRST_CHRONICLES,
            "2ch" to Bible.SECOND_CHRONICLES, "2chr" to Bible.SECOND_CHRONICLES, "2chronicles" to Bible.SECOND_CHRONICLES,
            "ezr" to Bible.EZRA, "ezra" to Bible.EZRA,
            "neh" to Bible.NEHEMIAH,
            "est" to Bible.ESTHER, "esth" to Bible.ESTHER,
            "job" to Bible.JOB,
            "ps" to Bible.PSALMS, "psa" to Bible.PSALMS, "psalm" to Bible.PSALMS, "psalms" to Bible.PSALMS, "pss" to Bible.PSALMS,
            "pro" to Bible.PROVERBS, "prv" to Bible.PROVERBS, "prov" to Bible.PROVERBS, "proverbs" to Bible.PROVERBS,
            "ecc" to Bible.ECCLESIASTES, "eccl" to Bible.ECCLESIASTES, "qoh" to Bible.ECCLESIASTES,
            "sng" to Bible.SONG_OF_SOLOMON, "sos" to Bible.SONG_OF_SOLOMON, "song" to Bible.SONG_OF_SOLOMON,
            "songofsolomon" to Bible.SONG_OF_SOLOMON, "songofsongs" to Bible.SONG_OF_SOLOMON, "canticles" to Bible.SONG_OF_SOLOMON,
            "isa" to Bible.ISAIAH, "is" to Bible.ISAIAH,
            "jer" to Bible.JEREMIAH,
            "lam" to Bible.LAMENTATIONS,
            "eze" to Bible.EZEKIEL, "ezek" to Bible.EZEKIEL,
            "dan" to Bible.DANIEL,
            "hos" to Bible.HOSEA,
            "joe" to Bible.JOEL, "jol" to Bible.JOEL,
            "amo" to Bible.AMOS, "am" to Bible.AMOS,
            "oba" to Bible.OBADIAH, "obad" to Bible.OBADIAH,
            "jon" to Bible.JONAH,
            "mic" to Bible.MICAH,
            "nah" to Bible.NAHUM,
            "hab" to Bible.HABAKKUK,
            "zep" to Bible.ZEPHANIAH, "zeph" to Bible.ZEPHANIAH,
            "hag" to Bible.HAGGAI,
            "zec" to Bible.ZECHARIAH, "zech" to Bible.ZECHARIAH,
            "mal" to Bible.MALACHI,
            "mat" to Bible.MATTHEW, "matt" to Bible.MATTHEW, "mt" to Bible.MATTHEW,
            "mrk" to Bible.MARK, "mar" to Bible.MARK, "mk" to Bible.MARK,
            "luk" to Bible.LUKE, "lk" to Bible.LUKE,
            "joh" to Bible.JOHN, "jn" to Bible.JOHN,
            "act" to Bible.ACTS, "acts" to Bible.ACTS,
            "rom" to Bible.ROMANS, "ro" to Bible.ROMANS,
            "1co" to Bible.FIRST_CORINTHIANS, "1cor" to Bible.FIRST_CORINTHIANS, "1corinthians" to Bible.FIRST_CORINTHIANS,
            "2co" to Bible.SECOND_CORINTHIANS, "2cor" to Bible.SECOND_CORINTHIANS, "2corinthians" to Bible.SECOND_CORINTHIANS,
            "gal" to Bible.GALATIANS,
            "eph" to Bible.EPHESIANS,
            "phi" to Bible.PHILIPPIANS, "php" to Bible.PHILIPPIANS, "phil" to Bible.PHILIPPIANS,
            "col" to Bible.COLOSSIANS,
            "1th" to Bible.FIRST_THESSALONIANS, "1thess" to Bible.FIRST_THESSALONIANS, "1thessalonians" to Bible.FIRST_THESSALONIANS,
            "2th" to Bible.SECOND_THESSALONIANS, "2thess" to Bible.SECOND_THESSALONIANS, "2thessalonians" to Bible.SECOND_THESSALONIANS,
            "1ti" to Bible.FIRST_TIMOTHY, "1tim" to Bible.FIRST_TIMOTHY, "1timothy" to Bible.FIRST_TIMOTHY,
            "2ti" to Bible.SECOND_TIMOTHY, "2tim" to Bible.SECOND_TIMOTHY, "2timothy" to Bible.SECOND_TIMOTHY,
            "tit" to Bible.TITUS,
            "phm" to Bible.PHILEMON, "phlm" to Bible.PHILEMON,
            "heb" to Bible.HEBREWS,
            "jas" to Bible.JAMES, "jam" to Bible.JAMES, "jm" to Bible.JAMES,
            "1pe" to Bible.FIRST_PETER, "1pet" to Bible.FIRST_PETER, "1peter" to Bible.FIRST_PETER,
            "2pe" to Bible.SECOND_PETER, "2pet" to Bible.SECOND_PETER, "2peter" to Bible.SECOND_PETER,
            "1jn" to Bible.FIRST_JOHN, "1joh" to Bible.FIRST_JOHN, "1john" to Bible.FIRST_JOHN,
            "2jn" to Bible.SECOND_JOHN, "2joh" to Bible.SECOND_JOHN, "2john" to Bible.SECOND_JOHN,
            "3jn" to Bible.THIRD_JOHN, "3joh" to Bible.THIRD_JOHN, "3john" to Bible.THIRD_JOHN,
            "jude" to Bible.JUDE, "jde" to Bible.JUDE,
            "rev" to Bible.REVELATION, "revelation" to Bible.REVELATION, "apocalypse" to Bible.REVELATION
        )

        for ((k, v) in abbreviations) {
            put(normalizeKey(k), v)
        }
    }

    /**
     * Attempts to find a matching [Bible] enum from a string representation.
     */
    fun matchBook(raw: String): Bible? {
        val clean = normalizeKey(raw)
        // 1. Direct map lookup
        aliasMap[clean]?.let { return it }

        // 2. Try prefix matching
        if (clean.length >= 3) {
            val prefixMatch = aliasMap.entries.firstOrNull { it.key.startsWith(clean) || clean.startsWith(it.key) }
            if (prefixMatch != null) return prefixMatch.value
        }

        // 3. Fallback to Bible.fromBookName
        return Bible.fromBookName(raw.trim())
    }

    private fun normalizeKey(raw: String): String {
        return raw.lowercase()
            .replace("first", "1")
            .replace("second", "2")
            .replace("third", "3")
            .replace("i_", "1")
            .replace("ii_", "2")
            .replace("iii_", "3")
            .replace(Regex("""[^a-z0-9]"""), "")
    }
}
