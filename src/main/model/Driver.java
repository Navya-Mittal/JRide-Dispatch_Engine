package com.navya.dispatchengine.model;

/**
 * A driver known to a {@link com.navya.dispatchengine.engine.DispatchZone}.
 * Mutable location and status by design — the zone updates these in place
 * as the driver moves and takes/completes rides, avoiding the churn of
 * re-inserting immutable objects into the spatial index on every GPS ping.
 */
public final class Driver {

    private final String driverId;
    private Location location;
    private DriverStatus status;
    private String currentGeohashCell; // cached so the zone can find+remove it from the index in O(1)

    public Driver(String driverId, Location location) {
        this.driverId = driverId;
        this.location = location;
        this.status = DriverStatus.OFFLINE;
    }

    public String getDriverId() { return driverId; }
    public Location getLocation() { return location; }
    public DriverStatus getStatus() { return status; }
    public String getCurrentGeohashCell() { return currentGeohashCell; }

    public void setLocation(Location location) { this.location = location; }
    public void setStatus(DriverStatus status) { this.status = status; }
    public void setCurrentGeohashCell(String cell) { this.currentGeohashCell = cell; }

    @Override
    public String toString() {
        return String.format("Driver{id=%s, status=%s, loc=(%.5f,%.5f)}",
                driverId, status, location.lat(), location.lon());
    }
}
