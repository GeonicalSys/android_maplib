package com.nextgis.maplib.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Parsed URL of a single NextGIS Web resource. */
public final class NGWResourceUrl {
    private static final String RESOURCE_SEGMENT = "/resource/";

    private final String mServerUrl;
    private final String mAccountName;
    private final long mResourceId;

    private NGWResourceUrl(String serverUrl, String accountName, long resourceId) {
        mServerUrl = serverUrl;
        mAccountName = accountName;
        mResourceId = resourceId;
    }

    public static NGWResourceUrl parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Resource URL is empty");
        }

        final URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid resource URL", e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Only HTTP and HTTPS resource URLs are supported");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Resource URL must contain a server host without credentials");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("Resource URL must not contain a fragment");
        }

        String path = uri.getPath();
        int marker = path == null ? -1 : path.lastIndexOf(RESOURCE_SEGMENT);
        if (marker < 0) {
            throw new IllegalArgumentException("Resource URL must contain /resource/<id>");
        }

        String idPart = path.substring(marker + RESOURCE_SEGMENT.length());
        if (idPart.endsWith("/")) {
            idPart = idPart.substring(0, idPart.length() - 1);
        }
        if (idPart.isEmpty() || idPart.indexOf('/') >= 0 || !idPart.matches("[0-9]+")) {
            throw new IllegalArgumentException("Resource id must be a positive integer");
        }

        final long resourceId;
        try {
            resourceId = Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Resource id is too large", e);
        }
        if (resourceId <= 0L) {
            throw new IllegalArgumentException("Resource id must be a positive integer");
        }

        String basePath = path.substring(0, marker);
        String serverUrl = buildServerUrl(uri, basePath);
        String accountName = buildAccountName(uri, basePath);
        return new NGWResourceUrl(serverUrl, accountName, resourceId);
    }

    public String getServerUrl() {
        return mServerUrl;
    }

    public String getAccountName() {
        return mAccountName;
    }

    public long getResourceId() {
        return mResourceId;
    }

    public boolean matchesServerUrl(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(candidate.trim());
            String path = trimTrailingSlashes(uri.getPath());
            return mServerUrl.equalsIgnoreCase(buildServerUrl(uri, path));
        } catch (IllegalArgumentException | URISyntaxException e) {
            return false;
        }
    }

    private static String buildServerUrl(URI uri, String path) {
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Server URL is incomplete");
        }
        String normalizedPath = trimTrailingSlashes(path);
        try {
            return new URI(
                    scheme.toLowerCase(Locale.US),
                    null,
                    uri.getHost().toLowerCase(Locale.US),
                    uri.getPort(),
                    normalizedPath.isEmpty() ? null : normalizedPath,
                    null,
                    null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid server URL", e);
        }
    }

    private static String buildAccountName(URI uri, String path) {
        StringBuilder result = new StringBuilder(uri.getHost().toLowerCase(Locale.US));
        if (uri.getPort() >= 0) {
            result.append(':').append(uri.getPort());
        }
        String normalizedPath = trimTrailingSlashes(path);
        if (!normalizedPath.isEmpty()) {
            result.append(normalizedPath);
        }
        return result.toString();
    }

    private static String trimTrailingSlashes(String value) {
        if (value == null || value.isEmpty() || "/".equals(value)) {
            return "";
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
