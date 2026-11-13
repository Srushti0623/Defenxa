package com.example.womensafetyalertsystem;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SafeRouteActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 400;

    private static final String USER_AGENT = "WomenSafetyAlertSystem/1.0 (college project; Android)";
    private static final String ORS_BASE_URL = "https://api.heigit.org/openrouteservice";
    private static final String ORS_PROFILE = "foot-walking"; // change to "driving-car" if needed

    // Public Overpass servers (the second one is a backup if the first is busy)
    private static final String[] OVERPASS_ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
    };

    // Safety scoring settings
    private static final String SAFE_AMENITY_REGEX = "^(police|hospital)$";
    private static final double SAFE_RADIUS_M = 300.0;   // "near" means within this distance
    private static final double SAMPLE_STEP_M = 50.0;    // check the route every 50 m
    private static final int MAX_POI_MARKERS = 80;

    private static final int COLOR_RECOMMENDED = 0xFF2E7D32; // green
    private static final int[] OTHER_ROUTE_COLORS = {0xFF1565C0, 0xFFEF6C00}; // blue, orange
    private static final String[] OTHER_ROUTE_COLOR_NAMES = {"blue", "orange"};
    private static final int COLOR_POLICE = 0xFF1565C0;
    private static final int COLOR_HOSPITAL = 0xFFC62828;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(50, TimeUnit.SECONDS)
            .build();

    private final List<Polyline> routeOverlays = new ArrayList<>();
    private final List<Marker> poiMarkers = new ArrayList<>();
    private Marker destinationMarker;

    private MapView mapView;
    private MyLocationNewOverlay myLocationOverlay;
    private EditText etDestination;
    private Button btnFindSafeRoute;
    private TextView tvRouteStatus;

    /** Everything we know about the routes returned by OpenRouteService. */
    private static class RouteResult {
        final List<List<GeoPoint>> routes;
        final List<Double> distances;
        final List<Double> durations;
        final GeoPoint destination;
        final String destinationName;

        RouteResult(List<List<GeoPoint>> routes, List<Double> distances,
                    List<Double> durations, GeoPoint destination, String destinationName) {
            this.routes = routes;
            this.distances = distances;
            this.durations = durations;
            this.destination = destination;
            this.destinationName = destinationName == null ? "" : destinationName;
        }
    }

    /** A police station or hospital from OpenStreetMap. */
    private static class SafePoint {
        final GeoPoint position;
        final String name;
        final String type;

        SafePoint(GeoPoint position, String name, String type) {
            this.position = position;
            this.name = name;
            this.type = type;
        }
    }

    /** Safety numbers for one route. */
    private static class RouteScore {
        double coveragePercent;      // % of the route within SAFE_RADIUS_M of a police station or hospital
        double longestGapMeters;     // longest stretch with none nearby
        final Set<Integer> nearbyPointIndexes = new HashSet<>();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // OpenStreetMap's tile servers block generic user agents such as the default
        // package name (com.example...). A descriptive, unique one is required.
        Configuration.getInstance().setUserAgentValue(USER_AGENT);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_safe_route);

        mapView = findViewById(R.id.mapView);
        etDestination = findViewById(R.id.etDestination);
        btnFindSafeRoute = findViewById(R.id.btnFindSafeRoute);
        tvRouteStatus = findViewById(R.id.tvRouteStatus);

        setupMap();
        requestLocationPermissionIfNeeded();

        btnFindSafeRoute.setOnClickListener(v -> onFindRouteClicked());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);

        // Placeholder center until a GPS fix arrives
        mapView.getController().setCenter(new GeoPoint(28.6139, 77.2090));

        myLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), mapView);
        myLocationOverlay.enableMyLocation();
        myLocationOverlay.runOnFirstFix(() -> runOnUiThread(() -> {
            GeoPoint currentLocation = myLocationOverlay.getMyLocation();
            if (currentLocation != null) {
                mapView.getController().animateTo(currentLocation);
                mapView.getController().setZoom(16.0);
            }
        }));
        mapView.getOverlays().add(myLocationOverlay);
    }

    // ---------------------------------------------------------------------
    // Step 1: validate input and find where we are starting from
    // ---------------------------------------------------------------------
    private void onFindRouteClicked() {
        String destination = etDestination.getText().toString().trim();
        if (destination.isEmpty()) {
            Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ApiConfig.ORS_API_KEY.startsWith("PASTE")) {
            tvRouteStatus.setText("Add your OpenRouteService key in ApiConfig.java first.");
            return;
        }
        GeoPoint start = myLocationOverlay.getMyLocation();
        if (start == null) {
            tvRouteStatus.setText("Waiting for your GPS location. Try again in a few seconds.");
            return;
        }

        btnFindSafeRoute.setEnabled(false);
        tvRouteStatus.setText("Searching for destination...");
        geocodeDestination(destination, start);
    }

    // ---------------------------------------------------------------------
    // Step 2: turn the typed place name into coordinates (OpenStreetMap Nominatim)
    // ---------------------------------------------------------------------
    private void geocodeDestination(String query, GeoPoint start) {
        // Prefer results near the user (a soft bias, not a hard limit)
        double d = 0.5; // about 55 km
        String viewbox = (start.getLongitude() - d) + "," + (start.getLatitude() + d) + ","
                + (start.getLongitude() + d) + "," + (start.getLatitude() - d);

        HttpUrl base = HttpUrl.parse("https://nominatim.openstreetmap.org/search");
        if (base == null) {
            showError("Internal error building the search URL.");
            return;
        }
        HttpUrl url = base.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", "1")
                .addQueryParameter("viewbox", viewbox)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                showError("Network error while searching for the destination.");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body;
                int code;
                try (Response r = response) {
                    code = r.code();
                    body = r.body() != null ? r.body().string() : "";
                }
                if (code != 200) {
                    showError("Destination search failed (HTTP " + code + ").");
                    return;
                }
                try {
                    JSONArray results = new JSONArray(body);
                    if (results.length() == 0) {
                        showError("Couldn't find that place. Try a more specific name, "
                                + "for example a railway station or hospital name.");
                        return;
                    }
                    JSONObject first = results.getJSONObject(0);
                    GeoPoint destination = new GeoPoint(
                            Double.parseDouble(first.getString("lat")),
                            Double.parseDouble(first.getString("lon")));
                    String destinationName = first.optString("display_name", "");
                    runOnUiThread(() -> tvRouteStatus.setText("Found destination. Calculating routes..."));
                    fetchRoutes(start, destination, destinationName, true);
                } catch (JSONException | NumberFormatException e) {
                    showError("Couldn't read the destination search result.");
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Step 3: ask OpenRouteService for walking routes (with alternatives)
    // ---------------------------------------------------------------------
    private void fetchRoutes(GeoPoint start, GeoPoint destination, String destinationName, boolean withAlternatives) {
        JSONObject payload = new JSONObject();
        try {
            JSONArray coordinates = new JSONArray();
            coordinates.put(new JSONArray().put(start.getLongitude()).put(start.getLatitude()));
            coordinates.put(new JSONArray().put(destination.getLongitude()).put(destination.getLatitude()));
            payload.put("coordinates", coordinates);

            if (withAlternatives) {
                JSONObject alternatives = new JSONObject();
                alternatives.put("target_count", 3);
                alternatives.put("weight_factor", 1.6);
                alternatives.put("share_factor", 0.6);
                payload.put("alternative_routes", alternatives);
            }
        } catch (JSONException e) {
            showError("Internal error building the route request.");
            return;
        }

        Request request = new Request.Builder()
                .url(ORS_BASE_URL + "/v2/directions/" + ORS_PROFILE + "/geojson")
                .header("Authorization", ApiConfig.ORS_API_KEY)
                .header("User-Agent", USER_AGENT)
                .post(RequestBody.create(payload.toString(),
                        MediaType.parse("application/json; charset=utf-8")))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                showError("Network error while fetching routes.");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body;
                int code;
                try (Response r = response) {
                    code = r.code();
                    body = r.body() != null ? r.body().string() : "";
                }

                if (code != 200) {
                    if (code == 400 && withAlternatives) {
                        // Alternatives not available for this trip; retry with a single route
                        fetchRoutes(start, destination, destinationName, false);
                        return;
                    }
                    String detail = extractApiError(body);
                    String hint = "";
                    if (code == 401 || code == 403) hint = " Check your API key.";
                    if (code == 429) hint = " Daily request limit reached.";
                    showError("Route request failed (HTTP " + code + ")."
                            + (detail.isEmpty() ? "" : " " + detail) + hint);
                    return;
                }

                try {
                    JSONArray features = new JSONObject(body).getJSONArray("features");
                    List<List<GeoPoint>> routes = new ArrayList<>();
                    List<Double> distances = new ArrayList<>();
                    List<Double> durations = new ArrayList<>();

                    for (int i = 0; i < features.length(); i++) {
                        JSONObject feature = features.getJSONObject(i);
                        JSONArray coords = feature.getJSONObject("geometry").getJSONArray("coordinates");
                        List<GeoPoint> points = new ArrayList<>();
                        for (int j = 0; j < coords.length(); j++) {
                            JSONArray p = coords.getJSONArray(j);
                            points.add(new GeoPoint(p.getDouble(1), p.getDouble(0)));
                        }
                        JSONObject summary = feature.getJSONObject("properties").getJSONObject("summary");
                        routes.add(points);
                        distances.add(summary.getDouble("distance"));
                        durations.add(summary.getDouble("duration"));
                    }

                    if (routes.isEmpty()) {
                        showError("No route was found to that destination.");
                        return;
                    }
                    fetchSafePointsAndScore(new RouteResult(routes, distances, durations, destination, destinationName));
                } catch (JSONException e) {
                    showError("Couldn't read the route response.");
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Step 4: find police stations and hospitals near the routes (Overpass)
    // ---------------------------------------------------------------------
    private void fetchSafePointsAndScore(RouteResult result) {
        runOnUiThread(() -> tvRouteStatus.setText("Checking police stations and hospitals near the routes..."));

        // Ask only for places within reach of the routes themselves. This keeps the request
        // small even for long routes, unlike one big rectangle around the whole trip.
        StringBuilder query = new StringBuilder("[out:json][timeout:40];(");
        for (List<GeoPoint> route : result.routes) {
            String around = buildAroundFilter(simplifyForQuery(route, 400.0));
            query.append("node[\"amenity\"~\"").append(SAFE_AMENITY_REGEX).append("\"](").append(around).append(");");
            query.append("way[\"amenity\"~\"").append(SAFE_AMENITY_REGEX).append("\"](").append(around).append(");");
        }
        query.append(");out center tags;");

        requestOverpass(query.toString(), 0, result, "");
    }

    /** Keeps roughly one point every minSpacingMeters so the query stays short. */
    private List<GeoPoint> simplifyForQuery(List<GeoPoint> route, double minSpacingMeters) {
        List<GeoPoint> out = new ArrayList<>();
        if (route.isEmpty()) {
            return out;
        }
        GeoPoint last = route.get(0);
        out.add(last);
        for (int i = 1; i < route.size() - 1; i++) {
            GeoPoint p = route.get(i);
            if (haversine(last, p) >= minSpacingMeters) {
                out.add(p);
                last = p;
            }
        }
        if (route.size() > 1) {
            out.add(route.get(route.size() - 1));
        }
        return out;
    }

    /** Builds "around:radius,lat,lon,lat,lon,..." which Overpass treats as a line. */
    private String buildAroundFilter(List<GeoPoint> points) {
        // A bit larger than SAFE_RADIUS_M so simplifying the line can't hide a nearby place
        StringBuilder sb = new StringBuilder("around:").append((int) (SAFE_RADIUS_M + 150));
        for (GeoPoint p : points) {
            sb.append(String.format(Locale.US, ",%.5f,%.5f", p.getLatitude(), p.getLongitude()));
        }
        return sb.toString();
    }

    private void requestOverpass(String query, int endpointIndex, RouteResult result, String failures) {
        Request request = new Request.Builder()
                .url(OVERPASS_ENDPOINTS[endpointIndex])
                .header("User-Agent", USER_AGENT)
                .post(new FormBody.Builder().add("data", query).build())
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                overpassFailed(query, endpointIndex, result, failures, "network error");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body;
                int code;
                try (Response r = response) {
                    code = r.code();
                    body = r.body() != null ? r.body().string() : "";
                }
                if (code != 200) {
                    overpassFailed(query, endpointIndex, result, failures, "HTTP " + code);
                    return;
                }

                try {
                    JSONObject json = new JSONObject(body);
                    String remark = json.optString("remark", "");
                    if (remark.toLowerCase(Locale.ROOT).contains("error")) {
                        overpassFailed(query, endpointIndex, result, failures, "server busy");
                        return;
                    }

                    JSONArray elements = json.getJSONArray("elements");
                    List<SafePoint> safePoints = new ArrayList<>();
                    for (int i = 0; i < elements.length(); i++) {
                        JSONObject el = elements.getJSONObject(i);
                        double lat;
                        double lon;
                        if (el.has("lat") && el.has("lon")) {
                            lat = el.getDouble("lat");
                            lon = el.getDouble("lon");
                        } else if (el.has("center")) {
                            JSONObject c = el.getJSONObject("center");
                            lat = c.getDouble("lat");
                            lon = c.getDouble("lon");
                        } else {
                            continue;
                        }
                        JSONObject tags = el.optJSONObject("tags");
                        String type = tags != null ? tags.optString("amenity", "") : "";
                        String name = tags != null ? tags.optString("name", "") : "";
                        safePoints.add(new SafePoint(new GeoPoint(lat, lon), name, type));
                    }

                    scoreAndShow(result, safePoints);
                } catch (JSONException e) {
                    overpassFailed(query, endpointIndex, result, failures, "unreadable response");
                }
            }
        });
    }

    private void overpassFailed(String query, int endpointIndex, RouteResult result,
                                String failures, String reason) {
        String allFailures = failures + (failures.isEmpty() ? "" : "; ")
                + "server " + (endpointIndex + 1) + ": " + reason;
        if (endpointIndex + 1 < OVERPASS_ENDPOINTS.length) {
            requestOverpass(query, endpointIndex + 1, result, allFailures); // try the backup server
        } else {
            runOnUiThread(() -> drawRoutes(result, null, null, -1,
                    "Couldn't load police/hospital data (" + allFailures + "). Showing routes without safety scores."));
        }
    }

    // ---------------------------------------------------------------------
    // Step 5: score every route (runs on the network thread, not the UI thread)
    // ---------------------------------------------------------------------
    private void scoreAndShow(RouteResult result, List<SafePoint> safePoints) {
        if (safePoints.isEmpty()) {
            runOnUiThread(() -> drawRoutes(result, null, null, -1,
                    "No police stations or hospitals are mapped in OpenStreetMap near these routes, "
                            + "so safety scoring isn't possible here."));
            return;
        }

        List<RouteScore> scores = new ArrayList<>();
        for (List<GeoPoint> route : result.routes) {
            scores.add(scoreRoute(route, safePoints));
        }

        int best = 0;
        for (int i = 1; i < scores.size(); i++) {
            if (isBetter(scores.get(i), result.distances.get(i), scores.get(best), result.distances.get(best))) {
                best = i;
            }
        }

        final int bestIndex = best;
        runOnUiThread(() -> drawRoutes(result, scores, safePoints, bestIndex, null));
    }

    /** Samples the route every SAMPLE_STEP_M and checks which samples have help nearby. */
    private RouteScore scoreRoute(List<GeoPoint> route, List<SafePoint> safePoints) {
        List<GeoPoint> samples = densify(route, SAMPLE_STEP_M);
        RouteScore score = new RouteScore();
        if (samples.isEmpty()) {
            return score;
        }

        int covered = 0;
        double currentGap = 0;
        GeoPoint previous = null;

        for (GeoPoint sample : samples) {
            boolean near = false;
            for (int i = 0; i < safePoints.size(); i++) {
                if (haversine(sample, safePoints.get(i).position) <= SAFE_RADIUS_M) {
                    near = true;
                    score.nearbyPointIndexes.add(i);
                }
            }
            if (near) {
                covered++;
                currentGap = 0;
            } else if (previous != null) {
                currentGap += haversine(previous, sample);
                score.longestGapMeters = Math.max(score.longestGapMeters, currentGap);
            }
            previous = sample;
        }

        score.coveragePercent = 100.0 * covered / samples.size();
        return score;
    }

    /** Higher coverage wins; then a shorter unprotected stretch; then the shorter route. */
    private boolean isBetter(RouteScore a, double distanceA, RouteScore b, double distanceB) {
        if (Math.abs(a.coveragePercent - b.coveragePercent) > 1.0) {
            return a.coveragePercent > b.coveragePercent;
        }
        if (Math.abs(a.longestGapMeters - b.longestGapMeters) > 50.0) {
            return a.longestGapMeters < b.longestGapMeters;
        }
        return distanceA < distanceB;
    }

    private List<GeoPoint> densify(List<GeoPoint> route, double stepMeters) {
        List<GeoPoint> out = new ArrayList<>();
        if (route.isEmpty()) {
            return out;
        }
        out.add(route.get(0));
        for (int i = 1; i < route.size(); i++) {
            GeoPoint a = route.get(i - 1);
            GeoPoint b = route.get(i);
            int pieces = (int) Math.ceil(haversine(a, b) / stepMeters);
            for (int k = 1; k <= pieces; k++) {
                double t = (double) k / pieces;
                out.add(new GeoPoint(
                        a.getLatitude() + (b.getLatitude() - a.getLatitude()) * t,
                        a.getLongitude() + (b.getLongitude() - a.getLongitude()) * t));
            }
        }
        return out;
    }

    private static double haversine(GeoPoint a, GeoPoint b) {
        double radius = 6371000.0;
        double dLat = Math.toRadians(b.getLatitude() - a.getLatitude());
        double dLon = Math.toRadians(b.getLongitude() - a.getLongitude());
        double lat1 = Math.toRadians(a.getLatitude());
        double lat2 = Math.toRadians(b.getLatitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * radius * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    // ---------------------------------------------------------------------
    // Step 6: draw routes, markers and the summary (UI thread)
    // scores == null means "show routes without safety scoring"
    // ---------------------------------------------------------------------
    private void drawRoutes(RouteResult result, List<RouteScore> scores,
                            List<SafePoint> safePoints, int bestIndex, String note) {
        for (Polyline old : routeOverlays) {
            mapView.getOverlays().remove(old);
        }
        routeOverlays.clear();
        for (Marker old : poiMarkers) {
            mapView.getOverlays().remove(old);
        }
        poiMarkers.clear();
        if (destinationMarker != null) {
            mapView.getOverlays().remove(destinationMarker);
        }

        int routeCount = result.routes.size();
        boolean scored = scores != null && bestIndex >= 0;
        boolean highlightBest = scored && routeCount > 1; // only meaningful with 2+ routes

        // Work out each route's colour and name
        int[] colors = new int[routeCount];
        String[] colorNames = new String[routeCount];
        int otherCounter = 0;
        for (int i = 0; i < routeCount; i++) {
            if (highlightBest && i == bestIndex) {
                colors[i] = COLOR_RECOMMENDED;
                colorNames[i] = "green";
            } else {
                colors[i] = OTHER_ROUTE_COLORS[otherCounter % OTHER_ROUTE_COLORS.length];
                colorNames[i] = OTHER_ROUTE_COLOR_NAMES[otherCounter % OTHER_ROUTE_COLOR_NAMES.length];
                otherCounter++;
            }
        }

        // Draw the recommended route first so the others sit underneath it
        List<GeoPoint> allPoints = new ArrayList<>();
        if (highlightBest) {
            addRouteLine(result.routes.get(bestIndex), colors[bestIndex], 16f);
        }
        for (int i = 0; i < routeCount; i++) {
            allPoints.addAll(result.routes.get(i));
            if (!(highlightBest && i == bestIndex)) {
                addRouteLine(result.routes.get(i), colors[i], 10f);
            }
        }

        // Police / hospital markers near any route
        if (scored) {
            Set<Integer> nearbyAll = new HashSet<>();
            for (RouteScore s : scores) {
                nearbyAll.addAll(s.nearbyPointIndexes);
            }
            int shown = 0;
            for (int index : nearbyAll) {
                if (shown >= MAX_POI_MARKERS) break;
                addSafePointMarker(safePoints.get(index));
                shown++;
            }
        }

        // Summary text
        StringBuilder status = new StringBuilder();
        if (!result.destinationName.isEmpty()) {
            status.append("Destination: ").append(result.destinationName).append("\n");
        }
        status.append("Found ").append(routeCount).append(routeCount == 1 ? " route:\n" : " routes:\n");
        for (int i = 0; i < routeCount; i++) {
            boolean isBest = highlightBest && i == bestIndex;
            status.append(isBest ? "\u2605 " : "").append("Route ").append(i + 1)
                    .append(" (").append(colorNames[i]).append(")")
                    .append(isBest ? " Recommended" : "")
                    .append(String.format(Locale.getDefault(), ": %.1f km, about %d min",
                            result.distances.get(i) / 1000.0, Math.round(result.durations.get(i) / 60.0)));
            if (scored) {
                RouteScore s = scores.get(i);
                int nearby = s.nearbyPointIndexes.size();
                status.append(String.format(Locale.getDefault(),
                        "\n   %.0f%% of route near help, longest gap %.0f m, %d %s nearby",
                        s.coveragePercent, s.longestGapMeters, nearby, nearby == 1 ? "point" : "points"));
            }
            status.append("\n");
        }
        if (scored) {
            status.append("Score = share of the route within ").append((int) SAFE_RADIUS_M)
                    .append(" m of a police station or hospital (OpenStreetMap data). ")
                    .append("Blue pins = police, red pins = hospitals.");
            if (scores.get(bestIndex).coveragePercent < 30.0) {
                status.append("\nLow coverage: few police stations or hospitals are mapped along ")
                        .append(routeCount == 1 ? "this route" : "these routes")
                        .append(", so the score is weak evidence.");
            }
            double longestRoute = 0;
            for (double meters : result.distances) {
                longestRoute = Math.max(longestRoute, meters);
            }
            if (longestRoute > 15000) {
                status.append("\nLong trip: over long distances little of any route stays within ")
                        .append((int) SAFE_RADIUS_M)
                        .append(" m of a police station or hospital, so treat the score as a rough guide.");
            }
        }
        if (note != null) {
            status.append(note);
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 20 || hour < 6) {
            status.append("\nNight-time caution: prefer busy, well-lit roads and share your live location.");
        }

        // Destination marker last so it stays on top
        destinationMarker = new Marker(mapView);
        destinationMarker.setPosition(result.destination);
        destinationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        destinationMarker.setTitle(result.destinationName.isEmpty() ? "Destination" : result.destinationName);
        mapView.getOverlays().add(destinationMarker);

        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(allPoints), true, 120);
        mapView.invalidate();

        tvRouteStatus.setText(status.toString());
        btnFindSafeRoute.setEnabled(true);
    }

    private void addRouteLine(List<GeoPoint> points, int color, float width) {
        Polyline line = new Polyline();
        line.setPoints(points);
        line.getOutlinePaint().setColor(color);
        line.getOutlinePaint().setStrokeWidth(width);
        mapView.getOverlays().add(0, line); // below the location dot
        routeOverlays.add(line);
    }

    private void addSafePointMarker(SafePoint point) {
        boolean isPolice = "police".equals(point.type);
        Marker marker = new Marker(mapView);
        marker.setPosition(point.position);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle((isPolice ? "Police station" : "Hospital")
                + (point.name.isEmpty() ? "" : ": " + point.name));

        // The default marker icon is shared by every marker on the map. Tinting it directly
        // would recolour the destination marker too, so tint a private copy instead.
        Drawable icon = marker.getIcon();
        if (icon != null && icon.getConstantState() != null) {
            Drawable copy = icon.getConstantState().newDrawable(getResources()).mutate();
            DrawableCompat.setTint(copy, isPolice ? COLOR_POLICE : COLOR_HOSPITAL);
            marker.setIcon(copy);
        }

        mapView.getOverlays().add(marker);
        poiMarkers.add(marker);
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            tvRouteStatus.setText(message);
            btnFindSafeRoute.setEnabled(true);
        });
    }

    private String extractApiError(String body) {
        try {
            Object err = new JSONObject(body).opt("error");
            if (err instanceof JSONObject) {
                return ((JSONObject) err).optString("message", "");
            }
            if (err != null) {
                return String.valueOf(err);
            }
        } catch (JSONException ignored) {
            // body was not JSON
        }
        return "";
    }

    // ---------------------------------------------------------------------
    // Location permission and map lifecycle
    // ---------------------------------------------------------------------
    private void requestLocationPermissionIfNeeded() {
        boolean fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!fineGranted && !coarseGranted) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean granted = false;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            if (granted) {
                myLocationOverlay.enableMyLocation();
            } else {
                Toast.makeText(this, "Location permission is needed to show your position on the map", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) {
            mapView.onPause();
        }
    }
}