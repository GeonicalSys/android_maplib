package com.nextgis.maplib.map.MLP;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Immutable request snapshots; credentials are scoped to an exact NGW tile endpoint/resource. */
public class AuthInterceptorNG implements Interceptor {
    private volatile Map<String, Entry> accounts = Collections.emptyMap();
    private static final class Entry {
        final HttpUrl endpoint;
        final String resource, authorization, owner;
        Entry(HttpUrl endpoint, String resource, String authorization, String owner) {
            this.endpoint = endpoint; this.resource = resource;
            this.authorization = authorization; this.owner = owner;
        }
        boolean matches(HttpUrl request) {
            List<String> resources = request.queryParameterValues("resource");
            return endpoint.scheme().equals(request.scheme()) && endpoint.host().equals(request.host())
                    && endpoint.port() == request.port() && endpoint.encodedPath().equals(request.encodedPath())
                    && resources.size() == 1 && resource.equals(resources.get(0));
        }
    }

    public synchronized void addAuth(String[] auth) {
        if (auth == null || auth.length < 3) return;
        HttpUrl endpoint = auth[0] == null ? null : HttpUrl.parse(auth[0]);
        String owner = auth.length > 3 && auth[3] != null ? auth[3] : "";
        Map<String, Entry> next = new LinkedHashMap<>(accounts);
        if (!owner.isEmpty()) next.values().removeIf(entry -> owner.equals(entry.owner));
        if (endpoint == null || auth[1] == null || !auth[1].startsWith("resource=")) {
            accounts = Collections.unmodifiableMap(next);
            return;
        }
        String resource = auth[1].substring("resource=".length());
        String key = endpoint.scheme() + "://" + endpoint.host() + ":" + endpoint.port()
                + endpoint.encodedPath() + "?resource=" + resource;
        next.remove(key);
        // RemoteTMSLayer uses 'no' as an explicit no-auth marker, never as a header value.
        if (!resource.isEmpty() && auth[2] != null && !auth[2].isEmpty() && !"no".equals(auth[2]))
            next.put(key, new Entry(endpoint, resource, auth[2], owner));
        accounts = Collections.unmodifiableMap(next);
    }

    /** Remove entries for the layer storage paths passed by the caller. */
    public synchronized void removeAuth(String[] owners) {
        if (owners == null) return;
        Map<String, Entry> next = new LinkedHashMap<>(accounts);
        for (String owner : owners) next.values().removeIf(entry -> entry.owner.equals(owner));
        accounts = Collections.unmodifiableMap(next);
    }

    public synchronized void clearAuth() { accounts = Collections.emptyMap(); }

    @Override public Response intercept(Chain chain) throws IOException {
        Request request = rewriteQuadtiles(chain.request());
        for (Entry entry : accounts.values()) {
            if (entry.matches(request.url())) {
                request = request.newBuilder().header("Authorization", entry.authorization).build();
                break;
            }
        }
        return chain.proceed(request);
    }

    private Request rewriteQuadtiles(Request request) {
        // Keep the existing public TMS quadkey substitution, limiting it to path segments.
        List<String> path = request.url().pathSegments();
        int index = path.indexOf("quadtiles");
        if (index < 0 || index + 3 >= path.size()) return request;
        try {
            int z = Integer.parseInt(path.get(index+1));
            int x = Integer.parseInt(path.get(index+2));
            String tail = path.get(index+3);
            int dot = tail.indexOf('.');
            int y = Integer.parseInt(dot < 0 ? tail : tail.substring(0, dot));
            if (z < 0 || z > 30 || x < 0 || y < 0 || x >= (1L << z) || y >= (1L << z)) return request;
            StringBuilder quadKey = new StringBuilder();
            for (int level=z; level>0; level--) {
                int mask = 1 << (level-1);
                quadKey.append(((x & mask) == 0 ? 0 : 1) + ((y & mask) == 0 ? 0 : 2));
            }
            java.util.ArrayList<String> updated = new java.util.ArrayList<>(path);
            updated.subList(index, index+4).clear();
            updated.add(index, quadKey + (dot < 0 ? "" : tail.substring(dot)));
            HttpUrl.Builder url = request.url().newBuilder().encodedPath("/");
            for (String segment : updated) url.addPathSegment(segment);
            return request.newBuilder().url(url.build()).build();
        } catch (NumberFormatException error) { return request; }
    }
}
