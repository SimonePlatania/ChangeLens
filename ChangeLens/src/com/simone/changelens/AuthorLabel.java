package com.simone.changelens;

/** Author label: {@code Name}, {@code Name+N}, {@code Name*}, {@code not committed yet*}. */
final class AuthorLabel {
    static final AuthorLabel PENDING = new AuthorLabel("", 0, false, false);

    /**
     * Code that does not exist in HEAD yet: it has no author to show, and
     * calling it "yours" would be a guess. All it states is that it has not
     * been committed.
     */
    static final String NOT_COMMITTED = "not committed yet";

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

    static AuthorLabel notCommitted() {
        return new AuthorLabel(NOT_COMMITTED, 0, true, true);
    }

    boolean isPending() {
        return this == PENDING;
    }

    /** No author: the notice alone, with no person icon and no {@code +N}. */
    boolean isNotCommitted() {
        return brandNew && NOT_COMMITTED.equals(name);
    }

    /**
     * {@code dirtyNow} comes from the current diff rather than from the blame:
     * that way the asterisk shows up while typing, without waiting for the
     * recomputation.
     */
    String render(boolean initialsOnly, boolean dirtyNow) {
        if (isNotCommitted()) return NOT_COMMITTED + "*";
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
