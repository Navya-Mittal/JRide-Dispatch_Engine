package com.navya.dispatchengine.model;

public enum RequestStatus {
    PENDING,    // waiting in the FIFO queue, no driver found yet
    MATCHED,
    CANCELLED
}
