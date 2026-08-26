package com.simone.changelens;

/**
 * Risultato grezzo del diff, ancora espresso in numeri di riga.
 * Vive solo il tempo di passare dal Job di analisi al thread UI, dove viene
 * trasformato in {@link ChangeBlock} ancorato al documento.
 */
final class RawChange {

    final int kind;
    final int startLine;
    final int endLine;
    final String original;

    RawChange(int kind, int startLine, int endLine, String original) {
        this.kind = kind;
        this.startLine = startLine;
        this.endLine = endLine;
        this.original = original == null ? "" : original;
    }
}
