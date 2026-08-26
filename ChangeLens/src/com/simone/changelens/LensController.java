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
 * Stato condiviso di un editor: diff verso HEAD, dichiarazioni e autori.
 *
 * Due garanzie che reggono tutto il resto:
 *
 * 1. i blocchi di modifica sono {@link Position} registrate nel documento, che
 *    Eclipse aggiorna a ogni battitura: restano incollati alle righe di inizio
 *    e fine del cambiamento, qualunque cosa succeda sopra di loro;
 * 2. una modifica non azzera mai lo stato visibile. Le barre restano dove
 *    sono e vengono sostituite solo quando il nuovo diff e pronto, quindi non
 *    c'e nessun lampeggio fra il vecchio e il nuovo risultato.
 */
final class LensController implements IDocumentListener {

    /** Attesa dopo l'ultima digitazione prima di ricalcolare il diff. */
    private static final int DEBOUNCE = 400;

    private final ITextViewer viewer;
    private final IFile file;
    private final Display display;
    private final List<Runnable> listeners = new ArrayList<Runnable>();

    private final Map<Integer, AuthorLabel> labels = new HashMap<Integer, AuthorLabel>();
    private final Set<Integer> requested = new HashSet<Integer>();
    /** Etichette ancora valide da mostrare, ma da ricalcolare appena possibile. */
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
     * Etichetta per la dichiarazione, oppure {@code null} se non e mai stata
     * calcolata.
     *
     * Se l'etichetta c'e ma e da rinfrescare, viene restituita comunque e il
     * ricalcolo parte in sottofondo: il nome resta sotto gli occhi mentre si
     * scrive invece di sparire e ricomparire al primo scorrimento.
     */
    AuthorLabel label(int declarationLine) {
        Integer key = Integer.valueOf(declarationLine);
        AuthorLabel label = labels.get(key);
        MethodLens method = methods.get(key);
        boolean wanted = label == null || stale.contains(key);
        if (wanted && method != null && model != null && requested.add(key)) {
            queueAuthors();
        }
        return label;
    }

    /**
     * Il metodo ha modifiche non committate secondo il diff corrente.
     * Serve a mostrare l'asterisco subito, senza attendere il blame.
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
        // Le Position dei blocchi le ha gia aggiornate il documento.
        // Alle dichiarazioni basta lo scorrimento delle righe sotto la modifica.
        int delta = event.getDocument().getNumberOfLines() - lineCountBefore;
        shiftMethods(delta);
        markLive(event.getDocument(), delta);
        fireRefresh();
        schedule(DEBOUNCE);
    }

    /**
     * Colora subito le righe appena toccate, senza aspettare il confronto con
     * HEAD: premuto invio la nuova riga e verde all'istante, e una riga
     * riscritta diventa arancione mentre si digita. Quando il diff vero arriva
     * sostituisce in blocco queste marcature provvisorie.
     */
    private void markLive(IDocument document, int delta) {
        // Senza repository, o su un file mai committato, non c'e niente con cui
        // confrontarsi: nessuna riga va colorata.
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

        // Il blocco accanto viene esteso fino a coprire anche le righe nuove.
        // Aggiungerne uno separato lascerebbe la vecchia barra dov'era e la
        // riga appena toccata senza indicatore.
        int kind = neighbour.kind == ChangeBlock.ADDED ? ChangeBlock.ADDED : ChangeBlock.MODIFIED;
        remove(neighbour);
        add(document, kind, Math.min(start, from), Math.max(end, to));
    }

    /**
     * Il blocco che copre o confina con le righe indicate.
     * Confinare basta: una riga scritta a ridosso di un cambiamento fa parte
     * dello stesso cambiamento, non ne apre un altro.
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
                // gia rimossa insieme al documento
            }
        }
        List<ChangeBlock> rest = new ArrayList<ChangeBlock>(snapshot.blocks);
        rest.remove(block);
        snapshot = new GitSnapshot(rest);
    }

    /** Sposta dichiarazioni ed etichette gia note, invece di farle sparire. */
    private void shiftMethods(int delta) {
        if (methods.isEmpty()) return;
        if (delta == 0) {
            // Il testo di una riga e cambiato: la dichiarazione va rivista, ma
            // il nome resta a video finche il nuovo valore non e pronto.
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
        analysis = new Job("ChangeLens: confronto con HEAD") {
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
                anchor(changes);
                fireRefresh();
            }
        });
    }

    /**
     * Prende le nuove dichiarazioni tenendo le etichette gia note per quelle
     * che stanno ancora alla stessa riga: restano visibili e vengono solo
     * rinfrescate, invece di sparire per il tempo del ricalcolo.
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

    /** Registra i nuovi blocchi come Position del documento e libera i vecchi. */
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
                // la Position puo essere gia stata rimossa con il documento
            }
        }
        anchored = null;
        snapshot = GitSnapshot.EMPTY;
    }

    /**
     * Un solo Job autori alla volta: le richieste che arrivano mentre uno gira
     * vengono servite dal giro successivo, cosi lo scorrimento veloce non
     * genera decine di blame concorrenti.
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

        authors = new Job("ChangeLens: autori") {
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
                    }
                });
                return Status.OK_STATUS;
            }
        };
        authors.setSystem(true);
        authors.schedule();
    }

    /**
     * Notifica i disegnatori una sola volta per ciclo di eventi. Coalescere
     * qui e cio che impedisce catene ridisegno/ricalcolo che rientrano su se
     * stesse fino a esaurire lo stack.
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
