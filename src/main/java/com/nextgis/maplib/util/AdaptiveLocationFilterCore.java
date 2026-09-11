package com.nextgis.maplib.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static com.nextgis.maplib.util.DeviceMotionEvidence.State.*;

/**
 * Small constant-velocity Kalman filter in local metres. Innovation gating protects the state
 * from isolated fixes; a bounded coherent sequence can reacquire real motion after rejection.
 * A stationary anchor is maintained only while fresh measurements continue to support it.
 * No road matching or dead-reckoned points are produced.
 */
final class AdaptiveLocationFilterCore {
    static final class Sample {
        final double x, y, accuracy, speed, speedAccuracy, bearing;
        final long timeMs;
        final DeviceMotionEvidence.State motion;

        Sample(double x, double y, long timeMs, double accuracy,
               double speed, double speedAccuracy, double bearing) {
            this(x, y, timeMs, accuracy, speed, speedAccuracy, bearing, UNKNOWN);
        }

        Sample(double x, double y, long timeMs, double accuracy, double speed,
               double speedAccuracy, double bearing, DeviceMotionEvidence.State motion) {
            this.x = x;
            this.y = y;
            this.timeMs = timeMs;
            this.motion = motion;
            this.accuracy = accuracy;
            this.speed = speed;
            this.speedAccuracy = speedAccuracy;
            this.bearing = bearing;
        }
    }

    static final class Estimate {
        final double x, y, accuracy;
        final boolean stationary;
        final long stopId;

        Estimate(double x, double y, double accuracy, boolean stationary, long stopId) {
            this.x = x;
            this.y = y;
            this.accuracy = accuracy;
            this.stationary = stationary;
            this.stopId = stopId;
        }
    }

    private final ArrayDeque<Sample> window = new ArrayDeque<>();
    private final ArrayDeque<Sample> candidates = new ArrayDeque<>();
    private Sample last;
    private double x, y, vx, vy, p00, p01, p11;
    private boolean stationary;
    private long rejected, stopId, motionSince;
    private double motionDirectionX, motionDirectionY;
    private double anchorAccuracy;
    private boolean anchorSettled;

    void reset() {
        last = null;
        window.clear();
        candidates.clear();
        stationary = false;
        rejected = stopId = motionSince = 0;
        anchorSettled = false;
    }

    long getRejectedCount() { return rejected; }

    Estimate onSample(Sample s) {
        if (!Double.isFinite(s.x) || !Double.isFinite(s.y)
                || !Double.isFinite(s.accuracy) || s.accuracy <= 0 || s.timeMs <= 0) {
            rejected++;
            return null;
        }
        if (last == null) return initialize(s, null);
        double dt = (s.timeMs - last.timeMs) / 1000d;
        if (dt <= 0) {
            rejected++;
            return null;
        }
        if (dt > 30) return reacquire(s, false);

        double distance = Math.hypot(s.x - last.x, s.y - last.y);
        if (distance > 55 * dt + 2.5 * (last.accuracy + s.accuracy)) {
            rejected++;
            candidates.clear();
            return null;
        }

        remember(s);
        boolean motionSupported = supportsMotion(s);
        if (stationary && !motionSupported) {
            // GNSS positions/speeds can drift coherently indoors. A fresh still accelerometer
            // requires sustained vehicle-scale motion to overrule it, not one nonzero speed.
            last = s;
            candidates.clear();
            refineAnchor(s);
            return estimate(s);
        }
        if (stationary) {
            // The stop's zero-velocity covariance is no longer a prediction of this motion.
            // Seed from the confirmed sequence rather than overshooting while catching up.
            return initialize(s, recent(s.timeMs, 6_000).get(0));
        } else if (!motionSupported && isStationaryWindow(s)) {
            enterStop(s);
            last = s;
            return estimate(s);
        }

        double speed = Double.isFinite(s.speed) ? s.speed : Math.hypot(vx, vy);
        // Vehicle manoeuvres require substantially more process freedom than walking.
        double q = speed > 6 ? 64 : 4;
        boolean courseChange = false;
        if (speed > 6 && Double.isFinite(s.bearing) && Math.hypot(vx, vy) > 6) {
            double previousBearing = Math.toDegrees(Math.atan2(vx, vy));
            double turn = Math.abs(Math.IEEEremainder(s.bearing - previousBearing, 360));
            // A GNSS course change opens the prediction gate for a real road corner.
            if (turn > 25 && (!Double.isFinite(s.speedAccuracy) || s.speedAccuracy < 1)) {
                q = 256;
                courseChange = true;
            }
        }
        double dt2 = dt * dt;
        double a = p00 + 2 * dt * p01 + dt2 * p11 + q * dt2 * dt2 / 4;
        double b = p01 + dt * p11 + q * dt2 * dt / 2;
        double c = p11 + q * dt2;
        double predictedX = x + vx * dt;
        double predictedY = y + vy * dt;
        double dx = s.x - predictedX;
        double dy = s.y - predictedY;
        double r = variance(s.accuracy);
        double innovation = (dx * dx + dy * dy) / (a + r);
        if (innovation > 9.21) return reacquire(s, false);

        candidates.clear();
        // Moderately unusual fixes receive less weight instead of moving the whole state.
        if (innovation > 4) r *= innovation / 4;
        double k0 = a / (a + r);
        double k1 = b / (a + r);
        x = predictedX + k0 * dx;
        y = predictedY + k0 * dy;
        vx += k1 * dx;
        vy += k1 * dy;
        p00 = Math.max(1, (1 - k0) * a);
        p01 = (1 - k0) * b;
        p11 = Math.max(0.01, c - k1 * b);
        // Old straight-line position/velocity correlation is invalid after a course change.
        if (courseChange) p01 = 0;
        updateVelocity(s);
        last = s;
        return estimate(s);
    }

