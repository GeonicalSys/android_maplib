/*
 * Project: NextGIS Mobile
 * Purpose: Local HTTP endpoint for on-demand vector tiles.
 */

package com.nextgis.maplib.map;

import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.util.Constants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();

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
        return getTileUrl(layer.getId());
    }

    public void unregisterLayer(int layerId) {
        mLayers.remove(layerId);
    }

    public void clearLayers() {
        mLayers.clear();
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
                mExecutor.execute(() -> handle(socket));
            } catch (IOException e) {
                if (mRunning) {
                    Log.w(TAG, "accept failed", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
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
            Response response = route(requestLine);
            writeResponse(s.getOutputStream(), response);
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        }
    }

    private Response route(String requestLine) {
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
            VectorLayer layer = mLayers.get(layerId);
            if (layer == null) {
                return Response.text(404, "Layer Not Registered");
            }
            byte[] tile = LocalVectorTileProvider.buildTile(layer, z, x, y);
            return new Response(200, "application/x-protobuf", tile);
        } catch (Exception e) {
            HyperLog.w(Constants.TAG, "Local vector tile request failed: "
                    + e.getMessage(), e);
            return Response.text(500, "Tile Error");
        }
    }

    private static void writeResponse(OutputStream out, Response response) throws IOException {
        byte[] body = response.body != null ? response.body : new byte[0];
        String headers = "HTTP/1.1 " + response.status + " " + response.reason() + "\r\n"
                + "Content-Type: " + response.contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static final class Response {
        final int status;
        final String contentType;
        final byte[] body;

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
                default:
                    return "OK";
            }
        }
    }
}
