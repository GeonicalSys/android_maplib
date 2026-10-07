package com.nextgis.maplib.scripts;

import java.nio.charset.StandardCharsets;

/** API v1: synchronous, read-only host functions and explicit human-readable notices. */
public final class ScriptProgram {
    private ScriptProgram() { }
    public static byte[] wrap(String source) {
        return ("""
            (function () {
                "use strict";
                const input = JSON.parse(__input);
                const bridge = __hostCall;
                const notices = [];
                function call(method, args) {
                    const reply = JSON.parse(bridge(JSON.stringify({method, args})));
                    if (!reply.ok) throw new Error("Host request failed");
                    return reply.value;
                }
                function notice(kind, key, message) {
                    if (notices.length >= 16) throw new Error("Notice budget exceeded");
                    notices.push({kind, key, message});
                }
                const ctx = Object.freeze({
                    apiVersion: 1, event: input.event, changedField: input.changedField,
                    feature: Object.freeze({
                        id: input.id, isNew: input.isNew, layer: input.layer,
                        get: name => Object.prototype.hasOwnProperty.call(input.fields, name) ? input.fields[name] : null
                    }),
                    gis: Object.freeze({query: (layer, options) => call("gis.query", Object.assign({}, options, {layer}))}),
                    time: Object.freeze({monthWindow: () => call("time.monthWindow", {})}),
                    warn: (key, message) => notice("warning", key, message),
                    block: (key, message) => notice("block", key, message)
                });
            """ + source + "\n" + """
                if (typeof run !== "function") throw new Error("run(ctx) is required");
                const result = run(ctx);
                if (result && typeof result.then === "function") throw new Error("Async scripts are unsupported");
                return JSON.stringify({notices});
            })()
            """).getBytes(StandardCharsets.UTF_8);
    }
}
