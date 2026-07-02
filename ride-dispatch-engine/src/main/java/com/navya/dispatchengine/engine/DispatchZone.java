package com.navya.dispatchengine.engine;

import com.navya.dispatchengine.geo.GeoHash;
import com.navya.dispatchengine.model.*;

import java.util.*;

/**
 * Matches ride requests to drivers within a single region (e.g. one city),
 * using a geohash grid as a spatial index in <b>both directions</b>.
 *
 * <p><b>Data structure choice:</b> both available drivers and unmatched
 * pending requests are indexed by geohash cell
 * ({@code Map<String, LinkedHashSet<T>>}). A new ride request searches
 * outward from its pickup point for the nearest driver; a driver that
 * just became available searches outward from its own location for the
 * oldest pending request. Both are the same expanding-ring BFS pattern,
 * just run against a different index — O(1) average per cell checked
 * instead of a linear scan over every driver/request in the city.
 *
 * <p>This symmetry matters: an earlier version of this class kept pending
 * requests in a single FIFO queue and re-scanned the <i>entire</i> queue
 * every time a driver became available. Under sustained supply/demand
 * imbalance that queue can grow into the thousands, and rescanning all of
 * it on every single driver event is O(pending) per event — with enough
 * events that becomes effectively quadratic overall, which is exactly
 * what caused an early version of the benchmark to hang instead of
 * finishing. Indexing pending requests spatially too means a
 * driver-becomes-available event costs the same bounded ring search as a
 * ride request does, regardless of how large the backlog is.
 *
 * <p><b>Fairness:</b> within whichever ring first has any candidates, the
 * <i>oldest</i> (lowest sequence number) is preferred — the geospatial
 * analogue of price-time priority: reachability determines the candidate
 * set, arrival order breaks ties within it.
 *
 * <p><b>Thread-safety:</b> intentionally not thread-safe — this class is
 * designed to be owned by a single worker thread per region, the same
 * "single writer" principle used in {@code OrderBook} from the matching
 * engine project. See {@code DispatchEngine.RegionProcessor}.
 */
public final class DispatchZone {

    private static final int GEOHASH_PRECISION = 6; // ~1.2km x 0.6km cells at the equator
    private static final int MAX_SEARCH_RINGS = 6;

    private final String region;

    private final Map<String, LinkedHashSet<Driver>> driversByCell = new HashMap<>();
    private final Map<String, Driver> allDrivers = new HashMap<>();

    private final Map<String, LinkedHashSet<RideRequest>> pendingRequestsByCell = new HashMap<>();
    private final Map<Long, RideRequest> pendingRequestIndex = new HashMap<>();

    public DispatchZone(String region) {
        this.region = region;
    }

    public String getRegion() {
        return region;
    }

    // ------------------------------------------------------------------
    // Driver lifecycle
    // ------------------------------------------------------------------

    public List<RideMatch> driverOnline(Driver driver) {
        allDrivers.put(driver.getDriverId(), driver);
        return makeAvailable(driver, driver.getLocation());
    }

    public void driverOffline(String driverId) {
        Driver driver = allDrivers.get(driverId);
        if (driver == null) return;
        if (driver.getStatus() == DriverStatus.AVAILABLE) {
            removeFromDriverGrid(driver);
        }
        driver.setStatus(DriverStatus.OFFLINE);
    }

    public void driverLocationUpdate(String driverId, Location newLocation) {
        Driver driver = allDrivers.get(driverId);
        if (driver == null) return;
        if (driver.getStatus() == DriverStatus.AVAILABLE) {
            removeFromDriverGrid(driver);
            driver.setLocation(newLocation);
            addToDriverGrid(driver);
        } else {
            driver.setLocation(newLocation); // still track EN_ROUTE drivers' positions, just not in the index
        }
    }

    /** Called when a driver drops off their rider and becomes available again. */
    public List<RideMatch> completeRide(String driverId, Location finalLocation) {
        Driver driver = allDrivers.get(driverId);
        if (driver == null) return List.of();
        return makeAvailable(driver, finalLocation);
    }

