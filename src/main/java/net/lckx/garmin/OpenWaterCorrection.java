package net.lckx.garmin;

import static net.lckx.garmin.DescribeGarminFile.fmtDist;
import static net.lckx.garmin.DescribeGarminFile.fmtDur;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import net.lckx.garmin.DescribeGarminFile.Activity;
import net.lckx.garmin.DescribeGarminFile.FitFile;
import net.lckx.garmin.DescribeGarminFile.FitMessage;

/**
 * A corrected distance for open-water swims, computed when the report is made (nothing is written, the Garmin files
 * are not changed).
 *
 * <p>The watch measures open-water distance with GPS, which loses its fix every time the wrist is under water: most
 * swims have a position for under 20% of the time (some have none at all), and the distance comes out 40–60% short.
 * Of the ways to rebuild it, the swimmer's own pool pace works best on the swims whose real distance is known:
 * <pre>
 *   distance = swim time / median pace (s per 100 m) of the {@link #NEARBY} nearest consistent pool swims
 * </pre>
 * Pool swims are reliable references (see {@link SwimCorrection}, whose correction files hold each swim's length
 * statistics); only pools of at least {@link #MIN_POOL} m are used, as push-offs make short pools look too fast.
 * Counting strokes × pool metres per stroke was tried too, but distance per stroke in open water varies far more
 * between swims (1.21 m in the sea, 0.89 m in the river on the two known swims) than the pace does.
 *
 * <p>The correction is only made when it is at least {@link #MIN_GAIN} longer than what the watch measured
 * (losing the GPS fix only ever shortens a swim), and never when the distance was already changed in Garmin Connect
 * (the JSON distance then differs from the FIT file's): that edit is the user's own and stands.
 */
final class OpenWaterCorrection {
    static final int NEARBY = 5, MAX_DAYS = 365;
    static final double MIN_POOL = 25;
    /** The estimate must be this much longer than the watch's distance to replace it. */
    static final double MIN_GAIN = 0.15;
    /** A distance in Garmin Connect this far from the FIT file's was edited by hand. */
    static final double EDITED = 0.01;

    /** A pool swim's own statistics, from its {@link SwimCorrection} file. */
    record PoolSwim(LocalDate date, double pool, double strokesPerLength, double secondsPerLength) {
        double secondsPer100() {
            return secondsPerLength / pool * 100;
        }

        double metresPerStroke() {
            return pool / strokesPerLength;
        }
    }

    /**
     * @param distance     corrected metres (for {@code edited}: the distance set in Garmin Connect)
     * @param watch        metres the watch measured (FIT session total distance)
     * @param seconds      swim time used
     * @param secondsPer100 reference pool pace
     * @param strokes      stroke cycles the watch counted (for information)
     * @param references   the pool swims the pace comes from
     * @param edited       the distance was already changed in Garmin Connect: nothing to correct
     * @param explanation  one sentence for the report
     */
    record Result(double distance, double watch, double seconds, double secondsPer100, double strokes,
                  List<LocalDate> references, boolean edited, String explanation) {
        /** True when the report should show {@link #distance()} instead of Garmin's distance. */
        boolean differs() {
            return !edited && Math.abs(distance - watch) >= 0.5;
        }
    }

    private OpenWaterCorrection() {
    }

    // ------------------------------------------------------------------ API

    /** The correction for an activity of the report, or null when it is not an open-water swim or needs none. */
    static Result correct(Activity a) {
        if (a == null || a.fit == null || a.files == null || a.files.fit() == null) return null;
        // The sport after corrections (sport-overrides.tsv), not only Garmin's type.
        if (a.sport != null && !a.sport.isEmpty() && !a.sport.equals("open_water_swimming")) return null;
        return correct(a.fit, a.json, LocalDate.parse(a.files.base().substring(0, 10)), root(a.files.fit()));
    }

