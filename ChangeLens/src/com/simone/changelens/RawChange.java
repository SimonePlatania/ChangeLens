package com.simone.changelens;

/**
 * The raw result of the diff, still expressed in line numbers.
 * It lives only long enough to travel from the analysis Job to the UI thread,
 * where it becomes a {@link ChangeBlock} anchored to the document.
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
