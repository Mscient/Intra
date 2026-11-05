package com.mscient.intrapaper.evaluation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Shared session-return definitions for offline evaluators; no eligibility decision is made here. */
public final class SessionReturnStatistics {
    public static final int PERIODS_PER_YEAR = 252;
    private static final int MAX_CHRONOLOGICAL_BLOCKS = 12;

    public record Series(String trialId, double[] returns) {
        public Series {
            if (trialId == null || !trialId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("Invalid trial ID");
            }
            returns = Objects.requireNonNull(returns, "returns").clone();
        }
        @Override public double[] returns() { return returns.clone(); }
    }

    public record TrialStatistics(String trialId, int sessionCount, double meanSessionReturn,
            double sampleSessionReturnStandardDeviation, double dailySharpe, double annualizedSharpe,
            double skewness, double kurtosis, double deflatedSharpeProbability,
            double dsrProbabilityAtOneEffectiveTrial, double dsrProbabilityAtRawFamilyTrialCount) { }

    public record PboSummary(int chronologicalBlockCount, int combinations,
            int belowMedianCount, double probabilityBacktestOverfitting) { }

    public record Summary(double sampleStandardDeviationAcrossTrialSharpes,
            double expectedMaximumSharpeThresholdDaily, List<TrialStatistics> trials, PboSummary pbo) {
        public Summary { trials = List.copyOf(trials); }
    }

    private record Moments(double mean, double sampleStandardDeviation,
            double skewness, double kurtosis) { }

    private SessionReturnStatistics() { }

    public static void requireBlockCount(int blockCount) {
        if (blockCount < 4 || blockCount > MAX_CHRONOLOGICAL_BLOCKS || blockCount % 2 != 0) {
            throw new IllegalArgumentException("chronological block count must be even and between 4 and "
                    + MAX_CHRONOLOGICAL_BLOCKS);
        }
    }

    /** The caller must supply every registered trial in one synchronous dataset/split family. */
    public static Summary analyze(List<Series> inputs, int chronologicalBlockCount) throws IOException {
        requireBlockCount(chronologicalBlockCount);
        List<Series> runs = new ArrayList<>(inputs);
        runs.sort(Comparator.comparing(Series::trialId));
        if (runs.size() < 2) throw new IOException("PBO requires at least two analyzed trials");
        int observations = runs.getFirst().returns.length;
        if (observations < chronologicalBlockCount * 2) {
            throw new IOException("Development and validation must contain at least two sessions per chronological block");
        }
        HashSet<String> ids = new HashSet<>();
        List<Moments> trialMoments = new ArrayList<>(runs.size());
        double[] dailySharpes = new double[runs.size()];
        double sumDailySharpe = 0.0;
        for (Series run : runs) {
            if (!ids.add(run.trialId)) throw new IOException("Duplicate trial ID: " + run.trialId);
            if (run.returns.length != observations) throw new IOException("Trials must have synchronous session returns");
            Moments moments = moments(run.returns);
            double dailySharpe = moments.mean() / moments.sampleStandardDeviation();
            if (!Double.isFinite(dailySharpe)) throw new IOException("Daily Sharpe is outside the supported numeric range");
            dailySharpes[trialMoments.size()] = dailySharpe;
            sumDailySharpe += dailySharpe;
            trialMoments.add(moments);
        }
        double meanTrialSharpe = sumDailySharpe / dailySharpes.length;
        double sumSquaredSharpeDeviation = 0.0;
        for (double sharpe : dailySharpes) {
            double deviation = sharpe - meanTrialSharpe;
            sumSquaredSharpeDeviation += deviation * deviation;
        }
        double trialSharpeSampleStdDev = Math.sqrt(sumSquaredSharpeDeviation / (dailySharpes.length - 1.0));
        if (!Double.isFinite(trialSharpeSampleStdDev)) {
            throw new IOException("Cross-trial Sharpe dispersion is outside the supported numeric range");
        }
        double expectedMaxDailySharpe = expectedMaxStandardNormal(runs.size()) * trialSharpeSampleStdDev;
        List<TrialStatistics> summaries = new ArrayList<>(runs.size());
        for (int index = 0; index < runs.size(); index++) {
            Series run = runs.get(index);
            Moments moments = trialMoments.get(index);
            double dailySharpe = dailySharpes[index];
            double standardError = sharpeStandardError(dailySharpe, moments, observations);
            double dsrAtOneTrial = normalCdf(dailySharpe / standardError);
            double dsrAtRawFamilyCount = normalCdf((dailySharpe - expectedMaxDailySharpe) / standardError);
            double annualizedSharpe = dailySharpe * Math.sqrt(PERIODS_PER_YEAR);
            if (!Double.isFinite(annualizedSharpe) || !Double.isFinite(dsrAtOneTrial)
                    || !Double.isFinite(dsrAtRawFamilyCount)) {
                throw new IOException("Evaluation statistic is outside the supported numeric range for " + run.trialId);
            }
            summaries.add(new TrialStatistics(run.trialId, observations, moments.mean(),
                    moments.sampleStandardDeviation(), dailySharpe, annualizedSharpe, moments.skewness(),
                    moments.kurtosis(), dsrAtRawFamilyCount, dsrAtOneTrial, dsrAtRawFamilyCount));
        }
        return new Summary(trialSharpeSampleStdDev, expectedMaxDailySharpe, summaries,
                probabilityBacktestOverfitting(runs, chronologicalBlockCount));
    }

