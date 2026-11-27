package com.nextgis.maplib.util;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ByteOrderValues;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/** NGW feature transactions for explicitly reviewed creations, without replaying an uncertain commit. */
public final class NgwFeatureTransaction {
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private NgwFeatureTransaction() { }

    @FunctionalInterface
    public interface Transport {
        HttpResponse request(String method, String absoluteUrl, String body, AccountUtil.AccountData account) throws IOException;
    }

    public static final class Result {
        public final boolean accepted, uncertain, retryable, rejected;
        public final long remoteId;
        public final HttpResponse response;
        public final String reason;
        private Result(boolean accepted, boolean uncertain, boolean retryable, boolean rejected,
                long remoteId, HttpResponse response, String reason) {
            this.accepted = accepted; this.uncertain = uncertain; this.retryable = retryable;
            this.rejected = rejected; this.remoteId = remoteId; this.response = response; this.reason = reason;
        }
    }

    private static Result blocked(String reason) { return new Result(false, false, false, false, -1, null, reason); }
    static boolean isInFlight(SQLiteDatabase db, String layer, String id) {
        return IN_FLIGHT.contains(db.getPath() + "\n" + layer + "\n" + id);
    }
    private static boolean ok(HttpResponse response) {
        return response != null && response.isOk() && response.getResponseCode() >= 200 && response.getResponseCode() < 300;
    }

    /** currentTarget must be recomputed from the current workspace, account, URL, login and resource. */
    public static Result execute(SQLiteDatabase db, UploadOperationJournal.Entry receipt, String currentTarget,
            long resourceId, AccountUtil.AccountData account, Transport transport) {
        if (db.inTransaction()) throw new SQLiteException("No transaction HTTP is allowed inside SQLite transactions");
        if (receipt == null || !"create".equals(receipt.operation) || receipt.payload == null)
            return blocked("transaction_requires_creation_receipt");
        UploadTransactionJournal.Entry entry = UploadTransactionJournal.find(db, receipt.layer, receipt.id);
        if (entry == null) return blocked("transaction_opt_in_missing");
        if (!entry.target.equals(receipt.target) || !entry.target.equals(currentTarget))
            return blocked("transaction_target_changed");
        if (!entry.sourceSha.equals(UploadTransactionJournal.sha256(receipt.payload)))
            return blocked("transaction_source_changed");
        if (resourceId < 0 || account == null || account.url == null || account.login == null)
            return blocked("transaction_target_invalid");
        String base;
        try { base = baseUrl(account.url); }
        catch (URISyntaxException error) { return blocked("transaction_target_invalid"); }
        String key = db.getPath() + "\n" + receipt.layer + "\n" + receipt.id;
        if (!IN_FLIGHT.add(key)) return blocked("transaction_already_in_flight");
        try {
            if (UploadTransactionJournal.COMMITTED.equals(entry.phase))
                return new Result(true, false, false, false, entry.remoteId, null, "server_transaction_confirmed");
            if (UploadTransactionJournal.REJECTED.equals(entry.phase))
                return new Result(false, false, false, true, -1, null, entry.reason);
            if (entry.commitStarted) return results(db, entry, base, resourceId, account, transport);

            if (UploadTransactionJournal.PREPARING.equals(entry.phase)) {
                try {
                    String operation = prepare(receipt.payload, entry, base, account, transport);
                    entry = UploadTransactionJournal.prepared(db, entry, operation);
                } catch (PreparationHttp error) {
                    return fail(db, entry, error.response, "transaction_geometry_transform_failed", false, true, false);
                } catch (IOException error) {
                    return fail(db, entry, null, "transaction_preparation_transport_failed", false, true, false);
                } catch (JSONException | ParseException | IllegalArgumentException error) {
                    return fail(db, entry, null, "transaction_payload_invalid", false, false, false);
                }
            }
            if (entry.operationJson == null) return blocked("transaction_payload_missing");
            String collection = base + "/api/resource/" + resourceId + "/feature/transaction/";
            if (UploadTransactionJournal.PREPARED.equals(entry.phase)) {
                HttpResponse allocated;
                try { allocated = transport.request("POST", collection, "{}", account); }
                catch (IOException error) {
                    // No feature operation can have been staged without a durably stored tid.
                    return fail(db, entry, null, "transaction_allocation_response_missing", false, true, false);
                }
                if (!ok(allocated)) return fail(db, entry, allocated, "transaction_allocation_failed", false, true, false);
                try {
                    long tid = UploadOperationJournal.serverId(new JSONObject(allocated.getResponseBody()));
                    entry = UploadTransactionJournal.allocated(db, entry, tid);
                } catch (JSONException | NullPointerException error) {
                    return fail(db, entry, allocated, "transaction_allocation_response_invalid", false, true, false);
                }
            }
            String item = collection + entry.transactionId;
            if (UploadTransactionJournal.ALLOCATED.equals(entry.phase)) {
                HttpResponse staged;
                try {
                    String body = new JSONArray().put(new JSONArray().put(0).put(new JSONObject(entry.operationJson))).toString();
                    staged = transport.request("PUT", item, body, account);
                } catch (IOException error) {
                    return fail(db, entry, null, "transaction_staging_response_missing", false, true, false);
                } catch (JSONException error) { return blocked("transaction_payload_invalid"); }
                if (!ok(staged)) return fail(db, entry, staged, "transaction_staging_failed", false, true, false);
                entry = UploadTransactionJournal.staged(db, entry);
            }
            if (!UploadTransactionJournal.STAGED.equals(entry.phase)) return blocked("transaction_phase_invalid");
            // Persist before commit: a later NotCommitted cannot disprove an external PostGIS commit.
            entry = UploadTransactionJournal.commitStarted(db, entry);
            try {
                HttpResponse committed = transport.request("POST", item, null, account);
                if (ok(committed)) {
                    try {
                        JSONObject body = new JSONObject(committed.getResponseBody());
                        if ("errors".equals(body.opt("status")) && validErrors(body.optJSONArray("errors")))
                            return fail(db, entry, committed, "transaction_rejected", false, false, true);
                        if (UploadTransactionJournal.validCommittedBody(committed.getResponseBody()))
                            entry = UploadTransactionJournal.observeCommitted(db, entry, committed.getResponseBody());
                    } catch (JSONException | NullPointerException ignored) { /* Resolve by the durable server result. */ }
                }
            } catch (IOException ignored) { /* GET is the only safe automatic recovery after commit started. */ }
            return results(db, entry, base, resourceId, account, transport);
        } finally { IN_FLIGHT.remove(key); }
    }

