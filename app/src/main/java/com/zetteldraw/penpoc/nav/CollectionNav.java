package com.zetteldraw.penpoc.nav;

import android.content.SharedPreferences;

import com.zetteldraw.penpoc.Board;

import java.util.HashMap;
import java.util.List;

/**
 * Sole owner of per-collection scroll intent and trusted page-count snapshots.
 *
 * <p>CanvasActivity may paint a collection only from a Ready ink-log projection
 * or from a snapshot previously {@linkplain #commitTrusted committed} here.
 * Room {@code BoardEntity} row counts never enter this module.
 *
 * <p>See docs/notebook-nav-correctness.md (agent store).
 */
public final class CollectionNav {
    public static final String PREF_SCROLL_PREFIX = "scroll_";
    public static final String PREF_SCROLL_PAGES_PREFIX = "scrollPages_";
    public static final String PREF_SCROLL_PAGE_PREFIX = "scrollPage_";
    public static final String PREF_TRUST_PAGES_PREFIX = "trustPages_";

    private final SharedPreferences prefs;
    private final HashMap<String, Integer> scrollByCollection = new HashMap<>();
    private final HashMap<String, Integer> trustedPageCount = new HashMap<>();

    public CollectionNav(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** Last painted Ready page count for this collection, or null if never trusted. */
    public Integer trustedCount(String collectionId) {
        if (collectionId == null) {
            return null;
        }
        Integer memory = trustedPageCount.get(collectionId);
        if (memory != null) {
            return memory;
        }
        String key = PREF_TRUST_PAGES_PREFIX + collectionId;
        if (!prefs.contains(key)) {
            return null;
        }
        int n = prefs.getInt(key, -1);
        if (n <= 0) {
            return null;
        }
        trustedPageCount.put(collectionId, n);
        return n;
    }

    /**
     * True when {@code pageCount} would shrink below a previously trusted Ready
     * list. Callers must refuse show / reconcile in that case.
     */
    public boolean rejectsShorter(String collectionId, int pageCount) {
        Integer trusted = trustedCount(collectionId);
        return trusted != null && pageCount < trusted;
    }

    /**
     * Record a Ready (or Ready-reconciled) list the UI actually painted.
     * Structural local edits that legitimately shrink the list must call this
     * with the new count after the edit.
     */
    public void commitTrusted(String collectionId, int pageCount, int scrollY, String anchorPageId) {
        if (collectionId == null || pageCount <= 0) {
            return;
        }
        trustedPageCount.put(collectionId, pageCount);
        prefs.edit().putInt(PREF_TRUST_PAGES_PREFIX + collectionId, pageCount).apply();
        rememberScroll(collectionId, scrollY, pageCount, anchorPageId);
    }

    public void clearTrusted(String collectionId) {
        if (collectionId == null) {
            return;
        }
        trustedPageCount.remove(collectionId);
        scrollByCollection.remove(collectionId);
        prefs.edit()
                .remove(PREF_TRUST_PAGES_PREFIX + collectionId)
                .remove(PREF_SCROLL_PREFIX + collectionId)
                .remove(PREF_SCROLL_PAGES_PREFIX + collectionId)
                .remove(PREF_SCROLL_PAGE_PREFIX + collectionId)
                .apply();
    }

    public void rememberScroll(String collectionId, int scrollY, int pageCount, String anchorPageId) {
        if (collectionId == null) {
            return;
        }
        int y = Math.max(0, scrollY);
        scrollByCollection.put(collectionId, y);
        SharedPreferences.Editor edit = prefs.edit().putInt(PREF_SCROLL_PREFIX + collectionId, y);
        if (pageCount > 0 && anchorPageId != null) {
            edit.putInt(PREF_SCROLL_PAGES_PREFIX + collectionId, pageCount)
                    .putString(PREF_SCROLL_PAGE_PREFIX + collectionId, anchorPageId);
        }
        edit.apply();
    }

    /** Null means never visited — first open still jumps to last page / last inked. */
    public Integer rememberedScroll(String collectionId) {
        if (collectionId == null) {
            return null;
        }
        Integer memory = scrollByCollection.get(collectionId);
        if (memory != null) {
            return memory;
        }
        String key = PREF_SCROLL_PREFIX + collectionId;
        if (!prefs.contains(key)) {
            return null;
        }
        int saved = Math.max(0, prefs.getInt(key, 0));
        scrollByCollection.put(collectionId, saved);
        return saved;
    }

    public boolean fingerprintMatches(String collectionId, List<Board> pages, PageLayout layout) {
        if (collectionId == null || pages == null || pages.isEmpty() || layout == null) {
            return false;
        }
        if (!prefs.contains(PREF_SCROLL_PAGES_PREFIX + collectionId)
                || !prefs.contains(PREF_SCROLL_PAGE_PREFIX + collectionId)) {
            Integer y = rememberedScroll(collectionId);
            if (y == null) {
                return false;
            }
            if (y == 0 && pages.size() > 1) {
                return false;
            }
            int max = 0;
            int gap = layout.gap(pages);
            for (Board page : pages) {
                max += layout.heightOf(page) + gap;
            }
            return y <= Math.max(0, max);
        }
        int savedCount = prefs.getInt(PREF_SCROLL_PAGES_PREFIX + collectionId, -1);
        String savedPage = prefs.getString(PREF_SCROLL_PAGE_PREFIX + collectionId, null);
        if (savedCount != pages.size() || savedPage == null) {
            return false;
        }
        for (Board page : pages) {
            if (savedPage.equals(page.id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Jump → Restore (fingerprint) → First open. {@code pages} must already be a
     * Ready projection (or trusted reconcile) the caller is willing to paint.
     */
    public int resolveTargetY(String collectionId, Integer keepScroll, List<Board> pages,
                              boolean scratchpad, PageLayout layout) {
        if (keepScroll != null) {
            return Math.max(0, keepScroll);
        }
        Integer remembered = rememberedScroll(collectionId);
        if (remembered != null && fingerprintMatches(collectionId, pages, layout)) {
            return remembered;
        }
        if (scratchpad) {
            int target = 0;
            int gap = layout.gap(pages);
            for (int i = 0; i < pages.size() - 1; i++) {
                target += layout.heightOf(pages.get(i)) + gap;
            }
            return target;
        }
        return layout.lastInkOffset(pages);
    }

    public String anchorPageId(List<Board> pages, int scrollY, PageLayout layout) {
        if (pages == null || pages.isEmpty() || layout == null) {
            return null;
        }
        int y = Math.max(0, scrollY);
        int gap = layout.gap(pages);
        int cursor = 0;
        for (int i = 0; i < pages.size(); i++) {
            int span = layout.heightOf(pages.get(i)) + gap;
            if (y < cursor + span || i == pages.size() - 1) {
                return pages.get(i).id;
            }
            cursor += span;
        }
        return pages.get(pages.size() - 1).id;
    }

    /** Layout measurements supplied by the activity (page heights / gaps). */
    public interface PageLayout {
        int heightOf(Board page);

        int gap(List<Board> pages);

        int lastInkOffset(List<Board> pages);
    }
}