    private static PboSummary probabilityBacktestOverfitting(List<Series> runs, int blockCount) throws IOException {
        int observationCount = runs.getFirst().returns.length;
        if (runs.size() < 2) throw new IOException("PBO requires at least two analyzed trials");
        List<int[]> blocks = new ArrayList<>(blockCount);
        for (int block = 0; block < blockCount; block++) {
            int start = block * observationCount / blockCount;
            int end = (block + 1) * observationCount / blockCount;
            if (end - start < 2) throw new IOException("Each PBO chronological block must contain at least two sessions");
            int[] indices = new int[end - start];
            for (int i = 0; i < indices.length; i++) indices[i] = start + i;
            blocks.add(indices);
        }

        int half = blockCount / 2;
        int combinations = 0;
        int belowMedian = 0;
        int limit = 1 << blockCount;
        for (int mask = 0; mask < limit; mask++) {
            if (Integer.bitCount(mask) != half) continue;
            combinations++;
            boolean[] inSample = new boolean[observationCount];
            for (int block = 0; block < blockCount; block++) {
                if ((mask & (1 << block)) == 0) continue;
                for (int observation : blocks.get(block)) inSample[observation] = true;
            }
            int selectedWinner = -1;
            double bestInSampleSharpe = Double.NEGATIVE_INFINITY;
            for (int trial = 0; trial < runs.size(); trial++) {
                double score = sharpe(runs.get(trial).returns, inSample);
                if (score > bestInSampleSharpe) {
                    bestInSampleSharpe = score;
                    selectedWinner = trial;
                }
            }
            boolean[] outOfSample = new boolean[observationCount];
            for (int i = 0; i < observationCount; i++) outOfSample[i] = !inSample[i];
            double winnerOutOfSample = sharpe(runs.get(selectedWinner).returns, outOfSample);
            int less = 0;
            int equal = 0;
            for (Series run : runs) {
                double score = sharpe(run.returns, outOfSample);
                int comparison = Double.compare(score, winnerOutOfSample);
                if (comparison < 0) less++;
                else if (comparison == 0) equal++;
            }
            double averageRank = less + (equal + 1.0) / 2.0;
            double relativeRank = averageRank / (runs.size() + 1.0);
            double lambda = Math.log(relativeRank / (1.0 - relativeRank));
            if (lambda < 0.0) belowMedian++;
        }
        if (combinations == 0) throw new IOException("No valid PBO block combinations");
        return new PboSummary(blockCount, combinations, belowMedian, (double) belowMedian / combinations);
    }