    private static Result results(SQLiteDatabase db, UploadTransactionJournal.Entry entry, String base,
            long resource, AccountUtil.AccountData account, Transport transport) {
        HttpResponse response;
        try { response = transport.request("GET", base + "/api/resource/" + resource
                + "/feature/transaction/" + entry.transactionId, null, account); }
        catch (IOException error) {
            return fail(db, entry, null, "transaction_result_response_missing", true, false, false);
        }
        if (!ok(response)) return fail(db, entry, response, "transaction_commit_outcome_unknown", true, false, false);
        try {
            JSONArray results = new JSONArray(response.getResponseBody());
            if (results.length() != 1) throw new JSONException("Unexpected transaction result count");
            JSONArray pair = results.getJSONArray(0);
            if (pair.length() != 2 || integral(pair.get(0)) != 0) throw new JSONException("Unexpected transaction sequence");
            JSONObject result = pair.getJSONObject(1);
            if (!"feature.create".equals(result.opt("action"))) throw new JSONException("Unexpected transaction action");
            long fid = integral(result.get("fid"));
            entry = UploadTransactionJournal.committed(db, entry, fid, results.toString());
            return new Result(true, false, false, false, entry.remoteId, response, "server_transaction_confirmed");
        } catch (JSONException | NullPointerException error) {
            return fail(db, entry, response, "transaction_result_invalid", true, false, false);
        }
    }

