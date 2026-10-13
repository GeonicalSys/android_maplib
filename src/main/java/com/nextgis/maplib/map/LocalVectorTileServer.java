/*
 * Project: NextGIS Mobile
 * Purpose: Local HTTP endpoint for on-demand vector tiles.
 */

package com.nextgis.maplib.map;

import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.MapRuntimeDiagnostics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small loopback HTTP server used by MapLibre VectorSource.
 *
 * It is process-local and only exposes generated MVT bytes for registered in-memory
 * {@link VectorLayer} objects. Keep this class even if the first provider is incomplete: it is the
 * integration point for the local-vector-tiles render path.
 */
public final class LocalVectorTileServer {
    private static final String TAG = "LocalVectorTiles";
    private static final LocalVectorTileServer INSTANCE = new LocalVectorTileServer();
    private static final Pattern TILE_PATH = Pattern.compile(
            "^/tiles/(\\d+)/(\\d+)/(\\d+)/(\\d+)\\.pbf$");

    private final Map<Integer, VectorLayer> mLayers = new ConcurrentHashMap<>();
    private final Map<Integer, Object> mLayerBuildLocks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor mExecutor = LocalVectorTileRequestPolicy.newExecutor();
    private final AtomicLong mLayerGeneration = new AtomicLong();

    private volatile ServerSocket mServerSocket;
    private volatile int mPort = -1;
    private volatile boolean mRunning;

    private LocalVectorTileServer() {
    }

    public static LocalVectorTileServer getInstance() {
        return INSTANCE;
    }

    public String registerLayer(VectorLayer layer) {
        if (layer == null || !LocalVectorTileProvider.canServe(layer)) {
            return null;
        }
        if (!ensureStarted()) {
            return null;
        }
        mLayers.put(layer.getId(), layer);
        mLayerBuildLocks.computeIfAbsent(layer.getId(), ignored -> new Object());
        return getTileUrl(layer.getId());
    }

    public void unregisterLayer(int layerId) {
        mLayers.remove(layerId);
        mLayerBuildLocks.remove(layerId);
    }

    public void clearLayers() {
        mLayerGeneration.incrementAndGet();
        mLayers.clear();
        mLayerBuildLocks.clear();
        closeQueuedRequests();
    }

    public boolean ensureStarted() {
        if (mRunning && mPort > 0) {
            return true;
        }
        synchronized (this) {
            if (mRunning && mPort > 0) {
                return true;
            }
            try {
                mServerSocket = new ServerSocket(
                        0,
                        16,
                        InetAddress.getByName("127.0.0.1"));
                mPort = mServerSocket.getLocalPort();
                mRunning = true;
                Thread acceptThread = new Thread(this::acceptLoop, "LocalVectorTileServer");
                acceptThread.setDaemon(true);
                acceptThread.start();
                HyperLog.d(Constants.TAG, "Local vector tile server started port=" + mPort);
                return true;
            } catch (IOException e) {
                mRunning = false;
                mPort = -1;
                HyperLog.w(Constants.TAG, "Local vector tile server start failed: "
                        + e.getMessage(), e);
                return false;
            }
        }
    }

    public String getTileUrl(int layerId) {
        if (mPort <= 0) {
            return null;
        }
        return "http://127.0.0.1:" + mPort + "/tiles/" + layerId + "/{z}/{x}/{y}.pbf";
    }

    private void acceptLoop() {
        while (mRunning && mServerSocket != null && !mServerSocket.isClosed()) {
            try {
                Socket socket = mServerSocket.accept();
                RequestTask request = new RequestTask(socket, mLayerGeneration.get());
                try {
                    mExecutor.execute(request);
                } catch (RejectedExecutionException rejected) {
                    MapRuntimeDiagnostics.tileRejected(request.mRequestId, "queue_full",
                            mExecutor.getActiveCount(), mExecutor.getQueue().size(), null);
                    request.rejectOverload();
                }
            } catch (IOException e) {
                if (mRunning) {
                    Log.w(TAG, "accept failed", e);
                }
            }
        }
    }

