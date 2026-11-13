package com.example.womensafetyalertsystem;

/**
 * Turns the phone's accelerometer readings (irregular timing, any rate) into a steady 50 Hz stream,
 * by linear interpolation between neighbouring readings. The fall model was trained on 50 Hz data.
 *
 * Feed it SensorEvent.timestamp (nanoseconds) and the three values (any unit; the detector converts to g).
 * If the readings stop for more than 0.5 s the stream is restarted and Sink.onGap() is called.
 */
public final class AccelResampler {

    public interface Sink {
        void onSample(double x, double y, double z);

        void onGap();
    }

    public static final long PERIOD_NS = 20_000_000L;       // 50 Hz
    private static final long MAX_GAP_NS = 500_000_000L;

    private final Sink sink;
    private boolean started;
    private long t0;
    private long nextT;
    private double x0, y0, z0;

    public AccelResampler(Sink sink) {
        this.sink = sink;
    }

    public void reset() {
        started = false;
    }

    public void push(long t1, double x1, double y1, double z1) {
        if (!started) {
            start(t1, x1, y1, z1);
            return;
        }
        if (t1 <= t0) {
            return;                                         // duplicate or out-of-order reading
        }
        if (t1 - t0 > MAX_GAP_NS) {
            sink.onGap();
            start(t1, x1, y1, z1);
            return;
        }
        double span = (double) (t1 - t0);
        while (nextT <= t1) {
            double a = (double) (nextT - t0) / span;
            sink.onSample(x0 + a * (x1 - x0), y0 + a * (y1 - y0), z0 + a * (z1 - z0));
            nextT += PERIOD_NS;
        }
        t0 = t1;
        x0 = x1;
        y0 = y1;
        z0 = z1;
    }

    private void start(long t, double x, double y, double z) {
        started = true;
        t0 = t;
        x0 = x;
        y0 = y;
        z0 = z;
        sink.onSample(x, y, z);                             // first grid point = first reading
        nextT = t + PERIOD_NS;
    }
}
