package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.data.TestInk;
import com.zetteldraw.penpoc.data.BoardRepository;
import com.zetteldraw.penpoc.data.TestStrokes;
import com.zetteldraw.penpoc.data.ZettelData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.List;

/**
 * Measures Link vs ⋮ glyph visual centers and crops page-chrome screenshots
 * into {@code app/build/screenshots/} (copied to the Project store media/).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33, application = Application.class, qualifiers = "w632dp-h840dp-xhdpi",
        shadows = IdleSurfaceViewShadow.class)
public class PageChromeOpticalTest {
    @Before
    public void freshRepository() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();

        BoardRepository _repo = ZettelData.repository(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());
        _repo.ensureSheet(null, 800);
        if (_repo.scratchpadPages().isEmpty()) {
            _repo.createPage(null);
        }

    }

    @Test
    public void pageChromeHitMatchesVisibleLabel() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo book = repo.createNotebook("chrome");
        repo.ensureSheet(book.id(), 800);
        if (repo.pages(book.id()).isEmpty()) {
            repo.createPage(book.id());
        }
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        NavClicks.openNotebook(root, "chrome");
        idleForPaint();
        ink(repo, book.id(), 40f);
        NavClicks.openNotebook(root, "chrome");
        idleForPaint();
        relayout(root);
        idle();

        TextView link = find(root, "Link");
        TextView reorder = find(root, "Reorder");
        TextView move = find(root, "Move");
        TextView more = find(root, "⋮");
        assertNotNull(link);
        assertNotNull(reorder);
        assertNotNull(move);
        assertNotNull(more);
        assertZeroHitSlop(link);
        assertZeroHitSlop(reorder);
        assertZeroHitSlop(move);
        assertZeroHitSlop(more);

        View pageSlot = pageSlotOf(link);
        assertNotNull(pageSlot);
        // Pre-v173 optical gutters restored as margin (outside hit rect).
        assertEquals(dp(root, 14), ((ViewGroup.MarginLayoutParams) ((View) link.getParent().getParent())
                .getLayoutParams()).leftMargin);
        assertEquals(dp(root, 14), ((ViewGroup.MarginLayoutParams) reorder.getLayoutParams()).leftMargin);
        assertEquals(dp(root, 20), ((ViewGroup.MarginLayoutParams) more.getLayoutParams()).leftMargin);
        assertEquals(dp(root, 18), ((ViewGroup.MarginLayoutParams) ((View) move.getParent())
                .getLayoutParams()).rightMargin);
        Bitmap full = render(root);
        Bitmap crop = cropChromeBandFromSlot(full, pageSlot, link, more);
        annotateCentersFromSlot(crop, pageSlot, link, more);
        String outName = System.getProperty("zd.chromeShot", "page-chrome-optical.png");
        save(crop, outName);

        controller.pause().stop().destroy();
    }

    /** Prefs: page chrome padding 0; no 48dp min / secret hit expansion. */
    private static void assertZeroHitSlop(TextView chrome) {
        assertTrue(chrome.getPaddingLeft() == 0
                && chrome.getPaddingTop() == 0
                && chrome.getPaddingRight() == 0
                && chrome.getPaddingBottom() == 0);
        assertTrue(chrome.getMinWidth() == 0 && chrome.getMinimumWidth() == 0);
        assertTrue(chrome.getMinHeight() == 0 && chrome.getMinimumHeight() == 0);
    }

    private static int dp(View any, int value) {
        return Math.round(value * any.getResources().getDisplayMetrics().density);
    }

    /** Glyph visual center Y on screen (text-bounds midline at baseline). */
    static float glyphCenterOnScreen(TextView tv) {
        Rect bounds = new Rect();
        String text = tv.getText().toString();
        tv.getPaint().getTextBounds(text, 0, text.length(), bounds);
        int[] loc = new int[2];
        tv.getLocationOnScreen(loc);
        return loc[1] + tv.getTranslationY() + tv.getBaseline()
                + (bounds.top + bounds.bottom) / 2f;
    }

    /** PageSlot = leftRow → left column → PageSlot FrameLayout. */
    private static View pageSlotOf(View link) {
        View leftRow = (View) link.getParent();
        if (leftRow == null) {
            return null;
        }
        View left = (View) leftRow.getParent();
        if (left == null) {
            return null;
        }
        return (View) left.getParent();
    }

    /**
     * Bottom-anchored crop: fixed band ending at the page slot bottom (dashed
     * edge), so a shared chromeBottom nudge is visible vs the dash.
     */
    private static Bitmap cropChromeBandFromSlot(
            Bitmap full, View pageSlot, TextView link, TextView more) {
        int[] slotLoc = new int[2];
        pageSlot.getLocationOnScreen(slotLoc);
        int bottom = Math.min(full.getHeight(), slotLoc[1] + pageSlot.getHeight() + 8);
        int band = Math.round(120f * full.getWidth() / 632f); // ~120dp at this width
        int top = Math.max(0, bottom - band);
        return Bitmap.createBitmap(full, 0, top, full.getWidth(), bottom - top);
    }

    private static void annotateCentersFromSlot(
            Bitmap crop, View pageSlot, TextView link, TextView more) {
        int[] slotLoc = new int[2];
        pageSlot.getLocationOnScreen(slotLoc);
        int bottom = Math.min(
                slotLoc[1] + pageSlot.getHeight() + 8,
                slotLoc[1] + pageSlot.getHeight() + 8);
        int band = crop.getHeight();
        int top = bottom - band;
        Canvas c = new Canvas(crop);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeWidth(2f);
        // Green = dashed-edge reference (page slot bottom).
        p.setColor(Color.rgb(0, 160, 0));
        float dashY = slotLoc[1] + pageSlot.getHeight() - top;
        c.drawLine(0, dashY, crop.getWidth(), dashY, p);
        p.setColor(Color.RED);
        c.drawLine(0, glyphCenterOnScreen(link) - top, crop.getWidth(),
                glyphCenterOnScreen(link) - top, p);
        p.setColor(Color.BLUE);
        c.drawLine(0, glyphCenterOnScreen(more) - top, crop.getWidth(),
                glyphCenterOnScreen(more) - top, p);
    }

    private static void ink(BoardRepository repo, String notebookId, float y) {
        List<Board> pages = repo.pages(notebookId);
        Board page = pages.get(pages.size() - 1);
        TestInk.appendCovering(page.paper, TestStrokes.stroke(4f, y), page.id);
        repo.saveInk(page);
    }

    private static Bitmap render(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        view.draw(canvas);
        return bitmap;
    }

    private static void save(Bitmap bitmap, String name) throws Exception {
        File dir = new File(System.getProperty("zd.repoRoot", "."), "app/build/screenshots");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private static void relayout(View root) {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getWidth(), root.getHeight());
        idle();
    }

    private static void click(View root, String text) {
        TextView view = find(root, text);
        assertNotNull(text, view);
        view.performClick();
        idle();
    }

    private static TextView find(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }

    private static void idleForPaint() {
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
    }
}
