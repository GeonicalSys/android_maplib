package com.nextgis.maplib.map;

import org.junit.Test;
import static org.junit.Assert.*;

public class SqliteVectorTileRequestTest {
    @Test public void cacheIdentitySeparatesEveryMutableSourceContract() {
        SqliteVectorTileRequest base = request("owner",1,2,"style",null,3,1,2);
        for (SqliteVectorTileRequest other : new SqliteVectorTileRequest[]{request("other",1,2,"style",null,3,1,2),
                request("owner",2,2,"style",null,3,1,2),request("owner",1,3,"style",null,3,1,2),
                request("owner",1,2,"new-style",null,3,1,2),request("owner",1,2,"style",7L,3,1,2),
                request("owner",1,2,"style",null,3,2,2),request("owner",1,2,"style",null,3,1,3)})
            assertNotEquals(base.cacheKey(),other.cacheKey());
        assertNotEquals(base.cacheKey("processA"),base.cacheKey("processB"));
        assertNull(base.getExcludedEditedId()); assertEquals(3,base.getZ());
    }
    @Test public void malformedCanonicalTilesAreRejectedBeforeIoAndRetryWaitsAreBounded() {
        for (int[] xyz : new int[][]{{-1,0,0},{31,0,0},{0,1,0},{2,-1,0},{2,0,4}}) {
            try { request("owner",1,1,"style",null,xyz[0],xyz[1],xyz[2]); fail(); }
            catch (IllegalArgumentException expected) { }
        }
        assertEquals(100,new SqliteVectorTileBackend.RetryableTileException("pressure",-1).getRetryAfterMillis());
        assertEquals(2000,new SqliteVectorTileBackend.RetryableTileException("pressure",10000).getRetryAfterMillis());
    }
    static SqliteVectorTileRequest request(String owner,long source,long content,String style,Long exclude,int z,int x,int y) {
        return new SqliteVectorTileRequest(owner,source,content,style,exclude,z,x,y);
    }
}
