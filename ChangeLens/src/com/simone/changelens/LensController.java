package com.simone.changelens;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.swt.widgets.Display;

/**
 * The shared state of one editor: the diff against HEAD, the declarations and
 * their authors.
 *
 * Two guarantees hold up everything else:
 *
 * 1. change blocks are {@link Position}s registered with the document, which
 *    Eclipse updates on every keystroke: they stay glued to the first and last
 *    line of the change, whatever happens above them;
 * 2. an edit never wipes the visible state. The bars stay where they are and
 *    are replaced only once the new diff is ready, so there is no flicker
 *    between the old result and the new one.
 */
final class LensController implements IDocumentListener {

    /** How long after the last keystroke the diff is recomputed. */
    private static final int DEBOUNCE = 400;

    private final ITextViewer viewer;
    private final IFile file;
    private final Display display;
    private final List<Runnable> listeners = new ArrayList<Runnable>();

    private final Map<Integer, AuthorLabel> labels = new HashMap<Integer, AuthorLabel>();
    private final Set<Integer> requested = new HashSet<Integer>();
    /** Labels still fine to show, but due for recomputation as soon as possible. */
    private final Set<Integer> stale = new HashSet<Integer>();

    private IDocument anchored;
    private GitSnapshot snapshot = GitSnapshot.EMPTY;
    private Map<Integer, MethodLens> methods = Collections.emptyMap();
    private GitDocumentModel model;

    private Job analysis;
    private Job authors;
    private int generation;
    private int lineCountBefore;
    private int changeLine;
    private boolean disposed;
    private boolean refreshScheduled;

    LensController(ITextViewer viewer, IFile file) {
        this.viewer = viewer;
        this.file = file;
        this.display = viewer.getTextWidget().getDisplay();
        IDocument document = viewer.getDocument();
        if (document != null) document.addDocumentListener(this);
        schedule(0);
    }

