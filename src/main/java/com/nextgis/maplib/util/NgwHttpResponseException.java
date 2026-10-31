package com.nextgis.maplib.util;

import android.content.Context;

/** HTTP provenance for a checked NGW response failure; keeps the existing localized message. */
public final class NgwHttpResponseException extends NGException {
    private final int responseCode;
    private final boolean temporaryServerFailure;

    public NgwHttpResponseException(Context context, HttpResponse response) {
        super(NetworkUtil.getError(context, response.getResponseCode()));
        responseCode = response.getResponseCode();
        temporaryServerFailure = NetworkUtil.isTransientNgwHttpError(responseCode,
                response.getResponseBody(), response.getResponseMessage())
                && NetworkUtil.isTemporaryNgwServerFailure(responseCode,
                        response.getResponseBody(), response.getResponseMessage());
    }

    public int getResponseCode() { return responseCode; }
    public boolean isTemporaryServerFailure() { return temporaryServerFailure; }
}
