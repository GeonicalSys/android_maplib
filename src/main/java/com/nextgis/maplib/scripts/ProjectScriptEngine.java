package com.nextgis.maplib.scripts;

/** JNI boundary. Only the isolated service loads the native interpreter. */
public final class ProjectScriptEngine {
    static { System.loadLibrary("lisa_project_scripts"); }
    private ProjectScriptEngine() { }
    public static native byte[] evaluate(byte[] source, byte[] input, IProjectScriptHost host);
}
