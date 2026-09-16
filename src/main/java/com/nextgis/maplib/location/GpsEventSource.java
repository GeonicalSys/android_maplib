/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2016, 2019 NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextgis.maplib.location;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.GnssStatus;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;
import android.content.SharedPreferences;

import com.nextgis.maplib.api.ExternalGnssKeepAliveHost;
import com.nextgis.maplib.api.GpsEventListener;
import com.nextgis.maplib.gnss.ExternalGnssSession;
import com.nextgis.maplib.gnss.GnssFix;
import com.nextgis.maplib.gnss.GnssInputPrefs;
import com.nextgis.maplib.gnss.GnssTransportFactory;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.DiagnosticLog;
import com.nextgis.maplib.util.ExternalGnssFixPolicy;
import com.nextgis.maplib.util.LocationDiagnosticFormat;
import com.nextgis.maplib.util.LocationFixPolicy;
import com.nextgis.maplib.util.LocationTrackFilter;
import com.nextgis.maplib.util.PermissionUtil;
import com.nextgis.maplib.util.SettingsConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Application-owned acquisition, current display state and GNSS-only recording stream. */
@SuppressLint("MissingPermission")
public class GpsEventSource {
    public static final int GPS_PROVIDER = 1;
    public static final int NETWORK_PROVIDER = 2;

    public interface RecordingListener {
        void onRecordingLocation(Location location);
        default void onRecordingUnavailable() { }
        default void onRecordingFlushComplete() { }
    }

    private final Context context;
    protected final LocationManager mLocationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<GpsEventListener> listeners = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<GpsEventListener> rawListeners = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> highFrequencyClients = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<RecordingListener, Long> recorders = new IdentityHashMap<>();
    private final LocationTrackFilter filter = new LocationTrackFilter();
    private Location gps, network, rawGps, lastPublished, lastMock, lastReceiverMock;
    private GnssFix lastExternalFix;
    private boolean recordingAvailable, statusRegistered;
    private final SharedPreferences prefs;
    private final ExternalGnssSession externalSession;
    private long subscriptionStartedNanos;
    private final LocationSubscriptionController subscriptions;
    private final SensorMotionMonitor motionMonitor;
    private PowerManager.WakeLock recordingWakeLock;
    private long gpsRequests, gpsStops, lastDiagnosticAt, diagnosticFixes, diagnosticMaxGapMs;
    private float diagnosticMaxAccuracy;
    private Boolean diagnosticScreenOn;
    private final SharedPreferences.OnSharedPreferenceChangeListener gnssPrefListener;
    private final ExternalGnssKeepAliveHost keepAliveHost;
    private boolean postedKeepAlive;

    private final Runnable expiry = new Runnable() {
        @Override public void run() {
            if (!hasConsumers() && !keepExternalSession()) return;
            if (!PermissionUtil.hasAnyLocationPermission(context) && !isExternalInput()) {
                gps = network = rawGps = lastMock = lastReceiverMock = null;
            } else if (!PermissionUtil.hasAnyLocationPermission(context)) {
                network = null;
            }
            publishCurrent();
            logRecordingHealth();
            handler.postDelayed(this, 500);
        }
    };

    private final GnssStatus.Callback status = new GnssStatus.Callback() {
        @Override public void onStarted() { publishStatus(GpsStatus.GPS_EVENT_STARTED); }
        @Override public void onStopped() { publishStatus(GpsStatus.GPS_EVENT_STOPPED); }
        @Override public void onFirstFix(int ttffMillis) { publishStatus(GpsStatus.GPS_EVENT_FIRST_FIX); }
        @Override public void onSatelliteStatusChanged(GnssStatus value) {
            publishStatus(GpsStatus.GPS_EVENT_SATELLITE_STATUS);
        }
    };

    private final LocationListener gpsListener = createLocationListener();
    private final LocationListener networkListener = createLocationListener();

