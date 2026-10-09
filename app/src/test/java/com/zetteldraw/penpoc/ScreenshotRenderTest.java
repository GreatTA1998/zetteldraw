package com.zetteldraw.penpoc;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the UI at the Go 7 Color's 1264×1680 (xhdpi) into
 * {@code app/build/screenshots/}. Ink lives on the SurfaceView and is not in these renders.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33, application = Application.class, qualifiers = "w632dp-h840dp-xhdpi",
        shadows = IdleSurfaceViewShadow.class)
public class ScreenshotRenderTest {
    @Before
    public void freshRepository() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();
    }

    @Test
    public void tabBar() throws Exception {
        Application app = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        repo.createNotebook("reading list");
        repo.createNotebook("zetteldraw project");
        repo.createNotebook("ideas");
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View content = controller.get().findViewById(android.R.id.content);
        idle();

        click(content, "journal");
        Bitmap closed = render(content);
        find(content, "journal").performLongClick();
        idle();
        Bitmap open = render(content);

        int band = dp(content, 330);
        int gap = dp(content, 16);
        Bitmap out = Bitmap.createBitmap(closed.getWidth(), band * 2 + gap, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.rgb(200, 200, 200));
        canvas.drawBitmap(Bitmap.createBitmap(closed, 0, 0, closed.getWidth(), band), 0f, 0f, null);
        canvas.drawBitmap(Bitmap.createBitmap(open, 0, 0, open.getWidth(), band), 0f, band + gap, null);
        save(out, "tab-bar-v10.png");
        controller.pause().stop().destroy();
    }

    @Test
    public void pageMenu() throws Exception {
        Application app = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        Board page = repo.pages(Notebook.JOURNAL.uuid).get(0);
        page.strokes.add(TestStrokes.stroke(200f, 300f));
        repo.saveInk(page);
        Board next = repo.pages(Notebook.JOURNAL.uuid).get(1);
        next.strokes.add(TestStrokes.stroke(300f, 300f));
        repo.saveInk(next);

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        CanvasActivity activity = controller.get();
        View content = activity.findViewById(android.R.id.content);
        idle();
        click(content, "journal");
        java.lang.reflect.Field field = CanvasActivity.class.getDeclaredField("pageHeight");
        field.setAccessible(true);
        System.out.println("PAGE_SIZE " + content.getWidth() + "x" + field.getInt(activity)
                + " window=" + content.getWidth() + "x" + content.getHeight());

        List<View> menus = new ArrayList<>();
        collectByDescription(content, "Page options", menus);
        assertTrue(menus.size() >= 2);
        menus.get(0).performClick();
        idle();
        assertNotNull(find(content, "Delete page"));
        save(render(content), "ui-v12.png");
        controller.pause().stop().destroy();
    }

    @Test
    public void toolbar() throws Exception {
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View content = controller.get().findViewById(android.R.id.content);
        idle();
        PageInkView ink = findInk(content);
        java.util.ArrayList<com.onyx.android.sdk.data.note.TouchPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            points.add(new com.onyx.android.sdk.data.note.TouchPoint(100f + i * 6f, 100f + i * 4f, 0.5f, 1f, 0, 0, i));
        }
        ink.addStroke(points);
        idle();
        Bitmap pen = render(content);
        click(content, R.string.undo);
        click(content, R.string.eraser);
        Bitmap eraser = render(content);
        click(content, R.string.lasso);
        Bitmap lasso = render(content);

        int band = dp(content, 128);
        int gap = dp(content, 12);
        Bitmap out = Bitmap.createBitmap(pen.getWidth(), band * 3 + gap * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.rgb(200, 200, 200));
        Bitmap[] states = {pen, eraser, lasso};
        for (int i = 0; i < states.length; i++) {
            canvas.drawBitmap(Bitmap.createBitmap(states[i], 0, 0, states[i].getWidth(), band),
                    0f, i * (band + gap), null);
        }
        save(out, "toolbar-v11.png");
        controller.pause().stop().destroy();
    }

    private static void click(View root, int description) {
        String text = root.getResources().getString(description);
        List<View> views = new ArrayList<>();
        collectByDescription(root, text, views);
        assertTrue(text, views.size() == 1);
        views.get(0).performClick();
        idle();
    }

    private static PageInkView findInk(View view) {
        if (view instanceof PageInkView) {
            return (PageInkView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                PageInkView found = findInk(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
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

    private static void collectByDescription(View view, String description, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (description.contentEquals(String.valueOf(view.getContentDescription()))) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectByDescription(group.getChildAt(i), description, out);
            }
        }
    }
}
