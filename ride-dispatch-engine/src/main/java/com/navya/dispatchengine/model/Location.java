package com.navya.dispatchengine.model;

/**
 * A latitude/longitude pair, with distance calculated via the haversine
 * formula (great-circle distance on a sphere — accurate enough for
 * city-scale ride dispatch; production systems at global scale account
 * for the earth's ellipsoid shape too, but haversine is the standard
 * choice for this scale).
 */
public record Location(double lat, double lon) {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    public Location {
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("lat must be in [-90, 90], got " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new IllegalArgumentException("lon must be in [-180, 180], got " + lon);
        }
    }

    public double distanceKmTo(Location other) {
        double lat1 = Math.toRadians(this.lat);
        double lat2 = Math.toRadians(other.lat);
        double dLat = Math.toRadians(other.lat - this.lat);
        double dLon = Math.toRadians(other.lon - this.lon);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