    private Estimate initialize(Sample s, Sample previous) {
        x = s.x;
        y = s.y;
        vx = vy = 0;
        if (previous != null && s.timeMs > previous.timeMs) {
            double dt = (s.timeMs - previous.timeMs) / 1000d;
            vx = (s.x - previous.x) / dt;
            vy = (s.y - previous.y) / dt;
        }
        if (Double.isFinite(s.speed) && Double.isFinite(s.bearing)) {
            vx = s.speed * Math.sin(Math.toRadians(s.bearing));
            vy = s.speed * Math.cos(Math.toRadians(s.bearing));
        }
        p00 = variance(s.accuracy);
        p01 = 0;
        p11 = Double.isFinite(s.speed) ? Math.max(0.25, s.speed * s.speed / 4) : 400;
        last = s;
        // Start conservatively; a few coherent fixes release walking/vehicle motion.
        // This avoids writing several noisy indoor points before the first stop decision.
        stationary = previous == null && (s.motion == STILL || s.accuracy > 12
                || Double.isFinite(s.speed) && speedLowerBound(s) <= .3)
                && !(speedLowerBound(s) > 3 && Double.isFinite(s.bearing));
        stopId = s.timeMs;
        motionSince = 0;
        anchorAccuracy = s.accuracy;
        anchorSettled = false;
        window.clear();
        remember(s);
        candidates.clear();
        return estimate(s);
    }

    private Estimate reacquire(Sample s, boolean requireMotion) {
        rejected++;
        if (!candidates.isEmpty()) {
            Sample previous = candidates.peekLast();
            double dt = (s.timeMs - previous.timeMs) / 1000d;
            if (dt <= 0 || dt > 3
                    || Math.hypot(s.x - previous.x, s.y - previous.y)
                    > 55 * dt + previous.accuracy + s.accuracy) candidates.clear();
        }
        candidates.addLast(s);
        while (candidates.size() > 3) candidates.removeFirst();
        if (candidates.size() < 3) return null;
        Sample first = candidates.peekFirst();
        double travelled = Math.hypot(s.x - first.x, s.y - first.y);
        double path = 0;
        Sample prev = null;
        for (Sample sample : candidates) {
            if (prev != null) path += Math.hypot(sample.x - prev.x, sample.y - prev.y);
            prev = sample;
        }
        boolean coherent = travelled >= Math.max(1.5, s.accuracy * 0.3)
                && travelled >= path * 0.8;
        boolean cluster = path <= Math.max(3, s.accuracy);
        if (coherent || (!requireMotion && cluster)) return initialize(s, first);
        return null;
    }

