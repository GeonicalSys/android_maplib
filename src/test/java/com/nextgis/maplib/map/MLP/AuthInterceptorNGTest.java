package com.nextgis.maplib.map.MLP;

import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class AuthInterceptorNGTest {
    private Request intercepted(AuthInterceptorNG interceptor, String url) throws Exception {
        Request request = new Request.Builder().url(url).build();
        Interceptor.Chain chain = (Interceptor.Chain) Proxy.newProxyInstance(
                Interceptor.Chain.class.getClassLoader(), new Class[]{Interceptor.Chain.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("request")) return request;
                    if (method.getName().equals("proceed")) return new Response.Builder()
                            .request((Request) args[0]).protocol(Protocol.HTTP_1_1).code(200).message("test").build();
                    throw new AssertionError("Unexpected network access: " + method.getName());
                });
        return interceptor.intercept(chain).request();
    }

    @Test public void matchesExactOriginEndpointAndResource() throws Exception {
        AuthInterceptorNG interceptor = new AuthInterceptorNG();
        interceptor.addAuth(new String[]{"https://example.invalid/api/component/render/tile",
                "resource=1", "test-header", "/project/layer"});
        assertEquals("test-header", intercepted(interceptor,
                "https://example.invalid/api/component/render/tile?resource=1&x=10").header("Authorization"));
        for (String url : new String[]{
                "https://example.invalid/api/component/render/tile?resource=10",
                "https://example.invalid/api/component/render/tile?resource=1&resource=10",
                "https://example.invalid.evil.invalid/api/component/render/tile?resource=1",
                "https://other.invalid/api/component/render/tile?resource=1&next=https://example.invalid",
                "http://example.invalid/api/component/render/tile?resource=1",
                "https://example.invalid:8443/api/component/render/tile?resource=1",
                "https://example.invalid/api/component/render/tile-extra?resource=1"})
            assertNull(url, intercepted(interceptor, url).header("Authorization"));
    }

    @Test public void replacingCredentialsAndRemovingOwnerAffectTheNextRequest() throws Exception {
        AuthInterceptorNG interceptor = new AuthInterceptorNG();
        String[] auth = {"https://example.invalid/tile", "resource=1", "old-test", "/one"};
        interceptor.addAuth(auth);
        auth[2] = "mutated-array";
        assertEquals("old-test", intercepted(interceptor, "https://example.invalid/tile?resource=1").header("Authorization"));
        interceptor.addAuth(new String[]{auth[0], auth[1], "new-test", "/one"});
        assertEquals("new-test", intercepted(interceptor, "https://example.invalid/tile?resource=1").header("Authorization"));
        interceptor.removeAuth(new String[]{"/one"});
        assertNull(intercepted(interceptor, "https://example.invalid/tile?resource=1").header("Authorization"));
    }

    @Test public void resourceChangeAndNoAuthMarkerRemoveStaleCredentials() throws Exception {
        AuthInterceptorNG interceptor = new AuthInterceptorNG();
        interceptor.addAuth(new String[]{"https://example.invalid/tile", "resource=1", "test", "/layer"});
        interceptor.addAuth(new String[]{"https://example.invalid/tile", "resource=2", "test", "/layer"});
        assertNull(intercepted(interceptor, "https://example.invalid/tile?resource=1").header("Authorization"));
        interceptor.addAuth(new String[]{"https://example.invalid/tile", "resource=2", "no", "/layer"});
        assertNull(intercepted(interceptor, "https://example.invalid/tile?resource=2").header("Authorization"));
    }

    @Test public void concurrentRequestsAndProjectRotationUseConsistentSnapshots() throws Exception {
        AuthInterceptorNG interceptor = new AuthInterceptorNG();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> mutations = pool.submit(() -> {
                for (int i=0; i<500; i++) {
                    interceptor.addAuth(new String[]{"https://example.invalid/tile", "resource=1", "test-"+i, "/layer"});
                    interceptor.clearAuth();
                }
            });
            Future<?> requests = pool.submit(() -> {
                try { for (int i=0; i<500; i++) intercepted(interceptor, "https://example.invalid/tile?resource=1"); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            mutations.get(10, TimeUnit.SECONDS); requests.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }

    @Test public void quadkeyRewritePreservesQueryAndInvalidCoordinatesDoNotCrash() throws Exception {
        AuthInterceptorNG interceptor = new AuthInterceptorNG();
        assertEquals("https://example.invalid/213.png?foo=quadtiles",
                intercepted(interceptor, "https://example.invalid/quadtiles/3/3/5.png?foo=quadtiles").url().toString());
        String invalid = "https://example.invalid/quadtiles/no/3/5.png";
        assertEquals(invalid, intercepted(interceptor, invalid).url().toString());
    }
}
