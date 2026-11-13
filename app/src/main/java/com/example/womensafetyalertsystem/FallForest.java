package com.example.womensafetyalertsystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Runs a Random Forest exported by retrain_50hz.py (file fall_forest.txt).
 * On Android, put fall_forest.txt in app/src/main/assets and load it with
 * FallForest.load(getAssets().open("fall_forest.txt")).
 */
public final class FallForest {

    private final int featureCount;
    private final int[][] feature;
    private final double[][] threshold;
    private final int[][] left;
    private final int[][] right;
    private final double[][] probability;

    private FallForest(int featureCount, int[][] feature, double[][] threshold,
                       int[][] left, int[][] right, double[][] probability) {
        this.featureCount = featureCount;
        this.feature = feature;
        this.threshold = threshold;
        this.left = left;
        this.right = right;
        this.probability = probability;
    }

    public static FallForest load(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            if (!"FALLFOREST 1".equals(reader.readLine())) {
                throw new IOException("Not a fall forest file");
            }
            int featureCount = Integer.parseInt(reader.readLine().split(" ")[1]);
            int treeCount = Integer.parseInt(reader.readLine().split(" ")[1]);
            int[][] feature = new int[treeCount][];
            double[][] threshold = new double[treeCount][];
            int[][] left = new int[treeCount][];
            int[][] right = new int[treeCount][];
            double[][] probability = new double[treeCount][];

            for (int t = 0; t < treeCount; t++) {
                int nodes = Integer.parseInt(reader.readLine().split(" ")[1]);
                feature[t] = new int[nodes];
                threshold[t] = new double[nodes];
                left[t] = new int[nodes];
                right[t] = new int[nodes];
                probability[t] = new double[nodes];
                for (int n = 0; n < nodes; n++) {
                    String[] p = reader.readLine().split(" ");
                    feature[t][n] = Integer.parseInt(p[0]);
                    threshold[t][n] = Double.parseDouble(p[1]);
                    left[t][n] = Integer.parseInt(p[2]);
                    right[t][n] = Integer.parseInt(p[3]);
                    probability[t][n] = Double.parseDouble(p[4]);
                }
            }
            return new FallForest(featureCount, feature, threshold, left, right, probability);
        }
    }

    /** Average of every tree's vote: the probability (0..1) that the window is a real fall. */
    public double predictProbability(double[] features) {
        if (features.length != featureCount) {
            throw new IllegalArgumentException("Expected " + featureCount + " features");
        }
        double sum = 0;
        int trees = feature.length;
        for (int t = 0; t < trees; t++) {
            int node = 0;
            while (feature[t][node] >= 0) {
                // scikit-learn compares the feature as a 32-bit float, so do the same
                double value = (double) (float) features[feature[t][node]];
                node = value <= threshold[t][node] ? left[t][node] : right[t][node];
            }
            sum += probability[t][node];
        }
        return sum / trees;
    }
}