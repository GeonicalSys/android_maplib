package com.nextgis.maplib.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.nextgis.maplib.R;
import com.nextgis.maplib.api.IProgressor;
import com.nextgis.maplib.map.LocalTMSLayer;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/** Android stream/configuration adapter around the durable catalog. */
public final class SharedUnderlayStore {
    private SharedUnderlayStore() { }
    public static SharedUnderlayCatalog catalog(Context context) {
        File mapRoot = context.getExternalFilesDir(SettingsConstants.KEY_PREF_MAP);
        if (mapRoot == null) mapRoot = new File(context.getFilesDir(), SettingsConstants.KEY_PREF_MAP);
        return new SharedUnderlayCatalog(new File(mapRoot, "shared_underlays"));
    }

    public static boolean eligible(File directory, JSONObject config) {
        return config.optInt("type") == Constants.LAYERTYPE_LOCAL_TMS
                && config.optString(SharedUnderlayCatalog.LAYER_KEY, "").isEmpty()
                && (config.has("ngrc_provenance") || new File(directory, MbTilesInfo.MBTILES_FILENAME).isFile());
    }

    /** Metadata and rename only. Existing MBTiles hashes and directory sizes are indexed later. */
    public static JSONObject migrate(Context context, File directory, JSONObject config) throws IOException {
        if (!config.optString(SharedUnderlayCatalog.LAYER_KEY, "").isEmpty())
            return catalog(context).repairReference(directory, config);
        if (!eligible(directory, config)) return config;
        JSONObject provenance = config.optJSONObject("ngrc_provenance");
        String hash = provenance == null ? "" : provenance.optString("archive_sha256", "");
        SharedUnderlayCatalog catalog = catalog(context);
        catalog.migrateLayer(directory, config, new File(directory, MbTilesInfo.MBTILES_FILENAME).isFile()
                ? SharedUnderlayCatalog.MBTILES : SharedUnderlayCatalog.LEGACY_TILES, hash, -1);
        return UnderlayFiles.readJson(new File(directory, "config.json"));
    }

    public static void migrateWorkspace(Context context, File mapFile) throws IOException {
        catalog(context).recover();
        for (UnderlayWorkspaceIndex.Link layer : UnderlayWorkspaceIndex.layers(mapFile))
            migrate(context, layer.directory, layer.config);
    }

    public static void attach(LocalTMSLayer layer, SharedUnderlayCatalog.Asset asset) throws IOException {
        if (asset == null || !asset.isReady()) throw new IOException("Underlay is not available");
        try {
            JSONObject own = asset.referenceConfig(layer.toJSON()), template = asset.layerConfig();
            for (String key : new String[]{"min_level", "max_level", "ngrc_provenance"}) {
                if (template.has(key)) own.put(key, template.get(key)); else own.remove(key);
            }
            layer.fromJSON(own);
            if (!layer.save()) throw new IOException("Cannot save underlay reference");
        } catch (JSONException e) { throw new IOException("Invalid underlay configuration", e); }
    }

    public static void importNgrc(LocalTMSLayer layer, Uri source, IProgressor progress) throws IOException, NGException {
        SharedUnderlayCatalog catalog = catalog(layer.getContext());
        NgrcArchive.Source input = () -> open(layer.getContext(), source);
        NgrcArchive.Progress check = () -> check(progress);
        beginLoading(progress, layer.getContext());
        NgrcArchive.Info archive = NgrcArchive.inspect(input, check);
        SharedUnderlayCatalog.Asset existing = catalog.find(archive.sha256, null);
        if (existing != null) { layer.setName(existing.name); attach(layer, existing); return; }
        File stage = catalog.createStage();
        try {
            File file = new File(new File(stage, "payload"), MbTilesInfo.MBTILES_FILENAME);
            String name = archive.config.optString("name", layer.getName());
            if (FileUtil.isUnusableDisplayName(name)) {
                name = layer.getContext().getString(R.string.underlay_unnamed);
            }
            reportTiles(progress, layer.getContext(), 0, archive.tileCount);
            try (RasterMbtilesWriter writer = new RasterMbtilesWriter(file, name)) {
                final int[] done = {0};
                NgrcArchive.convert(input, archive, (path, data, scheme) -> {
                    writer.addTile(path, data, scheme);
                    done[0]++;
                    reportTiles(progress, layer.getContext(), done[0], archive.tileCount);
                    check(progress);
                }, check);
                writer.finish();
            }
            sync(file);
            layer.configureAsRasterMbTiles(MbTilesInfo.inspect(file));
            layer.setName(name);
            layer.setVisible(archive.config.optBoolean("visible", true));
            layer.setMinZoom(Math.max(GeoConstants.DEFAULT_MIN_ZOOM, layer.getMinZoom() - 2f));
            layer.setMaxZoom(Math.min(GeoConstants.DEFAULT_MAX_ZOOM, layer.getMaxZoom() + 2f));
            layer.setNgrcImportProvenance(source.getLastPathSegment(), archive.sha256);
            SharedUnderlayCatalog.Asset asset = catalog.publish(stage, name, SharedUnderlayCatalog.MBTILES,
                    archive.sha256, file.length(), layer.toJSON(), null);
            attach(layer, asset);
        } catch (JSONException e) { throw new IOException(e); }
        finally { if (stage.exists()) catalog.discardStage(stage); }
    }

