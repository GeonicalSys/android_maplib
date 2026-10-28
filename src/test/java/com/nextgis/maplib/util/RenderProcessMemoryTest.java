package com.nextgis.maplib.util;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.StringReader;
import static org.junit.Assert.*;

public class RenderProcessMemoryTest {
    private long rss(String text) throws Exception { return RenderProcessMemory.readRss(new BufferedReader(new StringReader(text))); }
    @Test public void readsResidentBytesInsteadOfVirtualAddressSpace() throws Exception {
        assertEquals(317440, rss("Name:\tapp\nVmSize:\t998877665 kB\nVmRSS:\t310 kB\n"));
    }
    @Test public void missingMalformedNegativeAndOverflowAreUnknownInsteadOfZero() throws Exception {
        for (String input : new String[]{"VmSize: 10 kB", "VmRSS: -4 kB", "VmRSS: abc kB", "VmRSS: 9 MB", "VmRSS: 9223372036854775807 kB"})
            assertEquals(-1, rss(input));
    }
}
