package com.nextgis.maplib.util;

import android.app.Application;
import android.content.Context;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Initial resource-metadata HTTP provenance; no actual request, layer or database. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE, application=Application.class, qualifiers="ru")
public class NgwHttpResponseExceptionTest {
    private Context context;
    @Before public void before() { context=RuntimeEnvironment.getApplication(); }
    private NgwHttpResponseException failure(int code,String body) {
        return new NgwHttpResponseException(context,new HttpResponse(code,"technical response",body));
    }
    @Test public void metadataServerStatusesRetainPositiveServerProvenance() {
        for(int code:new int[]{429,500,503,599}) {
            NgwHttpResponseException error=failure(code,"<html>sensitive-body-sentinel</html>");
            assertTrue(error.isTemporaryServerFailure());assertEquals(code,error.getResponseCode());
            assertTrue(error instanceof NGException);
        }
    }
    @Test public void checkedExceptionAndLegacyLocalizedMessageStayExact() {
        for(int code:new int[]{401,403,404,408,422,500,503}) {
            NgwHttpResponseException error=failure(code,"technical response");
            assertEquals(NetworkUtil.getError(context,code),error.getMessage());
            assertNull(error.getCause());
        }
    }
    @Test public void authRightsMissingResourceAndOrdinaryValidationNeverBecomeMaintenance() {
        for(int code:new int[]{200,401,403,404,408,422,499,600}) {
            assertFalse(failure(code,"plain validation").isTemporaryServerFailure());
        }
    }
    @Test public void externalDatabaseWordsNeedTheKnown422TransientGate() {
        assertTrue(failure(422,"ExternalDatabaseError").isTemporaryServerFailure());
        for(int code:new int[]{200,401,403,404,600}) {
            assertFalse(failure(code,"ExternalDatabaseError").isTemporaryServerFailure());
        }
    }
    @Test public void responseAndContextAreNotRetainedInAdditionalFields() {
        NgwHttpResponseException error=new NgwHttpResponseException(context,
                new HttpResponse(503,"sensitive-header-sentinel","sensitive-body-sentinel"));
        assertEquals(context.getString(com.nextgis.maplib.R.string.error_500),error.getMessage());
        for(Field field:NgwHttpResponseException.class.getDeclaredFields()) {
            assertTrue(field.getType()==int.class||field.getType()==boolean.class);
        }
    }
}
