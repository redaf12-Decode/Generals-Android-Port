// GeneralsX @build Android security 10/11/2026
//
// The account's session and refresh tokens live in two places -- the marker file
// files/generalsonline_session.txt and the SharedPreferences file beside it -- and the support
// log describes this device's traffic. None of them may travel in a backup. The rules that say
// so are XML resources, so these tests read the merged resources and assert the exclusions are
// actually present (a typo in a path would silently back the tokens up).

package com.ports.generalsx;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
public class BackupRulesTest {

    private static String readRaw(Context ctx, int resId) throws Exception {
        try (InputStream in = ctx.getResources().openRawResource(resId)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void android11BackupRulesExcludeTheSessionAndTraceData() throws Exception {
        String xml = readRaw(RuntimeEnvironment.getApplication(), R.xml.backup_rules);
        assertNotNull(xml);
        assertTrue("the session marker must be excluded from backup",
            xml.contains("generalsonline_session.txt"));
        assertTrue("the session preferences must be excluded from backup",
            xml.contains("generalsonline_session.xml"));
        assertTrue("the network trace log must be excluded from backup",
            xml.contains("generals-network.log"));
    }

    @Test
    public void android12ExtractionRulesExcludeTheSameDataForCloudAndTransfer() throws Exception {
        String xml = readRaw(RuntimeEnvironment.getApplication(), R.xml.data_extraction_rules);
        assertNotNull(xml);
        assertTrue(xml.contains("generalsonline_session.txt"));
        assertTrue(xml.contains("generalsonline_session.xml"));
        assertTrue("both cloud backup and device transfer must exclude the tokens",
            xml.indexOf("cloud-backup") >= 0 && xml.indexOf("device-transfer") >= 0);
    }
}
