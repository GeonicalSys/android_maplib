# JNI names and the byte-array callback are part of the native ABI.
-keep class com.nextgis.maplib.scripts.ProjectScriptEngine { *; }
-keep interface com.nextgis.maplib.scripts.IProjectScriptHost { *; }
-keepclassmembers class * implements com.nextgis.maplib.scripts.IProjectScriptHost { public byte[] call(byte[]); }
