package net.lckx.garmin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.lckx.garmin.DescribeGarminFile.Activity;
import net.lckx.garmin.DescribeGarminFile.FitMessage;

/**
 * The fastest 50, 100, 200, 400 and 1000 m of a pool swim, from the lengths in its FIT file.
 * <ul>
 *   <li>only consecutive active lengths count: a rest (idle length) ends the stretch;</li>
 *   <li>only distances that are a whole number of lengths (4 × 25 m = 100 m; a 14 m pool has no 50 m);</li>
 *   <li>no stretch may hold a length the watch probably miscounted: one in a lap that {@link SwimCorrection}
 *       recounted, or one that took under {@value #SHORT} or over {@value #LONG} times a real length (the correction's
 *       reference time, from consistent swims; else the swim's median) — a phantom or a missed turn;</li>
 *   <li>a swim whose distance was set by hand has none (its lengths are not to be trusted).</li>
 * </ul>
 */
final class SwimBests {
    static final int[] DISTANCES = {50, 100, 200, 400, 1000};
    static final double SHORT = 0.75, LONG = 1.33;

    private SwimBests() {}

    /** Distance in metres → fastest time in seconds, for the distances this swim has; empty for anything else. */
    static Map<Integer, Double> of(Activity a) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        if (!"lap_swimming".equals(a.sport) || a.fit == null || a.fit.count(101) == 0) return out;
        if (a.correction != null && a.correction.manualDistance() != null) return out;
        FitMessage session = a.fit.first(18);
        Double p = session == null ? null : session.scaled(44);
        double pool = p == null || p <= 0 ? 25 : p;

        // Runs of consecutive active lengths; null marks a rest or a length that is not to be trusted.
        List<Double> times = new ArrayList<>();
        List<Double> active = new ArrayList<>();
        List<FitMessage> lens = a.fit.messages(101);
        // The lengths of a lap that SwimCorrection recounted no longer stand for one pool length each.
        boolean[] recounted = new boolean[lens.size()];
        if (a.correction != null) {
            List<FitMessage> lapMsgs = a.fit.messages(19);
            for (SwimCorrection.Lap l : a.correction.laps()) {
                if (!l.recounted() || l.lap() < 1 || l.lap() > lapMsgs.size()) continue;
                FitMessage m = lapMsgs.get(l.lap() - 1);
                if (m.num(35) == null || m.num(32) == null) continue;
                int first = m.num(35).intValue(), count = m.num(32).intValue();
                for (int i = Math.max(0, first); i < Math.min(lens.size(), first + count); i++) recounted[i] = true;
            }
        }
        for (int i = 0; i < lens.size(); i++) {
            FitMessage len = lens.get(i);
            Double t = len.scaled(4);
            boolean on = Double.valueOf(1).equals(len.num(12)) && t != null && t > 0 && !recounted[i];
            times.add(on ? t : null);
            if (on) active.add(t);
        }
        if (active.size() < 2) return out;
        List<Double> sorted = active.stream().sorted().toList();
        double usual = a.correction != null && a.correction.secondsPerLength() > 0 ? a.correction.secondsPerLength() : sorted.get(sorted.size() / 2);
        for (int i = 0; i < times.size(); i++) {
            Double t = times.get(i);
            if (t != null && (t < SHORT * usual || t > LONG * usual)) times.set(i, null);
        }
        for (int d : DISTANCES) {
            double n = d / pool;
            int lengths = (int) Math.round(n);
            if (lengths < 1 || Math.abs(n - lengths) > 1e-6) continue;
            double best = Double.NaN, sum = 0;
            int run = 0;
            for (int i = 0; i < times.size(); i++) {
                Double t = times.get(i);
                if (t == null) {
                    run = 0;
                    sum = 0;
                    continue;
                }
                sum += t;
                run++;
                if (run > lengths) sum -= times.get(i - lengths);
                if (run >= lengths && (Double.isNaN(best) || sum < best)) best = sum;
            }
            if (!Double.isNaN(best)) out.put(d, Math.round(best * 10) / 10.0);
        }
        return out;
    }
}