    /**
     * A driver becomes available at {@code location}. First searches
     * outward from the driver for the oldest pending request within
     * range; if one exists, the driver goes straight to EN_ROUTE without
     * ever entering the available-driver index. Otherwise the driver
     * joins the index to await the next incoming request.
     */
    private List<RideMatch> makeAvailable(Driver driver, Location location) {
        driver.setLocation(location);
        PendingSearchResult found = findOldestPendingRequestNearby(location);
        if (found != null) {
            removeFromPendingIndex(found.request());
            RideMatch match = createMatch(found.request(), driver, found.distanceKm(), found.ringsSearched());
            return List.of(match);
        }
        driver.setStatus(DriverStatus.AVAILABLE);
        addToDriverGrid(driver);
        return List.of();
    }

    private void addToDriverGrid(Driver driver) {
        String cell = GeoHash.encode(driver.getLocation().lat(), driver.getLocation().lon(), GEOHASH_PRECISION);
        driversByCell.computeIfAbsent(cell, k -> new LinkedHashSet<>()).add(driver);
        driver.setCurrentGeohashCell(cell);
    }

    private void removeFromDriverGrid(Driver driver) {
        String cell = driver.getCurrentGeohashCell();
        if (cell == null) return;
        LinkedHashSet<Driver> cellDrivers = driversByCell.get(cell);
        if (cellDrivers != null) {
            cellDrivers.remove(driver);
            if (cellDrivers.isEmpty()) {
                driversByCell.remove(cell);
            }
        }
        driver.setCurrentGeohashCell(null);
    }

    // ------------------------------------------------------------------
    // Ride requests
    // ------------------------------------------------------------------

    public DispatchResult requestRide(RideRequest request) {
        RingSearchResult found = findNearestAvailableDriver(request.getPickup());
        if (found != null) {
            removeFromDriverGrid(found.driver());
            RideMatch match = createMatch(request, found.driver(), found.distanceKm(), found.ringsSearched());
            return new DispatchResult(request, match);
        }
        addToPendingIndex(request);
        return new DispatchResult(request, null);
    }

    public boolean cancelRequest(long requestId) {
        RideRequest request = pendingRequestIndex.get(requestId);
        if (request == null) {
            return false;
        }
        removeFromPendingIndex(request);
        request.markCancelled();
        return true;
    }

    private void addToPendingIndex(RideRequest request) {
        String cell = GeoHash.encode(request.getPickup().lat(), request.getPickup().lon(), GEOHASH_PRECISION);
        pendingRequestsByCell.computeIfAbsent(cell, k -> new LinkedHashSet<>()).add(request);
        pendingRequestIndex.put(request.getRequestId(), request);
    }

    private void removeFromPendingIndex(RideRequest request) {
        String cell = GeoHash.encode(request.getPickup().lat(), request.getPickup().lon(), GEOHASH_PRECISION);
        LinkedHashSet<RideRequest> cellRequests = pendingRequestsByCell.get(cell);
        if (cellRequests != null) {
            cellRequests.remove(request);
            if (cellRequests.isEmpty()) {
                pendingRequestsByCell.remove(cell);
            }
        }
        pendingRequestIndex.remove(request.getRequestId());
    }

    private RideMatch createMatch(RideRequest request, Driver driver, double distanceKm, int ringsSearched) {
        driver.setStatus(DriverStatus.EN_ROUTE);
        request.markMatched(driver.getDriverId());
        return new RideMatch(request.getRequestId(), request.getRiderId(), driver.getDriverId(),
                request.getPickup(), distanceKm, ringsSearched);
    }

    // ------------------------------------------------------------------
    // Expanding-ring BFS — one version per index, kept as separate,
    // straightforward methods rather than a shared generic abstraction
    // (a generic version was tried and made the ring/candidate logic
    // harder to follow for a modest amount of deduplication).
    // ------------------------------------------------------------------

