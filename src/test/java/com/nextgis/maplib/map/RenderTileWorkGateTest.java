package com.nextgis.maplib.map;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class RenderTileWorkGateTest {
    @Test public void canceledJobLeavesTheSharedAdmissionWaitWithoutNeedingThreadInterrupt() throws Exception {
        RenderTileWorkGate gate=new RenderTileWorkGate();
        java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);
        CountDownLatch canceled=new CountDownLatch(1);
        RenderTileWorkGate.Lease held=gate.acquire(true);
        Thread waiter=new Thread(()->{
            try(RenderTileWorkGate.Lease ignored=gate.acquire(false,current::get)){fail("Canceled job entered");}
            catch(java.util.concurrent.CancellationException expected){canceled.countDown();}
            catch(InterruptedException unexpected){Thread.currentThread().interrupt();}
        });waiter.setDaemon(true);waiter.start();
        try{current.set(false);assertTrue(canceled.await(1,TimeUnit.SECONDS));}
        finally{held.close();waiter.interrupt();waiter.join(2000);}
        normalDevicesKeepTwoConcurrentBuildsAndAThirdWaits(gate);
    }
    @Test(timeout=2000) public void cancelImmediatelyAfterAcquisitionReturnsEveryPermit() throws Exception {
        RenderTileWorkGate gate=new RenderTileWorkGate();java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        try(RenderTileWorkGate.Lease ignored=gate.acquire(false,()->checks.incrementAndGet()==1)){fail();}
        catch(java.util.concurrent.CancellationException expected){}
        try(RenderTileWorkGate.Lease ignored=gate.acquire(true)){assertEquals(2,checks.get());}
    }
    @Test(timeout=2000) public void throwingCurrentCallbackAfterAcquisitionAlsoReturnsEveryPermit() throws Exception {
        RenderTileWorkGate gate=new RenderTileWorkGate();java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        try(RenderTileWorkGate.Lease ignored=gate.acquire(false,()->{if(checks.incrementAndGet()>1)throw new IllegalStateException("Technical job failure");return true;})){fail();}
        catch(IllegalStateException expected){}
        try(RenderTileWorkGate.Lease ignored=gate.acquire(true)){assertEquals(2,checks.get());}
    }
    @Test public void normalDevicesKeepTwoConcurrentBuildsAndAThirdWaits() throws Exception {
        RenderTileWorkGate gate = new RenderTileWorkGate();
        RenderTileWorkGate.Lease first = gate.acquire(false), second = gate.acquire(false);
        CountDownLatch entered = new CountDownLatch(1);
        Thread third = acquire(gate, false, entered, null);
        try {
            assertFalse(entered.await(100, TimeUnit.MILLISECONDS));
            first.close(); assertTrue(entered.await(2, TimeUnit.SECONDS));
        } finally { first.close(); second.close(); third.interrupt(); third.join(2000); }
        assertFalse(third.isAlive());
    }

    @Test public void constrainedWorkIsExclusiveAndWaitingConstrainedWorkIsNotStarved() throws Exception {
        RenderTileWorkGate gate = new RenderTileWorkGate();
        RenderTileWorkGate.Lease running = gate.acquire(false);
        CountDownLatch constrainedEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch normalEntered = new CountDownLatch(1);
        Thread constrained = acquire(gate, true, constrainedEntered, release);
        Thread normal = null;
        try {
            awaitWaiting(constrained);
            normal = acquire(gate, false, normalEntered, null);
            assertFalse(constrainedEntered.await(100, TimeUnit.MILLISECONDS));
            assertFalse(normalEntered.await(100, TimeUnit.MILLISECONDS));
            running.close(); assertTrue(constrainedEntered.await(2, TimeUnit.SECONDS));
            assertFalse(normalEntered.await(100, TimeUnit.MILLISECONDS));
            release.countDown(); assertTrue(normalEntered.await(2, TimeUnit.SECONDS));
        } finally {
            running.close(); release.countDown(); constrained.interrupt();
            if (normal != null) normal.interrupt();
            constrained.join(2000); if (normal != null) normal.join(2000);
        }
        assertFalse(constrained.isAlive()); assertNotNull(normal); assertFalse(normal.isAlive());
    }

    @Test public void interruptedWaitAndFailedWorkDoNotLeakOrDuplicatePermits() throws Exception {
        RenderTileWorkGate gate = new RenderTileWorkGate();
        RenderTileWorkGate.Lease held = gate.acquire(true);
        CountDownLatch unexpected = new CountDownLatch(1);
        Thread waiting = acquire(gate, false, unexpected, null);
        try { awaitWaiting(waiting); }
        finally { waiting.interrupt(); waiting.join(2000); held.close(); }
        assertEquals(1, unexpected.getCount()); held.close(); held.close();
        try (RenderTileWorkGate.Lease work = gate.acquire(true)) {
            throw new IllegalStateException("Technical tile build failure");
        } catch (IllegalStateException expected) { }
        normalDevicesKeepTwoConcurrentBuildsAndAThirdWaits(gate);
    }

    private static void normalDevicesKeepTwoConcurrentBuildsAndAThirdWaits(RenderTileWorkGate gate) throws Exception {
        RenderTileWorkGate.Lease one = gate.acquire(false), two = gate.acquire(false);
        CountDownLatch entered = new CountDownLatch(1); Thread third = acquire(gate, false, entered, null);
        try { assertFalse(entered.await(100, TimeUnit.MILLISECONDS)); }
        finally { one.close(); two.close(); third.interrupt(); third.join(2000); }
    }
    private static Thread acquire(RenderTileWorkGate gate, boolean constrained, CountDownLatch entered, CountDownLatch release) {
        Thread thread = new Thread(() -> {
            try (RenderTileWorkGate.Lease work = gate.acquire(constrained)) {
                entered.countDown(); if (release != null) release.await();
            } catch (InterruptedException canceled) { Thread.currentThread().interrupt(); }
        });
        thread.setDaemon(true);
        thread.start(); return thread;
    }
    private static void awaitWaiting(Thread thread) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < until) Thread.sleep(1);
        assertEquals(Thread.State.WAITING, thread.getState());
    }
}
