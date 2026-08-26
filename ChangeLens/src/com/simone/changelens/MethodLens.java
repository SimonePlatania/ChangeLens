package com.simone.changelens;

/** Una dichiarazione riconosciuta nel documento, con il suo corpo. */
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
