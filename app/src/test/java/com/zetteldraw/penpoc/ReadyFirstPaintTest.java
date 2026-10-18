package com.zetteldraw.penpoc;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.data.NotebookPaper;
import com.zetteldraw.penpoc.data.TestStrokes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ready first paint must schedule visible-slice work on {@link UiExecutors#renderer}
 * so {@code renderSlice} is not done on the UI thread (v146 ANR on dense Scratchpad).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, shadows = IdleSurfaceViewShadow.class)
public class ReadyFirstPaintTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    private final List<Runnable> surface = new ArrayList<>();

    @Before
    public void setUp() {
        UiExecutors.useSynchronousForTest();
        UiExecutors.surfaceForTest = surface::add;
    }

    @After
    public void tearDown() {
        UiExecutors.surfaceForTest = null;
        UiExecutors.useSynchronousForTest();
    }

    @Test
    public void readyFirstSetPagesSchedulesRendererNotMainThreadSlice() {
        AtomicInteger renderJobs = new AtomicInteger();
        UiExecutors.renderer = runnable -> {
            renderJobs.incrementAndGet();
            runnable.run();
        };

        NotebookPaper paper = NotebookPaper.empty(1000);
        paper.appendStroke(TestStrokes.stroke(40f, 60f));
        Board page = new Board("ready-0", 1L);
        page.paper = paper;
        page.sliceIndex = 0;
        page.paperOrigin = 0f;
        page.slicePx = 1000;
        // Ready clears page.strokes; leave them empty so a sameStrokes finish gate would fail.
        page.strokes.clear();

        PageInkView ink = new PageInkView(context);
        ink.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        ink.layout(0, 0, 600, 1000);

        ink.setPages(Arrays.asList(page), new int[]{1000}, 24, 0);
        ShadowLooper.idleMainLooper();
        // finishRender posts install back onto main.
        ShadowLooper.idleMainLooper();

        assertTrue("Ready first setPages must queue UiExecutors.renderer (v146 skipped paper jobs)",
                renderJobs.get() > 0);
    }
}