    private static long integral(Object value) throws JSONException {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0)
            throw new JSONException("Invalid transaction identifier");
        return ((Number) value).longValue();
    }

    private static boolean validErrors(JSONArray errors) {
        if (errors == null || errors.length() != 1) return false;
        try {
            JSONArray pair = errors.getJSONArray(0);
            JSONObject error = pair.getJSONObject(1);
            return pair.length() == 2 && integral(pair.get(0)) == 0
                    && error.opt("error") instanceof String && !error.getString("error").isEmpty();
        } catch (JSONException failure) { return false; }
    }

    private static Result fail(SQLiteDatabase db, UploadTransactionJournal.Entry entry, HttpResponse response,
            String reason, boolean uncertain, boolean retryable, boolean rejected) {
        UploadTransactionJournal.failure(db, entry, reason, response == null ? 0 : response.getResponseCode(), rejected);
        return new Result(false, uncertain, retryable, rejected, -1, response, reason);
    }

    private static String baseUrl(String url) throws URISyntaxException {
        URI uri = new URI(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new URISyntaxException("", "Invalid server URL");
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static final class PreparationHttp extends Exception {
        final HttpResponse response;
        PreparationHttp(HttpResponse response) { this.response = response; }
    }

    private static String prepare(String payload, UploadTransactionJournal.Entry entry, String base,
            AccountUtil.AccountData account, Transport transport) throws JSONException, ParseException, IOException, PreparationHttp {
        JSONObject source = new JSONObject(payload);
        for (Iterator<String> i = source.keys(); i.hasNext();) {
            String key = i.next();
            if (!"geom".equals(key) && !"fields".equals(key)) throw new JSONException("Unsupported feature payload member");
        }
        JSONObject operation = new JSONObject().put("action", "feature.create");
        if (source.has("fields")) {
            JSONObject fields = source.getJSONObject("fields"), types = new JSONObject(entry.fieldTypesJson);
            JSONObject values = new JSONObject();
            for (Iterator<String> i = fields.keys(); i.hasNext();) {
                String key = i.next();
                values.put(key, fieldValue(fields.get(key), types.getString(key)));
            }
            operation.put("fields", values);
        }
        if (source.has("geom")) {
            Object raw = source.get("geom");
            if (!(raw instanceof String)) throw new JSONException("Creation geometry must be WKT");
            String wkt = (String) raw;
            Geometry geometry = geometry(wkt);
            if (entry.resourceSrs != 3857) {
                HttpResponse transformed = transport.request("POST", base + "/api/component/spatial_ref_sys/"
                        + entry.resourceSrs + "/geom_transform", new JSONObject().put("srs", 3857).put("geom", wkt).toString(), account);
                if (!ok(transformed)) throw new PreparationHttp(transformed);
                try {
                    if (transformed.getResponseBody() == null) throw new JSONException("Missing transformed geometry");
                    Object converted = new JSONObject(transformed.getResponseBody()).get("geom");
                    if (!(converted instanceof String)) throw new JSONException("Invalid transformed geometry");
                    Geometry projected = geometry((String) converted);
                    if (!structure(geometry).equals(structure(projected)))
                        throw new IllegalArgumentException("Projection changed geometry structure");
                    geometry = projected;
                } catch (JSONException | ParseException | IllegalArgumentException error) {
                    // A bad response from this pure computation is safe to request again.
                    throw new PreparationHttp(transformed);
                }
            }
            byte[] wkb = new WKBWriter(2, ByteOrderValues.LITTLE_ENDIAN, false).write(geometry);
            operation.put("geom", Base64.encodeToString(wkb, Base64.NO_WRAP));
        }
        return operation.toString();
    }

    private static Geometry geometry(String wkt) throws ParseException {
        String input = wkt.trim();
        if (input.matches("(?is)^[A-Z]+\\s+(?:Z|M|ZM)\\s*\\(.*"))
            throw new IllegalArgumentException("Only 2D geometry is supported");
        int depth = 0, first = input.indexOf('(');
        if (first < 0 || !input.endsWith(")")) throw new IllegalArgumentException("Incomplete geometry");
        for (int i = first; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth < 0) throw new IllegalArgumentException("Unbalanced geometry");
            if (depth == 0 && i != input.length() - 1) throw new IllegalArgumentException("Trailing geometry data");
        }
        if (depth != 0) throw new IllegalArgumentException("Unbalanced geometry");
        Geometry geometry = new WKTReader().read(wkt);
        if (geometry.isEmpty()) throw new IllegalArgumentException("Empty creation geometry");
        for (Coordinate coordinate : geometry.getCoordinates()) {
            if (!Double.isFinite(coordinate.x) || !Double.isFinite(coordinate.y)
                    || !Double.isNaN(coordinate.getZ()) || !Double.isNaN(coordinate.getM()))
                throw new IllegalArgumentException("Only finite 2D coordinates are supported");
        }
        return geometry;
    }

    private static String structure(Geometry geometry) {
        StringBuilder result = new StringBuilder(geometry.getGeometryType()).append(':');
        if (geometry instanceof org.locationtech.jts.geom.Polygon) {
            org.locationtech.jts.geom.Polygon polygon = (org.locationtech.jts.geom.Polygon) geometry;
            result.append(structure(polygon.getExteriorRing()));
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) result.append('/').append(structure(polygon.getInteriorRingN(i)));
        } else if (geometry instanceof org.locationtech.jts.geom.GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) result.append('/').append(structure(geometry.getGeometryN(i)));
        } else result.append(geometry.getNumPoints());
        return result.toString();
    }

    private static Object fieldValue(Object value, String type) throws JSONException {
        if (value == JSONObject.NULL) return value;
        if ("DATE".equals(type) || "TIME".equals(type) || "DATETIME".equals(type)) return dateTime(value, type);
        if ("BOOLEAN".equals(type)) {
            if (value instanceof Boolean) return value;
            if ((value instanceof Integer || value instanceof Long) && (((Number) value).longValue() == 0 || ((Number) value).longValue() == 1))
                return ((Number) value).longValue() == 1;
            throw new JSONException("Invalid boolean field");
        }
        return value;
    }

    private static String dateTime(Object value, String type) throws JSONException {
        String text;
        if (value instanceof String) text = (String) value;
        else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String[] keys = "DATE".equals(type) ? new String[]{"year", "month", "day"}
                    : "TIME".equals(type) ? new String[]{"hour", "minute", "second"}
                    : new String[]{"year", "month", "day", "hour", "minute", "second"};
            if (object.length() != keys.length) throw new JSONException("Unexpected date members");
            int[] parts = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                long number = integral(object.get(keys[i]));
                if (number > 9999) throw new JSONException("Invalid date component");
                parts[i] = (int) number;
            }
            text = "DATE".equals(type) ? String.format(Locale.ROOT, "%04d-%02d-%02d", parts[0], parts[1], parts[2])
                    : "TIME".equals(type) ? String.format(Locale.ROOT, "%02d:%02d:%02d", parts[0], parts[1], parts[2])
                    : String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d", parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
        } else throw new JSONException("Invalid date field");
        String pattern = "DATE".equals(type) ? "\\d{4}-\\d{2}-\\d{2}"
                : "TIME".equals(type) ? "\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?"
                : "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?";
        if (!text.matches(pattern)) throw new JSONException("Invalid ISO date field");
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        calendar.setGregorianChange(new java.util.Date(Long.MIN_VALUE));
        calendar.setLenient(false); calendar.clear();
        try {
            int offset = "TIME".equals(type) ? 0 : 11;
            int year = "TIME".equals(type) ? 2000 : Integer.parseInt(text.substring(0, 4));
            if (year < 1) throw new IllegalArgumentException("Invalid year");
            calendar.set(year, "TIME".equals(type) ? 0 : Integer.parseInt(text.substring(5, 7)) - 1,
                    "TIME".equals(type) ? 1 : Integer.parseInt(text.substring(8, 10)),
                    "DATE".equals(type) ? 0 : Integer.parseInt(text.substring(offset, offset + 2)),
                    "DATE".equals(type) ? 0 : Integer.parseInt(text.substring(offset + 3, offset + 5)),
                    "DATE".equals(type) ? 0 : Integer.parseInt(text.substring(offset + 6, offset + 8)));
            calendar.getTimeInMillis();
        } catch (IllegalArgumentException failure) { throw new JSONException("Invalid ISO date components"); }
        return text;
    }
}
