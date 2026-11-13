package com.example.womensafetyalertsystem;

/**
 * Computes the 19 fall-detection features from one 3-second window of accelerometer data.
 *
 * Input: window[150][3], readings in g (1 g = 9.80665 m/s^2), sampled at 50 Hz,
 *        with the acceleration peak at index 50 (1 s before it, 2 s after it).
 *
 * This must stay numerically identical to window_features() in retrain_50hz.py.
 * Order of the returned values = FEATURE_NAMES = the order the forest was trained with.
 */
public final class FallFeatureExtractor {

    public static final int SAMPLE_RATE_HZ = 50;
    public static final int PRE = 50;          // samples before the peak (1 s)
    public static final int POST = 100;        // samples from the peak onwards (2 s)
    public static final int WINDOW = PRE + POST;
    private static final int HALF = 25;        // 0.5 s
    private static final int ONE = 50;         // 1.0 s

    public static final String[] FEATURE_NAMES = {
            "svm_peak", "svm_range", "svm_min_pre", "svm_mean_pre_far", "svm_std_pre",
            "svm_mean_impact", "svm_std_impact", "svm_mean_post_mid", "svm_mean_post_far",
            "svm_std_post_far", "svm_dev_post_far", "jerk_max", "energy", "secs_above_1_5g",
            "peak_over_pre", "dip_to_peak_s", "skew", "kurt", "orient_change_deg"
    };

    private FallFeatureExtractor() {
    }

    public static double[] extract(double[][] w) {
        if (w == null || w.length != WINDOW) {
            throw new IllegalArgumentException("Window must contain exactly " + WINDOW + " samples");
        }
        double[] svm = new double[WINDOW];
        for (int i = 0; i < WINDOW; i++) {
            svm[i] = Math.sqrt(w[i][0] * w[i][0] + w[i][1] * w[i][1] + w[i][2] * w[i][2]);
        }

        double max = max(svm, 0, WINDOW);
        double min = min(svm, 0, WINDOW);
        double meanPreFar = mean(svm, 0, HALF);

        double maxJump = 0;
        for (int i = 1; i < WINDOW; i++) {
            maxJump = Math.max(maxJump, Math.abs(svm[i] - svm[i - 1]));
        }

        double energy = 0;
        int above = 0;
        for (double v : svm) {
            energy += v * v;
            if (v > 1.5) above++;
        }
        energy /= WINDOW;

        double devPost = 0;
        for (int i = PRE + ONE; i < WINDOW; i++) {
            devPost += Math.abs(svm[i] - 1.0);
        }
        devPost /= (WINDOW - PRE - ONE);

        int argMin = 0;                                  // first minimum, like numpy.argmin
        for (int i = 1; i < PRE; i++) {
            if (svm[i] < svm[argMin]) argMin = i;
        }

        double meanAll = mean(svm, 0, WINDOW);
        double m2 = 0, m3 = 0, m4 = 0;
        for (double v : svm) {
            double d = v - meanAll;
            m2 += d * d;
            m3 += d * d * d;
            m4 += d * d * d * d;
        }
        m2 /= WINDOW;
        m3 /= WINDOW;
        m4 /= WINDOW;
        boolean constant = Math.sqrt(m2) <= 1e-9;
        double skew = constant ? 0.0 : m3 / Math.pow(m2, 1.5);
        double kurt = constant ? 0.0 : m4 / (m2 * m2) - 3.0;

        double[] f = new double[FEATURE_NAMES.length];
        f[0] = max;
        f[1] = max - min;
        f[2] = min(svm, 0, PRE);
        f[3] = meanPreFar;
        f[4] = std(svm, 0, PRE);
        f[5] = mean(svm, PRE, PRE + HALF);
        f[6] = std(svm, PRE, PRE + HALF);
        f[7] = mean(svm, PRE + HALF, PRE + ONE);
        f[8] = mean(svm, PRE + ONE, WINDOW);
        f[9] = std(svm, PRE + ONE, WINDOW);
        f[10] = devPost;
        f[11] = maxJump * SAMPLE_RATE_HZ;
        f[12] = energy;
        f[13] = (double) above / SAMPLE_RATE_HZ;
        f[14] = max / Math.max(meanPreFar, 0.05);
        f[15] = (double) (PRE - argMin) / SAMPLE_RATE_HZ;
        f[16] = skew;
        f[17] = kurt;
        f[18] = orientationChangeDegrees(w);
        for (int i = 0; i < f.length; i++) {
            if (Double.isNaN(f[i]) || Double.isInfinite(f[i])) f[i] = 0.0;
        }
        return f;
    }

    /** Angle between the average gravity direction before and after the event. */
    private static double orientationChangeDegrees(double[][] w) {
        double[] before = new double[3];
        double[] after = new double[3];
        for (int i = 0; i < HALF; i++) {
            for (int k = 0; k < 3; k++) before[k] += w[i][k];
        }
        for (int i = PRE + ONE; i < WINDOW; i++) {
            for (int k = 0; k < 3; k++) after[k] += w[i][k];
        }
        for (int k = 0; k < 3; k++) {
            before[k] /= HALF;
            after[k] /= (WINDOW - PRE - ONE);
        }
        double n1 = Math.sqrt(before[0] * before[0] + before[1] * before[1] + before[2] * before[2]);
        double n2 = Math.sqrt(after[0] * after[0] + after[1] * after[1] + after[2] * after[2]);
        if (n1 < 1e-6 || n2 < 1e-6) return 0.0;
        double cos = (before[0] * after[0] + before[1] * after[1] + before[2] * after[2]) / (n1 * n2);
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return Math.toDegrees(Math.acos(cos));
    }

    private static double max(double[] a, int from, int to) {
        double m = a[from];
        for (int i = from + 1; i < to; i++) if (a[i] > m) m = a[i];
        return m;
    }

    private static double min(double[] a, int from, int to) {
        double m = a[from];
        for (int i = from + 1; i < to; i++) if (a[i] < m) m = a[i];
        return m;
    }

    private static double mean(double[] a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += a[i];
        return s / (to - from);
    }

    /** Population standard deviation (same as numpy.std). */
    private static double std(double[] a, int from, int to) {
        double m = mean(a, from, to);
        double s = 0;
        for (int i = from; i < to; i++) s += (a[i] - m) * (a[i] - m);
        return Math.sqrt(s / (to - from));
    }
}
