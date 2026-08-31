package com.simone.changelens;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;

/**
 * A contiguous block of lines that differ from HEAD.
 *
 * The block keeps no line numbers: it keeps a {@link Position} registered with
 * the document. The document updates it on its own at every edit, so the block
 * stays glued to the code it belongs to and never moves because of a scroll or
 * of a line inserted further up.
 */
final class ChangeBlock {

    static final int ADDED = 1;
    static final int MODIFIED = 2;
    /** Deletion: no line occupied, the mark sits on the boundary. */
    static final int DELETED = 3;
    /** Existing lines rewritten and others added within the same block. */
    static final int MIXED = 4;

    final int kind;
    final String original;
    private final Position position;

    ChangeBlock(int kind, Position position, String original) {
        this.kind = kind;
        this.position = position;
        this.original = original == null ? "" : original;
    }

    Position position() {
        return position;
    }

    boolean isValid() {
        return !position.isDeleted();
    }

    int startLine(IDocument document) {
        try {
            return document.getLineOfOffset(position.getOffset());
        } catch (BadLocationException ignored) {
            return -1;
        }
    }

    int endLine(IDocument document) {
        try {
            int length = Math.max(0, position.getLength() - 1);
            return document.getLineOfOffset(position.getOffset() + length);
        } catch (BadLocationException ignored) {
            return -1;
        }
    }

    /** Builds the Position covering the given lines, both ends included. */
    static Position positionFor(IDocument document, int startLine, int endLine, boolean deletion) {
        try {
            int lastLine = document.getNumberOfLines() - 1;
            int start = Math.min(Math.max(0, startLine), lastLine);
            int end = Math.min(Math.max(start, endLine), lastLine);
            IRegion first = document.getLineInformation(start);
            if (deletion) return new Position(first.getOffset(), 0);
            IRegion last = document.getLineInformation(end);
            // The last line's delimiter belongs to the block: without it, a
            // line emptied out (backspace over the indentation) would leave the
            // end of the Position on the previous line, and the bar would lose
            // a line although none had been deleted.
            String delimiter = document.getLineDelimiter(end);
            int tail = delimiter == null ? 0 : delimiter.length();
            int length = last.getOffset() + last.getLength() + tail - first.getOffset();
            return new Position(first.getOffset(), Math.max(1, length));
        } catch (BadLocationException ignored) {
            return null;
        }
    }

    String label() {
        switch (kind) {
            case ADDED: return "Added lines";
            case MODIFIED: return "Modified lines";
            case MIXED: return "Modified and added lines";
            default: return "Deleted lines";
        }
    }
}
