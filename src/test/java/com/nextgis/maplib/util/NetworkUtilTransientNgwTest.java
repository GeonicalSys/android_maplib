package com.nextgis.maplib.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NetworkUtilTransientNgwTest
{
    @Test
    public void externalDatabase503IsTemporaryServerFailure()
    {
        String body = "{\"exception\":\"nextgisweb.postgis.exception.ExternalDatabaseError\"}";

        assertTrue(NetworkUtil.isTransientNgwHttpError(503, body, "Service Unavailable"));
        assertTrue(NetworkUtil.isTemporaryNgwServerFailure(503, body, "Service Unavailable"));
    }

    @Test
    public void clientTimeoutIsTransientButNotServerFailure()
    {
        assertTrue(NetworkUtil.isTransientNgwHttpError(408, null, "Request Timeout"));
        assertFalse(NetworkUtil.isTemporaryNgwServerFailure(408, null, "Request Timeout"));
    }

    @Test
    public void uncommonServerErrorIsRetried()
    {
        assertTrue(NetworkUtil.isTransientNgwHttpError(
                507, null, "Insufficient Storage"));
        assertTrue(NetworkUtil.isTemporaryNgwServerFailure(
                507, null, "Insufficient Storage"));
    }

    @Test
    public void validationFailureIsNotRetried()
    {
        assertFalse(NetworkUtil.isTransientNgwHttpError(
                422,
                "{\"message\":\"invalid field value\"}",
                "Unprocessable Entity"));
    }
}
