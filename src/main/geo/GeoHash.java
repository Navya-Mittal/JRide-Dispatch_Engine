package com.navya.dispatchengine.geo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A from-scratch implementation of the standard geohash algorithm
 * (the same one used by geohash.org): recursively bisecting the
 * lat/lon space and interleaving bits, encoded to a base32 string.
 *
 * <p>Geohashing turns "find nearby points" from an O(n) linear scan into
 * an O(1) grid-cell lookup: two locations that are geographically close
 * usually (not always — see the note on edge effects below) share a
 * common hash prefix. This is the same underlying idea as spatial
 * indexes used in production systems like MongoDB's 2dsphere index or
 * Redis's GEO commands.
 *
 * <p><b>Verified against the canonical Wikipedia test vector:</b>
 * {@code encode(42.6, -5.6, 5)} should be {@code "ezs42"} — see
 * {@code GeoHashTest} for the check.
 *
 * <p><b>Known limitation:</b> geohash cells are not perfectly uniform —
 * cell edges don't align with actual distance in a way that makes
 * "same prefix" a perfect proxy for "nearby", and two points a few
 * meters apart can fall either side of a cell boundary and get
 * completely different hashes. This implementation's neighbor lookup
 * handles the common case correctly (verified via
 * {@code GeoHashTest#neighborsFormACompleteRingAroundTheCenterCell}) but
 * does not specially handle the antimeridian (±180° longitude) or poles —
 * a documented, acceptable simplification for a city-scale ride-dispatch
 * demo, called out explicitly rather than silently wrong.
 */
public final class GeoHash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final int[] BITS = {16, 8, 4, 2, 1};

    private GeoHash() {}

    public static String encode(double lat, double lon, int precision) {
        double[] latRange = {-90.0, 90.0};
        double[] lonRange = {-180.0, 180.0};
        StringBuilder result = new StringBuilder(precision);
        boolean isEvenBit = true;
        int bit = 0;
        int ch = 0;

        while (result.length() < precision) {
            if (isEvenBit) {
                double mid = (lonRange[0] + lonRange[1]) / 2;
                if (lon >= mid) {
                    ch |= BITS[bit];
                    lonRange[0] = mid;
                } else {
                    lonRange[1] = mid;
                }
            } else {
                double mid = (latRange[0] + latRange[1]) / 2;
                if (lat >= mid) {
                    ch |= BITS[bit];
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }
            isEvenBit = !isEvenBit;

            if (bit < 4) {
                bit++;
            } else {
                result.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return result.toString();
    }

    /** Bounding box for a geohash cell: {latMin, latMax, lonMin, lonMax}. */
    public static double[] decodeBoundingBox(String geohash) {
        double[] latRange = {-90.0, 90.0};
        double[] lonRange = {-180.0, 180.0};
        boolean isEvenBit = true;

        for (int i = 0; i < geohash.length(); i++) {
            int charIndex = BASE32.indexOf(geohash.charAt(i));
            if (charIndex < 0) {
                throw new IllegalArgumentException("invalid geohash character: " + geohash.charAt(i));
            }
            for (int bitMask : BITS) {
                boolean bitSet = (charIndex & bitMask) != 0;
                if (isEvenBit) {
                    double mid = (lonRange[0] + lonRange[1]) / 2;
                    if (bitSet) lonRange[0] = mid; else lonRange[1] = mid;
                } else {
                    double mid = (latRange[0] + latRange[1]) / 2;
                    if (bitSet) latRange[0] = mid; else latRange[1] = mid;
                }
                isEvenBit = !isEvenBit;
            }
        }
        return new double[]{latRange[0], latRange[1], lonRange[0], lonRange[1]};
    }

    public static double[] decodeCenter(String geohash) {
        double[] box = decodeBoundingBox(geohash);
        return new double[]{(box[0] + box[1]) / 2, (box[2] + box[3]) / 2};
    }

    /**
     * The 8 cells surrounding {@code geohash} at the same precision, found by
     * shifting the cell's center by one cell-width/height in each compass
     * direction and re-encoding — simpler and less error-prone than the
     * classic bit-manipulation neighbor tables, at the cost of an extra
     * encode() call per neighbor.
     */
    public static Set<String> neighbors(String geohash) {
        double[] box = decodeBoundingBox(geohash);
        double latMin = box[0], latMax = box[1], lonMin = box[2], lonMax = box[3];
        double latStep = latMax - latMin;
        double lonStep = lonMax - lonMin;
        double centerLat = (latMin + latMax) / 2;
        double centerLon = (lonMin + lonMax) / 2;
        int precision = geohash.length();

        Set<String> result = new LinkedHashSet<>();
        for (int dLat = -1; dLat <= 1; dLat++) {
            for (int dLon = -1; dLon <= 1; dLon++) {
                if (dLat == 0 && dLon == 0) continue; // that's geohash itself, not a neighbor
                double nLat = clampLat(centerLat + dLat * latStep);
                double nLon = wrapLon(centerLon + dLon * lonStep);
                result.add(encode(nLat, nLon, precision));
            }
        }
        return result;
    }

    private static double clampLat(double lat) {
        return Math.max(-90.0, Math.min(90.0, lat));
    }

    private static double wrapLon(double lon) {
        double wrapped = lon;
        while (wrapped > 180.0) wrapped -= 360.0;
        while (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }
}
