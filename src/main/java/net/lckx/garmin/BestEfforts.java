package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static net.lckx.garmin.DescribeGarminFile.esc;
import static net.lckx.garmin.DescribeGarminFile.jsStr;

/**
 * "Best efforts": the fastest 1 km, 5 km, 10 km, half marathon and marathon found inside every run (and the fastest
 * 20, 40 and 100 km inside every ride), how the best of each year evolved, this season's bests, and what a race
 * would take now (Riegel's formula on the best effort of the last 90 days).
 *
 * <p>An effort is the quickest stretch of the given distance anywhere in the activity: a sliding window over the
 * watch's cumulative distance against the clock, interpolated between track points. The distance comes from the TCX
 * ({@code DistanceMeters}, what the watch measured, also with a foot pod); without it the GPX track is measured point
 * to point. Jumps (a point further away than anyone runs or rides in that time) add no distance, and windows faster
 * than {@value #RUN_FASTEST} s/km running or {@value #RIDE_FASTEST} km/h riding are not counted. Elapsed time, so a
 * stop inside the window counts. Runs that were rides ({@link SportCorrection}) count as rides; indoor rides are left
 * out (their distance is virtual).
 *
 * <p>Reading every TCX file takes a while, so the efforts per activity are kept in {@value #CACHE_FILE}, keyed by the
 * file's path and modification time.
 * <pre>
 * java --enable-preview -cp target/classes net.lckx.garmin.BestEfforts [--account NAME]
 * </pre>
 */
public final class BestEfforts {
    static final String PAGE_FILE = "best-efforts.html";
    private static final String DATA_FILE = "best-efforts-data.js";
    private static final String CACHE_FILE = "best-efforts-cache.tsv";
    private static final String CACHE_VERSION = "best-efforts-cache v4";

    static final double[] RUN = {1000, 5000, 10000, 21097.5, 42195};
    static final double[] RIDE = {20000, 40000, 100000};
    /** Faster than 2:30 per km over any distance is not a run. */
    private static final double RUN_FASTEST = 150;
    /** Faster than this average is not a ride (or not one on the flat by a human). */
    private static final double RIDE_FASTEST = 65;
    /** Between two track points: faster than this is a jump of the distance, not movement (m/s). */
    private static final double RUN_JUMP = 12, RIDE_JUMP = 30;

    /** Same as the overview's records: activities whose numbers are not real (date + start time). */
    private static final Set<String> NOT_A_RECORD = Set.of("2012-12-13 20:33", "2012-12-17 13:02");

