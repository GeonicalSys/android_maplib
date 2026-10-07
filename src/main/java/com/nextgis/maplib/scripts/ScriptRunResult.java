package com.nextgis.maplib.scripts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Never trust the sandbox's output to be well-formed, bounded or safe to display. */
public final class ScriptRunResult {
    public static final class Notice {
        public final boolean blocking;
        public final String key, message;
        private Notice(boolean blocking, String key, String message) {
            this.blocking = blocking; this.key = key; this.message = message;
        }
    }
    public final List<Notice> notices;
    public ScriptRunResult(byte[] reply) throws IOException, JSONException {
        if (reply == null || reply.length > 128 * 1024) throw new IOException("Invalid script reply");
        JSONObject json = new JSONObject(ScriptPackage.utf8(reply));
        if (json.has("error")) throw new IOException("Script execution failed");
        JSONArray values = json.getJSONArray("notices");
        if (values.length() > 16) throw new IOException("Notice count");
        List<Notice> parsed = new ArrayList<>();
        for (int i=0; i<values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            String kind = value.getString("kind"), key = value.getString("key"), message = value.getString("message");
            if ((!kind.equals("warning") && !kind.equals("block")) || !key.matches("[A-Za-z0-9._:-]{1,128}")
                    || message.trim().isEmpty() || message.length() > 1000 || message.contains("://")
                    || message.matches("(?s).*<[^>]+>.*") || message.matches("(?s).*[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F].*"))
                throw new IOException("Invalid notice");
            parsed.add(new Notice(kind.equals("block"), key, message));
        }
        notices = Collections.unmodifiableList(parsed);
    }
}
