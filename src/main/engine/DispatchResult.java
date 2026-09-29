package com.navya.dispatchengine.engine;

import com.navya.dispatchengine.model.RideMatch;
import com.navya.dispatchengine.model.RideRequest;

/**
 * The outcome of submitting a ride request: either an immediate
 * {@link RideMatch}, or {@code null} match meaning the request is now
 * waiting in the FIFO queue for the next available driver.
 */
public final class DispatchResult {

    private final RideRequest request;
    private final RideMatch match; // null if the request is now pending

    DispatchResult(RideRequest request, RideMatch match) {
        this.request = request;
        this.match = match;
    }

    public RideRequest getRequest() { return request; }
    public RideMatch getMatch() { return match; }
    public boolean isMatched() { return match != null; }
}
