package com.navya.dispatchengine.engine;

import com.navya.dispatchengine.model.Driver;
import com.navya.dispatchengine.model.Location;
import com.navya.dispatchengine.model.RideMatch;
import com.navya.dispatchengine.model.RideRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Coordinates dispatch zones for many regions under concurrent access —
 * the direct structural counterpart of {@code MatchingEngine} from the
 * order matching engine project. Every region (e.g. a city) gets its own
 * dedicated worker thread and task queue ({@link RegionProcessor}), so a
 * given {@link DispatchZone} is only ever touched by one thread and needs
 * no internal locking, while different regions are matched fully in
 * parallel.
 */
public final class DispatchEngine {

    private final Map<String, RegionProcessor> processors = new ConcurrentHashMap<>();
    private final List<Consumer<RideMatch>> matchListeners = new CopyOnWriteArrayList<>();

    public CompletableFuture<DispatchResult> requestRide(RideRequest request) {
        return processorFor(request.getRegion()).enqueue(zone -> {
            DispatchResult result = zone.requestRide(request);
            if (result.isMatched()) {
                publishMatch(result.getMatch());
            }
            return result;
        });
    }

    public CompletableFuture<Boolean> cancelRequest(String region, long requestId) {
        return processorFor(region).enqueue(zone -> zone.cancelRequest(requestId));
    }

    public CompletableFuture<Void> driverOnline(String region, Driver driver) {
        return processorFor(region).enqueue(zone -> {
            List<RideMatch> matches = zone.driverOnline(driver);
            matches.forEach(this::publishMatch);
            return null;
        });
    }

    public CompletableFuture<Void> driverOffline(String region, String driverId) {
        return processorFor(region).enqueue(zone -> {
            zone.driverOffline(driverId);
            return null;
        });
    }

    public CompletableFuture<Void> driverLocationUpdate(String region, String driverId, Location location) {
        return processorFor(region).enqueue(zone -> {
            zone.driverLocationUpdate(driverId, location);
            return null;
        });
    }

    public CompletableFuture<Void> completeRide(String region, String driverId, Location finalLocation) {
        return processorFor(region).enqueue(zone -> {
            List<RideMatch> matches = zone.completeRide(driverId, finalLocation);
            matches.forEach(this::publishMatch);
            return null;
        });
    }

    public CompletableFuture<ZoneSnapshot> snapshot(String region) {
        return processorFor(region).enqueue(DispatchZone::snapshot);
    }

    public void addMatchListener(Consumer<RideMatch> listener) {
        matchListeners.add(listener);
    }

    private void publishMatch(RideMatch match) {
        for (Consumer<RideMatch> listener : matchListeners) {
            listener.accept(match);
        }
    }

    private RegionProcessor processorFor(String region) {
        return processors.computeIfAbsent(region, RegionProcessor::new);
    }

    public void shutdown() {
        processors.values().forEach(RegionProcessor::stop);
    }

    // ------------------------------------------------------------------

    static final class RegionProcessor {
        private final DispatchZone zone;
        private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        private final Thread worker;
        private volatile boolean running = true;

        RegionProcessor(String region) {
            this.zone = new DispatchZone(region);
            this.worker = new Thread(this::runLoop, "dispatch-engine-" + region);
            this.worker.setDaemon(true);
            this.worker.start();
        }

        <T> CompletableFuture<T> enqueue(Function<DispatchZone, T> task) {
            CompletableFuture<T> future = new CompletableFuture<>();
            queue.add(() -> {
                try {
                    future.complete(task.apply(zone));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            return future;
        }

        private void runLoop() {
            while (running) {
                try {
                    Runnable task = queue.take();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        void stop() {
            running = false;
            worker.interrupt();
        }
    }
}
