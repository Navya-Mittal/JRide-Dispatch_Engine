package com.navya.dispatchengine.sim;

import com.navya.dispatchengine.engine.DispatchEngine;
import com.navya.dispatchengine.model.Driver;
import com.navya.dispatchengine.model.Location;
import com.navya.dispatchengine.model.RideRequest;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates synthetic driver and rider activity around Dublin, weighted
 * toward real landmarks so the demo output is grounded rather than
 * abstract lat/lon noise.
 */
public final class DispatchSimulator {

    private final DispatchEngine engine;
    private final String region;
    private final Random random;
    private final AtomicInteger driverCounter = new AtomicInteger();
    private final AtomicInteger riderCounter = new AtomicInteger();

    public DispatchSimulator(DispatchEngine engine, String region, long seed) {
        this.engine = engine;
        this.region = region;
        this.random = new Random(seed);
    }

    /** Puts {@code count} drivers online at randomized positions around Dublin. */
    public void seedDrivers(int count) {
        for (int i = 0; i < count; i++) {
            Driver driver = new Driver("driver-" + driverCounter.incrementAndGet(), randomDublinLocation());
            engine.driverOnline(region, driver).join();
        }
    }

    /** Submits {@code count} ride requests, pickup/dropoff biased toward real landmarks. */
    public CompletableFuture<?>[] requestRides(int count) {
        CompletableFuture<?>[] futures = new CompletableFuture<?>[count];
        for (int i = 0; i < count; i++) {
            Location pickup = randomLandmarkNearby();
            Location destination = randomLandmarkNearby();
            RideRequest request = new RideRequest("rider-" + riderCounter.incrementAndGet(), region, pickup, destination);
            futures[i] = engine.requestRide(request);
        }
        return futures;
    }

    /** A uniformly random point within Dublin's bounding box. */
    public Location randomDublinLocation() {
        double lat = DublinLocations.LAT_MIN + random.nextDouble() * (DublinLocations.LAT_MAX - DublinLocations.LAT_MIN);
        double lon = DublinLocations.LON_MIN + random.nextDouble() * (DublinLocations.LON_MAX - DublinLocations.LON_MIN);
        return new Location(lat, lon);
    }

    /** A random real landmark, jittered by up to ~300m so requests don't all land on the exact same point. */
    public Location randomLandmarkNearby() {
        Location landmark = DublinLocations.LANDMARKS[random.nextInt(DublinLocations.LANDMARKS.length)];
        double jitterLat = (random.nextDouble() - 0.5) * 0.006; // ~ +/-300m
        double jitterLon = (random.nextDouble() - 0.5) * 0.006;
        double lat = Math.max(-90, Math.min(90, landmark.lat() + jitterLat));
        double lon = Math.max(-180, Math.min(180, landmark.lon() + jitterLon));
        return new Location(lat, lon);
    }
}
