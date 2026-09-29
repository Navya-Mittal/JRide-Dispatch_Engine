package com.navya.dispatchengine.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A rider's request for a ride. Mirrors {@code Order} in the trading
 * analogy: {@code sequence} plays the same role as an order's sequence
 * number, enforcing FIFO fairness among riders waiting in the same area
 * when no driver is immediately available.
 */
public final class RideRequest {

    private static final AtomicLong SEQUENCE_GENERATOR = new AtomicLong(0);
    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);

    private final long requestId;
    private final String riderId;
    private final String region;
    private final Location pickup;
    private final Location destination;
    private final long sequence;

    private RequestStatus status;
    private String matchedDriverId;

    public RideRequest(String riderId, String region, Location pickup, Location destination) {
        this.requestId = ID_GENERATOR.getAndIncrement();
        this.riderId = riderId;
        this.region = region;
        this.pickup = pickup;
        this.destination = destination;
        this.status = RequestStatus.PENDING;
        this.sequence = SEQUENCE_GENERATOR.getAndIncrement();
    }

    public long getRequestId() { return requestId; }
    public String getRiderId() { return riderId; }
    public String getRegion() { return region; }
    public Location getPickup() { return pickup; }
    public Location getDestination() { return destination; }
    public long getSequence() { return sequence; }
    public RequestStatus getStatus() { return status; }
    public String getMatchedDriverId() { return matchedDriverId; }

    public void markMatched(String driverId) {
        this.status = RequestStatus.MATCHED;
        this.matchedDriverId = driverId;
    }

    public void markCancelled() {
        this.status = RequestStatus.CANCELLED;
    }

    @Override
    public String toString() {
        return String.format("RideRequest{id=%d, rider=%s, region=%s, status=%s}",
                requestId, riderId, region, status);
    }
}
