package com.zetteldraw.penpoc;

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
    }

    @Test
    public void moreGlyphCenterMatchesLinkWithinTwoDp() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo book = repo.createNotebook("chrome");
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        click(root, "chrome");
        idleForPaint();
        ink(repo, book.id, 40f);
        click(root, "chrome");
        idleForPaint();
        relayout(root);
        // Drain moreButton.post(opticalAlign…).
        idle();
        relayout(root);
        idle();

        TextView link = find(root, "Link");
        TextView more = find(root, "⋮");
        assertNotNull(link);
        assertNotNull(more);
        float linkCenter = glyphCenterOnScreen(link);
        float moreCenter = glyphCenterOnScreen(more);
        float density = root.getResources().getDisplayMetrics().density;
        float deltaDp = (moreCenter - linkCenter) / density;
        System.out.println("CHROME_OPTICAL linkCenter=" + linkCenter
                + " moreCenter=" + moreCenter
                + " deltaDp=" + deltaDp
                + " morePadT/B=" + more.getPaddingTop() + "/" + more.getPaddingBottom()
                + " moreTy=" + more.getTranslationY());

        Bitmap full = render(root);
        Bitmap crop = cropChromeBand(full, link, more);
        annotateCenters(crop, link, more, full);
        save(crop, "page-chrome-optical.png");

        // Positive delta ⇒ ⋮ sits lower than Link. Optical align zeros this.
        assertTrue("⋮ glyph center vs Link deltaDp=" + deltaDp + " (want |δ|≤0.75)",
                Math.abs(deltaDp) <= 0.75f);

        controller.pause().stop().destroy();
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

    private static Bitmap cropChromeBand(Bitmap full, TextView link, TextView more) {
        int[] linkLoc = new int[2];
        int[] moreLoc = new int[2];
        link.getLocationOnScreen(linkLoc);
        more.getLocationOnScreen(moreLoc);
        int top = Math.max(0, Math.min(linkLoc[1], moreLoc[1]) - 24);
        int bottom = Math.min(full.getHeight(),
                Math.max(linkLoc[1] + link.getHeight(), moreLoc[1] + more.getHeight()) + 24);
        int left = 0;
        int right = full.getWidth();
        return Bitmap.createBitmap(full, left, top, right - left, bottom - top);
    }

    private static void annotateCenters(Bitmap crop, TextView link, TextView more, Bitmap full) {
        int[] linkLoc = new int[2];
        int[] moreLoc = new int[2];
        link.getLocationOnScreen(linkLoc);
        more.getLocationOnScreen(moreLoc);
        int top = Math.max(0, Math.min(linkLoc[1], moreLoc[1]) - 24);
        Canvas c = new Canvas(crop);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeWidth(2f);
        p.setColor(Color.RED);
        float linkY = glyphCenterOnScreen(link) - top;
        float moreY = glyphCenterOnScreen(more) - top;
        c.drawLine(0, linkY, crop.getWidth(), linkY, p);
        p.setColor(Color.BLUE);
        c.drawLine(0, moreY, crop.getWidth(), moreY, p);
    }

    private static void ink(BoardRepository repo, String notebookId, float y) {
        List<Board> pages = repo.pages(notebookId);
        Board page = pages.get(pages.size() - 1);
        page.paper.appendStroke(TestStrokes.stroke(4f, y), page.id);
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
