package com.simone.changelens;

/** Etichetta autore: {@code Nome}, {@code Nome+N}, {@code Nome*}, {@code new*}. */
final class AuthorLabel {
    static final AuthorLabel PENDING = new AuthorLabel("", 0, false, false);

    final String name;
    final int additionalAuthors;
    final boolean dirty;
    final boolean brandNew;

    AuthorLabel(String name, int additionalAuthors, boolean dirty, boolean brandNew) {
        this.name = name == null ? "" : name;
        this.additionalAuthors = additionalAuthors;
        this.dirty = dirty;
        this.brandNew = brandNew;
    }

    boolean isPending() {
        return this == PENDING;
    }

    /**
     * {@code dirtyNow} arriva dal diff corrente invece che dal blame: cosi
     * l'asterisco compare gia mentre si scrive, senza aspettare il ricalcolo.
     */
    String render(boolean initialsOnly, boolean dirtyNow) {
        StringBuilder text = new StringBuilder();
        text.append(brandNew || !initialsOnly ? name : initials(name));
        if (additionalAuthors > 0) text.append('+').append(additionalAuthors);
        if (dirty || dirtyNow) text.append('*');
        return text.toString();
    }

    private static String initials(String value) {
        StringBuilder out = new StringBuilder();
        for (String word : value.trim().split("[ \t]+")) {
            if (!word.isEmpty()) out.append(Character.toUpperCase(word.charAt(0)));
        }
        return out.length() == 0 ? "?" : out.toString();
    }
}
