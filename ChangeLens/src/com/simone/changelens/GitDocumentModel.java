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
import org.eclipse.jgit.lib.UserConfig;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Fotografia immutabile del confronto fra una esatta versione in memoria del
 * documento e HEAD. Ogni istanza vale per una sola generazione: quando il
 * documento cambia viene chiusa e ricreata, cosi nessun risultato puo restare
 * attaccato a righe che nel frattempo si sono spostate.
 */
final class GitDocumentModel implements AutoCloseable {

    private final Repository repository;
    private final String path;
    private final RawText committed;
    private final RawText current;
    private final EditList edits;
    private final String currentUser;
    private final boolean tracked;
    private final Map<Integer, AuthorLabel> authorCache = new HashMap<Integer, AuthorLabel>();

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
        // WS_IGNORE_TRAILING: CRLF contro LF e spazi finali non devono far
        // apparire modificato un file che e identico a HEAD.
        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.WS_IGNORE_TRAILING, committed, current);
        return new GitDocumentModel(repository, path, committed, current, edits,
                userNameOf(repository), tracked);
    }

    private static Charset charsetOf(IFile file) {
        try {
            String name = file.getCharset();
            if (name != null) return Charset.forName(name);
        } catch (Exception ignored) {
            // risorsa non disponibile o charset sconosciuto
        }
        return StandardCharsets.UTF_8;
    }

    private static String userNameOf(Repository repository) {
        try {
            UserConfig config = repository.getConfig().get(UserConfig.KEY);
            String name = config == null ? null : config.getAuthorName();
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (Exception ignored) {
            // nessun user.name configurato
        }
        return null;
    }

    private GitDocumentModel(Repository repository, String path, RawText committed, RawText current,
            EditList edits, String currentUser, boolean tracked) {
        this.repository = repository;
        this.path = path;
        this.committed = committed;
        this.current = current;
        this.edits = edits;
        this.currentUser = currentUser;
        this.tracked = tracked;
    }

    /**
     * I blocchi contigui di modifica, con riga di inizio e riga di fine
     * esatte. Le righe committate e non toccate non producono alcun blocco:
     * per loro non viene disegnato nulla.
     */
    List<RawChange> changes() {
        List<RawChange> blocks = new ArrayList<RawChange>();
        // File mai committato: senza una versione in HEAD non esiste un
        // confronto, e colorare tutto il file di verde non direbbe nulla.
        if (!tracked) return blocks;
        int lastLine = Math.max(0, current.size() - 1);
        for (Edit edit : edits) {
            String original = textOf(committed, edit.getBeginA(), edit.getEndA());
            if (edit.getLengthB() == 0) {
                int anchor = Math.min(Math.max(0, edit.getBeginB()), lastLine);
                blocks.add(new RawChange(ChangeBlock.DELETED, anchor, anchor, original));
                continue;
            }
            blocks.add(new RawChange(kindOf(edit), edit.getBeginB(), edit.getEndB() - 1, original));
        }
        return blocks;
    }

    /**
     * Che tipo di cambiamento e questo blocco.
     *
     * Un blocco che non tocca nulla in HEAD e una pura aggiunta; uno che
     * sostituisce righe una a una e una pura modifica. Quando ne riscrive
     * alcune e nello stesso punto ne aggiunge altre le due cose si sommano, e
     * non e ne l'una ne l'altra: quel caso ha un colore suo.
     */
    private static int kindOf(Edit edit) {
        if (edit.getLengthA() == 0) return ChangeBlock.ADDED;
        if (edit.getLengthB() > edit.getLengthA()) return ChangeBlock.MIXED;
        return ChangeBlock.MODIFIED;
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

    /** Etichetta autore per una dichiarazione. Da invocare fuori dal thread UI. */
    synchronized AuthorLabel author(MethodLens method) {
        if (closed || !tracked) return AuthorLabel.PENDING;
        Integer key = Integer.valueOf(method.declarationLine);
        AuthorLabel cached = authorCache.get(key);
        if (cached != null) return cached;

        AuthorLabel value;
        try {
            boolean dirty = overlaps(method.declarationLine, method.endLine);
            int oldLine = oldLineFor(method.declarationLine);
            value = !method.structurallyComplete ? unknown()
                    : oldLine < 0 ? mine()
                    : fromBlame(method, oldLine, dirty);
        } catch (Exception failure) {
            Activator.log(failure);
            value = mine();
        }
        authorCache.put(key, value);
        return value;
    }

    /** Metodo nuovo ma leggibile: e roba scritta da chi sta editando ora. */
    private AuthorLabel mine() {
        return currentUser == null ? unknown() : new AuthorLabel(currentUser, 0, true, true);
    }

    /**
     * Dichiarazione non associabile a HEAD con sicurezza: corpo non chiuso,
     * graffa mancante, blame senza risposta. In quel caso non si tira a
     * indovinare un autore, si dichiara {@code new*}.
     */
    private AuthorLabel unknown() {
        return new AuthorLabel("new", 0, true, true);
    }

    private AuthorLabel fromBlame(MethodLens method, int oldLine, boolean dirty) throws Exception {
        ensureBlame();
        if (blame == null) return mine();

        int min = oldLine;
        int max = oldLine;
        for (int line = method.declarationLine; line <= method.endLine; line++) {
            int mapped = oldLineFor(line);
            if (mapped < 0) continue;
            min = Math.min(min, mapped);
            max = Math.max(max, mapped);
        }
        min = Math.max(0, min);
        max = Math.min(max, committed.size() - 1);
        if (min > max) return mine();
        blame.computeRange(min, max + 1);

        PersonIdent owner = blame.getSourceAuthor(oldLine);
        if (owner == null || owner.getName() == null) return unknown();

        String primary = owner.getName();
        Set<String> others = new LinkedHashSet<String>();
        for (int line = method.declarationLine; line <= method.endLine; line++) {
            int mapped = oldLineFor(line);
            if (mapped < min || mapped > max) continue;
            PersonIdent contributor = blame.getSourceAuthor(mapped);
            if (contributor != null && contributor.getName() != null
                    && !primary.equals(contributor.getName())) {
                others.add(contributor.getName());
            }
        }
        return new AuthorLabel(primary, others.size(), dirty, false);
    }

    private void ensureBlame() throws Exception {
        if (blame != null || blameFailed || closed) return;
        try {
            generator = new BlameGenerator(repository, path);
            generator.setFollowFileRenames(true);
            generator.prepareHead();
            blame = generator.computeBlameResult();
        } catch (Exception failure) {
            blameFailed = true;
            throw failure;
        }
    }

    /** Riga corrispondente in HEAD, oppure -1 se la riga e nuova o modificata. */
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
                // in chiusura non c'e nulla da recuperare
            }
        }
        generator = null;
        blame = null;
        authorCache.clear();
    }
}
