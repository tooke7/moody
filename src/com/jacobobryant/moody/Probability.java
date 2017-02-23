package com.jacobobryant.moody;

import android.util.Log;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Map;

public class Probability {
    public double prob;
    Metadata m;
    public static final double DEFAULT_PROB = 0.75;

    public Probability(Map<Metadata, Ratio> ratios, Metadata m) {
        prob = calc(ratios, m);


        NumberFormat formatter = new DecimalFormat("#0.00");

        //Log.d(C.TAG, formatter.format(prob) + " (" + m.title + ")");
        this.m = m;
    }

    private double calc(Map<Metadata, Ratio> ratios, Metadata m) {
        if (m == null) {
            return DEFAULT_PROB;
        }
        Ratio r = ratios.get(m);
        double conf = r.confidence();
        return conf * r.ratio() + (1 - conf) * calc(ratios, m.pop());
    }
}
