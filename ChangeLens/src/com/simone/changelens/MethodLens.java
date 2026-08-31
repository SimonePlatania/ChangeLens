package com.simone.changelens;

/** A declaration recognised in the document, together with its body. */
final class MethodLens {
    final int declarationLine;
    final int endLine;
    final boolean structurallyComplete;

    MethodLens(int declarationLine, int endLine, boolean structurallyComplete) {
        this.declarationLine = declarationLine;
        this.endLine = endLine;
        this.structurallyComplete = structurallyComplete;
    }
}
