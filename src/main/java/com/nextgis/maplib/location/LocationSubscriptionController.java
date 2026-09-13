package com.nextgis.maplib.location;

/** Reconciles independent subscriptions without interrupting an ongoing recording session. */
final class LocationSubscriptionController {
    interface Backend {
        boolean requestGps(long intervalMs);
        void stopGps();
        boolean requestNetwork();
        void stopNetwork();
        void setRecordingActive(boolean active);
    }

    private final Backend backend;
    private long gpsInterval;
    private boolean network, recording;

    LocationSubscriptionController(Backend backend) { this.backend = backend; }

    void update(long wantedGpsInterval, boolean wantedNetwork, boolean wantedRecording) {
        // Acquire recording resources before registering callbacks; release only after the
        // final recorder leaves. Display listeners and the sound preference do not own them.
        if (wantedRecording && !recording) {
            backend.setRecordingActive(true);
            recording = true;
        }
        if (wantedGpsInterval != gpsInterval) {
            if (wantedGpsInterval == 0) {
                backend.stopGps();
                gpsInterval = 0;
            } else if (backend.requestGps(wantedGpsInterval)) {
                // Android replaces the request for the same listener. Never remove it first.
                gpsInterval = wantedGpsInterval;
            }
        }
        if (wantedNetwork != network) {
            if (wantedNetwork) network = backend.requestNetwork();
            else {
                backend.stopNetwork();
                network = false;
            }
        }
        if (!wantedRecording && recording) {
            backend.setRecordingActive(false);
            recording = false;
        }
    }

    boolean hasGps() { return gpsInterval != 0; }
    boolean hasNetwork() { return network; }
}