    public static void importMbtiles(LocalTMSLayer layer, Uri source, IProgressor progress) throws IOException, NGException {
        SharedUnderlayCatalog catalog = catalog(layer.getContext());
        beginLoading(progress, layer.getContext());
        String hash;
        try (InputStream input = checked(openMbtiles(layer.getContext(), source), progress)) { hash = UnderlayFiles.sha256(input); }
        SharedUnderlayCatalog.Asset existing = catalog.find(hash, null);
        if (existing != null) { attach(layer, existing); return; }
        File stage = catalog.createStage();
        try {
            File file = new File(new File(stage, "payload"), MbTilesInfo.MBTILES_FILENAME);
            MessageDigest digest = UnderlayFiles.digest();
            long total = openableSize(layer.getContext(), source);
            long copied = 0L;
            reportBytes(progress, copied, total);
            try (InputStream input = new DigestInputStream(checked(openMbtiles(layer.getContext(), source), progress), digest);
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    copied += count;
                    reportBytes(progress, copied, total);
                }
                output.getFD().sync();
            }
            if (!hash.equals(UnderlayFiles.hex(digest.digest()))) throw new IOException("MBTiles changed during import");
            layer.configureAsRasterMbTiles(MbTilesInfo.inspect(file));
            SharedUnderlayCatalog.Asset asset = catalog.publish(stage, layer.getName(), SharedUnderlayCatalog.MBTILES,
                    hash, file.length(), layer.toJSON(), null);
            attach(layer, asset);
        } catch (JSONException e) { throw new IOException(e); }
        finally { if (stage.exists()) catalog.discardStage(stage); }
    }

    public static InputStream open(Context context, Uri uri) throws IOException {
        InputStream input = NetworkUtil.isValidUri(uri.toString()) ? new URL(uri.toString()).openStream()
                : context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Underlay file is unavailable");
        return input;
    }

    private static InputStream openMbtiles(Context context, Uri uri) throws IOException {
        PushbackInputStream input = new PushbackInputStream(new BufferedInputStream(open(context, uri)), 4);
        byte[] header = new byte[4]; int count = input.read(header); if (count > 0) input.unread(header, 0, count);
        if (count != 4 || header[0] != 'P' || header[1] != 'K') return input;
        java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(input);
        try {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                NgrcArchive.normalized(entry.getName());
                if (!entry.isDirectory() && entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mbtiles")) {
                    return new FilterInputStream(zip) {
                        private boolean finished;
                        @Override public int read(byte[] bytes, int off, int length) throws IOException {
                            if (finished) return -1;
                            int read = in.read(bytes, off, length);
                            if (read == -1) {
                                finished = true;
                                java.util.zip.ZipEntry extra;
                                while ((extra = zip.getNextEntry()) != null) {
                                    NgrcArchive.normalized(extra.getName());
                                    if (!extra.isDirectory() && extra.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mbtiles"))
                                        throw new IOException("ZIP contains multiple MBTiles databases");
                                    zip.closeEntry();
                                }
                            }
                            return read;
                        }
                    };
                }
                zip.closeEntry();
            }
            throw new IOException("ZIP does not contain a raster MBTiles database");
        } catch (IOException e) { zip.close(); throw e; }
    }
    private static InputStream checked(InputStream input, IProgressor progress) {
        return new FilterInputStream(input) {
            @Override public int read(byte[] bytes, int off, int count) throws IOException {
                check(progress); return in.read(bytes, off, count);
            }
        };
    }
    private static void check(IProgressor progress) throws IOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && progress.isCanceled()))
            throw new InterruptedIOException("Underlay import cancelled");
    }

    private static void beginLoading(IProgressor progress, Context context) {
        if (progress == null || context == null) return;
        progress.setIndeterminate(true);
        progress.setMessage(context.getString(R.string.message_loading));
    }

    private static void reportTiles(IProgressor progress, Context context, int value, int max) {
        if (progress == null || context == null || max <= 0) return;
        progress.setIndeterminate(false);
        progress.setMax(max);
        progress.setValue(value);
        progress.setMessage(context.getString(R.string.underlay_import_tiles, value, max));
    }

    private static void reportBytes(IProgressor progress, long copied, long total) {
        if (progress == null || total <= 0L) return;
        progress.setIndeterminate(false);
        int max = total <= Integer.MAX_VALUE ? (int) total : 10000;
        int value = total <= Integer.MAX_VALUE
                ? (int) Math.min(copied, total)
                : (int) Math.min(10000L, copied * 10000L / total);
        progress.setMax(max);
        progress.setValue(value);
    }

    private static long openableSize(Context context, Uri uri) {
        if (context == null || uri == null) return -1L;
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) {
                    return cursor.getLong(column);
                }
            }
        } catch (RuntimeException ignored) { }
        return -1L;
    }

    public static void sync(File file) throws IOException {
        try (RandomAccessFile opened = new RandomAccessFile(file, "rw")) { opened.getFD().sync(); }
    }
}