    /** Nearest available driver within range of {@code pickup}. Stops at the first ring with any candidate. */
    private RingSearchResult findNearestAvailableDriver(Location pickup) {
        RingIterator rings = new RingIterator(pickup);
        while (rings.hasNext()) {
            RingCells ring = rings.next();
            List<Driver> candidates = new ArrayList<>();
            for (String cell : ring.cells()) {
                LinkedHashSet<Driver> inCell = driversByCell.get(cell);
                if (inCell != null) candidates.addAll(inCell);
            }
            if (candidates.isEmpty()) continue;

            Driver nearest = null;
            double bestDistance = Double.MAX_VALUE;
            for (Driver candidate : candidates) {
                double distance = candidate.getLocation().distanceKmTo(pickup);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = candidate;
                }
            }
            return new RingSearchResult(nearest, bestDistance, ring.ringNumber());
        }
        return null;
    }

    /** Oldest pending request within range of a driver's {@code location}. Stops at the first ring with any candidate. */
    private PendingSearchResult findOldestPendingRequestNearby(Location driverLocation) {
        RingIterator rings = new RingIterator(driverLocation);
        while (rings.hasNext()) {
            RingCells ring = rings.next();
            List<RideRequest> candidates = new ArrayList<>();
            for (String cell : ring.cells()) {
                LinkedHashSet<RideRequest> inCell = pendingRequestsByCell.get(cell);
                if (inCell != null) candidates.addAll(inCell);
            }
            if (candidates.isEmpty()) continue;

            RideRequest oldest = null;
            for (RideRequest candidate : candidates) {
                if (oldest == null || candidate.getSequence() < oldest.getSequence()) {
                    oldest = candidate;
                }
            }
            double distance = driverLocation.distanceKmTo(oldest.getPickup());
            return new PendingSearchResult(oldest, distance, ring.ringNumber());
        }
        return null;
    }

    /**
     * Generates geohash-cell rings outward from an origin point one at a
     * time, on demand, so a search can stop as soon as it finds a
     * non-empty ring without paying for cells further out that will
     * never be looked at — the common case, since most rides match
     * within the first ring or two. Shared by both search methods above
     * so the grid-walking logic itself doesn't need to know what's being
     * searched for.
     */
    private final class RingIterator implements Iterator<RingCells> {
        private final Set<String> visited = new HashSet<>();
        private List<String> currentRing;
        private int ringNumber = 0;

        RingIterator(Location origin) {
            String centerCell = GeoHash.encode(origin.lat(), origin.lon(), GEOHASH_PRECISION);
            visited.add(centerCell);
            currentRing = new ArrayList<>(List.of(centerCell));
        }

        @Override
        public boolean hasNext() {
            return ringNumber <= MAX_SEARCH_RINGS && !currentRing.isEmpty();
        }

        @Override
        public RingCells next() {
            RingCells result = new RingCells(currentRing, ringNumber);

            List<String> nextRing = new ArrayList<>();
            for (String cell : currentRing) {
                for (String neighbor : GeoHash.neighbors(cell)) {
                    if (visited.add(neighbor)) {
                        nextRing.add(neighbor);
                    }
                }
            }
            currentRing = nextRing;
            ringNumber++;
            return result;
        }
    }

    // ------------------------------------------------------------------

    public ZoneSnapshot snapshot() {
        int availableDrivers = (int) allDrivers.values().stream()
                .filter(d -> d.getStatus() == DriverStatus.AVAILABLE).count();
        int enRouteDrivers = (int) allDrivers.values().stream()
                .filter(d -> d.getStatus() == DriverStatus.EN_ROUTE).count();
        return new ZoneSnapshot(region, allDrivers.size(), availableDrivers, enRouteDrivers, pendingRequestIndex.size());
    }

    private record RingCells(List<String> cells, int ringNumber) {}
    private record RingSearchResult(Driver driver, double distanceKm, int ringsSearched) {}
    private record PendingSearchResult(RideRequest request, double distanceKm, int ringsSearched) {}
}
