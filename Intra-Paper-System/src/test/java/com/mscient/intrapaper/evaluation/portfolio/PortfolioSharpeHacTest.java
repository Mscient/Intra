package com.mscient.intrapaper.evaluation.portfolio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Fixed deterministic HAC equation checks and frozen synthetic-process qualification. */
public final class PortfolioSharpeHacTest {
    private static int checks;
    private static final int REPLICATIONS = 2_000;
    private static final int RETAINED = 240;
    private static final int BURN_IN = 1_000;
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private PortfolioSharpeHacTest() { }

    public static void main(String[] args) throws Exception {
        equationFixture();
        minimumHistoryAndGrid();
        numericGuards();
        syntheticQualification();
        System.out.println("Portfolio Sharpe HAC: " + checks + " checks passed");
    }

    private static void equationFixture() throws Exception {
        double[] values = {0.0, 0.01, 0.0, 0.01};
        var result = PortfolioSharpeHac.calculateForEquationFixture(values);
        close(result.sharpePointEstimate(), Math.sqrt(3.0) / 2.0, 2e-15, "equation fixture Sharpe");
        close(result.bandwidths().get(0).projectedLongRunVariance(), 1.0, 2e-14, "L=0 projected LRV");
        close(result.bandwidths().get(0).standardError(), Math.sqrt(3.0) / 4.0, 2e-14, "L=0 standard error");
        close(result.bandwidths().get(1).projectedLongRunVariance(), 0.25, 2e-14, "L=1 projected LRV");
        close(result.bandwidths().get(1).standardError(), Math.sqrt(3.0) / 8.0, 2e-14, "L=1 standard error");
        for (var bandwidth : result.bandwidths()) {
            close(bandwidth.projectedLongRunVariance(), bandwidth.scalarProjectedLongRunVariance(),
                    2e-10, "matrix/scalar projection agreement");
        }
    }

    private static void minimumHistoryAndGrid() throws Exception {
        double[] shortValues = new double[59];
        for (int i = 0; i < shortValues.length; i++) shortValues[i] = i * 1e-4;
        var shortSeries = PortfolioSharpeHac.evaluate(shortValues);
        require(shortSeries.status().equals("INCOMPLETE") && shortSeries.reason().equals("INSUFFICIENT_SESSIONS"),
                "T<60 is explicitly incomplete");
        require(shortSeries.bandwidths().stream().allMatch(b -> b.standardError() == null), "no HAC SE below floor");
        require(PortfolioSharpeHac.bandwidthGrid(240).equals(List.of(0, 1, 2, 4)), "frozen bandwidth grid");
        require(PortfolioSharpeHac.bandwidthGrid(60).equals(List.of(0, 1, 2, 4)), "T=60 bandwidth grid");
        double[] eligible = new double[60];
        for (int i = 0; i < eligible.length; i++) eligible[i] = 0.01 + (i % 7 - 3) * 0.001;
        var complete = PortfolioSharpeHac.evaluate(eligible);
        double mean = compensatedMean(java.util.Arrays.stream(eligible).boxed().toList());
        double squares = 0.0;
        for (double value : eligible) squares += (value - mean) * (value - mean);
        close(complete.sharpePointEstimate(), mean / Math.sqrt(squares / (eligible.length - 1.0)),
                2e-14, "HAC point estimate equals legacy sample-standard-deviation Sharpe");
    }

    private static void numericGuards() throws Exception {
        expectFailure(() -> PortfolioSharpeHac.evaluate(new double[60]), "zero variance rejected");
        double[] nearZero = new double[60];
        for (int i = 0; i < nearZero.length; i++) nearZero[i] = 1.0 + i * 1e-9;
        expectFailure(() -> PortfolioSharpeHac.evaluate(nearZero), "near precision-limit variance rejected");
        double[] nonFinite = new double[60];
        nonFinite[3] = Double.NaN;
        expectFailure(() -> PortfolioSharpeHac.evaluate(nonFinite), "nonfinite return rejected");
    }

    private static void syntheticQualification() throws Exception {
        qualify("IID", 0x4949445f48414331L, Process.IID);
        qualify("AR1", 0x4152315f48414331L, Process.AR1);
        qualify("VOL", 0x564f4c5f48414331L, Process.VOL);
    }

    private enum Process { IID, AR1, VOL }

