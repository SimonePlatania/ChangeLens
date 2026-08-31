package com.simone.changelens;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.revisions.Revision;
import org.eclipse.jface.text.revisions.RevisionInformation;
import org.eclipse.jface.text.revisions.RevisionRange;
import org.eclipse.jface.text.source.LineRange;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Shell;

/**
 * The revisions to show in Eclipse's column, computed with JGit's blame.
 *
 * They exist for a single reason: the column draws whatever
 * {@link Revision#getAuthor()} returns, and EGit's returns the name alone.
 * Here the same string carries the commit date too, so every line says not
 * only who wrote it but when.
 */
final class BlameRevisions {

    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM/yyyy");
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    private BlameRevisions() {
    }

    /** To be called off the UI thread: the blame of a large file is not instant. */
    static RevisionInformation of(IFile file) {
        try {
            RepositoryMapping mapping = RepositoryMapping.getMapping(file);
            if (mapping == null) return null;
            Repository repository = mapping.getRepository();
            String path = mapping.getRepoRelativePath(file);
            if (repository == null || path == null) return null;
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null) return null;

            BlameResult blame = new BlameCommand(repository)
                    .setFollowFileRenames(true)
                    .setFilePath(path)
                    .setStartCommit(head)
                    .call();
            if (blame == null) return null;
            return build(blame);
        } catch (Exception failure) {
            Activator.log(failure);
            return null;
        }
    }

    /**
     * One range per single line, not one per block of lines from the same
     * commit.
     *
     * Eclipse's column writes the date and the author once per range, at the
     * top: grouping contiguous lines, as EGit does, puts the text only on the
     * first line of the block and leaves the others blank. One range per line
     * is what puts a date and a name on every line.
     */
    private static RevisionInformation build(BlameResult blame) {
        RevisionInformation information = new RevisionInformation();
        Map<String, CommitRevision> revisions = new HashMap<String, CommitRevision>();
        int lines = blame.getResultContents().size();

        for (int line = 0; line < lines; line++) {
            RevCommit commit = blame.getSourceCommit(line);
            if (commit == null) continue;
            CommitRevision revision = revisions.get(commit.name());
            if (revision == null) {
                revision = new CommitRevision(commit, blame.getSourceAuthor(line));
                revisions.put(commit.name(), revision);
                information.addRevision(revision);
            }
            revision.addRange(new LineRange(line, 1));
        }
        // Without a creator of its own the column's hover stays the canvas's
        // generic one, which knows nothing of the revision: here it is stated
        // explicitly what the commit card is to be shown with.
        IInformationControlCreator hover = new IInformationControlCreator() {
            @Override
            public IInformationControl createInformationControl(Shell parent) {
                return new DefaultInformationControl(parent, (String) null);
            }
        };
        information.setHoverControlCreator(hover);
        information.setInformationPresenterControlCreator(hover);
        return information;
    }

    /**
     * The same revisions, one range per line and the date before the name.
     *
     * The column writes the text once per range, at the top: with EGit's long
     * ranges the date and the name only appeared on the first line of a block.
     * The revisions are not rebuilt but wrapped, so the colour, the id and the
     * commit card stay EGit's own.
     */
    static RevisionInformation perLine(RevisionInformation source) {
        if (source == null) return null;
        try {
            RevisionInformation information = new RevisionInformation();
            Map<Revision, LineRevision> wrapped = new LinkedHashMap<Revision, LineRevision>();
            for (RevisionRange range : source.getRanges()) {
                Revision origin = range.getRevision();
                if (origin == null) continue;
                LineRevision revision = wrapped.get(origin);
                if (revision == null) {
                    revision = new LineRevision(origin);
                    wrapped.put(origin, revision);
                    information.addRevision(revision);
                }
                int start = range.getStartLine();
                for (int line = start; line < start + range.getNumberOfLines(); line++) {
                    revision.addRange(new LineRange(line, 1));
                }
            }
            if (wrapped.isEmpty()) return null;
            information.setHoverControlCreator(source.getHoverControlCreator());
            information.setInformationPresenterControlCreator(
                    source.getInformationPresenterControlCreator());
            return information;
        } catch (Exception failure) {
            Activator.log(failure);
            return null;
        }
    }

    /** They have been through here already: no wrapping them a second time. */
    static boolean isOurs(RevisionInformation information) {
        if (information == null) return false;
        for (Revision revision : information.getRevisions()) {
            return revision instanceof LineRevision || revision instanceof CommitRevision;
        }
        return false;
    }

    /**
     * An EGit revision dressed by us: the only thing that changes is what ends
     * up written in the column, where the date comes before the name.
     * Everything else is delegated, the commit card included.
     */
    private static final class LineRevision extends Revision {

        private final Revision source;

        LineRevision(Revision source) {
            this.source = source;
        }

        @Override
        public String getAuthor() {
            String name = source.getAuthor();
            Date when = source.getDate();
            if (name == null || name.isEmpty()) return when == null ? "" : format(when);
            return when == null ? name : format(when) + " " + name;
        }

        @Override
        public Object getHoverInfo() {
            return source.getHoverInfo();
        }

        @Override
        public RGB getColor() {
            return source.getColor();
        }

        @Override
        public String getId() {
            return source.getId();
        }

        @Override
        public Date getDate() {
            return source.getDate();
        }
    }

    private static final class CommitRevision extends Revision {

        private final RevCommit commit;
        private final PersonIdent author;

        CommitRevision(RevCommit commit, PersonIdent author) {
            this.commit = commit;
            this.author = author == null ? commit.getAuthorIdent() : author;
        }

        @Override
        public String getId() {
            return commit.abbreviate(7).name();
        }

        @Override
        public Date getDate() {
            return author == null ? commit.getAuthorIdent().getWhen() : author.getWhen();
        }

        /** What ends up in the column, line by line: the date, then the full name. */
        @Override
        public String getAuthor() {
            String name = author == null || author.getName() == null ? "?" : author.getName();
            Date when = getDate();
            return when == null ? name : format(when) + " " + name;
        }

        /**
         * What one reads hovering the column: the commit card for that line, as
         * EGit gave it before the revisions became ours.
         */
        @Override
        public Object getHoverInfo() {
            PersonIdent who = author == null ? commit.getAuthorIdent() : author;
            StringBuilder text = new StringBuilder();
            text.append("Commit: ").append(commit.name()).append('\n');
            if (who != null) {
                text.append("Author: ").append(who.getName());
                if (who.getEmailAddress() != null && !who.getEmailAddress().isEmpty()) {
                    text.append(" <").append(who.getEmailAddress()).append('>');
                }
                text.append('\n');
                if (who.getWhen() != null) {
                    text.append("Date: ").append(stamp(who.getWhen())).append('\n');
                }
            }
            PersonIdent committer = commit.getCommitterIdent();
            if (committer != null && who != null && !committer.getName().equals(who.getName())) {
                text.append("Committed by: ").append(committer.getName()).append('\n');
            }
            String message = commit.getFullMessage();
            if (message != null && !message.trim().isEmpty()) {
                text.append('\n').append(message.trim());
            }
            return text.toString();
        }

        /**
         * A stable shade per author: two revisions by the same author must take
         * the same colour, files apart.
         */
        @Override
        public RGB getColor() {
            String key = author == null || author.getName() == null ? getId() : author.getName();
            int hash = key.hashCode();
            return new RGB(Math.abs(hash) % 360, 0.35f, 0.95f);
        }
    }

    private static String format(Date when) {
        synchronized (DATE) {
            return DATE.format(when);
        }
    }

    private static String stamp(Date when) {
        synchronized (STAMP) {
            return STAMP.format(when);
        }
    }
}
