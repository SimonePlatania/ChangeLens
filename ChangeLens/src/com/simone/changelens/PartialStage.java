package com.simone.changelens;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Stages the lines of a single change block, leaving all the rest of the file
 * out.
 *
 * It is what committing exactly what one bar points at, and nothing else,
 * takes. Git does not know lines, it knows contents: so one does not "add lines
 * to the index", one builds the content the index would hold if, of all the
 * changes in the file, only that one had been applied, and writes it there.
 *
 * The lines come from the open editor buffer, not from the file on disk, and
 * the working tree is never touched. That is the point of doing this inside an
 * editor - it is the one thing {@code git add -p} cannot do, having no buffer -
 * and it is why the file stays dirty while its change shows up as staged.
 *
 * Writing a blob straight from the buffer bypasses Git's clean filters, so the
 * shape of what is being replaced is taken from the blob already in the index -
 * line endings, final newline, byte order mark, file mode - rather than from the
 * buffer or from any configuration. Where a filter does something this cannot
 * reproduce, as Git LFS does, the work is refused with a reason: a botched index
 * of that kind is the sort of thing one discovers days later without ever
 * connecting it to this plug-in.
 */
final class PartialStage {

    /** First bytes of an LFS pointer file, as written in the index. */
    private static final String LFS_POINTER = "version https://git-lfs";
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
    /** Past this many other staged files the exact number stops mattering. */
    private static final int OTHERS_LIMIT = 9;

    private PartialStage() { }

    /** What came of the attempt, and what to tell whoever asked for it. */
    static final class Result {

        final boolean staged;
        final String message;

        private Result(boolean staged, String message) {
            this.staged = staged;
            this.message = message;
        }

        static Result done(String message) {
            return new Result(true, message);
        }

        static Result refused(String message) {
            return new Result(false, message);
        }
    }

    /**
     * Brings into the index only the lines between {@code startLine} and
     * {@code endLine} of the open document.
     *
     * Runs Git I/O and takes the index lock: to be called off the UI thread.
     *
     * @return whether the index was updated, and the reason when it was not.
     *         Nothing at all is modified unless the result says staged.
     */
    static Result stage(IFile file, String documentText, int startLine, int endLine) {
        if (file == null || documentText == null) {
            return Result.refused("ChangeLens: no document to stage.");
        }
        try {
            RepositoryMapping mapping = RepositoryMapping.getMapping(file);
            Repository repository = mapping == null ? null : mapping.getRepository();
            String path = mapping == null ? null : mapping.getRepoRelativePath(file);
            if (repository == null || path == null) {
                return Result.refused("ChangeLens: this file is not inside a Git repository.");
            }
            Result result = write(repository, path, file, documentText, startLine, endLine);
            // The count of what else is staged walks HEAD's trees, so it waits
            // until the index lock has been let go: it is a courtesy to the
            // reader, not a reason to hold a repository-wide lock any longer.
            return result.staged
                    ? Result.done(result.message + others(repository, path)) : result;
        } catch (Exception failure) {
            Activator.log(failure);
            return Result.refused("ChangeLens: partial staging failed, see the Error Log.");
        }
    }

