package com.navya.dispatchengine.engine;

import com.navya.dispatchengine.model.Driver;
import com.navya.dispatchengine.model.Location;
import com.navya.dispatchengine.model.RideMatch;
import com.navya.dispatchengine.model.RideRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DispatchEngineTest {

    private static final Location TRINITY = new Location(53.3438, -6.2546);
    private static final Location TEMPLE_BAR = new Location(53.3453, -6.2635);
    private static final Location LONDON_EYE = new Location(51.5033, -0.1195); // different city entirely

    private DispatchEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DispatchEngine();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void requestRideReturnsAFutureThatCompletes() throws Exception {
        DispatchResult result = engine.requestRide(new RideRequest("rider-1", "Dublin", TEMPLE_BAR, TRINITY)).get();
        assertNotNull(result);
        assertFalse(result.isMatched());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void regionsAreCompletelyIsolatedFromEachOther() throws Exception {
        engine.driverOnline("Dublin", new Driver("driver-1", TRINITY)).get();

        // a driver in Dublin should never match a rider in London, even at identical-looking coordinates logic
        DispatchResult londonResult = engine.requestRide(new RideRequest("rider-1", "London", LONDON_EYE, LONDON_EYE)).get();
        DispatchResult dublinResult = engine.requestRide(new RideRequest("rider-2", "Dublin", TEMPLE_BAR, TRINITY)).get();

        assertFalse(londonResult.isMatched(), "London has no drivers of its own");
        assertTrue(dublinResult.isMatched(), "Dublin's driver should still be found");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void matchListenersAreNotifiedForImmediateMatches() throws Exception {
        List<RideMatch> observed = new CopyOnWriteArrayList<>();
        engine.addMatchListener(observed::add);

        engine.driverOnline("Dublin", new Driver("driver-1", TRINITY)).get();
        engine.requestRide(new RideRequest("rider-1", "Dublin", TEMPLE_BAR, TRINITY)).get();

        assertEquals(1, observed.size());
        assertEquals("driver-1", observed.get(0).getDriverId());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void matchListenersAreNotifiedWhenAQueuedRequestMatchesLaterViaDriverOnline() throws Exception {
        List<RideMatch> observed = new CopyOnWriteArrayList<>();
        engine.addMatchListener(observed::add);

        engine.requestRide(new RideRequest("rider-1", "Dublin", TEMPLE_BAR, TRINITY)).get(); // no drivers yet, queues
        assertTrue(observed.isEmpty());

        engine.driverOnline("Dublin", new Driver("driver-1", TEMPLE_BAR)).get(); // should clear the queue
        assertEquals(1, observed.size(), "the listener must fire for matches made while draining the pending queue, not just immediate ones");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void cancelViaEngineRemovesThePendingRequest() throws Exception {
        RideRequest request = new RideRequest("rider-1", "Dublin", TEMPLE_BAR, TRINITY);
        engine.requestRide(request).get();

        boolean cancelled = engine.cancelRequest("Dublin", request.getRequestId()).get();
        assertTrue(cancelled);

        ZoneSnapshot snapshot = engine.snapshot("Dublin").get();
        assertEquals(0, snapshot.pendingRequests());
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void concurrentDriverOnlineAndRideRequestsFromManyThreadsMatchExactlyOnce() throws Exception {
        // Regression test for the single-writer design and the dual spatial index: hammer one
        // region with an equal number of drivers and requests from multiple threads at once.
        // Every driver should end up EN_ROUTE (i.e. matched) exactly once, and every request
        // should end up MATCHED exactly once - no driver double-booked, no request dropped.
        int threads = 8;
        int perThread = 100;
        String region = "StressCity";
        Location hub = new Location(53.35, -6.26);

        AtomicInteger driverCounter = new AtomicInteger();
        AtomicInteger riderCounter = new AtomicInteger();
        List<CompletableFuture<?>> futures = new CopyOnWriteArrayList<>();

        Runnable driverSpawner = () -> {
            for (int i = 0; i < perThread; i++) {
                Driver driver = new Driver("stress-driver-" + driverCounter.incrementAndGet(), hub);
                futures.add(engine.driverOnline(region, driver));
            }
        };
        Runnable riderSpawner = () -> {
            for (int i = 0; i < perThread; i++) {
                RideRequest request = new RideRequest("stress-rider-" + riderCounter.incrementAndGet(), region, hub, hub);
                futures.add(engine.requestRide(request));
            }
        };

        List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads / 2; t++) {
            workers.add(new Thread(driverSpawner));
            workers.add(new Thread(riderSpawner));
        }
        workers.forEach(Thread::start);
        for (Thread w : workers) w.join();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

        ZoneSnapshot snapshot = engine.snapshot(region).get();
        int totalDriversSpawned = (threads / 2) * perThread;
        int totalRidersSpawned = (threads / 2) * perThread;

        // equal supply and demand at the same point -> everyone should pair off, nothing left over
        assertEquals(0, snapshot.pendingRequests(), "no request should be left waiting when supply equals demand at the same location");
        assertEquals(totalDriversSpawned, snapshot.enRouteDrivers() + snapshot.availableDrivers(),
                "every spawned driver should be accounted for");
        assertEquals(totalDriversSpawned, snapshot.enRouteDrivers(), "every driver should have been matched since demand equals supply");
    }
}