    /**
     * @param fit      the parsed FIT file
     * @param json     the activity's Garmin Connect JSON (may be null)
     * @param date     the swim's date
     * @param dataRoot the account folder ({@code <root>/YYYY/MM/...}) whose pool swims give the reference pace
     */
    static Result correct(FitFile fit, Map<?, ?> json, LocalDate date, Path dataRoot) {
        if (!isOpenWater(fit, json)) return null;
        FitMessage s = fit.first(18);
        double watch = orZero(s.scaled(9)), strokes = orZero(s.num(10));
        double seconds = swimSeconds(fit, json);
        if (strokes <= 0 || seconds < 120) return null; // no swimming recorded (e.g. a GPS test standing on the shore)
        Double connect = number(at(json, "summaryDTO", "distance"));
        if (connect != null && connect > 0 && (watch <= 0 || Math.abs(connect / watch - 1) > EDITED)) {
            return new Result(connect, watch, seconds, Double.NaN, strokes, List.of(), true,
                    "Distance set by hand in Garmin Connect; the watch's GPS measured " + fmtDist(watch) + ".");
        }
        List<PoolSwim> refs = nearby(dataRoot, date);
        if (refs.isEmpty()) return null;
        double pace = median(refs.stream().map(PoolSwim::secondsPer100).toList());
        double distance = Math.round(seconds / pace * 100 / 10) * 10.0;
        if (distance < watch * (1 + MIN_GAIN)) return null; // the GPS kept up well enough
        List<LocalDate> dates = refs.stream().map(PoolSwim::date).sorted().toList();
        String explanation = "Estimated from " + fmtDur(seconds) + " of swimming at " + fmtDur(pace)
                + " per 100 m, the median pace of your " + refs.size() + " nearest consistent pool swims ("
                + (dates.size() == 1 ? dates.getFirst() : dates.getFirst() + " to " + dates.getLast())
                + "); the watch's GPS, which loses its fix under water, measured " + fmtDist(watch) + ".";
        return new Result(distance, watch, seconds, pace, strokes, dates, false, explanation);
    }

    static boolean isOpenWater(FitFile fit, Map<?, ?> json) {
        if (fit == null || fit.first(18) == null) return false;
        Object type = at(json, "activityTypeDTO", "typeKey");
        if (type != null) return "open_water_swimming".equals(type);
        FitMessage s = fit.first(18);
        return Double.valueOf(5).equals(s.num(5)) && Double.valueOf(18).equals(s.num(6)); // swimming / open_water
    }

    /** Garmin Connect's duration (which the user may have corrected), else the FIT session's timer time. */
    static double swimSeconds(FitFile fit, Map<?, ?> json) {
        Double d = number(at(json, "summaryDTO", "duration"));
        if (d != null && d > 0) return d;
        return orZero(fit.first(18).scaled(8));
    }

    // ------------------------------------------------------------------ reference pool swims

    private static final Map<Path, List<PoolSwim>> POOL = new ConcurrentHashMap<>();

    /** The {@link #NEARBY} consistent pool swims (of at least {@link #MIN_POOL} m) nearest to the date, within a year. */
    static List<PoolSwim> nearby(Path dataRoot, LocalDate date) {
        return poolSwims(dataRoot).stream()
                .filter(p -> Math.abs(ChronoUnit.DAYS.between(p.date(), date)) <= MAX_DAYS)
                .sorted(Comparator.comparingLong(p -> Math.abs(ChronoUnit.DAYS.between(p.date(), date))))
                .limit(NEARBY).toList();
    }

    /** Every consistent pool swim with a {@link SwimCorrection} file under the account folder (read once). */
    static List<PoolSwim> poolSwims(Path dataRoot) {
        return POOL.computeIfAbsent(dataRoot.toAbsolutePath().normalize(), OpenWaterCorrection::loadPoolSwims);
    }

