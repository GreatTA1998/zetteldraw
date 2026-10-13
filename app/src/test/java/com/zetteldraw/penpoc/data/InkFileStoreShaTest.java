package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = android.app.Application.class)
public class InkFileStoreShaTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void sha256FileMatchesByteArray() throws Exception {
        byte[] bytes = new byte[400_000];
        Arrays.fill(bytes, (byte) 0x5a);
        File file = tmp.newFile("big.zdl");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        assertEquals(InkFileStore.sha256(bytes), InkFileStore.sha256File(file));
        assertTrue(bytes.length > SyncStore.INLINE_BLOB_MAX_BYTES);
    }

    @Test
    public void copyAndSha256StreamsWithoutChangingHash() throws Exception {
        byte[] bytes = new byte[300_000];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        File file = tmp.newFile("stream.zdl");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        String hash = InkFileStore.copyAndSha256(file, sink);
        assertEquals(InkFileStore.sha256(bytes), hash);
        assertTrue(Arrays.equals(bytes, sink.toByteArray()));
    }
}
