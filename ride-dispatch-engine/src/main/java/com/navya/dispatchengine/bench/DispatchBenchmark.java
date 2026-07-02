package com.navya.dispatchengine.bench;

import com.navya.dispatchengine.engine.DispatchEngine;
import com.navya.dispatchengine.engine.DispatchResult;
import com.navya.dispatchengine.model.RideRequest;
import com.navya.dispatchengine.sim.DispatchSimulator;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Measures {@link DispatchEngine} throughput and latency, same methodology
 * as {@code LatencyBenchmark} in the order matching engine project: latency
 * is measured one request at a time (round-trip through the engine),
 * throughput is measured with many requests in flight at once.
 */
public final class DispatchBenchmark {

    private static final String REGION = "Dublin";

    public static void main(String[] args) throws Exception {
        int driverCount = 2_000;
        int warmupRequests = 10_000;
        int latencySamples = 20_000;
        int throughputRequests = 100_000;

        DispatchEngine engine = new DispatchEngine();
        DispatchSimulator simulator = new DispatchSimulator(engine, REGION, 99);

        System.out.println("Seeding " + driverCount + " drivers across Dublin...");
        simulator.seedDrivers(driverCount);

        System.out.println("Warming up JIT with " + warmupRequests + " ride requests (drivers recycle after each ride)...");
        warmupWithRecycling(engine, simulator, warmupRequests);

        System.out.println("Measuring latency over " + latencySamples + " sequential ride requests...");
        long[] latenciesNanos = measureLatency(engine, simulator, latencySamples);
        printLatencyReport(latenciesNanos);

        System.out.println();
        System.out.println("Measuring throughput over " + throughputRequests + " fire-and-forget ride requests...");
        double requestsPerSecond = measureThroughput(engine, simulator, throughputRequests);
        System.out.printf("Throughput: %,.0f ride requests/sec%n", requestsPerSecond);

        engine.shutdown();
    }

    private static void warmupWithRecycling(DispatchEngine engine, DispatchSimulator simulator, int count) throws Exception {
        CompletableFuture<?>[] futures = new CompletableFuture<?>[count];
        for (int i = 0; i < count; i++) {
            RideRequest request = new RideRequest("warmup-rider-" + i, REGION,
                    simulator.randomLandmarkNearby(), simulator.randomLandmarkNearby());
            futures[i] = engine.requestRide(request).thenCompose(result -> result.isMatched()
                    ? engine.completeRide(REGION, result.getMatch().getDriverId(), simulator.randomLandmarkNearby())
                    : CompletableFuture.completedFuture(null));
        }
        CompletableFuture.allOf(futures).get();
    }

    private static long[] measureLatency(DispatchEngine engine, DispatchSimulator simulator, int samples) throws Exception {
        long[] latencies = new long[samples];
        for (int i = 0; i < samples; i++) {
            RideRequest request = new RideRequest("bench-rider-" + i, REGION,
                    simulator.randomLandmarkNearby(), simulator.randomLandmarkNearby());
            long start = System.nanoTime();
            DispatchResult result = engine.requestRide(request).get();
            if (result.isMatched()) {
                // recycle the driver immediately so the pool doesn't deplete over the run;
                // not counted in the timed window itself
                engine.completeRide(REGION, result.getMatch().getDriverId(), simulator.randomLandmarkNearby()).get();
            }
            latencies[i] = System.nanoTime() - start;
        }
        return latencies;
    }

    private static double measureThroughput(DispatchEngine engine, DispatchSimulator simulator, int count) throws Exception {
        // Steady-state model: as soon as a request matches, the driver "completes" the ride
        // almost immediately and returns to the available pool at a new random location -
        // the same way real drivers cycle between rides, rather than being consumed once and
        // never coming back. Without this, a fixed driver pool depletes over a long benchmark
        // run and later requests pay the cost of an exhausted search (expensive, unrepresentative
        // of steady-state load) rather than the cost of the matching algorithm itself.
        CompletableFuture<?>[] futures = new CompletableFuture<?>[count];
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            RideRequest request = new RideRequest("throughput-rider-" + i, REGION,
                    simulator.randomLandmarkNearby(), simulator.randomLandmarkNearby());
            futures[i] = engine.requestRide(request).thenCompose(result -> {
                if (result.isMatched()) {
                    return engine.completeRide(REGION, result.getMatch().getDriverId(), simulator.randomLandmarkNearby());
                }
                return CompletableFuture.completedFuture(null);
            });
        }
        CompletableFuture.allOf(futures).get();
        long elapsedNanos = System.nanoTime() - start;
        return count / (elapsedNanos / 1_000_000_000.0);
    }

    private static void printLatencyReport(long[] latenciesNanos) {
        long[] sorted = latenciesNanos.clone();
        Arrays.sort(sorted);
        System.out.println("Latency (round-trip through the engine, includes geohash ring search):");
        System.out.printf("  p50:  %,8.1f us%n", percentile(sorted, 50) / 1000.0);
        System.out.printf("  p90:  %,8.1f us%n", percentile(sorted, 90) / 1000.0);
        System.out.printf("  p99:  %,8.1f us%n", percentile(sorted, 99) / 1000.0);
        System.out.printf("  p99.9:%,8.1f us%n", percentile(sorted, 99.9) / 1000.0);
        System.out.printf("  max:  %,8.1f us%n", sorted[sorted.length - 1] / 1000.0);
    }

    private static long percentile(long[] sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.length) - 1;
        index = Math.max(0, Math.min(index, sortedValues.length - 1));
        return sortedValues[index];
    }
}
