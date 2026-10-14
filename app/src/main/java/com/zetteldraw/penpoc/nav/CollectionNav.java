package com.zetteldraw.penpoc.nav;

import android.content.SharedPreferences;

import com.zetteldraw.penpoc.Board;

import java.util.HashMap;
import java.util.List;

/**
 * Sole owner of per-collection <em>scroll</em> intent (Jump → Restore → First-open).
 *
 * <p><b>Not</b> the source of truth for page count. Length comes only from the
 * Ready ink-log via {@link com.zetteldraw.penpoc.data.InkSheetStore}. Prefs must
 * never gate how many pages the UI may paint (v80 trust trap).
 *
 * <p>See docs/ink-sot-rewrite.md.
 */
public final class CollectionNav {
    public static final String PREF_SCROLL_PREFIX = "scroll_";
    public static final String PREF_SCROLL_PAGES_PREFIX = "scrollPages_";
    public static final String PREF_SCROLL_PAGE_PREFIX = "scrollPage_";

    /** Legacy v80 length-trust — purged on construct; not a SoT. */
    static final String PREF_TRUST_PAGES_PREFIX = "trustPages_";

    private final SharedPreferences prefs;
    private final HashMap<String, Integer> scrollByCollection = new HashMap<>();

    public CollectionNav(SharedPreferences prefs) {
        this.prefs = prefs;
        purgeLegacyTrustKeys();
    }

    /** One-shot: drop any leftover v80 trustPages_* so length cannot come from prefs. */
    private void purgeLegacyTrustKeys() {
        SharedPreferences.Editor edit = null;
        for (String key : prefs.getAll().keySet()) {
            if (key != null && key.startsWith(PREF_TRUST_PAGES_PREFIX)) {
                if (edit == null) {
                    edit = prefs.edit();
                }
                edit.remove(key);
            }
        }
        if (edit != null) {
            edit.apply();
        }
    }

    /** Drop scroll when a notebook is deleted. */
    public void clear(String collectionId) {
        if (collectionId == null) {
            return;
        }
        scrollByCollection.remove(collectionId);
        prefs.edit()
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
