package com.nextgis.maplib.util;

import java.io.IOException;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

/** Typed initial-fill HTTP producer only; no HTTP request, layer, map or database. */
public class NgwHttpExceptionTest {
    private NgwHttpException failure(int code, String body) {
        return new NgwHttpException(code, body, "technical response",
                "NGW feature pull HTTP " + code + " technical response https://example.test/resource/7");
    }
    @Test public void serverStatusRangeAndThrottleAreKnownServerFailures() {
        for (int code : new int[]{429, 500, 503, 599}) {
            NgwHttpException error = failure(code, "<html>technical response</html>");
            assertTrue(error.isTemporaryServerFailure());
            assertEquals(code, error.getResponseCode());
            assertTrue(error instanceof IOException);
        }
    }
    @Test public void authRightsMissingResourceAndPlainValidationAreNotMaintenance() {
        for (int code : new int[]{200, 400, 401, 403, 404, 408, 422, 499, 600}) {
            assertFalse("status " + code, failure(code, "plain validation").isTemporaryServerFailure());
        }
    }
    @Test public void externalDatabaseTextNeedsTheExisting422TransientGate() {
        assertTrue(failure(422, "ExternalDatabaseError").isTemporaryServerFailure());
        for (int code : new int[]{200, 401, 403, 404, 600}) {
            assertFalse(failure(code, "ExternalDatabaseError").isTemporaryServerFailure());
        }
    }
    @Test public void responseBodyIsNotRetainedAsAnExtraField() {
        NgwHttpException error = new NgwHttpException(503, "sensitive-body-sentinel",
                "sensitive-header-sentinel", "bounded existing diagnostic");
        assertEquals("bounded existing diagnostic", error.getMessage());
        assertNull(error.getCause());
        for (Field field : NgwHttpException.class.getDeclaredFields()) {
            assertTrue(field.getType() == int.class || field.getType() == boolean.class);
        }
    }
    @Test public void originalDiagnosticAndExistingIOExceptionRetryPolicyStayExact() {
        for (int code : new int[]{408, 429, 500, 502, 503, 504, 511}) {
            String diagnostic = "NGW feature pull HTTP " + code + " technical response https://example.test/resource/7";
            NgwHttpException typed = failure(code, "");
            assertEquals(diagnostic, typed.getMessage());
            assertEquals(NetworkUtil.isTransientNetworkFailure(new IOException(diagnostic)),
                    NetworkUtil.isTransientNetworkFailure(typed));
        }
    }
}