    /**
     * Reads the index, builds the new content and writes it, all inside the
     * index lock.
     *
     * Reading first and locking later left a window in which another process -
     * a terminal, EGit itself - could stage something on the same path, and the
     * blob built on the earlier read would then have silently wiped it. The
     * whole round trip happens under the lock instead.
     *
     * That lock is {@code .git/index.lock}, and while it is held a git command
     * in a terminal fails rather than waits, so the section has to stay short:
     * it is one blob read, one diff and one blob write, all on a single file.
     * Whatever happens, exceptions included, the lock is released on the way
     * out - an orphaned index.lock is not something a user can be expected to
     * clear up on their own. After a successful commit JGit has already let it
     * go and the release is a no-op.
     */
    private static Result write(Repository repository, String path, IFile file,
            String documentText, int startLine, int endLine) throws Exception {
        DirCache cache = repository.lockDirCache();
        try {
            DirCacheEntry entry = cache.getEntry(path);
            if (entry != null && entry.getStage() != 0) {
                return Result.refused("ChangeLens: this file is in a merge conflict, "
                        + "partial staging leaves it alone.");
            }
            byte[] indexBytes = entry == null ? new byte[0]
                    : repository.open(entry.getObjectId(), Constants.OBJ_BLOB).getBytes();
            if (isLfsPointer(indexBytes)) {
                return Result.refused("ChangeLens: this file is handled by Git LFS, "
                        + "partial staging would write the content where the pointer belongs.");
            }

            // The byte order mark comes off before the comparison and goes back
            // on after it. The editor keeps it out of the document, so leaving
            // it in would make the first line differ from the buffer's on every
            // single file that has one: a phantom hunk, forever glued to the
            // top of the file, that any block near it would drag along.
            boolean bom = startsWithBom(indexBytes);
            byte[] body = bom ? strip(indexBytes) : indexBytes;

            Charset charset = charsetOf(file);
            RawText staged = new RawText(body);
            RawText current = new RawText(documentText.getBytes(charset));
            EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                    .diff(RawTextComparator.WS_IGNORE_TRAILING, staged, current);

            Applied applied = apply(staged, current, edits, startLine, endLine);
            if (applied == null) {
                return Result.refused("ChangeLens: that block is already staged, "
                        + "or the index does not hold it. Nothing was changed.");
            }
            byte[] content = bytes(applied.content, body, bom, charset);
            // The mode comes from the entry that is already there: writing
            // REGULAR_FILE unconditionally stripped the executable bit off
            // scripts, which is the kind of damage that surfaces in CI days
            // later without ever being traced back here.
            FileMode mode = entry == null ? FileMode.REGULAR_FILE : entry.getFileMode();
            if (!store(repository, cache, path, content, mode)) {
                // Not the same thing as "already staged": there the click was a
                // harmless no-op, here the write itself did not land and the
                // gesture has to be made again.
                return Result.refused("ChangeLens: the index changed while writing, "
                        + "so nothing was staged. Try again.");
            }
            return Result.done(applied.removed
                    ? "ChangeLens: block staged, along with the lines it replaces."
                    : "ChangeLens: block staged.");
        } finally {
            cache.unlock();
        }
    }

