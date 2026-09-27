package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.InkRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class InkCodecTest {
    @Test
    public void roundTripsEveryPointField() throws IOException {
        List<InkRenderer.InkStroke> strokes = new ArrayList<>();
        strokes.add(TestInk.stroke(10f, 20f, 40));
        strokes.add(TestInk.stroke(300f, 400f, 1));
        byte[] bytes = InkCodec.encode(strokes);
        TestInk.assertSameInk(strokes, InkCodec.decode(bytes));
        assertEquals(bytes.length, InkCodec.encode(InkCodec.decode(bytes)).length);
    }

    @Test
    public void isCompact() {
        List<InkRenderer.InkStroke> strokes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            strokes.add(TestInk.stroke(i * 10f, i * 5f, 100));
        }
        int bytes = InkCodec.encode(strokes).length;
        assertTrue("2000 points in " + bytes + " bytes", bytes < 2000 * 28);
    }

    @Test
    public void rejectsForeignFiles() {
        assertThrows(IOException.class, () -> InkCodec.decode(new byte[]{1, 2, 3, 4, 5}));
    }
}
