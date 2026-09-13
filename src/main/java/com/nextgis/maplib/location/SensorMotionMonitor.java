package com.nextgis.maplib.location;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.DeviceMotionEvidence;

/** Shares the GNSS session lifetime. Requires no activity-recognition permission. */
final class SensorMotionMonitor implements SensorEventListener {
    private final SensorManager manager;
    private final Handler handler;
    private final DeviceMotionEvidence evidence = new DeviceMotionEvidence();
    private boolean registered;

    SensorMotionMonitor(Context context, Handler handler) {
        manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.handler = handler;
    }

    void start() {
        if (registered || manager == null) return;
        Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (sensor == null) return;
        try {
            // 25 Hz, no sensor batching. During recording the source owns a partial wake lock.
            registered = manager.registerListener(this, sensor, 40_000, 0, handler);
        } catch (RuntimeException exception) {
            HyperLog.w(Constants.TAG, "GPS motion evidence unavailable", exception);
        }
    }

    void stop() {
        if (manager != null && registered) manager.unregisterListener(this);
        registered = false;
        evidence.reset();
    }

    DeviceMotionEvidence.State stateAt(long timeNanos) { return evidence.at(timeNanos); }

    @Override public void onSensorChanged(SensorEvent event) {
        evidence.add(event.timestamp, event.values[0], event.values[1], event.values[2]);
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) evidence.reset();
    }
}
