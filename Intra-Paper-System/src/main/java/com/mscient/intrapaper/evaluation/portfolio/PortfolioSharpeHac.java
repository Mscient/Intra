package com.mscient.intrapaper.evaluation.portfolio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Frozen fixed-candidate HAC standard-error diagnostic; does not alter legacy DSR/PBO statistics. */
final class PortfolioSharpeHac {
    static final String PROTOCOL_ID = "INTRA_PORTFOLIO_SHARPE_HAC_V1";
    static final String PROTOCOL_SHA256 = "e7f00ce87640c69743779ee580b8eab81725ef34054a056330ae6d1757ead06b";
    static final String SOURCE_REVIEW_COMMIT = "263e435f6daf3986267aae1e3d9b3ef45d3e707b";
    static final String SOURCE_REVIEW_BLOB = "e56d0b9b3f3ccc56004303de871c45e008bc40bd";
    static final int MINIMUM_SESSIONS = 60;
    static final String ESTIMATOR_VERSION = "DELTA_SHARPE_BARTLETT_GLOBAL_CENTERED_V1";
    private static final double VARIANCE_FLOOR = 64.0 * Math.ulp(1.0);
    private static final double PROJECTION_TOLERANCE = 2e-10;

    record Bandwidth(int lag, String status, String reason, Double projectedLongRunVariance,
            Double standardError, Double scalarProjectedLongRunVariance) { }
    record Acf(int lag, int pairCount, Double returnAcf, Double squaredCenteredReturnAcf,
            Double influenceAcf, String undefinedReason) { }
    record Result(String status, String reason, int sessionCount, double sharpePointEstimate,
            List<Integer> bandwidthGrid, List<Bandwidth> bandwidths, List<Acf> acf) {
        Result { bandwidthGrid = List.copyOf(bandwidthGrid); bandwidths = List.copyOf(bandwidths); acf = List.copyOf(acf); }
    }

    private PortfolioSharpeHac() { }

    static Result evaluate(double[] input) throws IOException { return calculate(input, true); }

    static Result calculateForEquationFixture(double[] input) throws IOException { return calculate(input, false); }

