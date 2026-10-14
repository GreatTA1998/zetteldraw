package com.zetteldraw.penpoc.nav;

import android.content.SharedPreferences;

import com.zetteldraw.penpoc.Board;

import java.util.HashMap;
import java.util.List;

/**
 * Sole owner of per-collection <em>scroll</em> intent (Jump → Restore → First-open).
 *
 * <p><b>Not</b> the source of truth for page count. Length comes only from the
 * Ready ink-log ({@code .zdl} → {@code NotebookPaper.sliceCount} + trailing-blank
 * projection in the repository). Prefs must never gate or freeze how many pages
 * the UI may paint — that was the v80 trap that normalized a truncated 23-page
 * Scratchpad after a short disk install.
 *
 * <p>CanvasActivity paints Ready {@code pages()} (or waits until Ready). This
 * module only answers where to scroll once that list is already chosen.
 *
 * <p>See docs/notebook-nav-correctness.md (agent store).
 */
public final class CollectionNav {
    public static final String PREF_SCROLL_PREFIX = "scroll_";
    public static final String PREF_SCROLL_PAGES_PREFIX = "scrollPages_";
    public static final String PREF_SCROLL_PAGE_PREFIX = "scrollPage_";

    /** Legacy v80 key — cleared on {@link #clear} so old trusts cannot linger. */
    public static final String PREF_TRUST_PAGES_PREFIX = "trustPages_";

    private final SharedPreferences prefs;
    private final HashMap<String, Integer> scrollByCollection = new HashMap<>();

    public CollectionNav(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** Drop scroll (+ any legacy trust mark) when a notebook is deleted. */
    public void clear(String collectionId) {
        if (collectionId == null) {
            return;
        }
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
        // Drop leftover v80 length-trust so it cannot affect a later install.
        edit.remove(PREF_TRUST_PAGES_PREFIX + collectionId);
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
     * Jump → Restore (fingerprint) → First open. {@code pages} must already be the
     * Ready list the caller is painting (repository SoT).
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
