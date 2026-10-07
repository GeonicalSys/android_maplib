package com.nextgis.maplib.scripts;

import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class ScriptPackageTest {
    private static final String MANIFEST = "{\"schema_version\":1,\"api_version\":1,\"id\":\"test\",\"version\":\"1\","
            + "\"capabilities\":[\"gis.query\"],\"layers\":{\"audits\":{\"read_fields\":[\"contractor\"]}},"
            + "\"hooks\":[{\"id\":\"check\",\"layer\":\"audits\",\"events\":[\"before_save\"],"
            + "\"fields\":[\"contractor\"],\"entry\":\"scripts/check.js\"}]}";
    private byte[] zip(String manifest, String path, byte[] script) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("manifest.json")); out.write(manifest.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            out.putNextEntry(new ZipEntry(path)); out.write(script); out.closeEntry();
        }
        return bytes.toByteArray();
    }
    private byte[] valid() throws Exception { return zip(MANIFEST, "scripts/check.js", "function run(ctx) {}".getBytes(StandardCharsets.UTF_8)); }
    @Test public void portableAliasesAreBoundOnlyByDeploymentReference() throws Exception {
        byte[] bytes = valid();
        ScriptPackage pack = ScriptPackage.read(bytes, ScriptPackage.sha256(bytes));
        ScriptReference ref = new ScriptReference(new JSONObject().put("schema_version",1).put("version","1")
                .put("sha256",pack.hash).put("resource_id",1).put("feature_id",1).put("attachment_id",1)
                .put("failure_policy","open").put("layer_bindings",new JSONObject().put("audits",807)));
        assertEquals(1, pack.hooks(ref,807,"before_save",null).size());
        assertTrue(pack.hooks(ref,809,"before_save",null).isEmpty());
        assertTrue(pack.canRead("audits","contractor"));
        assertFalse(pack.canRead("audits","password"));
    }
    @Test public void checksumMismatchCannotInstall() throws Exception {
        assertThrows(java.io.IOException.class, () -> ScriptPackage.read(valid(), "0".repeat(64)));
    }
    @Test public void zipTraversalIsRejectedWithoutExtraction() throws Exception {
        byte[] bytes=zip(MANIFEST,"scripts/../../outside.js", new byte[]{1});
        assertThrows(java.io.IOException.class, () -> ScriptPackage.read(bytes,ScriptPackage.sha256(bytes)));
    }
    @Test public void malformedUtf8IsRejected() throws Exception {
        byte[] bytes=zip(MANIFEST,"scripts/check.js",new byte[]{(byte)0xc3,0x28});
        assertThrows(java.io.IOException.class, () -> ScriptPackage.read(bytes,ScriptPackage.sha256(bytes)));
    }
    @Test public void unknownNativeCapabilityFailsBeforeExecution() throws Exception {
        byte[] bytes=zip(MANIFEST.replace("gis.query","layer.update"),"scripts/check.js",new byte[0]);
        assertThrows(org.json.JSONException.class, () -> ScriptPackage.read(bytes,ScriptPackage.sha256(bytes)));
    }
    @Test public void compressedBombIsRejected() throws Exception {
        byte[] bytes=zip(MANIFEST,"scripts/check.js",new byte[ScriptPackage.MAX_ENTRY+1]);
        assertThrows(java.io.IOException.class, () -> ScriptPackage.read(bytes,ScriptPackage.sha256(bytes)));
    }
    @Test public void foreignApiAndHooksAreRejected() throws Exception {
        byte[] bytes=zip(MANIFEST.replace("before_save","after_save"),"scripts/check.js",new byte[0]);
        assertThrows(org.json.JSONException.class, () -> ScriptPackage.read(bytes,ScriptPackage.sha256(bytes)));
    }
    @Test public void monthRangeClampsMonthEndAndIncludesToday() throws Exception {
        JSONObject range = ProjectScriptHost.monthWindow(java.time.LocalDate.of(2026,3,31));
        assertEquals("2026-02-28",range.getString("from"));
        assertEquals("2026-04-01",range.getString("until"));
        assertEquals("2024-02-29",ProjectScriptHost.monthWindow(java.time.LocalDate.of(2024,3,31)).getString("from"));
    }
    @Test public void rawTechnicalMessagesCannotReachUi() throws Exception {
        byte[] bad="{\"notices\":[{\"kind\":\"warning\",\"key\":\"test\",\"message\":\"https://private/path\"}]}".getBytes(StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> new ScriptRunResult(bad));
    }
}