    private static Result calculate(double[] input, boolean enforceMinimum) throws IOException {
        if (input == null || input.length == 0) throw new IOException("HAC requires a non-empty return series");
        int t = input.length;
        List<Integer> grid = bandwidthGrid(t);
        double scale = 0.0;
        for (double value : input) {
            if (!Double.isFinite(value)) throw new IOException("Non-finite HAC return");
            scale = Math.max(scale, Math.abs(value));
        }
        if (!(scale > 0.0) || !Double.isFinite(scale)) throw new IOException("HAC return variance is at the numeric precision limit");
        double[] x = new double[t];
        for (int i = 0; i < t; i++) x[i] = input[i] / scale;
        double mu = mean(x);
        double[] square = new double[t];
        for (int i = 0; i < t; i++) square[i] = x[i] * x[i];
        double m2 = mean(square);
        double[] centered = new double[t];
        double[] centeredSquare = new double[t];
        double[] varianceTerms = new double[t];
        for (int i = 0; i < t; i++) {
            centered[i] = x[i] - mu;
            varianceTerms[i] = centered[i] * centered[i];
        }
        double variance = mean(varianceTerms);
        if (!(variance > VARIANCE_FLOOR) || !Double.isFinite(variance)) {
            throw new IOException("HAC return variance is at the numeric precision limit");
        }
        if (enforceMinimum && t < MINIMUM_SESSIONS) {
            return new Result("INCOMPLETE", "INSUFFICIENT_SESSIONS", t, Double.NaN, grid,
                    grid.stream().map(lag -> new Bandwidth(lag, "INCOMPLETE", "INSUFFICIENT_SESSIONS", null, null, null)).toList(), List.of());
        }
        for (int i = 0; i < t; i++) centeredSquare[i] = varianceTerms[i] - variance;
        double rootVariance = Math.sqrt(variance);
        double v32 = variance * rootVariance;
        double d0 = m2 / v32;
        double d1 = -mu / (2.0 * v32);
        if (!Double.isFinite(d0) || !Double.isFinite(d1)) throw new IOException("HAC Sharpe derivative is non-finite");
        double[] z0 = x.clone();
        double[] z1 = square;
        double z0Mean = mu;
        double z1Mean = m2;
        double[] influence = new double[t];
        for (int i = 0; i < t; i++) {
            influence[i] = centered[i] / rootVariance
                    - mu * (varianceTerms[i] - variance) / (2.0 * v32);
            if (!Double.isFinite(influence[i])) throw new IOException("HAC influence series is non-finite");
        }
        double influenceMean = mean(influence);
        double[] influenceCentered = new double[t];
        for (int i = 0; i < t; i++) influenceCentered[i] = influence[i] - influenceMean;
        double cT = Math.sqrt((t - 1.0) / t);
        double point = cT * mu / rootVariance;
        List<Bandwidth> results = new ArrayList<>();
        for (int lag : grid) {
            if (lag >= t) {
                results.add(new Bandwidth(lag, "UNAVAILABLE", "BANDWIDTH_NOT_LESS_THAN_SESSION_COUNT", null, null, null));
                continue;
            }
            double[] matrix = matrixProjection(z0, z1, z0Mean, z1Mean, d0, d1, t, lag);
            double scalar = scalarLongRunVariance(influenceCentered, lag);
            double difference = Math.abs(matrix[0] - scalar);
            if (!Double.isFinite(matrix[0]) || !Double.isFinite(scalar)
                    || difference > PROJECTION_TOLERANCE * Math.max(1.0, Math.max(Math.abs(matrix[0]), Math.abs(scalar)))) {
                throw new IOException("HAC matrix and scalar projections disagree");
            }
            double termScale = matrix[1];
            double floor = VARIANCE_FLOOR * Math.max(1.0, termScale);
            if (!(matrix[0] > floor)) {
                results.add(new Bandwidth(lag, "INCOMPLETE", "NUMERIC_PRECISION_LIMIT", null, null, scalar));
                continue;
            }
            double se = cT * Math.sqrt(matrix[0] / t);
            if (!(se > 0.0) || !Double.isFinite(se)) {
                results.add(new Bandwidth(lag, "INCOMPLETE", "NUMERIC_PRECISION_LIMIT", null, null, scalar));
                continue;
            }
            results.add(new Bandwidth(lag, "COMPLETE", null, matrix[0], se, scalar));
        }
        int maxLag = results.stream().filter(b -> b.status().equals("COMPLETE")).mapToInt(Bandwidth::lag).max().orElse(0);
        List<Acf> acfs = new ArrayList<>();
        for (int lag = 1; lag <= maxLag; lag++) {
            acfs.add(new Acf(lag, t - lag,
                    acf(x, mean(x), lag), acf(centeredSquare, mean(centeredSquare), lag),
                    acf(influenceCentered, mean(influenceCentered), lag),
                    acfUndefinedReason(x, centeredSquare, influenceCentered)));
        }
        return new Result("COMPLETE", null, t, point, grid, results, acfs);
    }

    static List<Integer> bandwidthGrid(int t) {
        int base = (int) Math.floor(Math.pow(t, 0.2));
        TreeSet<Integer> values = new TreeSet<>();
        values.add(0);
        values.add(Math.max(1, base / 2));
        values.add(base);
        values.add(Math.multiplyExact(2, base));
        return List.copyOf(values);
    }

