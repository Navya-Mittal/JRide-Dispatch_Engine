package com.navya.dispatchengine.geo;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GeoHashTest {

    @Test
    void encodeMatchesTheCanonicalWikipediaTestVector() {
        // https://en.wikipedia.org/wiki/Geohash - the standard worked example for the algorithm.
        assertEquals("ezs42", GeoHash.encode(42.6, -5.6, 5));
    }

    @Test
    void encodeMatchesKnownSanFranciscoPrefix() {
        // "9q8yy" is San Francisco's well-known geohash prefix.
        String hash = GeoHash.encode(37.7749, -122.4194, 6);
        assertTrue(hash.startsWith("9q8yy"), "expected San Francisco hash to start with 9q8yy, got " + hash);
    }

    @Test
    void decodeBoundingBoxContainsTheOriginalPoint() {
        double lat = 53.3498, lon = -6.2603; // Dublin
        String hash = GeoHash.encode(lat, lon, 7);
        double[] box = GeoHash.decodeBoundingBox(hash);

        assertTrue(box[0] <= lat && lat <= box[1], "latitude should fall within the decoded bounding box");
        assertTrue(box[2] <= lon && lon <= box[3], "longitude should fall within the decoded bounding box");
    }

    @Test
    void higherPrecisionGivesATighterBoundingBox() {
        double lat = 53.3498, lon = -6.2603;
        double[] coarse = GeoHash.decodeBoundingBox(GeoHash.encode(lat, lon, 4));
        double[] fine = GeoHash.decodeBoundingBox(GeoHash.encode(lat, lon, 8));

        double coarseWidth = coarse[3] - coarse[2];
        double fineWidth = fine[3] - fine[2];
        assertTrue(fineWidth < coarseWidth, "more precision characters should mean a smaller cell");
    }

    @Test
    void neighborsFormACompleteRingAroundTheCenterCell() {
        String center = GeoHash.encode(53.3498, -6.2603, 6);
        Set<String> neighbors = GeoHash.neighbors(center);

        assertEquals(8, neighbors.size(), "a non-edge-case cell should have exactly 8 distinct neighbors");
        assertFalse(neighbors.contains(center), "a cell is not its own neighbor");
    }

    @Test
    void neighborsOfNeighborsIncludeCellsFurtherOut() {
        String center = GeoHash.encode(53.3498, -6.2603, 6);
        Set<String> ring1 = GeoHash.neighbors(center);

        boolean foundNewCellInRing2 = false;
        for (String cell : ring1) {
            for (String neighbor : GeoHash.neighbors(cell)) {
                if (!neighbor.equals(center) && !ring1.contains(neighbor)) {
                    foundNewCellInRing2 = true;
                    break;
                }
            }
        }
        assertTrue(foundNewCellInRing2, "expanding one more ring should reach cells not in ring 1");
    }

    @Test
    void pointsCloseTogetherShareACommonPrefixAtLowerPrecision() {
        // Two points ~200m apart in central Dublin should usually agree on a shorter hash prefix,
        // even if their full-precision hashes differ.
        String a = GeoHash.encode(53.3498, -6.2603, 5);
        String b = GeoHash.encode(53.3505, -6.2610, 5);
        assertEquals(a, b, "nearby points should usually fall in the same coarse-precision cell");
    }

    @Test
    void invalidCharacterInDecodeThrows() {
        assertThrows(IllegalArgumentException.class, () -> GeoHash.decodeBoundingBox("abc!"));
    }
}
