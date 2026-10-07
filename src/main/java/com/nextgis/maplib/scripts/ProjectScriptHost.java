package com.nextgis.maplib.scripts;

import android.database.Cursor;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.Log;
import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.map.CollectorProjectMetadata;
import com.nextgis.maplib.map.LayerGroup;
import com.nextgis.maplib.map.LayerOriginMetadata;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.map.VectorLayer;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.DatabaseContext;
import com.nextgis.maplib.util.GeoConstants;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Main-process broker. Revalidates every request independently of the sandbox JavaScript. */
public final class ProjectScriptHost extends IProjectScriptHost.Stub {
    private static final ScheduledExecutorService CANCEL = Executors.newSingleThreadScheduledExecutor();
    private final LayerGroup group;
    private final ScriptReference reference;
    private final ScriptPackage pack;
    private final LocalDate today;
    private long deadline;
    private int calls, rows;

    public ProjectScriptHost(LayerGroup group, ScriptReference reference, ScriptPackage pack, LocalDate today) {
        this.group = group; this.reference = reference; this.pack = pack; this.today = today;
    }
    @Override public synchronized byte[] call(byte[] request) {
        try {
            if (deadline == 0) deadline = SystemClock.elapsedRealtime() + 2000;
            if (++calls > 32 || request == null || request.length > 32768
                    || SystemClock.elapsedRealtime() >= deadline) throw new SecurityException("Host budget");
            JSONObject input = new JSONObject(ScriptPackage.utf8(request));
            String method = input.getString("method");
            if (!pack.hasCapability(method)) throw new SecurityException("Capability not granted");
            Object value;
            if (method.equals("gis.query")) value = query(input.getJSONObject("args"));
            else if (method.equals("time.monthWindow")) value = monthWindow(today);
            else throw new SecurityException("Unsupported host method");
            byte[] reply = new JSONObject().put("ok", true).put("value", value).toString().getBytes(StandardCharsets.UTF_8);
            if (reply.length > 128 * 1024) throw new SecurityException("Host reply size");
            return reply;
        } catch (Exception error) {
            Log.w("ProjectScripts", "Host request failed", error);
            return "{\"ok\":false,\"error\":\"host_request_failed\"}".getBytes(StandardCharsets.UTF_8);
        }
    }
    public static JSONObject monthWindow(LocalDate today) throws JSONException {
        return new JSONObject().put("from", today.minusMonths(1).toString())
                .put("through", today.toString()).put("until", today.plusDays(1).toString());
    }
    public static LayerGroup projectFor(VectorLayer layer) {
        for (ILayer parent = layer.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof LayerGroup && ((LayerGroup)parent).getCollectorProjectMetadata() != null)
                return (LayerGroup) parent;
        }
        return null;
    }
    public static NGWVectorLayer resolve(LayerGroup group, ScriptReference ref, String alias) throws JSONException {
        CollectorProjectMetadata metadata = group.getCollectorProjectMetadata();
        if (metadata == null || !metadata.isValid()) throw new SecurityException("Project identity missing");
        ILayer found = LayerGroup.findCollectorManagedLayerByRemoteIdRecursive(group, ref.layerId(alias),
                metadata.getAccountName(), metadata.getProjectUid());
        if (!(found instanceof NGWVectorLayer)) throw new SecurityException("Managed vector layer unavailable");
        NGWVectorLayer layer = (NGWVectorLayer)found;
        LayerOriginMetadata origin = layer.getLayerOriginMetadata();
        if (origin == null || !origin.isManagedByProject()
                || !metadata.getProjectUid().equals(origin.getProjectUid())
                || !metadata.getAccountName().equals(layer.getAccountName())
                || DatabaseContext.getMapForLayer(layer) != DatabaseContext.getMapForLayer(group))
            throw new SecurityException("Foreign layer");
        return layer;
    }
    private JSONObject query(JSONObject args) throws Exception {
        String alias = args.getString("layer");
        if (!pack.aliases().contains(alias)) throw new SecurityException("Unbound logical layer");
        NGWVectorLayer layer = resolve(group, reference, alias);
        JSONArray fields = args.getJSONArray("fields");
        if (fields.length() == 0 || fields.length() > 32) throw new SecurityException("Projection size");
        String[] projection = new String[fields.length()+1];
        projection[0] = quote(Constants.FIELD_ID);
        Set<String> seen = new HashSet<>();
        for (int i=0; i<fields.length(); i++) {
            String name = fields.getString(i);
            requireField(layer, alias, name);
            if (!seen.add(name)) throw new SecurityException("Duplicate field");
            projection[i+1] = quote(name);
        }
        int limit = args.getInt("limit");
        if (limit < 1 || limit > 200 || rows + limit > 1000) throw new SecurityException("Row budget");
        JSONArray filters = args.getJSONArray("where");
        if (filters.length() == 0 || filters.length() > 16) throw new SecurityException("Filter budget");
        StringBuilder selection = new StringBuilder();
        ArrayList<String> parameters = new ArrayList<>();
        for (int i=0; i<filters.length(); i++) {
            JSONObject filter = filters.getJSONObject(i);
            String name = filter.getString("field");
            Field field = requireField(layer, alias, name);
            String operator;
            switch (filter.getString("op")) {
                case "eq": operator = "="; break;
                case "ne": operator = "<>"; break;
                case "gte": operator = ">="; break;
                case "gt": operator = ">"; break;
                case "lte": operator = "<="; break;
                case "lt": operator = "<"; break;
                default: throw new SecurityException("Filter operator");
            }
            if (i > 0) selection.append(" AND ");
            selection.append(quote(name)).append(operator).append('?');
            parameters.add(filterValue(field, filter.get("value")));
        }
        CancellationSignal cancellation = new CancellationSignal();
        long remaining = Math.min(500, deadline - SystemClock.elapsedRealtime());
        if (remaining <= 0) throw new SecurityException("Query deadline");
        var cancel = CANCEL.schedule(cancellation::cancel, remaining, TimeUnit.MILLISECONDS);
        JSONArray result = new JSONArray();
        boolean truncated = false;
        try (Cursor cursor = DatabaseContext.getDatabaseForLayer(layer, false).query(false,
                quote(layer.getPath().getName()), projection, selection.toString(),
                parameters.toArray(new String[0]), null, null, quote(Constants.FIELD_ID) + " ASC",
                Integer.toString(limit+1), cancellation)) {
            while (cursor.moveToNext()) {
                cancellation.throwIfCanceled();
                if (result.length() == limit) { truncated = true; break; }
                JSONObject values = new JSONObject();
                for (int i=0; i<fields.length(); i++) {
                    String name = fields.getString(i);
                    values.put(name, cursor.isNull(i+1) ? JSONObject.NULL : readValue(cursor, i+1, layer.getFieldByName(name)));
                }
                result.put(new JSONObject().put("id", Long.toString(cursor.getLong(0))).put("fields", values));
                if (result.toString().length() > 60000) throw new SecurityException("Query byte budget");
            }
        } finally { cancel.cancel(false); }
        rows += result.length();
        return new JSONObject().put("features", result).put("truncated", truncated)
                .put("scope", "local").put("generation", layer.getDataGeneration());
    }
    private Field requireField(VectorLayer layer, String alias, String name) {
        Field field = layer.getFieldByName(name);
        if (!pack.canRead(alias, name) || field == null) throw new SecurityException("Field not granted or missing");
        return field;
    }
    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    private static String filterValue(Field field, Object value) {
        if (value == JSONObject.NULL) throw new IllegalArgumentException("Null filter unsupported");
        switch (field.getType()) {
            case GeoConstants.FTString:
                if (!(value instanceof String) || ((String)value).length() > 2048) throw new IllegalArgumentException("String filter");
                return (String)value;
            case GeoConstants.FTDate:
                return Long.toString(LocalDate.parse((String)value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
            case GeoConstants.FTInteger: case GeoConstants.FTLong:
            case GeoConstants.FTDateTime: case GeoConstants.FTTime:
                return Long.toString(Long.parseLong(value.toString()));
            case GeoConstants.FTReal:
                double real = Double.parseDouble(value.toString());
                if (!Double.isFinite(real)) throw new IllegalArgumentException("Finite filter required");
                return Double.toString(real);
            default: throw new IllegalArgumentException("Unsupported field type");
        }
    }
    public static Object readValue(Cursor cursor, int column, Field field) {
        switch (field.getType()) {
            case GeoConstants.FTInteger: return cursor.getInt(column);
            case GeoConstants.FTReal: return cursor.getDouble(column);
            case GeoConstants.FTLong: return Long.toString(cursor.getLong(column));
            case GeoConstants.FTDate: return Instant.ofEpochMilli(cursor.getLong(column)).atOffset(ZoneOffset.UTC).toLocalDate().toString();
            case GeoConstants.FTDateTime: case GeoConstants.FTTime: return cursor.getLong(column);
            case GeoConstants.FTString: return cursor.getString(column);
            default: throw new IllegalArgumentException("Unsupported query type");
        }
    }
}
