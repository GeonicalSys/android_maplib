package com.nextgis.maplib.util;

import android.location.Location;
import android.os.Bundle;

/** Converts WGS84 fixes to a local metric frame for the platform-independent motion filter. */
final class LocationMotionFilter {
    static final String STOP_ID = "com.nextgis.maplib.stationaryStop";
    static final String ANCHOR_LAT = "com.nextgis.maplib.stationaryLatitude";
    static final String ANCHOR_LON = "com.nextgis.maplib.stationaryLongitude";
    static final String DEPARTURE_SINCE = "com.nextgis.maplib.departureSince";
    private static final double EARTH_RADIUS = 6_378_137d;
    private final AdaptiveLocationFilterCore core = new AdaptiveLocationFilterCore();
    private boolean initialized;
    private double latitude, longitude, scale;
    private Location displayLocation;

    void reset() { initialized = false; displayLocation = null; core.reset(); }
    long getRejectedCount() { return core.getRejectedCount(); }
    Location getDisplayLocation() { return new Location(displayLocation); }

    Location filter(Location raw, DeviceMotionEvidence.State motion) {
        if (!initialized) {
            latitude = raw.getLatitude();
            longitude = raw.getLongitude();
            scale = Math.max(0.00001, Math.cos(Math.toRadians(latitude)));
            initialized = true;
        }
        double deltaLon = Math.IEEEremainder(raw.getLongitude() - longitude, 360);
        double x = Math.toRadians(deltaLon) * EARTH_RADIUS * scale;
        double y = Math.toRadians(raw.getLatitude() - latitude) * EARTH_RADIUS;
        AdaptiveLocationFilterCore.Estimate result = core.onSample(new AdaptiveLocationFilterCore.Sample(
                x, y, raw.getElapsedRealtimeNanos() / 1_000_000L, raw.getAccuracy(),
                raw.hasSpeed() ? raw.getSpeed() : Double.NaN,
                raw.hasSpeedAccuracy() ? raw.getSpeedAccuracyMetersPerSecond() : Double.NaN,
                raw.hasBearing() ? raw.getBearing() : Double.NaN, motion));
        if (result == null) return null;
        Location output = new Location(raw);
        output.setLatitude(latitude + Math.toDegrees(result.candidateY / EARTH_RADIUS));
        output.setLongitude(Math.IEEEremainder(longitude
                + Math.toDegrees(result.candidateX / (EARTH_RADIUS * scale)), 360));
        output.setAccuracy((float) result.accuracy);
        Bundle extras = output.getExtras() == null ? new Bundle() : new Bundle(output.getExtras());
        extras.putLong(STOP_ID, result.stopId);
        extras.putLong(DEPARTURE_SINCE, result.departureSinceMs);
        extras.putDouble(ANCHOR_LAT, latitude + Math.toDegrees(result.y / EARTH_RADIUS));
        extras.putDouble(ANCHOR_LON, Math.IEEEremainder(longitude
                + Math.toDegrees(result.x / (EARTH_RADIUS * scale)), 360));
        output.setExtras(extras);
        displayLocation = anchor(output);
        return output;
    }

    static Location anchor(Location point) {
        Location output = new Location(point);
        Bundle extras = point.getExtras();
        if (extras != null && extras.getLong(STOP_ID, 0) != 0) {
            output.setLatitude(extras.getDouble(ANCHOR_LAT, point.getLatitude()));
            output.setLongitude(extras.getDouble(ANCHOR_LON, point.getLongitude()));
            output.setSpeed(0);
            output.removeBearing();
        }
        return output;
    }
}
