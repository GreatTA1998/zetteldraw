package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertTrue;

import android.app.Application;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Host-side fixture for emulator crash repro: dense Scratchpad {@code .zdl}.
 *
 * <pre>
 * ./gradlew :app:testDebugUnitTest -Pzetteldraw.emulator \
 *   -Dzd.dense.slices=40 -Dzd.dense.strokes=25 -Dzd.dense.out=/tmp/zd-dense-scratchpad.zdl \
 *   --tests com.zetteldraw.penpoc.data.DenseZdlFixtureWriter
 * </pre>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class DenseZdlFixtureWriter {
    @Test
    public void writeDenseScratchpadZdl() throws Exception {
        int slices = intProp("zd.dense.slices", intEnv("ZD_DENSE_SLICES", 40));
        int strokesPerSlice = intProp("zd.dense.strokes", intEnv("ZD_DENSE_STROKES", 25));
        int page = 1000;
        NotebookPaper paper = NotebookPaper.empty(page);
        for (int i = 0; i < slices; i++) {
            paper.appendStroke(TestInk.stroke(10f, i * page + 20f, 8));
            for (int s = 1; s < strokesPerSlice; s++) {
                float x = 20f + (s % 10) * 40f;
                float y = i * page + 30f + (s / 10) * 50f;
                paper.appendStroke(TestInk.stroke(x, y, 24));
            }
        }
        String outPath = System.getProperty("zd.dense.out",
                System.getenv().getOrDefault("ZD_DENSE_OUT", "/tmp/zd-dense-scratchpad.zdl"));
        File out = new File(outPath);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(paper.bytes());
        }
        System.out.println("wrote " + out.getAbsolutePath() + " bytes=" + out.length()
                + " strokes=" + (slices * strokesPerSlice) + " slices=" + slices);
        assertTrue(out.length() > 10_000);
    }

    private static int intEnv(String key, int fallback) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(raw.trim());
    }

    private static int intProp(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(raw.trim());
    }
}
