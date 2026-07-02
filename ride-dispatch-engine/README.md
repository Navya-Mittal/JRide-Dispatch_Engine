# Ride Dispatch Engine — Dublin

A geospatial ride-matching engine, structurally modeled on the price-time
priority matching engine from my order-book project, but for a completely
different domain: matching riders to drivers by *location* instead of
orders by *price*. Built around real Dublin coordinates (Trinity College,
Grafton Street, Dublin Airport, and others).

Zero runtime dependencies — the engine, the REST gateway, and the
benchmark harness are all pure JDK 21. Only the test suite uses an
external library (JUnit 5).

## Why this project

This started as "build the same systems-engineering ideas from the order
matching engine, but in a non-finance domain." The interesting part turned
out to be geospatial indexing: instead of "walk price levels outward from
the best price until something matches," it's "walk grid cells outward
from a location until something matches." Same algorithmic shape,
different substrate — and the substrate change surfaced a real design bug
that the order book's structure had quietly protected against (see
"A design bug I found while benchmarking" below). That's the most
CV/interview-worthy part of this project: not just that it works, but the
story of finding and fixing a scalability flaw through benchmarking.

## Conceptual mapping to the order matching engine

| Order matching engine | This project |
|---|---|
| Price level (`TreeMap<price, PriceLevel>`) | Geohash grid cell (`Map<cell, ...>`) |
| Price-time priority | Nearest-match + time priority within a search ring |
| `OrderBook` per symbol | `DispatchZone` per region (city) |
| `MatchingEngine`, single writer per symbol | `DispatchEngine`, single writer per region |
| Order crosses the spread | Ride request finds a driver in range |
| `Trade` | `RideMatch` |
| REST: submit/cancel order | REST: request ride, driver online/offline/location |

## Architecture

```
Location (haversine distance) + GeoHash (from-scratch geohashing)
   |
   v
DispatchZone --- matches riders to drivers for ONE region
   |               Map<cell, drivers> AND Map<cell, pending requests> -
   |               indexed in BOTH directions (see below)
   |               expanding-ring BFS, lazy, stops at first non-empty ring
   v
DispatchEngine --- owns one DispatchZone per region
   |                 single-writer concurrency: each region gets its own
   |                 worker thread draining a task queue, so a zone never
   |                 needs locking
   v
DispatchHttpServer (REST) / DispatchSimulator (synthetic Dublin traffic) / DispatchBenchmark
```

### Key design decisions

**Geohashing implemented from scratch**, not pulled from a library —
recursive bisection of lat/lon space with interleaved bits, base32
encoded. Verified against the canonical Wikipedia test vector
(`encode(42.6, -5.6, 5) == "ezs42"`) and against San Francisco's
well-known real-world prefix (`9q8yy...`) — see `GeoHashTest`. Turns
"find nearby points" from an O(n) scan into an O(1)-per-cell grid lookup,
the same idea behind MongoDB's `2dsphere` index or Redis's `GEO` commands.

**Both drivers and pending requests are geospatially indexed** — not just
drivers. A new ride request searches outward from its pickup for the
nearest driver; a driver that just came online searches outward from
itself for the *oldest* pending request in range. See below for why this
symmetry isn't just a nice-to-have.

**Expanding-ring BFS is a lazy iterator**, not a precomputed list — it
generates one ring of grid cells at a time and both search methods stop
as soon as a ring has any candidates, so a request that matches
immediately (the common case) never pays for the cells further out that
it'll never look at.

**Single-writer-per-region concurrency**, mirroring the order book's
approach: each region gets its own worker thread and task queue
(`DispatchEngine.RegionProcessor`), callers get a `CompletableFuture` and
never block, and different regions are matched fully in parallel with no
locking needed within a region.

### A design bug I found while benchmarking

The first version of `DispatchZone` kept unmatched ride requests in a
single FIFO `Deque`, and whenever a driver became available it rescanned
the *entire* queue looking for something to match — a direct, literal
translation of "resting orders wait in time-priority order" from the
order book. That's fine when the queue stays short.

Running the benchmark exposed the problem: under sustained load with
imbalanced supply and demand, that queue grows into the thousands, and
rescanning all of it on *every single* driver-availability event is
O(pending) per event. With enough events, that's effectively quadratic —
and the benchmark run didn't finish in 60 seconds where the equivalent
order-book benchmark finishes in about 2.

The fix: index pending requests spatially too, the same way drivers are
indexed. A driver coming online runs the *same bounded ring search*
outward from itself, looking for the oldest pending request in range,
instead of scanning every pending request in the city. This bounds a
driver-availability event to the same cost as a ride request, regardless
of backlog size — and it's a more honest model of how dispatch actually
works: a driver shouldn't have to be compared against every waiting rider
in the city just because one came online.

A second, smaller version of the same mistake: the first "fix" attempt
precomputed *all* rings up to the search cap before ever checking ring 0
for candidates, defeating the entire point of "stop at the first ring
that has anything." Converting ring generation to a genuinely lazy
`Iterator` (see `RingIterator` in `DispatchZone`) fixed that.

