package org.bible.reading.dto

import kotlin.enums.enumEntries


/*
This enum is mostly to help get v1 off the ground.

As i've dug into generalizing this for a generic "build your own reading plan" tool, I'm
having a bit of trouble making sure the mental model stays good upfront.

So this enum is a shortcut since i want to focus on a Scripture reading plan first.

Which also explains the methods - they're prototypes of what a future interface might look like.
 */
enum class Bible( val bookName : String, val chapters : Int, val collection : BookCollection, val order : Int) {
    GENESIS("Genesis", 50, BookCollection.Torah, 1),
    EXODUS("Exodus", 40, BookCollection.Torah, 2),
    LEVITICUS("Leviticus",27, BookCollection.Torah, 3),
    NUMBERS("Numbers", 36, BookCollection.Torah, 4),
    DEUTERONOMY("Deuteronomy", 34, BookCollection.Torah, 5),
    JOSHUA("Joshua", 24, BookCollection.History_1, 6),
    JUDGES("Judges", 21, BookCollection.History_1, 7),
    RUTH("Ruth", 4, BookCollection.History_1, 8),
    FIRST_SAMUEL("1 Samuel", 31, BookCollection.History_1, 9),
    SECOND_SAMUEL("2 Samuel", 24, BookCollection.History_1, 10),
    FIRST_KINGS("1 Kings", 22, BookCollection.History_1,11),
    SECOND_KINGS("2 Kings", 25, BookCollection.History_1, 12),
    FIRST_CHRONICLES("1 Chronicles", 29, BookCollection.History_2, 13),
    SECOND_CHRONICLES("2 Chronicles", 36, BookCollection.History_2, 14),
    EZRA("Ezra", 10, BookCollection.History_2,15),
    NEHEMIAH("Nehemiah", 13, BookCollection.History_2, 16),
    ESTHER("Esther", 10, BookCollection.History_2, 17),
    JOB("Job", 42, BookCollection.History_2, 18),
    PSALMS("Psalms", 150, BookCollection.Wisdom, 19),
    PROVERBS("Proverbs", 31, BookCollection.Wisdom,20),
    ECCLESIASTES("Ecclesiastes", 12, BookCollection.Wisdom, 21),
    SONG_OF_SOLOMON("Song of Solomon", 8, BookCollection.Wisdom,22),
    ISAIAH("Isaiah", 66, BookCollection.Prophets, 23),
    JEREMIAH("Jeremiah", 52, BookCollection.Prophets, 24),
    LAMENTATIONS("Lamentations", 5, BookCollection.Prophets,25),
    EZEKIEL("Ezekiel", 48, BookCollection.Prophets,26),
    DANIEL("Daniel", 12, BookCollection.Prophets, 27),
    HOSEA("Hosea", 14, BookCollection.Prophets, 28),
    JOEL("Joel", 3, BookCollection.Prophets, 29),
    AMOS("Amos", 9, BookCollection.Prophets,30),
    OBADIAH("Obadiah", 1, BookCollection.Prophets,31),
    JONAH("Jonah", 4, BookCollection.Prophets, 32),
    MICAH("Micah", 7, BookCollection.Prophets,33),
    NAHUM("Nahum", 3, BookCollection.Prophets,34),
    HABAKKUK("Habakkuk", 3, BookCollection.Prophets, 35),
    ZEPHANIAH("Zephaniah", 3, BookCollection.Prophets,36),
    HAGGAI("Haggai", 2, BookCollection.Prophets,37),
    ZECHARIAH("Zechariah", 14, BookCollection.Prophets,38),
    MALACHI("Malachi", 4, BookCollection.Prophets,39),
    MATTHEW("Matthew", 28, BookCollection.Gospels_Acts, 40),
    MARK("Mark", 16, BookCollection.Gospels_Acts, 41),
    LUKE("Luke", 24, BookCollection.Gospels_Acts, 42),
    JOHN("John", 21, BookCollection.Gospels_Acts, 43),
    ACTS("Acts", 28, BookCollection.Gospels_Acts,44),
    ROMANS("Romans", 16, BookCollection.Epistles,45),
    FIRST_CORINTHIANS("1 Corinthians", 16, BookCollection.Epistles, 46),
    SECOND_CORINTHIANS("2 Corinthians", 13, BookCollection.Epistles, 47),
    GALATIANS("Galatians", 6, BookCollection.Epistles, 48),
    EPHESIANS("Ephesians", 6, BookCollection.Epistles, 49),
    PHILIPPIANS("Philippians", 4, BookCollection.Epistles, 50),
    COLOSSIANS("Colossians", 4, BookCollection.Epistles,51),
    FIRST_THESSALONIANS("1 Thessalonians", 5, BookCollection.Epistles,52),
    SECOND_THESSALONIANS("2 Thessalonians", 3, BookCollection.Epistles, 53),
    FIRST_TIMOTHY("1 Timothy", 6, BookCollection.Epistles,54),
    SECOND_TIMOTHY("2 Timothy", 4, BookCollection.Epistles,55),
    TITUS("Titus", 3,BookCollection.Epistles, 56),
    PHILEMON("Philemon", 1, BookCollection.Epistles,57),
    HEBREWS("Hebrews", 13, BookCollection.Epistles, 58),
    JAMES("James", 5, BookCollection.Epistles,59),
    FIRST_PETER("1 Peter", 5, BookCollection.Epistles,60),
    SECOND_PETER("2 Peter", 3, BookCollection.Epistles, 61),
    FIRST_JOHN("1 John", 5, BookCollection.Epistles, 62),
    SECOND_JOHN("2 John", 1, BookCollection.Epistles, 63),
    THIRD_JOHN("3 John", 1, BookCollection.Epistles,64),
    JUDE("Jude", 1, BookCollection.Epistles,65),
    REVELATION("Revelation", 22, BookCollection.Epistles,66);

companion object {
    fun getTotalChapters() : Int {
        return 1189
    }

    fun getBooksInOrder() : List<Bible> {
        return enumEntries<Bible>().sortedBy { b -> b.order }
    }

}



}