    private static final Pattern ACTIVITY_JSON = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})_(\\d+)_(.*)\\.json");

    private BestEfforts() {}

    /** One activity: what kind it is and, for runs and rides, its best effort per distance (NaN when too short). */
    record Act(long mtime, String date, String id, String name, String sport, String kind, String start,
               double metres, double secs, String src, double[] best, double[] at) {}

    public static void main(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--account")) DescribeGarminFile.useAccount(args[++i]);
            else {
                System.err.println("Usage: BestEfforts [--account NAME]");
                System.exit(1);
            }
        }
        System.out.println("Best efforts updated: " + write().toAbsolutePath());
    }

    static Path write() throws IOException {
        long t0 = System.nanoTime();
        Path dir = DescribeGarminFile.dataDir(DescribeGarminFile.account());
        Files.createDirectories(dir);
        SportCorrection.dropIfStale(dir.resolve(CACHE_FILE));
        Map<String, Act> cache = readCache(dir.resolve(CACHE_FILE));
        TreeMap<String, Act> acts = new TreeMap<>();
        List<Object[]> todo = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Matcher m = ACTIVITY_JSON.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                String key = dir.relativize(p).toString().replace('\\', '/');
                long mtime = mtime(p);
                Act a = cache.get(key);
                if (a != null && a.mtime == mtime) acts.put(key, a);
                else todo.add(new Object[]{key, p, m.group(1), m.group(2), mtime});
            }
        }
        AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
        Map<String, Act> loaded = new java.util.concurrent.ConcurrentHashMap<>();
        todo.parallelStream().forEach(t -> {
            try {
                loaded.put((String) t[0], load((Path) t[1], (String) t[2], (String) t[3], (long) t[4]));
            } catch (Exception e) {
                failed.incrementAndGet();
                System.err.println("Best efforts: could not read " + t[1] + ": " + e.getMessage());
            }
            int n = done.incrementAndGet();
            if (n % 500 == 0) System.out.printf("Best efforts: %d of %d activities read…%n", n, todo.size());
        });
        acts.putAll(loaded);
        writeCache(dir.resolve(CACHE_FILE), acts);
        writeData(dir.resolve(DATA_FILE), withoutDuplicates(acts.values()));
        Path page = dir.resolve(PAGE_FILE);
        replace(page, page());
        long runs = acts.values().stream().filter(a -> a.kind.equals("run") && !Double.isNaN(a.best[0])).count();
        long rides = acts.values().stream().filter(a -> a.kind.equals("ride") && !Double.isNaN(a.best[0])).count();
        System.out.printf("Best efforts: %d runs of 1 km or more, %d rides of 20 km or more (%d activities read, %d unreadable) in %.1fs.%n",
                runs, rides, todo.size(), failed.get(), (System.nanoTime() - t0) / 1e9);
        return page;
    }

    /** The newest of the activity's files: a re-downloaded TCX or GPX makes it read again. */
    private static long mtime(Path json) throws IOException {
        long t = Files.getLastModifiedTime(json).toMillis();
        for (String ext : new String[]{".tcx", ".gpx"}) {
            Path p = sibling(json, ext);
            if (Files.exists(p)) t = Math.max(t, Files.getLastModifiedTime(p).toMillis());
        }
        return t;
    }

    private static Path sibling(Path json, String ext) {
        return json.resolveSibling(json.getFileName().toString().replaceFirst("\\.json$", ext));
    }

    // ---- reading -------------------------------------------------------------------------------------------

    private static Act load(Path json, String date, String id, long mtime) throws IOException {
        Map<?, ?> j = (Map<?, ?>) DescribeGarminFile.Json.parse(Files.readString(json));
        String type = str(get(j, "activityTypeDTO", "typeKey"));
        String name = clean(str(get(j, "activityName")));
        String startLocal = str(get(j, "summaryDTO", "startTimeLocal"));
        String start = startLocal.length() >= 16 ? startLocal.substring(11, 16) : "";
        double metres = number(get(j, "summaryDTO", "distance")), secs = number(get(j, "summaryDTO", "duration"));
        String sport = SportCorrection.sport(SportCorrection.id(id), type, metres, secs);
        String group = DescribeGarminFile.Overview.sportGroup(sport);
        // Treadmill distances are the watch's estimate, not a measured course.
        String kind = sport.equals("treadmill_running") ? "" : group.equals("running") ? "run" : group.equals("cycling") ? "ride" : "";
        if (NOT_A_RECORD.contains(date + " " + start)) kind = "";
        // A short "run" at over 18 km/h on average was a ride (SportCorrection only looks at 5 km and more).
        if (kind.equals("run") && secs > 0 && metres / secs * 3.6 > SHORT_RIDE_KMH) kind = "";
        double[] dists = kind.equals("run") ? RUN : kind.equals("ride") ? RIDE : new double[0];
        double[] best = new double[dists.length], at = new double[dists.length];
        Arrays.fill(best, Double.NaN);
        Arrays.fill(at, Double.NaN);
        String src = "";
        if (dists.length > 0 && metres >= dists[0] * 0.9) {
            double[][] track = null;
            Path tcx = sibling(json, ".tcx"), gpx = sibling(json, ".gpx");
            if (Files.exists(tcx)) {
                track = tcx(Files.readAllBytes(tcx));
                src = "tcx";
            }
            if ((track == null || last(track[1]) < dists[0]) && Files.exists(gpx)) {
                double[][] g = gpx(Files.readString(gpx));
                if (g != null && (track == null || last(g[1]) > last(track[1]))) {
                    track = g;
                    src = "gpx";
                }
            }
            if (track != null) {
                boolean run = kind.equals("run");
                double[] c = cumulative(track[0], track[1], run && track.length > 2 ? track[2] : null,
                        run ? RUN_JUMP : RIDE_JUMP, run ? RUN_SUSTAINED : RIDE_SUSTAINED);
                double fastest = run ? RUN_FASTEST / 1000 : 3.6 / RIDE_FASTEST;  // seconds per metre
                for (int k = 0; k < dists.length; k++) {
                    double[] w = fastestWindow(track[0], c, dists[k], fastest);
                    if (w != null) {
                        best[k] = w[0];
                        at[k] = w[1];
                    }
                }
            } else src = "";
        }
        return new Act(mtime, date, id, name, sport, kind, start, metres, secs, src, best, at);
    }

    private static double last(double[] a) {
        return a.length == 0 ? 0 : a[a.length - 1];
    }

    /**
     * The quickest stretch of {@code dist} metres: [seconds, metres into the activity where it starts], or null.
     * The start is interpolated between the two points around it; windows quicker than {@code fastest} s/m are skipped.
     */
    static double[] fastestWindow(double[] t, double[] c, double dist, double fastest) {
        int n = c.length;
        if (n < 2 || c[n - 1] - c[0] < dist) return null;
        double bestT = Double.POSITIVE_INFINITY, bestAt = 0, min = dist * fastest;
        int i = 0;
        for (int j = 1; j < n; j++) {
            double x = c[j] - dist;
            if (x < c[0]) continue;
            while (i + 1 < j && c[i + 1] <= x) i++;
            if (c[i + 1] <= c[i]) continue;
            double ts = t[i] + (x - c[i]) / (c[i + 1] - c[i]) * (t[i + 1] - t[i]);
            double d = t[j] - ts;
            if (d >= min && d < bestT) {
                bestT = d;
                bestAt = x;
            }
        }
        // Slower than walking (a watch left running through a day) is not an effort.
        return bestT > dist * fastest * SLOWEST_FACTOR ? null : new double[]{bestT, bestAt};
    }

    /**
     * The distance after each point, with backward steps and jumps (faster than {@code jump} m/s between two points)
     * adding nothing. Stretches that are faster than {@code sustained} m/s over {@value #SUSTAIN_SECS} seconds (a run
     * that went on in a car or on a bike, GPS drifting away), and {@value #SUSTAIN_SECS} seconds either side, add
     * nothing either: a window across them only gets slower. With the running cadence ({@code cad}, steps per minute
     * of one foot, NaN where missing) the same goes for stretches faster than 4 m/s with steps longer than
     * {@value #MAX_STRIDE} m: GPS that wanders off while the feet keep their usual rhythm.
     */
    static double[] cumulative(double[] t, double[] d, double[] cad, double jump, double sustained) {
        int n = d.length;
        double[] c = new double[n];
        for (int i = 1; i < n; i++) {
            double dd = d[i] - d[i - 1], dt = t[i] - t[i - 1];
            if (dd < 0 || (dd > 10 && (dt <= 0 || dd / dt > jump))) dd = 0;
            c[i] = c[i - 1] + dd;
        }
        // Steps taken and seconds with a cadence reading, up to each point.
        double[] steps = new double[n], timed = new double[n];
        for (int i = 1; i < n; i++) {
            double dt = Math.max(0, t[i] - t[i - 1]);
            boolean has = cad != null && !Double.isNaN(cad[i]);
            steps[i] = steps[i - 1] + (has ? cad[i] * 2 / 60 * dt : 0);
            timed[i] = timed[i - 1] + (has ? dt : 0);
        }
        // Mark the too-fast stretches, then widen the marks by the same time either way.
        boolean[] fast = new boolean[n];
        int k = 0;
        for (int i = 1; i < n; i++) {
            while (k + 1 < i && t[i] - t[k + 1] >= SUSTAIN_SECS) k++;
            double dt = t[i] - t[k], dist = c[i] - c[k];
            if (dt < SUSTAIN_SECS) continue;
            boolean tooFast = dist / dt > sustained;
            if (!tooFast && cad != null && dist / dt > 4 && timed[i] - timed[k] >= .8 * dt) {
                double st = steps[i] - steps[k];
                tooFast = st <= 0 || dist / st > MAX_STRIDE;
            }
            if (tooFast) for (int q = k; q <= i; q++) fast[q] = true;
        }
        boolean any = false;
        for (boolean f : fast) any |= f;
        if (!any) return c;
        boolean[] wide = new boolean[n];
        int lo = 0, hi = 0;
        for (int i = 0; i < n; i++) {
            while (t[i] - t[lo] > SUSTAIN_SECS) lo++;
            if (hi < lo) hi = lo;
            while (hi + 1 < n && t[hi + 1] - t[i] <= SUSTAIN_SECS) hi++;
            for (int q = lo; q <= hi && !wide[i]; q++) wide[i] = fast[q];
        }
        double[] out = new double[n];
        for (int i = 1; i < n; i++) out[i] = out[i - 1] + (wide[i] || wide[i - 1] ? 0 : c[i] - c[i - 1]);
        return out;
    }

    private static final double SUSTAIN_SECS = 30;
    /** An effort at more than six times the fastest allowed time (15:00 per km running, 11 km/h riding) is not one. */
    private static final double SLOWEST_FACTOR = 6;
    /** Over {@value #SUSTAIN_SECS} seconds: 7 m/s is 2:23 per km, 25 m/s is 90 km/h. */
    private static final double RUN_SUSTAINED = 7, RIDE_SUSTAINED = 25;
    /** Metres per step: well beyond what anyone but a sprinter covers. */
    private static final double MAX_STRIDE = 2.1;
    private static final double SHORT_RIDE_KMH = 18;

    private static final byte[] TP = "<Trackpoint>".getBytes(StandardCharsets.US_ASCII),
            TP_END = "</Trackpoint>".getBytes(StandardCharsets.US_ASCII),
            TIME = "<Time>".getBytes(StandardCharsets.US_ASCII),
            DIST = "<DistanceMeters>".getBytes(StandardCharsets.US_ASCII),
            CADENCE = "RunCadence>".getBytes(StandardCharsets.US_ASCII);

    /**
     * [seconds, metres, running cadence (NaN where missing)] of the track points with both a time and a distance,
     * or null without any.
     */
    static double[][] tcx(byte[] b) {
        double[] ts = new double[1024], ds = new double[1024], cs = new double[1024];
        int n = 0, p = 0;
        double t0 = Double.NaN;
        while ((p = indexOf(b, TP, p, b.length)) >= 0) {
            int e = indexOf(b, TP_END, p, b.length);
            if (e < 0) break;
            int ti = indexOf(b, TIME, p, e), di = indexOf(b, DIST, p, e);
            p = e;
            if (ti < 0 || di < 0) continue;
            double t = isoSeconds(b, ti + TIME.length);
            double d = parseNumber(b, di + DIST.length);
            if (Double.isNaN(t) || Double.isNaN(d)) continue;
            if (Double.isNaN(t0)) t0 = t;
            if (n == ts.length) {
                ts = Arrays.copyOf(ts, n * 2);
                ds = Arrays.copyOf(ds, n * 2);
                cs = Arrays.copyOf(cs, n * 2);
            }
            int ci = indexOf(b, CADENCE, ti, e);
            ts[n] = t - t0;
            ds[n] = d;
            cs[n] = ci < 0 ? Double.NaN : parseNumber(b, ci + CADENCE.length);
            n++;
        }
        return n < 2 ? null : new double[][]{Arrays.copyOf(ts, n), Arrays.copyOf(ds, n), Arrays.copyOf(cs, n)};
    }

    private static final Pattern TRKPT = Pattern.compile("<trkpt[^>]*?lat=\"([-0-9.]+)\"[^>]*?lon=\"([-0-9.]+)\"[^>]*>(.*?)</trkpt>", Pattern.DOTALL);
    private static final Pattern GPX_TIME = Pattern.compile("<time>([^<]+)</time>");

    /** [seconds, metres measured point to point] of a GPX track, or null. */
    static double[][] gpx(String xml) {
        List<double[]> pts = new ArrayList<>();
        Matcher m = TRKPT.matcher(xml);
        while (m.find()) {
            Matcher tm = GPX_TIME.matcher(m.group(3));
            if (!tm.find()) continue;
            byte[] tb = tm.group(1).getBytes(StandardCharsets.US_ASCII);
            double t = isoSeconds(tb, 0);
            if (Double.isNaN(t)) continue;
            pts.add(new double[]{t, Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))});
        }
        if (pts.size() < 2) return null;
        double[] ts = new double[pts.size()], ds = new double[pts.size()];
        double t0 = pts.getFirst()[0];
        for (int i = 0; i < pts.size(); i++) {
            double[] q = pts.get(i);
            ts[i] = q[0] - t0;
            ds[i] = i == 0 ? 0 : ds[i - 1] + haversine(pts.get(i - 1), q);
        }
        return new double[][]{ts, ds};
    }

    private static double haversine(double[] a, double[] b) {
        double la1 = Math.toRadians(a[1]), la2 = Math.toRadians(b[1]);
        double dla = la2 - la1, dlo = Math.toRadians(b[2] - a[2]);
        double h = Math.sin(dla / 2) * Math.sin(dla / 2) + Math.cos(la1) * Math.cos(la2) * Math.sin(dlo / 2) * Math.sin(dlo / 2);
        return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    private static int indexOf(byte[] h, byte[] n, int from, int to) {
        outer:
        for (int i = from, end = to - n.length; i <= end; i++) {
            if (h[i] != n[0]) continue;
            for (int k = 1; k < n.length; k++) if (h[i + k] != n[k]) continue outer;
            return i;
        }
        return -1;
    }

    /** Seconds since the epoch of "2016-05-10T17:03:10.000Z" (the zone is ignored: only differences matter). */
    static double isoSeconds(byte[] b, int p) {
        if (p + 19 > b.length || b[p + 4] != '-' || b[p + 10] != 'T') return Double.NaN;
        try {
            int y = digits(b, p, 4), mo = digits(b, p + 5, 2), d = digits(b, p + 8, 2);
            int h = digits(b, p + 11, 2), mi = digits(b, p + 14, 2), s = digits(b, p + 17, 2);
            double frac = 0;
            int q = p + 19;
            if (q < b.length && b[q] == '.') {
                double f = 0.1;
                for (q++; q < b.length && b[q] >= '0' && b[q] <= '9'; q++, f /= 10) frac += (b[q] - '0') * f;
            }
            return LocalDate.of(y, mo, d).toEpochDay() * 86400.0 + h * 3600 + mi * 60 + s + frac;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static int digits(byte[] b, int p, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            int c = b[p + i] - '0';
            if (c < 0 || c > 9) throw new NumberFormatException();
            v = v * 10 + c;
        }
        return v;
    }

    private static double parseNumber(byte[] b, int p) {
        int e = p;
        while (e < b.length && b[e] != '<') e++;
        try {
            return Double.parseDouble(new String(b, p, e - p, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static Object get(Object o, String... path) {
        for (String k : path) {
            if (!(o instanceof Map<?, ?> m)) return null;
            o = m.get(k);
        }
        return o;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String clean(String s) {
        return s.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    // ---- cache ---------------------------------------------------------------------------------------------

    private static Map<String, Act> readCache(Path f) {
        Map<String, Act> m = new HashMap<>();
        if (!Files.exists(f)) return m;
        try {
            List<String> rows = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (rows.isEmpty() || !rows.getFirst().equals(CACHE_VERSION)) return m;
            for (String row : rows.subList(1, rows.size())) {
                String[] c = row.split("\t", -1);
                if (c.length < 12) continue;
                String[] e = c[11].isEmpty() ? new String[0] : c[11].split(" ");
                double[] best = new double[e.length], at = new double[e.length];
                for (int i = 0; i < e.length; i++) {
                    if (e[i].equals("-")) {
                        best[i] = Double.NaN;
                        at[i] = Double.NaN;
                    } else {
                        String[] x = e[i].split("@");
                        best[i] = Double.parseDouble(x[0]);
                        at[i] = Double.parseDouble(x[1]);
                    }
                }
                m.put(c[0], new Act(Long.parseLong(c[1]), c[2], c[3], c[4], c[5], c[6], c[7], Double.parseDouble(c[8]),
                        Double.parseDouble(c[9]), c[10], best, at));
            }
        } catch (Exception e) {
            System.err.println("Best efforts: cache unreadable, reading every activity again (" + e.getMessage() + ")");
            m.clear();
        }
        return m;
    }

    private static void writeCache(Path f, Map<String, Act> acts) throws IOException {
        StringBuilder b = new StringBuilder(CACHE_VERSION).append('\n');
        for (var e : acts.entrySet()) {
            Act a = e.getValue();
            b.append(e.getKey()).append('\t').append(a.mtime).append('\t').append(a.date).append('\t').append(a.id)
                    .append('\t').append(a.name).append('\t').append(a.sport).append('\t').append(a.kind).append('\t')
                    .append(a.start).append('\t').append(a.metres).append('\t').append(a.secs).append('\t').append(a.src)
                    .append('\t');
            for (int i = 0; i < a.best.length; i++) {
                if (i > 0) b.append(' ');
                b.append(Double.isNaN(a.best[i]) ? "-" : fmt(a.best[i], 1) + "@" + fmt(a.at[i], 0));
            }
            b.append('\n');
        }
        replace(f, b.toString());
    }

    // ---- output --------------------------------------------------------------------------------------------

    /**
     * One activity per outing: a ride recorded on both the bike computer and the watch shows up twice, on the same
     * day and starting within {@value #SAME_OUTING_MIN} minutes. The longer recording stays.
     */
    private static List<Act> withoutDuplicates(Iterable<Act> all) {
        List<Act> acts = new ArrayList<>();
        for (Act a : all) if (!a.kind.isEmpty() && a.best.length > 0 && !Double.isNaN(a.best[0])) acts.add(a);
        acts.sort(java.util.Comparator.comparing(Act::date).thenComparing(Act::start));
        List<Act> out = new ArrayList<>();
        for (Act a : acts) {
            Act prev = null;
            for (int i = out.size() - 1; i >= 0 && out.get(i).date.equals(a.date); i--) {
                Act o = out.get(i);
                if (o.kind.equals(a.kind) && Math.abs(minutes(o.start) - minutes(a.start)) <= SAME_OUTING_MIN) {
                    prev = o;
                    break;
                }
            }
            if (prev == null) out.add(a);
            else if (a.secs > prev.secs) out.set(out.indexOf(prev), a);
        }
        return out;
    }

    private static final int SAME_OUTING_MIN = 15;

    private static int minutes(String hhmm) {
        return hhmm.length() < 5 ? -999 : Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    private static void writeData(Path f, Iterable<Act> acts) throws IOException {
        StringBuilder run = new StringBuilder(), ride = new StringBuilder();
        Map<String, String> icons = new TreeMap<>();
        for (Act a : acts) {
            if (a.kind.isEmpty() || a.best.length == 0 || Double.isNaN(a.best[0])) continue;
            StringBuilder b = a.kind.equals("run") ? run : ride;
            // [date, garmin id, name, sport, start, km, seconds, [best seconds per distance], [km where it starts]]
            b.append(b.isEmpty() ? "\n" : ",\n").append('[').append(jsStr(a.date)).append(',').append(jsStr(a.id)).append(',')
                    .append(jsStr(a.name)).append(',').append(jsStr(a.sport)).append(',').append(jsStr(a.start)).append(',')
                    .append(fmt(a.metres / 1000, 2)).append(',').append(fmt(a.secs, 0)).append(",[");
            for (int i = 0; i < a.best.length; i++) b.append(i > 0 ? "," : "").append(Double.isNaN(a.best[i]) ? "0" : fmt(a.best[i], 1));
            b.append("],[");
            for (int i = 0; i < a.at.length; i++) b.append(i > 0 ? "," : "").append(Double.isNaN(a.at[i]) ? "0" : fmt(a.at[i] / 1000, 2));
            b.append("]]");
            icons.put(a.sport, DescribeGarminFile.sportIcon(a.sport));
        }
        StringBuilder js = new StringBuilder("window.BEST={today:").append(jsStr(LocalDate.now().toString()))
                .append(",run:[").append(run).append("\n],ride:[").append(ride).append("\n],icons:{");
        boolean first = true;
        for (var i : icons.entrySet()) {
            js.append(first ? "" : ",").append(jsStr(i.getKey())).append(':').append(jsStr(i.getValue()));
            first = false;
        }
        js.append("}};\n");
        replace(f, js.toString());
    }

    private static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static void replace(Path f, String content) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String page() {
        String label = DescribeGarminFile.accountLabelOf(DescribeGarminFile.account());
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + DescribeGarminFile.favicon("#d95926")
                + "<title>Best efforts · " + esc(label) + "</title>"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"><link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:wght@400;700"
                + "&family=Big+Shoulders+Display:wght@500;700;800&display=swap\">"
                + "<style>" + CSS + "</style>" + Site.HEAD + "</head><body>"
                + BODY.replace("{{NAV}}", Site.nav(PAGE_FILE, 0))
                .replace("{{LABEL}}", esc(label))
                .replace("{{UPDATED}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH).format(LocalDateTime.now()))
                .replace("{{UPDATED_NL}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("nl")).format(LocalDateTime.now()))
                + "<script src=\"" + DATA_FILE + "\"></script>"
                + "<script>" + JS + "</script></body></html>";
    }

    private static final String CSS = """
            :root{--night:#0a1220;--deep:#0f1a2e;--rule:#1d2a44;--fog:#a9b4ca;--moon:#eef1f7;--dim:#76829b;
              --run:#d95926;--ride:#3987e5;
              --display:'Big Shoulders Display',Impact,'Arial Narrow',sans-serif;--text:'Atkinson Hyperlegible',system-ui,sans-serif;color-scheme:dark}
            *{box-sizing:border-box}
            html,body{margin:0;background:var(--night);color:var(--fog);font:17px/1.55 var(--text)}
            a{color:var(--moon)}
            :focus-visible{outline:2px solid var(--moon);outline-offset:2px}
            h1{font:800 clamp(52px,9vw,112px)/.86 var(--display);color:var(--moon);margin:0 0 18px;letter-spacing:-.005em}
            h2{font:700 clamp(40px,6vw,64px)/.95 var(--display);color:var(--moon);margin:0 0 12px;letter-spacing:.005em}
            .lede{max-width:62ch;margin:0 0 28px}
            .top{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px 24px;padding:16px 28px;color:var(--fog)}
            .title{font:800 22px/1 var(--display);color:var(--moon);margin:0;letter-spacing:.02em;white-space:nowrap}
            .title span{color:var(--dim);font-weight:500}.title .t{color:var(--moon);font-weight:800}
            .top .site-nav{flex:1 1 auto;justify-content:flex-end;--site-active-bg:var(--run);--site-active-fg:var(--night)}
            main{max-width:1180px;margin:0 auto;padding:0 28px}
            .hero{padding:64px 0 20px}
            section.part{padding:100px 0 20px}
            /* ---- the stopwatches */
            .watches{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,190px),1fr));gap:30px 30px;margin:40px 0 0}
            @media (min-width:1100px){#watches{grid-template-columns:repeat(5,minmax(0,1fr))}}
            .watch{border-top:2px solid var(--c,var(--run));padding-top:14px;min-width:0}
            .watch h3{font:700 22px/1 var(--display);color:var(--fog);margin:0;letter-spacing:.02em}
            .watch .time{font:800 clamp(46px,5vw,74px)/.9 var(--display);color:var(--moon);margin:10px 0 4px;
              font-variant-numeric:tabular-nums;letter-spacing:-.01em;white-space:nowrap}
            .watch .time.none{color:var(--rule)}
            .watch .pace{font:700 22px/1.1 var(--display);color:var(--c,var(--run));margin:0 0 10px;letter-spacing:.02em}
            .watch .src{font-size:14px;line-height:1.45;margin:0}
            .watch .src a{text-decoration-color:var(--dim);text-underline-offset:3px}
            .watch .src span{color:var(--dim)}
            .watch .season{font-size:14px;line-height:1.45;margin:10px 0 0;color:var(--dim)}
            .watch .season b{color:var(--fog);font-weight:700;font-variant-numeric:tabular-nums}
            /* ---- prediction */
            .say{font:700 clamp(32px,4.4vw,52px)/1.08 var(--display);color:var(--moon);max-width:24ch;margin:0 0 30px}
            .say em{font-style:normal;color:var(--run)}
            .preds{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,180px),1fr));gap:24px 40px;margin:0;padding:0}
            .preds div{border-top:1px solid var(--rule);padding-top:12px}
            .preds dt{font-size:14px;color:var(--dim)}
            .preds dd{margin:2px 0 0;font:700 40px/1.05 var(--display);color:var(--moon);font-variant-numeric:tabular-nums}
            .preds dd small{display:block;font:400 14px/1.4 var(--text);color:var(--fog);margin-top:4px}
            .note{font-size:14px;color:var(--dim);max-width:70ch;margin:22px 0 0}
            /* ---- season table */
            .scroll{overflow-x:auto;margin:0 -4px;padding:0 4px}
            table{border-collapse:collapse;width:100%;min-width:560px;font-variant-numeric:tabular-nums}
            th,td{text-align:left;padding:12px 16px 12px 0;border-bottom:1px solid var(--rule);vertical-align:baseline}
            thead th{font-size:14px;color:var(--dim);font-weight:400}
            tbody th{font:700 22px/1 var(--display);color:var(--moon);letter-spacing:.02em}
            td b{font:700 26px/1 var(--display);color:var(--moon);letter-spacing:.02em}
            td small{display:block;font-size:13px;color:var(--dim);margin-top:3px}
            td a{color:inherit}
            .gap{color:var(--fog);font-size:15px}
            .gap.best{color:var(--run);font-weight:700}
            /* ---- charts */
            .charts{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,420px),1fr));gap:44px 48px}
            .chart h3{font:700 26px/1 var(--display);color:var(--moon);margin:0}
            .chart p{margin:4px 0 10px;font-size:14px;color:var(--dim)}
            .chart svg{display:block;width:100%;height:auto;overflow:visible;cursor:crosshair}
            .chart svg a:focus{outline:none}
            .chart svg a:focus-visible circle{stroke:var(--moon);stroke-width:3}
            .keys{display:flex;flex-wrap:wrap;gap:6px 22px;margin:0 0 30px;padding:0;list-style:none;font-size:14px}
            .keys li{display:flex;align-items:center;gap:8px}
            .keys i{display:inline-block;border-radius:50%}
            .ride{--c:var(--ride)}
            /* ---- best per year */
            #perYear{margin-top:72px}
            #perYear h3{font:700 30px/1 var(--display);color:var(--moon);margin:0 0 6px;letter-spacing:.01em}
            #perYear > p{margin:0 0 14px;font-size:14px;color:var(--dim)}
            table.py{min-width:600px}
            table.py th,table.py td{padding:9px 14px 9px 0}
            table.py tbody th{font-size:20px}
            table.py td a{text-decoration:none;display:inline-block;padding:2px 8px;margin-left:-8px;border-radius:6px;font:700 22px/1.1 var(--display);color:var(--moon);letter-spacing:.02em}
            table.py td a:hover{background:var(--deep);text-decoration:underline;text-decoration-color:var(--dim)}
            table.py td a small{display:block;font:400 12px/1.3 var(--text);color:var(--dim);letter-spacing:0;margin-top:2px}
            table.py td.pb a{background:rgba(217,89,38,.16);box-shadow:inset 0 0 0 1px var(--run)}
            table.py td.pb a small{color:var(--fog)}
            table.py td.none{color:var(--rule)}
            footer{border-top:1px solid var(--rule);margin-top:120px;padding:22px 0 40px;font-size:14px;color:var(--dim)}
            footer a{color:var(--fog);margin-right:16px}
            #tip{position:fixed;z-index:1000;pointer-events:none;background:var(--deep);border:1px solid var(--rule);border-radius:8px;
              padding:9px 12px;font-size:14px;line-height:1.45;color:var(--fog);max-width:320px;box-shadow:0 8px 30px rgba(0,0,0,.45)}
            #tip b{color:var(--moon)}
            @media (prefers-reduced-motion:no-preference){
              .watch{animation:rise .7s cubic-bezier(.2,.7,.2,1) both}
              .watch:nth-child(2){animation-delay:.06s}.watch:nth-child(3){animation-delay:.12s}
              .watch:nth-child(4){animation-delay:.18s}.watch:nth-child(5){animation-delay:.24s}
              @keyframes rise{from{opacity:0;transform:translateY(14px)}to{opacity:1;transform:none}}
            }
            @media (max-width:700px){
              .top{padding:12px 16px}.top .site-nav{justify-content:flex-start;min-width:0;max-width:100%}main{padding:0 16px}
              .hero{padding-top:36px}section.part{padding-top:72px}
              .watches{grid-template-columns:repeat(2,minmax(0,1fr));gap:26px 20px}
              .watch .time{font-size:clamp(34px,10.5vw,64px)}
              .watch .pace{font-size:19px}
            }
            """;

    private static final String BODY = """
            <header class="top"><p class="title"><span class="t" data-nl="Beste prestaties">Best efforts</span> <span>{{LABEL}}</span></p>
              {{NAV}}</header>
            <main>
              <section class="hero" aria-labelledby="heroTitle">
                <h1 id="heroTitle" data-nl="Je snelste, tot nu toe">Your fastest, so far</h1>
                <p class="lede" id="heroLede"></p>
                <div class="watches" id="watches"></div>
              </section>
              <section class="part" id="now" aria-labelledby="nowTitle">
                <h2 id="nowTitle" data-nl="Wat een wedstrijd nu zou duren">What a race would take now</h2>
                <p class="say" id="say"></p>
                <dl class="preds" id="preds"></dl>
                <p class="note" id="predNote"></p>
              </section>
              <section class="part" id="season" aria-labelledby="seasonTitle">
                <h2 id="seasonTitle" data-nl="Dit seizoen">This season</h2>
                <p class="lede" id="seasonLede"></p>
                <div class="scroll"><table id="seasonTable"></table></div>
              </section>
              <section class="part" id="years" aria-labelledby="yearsTitle">
                <h2 id="yearsTitle" data-nl="Jaar na jaar">Year by year</h2>
                <p class="lede" data-nl="Elke stip is het snelste stuk van die afstand binnen één loop; de lijn verbindt de beste van elk jaar.
                  De tempo-as staat omgekeerd, zodat sneller hoger is. Wijs een stip aan om hem te lezen, klik om de loop te openen.">Every dot is the fastest stretch of that distance inside one run; the line joins the best of each year.
                  The pace axis is turned around so that faster is higher. Point at a dot to read it, click to open the run.</p>
                <ul class="keys" id="runKeys"></ul>
                <div class="charts" id="runCharts"></div>
                <div id="perYear" hidden>
                  <h3 data-nl="Beste per jaar">Best per year</h3>
                  <p data-nl="De snelste tijd van elk jaar per afstand. Klik op een tijd om de loop te openen; de omrande tijd is je beste ooit.">The fastest time of each year per distance. Click a time to open the run; the outlined time is your best ever.</p>
                  <div class="scroll"><table class="py" id="perYearTable"></table></div>
                </div>
              </section>
              <section class="part ride" id="bike" aria-labelledby="bikeTitle" hidden>
                <h2 id="bikeTitle" data-nl="Op de fiets">On the bike</h2>
                <p class="lede" data-nl="De snelste 20, 40 en 100 km binnen elke buitenrit, als gemiddelde snelheid. Ritten binnen tellen niet mee:
                  hun afstand verzint de fietstrainer.">The fastest 20, 40 and 100 km inside every ride outdoors, as average speed. Indoor rides are left out:
                  their distance is made up by the trainer.</p>
                <div class="watches" id="rideWatches"></div>
                <div class="charts" id="rideCharts" style="margin-top:56px"></div>
              </section>
              <footer><span data-nl="Gemaakt uit je Garmin-bestanden op {{UPDATED_NL}}. De afstand is wat het horloge mat (TCX), of het GPS-spoor als het
                horloge er geen heeft. Tijden zijn bruto: een stop binnen het stuk telt mee.">Built from your Garmin files on {{UPDATED}}. Distance is what the watch measured (TCX), or the GPS track when the
                watch has none. Times are elapsed: a stop inside the stretch counts.</span></footer>
            </main>
            <div id="tip" hidden></div>
            """;

    private static final String JS = """
            (function(){
            'use strict';
            var B=window.BEST||{run:[],ride:[],icons:{}},ICONS=B.icons||{};
            var NL=window.LANG=='nl',L=window.L||function(e){return e};
            if(NL)document.title=document.title.replace('Best efforts','Beste prestaties');
            var MONTHS=NL?['jan','feb','mrt','apr','mei','jun','jul','aug','sep','okt','nov','dec']:['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
            var RUN=[{m:1000,label:'1 km',lower:'1 km',short:'1 km'},{m:5000,label:'5 km',lower:'5 km',short:'5 km'},{m:10000,label:'10 km',lower:'10 km',short:'10 km'},
              {m:21097.5,label:L('Half marathon','Halve marathon'),lower:L('half marathon','halve marathon'),short:L('Half','Halve')},
              {m:42195,label:L('Marathon','Marathon'),lower:L('marathon','marathon'),short:L('Marathon','Marathon')}];
            var RIDE=[{m:20000,label:'20 km'},{m:40000,label:'40 km'},{m:100000,label:'100 km'}];
            var RUNC='#d95926',RIDEC='#3987e5';
            function dayNo(s){return Date.UTC(+s.slice(0,4),+s.slice(5,7)-1,+s.slice(8,10))/864e5}
            var TODAY=B.today||new Date().toISOString().slice(0,10),TN=dayNo(TODAY),THIS_YEAR=+TODAY.slice(0,4);
            function fmtDate(s,short){var y=s.slice(0,4);return +s.slice(8,10)+' '+MONTHS[+s.slice(5,7)-1]+(short&&+y==THIS_YEAR?'':' '+y)}
            function two(n){return String(n).padStart(2,'0')}
            function fmtT(s){s=Math.round(s);var h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;return h?h+':'+two(m)+':'+two(x):m+':'+two(x)}
            // Predictions are estimates: to the minute above an hour ("1:38"), to five seconds below.
            function fmtRough(s){if(s>=3600){var m=Math.round(s/60);return Math.floor(m/60)+' h '+two(m%60)}return fmtT(Math.round(s/5)*5)}
            function fmtPace(secPerKm){return fmtT(secPerKm)}
            function href(a){return a.date.slice(0,4)+'/'+a.date.slice(5,7)+'/'+a.date+'-report.html#a'+a.id}
            function esc(s){return String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
            function dec(x){return NL?String(x).replace('.',','):String(x)}
            function km(v){return dec((Math.round(v*10)/10).toFixed(1))}
            function kmh(e){return dec(speed(e).toFixed(1))}
            var PERKM=' /km',KMH=L('km/h','km/u');
            var tip=document.getElementById('tip');
            function showTip(html,x,y){tip.innerHTML=html;tip.hidden=false;var w=tip.offsetWidth,h=tip.offsetHeight;
              tip.style.left=Math.max(8,Math.min(innerWidth-w-8,x+14))+'px';tip.style.top=(y+h+20>innerHeight?y-h-14:y+16)+'px'}
            function hideTip(){tip.hidden=true}

            // ---- every effort per distance
            function efforts(list,k,dist){
              return list.filter(function(r){return r[7][k]>0}).map(function(r){
                return {date:r[0],id:r[1],name:r[2],sport:r[3],start:r[4],km:r[5],secs:r[6],t:r[7][k],at:r[8][k],n:dayNo(r[0]),m:dist.m,dist:dist}})}
            function best(list){return list.reduce(function(b,e){return !b||e.t<b.t?e:b},null)}
            var RUNS=RUN.map(function(d,k){return efforts(B.run,k,d)}),RIDES=RIDE.map(function(d,k){return efforts(B.ride,k,d)});
            function name(e){return (ICONS[e.sport]||'')+' '+esc(e.name||L('Run','Loop'))}
            function where(e){
              if(e.km-e.m/1000<.15)return L('the whole ','de hele ')+km(e.km)+' km';
              return 'km '+km(e.at)+L(' to ',' tot ')+km(e.at+e.m/1000)+L(' of ',' van ')+km(e.km)+' km'}
            function pace(e){return e.t/(e.m/1000)}
            function speed(e){return e.m/1000/(e.t/3600)}
            function inYear(e){return +e.date.slice(0,4)==THIS_YEAR}
            function last12(e){return e.n>TN-365.25}
            var runCount=B.run.length;

            // ---- hero: the current bests as stopwatches
            var rc0=runCount.toLocaleString('en-US').replace(/,/g,' ');
            document.getElementById('heroLede').textContent=runCount
              ?(NL?'Het snelste stuk van elke afstand, waar dan ook in je '+rc0+' loopjes sinds '+fmtDate(B.run[0][0])
                  +', niet alleen de wedstrijden: je snelste 5 km kan het midden van een lange zondagsloop zijn. Bruto tijd, uit wat het horloge mat.'
                :'The quickest stretch of each distance anywhere inside your '+rc0
                  +' runs since '+fmtDate(B.run[0][0])+', not only the races: your fastest 5 km may be the middle of a long Sunday run. Elapsed time, from what the watch measured.')
              :L('No runs of 1 km or more found yet.','Nog geen loopjes van 1 km of meer gevonden.');
            function watch(e,d,mode,list){
              var main=e?(mode=='speed'?kmh(e):fmtT(e.t)):'—';
              var s='<article class="watch"><h3>'+d.label+'</h3><p class="time'+(e?'':' none')+'">'+main+'</p>';
              if(!e)return s+'<p class="src"><span>'+(mode=='speed'?L('Not ridden that far yet','Nog niet zo ver gefietst'):L('Not run that far yet','Nog niet zo ver gelopen'))+'</span></p></article>';
              s+='<p class="pace">'+(mode=='speed'?KMH+', '+fmtT(e.t):fmtPace(pace(e))+PERKM)+'</p>'
                +'<p class="src"><a href="'+href(e)+'">'+name(e)+'</a><br><span>'+fmtDate(e.date)+', '+where(e)+'</span></p>';
              var y=best(list.filter(inYear)),l=best(list.filter(last12));
              var v=function(x){return x?(mode=='speed'?kmh(x)+' '+KMH:fmtT(x.t)):L('none yet','nog geen')};
              s+='<p class="season">'+L('This year','Dit jaar')+' <b>'+v(y)+'</b><br>'+L('Last 12 months','Laatste 12 maanden')+' <b>'+v(l)+'</b></p>';
              return s+'</article>'}
            document.getElementById('watches').innerHTML=RUN.map(function(d,k){return watch(best(RUNS[k]),d,'pace',RUNS[k])}).join('');

            // ---- what a race would take now: Riegel, T2 = T1 x (D2/D1)^1.06, from the best effort of the last 90 days
            (function(){
              var say=document.getElementById('say'),preds=document.getElementById('preds'),note=document.getElementById('predNote');
              var recent=[];RUNS.forEach(function(l,k){if(k>=1)l.forEach(function(e){if(e.n>TN-90)recent.push(e)})});
              function riegel(e,m){return e.t*Math.pow(m/e.m,1.06)}
              var ref=recent.reduce(function(b,e){return !b||riegel(e,10000)<riegel(b,10000)?e:b},null);
              if(!ref){
                var lastRun=B.run.length?B.run[B.run.length-1][0]:null;
                say.textContent=L('No runs of 5 km or more in the last 90 days, so no estimate for now.','Geen loopjes van 5 km of meer in de laatste 90 dagen, dus voorlopig geen schatting.');
                note.textContent=lastRun?L('Your last run was on ','Je laatste loop was op ')+fmtDate(lastRun)
                  +L('. The estimate comes back with the next run of 5 km or more.','. De schatting komt terug met de volgende loop van 5 km of meer.'):'';
                return}
              var k=RUN.indexOf(ref.dist),target=RUN[k==4?3:k+1];
              say.innerHTML=NL?'Op basis van je '+ref.dist.lower+' van '+fmtDate(ref.date,true)+' zou een <em>'+target.lower+'</em> nu ongeveer <em>'+fmtRough(riegel(ref,target.m))+'</em> duren.'
                :'From your '+ref.dist.lower+' on '+fmtDate(ref.date,true)+', a <em>'+target.lower+'</em> now would take about <em>'+fmtRough(riegel(ref,target.m))+'</em>.';
              preds.innerHTML=RUN.slice(1).map(function(d){
                var same=d===ref.dist,t=same?ref.t:riegel(ref,d.m);
                return '<div><dt>'+d.label+'</dt><dd>'+(same?fmtT(t):fmtRough(t))+'<small>'+(same?L('what you ran','wat je liep'):fmtPace(t/(d.m/1000))+PERKM)+'</small></dd></div>'}).join('');
              note.innerHTML=NL?'Op basis van <a href="'+href(ref)+'">'+name(ref)+'</a>: '+ref.dist.lower+' in '+fmtT(ref.t)+' ('+where(ref)+'), de prestatie van de laatste 90 dagen die '
                +'naar de snelste tijden wijst. De regel van Riegel (T2 = T1 × (D2/D1)<sup>1,06</sup>) gaat ervan uit dat je voor de langere afstand getraind hebt; '
                +'voor een marathon is hij meestal aan de optimistische kant, tenzij je lange duurlopen echt lang zijn. Het stuk kwam uit een gewone loop, dus op een uitgeruste wedstrijddag ben je misschien sneller.'
                :'Based on <a href="'+href(ref)+'">'+name(ref)+'</a>: '+ref.dist.lower+' in '+fmtT(ref.t)+' ('+where(ref)+'), the effort of the last 90 days that '
                +'points to the fastest times. Riegel\\'s rule (T2 = T1 × (D2/D1)<sup>1.06</sup>) assumes you have trained for the longer distance; '
                +'for a marathon it is usually on the hopeful side unless your long runs are long. The stretch came from inside a run, so a rested race day may well be quicker.';
            })();

            // ---- this season against the all-time best
            (function(){
              var rows=RUN.map(function(d,k){return {d:d,all:best(RUNS[k]),y:best(RUNS[k].filter(inYear)),l:best(RUNS[k].filter(last12))}}).filter(function(r){return r.all});
              var t=document.getElementById('seasonTable');
              if(!rows.length){t.parentNode.hidden=true;return}
              function cell(e){return e?'<b>'+fmtT(e.t)+'</b><small><a href="'+href(e)+'">'+fmtDate(e.date)+'</a>, '+fmtPace(pace(e))+PERKM+'</small>':'<span class="gap">'+L('no run that long','geen loop zo lang')+'</span>'}
              function gap(e,all){if(!e)return '';if(e===all)return '<span class="gap best">'+L('Your best ever','Je beste ooit')+'</span>';
                var p=(e.t/all.t-1)*100;return '<span class="gap">'+fmtT(e.t-all.t)+L(' slower than your best (',' trager dan je beste (')+dec(p<10?p.toFixed(1):Math.round(p))+'%)</span>'}
              t.innerHTML='<thead><tr><th scope="col">'+L('Distance','Afstand')+'</th><th scope="col">'+THIS_YEAR+'</th><th scope="col">'+L('Last 12 months','Laatste 12 maanden')
                +'</th><th scope="col">'+L('How close','Hoe dichtbij')+'</th><th scope="col">'+L('All-time best','Beste ooit')+'</th></tr></thead><tbody>'
                +rows.map(function(r){return '<tr><th scope="row">'+r.d.label+'</th><td>'+cell(r.y)+'</td><td>'+cell(r.l)+'</td><td>'+gap(r.l,r.all)+'</td><td>'+cell(r.all)+'</td></tr>'}).join('')+'</tbody>';
              var close=rows.filter(function(r){return r.l}).map(function(r){return {r:r,p:r.l.t/r.all.t}}).sort(function(a,b){return a.p-b.p})[0];
              document.getElementById('seasonLede').textContent=close
                ?(close.p<=1.0001?L('In the last 12 months you set your best ever over ','In de laatste 12 maanden liep je je beste tijd ooit op de ')+close.r.d.lower+'.'
                  :NL?'In de laatste 12 maanden kwam je het dichtst bij je beste op de '+close.r.d.lower+': '+fmtT(close.r.l.t)+' tegenover '+fmtT(close.r.all.t)+' in '+close.r.all.date.slice(0,4)+'.'
                  :'Over the last 12 months you came closest to your best over '+close.r.d.lower+': '+fmtT(close.r.l.t)+' against '+fmtT(close.r.all.t)+' in '+close.r.all.date.slice(0,4)+'.')
                :L('No runs in the last 12 months.','Geen loopjes in de laatste 12 maanden.');
            })();

            // ---- year by year: one chart per distance
            function niceStep(range,steps,cands){for(var i=0;i<cands.length;i++)if(range/cands[i]<=steps)return cands[i];return cands[cands.length-1]}
            function chart(host,list,d,color,mode){
              if(list.length<2)return;
              var val=mode=='speed'?speed:pace,W=520,H=250,L0=mode=='speed'?40:46,R=8,T0=10,BT=26;
              var vals=list.map(val).sort(function(a,b){return a-b});
              var years={};list.forEach(function(e){var y=e.date.slice(0,4);if(!years[y]||e.t<years[y].t)years[y]=e});
              var yb=Object.keys(years).sort().map(function(y){return years[y]});
              var goodV,badV;
              if(mode=='speed'){goodV=vals[vals.length-1];badV=Math.min(vals[Math.floor(vals.length*.06)],Math.min.apply(null,yb.map(val)))}
              else{goodV=vals[0];badV=Math.max(vals[Math.min(vals.length-1,Math.floor(vals.length*.94))],Math.max.apply(null,yb.map(val)))}
              var pad=Math.abs(goodV-badV)*.08||5;if(mode=='speed'){goodV+=pad;badV-=pad}else{goodV-=pad;badV+=pad}
              function Y(v){return T0+(goodV-v)/(goodV-badV)*(H-BT-T0)}
              var Y0=+list[0].date.slice(0,4),Y1=Math.max(THIS_YEAR,+list[list.length-1].date.slice(0,4)),x0=Date.UTC(Y0,0,1)/864e5,x1=Date.UTC(Y1+1,0,1)/864e5;
              function X(n){return L0+(n-x0)/(x1-x0)*(W-L0-R)}
              var s='',lo=Math.min(goodV,badV),hi=Math.max(goodV,badV);
              var step=mode=='speed'?niceStep(hi-lo,5,[.5,1,2,5,10]):niceStep(hi-lo,5,[5,10,15,20,30,60,120,300]);
              for(var v=Math.ceil(lo/step)*step;v<=hi;v+=step){var y=Y(v);
                s+='<line x1="'+L0+'" x2="'+(W-R)+'" y1="'+y.toFixed(1)+'" y2="'+y.toFixed(1)+'" stroke="#1d2a44"/>'
                  +'<text x="'+(L0-8)+'" y="'+(y+4).toFixed(1)+'" fill="#76829b" font-size="12" text-anchor="end">'+(mode=='speed'?(step<1?dec(v.toFixed(1)):v):fmtT(v))+'</text>'}
              var ystep=Math.ceil((Y1-Y0+1)/7);
              for(var yr=Y0;yr<=Y1;yr++){var xa=X(Date.UTC(yr,0,1)/864e5),xm=X(Date.UTC(yr,6,1)/864e5);
                s+='<line x1="'+xa.toFixed(1)+'" x2="'+xa.toFixed(1)+'" y1="'+T0+'" y2="'+(H-BT)+'" stroke="#16213a"/>';
                if((yr-Y0)%ystep==0)s+='<text x="'+xm.toFixed(1)+'" y="'+(H-6)+'" fill="#76829b" font-size="12" text-anchor="middle">'+yr+'</text>'}
              var pts=[];
              list.forEach(function(e){var v=val(e);if(v<lo||v>hi)return;var p={e:e,x:X(e.n+.5),y:Y(v)};pts.push(p);
                s+='<circle cx="'+p.x.toFixed(1)+'" cy="'+p.y.toFixed(1)+'" r="2.3" fill="'+color+'" fill-opacity=".3"/>'});
              var path=yb.map(function(e,i){return (i?'L':'M')+X(e.n+.5).toFixed(1)+' '+Y(val(e)).toFixed(1)}).join('');
              s+='<path d="'+path+'" fill="none" stroke="#eef1f7" stroke-width="1.6" stroke-opacity=".75" stroke-linejoin="round"/>';
              var all=best(list);
              yb.forEach(function(e){var x=X(e.n+.5),y=Y(val(e)),top=e===all;
                var label=e.date.slice(0,4)+L(' best: ',' beste: ')+fmtT(e.t)+(mode=='speed'?', '+kmh(e)+' '+KMH:', '+fmtPace(pace(e))+L(' per km',' per km'))+', '+(e.name||'')+', '+fmtDate(e.date);
                s+='<a href="'+href(e)+'" data-i="'+list.indexOf(e)+'" aria-label="'+esc(label)+'">'
                  +(top?'<circle cx="'+x.toFixed(1)+'" cy="'+y.toFixed(1)+'" r="9" fill="none" stroke="'+color+'" stroke-width="1.5"/>':'')
                  +'<circle cx="'+x.toFixed(1)+'" cy="'+y.toFixed(1)+'" r="4.5" fill="'+color+'" stroke="#0a1220" stroke-width="2"/></a>'});
              s+='<circle class="hl" r="5.5" fill="none" stroke="#eef1f7" stroke-width="2" visibility="hidden" pointer-events="none"/>';
              var div=document.createElement('div');div.className='chart';
              var left=list.length-pts.length;
              div.innerHTML='<h3>'+d.label+'</h3><p>'+list.length+(list.length==1?L(' effort',' prestatie'):L(' efforts',' prestaties'))+'. '
                +(mode=='speed'?L('Average speed in km/h, faster is higher.','Gemiddelde snelheid in km/u, sneller is hoger.')
                  :L('Pace per km, axis turned around: faster is higher.','Tempo per km, as omgekeerd: sneller is hoger.'))
                +(left?(NL?' '+left+(left==1?' tragere staat':' tragere staan')+' onder de grafiek.':' '+left+' slower '+(left==1?'one is':'ones are')+' below the chart.'):'')+'</p>'
                +'<svg viewBox="0 0 '+W+' '+H+'" role="img" aria-label="'+esc(d.label+(NL?': beste '+(mode=='speed'?'snelheid':'tempo')+' per jaar':': best '+(mode=='speed'?'speed':'pace')+' per year'))+'">'+s+'</svg>';
              host.appendChild(div);
              var svg=div.querySelector('svg'),hl=svg.querySelector('.hl');
              function tipFor(e){var y=years[e.date.slice(0,4)];
                return '<b>'+fmtT(e.t)+'</b> '+(mode=='speed'?kmh(e)+' '+KMH:fmtPace(pace(e))+PERKM)
                  +(e===all?L(' · your best',' · je beste'):e===y?L(' · best of ',' · beste van ')+e.date.slice(0,4):'')
                  +'<br>'+name(e)+'<br><span style="color:#76829b">'+fmtDate(e.date)+', '+where(e)+'</span>'}
              function near(ev){var r=svg.getBoundingClientRect(),sx=W/r.width,x=(ev.clientX-r.left)*sx,y=(ev.clientY-r.top)*sx,b=null,bd=24*sx;
                pts.forEach(function(p){var dd=Math.hypot(p.x-x,p.y-y);if(dd<bd){bd=dd;b=p}});return b}
              var cur=null;
              svg.addEventListener('mousemove',function(ev){var p=near(ev);cur=p;if(!p){hideTip();hl.setAttribute('visibility','hidden');svg.style.cursor='crosshair';return}
                hl.setAttribute('cx',p.x);hl.setAttribute('cy',p.y);hl.setAttribute('visibility','visible');svg.style.cursor='pointer';
                showTip(tipFor(p.e),ev.clientX,ev.clientY)});
              svg.addEventListener('mouseleave',function(){cur=null;hideTip();hl.setAttribute('visibility','hidden')});
              svg.addEventListener('click',function(ev){if(ev.target.closest('a'))return;var p=near(ev);if(p)location.href=href(p.e)});
              svg.querySelectorAll('a').forEach(function(a){
                a.addEventListener('focus',function(){var r=a.getBoundingClientRect();showTip(tipFor(list[+a.dataset.i]),r.right,r.top)});
                a.addEventListener('blur',hideTip)});
            }
            document.getElementById('runKeys').innerHTML='<li><i style="width:8px;height:8px;background:'+RUNC+';opacity:.4"></i>'+L('One run','Eén loop')+'</li>'
              +'<li><i style="width:11px;height:11px;background:'+RUNC+'"></i>'+L('Best of the year','Beste van het jaar')+'</li>'
              +'<li><i style="width:16px;height:16px;border:1.5px solid '+RUNC+'"></i>'+L('Best ever','Beste ooit')+'</li>';
            var rc=document.getElementById('runCharts');
            RUN.forEach(function(d,k){chart(rc,RUNS[k],d,RUNC,'pace')});

            // ---- best per year: a table, years newest first, one column per distance
            (function(){
              var by={},all=RUN.map(function(d,k){return best(RUNS[k])});
              RUNS.forEach(function(l,k){l.forEach(function(e){var y=e.date.slice(0,4);by[y]=by[y]||[];if(!by[y][k]||e.t<by[y][k].t)by[y][k]=e})});
              var years=Object.keys(by).sort().reverse();
              if(!years.length)return;
              var t=document.getElementById('perYearTable');
              t.innerHTML='<thead><tr><th scope="col">'+L('Year','Jaar')+'</th>'+RUN.map(function(d){return '<th scope="col">'+d.short+'</th>'}).join('')+'</tr></thead><tbody>'
                +years.map(function(y){return '<tr><th scope="row">'+y+'</th>'+RUN.map(function(d,k){var e=by[y][k];
                  if(!e)return '<td class="none">—</td>';
                  var top=e===all[k],lab=d.label+' '+y+': '+fmtT(e.t)+', '+(e.name||'')+', '+fmtDate(e.date)+(top?L(', your best ever',', je beste ooit'):'');
                  return '<td'+(top?' class="pb"':'')+'><a href="'+href(e)+'" title="'+esc(lab)+'">'+fmtT(e.t)+'<small>'+fmtPace(pace(e))+PERKM+' · '+(+e.date.slice(8,10))+' '+MONTHS[+e.date.slice(5,7)-1]+'</small></a></td>'}).join('')+'</tr>'}).join('')
                +'</tbody>';
              document.getElementById('perYear').hidden=false;
            })();

            // ---- on the bike
            if(RIDES[0].length){
              document.getElementById('bike').hidden=false;
              document.getElementById('rideWatches').innerHTML=RIDE.map(function(d,k){return watch(best(RIDES[k]),d,'speed',RIDES[k])}).join('');
              var rh=document.getElementById('rideCharts');
              RIDE.forEach(function(d,k){chart(rh,RIDES[k],d,RIDEC,'speed')});
            }
            })();
            """;
}
