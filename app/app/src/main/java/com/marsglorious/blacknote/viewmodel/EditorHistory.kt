package com.marsglorious.blacknote.viewmodel

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.min

/** One editor moment: both fields together, so undo replays edits in true order. */
data class EditorSnapshot(
    val title: TextFieldValue = TextFieldValue(""),
    val body: TextFieldValue = TextFieldValue(""),
)

/**
 * Capped undo/redo stack over [EditorSnapshot] with simple coalescing — consecutive
 * single-character insertions inside a 600ms window share one history entry, so the
 * user gets word-grained undos instead of one-per-keystroke.
 *
 * Snapshotting title+body together fixes the old two-stack design where undo always
 * drained the body stack before the title stack, regardless of which field was
 * actually edited last.
 */
class EditorHistory(private val limit: Int = 100) {
    private val undo: ArrayDeque<EditorSnapshot> = ArrayDeque()
    private val redo: ArrayDeque<EditorSnapshot> = ArrayDeque()
    private var lastPushAt: Long = 0L
    private var current: EditorSnapshot = EditorSnapshot()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun reset(initial: EditorSnapshot) {
        undo.clear(); redo.clear()
        current = initial
        lastPushAt = 0L
    }

    fun record(next: EditorSnapshot, now: Long = System.currentTimeMillis()) {
        val textChanged = next.title.text != current.title.text || next.body.text != current.body.text
        if (!textChanged) {
            current = next
            return
        }
        val delta = (next.title.text.length + next.body.text.length) -
                    (current.title.text.length + current.body.text.length)
        val changedText = if (next.body.text != current.body.text) next.body.text else next.title.text
        val coalesce = delta == 1 &&
                       (now - lastPushAt) < 600 &&
                       undo.isNotEmpty() &&
                       changedText.lastOrNull()?.isWhitespace() == false
        if (!coalesce) {
            undo.addLast(current)
            if (undo.size > limit) undo.removeFirst()
        }
        current = next
        lastPushAt = now
        redo.clear()
    }

    fun undo(): EditorSnapshot? {
        val prev = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        if (redo.size > limit) redo.removeFirst()
        current = prev
        return prev
    }

    fun redo(): EditorSnapshot? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        if (undo.size > limit) undo.removeFirst()
        current = next
        return next
    }

    fun snapshot(): EditorSnapshot = current

    companion object {
        fun safeRange(tfv: TextFieldValue): IntRange {
            val s = min(tfv.selection.start, tfv.selection.end)
            val e = kotlin.math.max(tfv.selection.start, tfv.selection.end)
            return s..e
        }
    }
}
