package com.navya.dispatchengine.engine;

/** A point-in-time view of a region's driver pool and queue, safe to read outside the zone's owning thread. */
public record ZoneSnapshot(String region, int totalDrivers, int availableDrivers,
                            int enRouteDrivers, int pendingRequests) {}
