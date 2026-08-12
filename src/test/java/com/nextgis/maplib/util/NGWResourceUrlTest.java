package com.nextgis.maplib.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class NGWResourceUrlTest {

    @Test
    public void standardNextgisUrl_isParsed() {
        NGWResourceUrl parsed = NGWResourceUrl.parse(
                "https://demo.nextgis.com/resource/123");

        assertEquals("https://demo.nextgis.com", parsed.getServerUrl());
        assertEquals("demo.nextgis.com", parsed.getAccountName());
        assertEquals(123L, parsed.getResourceId());
    }

    @Test
    public void customServerPathAndPort_arePreserved() {
        NGWResourceUrl parsed = NGWResourceUrl.parse(
                "https://gis.example.org:8443/ngw/resource/42/?source=share");

        assertEquals("https://gis.example.org:8443/ngw", parsed.getServerUrl());
        assertEquals("gis.example.org:8443/ngw", parsed.getAccountName());
        assertEquals(42L, parsed.getResourceId());
        assertTrue(parsed.matchesServerUrl("https://GIS.EXAMPLE.ORG:8443/ngw/"));
        assertFalse(parsed.matchesServerUrl("https://gis.example.org/ngw"));
    }

    @Test
    public void unsupportedOrAmbiguousUrls_areRejected() {
        assertRejected("ftp://demo.nextgis.com/resource/1");
        assertRejected("https://demo.nextgis.com/resource/not-a-number");
        assertRejected("https://demo.nextgis.com/resource/1/extra");
        assertRejected("https://user:secret@demo.nextgis.com/resource/1");
        assertRejected("https://demo.nextgis.com/resource/0");
    }

    private static void assertRejected(String value) {
        try {
            NGWResourceUrl.parse(value);
            fail("Expected URL to be rejected: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