    private boolean supportsMotion(Sample current) {
        List<Sample> recent = recent(current.timeMs, 6_000);
        if (recent.size() < 4) return false;
        Sample first = recent.get(0);
        double span = (current.timeMs - first.timeMs) / 1000d;
        if (span < 3) return false;
        double[] trend = trend(recent);
        double dx = trend[0] * span, dy = trend[1] * span;
        double displacement = Math.hypot(dx, dy), path = 0, biggest = 0;
        int progress = 0, movingSpeeds = 0;
        double svx = 0, svy = 0, speedSum = 0;
        for (int i = 0; i < recent.size(); i++) {
            Sample sample = recent.get(i);
            if (i > 0) {
                Sample previous = recent.get(i - 1);
                double step = Math.hypot(sample.x - previous.x, sample.y - previous.y);
                path += step;
                biggest = Math.max(biggest, step);
                if (step > .1) progress++;
            }
            if (speedLowerBound(sample) > .3 && Double.isFinite(sample.bearing)) {
                movingSpeeds++;
                svx += sample.speed * Math.sin(Math.toRadians(sample.bearing));
                svy += sample.speed * Math.cos(Math.toRadians(sample.bearing));
                speedSum += sample.speed;
            }
        }
        boolean coherent = progress >= 3 && biggest < path * .55
                && (displacement >= path * .75 || trend[2] >= .7);
        double trendSpeed = Math.hypot(trend[0], trend[1]);
        if (coherent && trendSpeed > .15) {
            if (motionSince == 0 || (trend[0] * motionDirectionX + trend[1] * motionDirectionY)
                    < trendSpeed * .85) {
                motionSince = first.timeMs;
                motionDirectionX = trend[0] / trendSpeed;
                motionDirectionY = trend[1] / trendSpeed;
            }
        } else motionSince = 0;
        double fromAnchor = Math.hypot(current.x - x, current.y - y);
        long sustainedMs = motionSince == 0 ? 0 : current.timeMs - motionSince;
        double velocity = Math.hypot(svx, svy) / Math.max(1, movingSpeeds);
        boolean doppler = movingSpeeds >= 3 && movingSpeeds >= recent.size() * .7
                && Math.hypot(svx, svy) >= speedSum * .85 && displacement >= 1.5
                && (dx * svx + dy * svy) >= displacement * Math.hypot(svx, svy) * .75
                && Math.abs(displacement / span - velocity) <= Math.max(.7, velocity * .5);
        if (!stationary && current.motion != STILL && coherent && trendSpeed > .2) return true;
        if (current.motion == STILL) {
            boolean quietDoppler = doppler
                    && Math.abs(trendSpeed - velocity) <= Math.max(.25, velocity * .3);
            // Hysteresis: once real slow movement was established, do not reapply the
            // stronger departure threshold every second and repeatedly declare false stops.
            if (!stationary && quietDoppler && coherent) return true;
            // An accelerometer cannot tell rest from a smooth constant-speed vehicle.
            // Sustained GNSS velocity AND displacement can override quiet sensor evidence.
            if (stationary && quietDoppler && coherent && sustainedMs >= 20_000
                    && velocity >= .5 && fromAnchor > Math.max(15, current.accuracy * 3)) return true;
            return coherent && span >= 4 && displacement >= Math.max(8, current.accuracy * .5)
                    && (doppler && velocity >= 2.5 || displacement / span >= 3
                    && displacement >= current.accuracy * 2);
        }
        if (current.motion == MOVING && doppler && progress >= 3 && biggest < path * .55
                && trend[2] >= .25) return true;
        if (doppler && coherent && (current.accuracy <= 12 || span >= 5)
                && displacement >= Math.max(1.5, current.accuracy * .35)) return true;
        // Accumulate persistent departure beyond the uncertainty radius. A fixed six-second
        // threshold alone would permanently pin slow walkers with coarse/missing speed data.
        if (stationary && coherent && sustainedMs >= (current.motion == MOVING ? 4_000 : 8_000)
                && fromAnchor > (current.motion == MOVING ? Math.max(3, current.accuracy * .4)
                : Math.max(6, current.accuracy * 1.5))) return true;
        double threshold = current.motion == MOVING ? Math.max(1.5, current.accuracy * .3)
                : current.accuracy <= 12 ? Math.max(2, current.accuracy * .5)
                : Math.max(8, current.accuracy * 1.2);
        return coherent && displacement > threshold && (current.accuracy <= 12 || span >= 5);
    }

    private boolean isStationaryWindow(Sample current) {
        List<Sample> recent = recent(current.timeMs, 5_000);
        if (recent.size() < 5 || current.timeMs - recent.get(0).timeMs < 4_000) return false;
        int reliableMotion = 0;
        for (Sample s : recent) if (speedLowerBound(s) > .3) reliableMotion++;
        // Preserve a moving vehicle through curves even when the phone is quiet in its mount.
        if (reliableMotion >= recent.size() * .6) {
            double[] speeds = new double[recent.size()];
            for (int i = 0; i < recent.size(); i++) speeds[i] = speedLowerBound(recent.get(i));
            if (current.motion != STILL || median(speeds) >= 2.5) return false;
        }
        double[] centre = centre(recent);
        int inside = 0;
        for (Sample s : recent) if (Math.hypot(s.x - centre[0], s.y - centre[1])
                <= Math.max(3, s.accuracy)) inside++;
        return inside >= recent.size() * .8;
    }

    private void enterStop(Sample s) {
        double[] centre = centre(recent(s.timeMs, 4_000));
        x = centre[0]; y = centre[1];
        vx = vy = p01 = 0;
        p11 = .04;
        stationary = true;
        stopId = s.timeMs;
        motionSince = 0;
        anchorAccuracy = s.accuracy;
        anchorSettled = false;
    }

