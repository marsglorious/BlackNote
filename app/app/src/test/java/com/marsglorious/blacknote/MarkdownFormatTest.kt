package com.marsglorious.blacknote

import com.marsglorious.blacknote.data.MarkdownFormat
import com.marsglorious.blacknote.data.FormatKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Formatting used to go through Rust `apply_format`, which had two bugs this suite
 * pins down: (1) it always wrapped, so re-tapping Bold produced `****text****`;
 * (2) it treated the Kotlin UTF-16 selection offsets as byte offsets, so any note
 * containing non-ASCII text got its markers inserted at the wrong position.
 */
class MarkdownFormatTest {

    @Test
    fun boldWrapsSelection() {
        val r = MarkdownFormat.apply("hello world", 0, 5, FormatKind.BOLD)
        assertEquals("**hello** world", r.text)
        assertEquals(2, r.selStart)
        assertEquals(7, r.selEnd)
    }

    @Test
    fun boldTogglesOffWhenSelectionIncludesMarkers() {
        val r = MarkdownFormat.apply("**hello** world", 0, 9, FormatKind.BOLD)
        assertEquals("hello world", r.text)
        assertEquals(0, r.selStart)
        assertEquals(5, r.selEnd)
    }

    @Test
    fun boldTogglesOffWhenMarkersSurroundSelection() {
        // "hello" selected inside "**hello**" — markers outside the selection.
        val r = MarkdownFormat.apply("**hello** world", 2, 7, FormatKind.BOLD)
        assertEquals("hello world", r.text)
        assertEquals(0, r.selStart)
        assertEquals(5, r.selEnd)
    }

    @Test
    fun doubleTapBoldIsIdentity() {
        val once = MarkdownFormat.apply("abc", 0, 3, FormatKind.BOLD)
        assertEquals("**abc**", once.text)
        val twice = MarkdownFormat.apply(once.text, once.selStart, once.selEnd, FormatKind.BOLD)
        assertEquals("this is the old ****text**** bug", "abc", twice.text)
    }

    @Test
    fun emojiBeforeSelectionDoesNotShiftMarkers() {
        // "🎉" is 2 UTF-16 units but 4 UTF-8 bytes — the old byte-offset path
        // inserted the markers 2 positions too far right.
        val text = "🎉 note"
        val r = MarkdownFormat.apply(text, 3, 7, FormatKind.BOLD) // "note"
        assertEquals("🎉 **note**", r.text)
    }

    @Test
    fun cjkSelectionWrapsExactly() {
        val text = "前置き 本文 後書き"
        val start = text.indexOf("本文")
        val r = MarkdownFormat.apply(text, start, start + 2, FormatKind.ITALIC)
        assertEquals("前置き _本文_ 後書き", r.text)
    }

    @Test
    fun emptySelectionInsertsPairAndPlacesCursorInside() {
        val r = MarkdownFormat.apply("ab", 1, 1, FormatKind.BOLD)
        assertEquals("a****b", r.text)
        assertEquals(3, r.selStart)
        assertEquals(3, r.selEnd)
    }

    @Test
    fun emptyPairTogglesBackOff() {
        // Cursor sitting between the just-inserted markers: tap again removes them.
        val r = MarkdownFormat.apply("a****b", 3, 3, FormatKind.BOLD)
        assertEquals("ab", r.text)
        assertEquals(1, r.selStart)
    }

    @Test
    fun underlineUsesHtmlTagAndToggles() {
        val on = MarkdownFormat.apply("word", 0, 4, FormatKind.UNDERLINE)
        assertEquals("<u>word</u>", on.text)
        val off = MarkdownFormat.apply(on.text, on.selStart, on.selEnd, FormatKind.UNDERLINE)
        assertEquals("word", off.text)
    }

    @Test
    fun strikeToggles() {
        val on = MarkdownFormat.apply("gone", 0, 4, FormatKind.STRIKE)
        assertEquals("~~gone~~", on.text)
        val off = MarkdownFormat.apply(on.text, on.selStart, on.selEnd, FormatKind.STRIKE)
        assertEquals("gone", off.text)
    }

