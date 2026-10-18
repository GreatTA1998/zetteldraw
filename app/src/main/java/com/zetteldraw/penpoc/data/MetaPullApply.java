package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.data.db.NotebookEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;
import com.zetteldraw.penpoc.data.db.PageLinkEntity;
import com.zetteldraw.penpoc.data.db.ZettelDao;

/**
 * Exception-path meta LWW for pulled notebooks and page links (Hand-off /
 * catch-up). Day-to-day ink SoT is {@link InkSheetStore} + {@link InkLogApply}.
 * One live writer — not peer merge.
 *
 * <p>Caller holds the repository lock and runs inside the pull transaction.
 * Remote BOARD ink is not adopted here: Hand-off unlocks only when Ready
 * {@code notebook_logs} cover every inked notebook, so catch-up restores via
 * {@link InkSheetStore#applyRemoteLogLocked} alone. Local BoardEntity
 * {@code .zdi} is residue cleared on Ready install — not a migrate-on-open path.
 *
 * @see Lww
 * @see docs/ink-sot-rewrite.md
 */
public final class MetaPullApply {
    /** Bridge into Room / outbox owned by the repository. */
    public interface Deps {
        ZettelDao dao();

        boolean pendingOutbox(String entity, String id);
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
}
