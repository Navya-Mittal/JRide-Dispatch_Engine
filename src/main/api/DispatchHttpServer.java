package com.navya.dispatchengine.api;

import com.navya.dispatchengine.engine.DispatchEngine;
import com.navya.dispatchengine.engine.DispatchResult;
import com.navya.dispatchengine.engine.ZoneSnapshot;
import com.navya.dispatchengine.model.Driver;
import com.navya.dispatchengine.model.Location;
import com.navya.dispatchengine.model.RideMatch;
import com.navya.dispatchengine.model.RideRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

/**
 * A dependency-free REST gateway in front of a {@link DispatchEngine},
 * built on the JDK's {@code com.sun.net.httpserver.HttpServer} — same
 * approach as {@code OrderHttpServer} in the matching engine project, for
 * the same reason: no network access to Maven Central in this sandbox to
 * pull in a framework, and it keeps the wire protocol fully visible.
 *
 * <h2>Endpoints</h2>
 * <pre>
 * POST   /riders/request?region=Dublin&amp;riderId=r1&amp;pickupLat=53.34&amp;pickupLon=-6.26&amp;destLat=53.35&amp;destLon=-6.25
 * DELETE /riders/request?region=Dublin&amp;id=42
 * POST   /drivers/online?region=Dublin&amp;driverId=d1&amp;lat=53.34&amp;lon=-6.26
 * POST   /drivers/offline?region=Dublin&amp;driverId=d1
 * POST   /drivers/location?region=Dublin&amp;driverId=d1&amp;lat=53.35&amp;lon=-6.27
 * POST   /drivers/complete?region=Dublin&amp;driverId=d1&amp;lat=53.35&amp;lon=-6.27
 * GET    /zone/status?region=Dublin
 * GET    /health
 * </pre>
 */
public final class DispatchHttpServer {

    private final DispatchEngine engine;
    private final HttpServer server;

    public DispatchHttpServer(DispatchEngine engine, int port) throws IOException {
        this.engine = engine;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/riders/request", this::handleRiderRequest);
        server.createContext("/drivers/online", this::handleDriverOnline);
        server.createContext("/drivers/offline", this::handleDriverOffline);
        server.createContext("/drivers/location", this::handleDriverLocation);
        server.createContext("/drivers/complete", this::handleDriverComplete);
        server.createContext("/zone/status", this::handleZoneStatus);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }

    // ------------------------------------------------------------------

    private void handleRiderRequest(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            String method = exchange.getRequestMethod();

            if ("POST".equalsIgnoreCase(method)) {
                String region = require(params, "region");
                String riderId = require(params, "riderId");
                Location pickup = new Location(parseDouble(params, "pickupLat"), parseDouble(params, "pickupLon"));
                Location destination = new Location(parseDouble(params, "destLat"), parseDouble(params, "destLon"));

                RideRequest request = new RideRequest(riderId, region, pickup, destination);
                DispatchResult result = engine.requestRide(request).get();
                respond(exchange, 200, toJson(result));
            } else if ("DELETE".equalsIgnoreCase(method)) {
                String region = require(params, "region");
                long id = Long.parseLong(require(params, "id"));
                boolean cancelled = engine.cancelRequest(region, id).get();
                respond(exchange, 200, "{\"cancelled\":" + cancelled + "}");
            } else {
                respond(exchange, 405, error("method not allowed: " + method));
            }
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 500, error("internal error: " + e.getMessage()));
        }
    }

    private void handleDriverOnline(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            String region = require(params, "region");
            String driverId = require(params, "driverId");
            Location location = new Location(parseDouble(params, "lat"), parseDouble(params, "lon"));
            engine.driverOnline(region, new Driver(driverId, location)).get();
            respond(exchange, 200, "{\"status\":\"online\"}");
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 500, error("internal error: " + e.getMessage()));
        }
    }

    private void handleDriverOffline(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            String region = require(params, "region");
            String driverId = require(params, "driverId");
            engine.driverOffline(region, driverId).get();
            respond(exchange, 200, "{\"status\":\"offline\"}");
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 500, error("internal error: " + e.getMessage()));
        }
    }

    private void handleDriverLocation(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            String region = require(params, "region");
            String driverId = require(params, "driverId");
            Location location = new Location(parseDouble(params, "lat"), parseDouble(params, "lon"));
            engine.driverLocationUpdate(region, driverId, location).get();
            respond(exchange, 200, "{\"status\":\"updated\"}");
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 500, error("internal error: " + e.getMessage()));
        }
    }

    private void handleDriverComplete(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            String region = require(params, "region");
            String driverId = require(params, "driverId");
            Location location = new Location(parseDouble(params, "lat"), parseDouble(params, "lon"));
            engine.completeRide(region, driverId, location).get();
            respond(exchange, 200, "{\"status\":\"available\"}");
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 500, error("internal error: " + e.getMessage()));
        }
    }

    private void handleZoneStatus(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            String region = require(params, "region");
            ZoneSnapshot snapshot = engine.snapshot(region).get();
            respond(exchange, 200, toJson(snapshot));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (InterruptedException | ExecutionException e) {
            respond(exchange, 500, error("internal error: " + e.getMessage()));
        }
    }

    // --- tiny hand-rolled JSON rendering ---

    private String toJson(DispatchResult result) {
        RideRequest r = result.getRequest();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"requestId\":").append(r.getRequestId());
        sb.append(",\"status\":\"").append(r.getStatus()).append("\"");
        if (result.isMatched()) {
            RideMatch m = result.getMatch();
            sb.append(",\"match\":{");
            sb.append("\"driverId\":\"").append(m.getDriverId()).append("\",");
            sb.append("\"distanceKm\":").append(String.format("%.3f", m.getDriverDistanceKm())).append(",");
            sb.append("\"ringsSearched\":").append(m.getRingsSearched());
            sb.append("}");
        } else {
            sb.append(",\"match\":null");
        }
        sb.append("}");
        return sb.toString();
    }

    private String toJson(ZoneSnapshot snapshot) {
        return "{\"region\":\"" + snapshot.region() + "\","
                + "\"totalDrivers\":" + snapshot.totalDrivers() + ","
                + "\"availableDrivers\":" + snapshot.availableDrivers() + ","
                + "\"enRouteDrivers\":" + snapshot.enRouteDrivers() + ","
                + "\"pendingRequests\":" + snapshot.pendingRequests() + "}";
    }

    private String error(String message) {
        return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
    }

    // --- plumbing ---

    private String require(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required parameter: " + key);
        }
        return value;
    }

    private double parseDouble(Map<String, String> params, String key) {
        try {
            return Double.parseDouble(require(params, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid number for parameter: " + key);
        }
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) return result;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