    @Test
    fun bulletListAddsPrefixToCurrentLineWithoutInsertingNewline() {
        // The old Rust toggle_list pushed the line onto a NEW line when the text
        // before the cursor didn't end in \n.
        val r = MarkdownFormat.apply("first line", 3, 3, FormatKind.BULLET_LIST)
        assertEquals("- first line", r.text)
    }

    @Test
    fun bulletListTogglesOff() {
        val r = MarkdownFormat.apply("- item", 3, 3, FormatKind.BULLET_LIST)
        assertEquals("item", r.text)
    }

    @Test
    fun bulletListOnMultilineSelection() {
        val text = "one\ntwo\nthree"
        val r = MarkdownFormat.apply(text, 0, text.length, FormatKind.BULLET_LIST)
        assertEquals("- one\n- two\n- three", r.text)
    }

    @Test
    fun orderedListNumbersSequentially() {
        val text = "alpha\nbeta\ngamma"
        val r = MarkdownFormat.apply(text, 0, text.length, FormatKind.ORDERED_LIST)
        assertEquals("1. alpha\n2. beta\n3. gamma", r.text)
    }

    @Test
    fun orderedListTogglesOff() {
        val text = "1. alpha\n2. beta"
        val r = MarkdownFormat.apply(text, 0, text.length, FormatKind.ORDERED_LIST)
        assertEquals("alpha\nbeta", r.text)
    }

    @Test
    fun switchingBulletToOrderedConvertsInPlace() {
        val text = "- alpha\n- beta"
        val r = MarkdownFormat.apply(text, 0, text.length, FormatKind.ORDERED_LIST)
        assertEquals("1. alpha\n2. beta", r.text)
    }

    @Test
    fun blankLinesInsideSelectionAreLeftAlone() {
        val text = "one\n\ntwo"
        val r = MarkdownFormat.apply(text, 0, text.length, FormatKind.BULLET_LIST)
        assertEquals("- one\n\n- two", r.text)
    }

    // --- Cursor-on-blank-line bug: tapping list with cursor on an empty line did nothing ---

    @Test
    fun bulletList_onEmptyBody_startsItem() {
        val r = MarkdownFormat.apply("", 0, 0, FormatKind.BULLET_LIST)
        assertEquals("- ", r.text)
    }

    @Test
    fun orderedList_onEmptyBody_startsItem() {
        val r = MarkdownFormat.apply("", 0, 0, FormatKind.ORDERED_LIST)
        assertEquals("1. ", r.text)
    }

    @Test
    fun bulletList_cursorOnBlankLineAtEnd_startsItem() {
        // User types a line, presses Enter, then taps the list button on the new blank line.
        val text = "hello\n"
        val r = MarkdownFormat.apply(text, text.length, text.length, FormatKind.BULLET_LIST)
        assertEquals("hello\n- ", r.text)
    }

    @Test
    fun orderedList_cursorOnBlankLineAtEnd_startsItem() {
        val text = "hello\n"
        val r = MarkdownFormat.apply(text, text.length, text.length, FormatKind.ORDERED_LIST)
        assertEquals("hello\n1. ", r.text)
    }

    @Test
    fun bulletList_cursorOnBlankLineInMiddle_startsItem() {
        // Cursor is on the empty line between two paragraphs.
        val text = "above\n\nbelow"
        val blankPos = text.indexOf('\n') + 1   // index 6, the blank line
        val r = MarkdownFormat.apply(text, blankPos, blankPos, FormatKind.BULLET_LIST)
        assertEquals("above\n- \nbelow", r.text)
    }

    @Test
    fun outOfRangeOffsetsAreClamped() {
        val r = MarkdownFormat.apply("ab", 0, 99, FormatKind.BOLD)
        assertEquals("**ab**", r.text)
    }
}