    private static double[] matrixProjection(double[] z0, double[] z1, double m0, double m1,
            double d0, double d1, int t, int bandwidth) {
        double[][] sums = new double[2][2];
        double[][] compensation = new double[2][2];
        double[][] absoluteSums = new double[2][2];
        for (int lag = 0; lag <= bandwidth; lag++) {
            double weight = lag == 0 ? 1.0 : 1.0 - (double) lag / (bandwidth + 1.0);
            for (int i = lag; i < t; i++) {
                double[] current = {z0[i] - m0, z1[i] - m1};
                double[] previous = {z0[i - lag] - m0, z1[i - lag] - m1};
                for (int row = 0; row < 2; row++) for (int col = 0; col < 2; col++) {
                    double term = current[row] * previous[col] / t * (lag == 0 ? 1.0 : weight);
                    kahanAdd(sums, compensation, row, col, term);
                    absoluteSums[row][col] += Math.abs(term);
                    if (lag > 0) {
                        double transpose = previous[row] * current[col] / t * weight;
                        kahanAdd(sums, compensation, row, col, transpose);
                        absoluteSums[row][col] += Math.abs(transpose);
                    }
                }
            }
        }
        double projection = d0 * (sums[0][0] * d0 + sums[0][1] * d1)
                + d1 * (sums[1][0] * d0 + sums[1][1] * d1);
        double absoluteProjected = d0 * d0 * absoluteSums[0][0]
                + Math.abs(d0 * d1) * (absoluteSums[0][1] + absoluteSums[1][0])
                + d1 * d1 * absoluteSums[1][1];
        return new double[] {projection, absoluteProjected};
    }

    private static void kahanAdd(double[][] sum, double[][] compensation, int row, int col, double value) {
        double adjusted = value - compensation[row][col];
        double next = sum[row][col] + adjusted;
        compensation[row][col] = (next - sum[row][col]) - adjusted;
        sum[row][col] = next;
    }

    private static double scalarLongRunVariance(double[] values, int bandwidth) {
        double sum = 0.0;
        double compensation = 0.0;
        int t = values.length;
        for (int lag = 0; lag <= bandwidth; lag++) {
            double weight = lag == 0 ? 1.0 : 1.0 - (double) lag / (bandwidth + 1.0);
            for (int i = lag; i < t; i++) {
                double term = values[i] * values[i - lag] / t * (lag == 0 ? 1.0 : 2.0 * weight);
                double adjusted = term - compensation;
                double next = sum + adjusted;
                compensation = (next - sum) - adjusted;
                sum = next;
            }
        }
        return sum;
    }

    private static Double acf(double[] values, double mean, int lag) {
        double denominator = 0.0;
        double denomCompensation = 0.0;
        double numerator = 0.0;
        double numCompensation = 0.0;
        for (double value : values) {
            double term = (value - mean) * (value - mean);
            double adjusted = term - denomCompensation;
            double next = denominator + adjusted;
            denomCompensation = (next - denominator) - adjusted;
            denominator = next;
        }
        if (!(denominator > 0.0)) return null;
        for (int i = lag; i < values.length; i++) {
            double term = (values[i] - mean) * (values[i - lag] - mean);
            double adjusted = term - numCompensation;
            double next = numerator + adjusted;
            numCompensation = (next - numerator) - adjusted;
            numerator = next;
        }
        double value = numerator / denominator;
        return Double.isFinite(value) ? value : null;
    }

    private static String acfUndefinedReason(double[] returns, double[] squared, double[] influence) {
        if (acf(returns, mean(returns), 1) == null) return "ZERO_VARIANCE_RETURNS";
        if (acf(squared, mean(squared), 1) == null) return "ZERO_VARIANCE_SQUARED_CENTERED_RETURNS";
        if (acf(influence, mean(influence), 1) == null) return "ZERO_VARIANCE_INFLUENCE";
        return null;
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        double compensation = 0.0;
        for (double value : values) {
            double adjusted = value - compensation;
            double next = sum + adjusted;
            compensation = (next - sum) - adjusted;
            sum = next;
        }
        return sum / values.length;
    }
}