Both bugs, and the fixes, are visible in the git history of this file if
you want to see the reasoning — or ask me about it directly, since it's
a good example of using a benchmark to catch an architectural problem
that unit tests alone wouldn't have surfaced (every individual test used
a small number of requests, well under where the quadratic behavior
becomes visible).

## Running it

```bash
mvn compile

# Scripted Dublin walkthrough: drivers come online near Trinity College and
# Grafton Street, a rider near Temple Bar gets matched, a rider at the
# airport gets queued (out of range), then matched once a driver arrives.
mvn exec:java -Dexec.mainClass=com.navya.dispatchengine.sim.Main
# or, after `mvn package`:
java -jar target/ride-dispatch-engine.jar

# REST API on :8080, pre-seeded with 150 drivers scattered across Dublin
java -jar target/ride-dispatch-engine.jar --server
```

With the server running:

```bash
curl "http://localhost:8080/zone/status?region=Dublin"

curl -X POST "http://localhost:8080/riders/request?region=Dublin&riderId=r1&pickupLat=53.3438&pickupLon=-6.2546&destLat=53.3498&destLon=-6.2603"

curl -X POST "http://localhost:8080/drivers/online?region=Dublin&driverId=d999&lat=53.3438&lon=-6.2546"
curl -X POST "http://localhost:8080/drivers/offline?region=Dublin&driverId=d999"
curl -X POST "http://localhost:8080/drivers/location?region=Dublin&driverId=d999&lat=53.35&lon=-6.27"
curl -X POST "http://localhost:8080/drivers/complete?region=Dublin&driverId=d999&lat=53.35&lon=-6.27"
```

### Tests

```bash
mvn test
```

27 JUnit 5 tests across `GeoHashTest` (encode/decode correctness against
known reference values, neighbor-ring correctness, edge cases),
`DispatchZoneTest` (matching correctness: nearest-driver selection,
oldest-pending-request fairness, driver lifecycle, cancellation,
snapshots), and `DispatchEngineTest` (async API, multi-region isolation,
match-listener notifications — including the queued-match case that an
earlier version of the code silently dropped — and a concurrency stress
test with 8 threads matching drivers against riders at the same
location). Every scenario in all three files was independently verified
logic-correct against the compiled engine before being written up as a
JUnit test (the same JUnit-can't-run-in-this-sandbox situation as the
order book project — see that project's README for why).

### Benchmark

```bash
mvn compile exec:java -Dexec.mainClass=com.navya.dispatchengine.bench.DispatchBenchmark
```

Representative results from this machine (yours will vary — report your
own numbers):

```
Latency (round-trip through the engine, includes geohash ring search):
  p50:     244.8 us
  p90:     277.6 us
  p99:     934.8 us
  p99.9: 1,459.7 us
  max:   4,181.3 us

Throughput: 3,801 ride requests/sec
```

**Worth explaining rather than hiding:** this is meaningfully slower than
the order book's ~313k orders/sec. Two honest reasons, not a regression:

1. Geospatial computation is inherently costlier per operation than a
   price comparison. A ride match involves string-based geohash encoding,
   neighbor-cell computation (itself several encode/decode calls), and a
   multi-ring BFS — versus a single `TreeMap` lookup for the order book's
   best price.
2. The throughput benchmark models steady state honestly: every matched
   ride is immediately followed by a `completeRide` call recycling the
   driver back to the pool (mirroring a driver actually finishing a trip
   and going available again), so it's measuring two engine round-trips
   per ride, not one. An earlier version of the benchmark that didn't do
   this got a bigger number by depleting the driver pool and never
   refilling it — a bigger number that meant less.

## What's deliberately out of scope

- **Route/ETA calculation** — matching uses straight-line (haversine)
  distance, not actual road network travel time. A real system would
  weight candidates by ETA along a road graph, not distance as the crow
  flies.
- **Driver acceptance/decline** — a match is final the moment it's made;
  no modeling of a driver rejecting a ride offer and the system falling
  back to the next candidate.
- **Surge pricing / dynamic search radius** — `MAX_SEARCH_RINGS` is a
  fixed constant. Real systems widen the search radius as wait time
  grows and use that as a pricing signal.
- **Persistence** — everything is in-memory, the right scope for a
  portfolio project, not for production.

## Possible extensions

- Weight driver selection by a combination of distance and driver rating
  or acceptance rate, not distance alone.
- Add a time-based search radius widening policy instead of a fixed ring
  cap, and expose average/p99 wait time per region as a metric.
- Port the benchmark to real JMH once there's normal internet access
  (this sandbox has no route to Maven Central — see the order book
  project's README for the same limitation there).
- Swap the hand-rolled `DispatchHttpServer` for a real framework and add
  WebSocket push of match events to riders/drivers instead of polling.