    private static List<PoolSwim> loadPoolSwims(Path root) {
        List<PoolSwim> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> st = Files.walk(root, 4)) {
            for (Path p : (Iterable<Path>) st::iterator) {
                if (p.getParent() == null || !p.getParent().getFileName().toString().equals(SwimCorrection.DIR)
                        || !p.getFileName().toString().endsWith(".json")) continue;
                try {
                    if (DescribeGarminFile.Json.parse(Files.readString(p)) instanceof Map<?, ?> m
                            && m.get("poolLength") instanceof Number pool && m.get("self") instanceof Map<?, ?> s
                            && Boolean.TRUE.equals(s.get("consistent")) && pool.doubleValue() >= MIN_POOL
                            && s.get("strokesPerLength") instanceof Number spl && s.get("secondsPerLength") instanceof Number sec
                            && spl.doubleValue() > 0 && sec.doubleValue() > 0) {
                        out.add(new PoolSwim(LocalDate.parse(p.getFileName().toString().substring(0, 10)),
                                pool.doubleValue(), spl.doubleValue(), sec.doubleValue()));
                    }
                } catch (Exception ignored) {
                    // an unreadable file just isn't used as a reference
                }
            }
        } catch (IOException e) {
            System.err.println("Could not scan " + root + " for pool swims: " + e.getMessage());
        }
        return out;
    }

    private static Path root(Path fit) {
        return fit.getParent().getParent().getParent(); // <root>/YYYY/MM/file.fit
    }

    // ------------------------------------------------------------------ alternative methods (evaluation only)

    /** Strokes × the reference pool swims' median metres per stroke. */
    static double strokeEstimate(double strokes, List<PoolSwim> refs) {
        return refs.isEmpty() ? Double.NaN : strokes * median(refs.stream().map(PoolSwim::metresPerStroke).toList());
    }

    /**
     * GPS-track repair: drop fixes implying over 2.5 m/s, split the track where it has no fix for over 30 s, smooth each
     * piece (5-point moving average), and add the pieces' lengths to straight lines bridging the gaps.
     *
     * @return {bridged length, speed in the pieces with a fix × swim time, share of the time with a fix}, or null without fixes
     */
    static double[] gpsRepair(FitFile fit, double seconds) {
        List<double[]> pts = new ArrayList<>(); // t, x, y (metres)
        Double lat0 = null, lon0 = null;
        for (FitMessage r : fit.messages(20)) {
            Double la = r.scaled(0), lo = r.scaled(1), t = r.num(253);
            if (la == null || lo == null || t == null) continue;
            if (lat0 == null) {
                lat0 = la;
                lon0 = lo;
            }
            pts.add(new double[]{t, (lo - lon0) * 111320 * Math.cos(Math.toRadians(lat0)), (la - lat0) * 110540});
        }
        if (pts.size() < 2) return null;
        List<double[]> kept = new ArrayList<>(List.of(pts.getFirst()));
        for (double[] p : pts.subList(1, pts.size())) {
            double[] q = kept.getLast();
            if (dist(p, q) / Math.max(1, p[0] - q[0]) <= 2.5) kept.add(p);
        }
        List<List<double[]>> pieces = new ArrayList<>();
        for (double[] p : kept) {
            if (pieces.isEmpty() || p[0] - pieces.getLast().getLast()[0] > 30) pieces.add(new ArrayList<>());
            pieces.getLast().add(p);
        }
        double inside = 0, covered = 0, bridges = 0;
        double[] prevEnd = null;
        for (List<double[]> piece : pieces) {
            List<double[]> sm = new ArrayList<>();
            for (int i = 0; i < piece.size(); i++) {
                double x = 0, y = 0;
                int n = 0;
                for (int k = Math.max(0, i - 2); k <= Math.min(piece.size() - 1, i + 2); k++, n++) {
                    x += piece.get(k)[1];
                    y += piece.get(k)[2];
                }
                sm.add(new double[]{piece.get(i)[0], x / n, y / n});
            }
            for (int i = 1; i < sm.size(); i++) inside += dist(sm.get(i), sm.get(i - 1));
            covered += piece.getLast()[0] - piece.getFirst()[0];
            if (prevEnd != null) bridges += dist(prevEnd, sm.getFirst());
            prevEnd = sm.getLast();
        }
        return new double[]{inside + bridges, covered > 0 ? inside / covered * seconds : Double.NaN, covered / seconds};
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[1] - b[1], a[2] - b[2]);
    }

    // ------------------------------------------------------------------ evaluation

    /** Ground truth the user confirmed (date → metres). */
    static final Map<String, Double> TRUTH = Map.of("2026-08-30", 1000.0, "2026-09-18", 3800.0);

    public static void main(String[] args) throws IOException {
        String account = DescribeGarminFile.MAIN_ACCOUNT;
        for (int i = 0; i < args.length; i++) if (args[i].equals("--account")) account = args[++i].toLowerCase(Locale.ROOT);
        Path root = DescribeGarminFile.dataDir(account);
        Map<String, Path> fits = new LinkedHashMap<>(); // one per activity id (a renamed activity can have two copies)
        try (Stream<Path> st = Files.walk(root, 3)) {
            st.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}_\\d+_.*\\.fit")).sorted()
                    .forEach(p -> fits.putIfAbsent(p.getFileName().toString().split("_")[1], p));
        }
        System.out.println("Open-water swims of " + account + " (" + root + "); pool references: "
                + poolSwims(root).size() + " consistent swims in pools of " + (int) MIN_POOL + "+ m");
        String fmt = "%-10s %-40s %8s %8s %7s %7s %6s | %8s %8s %8s %8s %8s | %8s  %s%n";
        System.out.printf(fmt, "date", "name", "time", "FIT tmr", "watch", "Connect", "strks",
                "PACE*", "pace/tmr", "strokes", "gps-brdg", "gps-spd", "applied", "truth / error of PACE, pace/timer, strokes, gps-bridged, gps-speed");
        for (Path f : fits.values()) {
            Path jf = f.resolveSibling(f.getFileName().toString().replaceFirst("\\.fit$", ".json"));
            Map<?, ?> json = null;
            if (Files.exists(jf) && DescribeGarminFile.Json.parse(Files.readString(jf)) instanceof Map<?, ?> m) json = m;
            if (json != null && !"open_water_swimming".equals(at(json, "activityTypeDTO", "typeKey"))) continue;
            FitFile fit;
            try {
                fit = FitFile.parse(Files.readAllBytes(f));
            } catch (Exception e) {
                continue;
            }
            if (!isOpenWater(fit, json)) continue;
            LocalDate date = LocalDate.parse(f.getFileName().toString().substring(0, 10));
            FitMessage s = fit.first(18);
            double watch = orZero(s.scaled(9)), strokes = orZero(s.num(10)), timer = orZero(s.scaled(8));
            double seconds = swimSeconds(fit, json);
            Double connect = number(at(json, "summaryDTO", "distance"));
            List<PoolSwim> refs = nearby(root, date);
            double pace = refs.isEmpty() ? Double.NaN : median(refs.stream().map(PoolSwim::secondsPer100).toList());
            double byPace = seconds / pace * 100, byTimer = timer / pace * 100, byStrokes = strokeEstimate(strokes, refs);
            double[] gps = gpsRepair(fit, seconds);
            double bridged = gps == null ? Double.NaN : gps[0], gpsSpeed = gps == null ? Double.NaN : gps[1];
            Result r = correct(fit, json, date, root);
            String applied = r == null ? "-" : r.edited() ? "edited" : m0(r.distance());
            Double truth = TRUTH.get(date.toString());
            String note = truth == null ? "" : m0(truth) + ": " + err(byPace, truth) + ", " + err(byTimer, truth) + ", "
                    + err(byStrokes, truth) + ", " + err(bridged, truth) + ", " + err(gpsSpeed, truth);
            if (gps != null) note += (note.isEmpty() ? "" : "; ") + String.format(Locale.ROOT, "GPS fix %.0f%% of the time", gps[2] * 100);
            String name = String.valueOf(at(json, "activityName"));
            System.out.printf(fmt, date, name.length() > 40 ? name.substring(0, 40) : name, fmtDur(seconds), fmtDur(timer),
                    m0(watch), connect == null ? "" : m0(connect), m0(strokes), m0(byPace), m0(byTimer), m0(byStrokes),
                    m0(bridged), m0(gpsSpeed), applied, note);
            if (r != null) System.out.println("           " + r.explanation());
        }
        System.out.println("""
                PACE* = chosen: swim time (Garmin Connect duration) / median pool pace of the nearest consistent pool swims.
                pace/tmr = same with the FIT timer time; strokes = cycles x pool metres per stroke;
                gps-brdg = cleaned GPS track with straight lines over the gaps; gps-spd = speed where GPS had a fix x swim time.""");
    }

    private static String m0(double v) {
        return Double.isNaN(v) ? "n/a" : String.valueOf(Math.round(v));
    }

    private static String err(double v, double truth) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%+.0f%%", (v / truth - 1) * 100);
    }

    // ------------------------------------------------------------------ helpers

    private static Object at(Object root, String... path) {
        Object cur = root;
        for (String p : path) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(p);
        }
        return cur;
    }

    private static Double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static double orZero(Double d) {
        return d == null ? 0 : d;
    }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.sort(null);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }
}
