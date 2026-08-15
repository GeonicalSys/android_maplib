/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Copyright (c) 2026 GeonicalSystem
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.nextgis.maplib.util;

/** Android-independent sound-band selection for stakeout guidance. */
public final class StakeoutGuidancePolicy {
    public enum Band {
        SILENT(null),
        FAR(2500L),
        MEDIUM(1200L),
        NEAR(500L),
        REACHED(null);

        private final Long intervalMillis;

        Band(Long intervalMillis) {
            this.intervalMillis = intervalMillis;
        }

        public Long getIntervalMillis() {
            return intervalMillis;
        }
    }

    private final double far;
    private final double medium;
    private final double near;
    private final double reached;
    private Band currentBand = Band.SILENT;

    public StakeoutGuidancePolicy(double far, double medium, double near, double reached) {
        if (!Double.isFinite(far) || !Double.isFinite(medium)
                || !Double.isFinite(near) || !Double.isFinite(reached)
                || far <= medium || medium <= near || near <= reached || reached <= 0.0) {
            throw new IllegalArgumentException("Stakeout thresholds are invalid");
        }
        this.far = far;
        this.medium = medium;
        this.near = near;
        this.reached = reached;
    }

    public Band evaluate(double distanceMeters, boolean isPrecisionFix) {
        if (!isPrecisionFix || !Double.isFinite(distanceMeters)) {
            currentBand = Band.SILENT;
            return currentBand;
        }

        Band candidate;
        if (distanceMeters <= reached) {
            candidate = Band.REACHED;
        } else if (distanceMeters <= near) {
            candidate = Band.NEAR;
        } else if (distanceMeters <= medium) {
            candidate = Band.MEDIUM;
        } else if (distanceMeters <= far) {
            candidate = Band.FAR;
        } else {
            candidate = Band.SILENT;
        }

        // Hold a band for ten percent when moving outwards to prevent threshold chatter.
        // Reported accuracy remains visible to the operator, but cannot silence a receiver
        // application that publishes corrected mock coordinates with a coarse placeholder.
        if (candidate.ordinal() < currentBand.ordinal()) {
            Double currentThreshold = thresholdFor(currentBand);
            if (currentThreshold != null
                    && distanceMeters <= currentThreshold * 1.10) {
                candidate = currentBand;
            }
        }

        currentBand = candidate;
        return currentBand;
    }

    public void reset() {
        currentBand = Band.SILENT;
    }

    private Double thresholdFor(Band band) {
        switch (band) {
            case FAR:
                return far;
            case MEDIUM:
                return medium;
            case NEAR:
                return near;
            case REACHED:
                return reached;
            case SILENT:
            default:
                return null;
        }
    }
}
