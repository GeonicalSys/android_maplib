package com.nextgis.maplib.scripts;
import com.nextgis.maplib.scripts.IProjectScriptHost;
interface IProjectScriptService {
    byte[] execute(in byte[] source, in byte[] input, IProjectScriptHost host);
}
