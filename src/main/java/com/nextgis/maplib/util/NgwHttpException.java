package com.nextgis.maplib.util;

import java.io.IOException;

/** HTTP provenance retained for presentation; the original diagnostic remains in logs. */
public final class NgwHttpException extends IOException {
    private final int responseCode;
    private final boolean temporaryServerFailure;

    public NgwHttpException(int responseCode, String responseBody, String responseMessage,
                            String diagnostic) {
        super(diagnostic);
        this.responseCode = responseCode;
        temporaryServerFailure = NetworkUtil.isTransientNgwHttpError(
                responseCode, responseBody, responseMessage)
                && NetworkUtil.isTemporaryNgwServerFailure(
                        responseCode, responseBody, responseMessage);
    }

    public int getResponseCode() { return responseCode; }
    public boolean isTemporaryServerFailure() { return temporaryServerFailure; }
}
