package com.example.womensafetyalertsystem;

/**
 * Live fall detector. Give it accelerometer readings; it tells you when a fall is detected.
 *
 * Rule (must match stream_events() in replay_50hz.py):
 *  - work on a steady 50 Hz stream in g;
 *  - look at the sample from 2 s ago (the "centre");
 *  - if it is >= triggerG, strictly larger than the 100 samples before it and at least as large as the
 *    99 samples after it, cut the 150-sample window around it (50 before, 100 from the centre on),
 *    compute the 19 features and ask the forest;
 *  - probability >= probabilityThreshold means "fall".
 * So a fall is reported about 2 seconds after the impact.
 */
public final class FallDetector {

    public interface Listener {
        /** Called for every impact-like moment that was checked, fall or not. centerIndex counts 50 Hz samples. */
        void onEvaluated(long centerIndex, double probability, boolean fall);
    }

    public static final double STANDARD_GRAVITY = 9.80665;       // m/s^2 per g
    private static final int NEIGHBOURHOOD = 100;                // 2 s each side
    private static final int BUFFER = 2 * NEIGHBOURHOOD;         // 200 samples = 4 s

    private final FallForest forest;
    private final double triggerG;
    private final double probabilityThreshold;
    private final Listener listener;
    private final AccelResampler resampler;

    private final double[][] ring = new double[BUFFER][3];
    private final double[] ringSvm = new double[BUFFER];
    private long count;                                          // 50 Hz samples since the last reset

    public FallDetector(FallForest forest, double triggerG, double probabilityThreshold, Listener listener) {
        this.forest = forest;
        this.triggerG = triggerG;
        this.probabilityThreshold = probabilityThreshold;
        this.listener = listener;
        this.resampler = new AccelResampler(new AccelResampler.Sink() {
            @Override
            public void onSample(double x, double y, double z) {
                addSample(x, y, z);
            }

            @Override
            public void onGap() {
                count = 0;                                       // data before the gap is not usable
            }
        });
    }

    /** For Android: call from onSensorChanged with SensorEvent.timestamp and the values in m/s^2. */
    public void onSensorEvent(long timestampNanos, float x, float y, float z) {
        resampler.push(timestampNanos, x / STANDARD_GRAVITY, y / STANDARD_GRAVITY, z / STANDARD_GRAVITY);
    }

    public void reset() {
        count = 0;
        resampler.reset();
    }

    /** One sample of a steady 50 Hz stream, in g. */
    public void addSample(double gx, double gy, double gz) {
        int pos = (int) (count % BUFFER);
        ring[pos][0] = gx;
        ring[pos][1] = gy;
        ring[pos][2] = gz;
        ringSvm[pos] = Math.sqrt(gx * gx + gy * gy + gz * gz);
        count++;
        if (count < BUFFER) {
            return;
        }

        long center = count - NEIGHBOURHOOD;                     // newest sample index is count - 1
        double v = ringSvm[(int) (center % BUFFER)];
        if (v < triggerG) {
            return;
        }
        for (long j = center - NEIGHBOURHOOD; j < center; j++) {
            if (ringSvm[(int) (j % BUFFER)] >= v) return;        // something at least as big just before
        }
        for (long j = center + 1; j < center + NEIGHBOURHOOD; j++) {
            if (ringSvm[(int) (j % BUFFER)] > v) return;         // something bigger just after
        }

        double[][] window = new double[FallFeatureExtractor.WINDOW][3];
        long start = center - FallFeatureExtractor.PRE;
        for (int i = 0; i < FallFeatureExtractor.WINDOW; i++) {
            double[] s = ring[(int) ((start + i) % BUFFER)];
            window[i][0] = s[0];
            window[i][1] = s[1];
            window[i][2] = s[2];
        }
        double probability = forest.predictProbability(FallFeatureExtractor.extract(window));
        listener.onEvaluated(center, probability, probability >= probabilityThreshold);
    }
}