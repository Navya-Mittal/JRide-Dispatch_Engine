package com.navya.dispatchengine.engine;

import com.navya.dispatchengine.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DispatchZoneTest {

    private static final String REGION = "TestCity";
    // Dublin-ish coordinates, close enough together to land in the same or adjacent geohash cells.
    private static final Location TRINITY = new Location(53.3438, -6.2546);
    private static final Location GRAFTON = new Location(53.3419, -6.2625);
    private static final Location TEMPLE_BAR = new Location(53.3453, -6.2635);
    private static final Location DUBLIN_AIRPORT = new Location(53.4213, -6.2701); // far from the above

    private DispatchZone zone;

    @BeforeEach
    void setUp() {
        zone = new DispatchZone(REGION);
    }

    @Test
    void rideRequestWithNoOnlineDriversGoesPending() {
        RideRequest request = new RideRequest("rider-1", REGION, TEMPLE_BAR, TRINITY);
        DispatchResult result = zone.requestRide(request);

        assertFalse(result.isMatched());
        assertEquals(RequestStatus.PENDING, request.getStatus());
    }

    @Test
    void rideRequestMatchesNearbyOnlineDriver() {
        Driver driver = new Driver("driver-1", TRINITY);
        zone.driverOnline(driver);

        RideRequest request = new RideRequest("rider-1", REGION, TEMPLE_BAR, GRAFTON);
        DispatchResult result = zone.requestRide(request);

        assertTrue(result.isMatched());
        assertEquals("driver-1", result.getMatch().getDriverId());
        assertEquals(RequestStatus.MATCHED, request.getStatus());
        assertEquals(DriverStatus.EN_ROUTE, driver.getStatus());
    }

    @Test
    void matchedDriverIsNoLongerOfferedToTheNextRequest() {
        Driver driver = new Driver("driver-1", TRINITY);
        zone.driverOnline(driver);

        zone.requestRide(new RideRequest("rider-1", REGION, TEMPLE_BAR, GRAFTON));
        DispatchResult second = zone.requestRide(new RideRequest("rider-2", REGION, TEMPLE_BAR, GRAFTON));

        assertFalse(second.isMatched(), "the only driver is already EN_ROUTE, so the second request should queue");
    }

    @Test
    void nearestDriverIsChosenOverAFartherOne() {
        Driver near = new Driver("driver-near", GRAFTON);   // close to Temple Bar
        Driver far = new Driver("driver-far", DUBLIN_AIRPORT);
        zone.driverOnline(near);
        zone.driverOnline(far);

        DispatchResult result = zone.requestRide(new RideRequest("rider-1", REGION, TEMPLE_BAR, TRINITY));

        assertTrue(result.isMatched());
        assertEquals("driver-near", result.getMatch().getDriverId());
    }

    @Test
    void driverGoingOnlineMatchesTheOldestPendingRequestNearby() {
        RideRequest pending = new RideRequest("rider-1", REGION, DUBLIN_AIRPORT, TRINITY);
        DispatchResult initial = zone.requestRide(pending);
        assertFalse(initial.isMatched());

        Driver driver = new Driver("driver-1", DUBLIN_AIRPORT);
        var matches = zone.driverOnline(driver);

        assertEquals(1, matches.size());
        assertEquals("rider-1", matches.get(0).getRiderId());
        assertEquals(RequestStatus.MATCHED, pending.getStatus());
    }

    @Test
    void driverGoingOnlinePrefersTheOldestOfSeveralNearbyPendingRequests() {
        RideRequest first = new RideRequest("rider-1", REGION, DUBLIN_AIRPORT, TRINITY);
        RideRequest second = new RideRequest("rider-2", REGION, DUBLIN_AIRPORT, TRINITY);
        zone.requestRide(first);
        zone.requestRide(second);

        Driver driver = new Driver("driver-1", DUBLIN_AIRPORT);
        var matches = zone.driverOnline(driver);

        assertEquals(1, matches.size());
        assertEquals("rider-1", matches.get(0).getRiderId(), "the earlier-submitted request should win, not the second");
    }

    @Test
    void completingARideReturnsDriverToAvailablePoolAndCanImmediatelyRematch() {
        Driver driver = new Driver("driver-1", TRINITY);
        zone.driverOnline(driver);
        zone.requestRide(new RideRequest("rider-1", REGION, TEMPLE_BAR, GRAFTON));
        assertEquals(DriverStatus.EN_ROUTE, driver.getStatus());

        var matches = zone.completeRide("driver-1", GRAFTON);
        assertTrue(matches.isEmpty(), "no pending requests near Grafton Street, driver should just become available");
        assertEquals(DriverStatus.AVAILABLE, driver.getStatus());

        DispatchResult next = zone.requestRide(new RideRequest("rider-2", REGION, GRAFTON, TRINITY));
        assertTrue(next.isMatched());
        assertEquals("driver-1", next.getMatch().getDriverId());
    }

    @Test
    void driverGoingOfflineIsNoLongerMatchable() {
        Driver driver = new Driver("driver-1", TRINITY);
        zone.driverOnline(driver);
        zone.driverOffline("driver-1");

        DispatchResult result = zone.requestRide(new RideRequest("rider-1", REGION, TEMPLE_BAR, GRAFTON));
        assertFalse(result.isMatched());
    }

    @Test
    void driverLocationUpdateMovesThemInTheSpatialIndex() {
        Driver driver = new Driver("driver-1", DUBLIN_AIRPORT);
        zone.driverOnline(driver);
        zone.driverLocationUpdate("driver-1", TRINITY); // moves before ever being matched

        DispatchResult airportRequest = zone.requestRide(new RideRequest("rider-1", REGION, DUBLIN_AIRPORT, GRAFTON));
        assertFalse(airportRequest.isMatched(), "driver moved away from the airport, should no longer be found there");

        DispatchResult cityRequest = zone.requestRide(new RideRequest("rider-2", REGION, TEMPLE_BAR, GRAFTON));
        assertTrue(cityRequest.isMatched(), "driver should now be found near their new location");
        assertEquals("driver-1", cityRequest.getMatch().getDriverId());
    }

    @Test
    void cancelRemovesAPendingRequest() {
        RideRequest request = new RideRequest("rider-1", REGION, TEMPLE_BAR, TRINITY);
        zone.requestRide(request);

        assertTrue(zone.cancelRequest(request.getRequestId()));
        assertEquals(RequestStatus.CANCELLED, request.getStatus());

        // a driver arriving nearby afterward should find nothing to match
        Driver driver = new Driver("driver-1", TEMPLE_BAR);
        var matches = zone.driverOnline(driver);
        assertTrue(matches.isEmpty());
    }

    @Test
    void cancelOfUnknownRequestReturnsFalse() {
        assertFalse(zone.cancelRequest(999_999L));
    }

    @Test
    void cancelOfAlreadyMatchedRequestReturnsFalse() {
        Driver driver = new Driver("driver-1", TRINITY);
        zone.driverOnline(driver);
        RideRequest request = new RideRequest("rider-1", REGION, TEMPLE_BAR, GRAFTON);
        zone.requestRide(request);

        assertFalse(zone.cancelRequest(request.getRequestId()), "an already-matched request is no longer in the pending index");
    }

    @Test
    void snapshotReportsAccurateCounts() {
        zone.driverOnline(new Driver("driver-1", TRINITY));
        zone.driverOnline(new Driver("driver-2", GRAFTON));
        zone.requestRide(new RideRequest("rider-1", REGION, TEMPLE_BAR, GRAFTON)); // matches one driver
        zone.requestRide(new RideRequest("rider-2", REGION, DUBLIN_AIRPORT, TRINITY)); // stays pending

        ZoneSnapshot snapshot = zone.snapshot();
        assertEquals(2, snapshot.totalDrivers());
        assertEquals(1, snapshot.availableDrivers());
        assertEquals(1, snapshot.enRouteDrivers());
        assertEquals(1, snapshot.pendingRequests());
    }
}