    private void handle(Socket socket, long generation, MapRuntimeDiagnostics.Trace trace) {
        String failure = null;
        int bytes = -1;
        try (Socket s = socket) {
            trace.phase("request_read");
            if (generation != mLayerGeneration.get()) {
                return;
            }
            s.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                // Drain headers; keep-alive is deliberately ignored.
            }
            Response response = route(requestLine, generation, trace);
            trace.put("status", response.status);
            if (response.status == 500) failure = "tile_error";
            bytes = response.body == null ? 0 : response.body.length;
            trace.phase("write_response");
            writeResponse(s.getOutputStream(), response);
        } catch (Exception e) {
            failure = e.getClass().getSimpleName();
            Log.w(TAG, "request failed", e);
        } finally {
            trace.finish(bytes, failure);
        }
    }

    private Response route(String requestLine, long generation, MapRuntimeDiagnostics.Trace trace) {
        try {
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0].toUpperCase(Locale.ROOT))) {
                return Response.text(405, "Method Not Allowed");
            }
            String path = parts[1];
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            Matcher matcher = TILE_PATH.matcher(path);
            if (!matcher.matches()) {
                return Response.text(404, "Not Found");
            }
            int layerId = Integer.parseInt(matcher.group(1));
            int z = Integer.parseInt(matcher.group(2));
            int x = Integer.parseInt(matcher.group(3));
            int y = Integer.parseInt(matcher.group(4));
            trace.put("layer_id", layerId); trace.put("z", z); trace.put("x", x); trace.put("y", y);
            VectorLayer layer = mLayers.get(layerId);
            if (layer == null) {
                return Response.text(404, "Layer Not Registered");
            }
            Runtime runtime = Runtime.getRuntime();
            if (!LocalVectorTileRequestPolicy.hasHeapHeadroom(
                    runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory())) {
                MapRuntimeDiagnostics.tileRejected(trace.id(), "heap_headroom",
                        mExecutor.getActiveCount(), mExecutor.getQueue().size(), trace);
                return Response.busy("heap_headroom", trace.id());
            }
            Object buildLock = mLayerBuildLocks.computeIfAbsent(layerId, ignored -> new Object());
            byte[] tile;
            trace.phase("wait_layer_lock");
            synchronized (buildLock) {
                if (generation != mLayerGeneration.get() || mLayers.get(layerId) != layer) {
                    return Response.text(404, "Layer Not Registered");
                }
                tile = LocalVectorTileProvider.buildTile(layer, z, x, y, trace);
            }
            return new Response(200, "application/x-protobuf", tile);
        } catch (Exception e) {
            trace.put("error_type", e.getClass().getSimpleName());
            HyperLog.w(Constants.TAG, "Local vector tile request failed: "
                    + e.getMessage(), e);
            return Response.text(500, "Tile Error");
        }
    }

    private void closeQueuedRequests() {
        List<Runnable> queued = new ArrayList<>();
        mExecutor.getQueue().drainTo(queued);
        for (Runnable runnable : queued) {
            if (runnable instanceof RequestTask) {
                ((RequestTask) runnable).closeQuietly();
            }
        }
    }

    private final class RequestTask implements Runnable {
        private final Socket mSocket;
        private final long mGeneration;
        private final long mRequestId = MapRuntimeDiagnostics.nextRequestId();
        private final long mAcceptedAt = MapRuntimeDiagnostics.now();

        RequestTask(Socket socket, long generation) {
            mSocket = socket;
            mGeneration = generation;
        }

        @Override
        public void run() {
            MapRuntimeDiagnostics.tileLoad(mExecutor.getActiveCount(), mExecutor.getQueue().size());
            try {
                handle(mSocket, mGeneration, MapRuntimeDiagnostics.beginTile(mRequestId, mAcceptedAt));
            } finally {
                MapRuntimeDiagnostics.tileLoad(Math.max(0, mExecutor.getActiveCount() - 1), mExecutor.getQueue().size());
            }
        }

        void rejectOverload() {
            try (Socket socket = mSocket) {
                writeResponse(socket.getOutputStream(), Response.busy("queue_full", mRequestId));
            } catch (IOException ignored) {
                // The MapLibre client may already have abandoned this request.
            }
        }

        void closeQuietly() {
            try {
                mSocket.close();
            } catch (IOException ignored) {
                // Best-effort cancellation of requests for the previous map generation.
            }
        }
    }

    private static void writeResponse(OutputStream out, Response response) throws IOException {
        byte[] body = response.body != null ? response.body : new byte[0];
        String headers = "HTTP/1.1 " + response.status + " " + response.reason() + "\r\n"
                + "Content-Type: " + response.contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n"
                + (response.diagnosticReason == null ? "" : MapRuntimeDiagnostics.REASON_HEADER + ": "
                    + response.diagnosticReason + "\r\n" + MapRuntimeDiagnostics.REQUEST_HEADER + ": "
                    + response.requestId + "\r\n")
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        String diagnosticReason;
        long requestId;

        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        static Response text(int status, String text) {
            return new Response(
                    status,
                    "text/plain; charset=utf-8",
                    text.getBytes(StandardCharsets.UTF_8));
        }

        static Response busy(String reason, long requestId) {
            Response response = text(503, "Tile Server Busy");
            response.diagnosticReason = reason;
            response.requestId = requestId;
            return response;
        }

        String reason() {
            switch (status) {
                case 200:
                    return "OK";
                case 404:
                    return "Not Found";
                case 405:
                    return "Method Not Allowed";
                case 500:
                    return "Internal Server Error";
                case 503:
                    return "Service Unavailable";
                default:
                    return "OK";
            }
        }
    }
}
