package com.navya.dispatchengine.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A confirmed rider-driver pairing. The dispatch equivalent of a
 * {@code Trade} in the order book analogy.
 */
public final class RideMatch {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);

    private final long matchId;
    private final long requestId;
    private final String riderId;
    private final String driverId;
    private final Location pickup;
    private final double driverDistanceKm;
    private final int ringsSearched;
    private final long timestampNanos;

    public RideMatch(long requestId, String riderId, String driverId, Location pickup,
                      double driverDistanceKm, int ringsSearched) {
        this.matchId = ID_GENERATOR.getAndIncrement();
        this.requestId = requestId;
        this.riderId = riderId;
        this.driverId = driverId;
        this.pickup = pickup;
        this.driverDistanceKm = driverDistanceKm;
        this.ringsSearched = ringsSearched;
        this.timestampNanos = System.nanoTime();
    }

    public long getMatchId() { return matchId; }
    public long getRequestId() { return requestId; }
    public String getRiderId() { return riderId; }
    public String getDriverId() { return driverId; }
    public Location getPickup() { return pickup; }
    public double getDriverDistanceKm() { return driverDistanceKm; }
    public int getRingsSearched() { return ringsSearched; }
    public long getTimestampNanos() { return timestampNanos; }

    @Override
    public String toString() {
        return String.format("RideMatch{id=%d, rider=%s, driver=%s, distance=%.2fkm, rings=%d}",
                matchId, riderId, driverId, driverDistanceKm, ringsSearched);
    }
}