    void addListener(Runnable listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    IFile file() {
        return file;
    }

    IDocument document() {
        return viewer.getDocument();
    }

    GitSnapshot snapshot() {
        return snapshot;
    }

    MethodLens methodAt(int line) {
        return methods.get(Integer.valueOf(line));
    }

    boolean hasMethods() {
        return !methods.isEmpty();
    }

    /**
     * The label for a declaration, or {@code null} when it has never been
     * computed.
     *
     * If the label is there but due for a refresh it is returned anyway and the
     * recomputation starts in the background: the name stays in sight while
     * typing instead of vanishing and coming back on the first scroll.
     */
    AuthorLabel label(int declarationLine) {
        Integer key = Integer.valueOf(declarationLine);
        AuthorLabel label = labels.get(key);
        MethodLens method = methods.get(key);
        boolean wanted = label == null || stale.contains(key);
        if (wanted && method != null && model != null && requested.add(key)) {
            queueAuthors();
        }
        // A freshly written signature drags along the label of whoever was on
        // that line an instant earlier, and for as long as the recomputation
        // takes one reads an author instead of "not committed yet". On a line
        // the diff calls added, the old label is worth nothing: in its place
        // goes what is already known.
        if (label != null && stale.contains(key) && isAdded(declarationLine)) {
            return method != null && method.structurallyComplete ? AuthorLabel.notCommitted() : null;
        }
        return label;
    }

    /** The line belongs to a block of lines added with respect to HEAD. */
    private boolean isAdded(int line) {
        IDocument document = viewer.getDocument();
        if (document == null || snapshot.isEmpty()) return false;
        for (ChangeBlock block : snapshot.blocks) {
            if (!block.isValid() || block.kind != ChangeBlock.ADDED) continue;
            if (line >= block.startLine(document) && line <= block.endLine(document)) return true;
        }
        return false;
    }

    /**
     * The method has uncommitted changes according to the current diff. It is
     * what shows the asterisk at once, without waiting for the blame.
     */
    boolean isDirty(int declarationLine) {
        MethodLens method = methods.get(Integer.valueOf(declarationLine));
        IDocument document = viewer.getDocument();
        if (method == null || document == null || snapshot.isEmpty()) return false;
        for (ChangeBlock block : snapshot.blocks) {
            if (!block.isValid()) continue;
            int start = block.startLine(document);
            int end = block.kind == ChangeBlock.DELETED ? start : block.endLine(document);
            if (start <= method.endLine && end >= method.declarationLine) return true;
        }
        return false;
    }

    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {
        IDocument document = event.getDocument();
        lineCountBefore = document.getNumberOfLines();
        try {
            changeLine = document.getLineOfOffset(event.getOffset());
        } catch (BadLocationException ignored) {
            changeLine = 0;
        }
    }

    @Override
    public void documentChanged(DocumentEvent event) {
        generation++;
        cancel(analysis);
        cancel(authors);
        analysis = null;
        authors = null;
        // The document has already updated the blocks' Positions. For the
        // declarations, shifting the lines below the edit is enough.
        int delta = event.getDocument().getNumberOfLines() - lineCountBefore;
        shiftMethods(delta);
        markLive(event.getDocument(), delta);
        fireRefresh();
        schedule(DEBOUNCE);
    }

    /**
     * Colours the just-touched lines at once, without waiting for the
     * comparison against HEAD: press Enter and the new line is green
     * immediately, and a rewritten line takes its colour while being typed.
     * When the real diff arrives it replaces all of these provisional marks.
     */
    private void markLive(IDocument document, int delta) {
        // With no repository, or on a file never committed, there is nothing to
        // compare against: no line gets coloured.
        if (document == null || model == null || !model.isTracked()) return;
        int from = Math.max(0, changeLine);
        int to = delta > 0 ? from + delta : from;
        if (to >= document.getNumberOfLines()) to = document.getNumberOfLines() - 1;
        if (to < from) return;

        ChangeBlock neighbour = touching(document, from, to);
        if (neighbour == null) {
            add(document, delta > 0 ? ChangeBlock.ADDED : ChangeBlock.MODIFIED, from, to);
            return;
        }

        int start = neighbour.startLine(document);
        int end = neighbour.kind == ChangeBlock.DELETED ? start : neighbour.endLine(document);
        if (neighbour.kind != ChangeBlock.DELETED && start <= from && end >= to) return;

        // The neighbouring block is stretched to cover the new lines too.
        // Adding a separate one would leave the old bar where it was and the
        // just-touched line with no marker at all.
        int kind = neighbour.kind == ChangeBlock.ADDED ? ChangeBlock.ADDED : ChangeBlock.MODIFIED;
        remove(neighbour);
        add(document, kind, Math.min(start, from), Math.max(end, to));
    }

    /**
     * The block covering or bordering the given lines.
     * Bordering is enough: a line written right against a change belongs to
     * that same change, it does not open another one.
     */
    private ChangeBlock touching(IDocument document, int from, int to) {
        for (ChangeBlock block : snapshot.blocks) {
            if (!block.isValid()) continue;
            int start = block.startLine(document);
            int end = block.kind == ChangeBlock.DELETED ? start : block.endLine(document);
            if (start <= to + 1 && end >= from - 1) return block;
        }
        return null;
    }

    private void add(IDocument document, int kind, int from, int to) {
        Position position = ChangeBlock.positionFor(document, from, to, false);
        if (position == null) return;
        try {
            document.addPosition(position);
        } catch (BadLocationException ignored) {
            return;
        }
        anchored = document;
        List<ChangeBlock> merged = new ArrayList<ChangeBlock>(snapshot.blocks);
        merged.add(new ChangeBlock(kind, position, ""));
        snapshot = new GitSnapshot(merged);
    }

    private void remove(ChangeBlock block) {
        if (anchored != null) {
            try {
                anchored.removePosition(block.position());
            } catch (Exception ignored) {
                // already removed along with the document
            }
        }
        List<ChangeBlock> rest = new ArrayList<ChangeBlock>(snapshot.blocks);
        rest.remove(block);
        snapshot = new GitSnapshot(rest);
    }

    /** Shifts the known declarations and labels instead of making them vanish. */
    private void shiftMethods(int delta) {
        if (methods.isEmpty()) return;
        if (delta == 0) {
            // The text of a line changed: the declaration needs revisiting, but
            // the name stays on screen until the new value is ready.
            stale.add(Integer.valueOf(changeLine));
            requested.remove(Integer.valueOf(changeLine));
            return;
        }
        Map<Integer, MethodLens> movedMethods = new TreeMap<Integer, MethodLens>();
        Map<Integer, AuthorLabel> movedLabels = new HashMap<Integer, AuthorLabel>();
        for (Map.Entry<Integer, MethodLens> entry : methods.entrySet()) {
            int line = entry.getKey().intValue();
            int target = line > changeLine ? line + delta : line;
            if (target < 0) continue;
            MethodLens source = entry.getValue();
            movedMethods.put(Integer.valueOf(target), new MethodLens(target,
                    Math.max(target, source.endLine + (source.endLine > changeLine ? delta : 0)),
                    source.structurallyComplete));
            AuthorLabel label = labels.get(entry.getKey());
            if (label != null) movedLabels.put(Integer.valueOf(target), label);
        }
        methods = movedMethods;
        labels.clear();
        labels.putAll(movedLabels);
        requested.clear();
        stale.clear();
        stale.addAll(movedLabels.keySet());
    }

    private void schedule(int delay) {
        if (disposed) return;
        final int gen = generation;
        display.timerExec(delay, new Runnable() {
            @Override
            public void run() {
                if (disposed || gen != generation) return;
                analyse(gen);
            }
        });
    }

    private void analyse(final int gen) {
        IDocument document = viewer.getDocument();
        if (document == null) return;
        final String text = document.get();
        cancel(analysis);
        analysis = new Job("ChangeLens: comparing against HEAD") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                GitDocumentModel fresh = null;
                Map<Integer, MethodLens> declarations;
                List<RawChange> changes;
                try {
                    declarations = DeclarationScanner.scan(text);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    fresh = GitDocumentModel.create(file, text);
                    changes = fresh == null
                            ? Collections.<RawChange>emptyList() : fresh.changes();
                } catch (Exception failure) {
                    Activator.log(failure);
                    if (fresh != null) fresh.close();
                    return Status.OK_STATUS;
                }
                publish(gen, fresh, declarations, changes, monitor);
                return Status.OK_STATUS;
            }
        };
        analysis.setSystem(true);
        analysis.schedule();
    }

    private void publish(final int gen, final GitDocumentModel fresh,
            final Map<Integer, MethodLens> declarations, final List<RawChange> changes,
            IProgressMonitor monitor) {
        if (monitor.isCanceled() || display.isDisposed()) {
            if (fresh != null) fresh.close();
            return;
        }
        display.asyncExec(new Runnable() {
            @Override
            public void run() {
                if (disposed || gen != generation) {
                    if (fresh != null) fresh.close();
                    return;
                }
                GitDocumentModel previous = model;
                model = fresh;
                if (previous != null) previous.close();
                adopt(declarations);
                // The blocks come from the comparison against HEAD, not from
                // the editor's quick diff: that one, unless configured by hand,
                // refers to the last saved version, so on every save it called
                // the file clean and all the markers disappeared. Comparing
                // against HEAD is also the only reading consistent with "not
                // committed yet" and with the authors. Its line-by-line verdict
                // - rewritten or added - is the quick diff's own.
                anchor(changes);
                fireRefresh();
            }
        });
    }

    /**
     * Takes the new declarations while keeping the known labels for those still
     * on the same line: they stay visible and are merely refreshed, instead of
     * disappearing for as long as the recomputation takes.
     */
    private void adopt(Map<Integer, MethodLens> declarations) {
        Map<Integer, AuthorLabel> carried = new HashMap<Integer, AuthorLabel>();
        for (Map.Entry<Integer, AuthorLabel> entry : labels.entrySet()) {
            if (declarations.containsKey(entry.getKey())) carried.put(entry.getKey(), entry.getValue());
        }
        methods = declarations;
        labels.clear();
        labels.putAll(carried);
        requested.clear();
        stale.clear();
        stale.addAll(carried.keySet());
    }

    /** Registers the new blocks as document Positions and releases the old ones. */
    private void anchor(List<RawChange> changes) {
        IDocument document = viewer.getDocument();
        releasePositions();
        if (document == null || changes.isEmpty()) {
            snapshot = GitSnapshot.EMPTY;
            return;
        }
        anchored = document;
        List<ChangeBlock> blocks = new ArrayList<ChangeBlock>(changes.size());
        for (RawChange change : changes) {
            Position position = ChangeBlock.positionFor(document, change.startLine,
                    change.endLine, change.kind == ChangeBlock.DELETED);
            if (position == null) continue;
            try {
                document.addPosition(position);
            } catch (BadLocationException ignored) {
                continue;
            }
            blocks.add(new ChangeBlock(change.kind, position, change.original));
        }
        snapshot = new GitSnapshot(blocks);
    }

    private void releasePositions() {
        if (anchored == null) return;
        for (ChangeBlock block : snapshot.blocks) {
            try {
                anchored.removePosition(block.position());
            } catch (Exception ignored) {
                // the Position may already have gone with the document
            }
        }
        anchored = null;
        snapshot = GitSnapshot.EMPTY;
    }

    /**
     * One author Job at a time: requests arriving while one is running are
     * served by the very next round, so fast scrolling does not spawn dozens of
     * concurrent blames, and nothing sits in the queue waiting for a paint
     * event to move it along.
     */
    private void queueAuthors() {
        if (authors != null || model == null || requested.isEmpty()) return;
        final int gen = generation;
        final GitDocumentModel target = model;
        final List<MethodLens> batch = new ArrayList<MethodLens>();
        for (Integer line : requested) {
            MethodLens method = methods.get(line);
            if (method != null) batch.add(method);
        }
        if (batch.isEmpty()) return;

        authors = new Job("ChangeLens: authors") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final Map<Integer, AuthorLabel> found = new HashMap<Integer, AuthorLabel>();
                for (MethodLens method : batch) {
                    if (monitor.isCanceled()) break;
                    found.put(Integer.valueOf(method.declarationLine), target.author(method));
                }
                if (display.isDisposed()) return Status.OK_STATUS;
                display.asyncExec(new Runnable() {
                    @Override
                    public void run() {
                        authors = null;
                        if (disposed || gen != generation || model != target) return;
                        labels.putAll(found);
                        requested.removeAll(found.keySet());
                        stale.removeAll(found.keySet());
                        fireRefresh();
                        // Requests that arrived while the Job was running are
                        // still queued: they have to be served now, not at the
                        // next paint. Without this round the label of a
                        // freshly written method only appeared after a scroll.
                        queueAuthors();
                    }
                });
                return Status.OK_STATUS;
            }
        };
        authors.setSystem(true);
        authors.schedule();
    }

    /**
     * Notifies the painters once per event loop turn. Coalescing here is what
     * stops repaint/recompute chains from re-entering themselves until the
     * stack runs out.
     */
    private void fireRefresh() {
        if (refreshScheduled || disposed || display.isDisposed()) return;
        refreshScheduled = true;
        display.asyncExec(new Runnable() {
            @Override
            public void run() {
                refreshScheduled = false;
                if (disposed) return;
                for (Runnable listener : new ArrayList<Runnable>(listeners)) {
                    try {
                        listener.run();
                    } catch (Exception failure) {
                        Activator.log(failure);
                    }
                }
            }
        });
    }

    private static void cancel(Job job) {
        if (job != null) job.cancel();
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        listeners.clear();
        cancel(analysis);
        cancel(authors);
        analysis = null;
        authors = null;
        IDocument document = viewer.getDocument();
        if (document != null) document.removeDocumentListener(this);
        releasePositions();
        GitDocumentModel previous = model;
        model = null;
        if (previous != null) previous.close();
        labels.clear();
        requested.clear();
        stale.clear();
        methods = Collections.emptyMap();
    }
}
