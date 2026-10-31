package com.exam.service.attendance;

/**
 * Distances between two points given in degrees, and the classroom geofence rule.
 * <p>
 * A phone's "accuracy" is a radius it is about 68% sure of, and the room centre (the lecturer's
 * reading, or a saved room's measurement) has its own error. Two phones on the same desk can
 * report points 30-40 m apart indoors, so the rule asks whether the student <em>could</em> be in
 * the room given both errors, not whether they certainly are.
 */
public final class AttendanceGeo {

    private AttendanceGeo() {}

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /** Centre error assumed when it wasn't recorded (rooms saved, or classes started, before it was). */
    static final int DEFAULT_CENTRE_ACCURACY_METERS = 15;
    /** The centre's error counts at least this much (no reading is perfect) and at most this much (a rough centre mustn't widen the room without limit). */
    static final int MIN_CENTRE_ACCURACY_METERS = 5, MAX_CENTRE_ACCURACY_METERS = 50;
    /** "Could be in the room" stretches to three times the combined error, but never further than this past the edge… */
    static final int MAX_UNCLEAR_MARGIN_METERS = 300;
    /** …and always at least this far (indoors a phone can be confidently wrong by a few tens of metres). */
    static final int MIN_UNCLEAR_MARGIN_METERS = 25;

    /** Great-circle (haversine) distance in metres. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public static boolean validPoint(Double lat, Double lon) {
        return lat != null && lon != null && Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !(lat == 0 && lon == 0);
    }

    /**
     * INSIDE: in the room, allowing for both errors. UNCLEAR: the reading is too rough, or just
     * outside, but still fits someone in the room (the lecturer decides). OUTSIDE: clearly elsewhere.
     */
    public enum Fit { INSIDE, UNCLEAR, OUTSIDE }

    /** {@code distance} and {@code accuracy} are whole metres; {@code combined} is both errors together. */
    public record Verdict(Fit fit, int distance, int accuracy, int combined) {}

    /**
     * The geofence rule. INSIDE when the reading is precise enough ({@code maxAccuracy}) and
     * the distance is within the radius plus the combined error (that allowance capped at the radius).
     * UNCLEAR when it isn't, but the distance is within the radius plus three times the combined
     * error (at least {@link #MIN_UNCLEAR_MARGIN_METERS}, at most {@link #MAX_UNCLEAR_MARGIN_METERS}).
     */
    public static Verdict judge(double centreLat, double centreLng, Integer centreAccuracy, int radius, int maxAccuracy,
                                double lat, double lng, int accuracy) {
        int distance = (int) Math.round(distanceMeters(centreLat, centreLng, lat, lng));
        return judge(distance, centreAccuracy, radius, maxAccuracy, accuracy);
    }

    static Verdict judge(int distance, Integer centreAccuracy, int radius, int maxAccuracy, int accuracy) {
        int centre = centreAccuracy == null ? DEFAULT_CENTRE_ACCURACY_METERS
                : Math.max(MIN_CENTRE_ACCURACY_METERS, Math.min(MAX_CENTRE_ACCURACY_METERS, centreAccuracy));
        double combined = Math.sqrt((double) accuracy * accuracy + (double) centre * centre);
        int c = (int) Math.ceil(combined);
        if (accuracy <= maxAccuracy && distance <= radius + Math.min(combined, radius))
            return new Verdict(Fit.INSIDE, distance, accuracy, c);
        double margin = Math.max(MIN_UNCLEAR_MARGIN_METERS, Math.min(3 * combined, MAX_UNCLEAR_MARGIN_METERS));
        return new Verdict(distance <= radius + margin ? Fit.UNCLEAR : Fit.OUTSIDE, distance, accuracy, c);
    }
}