    private LocationListener createLocationListener() { return new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            processLocation(location, false);
            publishCurrent();
        }

        @Override public void onLocationChanged(List<Location> locations) {
            List<Location> ordered = new ArrayList<>(locations);
            ordered.sort(Comparator.comparingLong(Location::getElapsedRealtimeNanos));
            for (Location location : ordered) {
                long fix = location.getElapsedRealtimeNanos();
                long now = SystemClock.elapsedRealtimeNanos();
                // Only measurements made during this live subscription qualify as a batch.
                if (fix >= subscriptionStartedNanos && fix <= now + 1_000_000_000L) {
                    processLocation(location, true);
                }
            }
            publishCurrent();
        }

        @Override public void onProviderDisabled(String provider) {
            DiagnosticLog.v("GNSS provider disabled=" + provider);
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                flushRecordingLocations();
            gps = rawGps = lastMock = lastReceiverMock = null;
                filter.reset();
            } else if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
                network = null;
            }
            publishCurrent();
        }
        @Override public void onProviderEnabled(String provider) {
            DiagnosticLog.v("GNSS provider enabled=" + provider);
            refreshLocationRequests();
        }
        @Override public void onStatusChanged(String provider, int value, Bundle extras) { }
    }; }

    public GpsEventSource(Context context) {
        this.context = context.getApplicationContext();
        keepAliveHost = context instanceof ExternalGnssKeepAliveHost
                ? (ExternalGnssKeepAliveHost) context : null;
        // Retire obsolete source switches: map uses every available source; recording uses GNSS.
        this.context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE)
                .edit().putString("location_source", "3").putString("tracks_location_source", "1").apply();
        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        motionMonitor = new SensorMotionMonitor(this.context, handler);
        subscriptions = new LocationSubscriptionController(new LocationSubscriptionController.Backend() {
            @Override public boolean requestGps(long intervalMs) {
                boolean started = requestProvider(LocationManager.GPS_PROVIDER, intervalMs, gpsListener);
                if (started) {
                    gpsRequests++;
                    motionMonitor.start();
                    if (!statusRegistered) {
                        try { statusRegistered = mLocationManager.registerGnssStatusCallback(status, handler); }
                        catch (RuntimeException exception) { Log.w(Constants.TAG, "GNSS status unavailable", exception); }
                    }
                    HyperLog.i(Constants.TAG, "GPS request intervalMs=" + intervalMs + " requests=" + gpsRequests);
                }
                return started;
            }
            @Override public void stopGps() {
                removeProvider(gpsListener);
                motionMonitor.stop();
                gpsStops++;
                try {
                    if (statusRegistered) mLocationManager.unregisterGnssStatusCallback(status);
                } catch (RuntimeException exception) { Log.w(Constants.TAG, "GNSS status cleanup failed", exception); }
                statusRegistered = false;
                HyperLog.i(Constants.TAG, "GPS subscription stopped count=" + gpsStops);
            }
            @Override public boolean requestNetwork() {
                return requestProvider(LocationManager.NETWORK_PROVIDER, 2_000L, networkListener);
            }
            @Override public void stopNetwork() { removeProvider(networkListener); }
            @Override public void setRecordingActive(boolean active) { setRecordingResources(active); }
        });
        prefs = this.context.getSharedPreferences(context.getPackageName() + "_preferences",
                Context.MODE_PRIVATE);
        externalSession = new ExternalGnssSession(this.context, prefs);
        externalSession.setCallback(new ExternalGnssSession.Callback() {
            @Override public void onExternalLocation(Location location) {
                processLocation(location, false);
                publishCurrent();
            }
            @Override public void onExternalFix(GnssFix fix) {
                lastExternalFix = fix;
                publishExternalFix(fix);
            }
            @Override public void onExternalStatus(String status) {
                publishExternalStatus(status);
            }
        });
        gnssPrefListener = (preferences, key) -> {
            if (SettingsConstants.KEY_PREF_GNSS_INPUT.equals(key)
                    || SettingsConstants.KEY_PREF_GNSS_TRANSPORT.equals(key)
                    || SettingsConstants.KEY_PREF_GNSS_DEVICE_ID.equals(key)
                    || SettingsConstants.KEY_PREF_GNSS_TCP_HOST.equals(key)
                    || SettingsConstants.KEY_PREF_GNSS_TCP_PORT.equals(key)) {
                gps = network = rawGps = lastPublished = lastMock = lastReceiverMock = null;
                lastExternalFix = null;
                filter.reset();
                externalSession.restart();
                refreshLocationRequests();
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(gnssPrefListener);
        DiagnosticLog.attach(prefs);
        refreshLocationRequests();
    }

    public void addListener(GpsEventListener listener) {
        if (listeners.add(listener)) {
            refreshLocationRequests();
            Location current = getLastKnownLocation();
            if (current == null) listener.onLocationUnavailable();
            else listener.onLocationChanged(current);
            if (lastExternalFix != null) listener.onExternalGnssFix(lastExternalFix);
            listener.onExternalGnssStatus(externalSession.status());
        }
    }

    public void removeListener(GpsEventListener listener) {
        listeners.remove(listener);
        refreshLocationRequests();
    }

    /** Precision tools consume original GNSS/mock fixes, never the pedestrian smoother. */
    public void addRawListener(GpsEventListener listener) {
        if (rawListeners.add(listener)) refreshLocationRequests();
    }

    public void removeRawListener(GpsEventListener listener) {
        rawListeners.remove(listener);
        refreshLocationRequests();
    }

    public void addRecordingListener(RecordingListener listener) {
        if (recorders.containsKey(listener)) return;
        // Buffered fixes from before Start must not become the new track's first vertices.
        recorders.put(listener, SystemClock.elapsedRealtimeNanos());
        refreshLocationRequests();
    }

    public void removeRecordingListener(RecordingListener listener) {
        recorders.remove(listener);
        refreshLocationRequests();
    }

    public void flushRecordingLocations() {
        emitRecording(filter.flushRemaining());
        for (RecordingListener listener : new ArrayList<>(recorders.keySet()))
            listener.onRecordingFlushComplete();
    }

    public Location getLastRecordingLocation() {
        Location location = filter.getLastAcceptedLocation();
        if (!isFresh(location)) return null;
        if (isExternalInput()) return location;
        return PermissionUtil.hasLocationPermissions(context)
                && isProviderEnabled(LocationManager.GPS_PROVIDER) ? location : null;
    }

    public boolean isExternalInput() {
        return GnssInputPrefs.isExternal(prefs);
    }

    public GnssFix getLastExternalFix() {
        return lastExternalFix == null ? null : lastExternalFix.copy();
    }

    public String getExternalGnssStatus() {
        return externalSession.status();
    }

    /** Only a current estimate is returned. Cached coordinates cannot live across expiry. */
    public Location getLastKnownLocation() {
        Location currentGps = isFresh(gps) && (isExternalInput()
                || (PermissionUtil.hasLocationPermissions(context)
                && isProviderEnabled(LocationManager.GPS_PROVIDER))) ? gps : null;
        if (currentGps == null && !PermissionUtil.hasAnyLocationPermission(context)
                && !isExternalInput()) return null;
        Location currentNetwork = PermissionUtil.hasAnyLocationPermission(context)
                && isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                && isFresh(network) ? network : null;
        if (currentGps == null) return copy(currentNetwork);
        if (currentNetwork == null) return copy(currentGps);
        return copy(LocationFixPolicy.preferGps(currentGps.getElapsedRealtimeNanos(), currentGps.getAccuracy(),
                currentNetwork.getElapsedRealtimeNanos(), currentNetwork.getAccuracy(),
                SystemClock.elapsedRealtimeNanos()) ? currentGps : currentNetwork);
    }

    public Location getLastKnownBestLocation() { return getLastKnownLocation(); }

    public String getRecordingDiagnostics() {
        return "input=" + filter.getInputFixCount() + " passed=" + filter.getPassedInputFixCount()
                + " dropped=" + filter.getDroppedInputFixCount()
                + " chordDropped=" + filter.getChordDroppedFixCount()
                + " " + filter.getMotionDiagnostics()
                + " gpsRequests=" + gpsRequests + " gpsStops=" + gpsStops
                + " wakeLock=" + (recordingWakeLock != null && recordingWakeLock.isHeld());
    }

    public void updateActiveListeners() { refreshLocationRequests(); }

    public void acquireHighFrequencyUpdates(Object owner) {
        if (owner != null && highFrequencyClients.add(owner)) refreshLocationRequests();
    }

    public void releaseHighFrequencyUpdates(Object owner) {
        if (highFrequencyClients.remove(owner)) refreshLocationRequests();
    }

    private boolean hasConsumers() {
        return !listeners.isEmpty() || !rawListeners.isEmpty() || !recorders.isEmpty();
    }

    private boolean isProviderEnabled(String provider) {
        try { return mLocationManager != null && mLocationManager.isProviderEnabled(provider); }
        catch (RuntimeException exception) { return false; }
    }

    private boolean keepExternalSession() {
        boolean allowed = keepAliveHost == null || keepAliveHost.canKeepExternalGnssAlive();
        return allowed && isExternalInput() && GnssTransportFactory.hasEndpoint(prefs);
    }

    private void notifyKeepAlive(boolean wanted) {
        if (keepAliveHost == null || postedKeepAlive == wanted) return;
        postedKeepAlive = wanted;
        keepAliveHost.setExternalGnssKeepAlive(wanted);
    }

    private void refreshLocationRequests() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::refreshLocationRequests);
            return;
        }
        handler.removeCallbacks(expiry);
        boolean consumers = hasConsumers();
        boolean external = isExternalInput();
        boolean keepExternal = keepExternalSession();
        boolean coarse = consumers && mLocationManager != null && PermissionUtil.hasAnyLocationPermission(context);
        boolean fine = coarse && PermissionUtil.hasLocationPermissions(context);
        boolean hadGps = subscriptions.hasGps();
        boolean hadNetwork = subscriptions.hasNetwork();
        if (!hadGps && (fine || external)) subscriptionStartedNanos = SystemClock.elapsedRealtimeNanos();
        long interval = !highFrequencyClients.isEmpty() ? 250L : 1_000L;
        boolean recording = !recorders.isEmpty();
        subscriptions.update(external ? 0 : (fine ? interval : 0),
                coarse && !listeners.isEmpty(),
                keepExternal ? recording : fine && recording);
        externalSession.setWanted(keepExternal);
        notifyKeepAlive(keepExternal);
        updateSharedWakeLock();
        if (!consumers && !keepExternal) {
            filter.reset();
            gps = network = rawGps = lastPublished = lastMock = lastReceiverMock = null;
            lastExternalFix = null;
            recordingAvailable = false;
            return;
        }
        if (!consumers) {
            publishCurrent();
            handler.post(expiry);
            return;
        }
        // Seed only a newly started source. A map reopen must not replace the live GNSS state.
        if (!external && !hadGps && subscriptions.hasGps()) seedFreshCache(LocationManager.GPS_PROVIDER);
        if (!hadNetwork && subscriptions.hasNetwork() && (gps == null || !isFresh(gps))) {
            seedFreshCache(LocationManager.NETWORK_PROVIDER);
        }
        publishCurrent();
        handler.post(expiry);
    }

    private boolean requestProvider(String provider, long interval, LocationListener listener) {
        try {
            if (mLocationManager == null || !mLocationManager.getAllProviders().contains(provider)) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                LocationRequest request = new LocationRequest.Builder(interval)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setMinUpdateIntervalMillis(interval).setMinUpdateDistanceMeters(0)
                        .setMaxUpdateDelayMillis(0).build();
                mLocationManager.requestLocationUpdates(provider, request, handler::post, listener);
            } else {
                mLocationManager.requestLocationUpdates(provider, interval, 0f, listener, Looper.getMainLooper());
            }
            return true;
        } catch (RuntimeException exception) {
            Log.w(Constants.TAG, "Location subscription unavailable: " + provider, exception);
            return false;
        }
    }

    private void removeProvider(LocationListener listener) {
        try { if (mLocationManager != null) mLocationManager.removeUpdates(listener); }
        catch (RuntimeException exception) { Log.w(Constants.TAG, "Location subscription cleanup failed", exception); }
    }

    private void setRecordingResources(boolean active) {
        if (active) {
            diagnosticScreenOn = null;
            lastDiagnosticAt = 0;
            diagnosticFixes = diagnosticMaxGapMs = 0;
            diagnosticMaxAccuracy = 0;
        }
        updateSharedWakeLock();
    }

    private void updateSharedWakeLock() {
        boolean needed = keepExternalSession() || !recorders.isEmpty();
        if (needed) {
            if (recordingWakeLock != null && recordingWakeLock.isHeld()) return;
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power == null) return;
            try {
                recordingWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        context.getPackageName() + ":GnssRecording");
                recordingWakeLock.setReferenceCounted(false);
                recordingWakeLock.acquire();
            } catch (RuntimeException exception) {
                HyperLog.e(Constants.TAG, "GPS recording wake lock acquisition failed", exception);
            }
        } else {
            try {
                if (recordingWakeLock != null && recordingWakeLock.isHeld()) recordingWakeLock.release();
            } catch (RuntimeException exception) {
                HyperLog.w(Constants.TAG, "GPS recording wake lock release failed", exception);
            } finally { recordingWakeLock = null; }
        }
    }

    private void logRecordingHealth() {
        if (recorders.isEmpty()) return;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean screenOn = power != null && power.isInteractive();
        long now = SystemClock.elapsedRealtime();
        if (diagnosticScreenOn != null && diagnosticScreenOn == screenOn && now - lastDiagnosticAt < 30_000) return;
        long age = rawGps == null ? -1 : now - rawGps.getElapsedRealtimeNanos() / 1_000_000;
        HyperLog.i(Constants.TAG, "GPS health screenOn=" + screenOn + " previousScreenOn=" + diagnosticScreenOn
                + " rawFixes=" + diagnosticFixes + " maxGapMs=" + diagnosticMaxGapMs
                + " maxRawAccuracyM=" + diagnosticMaxAccuracy + " lastRawAgeMs=" + age
                + " lastRawAccuracyM=" + (rawGps == null ? -1 : rawGps.getAccuracy())
                + " motion=" + motionMonitor.stateAt(SystemClock.elapsedRealtimeNanos())
                + " " + getRecordingDiagnostics());
        diagnosticScreenOn = screenOn;
        lastDiagnosticAt = now;
        diagnosticFixes = diagnosticMaxGapMs = 0;
        diagnosticMaxAccuracy = 0;
    }

    private void seedFreshCache(String provider) {
        try {
            if (!isProviderEnabled(provider)) return;
            if (LocationManager.GPS_PROVIDER.equals(provider)
                    && !PermissionUtil.hasLocationPermissions(context)) return;
            Location location = mLocationManager.getLastKnownLocation(provider);
            if (isFresh(location)) processLocation(location, false);
        } catch (RuntimeException exception) {
            Log.w(Constants.TAG, "Location cache unavailable: " + provider, exception);
        }
    }

    private void processLocation(Location location, boolean historical) {
        if (location == null) {
            DiagnosticLog.v(LocationDiagnosticFormat.incoming(
                    "null", -1f, -1L, false, false, 0, 0, 0, "drop:null"));
            return;
        }
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        long ageMs = LocationFixPolicy.ageMs(location.getElapsedRealtimeNanos(), nowNanos);
        boolean mock = LocationTrackFilter.isMockLocation(location);
        boolean nmea = LocationTrackFilter.isNativeNmea(location);
        int sats = location.getExtras() != null ? location.getExtras().getInt("satellites") : 0;
        if (!location.hasAccuracy()
                || !LocationFixPolicy.validPosition(location.getLatitude(), location.getLongitude(),
                location.getAccuracy())) {
            DiagnosticLog.v(LocationDiagnosticFormat.incoming(
                    location.getProvider(), location.getAccuracy(), ageMs, mock, nmea, sats,
                    location.getLatitude(), location.getLongitude(), "drop:invalid"));
            return;
        }
        if (!historical && !isFresh(location)) {
            DiagnosticLog.v(LocationDiagnosticFormat.incoming(
                    location.getProvider(), location.getAccuracy(), ageMs, mock, nmea, sats,
                    location.getLatitude(), location.getLongitude(), "drop:stale"));
            return;
        }
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            boolean extras = LocationTrackFilter.hasReceiverExtras(location);
            if (ExternalGnssFixPolicy.dropPlaceholderMock(mock, extras, isFresh(lastReceiverMock))) {
                DiagnosticLog.v(LocationDiagnosticFormat.incoming(
                        location.getProvider(), location.getAccuracy(), ageMs, mock, nmea, sats,
                        location.getLatitude(), location.getLongitude(), "drop:placeholder"));
                return;
            }
            if (ExternalGnssFixPolicy.dropChipWhileMock(mock, isFresh(lastMock))) {
                DiagnosticLog.v(LocationDiagnosticFormat.incoming(
                        location.getProvider(), location.getAccuracy(), ageMs, mock, nmea, sats,
                        location.getLatitude(), location.getLongitude(), "drop:chipWhileMock"));
                return;
            }
            if (rawGps != null && location.getElapsedRealtimeNanos() <= rawGps.getElapsedRealtimeNanos()) {
                DiagnosticLog.v(LocationDiagnosticFormat.incoming(
                        location.getProvider(), location.getAccuracy(), ageMs, mock, nmea, sats,
                        location.getLatitude(), location.getLongitude(), "drop:dup"));
                return;
            }
            if (!recorders.isEmpty()) {
                diagnosticFixes++;
                if (rawGps != null) diagnosticMaxGapMs = Math.max(diagnosticMaxGapMs,
                        (location.getElapsedRealtimeNanos() - rawGps.getElapsedRealtimeNanos()) / 1_000_000);
                diagnosticMaxAccuracy = Math.max(diagnosticMaxAccuracy, location.getAccuracy());
            }
            rawGps = new Location(location);
            if (mock || LocationTrackFilter.isNativeNmea(location)) {
                lastMock = rawGps;
                if (extras || LocationTrackFilter.isNativeNmea(location)) lastReceiverMock = rawGps;
            }
            if (isFresh(location)) {
                for (GpsEventListener listener : new ArrayList<>(rawListeners))
                    listener.onLocationChanged(new Location(location));
            }
            List<Location> points = filter.onLocation(location, historical, motionMonitor.stateAt(location.getElapsedRealtimeNanos()));
            Location accepted = filter.getLastAcceptedLocation();
            String action = "accept";
            if (accepted != null && accepted.getElapsedRealtimeNanos() == location.getElapsedRealtimeNanos()) {
                gps = accepted;
            } else if (location.getAccuracy() > LocationTrackFilter.DEFAULT_MAX_ACCURACY_M
                    || mock || LocationTrackFilter.isNativeNmea(location)) {
                gps = new Location(location);
                action = "accept:display";
            } else {
                action = "hold:filter";
            }
            DiagnosticLog.v(LocationDiagnosticFormat.incoming(
                    location.getProvider(), location.getAccuracy(), ageMs, mock, nmea, sats,
                    location.getLatitude(), location.getLongitude(), action));
            emitRecording(points);
        } else if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider()) && isFresh(location)) {
            DiagnosticLog.v(LocationDiagnosticFormat.incoming(
                    location.getProvider(), location.getAccuracy(), ageMs, false, false, 0,
                    location.getLatitude(), location.getLongitude(), "accept:network"));
            if (network == null || location.getElapsedRealtimeNanos() > network.getElapsedRealtimeNanos())
                network = new Location(location);
        }
    }

    private void emitRecording(List<Location> locations) {
        for (Location location : locations) {
            for (Map.Entry<RecordingListener, Long> entry : new ArrayList<>(recorders.entrySet())) {
                if (location.getElapsedRealtimeNanos() >= entry.getValue())
                    entry.getKey().onRecordingLocation(new Location(location));
            }
        }
    }

    private void publishCurrent() {
        Location current = getLastKnownLocation();
        if (current == null) {
            if (lastPublished != null) {
                DiagnosticLog.v(unavailableDiagnostic());
                lastPublished = null;
                for (GpsEventListener listener : new ArrayList<>(listeners)) listener.onLocationUnavailable();
            }
        } else if (lastPublished == null
                || current.getElapsedRealtimeNanos() != lastPublished.getElapsedRealtimeNanos()
                || !current.getProvider().equals(lastPublished.getProvider())) {
            lastPublished = new Location(current);
            for (GpsEventListener listener : new ArrayList<>(listeners))
                listener.onLocationChanged(new Location(current));
        }
        boolean available = getLastRecordingLocation() != null;
        if (!available && recordingAvailable) {
            // Finish the pre-gap buffer before notifying consumers of the discontinuity.
            flushRecordingLocations();
            for (RecordingListener listener : new ArrayList<>(recorders.keySet()))
                listener.onRecordingUnavailable();
        }
        recordingAvailable = available;
    }

    private String unavailableDiagnostic() {
        long now = SystemClock.elapsedRealtimeNanos();
        long gpsAge = gps == null ? -1L : LocationFixPolicy.ageMs(gps.getElapsedRealtimeNanos(), now);
        long netAge = network == null ? -1L : LocationFixPolicy.ageMs(network.getElapsedRealtimeNanos(), now);
        String reason;
        if (gps == null) reason = "gpsNull";
        else if (!isFresh(gps)) reason = "stale";
        else if (!isExternalInput() && !isProviderEnabled(LocationManager.GPS_PROVIDER)) reason = "providerDisabled";
        else if (!isExternalInput() && !PermissionUtil.hasLocationPermissions(context)) reason = "noPerm";
        else reason = "noNetworkFallback";
        return LocationDiagnosticFormat.unavailable(reason, gpsAge,
                isProviderEnabled(LocationManager.GPS_PROVIDER), netAge, isExternalInput());
    }

    private void publishStatus(int event) {
        for (GpsEventListener listener : new ArrayList<>(listeners)) listener.onGpsStatusChanged(event);
        for (GpsEventListener listener : new ArrayList<>(rawListeners)) listener.onGpsStatusChanged(event);
    }

    public static boolean isFresh(Location location) {
        return location != null && LocationFixPolicy.isFresh(location.getElapsedRealtimeNanos(),
                SystemClock.elapsedRealtimeNanos());
    }

    private void publishExternalFix(GnssFix fix) {
        for (GpsEventListener listener : new ArrayList<>(listeners)) listener.onExternalGnssFix(fix);
        for (GpsEventListener listener : new ArrayList<>(rawListeners)) listener.onExternalGnssFix(fix);
    }

    private void publishExternalStatus(String status) {
        for (GpsEventListener listener : new ArrayList<>(listeners)) listener.onExternalGnssStatus(status);
        for (GpsEventListener listener : new ArrayList<>(rawListeners)) listener.onExternalGnssStatus(status);
    }

    private static Location copy(Location location) { return location == null ? null : new Location(location); }
}
