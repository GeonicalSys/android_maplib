package com.nextgis.maplib.util;

import org.junit.Test;
import java.io.*;
import java.util.Arrays;
import static org.junit.Assert.*;

public class NgwSyncResourceBudgetTest {
    private static final class Space extends File {
        long available;
        Space(long available) { super(System.getProperty("java.io.tmpdir")); this.available = available; }
        @Override public long getUsableSpace() { return available; }
    }
    @Test public void unknownSpaceAndExactReserveRefuseBeforeAnyWrite() throws Exception {
        for (long available : new long[]{0, 1, NgwSyncResourceBudget.DISK_RESERVE_BYTES}) {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            try (OutputStream out = NgwSyncResourceBudget.reserving(target, new Space(available))) {
                try { out.write(new byte[]{1,2,3}); fail("Must keep rollback reserve"); }
                catch (NgwSyncResourceBudget.LimitException expected) { }
            }
            assertEquals(0, target.size());
        }
    }
    @Test public void ongoingUnknownLengthOutputStopsWhenActualSpaceFalls() throws Exception {
        Space space = new Space(NgwSyncResourceBudget.DISK_RESERVE_BYTES + 65536);
        ByteArrayOutputStream target = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int count) {
                super.write(bytes, offset, count); space.available -= count;
            }
        };
        try (OutputStream out = NgwSyncResourceBudget.reserving(target, space)) {
            try { out.write(new byte[131072]); fail("Second chunk must not consume reserve"); }
            catch (NgwSyncResourceBudget.LimitException expected) { }
        }
        assertEquals(65536, target.size());
        assertEquals(NgwSyncResourceBudget.DISK_RESERVE_BYTES, space.available);
    }
    @Test public void capacityArithmeticCannotOverflowOnAnUntrustedLength() throws Exception {
        try { NgwSyncResourceBudget.requireSpace(new Space(Long.MAX_VALUE), Long.MAX_VALUE); fail(); }
        catch (NgwSyncResourceBudget.LimitException expected) { }
        NgwSyncResourceBudget.requireSpace(new Space(NgwSyncResourceBudget.DISK_RESERVE_BYTES + 3), 3);
    }
    @Test public void featureMemoryBudgetScalesConservativelyAndHasAnUpperBound() {
        assertEquals(1024*1024, NgwSyncResourceBudget.featureLimit(64L*1024*1024));
        assertEquals(8L*1024*1024, NgwSyncResourceBudget.featureLimit(1024L*1024*1024));
        assertEquals(8L*1024*1024, NgwSyncResourceBudget.featureLimit(Long.MAX_VALUE));
    }
    @Test public void aSingleReaderCannotAllocateAnUnboundedFeatureString() throws Exception {
        try (NgwFeatureBudgetReader reader = new NgwFeatureBudgetReader(new StringReader("x".repeat(4096)), 64)) {
            reader.beginFeature();
            try { while (reader.read(new char[32]) != -1) { } fail(); }
            catch (NgwSyncResourceBudget.LimitException expected) { }
        }
    }
    @Test public void separateFeaturesGetSeparateBudgetsWithoutCollectingTheResponse() throws Exception {
        String original = "abcdef".repeat(2000);
        StringBuilder result = new StringBuilder();
        try (NgwFeatureBudgetReader reader = new NgwFeatureBudgetReader(new StringReader(original), 8)) {
            char[] feature = new char[6];
            for (int i=0;i<2000;i++) {
                reader.beginFeature();
                assertEquals(6,reader.read(feature)); result.append(feature);
            }
        }
        assertEquals(original,result.toString());
    }
    @Test public void cancellationIsNotMisreportedAsStorageCapacity() throws Exception {
        try (NgwSyncIo.Session session = NgwSyncIo.beginSession()) {
            NgwSyncIo.requestCancellation();
            try { NgwSyncResourceBudget.requireSpace(new Space(Long.MAX_VALUE), 1); fail(); }
            catch (InterruptedIOException expected) { }
        }
    }
    @Test public void diagnosticErrorBodyIsBoundedAndClosed() throws Exception {
        final boolean[] closed={false};
        ByteArrayInputStream source=new ByteArrayInputStream(new byte[200000]) {
            @Override public void close() throws IOException { closed[0]=true;super.close(); }
        };
        assertEquals(65536,NgwSyncResourceBudget.readErrorText(source).length());
        assertEquals(200000-65536,source.available());
        assertTrue(closed[0]);
        assertNull(NgwSyncResourceBudget.readErrorText(null));
    }

}
