package com.mscient.intrapaper.pricing;

/** Double-precision standard normal density and CDF shared by the European pricing models. */
final class NormalDistribution {
    private static final double SQRT_TWO = Math.sqrt(2.0);
    private static final double SQRT_TWO_PI = Math.sqrt(2.0 * Math.PI);

    // Cephes erf/erfc rational approximations (Stephen L. Moshier); see benchmark README for source.
    private static final double[] ERF_T = {
            9.60497373987051638749E0, 9.00260197203842689217E1,
            2.23200534594684319226E3, 7.00332514112805075473E3,
            5.55923013010394962768E4
    };
    private static final double[] ERF_U = {
            3.35617141647503099647E1, 5.21357949780152679795E2,
            4.59432382970980127987E3, 2.26290000613890934246E4,
            4.92673942608635921086E4
    };
    private static final double[] ERFC_P = {
            2.46196981473530512524E-10, 5.64189564831068821977E-1,
            7.46321056442269912687E0, 4.86371970985681366614E1,
            1.96520832956077098242E2, 5.26445194995477358631E2,
            9.34528527171957607540E2, 1.02755188689515710272E3,
            5.57535335369399327526E2
    };
    private static final double[] ERFC_Q = {
            1.32281951154744992508E1, 8.67072140885989742329E1,
            3.54937778887819891062E2, 9.75708501743205489753E2,
            1.82390916687909736289E3, 2.24633760818710981792E3,
            1.65666309194161350182E3, 5.57535340817727675546E2
    };
    private static final double[] ERFC_R = {
            5.64189583547755073984E-1, 1.27536670759978104416E0,
            5.01905042251180477414E0, 6.16021097993053585195E0,
            7.40974269950448939160E0, 2.97886665372100240670E0
    };
    private static final double[] ERFC_S = {
            2.26052863220117276590E0, 9.39603524938001434673E0,
            1.20489539808096656605E1, 1.70814450747565897222E1,
            9.60896809063285878198E0, 3.36907645100081516050E0
    };
    private static final double MAX_LOG = 7.09782712893383996732E2;

    private NormalDistribution() { }

    static double density(double value) {
        return Math.exp(-0.5 * value * value) / SQRT_TWO_PI;
    }

    /** Accurate through the tails; unlike 1-CDF(x), negative tails do not cancel to zero. */
    static double cdf(double value) {
        if (Double.isNaN(value)) return Double.NaN;
        if (value > 1.0) return 1.0 - 0.5 * erfc(value / SQRT_TWO);
        if (value < -1.0) return 0.5 * erfc(-value / SQRT_TWO);
        return 0.5 + 0.5 * erf(value / SQRT_TWO);
    }

    private static double erf(double value) {
        if (Math.abs(value) > 1.0) {
            return value < 0.0 ? erfc(-value) - 1.0 : 1.0 - erfc(value);
        }
        double square = value * value;
        return value * polevl(square, ERF_T) / p1evl(square, ERF_U);
    }

    private static double erfc(double value) {
        if (value < 0.0) return 2.0 - erfc(-value);
        if (value < 1.0) return 1.0 - erf(value);

        double exponent = -value * value;
        if (exponent < -MAX_LOG) return 0.0;
        double rational = value < 8.0
                ? polevl(value, ERFC_P) / p1evl(value, ERFC_Q)
                : polevl(value, ERFC_R) / p1evl(value, ERFC_S);
        return Math.exp(exponent) * rational;
    }

    private static double polevl(double value, double[] coefficients) {
        double result = coefficients[0];
        for (int index = 1; index < coefficients.length; index++) {
            result = result * value + coefficients[index];
        }
        return result;
    }

    /** Polynomial with an implicit leading coefficient of one. */
    private static double p1evl(double value, double[] coefficients) {
        double result = value + coefficients[0];
        for (int index = 1; index < coefficients.length; index++) {
            result = result * value + coefficients[index];
        }
        return result;
    }
}