    private static void qualify(String name, long baseSeed, Process process) throws Exception {
        List<Double> pointEstimates = new ArrayList<>(REPLICATIONS);
        double[] sumSquaredSe = null;
        int[] bandwidths = null;
        for (int replication = 0; replication < REPLICATIONS; replication++) {
            long seed = baseSeed ^ (GOLDEN * (replication + 1L));
            double[] returns = sample(seed, process);
            var result = PortfolioSharpeHac.evaluate(returns);
            require(result.status().equals("COMPLETE"), name + " replication has complete HAC result");
            if (bandwidths == null) {
                bandwidths = result.bandwidthGrid().stream().mapToInt(Integer::intValue).toArray();
                sumSquaredSe = new double[bandwidths.length];
            }
            pointEstimates.add(result.sharpePointEstimate());
            for (int index = 0; index < bandwidths.length; index++) {
                var bandwidth = result.bandwidths().get(index);
                require(bandwidth.lag() == bandwidths[index] && bandwidth.standardError() != null,
                        name + " bandwidth result available");
                sumSquaredSe[index] += bandwidth.standardError() * bandwidth.standardError();
            }
        }
        double mean = compensatedMean(pointEstimates);
        double empiricalVariance = 0.0;
        double compensation = 0.0;
        for (double value : pointEstimates) {
            double delta = value - mean;
            double term = delta * delta;
            double adjusted = term - compensation;
            double next = empiricalVariance + adjusted;
            compensation = (next - empiricalVariance) - adjusted;
            empiricalVariance = next;
        }
        double empiricalSd = Math.sqrt(empiricalVariance / REPLICATIONS);
        int finalIndex = bandwidths.length - 1;
        double rmsHacSe = Math.sqrt(sumSquaredSe[finalIndex] / REPLICATIONS);
        double ratio = rmsHacSe / empiricalSd;
        StringBuilder details = new StringBuilder(name).append(" synthetic qualification: empirical_sd=")
                .append(empiricalSd).append("; bandwidth_rms_se=");
        for (int index = 0; index < bandwidths.length; index++) {
            if (index > 0) details.append(',');
            details.append(bandwidths[index]).append(':').append(Math.sqrt(sumSquaredSe[index] / REPLICATIONS));
        }
        details.append("; largest_bandwidth_ratio=").append(ratio);
        System.out.println(details);
        require(ratio >= 0.80 && ratio <= 1.20, name + " largest bandwidth lies in frozen [0.80,1.20] band");
    }

    private static double[] sample(long seed, Process process) {
        SplittableRandom random = new SplittableRandom(seed);
        Normal normal = new Normal(random);
        double[] result = new double[RETAINED];
        double previous = 0.0;
        double variance = 0.0002;
        for (int index = 0; index < BURN_IN + RETAINED; index++) {
            double value;
            if (process == Process.IID) {
                value = 0.0002 + 0.01 * normal.next();
            } else if (process == Process.AR1) {
                previous = 0.25 * previous + 0.01 * Math.sqrt(1.0 - 0.25 * 0.25) * normal.next();
                value = 0.0002 + previous;
            } else {
                variance = 0.000004 + 0.08 * previous * previous + 0.90 * variance;
                double sigma = Math.sqrt(variance);
                previous = sigma * normal.next();
                value = 0.0002 + previous;
            }
            if (index >= BURN_IN) result[index - BURN_IN] = value;
        }
        return result;
    }

    private static final class Normal {
        private final SplittableRandom random;
        Normal(SplittableRandom random) { this.random = random; }
        double next() {
            double u1 = 1.0 - random.nextDouble();
            double u2 = 1.0 - random.nextDouble();
            return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
        }
    }

    private static double compensatedMean(List<Double> values) {
        double sum = 0.0;
        double compensation = 0.0;
        for (double value : values) {
            double adjusted = value - compensation;
            double next = sum + adjusted;
            compensation = (next - sum) - adjusted;
            sum = next;
        }
        return sum / values.size();
    }

    private static void close(Double actual, double expected, double tolerance, String label) {
        if (actual == null || Math.abs(actual - expected) > tolerance * Math.max(1.0, Math.abs(expected))) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
        checks++;
    }

    private static void expectFailure(ThrowingRunnable action, String label) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError(label + " (not rejected)");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
