package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LocationDiagnosticFormatTest {
    @Test
    public void incomingIncludesActionAndCoordinates() {
        String line = LocationDiagnosticFormat.incoming(
                "gps", 0.12f, 40L, false, true, 18, 55.75, 37.61, "accept");
        assertTrue(line.contains("action=accept"));
        assertTrue(line.contains("nmea=true"));
        assertTrue(line.contains("lat=55.75"));
    }

    @Test
    public void unavailableIncludesReason() {
        assertEquals(
                "GNSS publish unavailable reason=stale gpsAgeMs=8120 gpsEnabled=true netAgeMs=-1 external=true",
                LocationDiagnosticFormat.unavailable("stale", 8120L, true, -1L, true));
    }

    @Test
    public void stakeoutWaitingIncludesAge() {
        String line = LocationDiagnosticFormat.stakeoutWaiting(true, -80L, "gps", 0.04f);
        assertTrue(line.contains("waitingForFix=true"));
        assertTrue(line.contains("ageMs=-80"));
    }
}