    private void refineAnchor(Sample current) {
        List<Sample> recent = recent(current.timeMs, 8_000);
        if (recent.size() < 6 || current.timeMs - recent.get(0).timeMs < 5_000) return;
        double[] centre = centre(recent);
        double[] accuracies = new double[recent.size()], residuals = new double[recent.size()];
        for (int i = 0; i < recent.size(); i++) {
            Sample s = recent.get(i);
            accuracies[i] = s.accuracy;
            residuals[i] = Math.hypot(s.x - centre[0], s.y - centre[1]);
        }
        double quality = median(accuracies);
        boolean compact = median(residuals) <= Math.max(2, quality * .5);
        // Settle the first uncertain fix using a robust cluster, then move the anchor only
        // for a sustained material improvement in reception. Repeated correlated errors
        // do not shrink uncertainty or slowly drag the whole stop across the map.
        boolean initial = !anchorSettled && current.timeMs - stopId >= 8_000;
        boolean improved = quality <= anchorAccuracy * .65
                && current.accuracy <= anchorAccuracy * .65;
        if (compact && (initial || improved)) {
            x = centre[0]; y = centre[1];
            anchorAccuracy = quality;
            anchorSettled = true;
            p00 = variance(quality);
        }
    }

    private List<Sample> recent(long now, long duration) {
        List<Sample> result = new ArrayList<>();
        for (Sample s : window) if (now - s.timeMs <= duration) result.add(s);
        return result;
    }

    private static double[] trend(List<Sample> samples) {
        double mt = 0, mx = 0, my = 0;
        long start = samples.get(0).timeMs;
        for (Sample s : samples) { mt += (s.timeMs - start) / 1000d; mx += s.x; my += s.y; }
        mt /= samples.size(); mx /= samples.size(); my /= samples.size();
        double tt = 0, tx = 0, ty = 0, spread = 0;
        for (Sample s : samples) {
            double t = (s.timeMs - start) / 1000d - mt, dx = s.x - mx, dy = s.y - my;
            tt += t * t; tx += t * dx; ty += t * dy; spread += dx * dx + dy * dy;
        }
        if (tt == 0 || spread == 0) return new double[]{0, 0, 0};
        return new double[]{tx / tt, ty / tt, Math.min(1, (tx * tx + ty * ty) / (tt * spread))};
    }

    private static double[] centre(List<Sample> samples) {
        double[] xs = new double[samples.size()], ys = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) { xs[i] = samples.get(i).x; ys[i] = samples.get(i).y; }
        return new double[]{median(xs), median(ys)};
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        int n = values.length;
        return n % 2 == 0 ? (values[n / 2 - 1] + values[n / 2]) / 2 : values[n / 2];
    }

    private static double speedLowerBound(Sample s) {
        if (!Double.isFinite(s.speed)) return 0;
        // Missing speed uncertainty is weaker evidence, particularly for noisy indoor fixes.
        double error = Double.isFinite(s.speedAccuracy) && s.speedAccuracy >= 0 ? s.speedAccuracy : .8;
        return Math.max(0, s.speed - 2 * error);
    }

    private void remember(Sample sample) {
        window.addLast(sample);
        while (window.size() > 64 || !window.isEmpty()
                && sample.timeMs - window.peekFirst().timeMs > 12_000) window.removeFirst();
    }

    private void updateVelocity(Sample s) {
        if (!Double.isFinite(s.speed) || !Double.isFinite(s.bearing)) return;
        double sigma = Double.isFinite(s.speedAccuracy) ? Math.max(0.2, s.speedAccuracy) : 1.5;
        double k0 = p01 / (p11 + sigma * sigma);
        double k1 = p11 / (p11 + sigma * sigma);
        double dx = s.speed * Math.sin(Math.toRadians(s.bearing)) - vx;
        double dy = s.speed * Math.cos(Math.toRadians(s.bearing)) - vy;
        x += k0 * dx;
        y += k0 * dy;
        vx += k1 * dx;
        vy += k1 * dy;
        p00 = Math.max(1, p00 - k0 * p01);
        p01 *= 1 - k1;
        p11 = Math.max(0.01, p11 * (1 - k1));
    }

    private Estimate estimate(Sample s) {
        // Covers the original provider circle even when smoothing shifts its centre.
        return new Estimate(x, y, s.accuracy + Math.hypot(s.x - x, s.y - y), stationary, stationary ? stopId : 0);
    }

    private static double variance(double radius68) {
        double sigma = Math.max(2, radius68 / 1.51);
        return sigma * sigma;
    }
}
