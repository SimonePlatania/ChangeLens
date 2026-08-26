package com.simone.changelens;

import java.util.Collections;
import java.util.List;

import org.eclipse.jface.text.IDocument;

/** Insieme dei blocchi di modifica ancorati al documento. */
final class GitSnapshot {

    static final GitSnapshot EMPTY = new GitSnapshot(Collections.<ChangeBlock>emptyList());

    final List<ChangeBlock> blocks;

    GitSnapshot(List<ChangeBlock> blocks) {
        this.blocks = Collections.unmodifiableList(blocks);
    }

    boolean isEmpty() {
        return blocks.isEmpty();
    }

    /** Blocco che copre la riga indicata, o la cancellazione ancorata su di essa. */
    ChangeBlock at(IDocument document, int line) {
        for (ChangeBlock block : blocks) {
            if (!block.isValid() || block.kind == ChangeBlock.DELETED) continue;
            if (line >= block.startLine(document) && line <= block.endLine(document)) return block;
        }
        for (ChangeBlock block : blocks) {
            if (block.isValid() && block.kind == ChangeBlock.DELETED
                    && block.startLine(document) == line) {
                return block;
            }
        }
        return null;
    }
}