    /**
     * What else is sitting in the index, said out loud.
     *
     * The commit that follows takes the whole index, not just this block, so
     * anything staged earlier - by this plug-in, by the Git tooling, from a
     * terminal - rides along with it. Naming it at the moment of the click is
     * what keeps the gesture honest: the alternative is a commit that quietly
     * carries more than the one bar that was pointed at.
     *
     * Counting stops at a handful. Beyond that the exact number changes
     * nothing, and the walk is over HEAD's trees, which on a large repository
     * is not free.
     */
    private static String others(Repository repository, String path) {
        try {
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null) return "";
            DirCache cache = repository.readDirCache();
            TreeWalk walk = new TreeWalk(repository);
            try {
                walk.addTree(repository.parseCommit(head).getTree());
                walk.addTree(new DirCacheIterator(cache));
                walk.setRecursive(true);
                int count = 0;
                while (walk.next() && count <= OTHERS_LIMIT) {
                    if (path.equals(walk.getPathString())) continue;
                    if (!walk.idEqual(0, 1)) count++;
                }
                if (count == 0) return "";
                if (count == 1) return " One other file is staged as well.";
                return count > OTHERS_LIMIT
                        ? " " + OTHERS_LIMIT + "+ other files are staged as well."
                        : " " + count + " other files are staged as well.";
            } finally {
                walk.close();
            }
        } catch (Exception ignored) {
            // the count is a courtesy: never let it get in the way of the stage
            return "";
        }
    }

    /**
     * How the content being replaced ends its lines, LF or CRLF.
     *
     * Git normally decides this with the clean filter, out of {@code autocrlf},
     * {@code core.eol} and .gitattributes. Reading the blob itself settles the
     * same question per file and without consulting any of them: whatever the
     * index already holds for this path is what has to go back in.
     *
     * Refusing to work when {@code autocrlf} was on, as this used to, was the
     * wrong call twice over. It is the default configuration of Git on Windows,
     * so it disabled the feature for most of the people using it - and the
     * filter it was afraid of is nothing more than this substitution.
     */
    private static boolean usesCrlf(byte[] body) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < body.length; i++) {
            if (body[i] != '\n') continue;
            if (i > 0 && body[i - 1] == '\r') crlf++;
            else lf++;
        }
        return crlf > lf;
    }

    /** The text with every line ending brought to the one the index uses. */
    private static String matchLineEndings(String content, boolean crlf) {
        String normalised = content.replace("\r\n", "\n");
        // RawText hands CRLF lines back with the CR still attached, so joining
        // them leaves one stray CR at the very end: that is the first half of a
        // terminator, not content, and putting a terminator after it produced a
        // doubled carriage return.
        if (normalised.endsWith("\r")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return crlf ? normalised.replace("\n", "\r\n") : normalised;
    }

    /** The index holds a pointer, not the file: only the LFS filter can write here. */
    private static boolean isLfsPointer(byte[] content) {
        if (content.length < LFS_POINTER.length()) return false;
        String head = new String(content, 0, LFS_POINTER.length(), StandardCharsets.US_ASCII);
        return LFS_POINTER.equals(head);
    }

    /** The rebuilt content, and whether it also dropped lines the block replaces. */
    private static final class Applied {
        final String content;
        final boolean removed;

        Applied(String content, boolean removed) {
            this.content = content;
            this.removed = removed;
        }
    }

    /**
     * The content of the index with the chosen lines, and only those, applied.
     *
     * The index is rebuilt in one pass and the decision is taken per line, not
     * per hunk. That distinction is the whole promise of the gesture: Git
     * reports "two lines rewritten and three added" as a single hunk, while the
     * ruler draws it as two bars of different colours, so applying whole hunks
     * meant that clicking the blue bar quietly staged the green one underneath
     * as well.
     *
     * The pairing inside a hunk follows the same convention the bars are drawn
     * with: the first {@code shared} lines are a rewrite, one for one, and
     * whatever is left over is an addition, or a removal. It is a convention
     * rather than a fact of the diff - but it is the one the user is looking
     * at, and the staging has to agree with the picture, not with the hunk.
     *
     * Lines that the block replaces and the buffer no longer has go with it:
     * they carry no bar of their own, so leaving them behind would put a change
     * in the file that no click could ever reach.
     */
    private static Applied apply(RawText staged, RawText current, EditList edits,
            int startLine, int endLine) {
        List<String> out = new ArrayList<String>(staged.size());
        boolean touched = false;
        boolean removed = false;
        int cursor = 0;

        for (Edit edit : edits) {
            for (; cursor < edit.getBeginA(); cursor++) out.add(staged.getString(cursor));
            int lengthA = edit.getEndA() - edit.getBeginA();
            int lengthB = edit.getEndB() - edit.getBeginB();
            int shared = Math.min(lengthA, lengthB);
            // A deletion occupies no line of its own: it is reached through the
            // line it is anchored to, which is why an empty B side still has to
            // answer this question.
            boolean inRange = startLine <= Math.max(edit.getBeginB(), edit.getEndB() - 1)
                    && endLine >= edit.getBeginB();

            for (int i = 0; i < shared; i++) {
                int line = edit.getBeginB() + i;
                if (line >= startLine && line <= endLine) {
                    out.add(current.getString(line));
                    touched = true;
                } else {
                    out.add(staged.getString(edit.getBeginA() + i));
                }
            }
            for (int i = shared; i < lengthB; i++) {
                int line = edit.getBeginB() + i;
                if (line < startLine || line > endLine) continue;
                out.add(current.getString(line));
                touched = true;
            }
            for (int i = shared; i < lengthA; i++) {
                if (inRange) {
                    touched = true;
                    removed = true;
                } else {
                    out.add(staged.getString(edit.getBeginA() + i));
                }
            }
            cursor = edit.getEndA();
        }
        for (; cursor < staged.size(); cursor++) out.add(staged.getString(cursor));
        if (!touched) return null;

        StringBuilder merged = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) merged.append('\n');
            merged.append(out.get(i));
        }
        return new Applied(merged.toString(), removed);
    }

    /**
     * The rebuilt text as bytes, ending the way the file it replaces ended.
     *
     * A final newline is only put back when the content being replaced had one.
     * Appending it unconditionally silently added a line terminator to every
     * file that legitimately ends without one. The byte order mark gets the same
     * treatment: the editor keeps it out of the document, so it has to be
     * carried over from the index or staging the first block would drop it.
     */
    private static byte[] bytes(String content, byte[] body, boolean bom, Charset charset) {
        // With no entry in the index there is nothing to take the shape from,
        // so the buffer's own convention stands and the file gets the final
        // newline a text file is expected to have.
        boolean fresh = body.length == 0;
        boolean crlf = fresh ? content.contains("\r\n") : usesCrlf(body);
        StringBuilder text = new StringBuilder(matchLineEndings(content, crlf));
        if ((fresh || endsWithNewline(body)) && text.length() > 0
                && text.charAt(text.length() - 1) != '\n') {
            text.append(crlf ? "\r\n" : "\n");
        }
        byte[] encoded = text.toString().getBytes(charset);
        if (!bom || startsWithBom(encoded)) return encoded;
        byte[] withBom = new byte[UTF8_BOM.length + encoded.length];
        System.arraycopy(UTF8_BOM, 0, withBom, 0, UTF8_BOM.length);
        System.arraycopy(encoded, 0, withBom, UTF8_BOM.length, encoded.length);
        return withBom;
    }

    private static byte[] strip(byte[] content) {
        byte[] body = new byte[content.length - UTF8_BOM.length];
        System.arraycopy(content, UTF8_BOM.length, body, 0, body.length);
        return body;
    }

    private static boolean endsWithNewline(byte[] content) {
        return content.length > 0 && content[content.length - 1] == '\n';
    }

    private static boolean startsWithBom(byte[] content) {
        if (content.length < UTF8_BOM.length) return false;
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (content[i] != UTF8_BOM[i]) return false;
        }
        return true;
    }

    /**
     * Writes the content as a blob and points the locked index at it.
     *
     * @return whether the index file was actually replaced. JGit reports a
     *         failed write by returning false, not by throwing: taking the call
     *         for granted meant reporting a stage that never happened.
     *         <p>
     *         True is not quite "written" either: an editor with no edits queued
     *         also returns true, having released the lock and done nothing. The
     *         edit below is added unconditionally, right before the commit, so
     *         that branch is out of reach - which is the only reason true can be
     *         read as success here.
     */
    private static boolean store(Repository repository, DirCache cache, final String path,
            final byte[] content, final FileMode mode) throws Exception {
        ObjectInserter inserter = repository.newObjectInserter();
        final ObjectId blob;
        try {
            blob = inserter.insert(Constants.OBJ_BLOB, content);
            inserter.flush();
        } finally {
            inserter.close();
        }
        DirCacheEditor editor = cache.editor();
        editor.add(new DirCacheEditor.PathEdit(path) {
            @Override
            public void apply(DirCacheEntry entry) {
                entry.setFileMode(mode);
                entry.setObjectId(blob);
                entry.setLength(content.length);
            }
        });
        return editor.commit();
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
}
