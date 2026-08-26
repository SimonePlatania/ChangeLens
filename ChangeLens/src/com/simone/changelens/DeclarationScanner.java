package com.simone.changelens;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Riconosce le dichiarazioni di metodo/funzione con una scansione lineare.
 * Niente espressioni regolari: su righe lunghe il backtracking del motore
 * regex e ricorsivo e puo arrivare a StackOverflowError, che e esattamente
 * cio che questo plug-in non deve mai provocare.
 */
final class DeclarationScanner {

    private static final int MAX_LINE_LENGTH = 500;
    private static final int MAX_BODY_LINES = 4000;

    private DeclarationScanner() { }

    static Map<Integer, MethodLens> scan(String document) {
        if (document == null || document.isEmpty()) return Collections.emptyMap();
        String[] lines = document.split("\\r?\\n", -1);
        Map<Integer, MethodLens> result = new HashMap<Integer, MethodLens>();
        for (int line = 0; line < lines.length; line++) {
            String text = lines[line];
            if (text.length() > MAX_LINE_LENGTH || !isDeclaration(text)) continue;
            result.put(Integer.valueOf(line), body(lines, line));
        }
        return result;
    }

    /**
     * Una dichiarazione e una riga che, ignorando stringhe e commenti, contiene
     * una lista di parametri bilanciata seguita da una graffa o da una freccia,
     * con un identificatore subito prima della parentesi aperta.
     */
    private static boolean isDeclaration(String text) {
        int open = -1;
        int depth = 0;
        int close = -1;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '/' && i + 1 < text.length() && (text.charAt(i + 1) == '/' || text.charAt(i + 1) == '*')) break;
            if (c == ';') return false;
            if (c == '(') {
                if (depth == 0) { if (open >= 0) return false; open = i; }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) return false;
                if (depth == 0) close = i;
            }
        }
        if (open <= 0 || close < 0 || depth != 0) return false;
        if (!isIdentifierEnd(text, open)) return false;

        String tail = text.substring(close + 1).trim();
        if (tail.startsWith("{") || tail.startsWith("=>")) return true;
        if (tail.isEmpty()) return false;
        // "throws IOException {" e simili
        return tail.endsWith("{");
    }

    /** L'identificatore del metodo deve stare a ridosso della parentesi aperta. */
    private static boolean isIdentifierEnd(String text, int open) {
        int i = open - 1;
        while (i >= 0 && text.charAt(i) == ' ') i--;
        if (i < 0) return false;
        char c = text.charAt(i);
        if (!Character.isJavaIdentifierPart(c)) return false;
        int end = i;
        while (i >= 0 && Character.isJavaIdentifierPart(text.charAt(i))) i--;
        String name = text.substring(i + 1, end + 1);
        if (isControlKeyword(name)) return false;
        // deve esserci qualcosa prima del nome: tipo, modificatore, function, =, :
        return i >= 0 || Character.isJavaIdentifierStart(name.charAt(0));
    }

    private static boolean isControlKeyword(String name) {
        return "if".equals(name) || "for".equals(name) || "while".equals(name)
                || "switch".equals(name) || "catch".equals(name) || "synchronized".equals(name)
                || "return".equals(name) || "case".equals(name) || "do".equals(name)
                || "else".equals(name) || "new".equals(name);
    }

    /** Trova la graffa di chiusura del corpo, con un tetto sul numero di righe. */
    private static MethodLens body(String[] lines, int declaration) {
        int balance = 0;
        boolean opened = false;
        boolean inBlockComment = false;
        int limit = Math.min(lines.length, declaration + MAX_BODY_LINES);
        for (int at = declaration; at < limit; at++) {
            String text = lines[at];
            char quote = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
                if (inBlockComment) {
                    if (c == '*' && next == '/') { inBlockComment = false; i++; }
                    continue;
                }
                if (quote != 0) {
                    if (c == '\\') i++;
                    else if (c == quote) quote = 0;
                    continue;
                }
                if (c == '/' && next == '/') break;
                if (c == '/' && next == '*') { inBlockComment = true; i++; continue; }
                if (c == '"' || c == '\'') { quote = c; continue; }
                if (c == '{') { opened = true; balance++; }
                else if (c == '}') balance--;
            }
            if (opened && balance <= 0) return new MethodLens(declaration, at, true);
            if (!opened && at > declaration && lines[at].trim().endsWith(";")) {
                // arrow function o dichiarazione senza corpo a graffe
                return new MethodLens(declaration, at, true);
            }
        }
        return new MethodLens(declaration, Math.max(declaration, limit - 1), false);
    }
}
