package com.simone.changelens;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.blame.BlameGenerator;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * An immutable snapshot of the comparison between one exact in-memory version
 * of the document and HEAD. Every instance is good for a single generation:
 * when the document changes it is closed and rebuilt, so no result can stay
 * attached to lines that have moved in the meantime.
 */
final class GitDocumentModel implements AutoCloseable {

    private final Repository repository;
    private final String path;
    private final ObjectId head;
    private final RawText committed;
    private final RawText current;
    private final EditList edits;
    private final boolean tracked;
    private final Map<Integer, AuthorLabel> authorCache = new HashMap<Integer, AuthorLabel>();

    /** The name JGit uses for uncommitted lines: a placeholder, not an author. */
    private static final String JGIT_UNCOMMITTED = "Not Committed Yet";

    private BlameGenerator generator;
    private BlameResult blame;
    private boolean blameFailed;
    private boolean closed;

    static GitDocumentModel create(IFile file, String document) throws Exception {
        RepositoryMapping mapping = RepositoryMapping.getMapping(file);
        if (mapping == null) return null;
        Repository repository = mapping.getRepository();
        String path = mapping.getRepoRelativePath(file);
        if (repository == null || path == null) return null;

        Charset charset = charsetOf(file);
        RawText current = new RawText(document.getBytes(charset));
        RawText committed = RawText.EMPTY_TEXT;
        boolean tracked = false;
        ObjectId head = repository.resolve(Constants.HEAD);
        if (head != null) {
            TreeWalk walk = TreeWalk.forPath(repository, path, repository.parseCommit(head).getTree());
            if (walk != null) {
                try {
                    ObjectLoader loader = repository.open(walk.getObjectId(0), Constants.OBJ_BLOB);
                    committed = new RawText(loader.getBytes());
                    tracked = true;
                } finally {
                    walk.close();
                }
            }
        }
        // WS_IGNORE_TRAILING: CRLF against LF and trailing spaces must not make
        // a file that is identical to HEAD look modified.
        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.WS_IGNORE_TRAILING, committed, current);
        return new GitDocumentModel(repository, path, head, committed, current, edits, tracked);
    }

    private static Charset charsetOf(IFile file) {
        try {
            String name = file.getCharset();
            if (name != null) return Charset.forName(name);
        } catch (Exception ignored) {
            // resource unavailable or charset unknown
        }
        return StandardCharsets.UTF_8;
    }

    private GitDocumentModel(Repository repository, String path, ObjectId head, RawText committed,
            RawText current, EditList edits, boolean tracked) {
        this.repository = repository;
        this.path = path;
        this.head = head;
        this.committed = committed;
        this.current = current;
        this.edits = edits;
        this.tracked = tracked;
    }

    /**
     * The contiguous change blocks, with exact first and last lines. Committed
     * and untouched lines produce no block at all: nothing is drawn for them.
     */
    List<RawChange> changes() {
        List<RawChange> blocks = new ArrayList<RawChange>();
        // A file never committed: with no version in HEAD there is no
        // comparison, and painting the whole file green would say nothing.
        if (!tracked) return blocks;
        int lastLine = Math.max(0, current.size() - 1);
        for (Edit edit : edits) {
            String original = textOf(committed, edit.getBeginA(), edit.getEndA());
            if (edit.getLengthB() == 0) {
                int anchor = Math.min(Math.max(0, edit.getBeginB()), lastLine);
                blocks.add(new RawChange(ChangeBlock.DELETED, anchor, anchor, original));
                continue;
            }
            if (edit.getLengthA() == 0) {
                blocks.add(new RawChange(ChangeBlock.ADDED, edit.getBeginB(), edit.getEndB() - 1,
                        original));
                continue;
            }
            // An edit replacing few lines with many is two different things put
            // together: the first lines rewrite what was there, the rest are
            // new. Keeping it whole gave a single colour to a block half
            // modified and half added; split, the new lines come out green and
            // only the rewritten ones take the rewrite colour, which is what
            // every other tool shows.
            int shared = Math.min(edit.getLengthA(), edit.getLengthB());
            int firstNew = edit.getBeginB() + shared;
            blocks.add(new RawChange(ChangeBlock.MODIFIED, edit.getBeginB(), firstNew - 1, original));
            if (firstNew <= edit.getEndB() - 1) {
                blocks.add(new RawChange(ChangeBlock.ADDED, firstNew, edit.getEndB() - 1, ""));
            }
        }
        return blocks;
    }

    private static String textOf(RawText text, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int line = from; line < to && line < text.size(); line++) {
            if (out.length() > 0) out.append('\n');
            out.append(text.getString(line));
        }
        return out.toString();
    }

    boolean isTracked() {
        return tracked;
    }

    /** The author label for a declaration. To be called off the UI thread. */
    synchronized AuthorLabel author(MethodLens method) {
        if (closed || !tracked) return AuthorLabel.PENDING;
        Integer key = Integer.valueOf(method.declarationLine);
        AuthorLabel cached = authorCache.get(key);
        if (cached != null) return cached;

        AuthorLabel value;
        try {
            value = resolve(method);
        } catch (Exception failure) {
            Activator.log(failure);
            value = AuthorLabel.PENDING;
        }
        if (!value.isPending()) authorCache.put(key, value);
        return value;
    }

    /**
     * Who wrote this declaration.
     *
     * There is a single criterion: how many of the method's lines already exist
     * in HEAD. If not one of them does, the method has just been written and
     * has no author in the history yet: {@code not committed yet}. If even a
     * single line comes from HEAD then an author exists and the blame is the
     * one to ask, even when the signature line is among the just-touched ones:
     * renaming a parameter does not turn the method into someone else's code.
     *
     * When the author cannot be established nothing is invented and nothing is
     * cached: the label stays absent and the next round tries again.
     */
    private AuthorLabel resolve(MethodLens method) throws Exception {
        // A declaration still open, a body with no closing brace: the body the
        // scanner attributes to it runs to the end of the file and would borrow
        // the lines, and the authors, of the methods below. Until it is closed,
        // nothing is said.
        if (!method.structurallyComplete) return AuthorLabel.PENDING;
        boolean dirty = overlaps(method.declarationLine, method.endLine);
        int anchor = -1;
        int min = Integer.MAX_VALUE;
        int max = -1;
        for (int line = method.declarationLine; line <= method.endLine; line++) {
            int mapped = oldLineFor(line);
            if (mapped < 0) continue;
            if (anchor < 0) anchor = mapped;
            min = Math.min(min, mapped);
            max = Math.max(max, mapped);
        }
        if (anchor < 0) return AuthorLabel.notCommitted();

        ensureBlame();
        if (blame == null) return AuthorLabel.PENDING;
        min = Math.max(0, min);
        max = Math.min(max, committed.size() - 1);
        if (min > max) return AuthorLabel.notCommitted();
        blame.computeRange(min, max + 1);

        PersonIdent owner = blame.getSourceAuthor(anchor);
        if (owner == null || owner.getName() == null) return AuthorLabel.PENDING;

        String primary = owner.getName();
        // JGit's fictitious author for lines not yet in a commit: it is a
        // placeholder, not a person, and must never be shown as an author.
        if (JGIT_UNCOMMITTED.equals(primary)) return AuthorLabel.notCommitted();
        Set<String> others = new LinkedHashSet<String>();
        for (int line = method.declarationLine; line <= method.endLine; line++) {
            int mapped = oldLineFor(line);
            if (mapped < min || mapped > max) continue;
            PersonIdent contributor = blame.getSourceAuthor(mapped);
            if (contributor != null && contributor.getName() != null
                    && !primary.equals(contributor.getName())
                    && !JGIT_UNCOMMITTED.equals(contributor.getName())) {
                others.add(contributor.getName());
            }
        }
        return new AuthorLabel(primary, others.size(), dirty, false);
    }

    private void ensureBlame() throws Exception {
        if (blame != null || blameFailed || closed) return;
        try {
            // Blame on HEAD, not on the working tree: the lines being compared
            // are indices inside the committed version, and prepareHead() would
            // have started the blame from the file on disk. That is where the
            // out-of-step lines and the fictitious "Not Committed Yet" author
            // came from.
            generator = new BlameGenerator(repository, path);
            generator.setFollowFileRenames(true);
            generator.push(null, head);
            blame = generator.computeBlameResult();
        } catch (Exception failure) {
            blameFailed = true;
            throw failure;
        }
    }

    /** The matching line in HEAD, or -1 when the line is new or modified. */
    private int oldLineFor(int newLine) {
        int delta = 0;
        for (Edit edit : edits) {
            if (edit.getBeginB() > newLine) break;
            if (newLine < edit.getEndB()) return -1;
            delta += edit.getLengthA() - edit.getLengthB();
        }
        int mapped = newLine + delta;
        return mapped >= 0 && mapped < committed.size() ? mapped : -1;
    }

    private boolean overlaps(int from, int to) {
        for (Edit edit : edits) {
            int begin = edit.getBeginB();
            int end = Math.max(begin, edit.getEndB() - 1);
            if (begin <= to && end >= from) return true;
        }
        return false;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (generator != null) {
            try {
                generator.close();
            } catch (Exception ignored) {
                // nothing to recover while closing down
            }
        }
        generator = null;
        blame = null;
        authorCache.clear();
    }
}
