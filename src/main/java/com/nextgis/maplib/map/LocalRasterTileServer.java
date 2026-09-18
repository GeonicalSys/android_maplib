/*
 * Project: NextGIS Mobile
 * Purpose: On-demand display transforms for local raster MBTiles.
 */
package com.nextgis.maplib.map;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;
import com.nextgis.maplib.util.MbTilesInfo;
import com.nextgis.maplib.util.RasterWhiteChromaKey;
import com.nextgis.maplib.util.TransparentPng;
import com.nextgis.maplib.util.UnderlayDisplaySettings;
import com.nextgis.maplib.util.UnderlayRasterZoomPolicy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loopback raster source for local MBTiles. It transforms only requested viewport tiles and never
 * copies or rewrites the source database.
 */
public final class LocalRasterTileServer {
    private static final String TAG = "LocalRasterTiles";
    private static final LocalRasterTileServer INSTANCE = new LocalRasterTileServer();
    private static final Pattern TILE_PATH = Pattern.compile(
            "^/raster/(\\d+)/([a-zA-Z0-9_-]+)/(\\d+)/(\\d+)/(\\d+)\\.tile$");
    private static final ExecutorService CLEANUP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "RasterSidecarCleanup");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<Integer, Registration> mLayers = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor mExecutor = LocalVectorTileRequestPolicy.newExecutor();
    private final AtomicLong mGeneration = new AtomicLong();
    private volatile ServerSocket mServerSocket;
    private volatile int mPort = -1;
    private volatile boolean mRunning;

    private LocalRasterTileServer() {
    }

    public static LocalRasterTileServer getInstance() {
        return INSTANCE;
    }

    public String registerLayer(
            LocalTMSLayer layer, File database, UnderlayDisplaySettings settings) {
        if (layer == null || database == null || !database.isFile()) {
            return null;
        }
        MbTilesInfo info = MbTilesInfo.inspectForDisplay(database);
        if (!info.valid || !ensureStarted()) {
            HyperLog.w(Constants.TAG, "Local raster proxy unavailable layer=\""
                    + layer.getName() + "\" reason=" + info.diagnostic);
            return null;
        }
        int tileMin = info.minZoom >= 0
                ? info.minZoom : Math.max(0, (int) Math.floor(layer.getMinZoom()));
        int tileMax = info.maxZoom >= 0
                ? info.maxZoom : Math.max(tileMin, (int) Math.ceil(layer.getMaxZoom()));
        UnderlayDisplaySettings effective = settings != null
                ? settings : UnderlayDisplaySettings.from(layer.getContext());
        String token = Long.toHexString(database.lastModified()) + "-"
                + Long.toHexString(database.length()) + "-"
                + effective.fingerprint().replace('-', 'n');
        Registration registration = new Registration(
                database,
                effective,
                tileMin,
                tileMax,
                "xyz".equalsIgnoreCase(info.scheme),
                mimeType(info.format),
                token);
        mLayers.put(layer.getId(), registration);
        cleanupLegacyCopies(database);
        return "http://127.0.0.1:" + mPort + "/raster/" + layer.getId() + "/"
                + token + "/{z}/{x}/{y}.tile";
    }

    public boolean isLocalUrl(String url) {
        return url != null && mPort > 0
                && url.startsWith("http://127.0.0.1:" + mPort + "/raster/");
    }

    public int getSourceMinZoom(int layerId) {
        return mLayers.containsKey(layerId)
                ? UnderlayRasterZoomPolicy.sourceMinZoom() : -1;
    }

    public int getSourceMaxZoom(int layerId) {
        Registration registration = mLayers.get(layerId);
        return registration != null ? registration.tileMaxZoom : -1;
    }

    public void unregisterLayer(int layerId) {
        mLayers.remove(layerId);
    }

    public void clearLayers() {
        mGeneration.incrementAndGet();
        mLayers.clear();
        List<Runnable> queued = new ArrayList<>();
        mExecutor.getQueue().drainTo(queued);
        for (Runnable runnable : queued) {
            if (runnable instanceof RequestTask) {
                ((RequestTask) runnable).closeQuietly();
            }
        }
    }

    private boolean ensureStarted() {
        if (mRunning && mPort > 0) {
            return true;
        }
        synchronized (this) {
            if (mRunning && mPort > 0) {
                return true;
            }
            try {
                mServerSocket = new ServerSocket(
                        0, 16, InetAddress.getByName("127.0.0.1"));
                mPort = mServerSocket.getLocalPort();
                mRunning = true;
                Thread acceptThread = new Thread(this::acceptLoop, "LocalRasterTileServer");
                acceptThread.setDaemon(true);
                acceptThread.start();
                HyperLog.d(Constants.TAG, "Local raster tile server started port=" + mPort);
                return true;
            } catch (IOException e) {
                mRunning = false;
                mPort = -1;
                HyperLog.w(Constants.TAG, "Local raster tile server start failed: "
                        + e.getMessage(), e);
                return false;
            }
        }
    }

    private void acceptLoop() {
        while (mRunning && mServerSocket != null && !mServerSocket.isClosed()) {
            try {
                Socket socket = mServerSocket.accept();
                RequestTask task = new RequestTask(socket, mGeneration.get());
                try {
                    mExecutor.execute(task);
                } catch (RejectedExecutionException e) {
                    task.respond(Response.text(503, "Tile Server Busy"));
                }
            } catch (IOException e) {
                if (mRunning) {
                    Log.w(TAG, "accept failed", e);
                }
            }
        }
    }

    private void handle(Socket socket, long generation) {
        try (Socket closeable = socket) {
            closeable.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    closeable.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Drain request headers; keep-alive is deliberately not supported.
            }
            writeResponse(closeable.getOutputStream(), route(requestLine, generation));
        } catch (IOException ignored) {
            // MapLibre routinely cancels obsolete tile requests while panning or zooming.
        } catch (RuntimeException e) {
            Log.w(TAG, "request failed", e);
        }
    }

    private Response route(String requestLine, long generation) {
        try {
            String[] request = requestLine.split(" ");
            if (request.length < 2 || !"GET".equals(request[0].toUpperCase(Locale.ROOT))) {
                return Response.text(405, "Method Not Allowed");
            }
            String path = request[1];
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            Matcher matcher = TILE_PATH.matcher(path);
            if (!matcher.matches()) {
                return Response.text(404, "Not Found");
            }
            int layerId = Integer.parseInt(matcher.group(1));
            Registration registration = mLayers.get(layerId);
            if (registration == null || generation != mGeneration.get()
                    || !registration.token.equals(matcher.group(2))) {
                return Response.text(404, "Layer Not Registered");
            }
            int z = Integer.parseInt(matcher.group(3));
            int x = Integer.parseInt(matcher.group(4));
            int y = Integer.parseInt(matcher.group(5));
            synchronized (registration) {
                if (generation != mGeneration.get() || mLayers.get(layerId) != registration) {
                    return Response.text(404, "Layer Not Registered");
                }
                return buildResponse(registration, z, x, y);
            }
        } catch (Exception e) {
            HyperLog.w(Constants.TAG, "Local raster tile request failed: "
                    + e.getMessage(), e);
            return Response.text(500, "Tile Error");
        }
    }

    private Response buildResponse(Registration registration, int z, int x, int y) {
        if (z < 0 || z > GeoConstants.DEFAULT_MAX_ZOOM || x < 0 || y < 0) {
            return Response.text(404, "Tile Outside Range");
        }
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(
                    registration.database.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            if (z < registration.tileMinZoom) {
                byte[] composed = composeUnderzoom(registration, database, z, x, y);
                return new Response(200, "image/png", composed);
            }
            if (z > registration.tileMaxZoom) {
                return new Response(200, "image/png", TransparentPng.tile256());
            }
            byte[] tile = readTile(database, registration, z, x, y);
            if (tile == null) {
                if (registration.settings.lastLevelOverzoom) {
                    return new Response(200, "image/png", TransparentPng.tile256());
                }
                return Response.text(404, "Tile Missing");
            }
            if (!registration.settings.whiteAsTransparent) {
                return new Response(200, registration.mimeType, tile);
            }
            return new Response(200, "image/png", punchWhite(tile));
        } catch (RuntimeException e) {
            HyperLog.w(Constants.TAG, "Local raster database open failed: " + e.getMessage(), e);
            return Response.text(500, "Tile Error");
        } finally {
            if (database != null) {
                database.close();
            }
        }
    }

    private byte[] composeUnderzoom(
            Registration registration, SQLiteDatabase database, int z, int x, int y) {
        if (!UnderlayRasterZoomPolicy.canComposeUnderzoom(z, registration.tileMinZoom)) {
            return TransparentPng.tile256();
        }
        int delta = registration.tileMinZoom - z;
        int factor = 1 << delta;
        Bitmap output = Bitmap.createBitmap(
                TransparentPng.TILE_SIZE, TransparentPng.TILE_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        float cell = (float) TransparentPng.TILE_SIZE / factor;
        boolean drew = false;
        for (int childY = 0; childY < factor; childY++) {
            for (int childX = 0; childX < factor; childX++) {
                byte[] child = readTile(
                        database,
                        registration,
                        registration.tileMinZoom,
                        x * factor + childX,
                        y * factor + childY);
                if (child == null) {
                    continue;
                }
                Bitmap bitmap = BitmapFactory.decodeByteArray(child, 0, child.length);
                if (bitmap == null) {
                    continue;
                }
                canvas.drawBitmap(
                        bitmap,
                        null,
                        new android.graphics.RectF(
                                childX * cell,
                                childY * cell,
                                (childX + 1) * cell,
                                (childY + 1) * cell),
                        paint);
                bitmap.recycle();
                drew = true;
            }
        }
        if (!drew) {
            output.recycle();
            return TransparentPng.tile256();
        }
        if (registration.settings.whiteAsTransparent) {
            punchWhite(output);
        }
        byte[] result = encodePng(output);
        output.recycle();
        return result;
    }

    private byte[] readTile(
            SQLiteDatabase database, Registration registration, int z, int x, int xyzY) {
        try {
            long row = UnderlayRasterZoomPolicy.databaseRow(z, xyzY, registration.xyzScheme);
            try (Cursor cursor = database.rawQuery(
                    "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                    new String[]{Integer.toString(z), Integer.toString(x), Long.toString(row)})) {
                return cursor.moveToFirst() ? cursor.getBlob(0) : null;
            }
        } catch (RuntimeException e) {
            HyperLog.w(Constants.TAG, "Local raster tile read failed: " + e.getMessage(), e);
            return null;
        }
    }

    private byte[] punchWhite(byte[] encoded) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
        if (bitmap == null) {
            return encoded;
        }
        Bitmap mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        bitmap.recycle();
        if (mutable == null) {
            return encoded;
        }
        // JPEG and opaque PNG decoders mark the bitmap as having no alpha. In that state
        // Bitmap.compress() silently writes punched pixels back as opaque, even though their
        // packed ARGB value has alpha 0.
        mutable.setHasAlpha(true);
        boolean changed = punchWhite(mutable);
        if (!changed) {
            mutable.recycle();
            return encoded;
        }
        byte[] result = encodePng(mutable);
        mutable.recycle();
        return result;
    }

    private boolean punchWhite(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        boolean changed = RasterWhiteChromaKey.punchExactWhite(pixels);
        if (changed) {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        }
        return changed;
    }

    private byte[] encodePng(Bitmap bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        return output.toByteArray();
    }

    private static String mimeType(String format) {
        if (format == null) {
            return "image/png";
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if ("jpg".equals(normalized) || "jpeg".equals(normalized)) {
            return "image/jpeg";
        }
        if ("webp".equals(normalized)) {
            return "image/webp";
        }
        return "image/png";
    }

    private static void cleanupLegacyCopies(File database) {
        File parent = database.getParentFile();
        if (parent == null) {
            return;
        }
        CLEANUP_EXECUTOR.execute(() -> {
            deleteDerived(new File(parent, "map-mbtiles.display.mbtiles"));
            deleteDerived(new File(parent, "map-mbtiles.display.mbtiles.partial"));
        });
    }

    private static void deleteDerived(File file) {
        if (file.isFile() && !file.delete()) {
            HyperLog.w(Constants.TAG, "Cannot delete obsolete raster display cache");
        }
    }

    private static void writeResponse(OutputStream out, Response response) throws IOException {
        byte[] body = response.body != null ? response.body : new byte[0];
        String headers = "HTTP/1.1 " + response.status + " " + response.reason() + "\r\n"
                + "Content-Type: " + response.contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: public, max-age=86400\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private final class RequestTask implements Runnable {
        private final Socket socket;
        private final long generation;

        RequestTask(Socket socket, long generation) {
            this.socket = socket;
            this.generation = generation;
        }

        @Override
        public void run() {
            handle(socket, generation);
        }

        void respond(Response response) {
            try (Socket closeable = socket) {
                writeResponse(closeable.getOutputStream(), response);
            } catch (IOException ignored) {
                // Client may already have abandoned the request.
            }
        }

        void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort cancellation for the previous map generation.
            }
        }
    }

    private static final class Registration {
        final File database;
        final UnderlayDisplaySettings settings;
        final int tileMinZoom;
        final int tileMaxZoom;
        final boolean xyzScheme;
        final String mimeType;
        final String token;

        Registration(
                File database,
                UnderlayDisplaySettings settings,
                int tileMinZoom,
                int tileMaxZoom,
                boolean xyzScheme,
                String mimeType,
                String token) {
            this.database = database;
            this.settings = settings;
            this.tileMinZoom = tileMinZoom;
            this.tileMaxZoom = tileMaxZoom;
            this.xyzScheme = xyzScheme;
            this.mimeType = mimeType;
            this.token = token;
        }
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
                case 503:
                    return "Service Unavailable";
                default:
                    return "Error";
            }
        }
    }
}
