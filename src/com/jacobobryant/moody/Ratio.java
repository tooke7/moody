package com.jacobobryant.moody;

public class Ratio {
    public int skipped;
    public int listened;

    public double ratio() {
        return (total() == 0) ? 0 :
            ((double)listened) / (skipped + listened);
    }

    private int total() {
        return skipped + listened;
    }

    public double confidence() {
        // when n == a, the confidence level will be 50%
        final int a = 5;
        int n = total();
        return ((double)n) / (n + a);
    }

    public void update(boolean skipped) {
        if (skipped) {
            this.skipped++;
        } else {
            listened++;
        }
    }
}
