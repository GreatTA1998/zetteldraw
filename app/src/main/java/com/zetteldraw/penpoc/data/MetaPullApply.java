package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.InkRenderer;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;
import com.zetteldraw.penpoc.data.db.PageLinkEntity;
import com.zetteldraw.penpoc.data.db.ZettelDao;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exception-path meta LWW for pulled notebooks, page links, and legacy board
 * rows (Hand off / history / dual-push shelf). Day-to-day ink SoT is
 * {@link InkSheetStore} + {@link InkLogApply}; this type is the shelf beside
 * {@link Lww}, not peer merge.
 *
 * <p>Caller holds the repository lock and runs inside the pull transaction.
 * Board ink still bypasses {@link InkLogApply} for <em>unmigrated</em> notebooks
 * only — once {@link Deps#notebookLogBacked} is true, {@link #applyRemoteBoard}
 * is a no-op (Ready {@code .zdl} owns ink).
 *
 * @see Lww
 * @see docs/ink-sot-rewrite.md
 */
public final class MetaPullApply {
    /** Staged legacy board ink from {@code applyPull} (hash-checked, decoded). */
    public record Staged(File file, byte[] bytes, List<InkRenderer.InkStroke> strokes) {
    }

    /** Bridge into Room / outbox / mirror / board cache owned by the repository. */
    public interface Deps {
        ZettelDao dao();

        InkFileStore ink();

        boolean pendingOutbox(String entity, String id);

        boolean hasPendingInk(String boardId);

        void queueBoardUpsert(String boardId);

        void mirrorInk(String boardId, byte[] bytes);

        void removeCachedBoard(String boardId);

        /**
         * True when this notebook already has a Ready {@code notebook_logs} /
         * on-disk {@code .zdl} primary — BoardEntity {@code .zdi} must not apply.
         */
        boolean notebookLogBacked(String notebookId);
    }

    private final Deps deps;

    public MetaPullApply(Deps deps) {
        this.deps = deps;
    }

    public void applyRemoteNotebook(NotebookEntity remote) {
        NotebookEntity local = deps.dao().notebook(remote.id);
        if (local != null && (remote.rev <= local.rev
                || (deps.pendingOutbox(OutboxEntry.NOTEBOOK, remote.id)
                    && !Lww.remoteWins(local.updatedAt, remote.updatedAt)))) {
            return;
        }
        deps.dao().upsertNotebook(remote);
        deps.dao().deleteOutbox(OutboxEntry.NOTEBOOK, remote.id);
    }

    public void applyRemoteLink(PageLinkEntity remote) {
        PageLinkEntity local = deps.dao().pageLink(remote.id);
        if (local != null && (remote.rev <= local.rev
                || (deps.pendingOutbox(OutboxEntry.LINK, remote.id)
                    && !Lww.remoteWins(local.updatedAt, remote.updatedAt)))) {
            return;
        }
        deps.dao().upsertPageLink(remote);
        deps.dao().deleteOutbox(OutboxEntry.LINK, remote.id);
    }

    public void applyRemoteBoard(BoardEntity remote, Map<String, Staged> staged,
                                 Map<String, List<InkRenderer.InkStroke>> refreshed,
                                 List<Runnable> mirrorOps) throws IOException {
        if (deps.notebookLogBacked(remote.notebookId)) {
            // Ready owns ink — do not recreate .zdi / BoardEntity conflict shelf.
            return;
        }
        ZettelDao dao = deps.dao();
        BoardEntity local = dao.board(remote.id);
        if (local != null) {
            if (remote.rev <= local.rev) {
                return;
            }
            if (deps.hasPendingInk(remote.id)) {
                // Drawn on this device and not yet written: that edit is the newest and pushes after.
                return;
            }
            if (deps.pendingOutbox(OutboxEntry.BOARD, remote.id)) {
                if (!Lww.remoteWins(local.updatedAt, remote.updatedAt)) {
                    // Local edit is newer; it stays queued with its old base rev and the
                    // server keeps its current row as a conflict copy on the next push.
                    return;
                }
                if (Lww.needsConflictCopy(local, remote)) {
                    conflictCopy(local, mirrorOps);
                }
            }
        }
        if (remote.deletedAt != null || remote.inkHash == null) {
            deps.ink().delete(remote.id);
            String id = remote.id;
            mirrorOps.add(() -> deps.mirrorInk(id, null));
            refreshed.put(remote.id, new ArrayList<>());
        } else if (local == null || !remote.inkHash.equals(local.inkHash)) {
            Staged blob = staged.remove(remote.id);
            if (blob == null) {
                throw new IOException("bad ink blob for " + remote.id);
            }
            deps.ink().commit(blob.file(), remote.id);
            String id = remote.id;
            mirrorOps.add(() -> deps.mirrorInk(id, blob.bytes()));
            refreshed.put(remote.id, blob.strokes());
        }
        dao.upsertBoard(remote);
        dao.deleteOutbox(OutboxEntry.BOARD, remote.id);
        if (remote.deletedAt != null) {
            deps.removeCachedBoard(remote.id);
        }
    }

    private void conflictCopy(BoardEntity loser, List<Runnable> mirrorOps) throws IOException {
        BoardEntity copy = loser.copy();
        copy.id = UUID.randomUUID().toString();
        copy.conflictOf = loser.id;
        copy.rev = 0;
        byte[] bytes = deps.ink().read(loser.id);
        if (bytes != null) {
            deps.ink().write(copy.id, bytes);
            String id = copy.id;
            mirrorOps.add(() -> deps.mirrorInk(id, bytes));
        }
        deps.dao().upsertBoard(copy);
        deps.queueBoardUpsert(copy.id);
    }
}
