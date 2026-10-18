package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Corrupt on-disk {@code .zdl} must heal-in-place: Ready opens without throwing,
 * good prefix kept when possible, header-fatal sheets quarantine + empty Ready
 * so other notebooks stay usable. Never wipe the library.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class CorruptLogEnsureSheetTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;

    @Before
    public void setUp() throws Exception {
        device = new Device("corrupt-log", tmp.newFolder("device"), 4_000_000L);
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void ensureSheetHealsAbsurdStrokeTailAndKeepsPriorInk() throws Exception {
        String sheetId = NotebookPaper.sheetId(null);
        NotebookPaper paper = NotebookPaper.empty(Device.PAGE);
        // Seed a real slice — appendStroke alone no longer grows pages.
        TestInk.appendCovering(paper, TestInk.stroke(10f, 20f, 8));
        byte[] base = paper.bytes();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        byte[] id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa".getBytes(StandardCharsets.UTF_8);
        out.writeShort(id.length);
        out.write(id);
        out.writeInt(80_000_000);
        out.writeLong(1L);
        byte[] body = payload.toByteArray();
        ByteBuffer record = ByteBuffer.allocate(5 + body.length).order(ByteOrder.BIG_ENDIAN);
        record.put((byte) 1);
        record.putInt(body.length);
        record.put(body);
        byte[] corrupt = new byte[base.length + record.position()];
        System.arraycopy(base, 0, corrupt, 0, base.length);
        System.arraycopy(record.array(), 0, corrupt, base.length, record.position());
        writeLog(sheetId, corrupt);

        assertTrue(device.ensureReady());
        assertTrue(device.repo.isSheetReady(null));
        assertEquals(1, device.repo.scratchpadPages().size());
        assertEquals(1, device.repo.scratchpadPages().get(0).paper.strokes().size());
        // Healed rewrite shorter than corrupt seed.
        assertTrue(new File(device.inkDir(), sheetId + ".zdl").length() <= base.length);
    }

    @Test
    public void ensureSheetQuarantinesUnreadableHeaderAndOpensEmptyReady() throws Exception {
        String sheetId = NotebookPaper.sheetId(null);
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 'Z').put((byte) 'D').put((byte) 'L').put((byte) 1);
        buf.putInt(Device.PAGE);
        buf.putInt(1);
        buf.putInt(Device.PAGE);
        buf.putShort((short) 36);
        buf.put(new byte[]{9, 9, 9, 9});
        writeLog(sheetId, Arrays.copyOf(buf.array(), buf.position()));

        assertTrue(device.ensureReady());
        assertTrue(device.repo.isSheetReady(null));
        // Original moved aside — not deleted.
        File[] corrupt = device.inkDir().listFiles((dir, name) -> name.contains(".zdl.corrupt"));
        assertTrue(corrupt != null && corrupt.length >= 1);
        // Empty Ready is valid — Create new page only; do not require a slice.
        assertTrue(device.repo.scratchpadPages().isEmpty());
    }

    @Test
    public void ensureSheetStillOpensValidLog() throws Exception {
        assertTrue(device.ensureReady());
        assertTrue(device.repo.isSheetReady(null));
    }

    private void writeLog(String sheetId, byte[] bytes) throws Exception {
        File file = new File(device.inkDir(), sheetId + ".zdl");
        //noinspection ResultOfMethodCallIgnored
        device.inkDir().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
