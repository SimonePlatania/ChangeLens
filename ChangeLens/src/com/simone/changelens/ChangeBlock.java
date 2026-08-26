package com.simone.changelens;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;

/**
 * Un blocco contiguo di righe cambiate rispetto a HEAD.
 *
 * Il blocco non conserva numeri di riga: conserva una {@link Position}
 * registrata nel documento. Il documento la aggiorna da solo a ogni modifica,
 * quindi il blocco resta incollato al codice a cui appartiene e non si sposta
 * mai per effetto dello scorrimento o di una riga inserita piu in alto.
 */
final class ChangeBlock {

    static final int ADDED = 1;
    static final int MODIFIED = 2;
    /** Cancellazione: nessuna riga occupata, il segno sta sul confine. */
    static final int DELETED = 3;
    /** Righe esistenti riscritte e altre aggiunte nello stesso blocco. */
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

    /** Crea la Position che copre le righe indicate, estremi inclusi. */
    static Position positionFor(IDocument document, int startLine, int endLine, boolean deletion) {
        try {
            int lastLine = document.getNumberOfLines() - 1;
            int start = Math.min(Math.max(0, startLine), lastLine);
            int end = Math.min(Math.max(start, endLine), lastLine);
            IRegion first = document.getLineInformation(start);
            if (deletion) return new Position(first.getOffset(), 0);
            IRegion last = document.getLineInformation(end);
            // Il terminatore dell'ultima riga fa parte del blocco: senza, una
            // riga svuotata (backspace sull'indentazione) lascerebbe la fine
            // della Position sulla riga precedente e la barra perderebbe una
            // riga pur non essendone stata cancellata nessuna.
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
            case ADDED: return "Righe aggiunte";
            case MODIFIED: return "Righe modificate";
            case MIXED: return "Righe modificate e aggiunte";
            default: return "Righe eliminate";
        }
    }
}