    private static double sharpe(double[] values, boolean[] selection) throws IOException {
        int count = 0;
        for (boolean included : selection) if (included) count++;
        if (count < 2) throw new IOException("PBO fold contains fewer than two sessions");
        double[] selected = new double[count];
        int cursor = 0;
        for (int index = 0; index < selection.length; index++) {
            if (selection[index]) selected[cursor++] = values[index];
        }
        Moments moments = moments(selected);
        if (moments.sampleStandardDeviation() == 0.0) {
            throw new IOException("Sharpe is undefined for a zero-variance PBO fold");
        }
        return moments.mean() / moments.sampleStandardDeviation();
    }

    private static Moments moments(double[] values) throws IOException {
        if (values.length < 2) throw new IOException("At least two return observations are required");
        double sum = 0.0;
        double compensation = 0.0;
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IOException("Non-finite return observation");
            double adjusted = value - compensation;
            double next = sum + adjusted;
            compensation = (next - sum) - adjusted;
            sum = next;
        }
        double mean = sum / values.length;
        double second = 0.0;
        double third = 0.0;
        double fourth = 0.0;
        for (double value : values) {
            double delta = value - mean;
            double square = delta * delta;
            second += square;
            third += square * delta;
            fourth += square * square;
        }
        double populationVariance = second / values.length;
        double sampleDeviation = Math.sqrt(second / (values.length - 1.0));
        if (populationVariance == 0.0 || sampleDeviation == 0.0) {
            throw new IOException("Sharpe is undefined for a zero-variance return series");
        }
        double skewness = (third / values.length) / Math.pow(populationVariance, 1.5);
        double kurtosis = (fourth / values.length) / (populationVariance * populationVariance);
        if (!Double.isFinite(mean) || !Double.isFinite(sampleDeviation)
                || !Double.isFinite(skewness) || !Double.isFinite(kurtosis)) {
            throw new IOException("Return moments are outside the supported numeric range");
        }
        return new Moments(mean, sampleDeviation, skewness, kurtosis);
    }

    private static double sharpeStandardError(double sharpe, Moments moments, int observations) throws IOException {
        double variance = (1.0 - moments.skewness() * sharpe
                + ((moments.kurtosis() - 1.0) / 4.0) * sharpe * sharpe) / (observations - 1.0);
        if (!(variance > 0.0) || !Double.isFinite(variance)) {
            throw new IOException("Deflated Sharpe variance estimate is invalid");
        }
        return Math.sqrt(variance);
    }

    private static double expectedMaxStandardNormal(long trialCount) {
        if (trialCount <= 1) return 0.0;
        double firstQuantile = inverseNormal(1.0 - 1.0 / trialCount);
        double secondQuantile = inverseNormal(1.0 - 1.0 / (trialCount * Math.E));
        double eulerMascheroni = 0.5772156649015329;
        return (1.0 - eulerMascheroni) * firstQuantile + eulerMascheroni * secondQuantile;
    }

    /** Acklam's rational approximation for the standard normal inverse CDF. */
    private static double inverseNormal(double probability) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00};
        double low = 0.02425;
        double high = 1.0 - low;
        if (probability < low) {
            double q = Math.sqrt(-2.0 * Math.log(probability));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        if (probability > high) {
            double q = Math.sqrt(-2.0 * Math.log(1.0 - probability));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        double q = probability - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0);
    }

    /** Numerical normal CDF approximation with absolute error below 1e-7 for ordinary finite inputs. */
    private static double normalCdf(double value) {
        if (value == Double.POSITIVE_INFINITY) return 1.0;
        if (value == Double.NEGATIVE_INFINITY) return 0.0;
        double x = Math.abs(value) / Math.sqrt(2.0);
        double t = 1.0 / (1.0 + 0.5 * x);
        double tau = t * Math.exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196
                + t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398
                + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        double erf = value >= 0.0 ? 1.0 - tau : tau - 1.0;
        return 0.5 * (1.0 + erf);
    }

}
