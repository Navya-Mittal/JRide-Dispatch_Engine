package com.navya.dispatchengine.sim;

import com.navya.dispatchengine.model.Location;

/** Real Dublin coordinates, used to make the demo and simulator feel grounded rather than abstract. */
public final class DublinLocations {

    private DublinLocations() {}

    public static final Location TRINITY_COLLEGE = new Location(53.3438, -6.2546);
    public static final Location GRAFTON_STREET = new Location(53.3419, -6.2625);
    public static final Location TEMPLE_BAR = new Location(53.3453, -6.2635);
    public static final Location O_CONNELL_STREET = new Location(53.3498, -6.2603);
    public static final Location ST_STEPHENS_GREEN = new Location(53.3382, -6.2591);
    public static final Location IFSC = new Location(53.3478, -6.2485);
    public static final Location POINT_VILLAGE = new Location(53.3474, -6.2288); // 3Arena / Canvas Point Campus area
    public static final Location CROKE_PARK = new Location(53.3607, -6.2512);
    public static final Location PHOENIX_PARK = new Location(53.3556, -6.3298);
    public static final Location DUBLIN_AIRPORT = new Location(53.4213, -6.2701);
    public static final Location DUN_LAOGHAIRE = new Location(53.2938, -6.1344);
    public static final Location BLANCHARDSTOWN = new Location(53.3856, -6.3808);

    public static final Location[] LANDMARKS = {
            TRINITY_COLLEGE, GRAFTON_STREET, TEMPLE_BAR, O_CONNELL_STREET, ST_STEPHENS_GREEN,
            IFSC, POINT_VILLAGE, CROKE_PARK, PHOENIX_PARK, DUBLIN_AIRPORT, DUN_LAOGHAIRE, BLANCHARDSTOWN
    };

    // Rough bounding box covering greater Dublin (city centre out to the airport and the M50 ring).
    public static final double LAT_MIN = 53.28;
    public static final double LAT_MAX = 53.43;
    public static final double LON_MIN = -6.44;
    public static final double LON_MAX = -6.10;
}
