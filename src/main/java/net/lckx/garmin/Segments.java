package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static net.lckx.garmin.DescribeGarminFile.esc;
import static net.lckx.garmin.DescribeGarminFile.jsStr;

/**
 * "Your segments": the stretches run or ridden most often, found in the account's own GPS tracks, with every time
 * through each of them over the years. Not a leaderboard: it is about one person's evolution.
 * <ol>
 *   <li>Every run and ride is resampled to a point every {@value #STEP} m. Each point marks the grid cells of about
 *       {@value #CELL} m around it, per direction of travel, once per activity: a cell's count is how many activities
 *       passed there going that way.</li>
 *   <li>Windows of 0.5–3 km (running) or 1–8 km (cycling) along the tracks score by the lowest count along them, so a
 *       window scores high only when many activities follow all of it in the same direction. The best windows that do
 *       not overlap become the segments.</li>
 *   <li>Every activity is then matched against every segment: it must pass the start, stay within {@value #FOLLOW_R}
 *       m of the whole segment in order, and reach the end. The time between start and end is the effort; efforts with
 *       a stop (for cycling: more than a minute of stopping) or an unbelievable pace are left out.</li>
 * </ol>
 * The tracks are kept in {@code segments-cache.tsv} (simplified, with time and elevation), so later runs only read
 * new or changed GPX files. Writes {@code segments.html} and {@code segments-data.js}; the downloaded files are not touched.
 * <pre>
 * java --enable-preview -cp target/classes net.lckx.garmin.Segments [--account NAME]
 * </pre>
 */
public final class Segments {
    static final String PAGE_FILE = "segments.html";
    private static final String DATA_FILE = "segments-data.js";
    private static final String CACHE_FILE = "segments-cache.tsv";
    /** Bump when what is cached changes, so every track is read again. */
    private static final String CACHE_VERSION = "s1";

    private static final Pattern GPX_FILE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})_(\\d+)_.*\\.gpx");
    /** Cached tracks keep a point every this many metres. */
    private static final double KEEP_STEP = 8;
    /** A jump larger than this between two recorded points is a GPS gap: the track is broken there (metres). */
    private static final double MAX_JUMP = 300;
    /** Spacing of the resampled tracks and segments (metres). */
    private static final double STEP = 25;
    /** Size of a grid cell (metres). */
    private static final double CELL = 25;
    /** Points at the start and end of an activity that do not count when looking for segments (150 m): every run from
     *  home starts in the same street, but the watch is not yet up to speed there. */
    private static final int EDGE = 6;
    /** A window needs at least this many activities along all of it. */
    private static final int MIN_COUNT = 8;
    /** Segments per sport, and how many efforts one needs to be shown. */
    private static final int MAX_SEGMENTS = 15, MIN_EFFORTS = 8;
    /** An effort starts within START_R of the segment start and stays within FOLLOW_R of every segment point (metres). */
    private static final double START_R = 30, FOLLOW_R = 35;

    /** Running and cycling: window lengths (in points of {@value #STEP} m), believable speeds (m/s), the longest one
     *  {@value #STEP} m step may take before it counts as a stop (seconds), and how much stopping an effort may have
     *  (seconds): a run must not stop at all, but a ride through town waits at traffic lights, so it may stop for up to
     *  a minute in all. */
    enum Kind {
        RUN("run", new int[]{20, 30, 40, 60, 80, 120}, 1.6, 6.5, 25, 0),
        RIDE("ride", new int[]{40, 60, 80, 120, 200, 320}, 2.5, 20, 15, 60);

        final String key;
        final int[] windows;
        final double minSpeed, maxSpeed, maxStepSecs, maxStopSecs;

        Kind(String key, int[] windows, double minSpeed, double maxSpeed, double maxStepSecs, double maxStopSecs) {
            this.key = key;
            this.windows = windows;
            this.minSpeed = minSpeed;
            this.maxSpeed = maxSpeed;
            this.maxStepSecs = maxStepSecs;
            this.maxStopSecs = maxStopSecs;
        }

        /**
         * Whether the steps from {@code from} to {@code to} stop too long. A step slower than {@link #maxStepSecs} is a
         * stop, as long as the time beyond riding (or running) it at the lowest believable speed; all stops together may
         * not exceed {@link #maxStopSecs}.
         */
        boolean stopsTooLong(Line l, int from, int to) {
            double stopped = 0, moving = STEP / minSpeed;
            for (int q = from; q < to; q++) {
                double dt = l.t[q + 1] - l.t[q];
                if (dt <= maxStepSecs) continue;
                stopped += dt - moving;
                if (maxStopSecs <= 0 || stopped > maxStopSecs) return true;
            }
            return false;
        }
    }

    private Segments() {}

    public static void main(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--account")) DescribeGarminFile.useAccount(args[++i]);
            else {
                System.err.println("Usage: Segments [--account NAME]");
                System.exit(1);
            }
        }
        System.out.println("Segments updated: " + write().toAbsolutePath());
    }

    /** One GPX file as cached: the class ("run", "ride" or "" for anything else) and the encoded track. */
    record Entry(long mtime, String cls, String date, String id, String time, String name, String track) {}

    static Path write() throws IOException {
        long t0 = System.nanoTime();
        Path dir = DescribeGarminFile.dataDir(DescribeGarminFile.account());
        Files.createDirectories(dir);
        SportCorrection.dropIfStale(dir.resolve(CACHE_FILE));
        Map<String, Entry> cache = readCache(dir.resolve(CACHE_FILE));
        List<Path> gpx = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> GPX_FILE.matcher(p.getFileName().toString()).matches()).forEach(gpx::add);
        }
        Map<String, Entry> entries = new ConcurrentHashMap<>();
        AtomicInteger read = new AtomicInteger(), failed = new AtomicInteger();
        gpx.parallelStream().forEach(p -> {
            Matcher m = GPX_FILE.matcher(p.getFileName().toString());
            if (!m.matches()) return;
            String key = dir.relativize(p).toString().replace('\\', '/');
            try {
                long mtime = mtime(p);
                Entry e = cache.get(key);
                if (e == null || e.mtime != mtime) {
                    e = load(p, m.group(1), m.group(2), mtime);
                    read.incrementAndGet();
                }
                entries.put(key, e);
            } catch (Exception ex) {
                failed.incrementAndGet();
                System.err.println("Segments: could not read " + p + ": " + ex.getMessage());
            }
        });
        TreeMap<String, Entry> sorted = new TreeMap<>(entries);
        writeCache(dir.resolve(CACHE_FILE), sorted);
        long tRead = System.nanoTime();

        List<Act> all = new ArrayList<>();
        for (Entry e : sorted.values()) {
            if (e.cls.isEmpty() || e.track.isEmpty()) continue;
            Act a = Act.of(e);
            if (a != null) all.add(a);
        }
        Map<String, double[]> places = places(all);
        StringBuilder js = new StringBuilder("window.SEGMENTS={year:").append(LocalDate.now().getYear());
        StringBuilder summary = new StringBuilder();
        for (Kind k : Kind.values()) {
            List<Act> acts = new ArrayList<>();
            for (Act a : all) if (a.cls.equals(k.key)) acts.add(a);
            for (int i = 0; i < acts.size(); i++) acts.get(i).idx = i;
            List<Found> found = find(k, acts);
            js.append(",\n").append(k.key).append(":[");
            int efforts = 0;
            for (int i = 0; i < found.size(); i++) {
                Found f = found.get(i);
                f.name = nearestPlace(places, f.lat[f.lat.length / 2], f.lon[f.lon.length / 2]);
                js.append(i == 0 ? "\n" : ",\n").append(f.json());
                efforts += f.efforts.size();
                System.out.printf("  %s %2d: %-22s %-11s %5.2f km %4.0f m climb %4d efforts%n", k.key, i + 1,
                        f.name.isEmpty() ? "-" : f.name, f.dir, f.metres / 1000, f.climb, f.efforts.size());
            }
            js.append("]");
            summary.append(summary.isEmpty() ? "" : ", ").append(found.size()).append(' ')
                    .append(k == Kind.RUN ? "running" : "cycling").append(" (").append(efforts).append(" efforts from ")
                    .append(acts.size()).append(" activities)");
        }
        js.append("};\n");
        replace(dir.resolve(DATA_FILE), js.toString());
        Path page = dir.resolve(PAGE_FILE);
        replace(page, page());
        System.out.printf("Segments: %s; %d GPX files (%d read, %d unreadable) in %.1fs (reading %.1fs).%n", summary,
                sorted.size(), read.get(), failed.get(), (System.nanoTime() - t0) / 1e9, (tRead - t0) / 1e9);
        return page;
    }

    /** The newest of the GPX and its summary, so a changed sport also reads the track again. */
    private static long mtime(Path gpx) throws IOException {
        long m = Files.getLastModifiedTime(gpx).toMillis();
        Path json = jsonOf(gpx);
        if (Files.exists(json)) m = Math.max(m, Files.getLastModifiedTime(json).toMillis());
        return m;
    }

    private static Path jsonOf(Path gpx) {
        return gpx.resolveSibling(gpx.getFileName().toString().replaceFirst("\\.gpx$", ".json"));
    }

    // ---- reading -------------------------------------------------------------------------------------------

    private static Entry load(Path gpx, String date, String id, long mtime) throws IOException {
        String type = "", name = "", time = "";
        double metres = 0, secs = 0;
        Path json = jsonOf(gpx);
        if (Files.exists(json)) {
            Map<?, ?> j = (Map<?, ?>) DescribeGarminFile.Json.parse(Files.readString(json));
            type = str(get(j, "activityTypeDTO", "typeKey"));
            name = str(get(j, "activityName"));
            String start = str(get(j, "summaryDTO", "startTimeLocal"));
            if (start.length() >= 16) time = start.substring(11, 16);
            metres = number(get(j, "summaryDTO", "distance"));
            secs = number(get(j, "summaryDTO", "duration"));
        }
        String sport = SportCorrection.sport(SportCorrection.id(id), type, metres, secs);
        String group = DescribeGarminFile.Overview.sportGroup(sport);
        // Treadmill runs and virtual rides have no real road under them.
        String cls = group.equals("running") && !sport.equals("treadmill_running") ? "run"
                : group.equals("cycling") ? "ride" : "";
        name = name.replaceAll("[\\t\\r\\n]+", " ").trim();
        if (cls.isEmpty()) return new Entry(mtime, cls, date, id, time, name, "");
        return new Entry(mtime, cls, date, id, time, name, encodeTrack(Files.readAllBytes(gpx)));
    }

    private static final byte[] TRKPT = ascii("<trkpt"), END = ascii("</trkpt>"), ELE = ascii("<ele>"), TIME = ascii("<time>");

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The track as Google polylines of (lat, lon, seconds since the first point, elevation in dm), one per GPS-gap-free piece. */
    static String encodeTrack(byte[] b) {
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder();
        long t0 = Long.MIN_VALUE;
        double pLat = 0, pLon = 0;
        long eLat = 0, eLon = 0, eT = 0, eEle = 0, ele = 0;
        int pts = 0;
        boolean have = false;
        int i = 0;
        while ((i = indexOf(b, TRKPT, i, b.length)) >= 0) {
            int gt = indexOf(b, new byte[]{'>'}, i, b.length);
            if (gt < 0) break;
            boolean selfClosing = b[gt - 1] == '/';
            int end = selfClosing ? gt : indexOf(b, END, gt, b.length);
            if (end < 0) break;
            double lat = attr(b, i, gt, "lat=\""), lon = attr(b, i, gt, "lon=\"");
            i = end + 1;
            if (Double.isNaN(lat) || Double.isNaN(lon) || (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6)) continue;
            int ts = indexOf(b, TIME, gt, end);
            if (ts < 0) continue;
            long t = parseTime(b, ts + TIME.length);
            if (t == Long.MIN_VALUE) continue;
            int es = indexOf(b, ELE, gt, end);
            if (es >= 0) {
                double e = number(b, es + ELE.length);
                if (!Double.isNaN(e)) ele = Math.round(e * 10);
            }
            if (t0 == Long.MIN_VALUE) t0 = t;
            if (have) {
                double d = metres(pLat, pLon, lat, lon);
                if (d > MAX_JUMP) {
                    if (pts >= 2) out.append(out.isEmpty() ? "" : " ").append(line);
                    line.setLength(0);
                    pts = 0;
                    eLat = eLon = eT = eEle = 0;
                } else if (d < KEEP_STEP) continue;
            }
            long la = Math.round(lat * 1e5), lo = Math.round(lon * 1e5), tt = t - t0;
            Heatmap.encodeValue(line, la - eLat);
            Heatmap.encodeValue(line, lo - eLon);
            Heatmap.encodeValue(line, tt - eT);
            Heatmap.encodeValue(line, ele - eEle);
            eLat = la;
            eLon = lo;
            eT = tt;
            eEle = ele;
            pLat = lat;
            pLon = lon;
            have = true;
            pts++;
        }
        if (pts >= 2) out.append(out.isEmpty() ? "" : " ").append(line);
        return out.toString();
    }

    private static int indexOf(byte[] b, byte[] p, int from, int to) {
        outer:
        for (int i = from, n = to - p.length; i <= n; i++) {
            if (b[i] != p[0]) continue;
            for (int k = 1; k < p.length; k++) if (b[i + k] != p[k]) continue outer;
            return i;
        }
        return -1;
    }

    private static double attr(byte[] b, int from, int to, String name) {
        int i = indexOf(b, ascii(name), from, to);
        return i < 0 ? Double.NaN : number(b, i + name.length());
    }

    private static double number(byte[] b, int from) {
        int e = from;
        while (e < b.length && (b[e] == '-' || b[e] == '+' || b[e] == '.' || b[e] == 'e' || b[e] == 'E' || (b[e] >= '0' && b[e] <= '9'))) e++;
        if (e == from) return Double.NaN;
        try {
            return Double.parseDouble(new String(b, from, e - from, StandardCharsets.ISO_8859_1));
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    /** Epoch seconds of 2016-05-10T17:03:10.000Z (other offsets through java.time). */
    private static long parseTime(byte[] b, int s) {
        if (s + 19 > b.length) return Long.MIN_VALUE;
        int y = digits(b, s, 4), mo = digits(b, s + 5, 2), d = digits(b, s + 8, 2), h = digits(b, s + 11, 2),
                mi = digits(b, s + 14, 2), se = digits(b, s + 17, 2);
        if (y < 0 || mo < 0 || d < 0 || h < 0 || mi < 0 || se < 0) return Long.MIN_VALUE;
        int k = s + 19;
        while (k < b.length && (b[k] == '.' || (b[k] >= '0' && b[k] <= '9'))) k++;
        if (k < b.length && b[k] == 'Z') return LocalDate.of(y, mo, d).toEpochDay() * 86400 + h * 3600L + mi * 60L + se;
        int e = k;
        while (e < b.length && b[e] != '<') e++;
        try {
            return OffsetDateTime.parse(new String(b, s, e - s, StandardCharsets.ISO_8859_1)).toEpochSecond();
        } catch (Exception ex) {
            return Long.MIN_VALUE;
        }
    }

    private static int digits(byte[] b, int s, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            int c = b[s + i] - '0';
            if (c < 0 || c > 9) return -1;
            v = v * 10 + c;
        }
        return v;
    }

    static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dy = (lat2 - lat1) * 110_540, dx = (lon2 - lon1) * 111_320 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.sqrt(dx * dx + dy * dy);
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

    // ---- cache ---------------------------------------------------------------------------------------------

    private static Map<String, Entry> readCache(Path f) {
        Map<String, Entry> m = new HashMap<>();
        if (!Files.exists(f)) return m;
        try {
            List<String> rows = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (rows.isEmpty() || !rows.getFirst().equals(CACHE_VERSION)) return m;
            for (String row : rows.subList(1, rows.size())) {
                String[] c = row.split("\t", -1);
                if (c.length < 8) continue;
                m.put(c[0], new Entry(Long.parseLong(c[1]), c[2], c[3], c[4], c[5], c[6], c[7]));
            }
        } catch (Exception e) {
            System.err.println("Segments: cache unreadable, reading every track again (" + e.getMessage() + ")");
            m.clear();
        }
        return m;
    }

    private static void writeCache(Path f, Map<String, Entry> entries) throws IOException {
        StringBuilder b = new StringBuilder(CACHE_VERSION).append('\n');
        for (var e : entries.entrySet()) {
            Entry t = e.getValue();
            b.append(e.getKey()).append('\t').append(t.mtime).append('\t').append(t.cls).append('\t').append(t.date).append('\t')
                    .append(t.id).append('\t').append(t.time).append('\t').append(t.name).append('\t').append(t.track).append('\n');
        }
        replace(f, b.toString());
    }

    // ---- tracks in memory ----------------------------------------------------------------------------------

    /** A piece of track resampled to a point every {@value #STEP} m: position, seconds since the start, elevation (m). */
    static final class Line {
        final double[] lat, lon, t, ele;
        final int n;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;

        Line(double[] lat, double[] lon, double[] t, double[] ele, int n) {
            this.lat = lat;
            this.lon = lon;
            this.t = t;
            this.ele = ele;
            this.n = n;
            for (int i = 0; i < n; i++) {
                minLat = Math.min(minLat, lat[i]);
                maxLat = Math.max(maxLat, lat[i]);
                minLon = Math.min(minLon, lon[i]);
                maxLon = Math.max(maxLon, lon[i]);
            }
        }
    }

    static final class Act {
        int idx;
        String cls, date, id, time, name;
        List<Line> lines = new ArrayList<>();
        double startLat, startLon;

        static Act of(Entry e) {
            Act a = new Act();
            a.cls = e.cls;
            a.date = e.date;
            a.id = e.id;
            a.time = e.time;
            a.name = e.name;
            for (String enc : e.track.split(" ")) {
                Line l = resample(enc);
                if (l != null) a.lines.add(l);
            }
            if (a.lines.isEmpty()) return null;
            a.startLat = a.lines.getFirst().lat[0];
            a.startLon = a.lines.getFirst().lon[0];
            return a;
        }
    }

    private static Line resample(String s) {
        int cap = s.length() / 4 + 4;
        double[] la = new double[cap], lo = new double[cap], tt = new double[cap], el = new double[cap];
        int n = 0, i = 0;
        long[] v = new long[4];
        while (i < s.length()) {
            for (int k = 0; k < 4; k++) {
                long r = 0;
                int sh = 0, c;
                do {
                    c = s.charAt(i++) - 63;
                    r |= (long) (c & 31) << sh;
                    sh += 5;
                } while (c >= 32 && i < s.length());
                v[k] += (r & 1) != 0 ? ~(r >> 1) : (r >> 1);
            }
            if (n == cap) {
                cap *= 2;
                la = Arrays.copyOf(la, cap);
                lo = Arrays.copyOf(lo, cap);
                tt = Arrays.copyOf(tt, cap);
                el = Arrays.copyOf(el, cap);
            }
            la[n] = v[0] / 1e5;
            lo[n] = v[1] / 1e5;
            tt[n] = v[2];
            el[n] = v[3] / 10.0;
            n++;
        }
        if (n < 2) return null;
        List<double[]> out = new ArrayList<>();
        out.add(new double[]{la[0], lo[0], tt[0], el[0]});
        double carry = 0;
        for (int k = 1; k < n; k++) {
            double d = metres(la[k - 1], lo[k - 1], la[k], lo[k]);
            if (d <= 0) continue;
            double pos = 0;
            while (carry + (d - pos) >= STEP) {
                pos += STEP - carry;
                double f = pos / d;
                out.add(new double[]{la[k - 1] + (la[k] - la[k - 1]) * f, lo[k - 1] + (lo[k] - lo[k - 1]) * f,
                        tt[k - 1] + (tt[k] - tt[k - 1]) * f, el[k - 1] + (el[k] - el[k - 1]) * f});
                carry = 0;
            }
            carry += d - pos;
        }
        int m = out.size();
        if (m < 4) return null;
        double[] a = new double[m], b = new double[m], c = new double[m], e = new double[m];
        for (int k = 0; k < m; k++) {
            double[] p = out.get(k);
            a[k] = p[0];
            b[k] = p[1];
            c[k] = p[2];
            e[k] = p[3];
        }
        return new Line(a, b, c, e, m);
    }

    // ---- finding segments ----------------------------------------------------------------------------------

    /** Open-addressing map from (cell, direction) to the number of activities that passed, stamped per activity. */
    static final class Counts {
        long[] keys = new long[1 << 20];
        int[] cnt = new int[1 << 20], stamp = new int[1 << 20];
        int size;

        private int slot(long[] ks, long key) {
            int mask = ks.length - 1, h = (int) (key * 0x9E3779B97F4A7C15L >>> 40) & mask;
            while (ks[h] != 0 && ks[h] != key) h = (h + 1) & mask;
            return h;
        }

        void mark(long key, int st) {
            int h = slot(keys, key);
            if (keys[h] == 0) {
                keys[h] = key;
                cnt[h] = 1;
                stamp[h] = st;
                if (++size > keys.length * 0.6) grow();
            } else if (stamp[h] != st) {
                stamp[h] = st;
                cnt[h]++;
            }
        }

        int get(long key) {
            int h = slot(keys, key);
            return keys[h] == 0 ? 0 : cnt[h];
        }

        private void grow() {
            long[] ok = keys;
            int[] oc = cnt, os = stamp;
            keys = new long[ok.length * 2];
            cnt = new int[ok.length * 2];
            stamp = new int[ok.length * 2];
            for (int i = 0; i < ok.length; i++) {
                if (ok[i] == 0) continue;
                int h = slot(keys, ok[i]);
                keys[h] = ok[i];
                cnt[h] = oc[i];
                stamp[h] = os[i];
            }
        }
    }

    private static double cx(double lat, double lon) {
        return lon * 111_320 * Math.cos(Math.toRadians(lat)) / CELL;
    }

    private static double cy(double lat) {
        return lat * 110_540 / CELL;
    }

    private static long cellKey(long x, long y) {
        return ((y + (1L << 21)) << 24) | (x + (1L << 22));
    }

    private static long dirKey(long cell, int sector) {
        return (cell << 3 | sector) | (1L << 62);
    }

    /** Heading at point k in units of 45° (0 = north, clockwise), 0..8. */
    private static double heading(Line l, int k) {
        int a = Math.max(0, k - 1), b = Math.min(l.n - 1, k + 1);
        double dy = (l.lat[b] - l.lat[a]) * 110_540, dx = (l.lon[b] - l.lon[a]) * 111_320 * Math.cos(Math.toRadians(l.lat[k]));
        double ang = Math.toDegrees(Math.atan2(dx, dy));
        if (ang < 0) ang += 360;
        return ang / 45;
    }

    private static boolean edge(Act a, int li, int k) {
        return (li == 0 && k < EDGE) || (li == a.lines.size() - 1 && k >= a.lines.get(li).n - EDGE);
    }

    record Candidate(double value, int act, int line, int start, int len) {}

    /** A segment and every effort on it. */
    static final class Found {
        double[] lat, lon;
        double metres, climb;
        String dir, name = "";
        List<Effort> efforts = new ArrayList<>();
        double score;

        String json() {
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < lat.length; i++) pts.add(new double[]{lat[i], lon[i]});
            StringBuilder b = new StringBuilder("{name:").append(jsStr(name)).append(",dir:").append(jsStr(dir))
                    .append(",m:").append(Math.round(metres)).append(",climb:").append(Math.round(climb))
                    .append(",line:").append(jsStr(Heatmap.encode(Heatmap.simplify(pts)))).append(",efforts:[");
            efforts.sort(Comparator.comparing((Effort e) -> e.act.date).thenComparing(e -> e.act.time).thenComparingDouble(e -> e.start));
            for (int i = 0; i < efforts.size(); i++) {
                Effort e = efforts.get(i);
                b.append(i == 0 ? "" : ",").append('[').append(jsStr(e.act.date)).append(',').append(jsStr(e.act.id)).append(',')
                        .append(DescribeGarminFile.trimNum(Math.round(e.secs * 10) / 10.0)).append(',').append(jsStr(e.act.time))
                        .append(',').append(jsStr(e.act.name)).append(']');
            }
            return b.append("]}").toString();
        }
    }

    record Effort(Act act, double start, double secs, double climb) {}

    static List<Found> find(Kind kind, List<Act> acts) {
        Counts counts = new Counts();
        for (Act a : acts) {
            int st = a.idx + 1;
            for (int li = 0; li < a.lines.size(); li++) {
                Line l = a.lines.get(li);
                for (int k = 0; k < l.n; k++) {
                    if (edge(a, li, k)) continue;
                    double h = heading(l, k);
                    int s1 = ((int) Math.floor(h)) & 7, s2 = (s1 + 1) & 7;
                    double x = cx(l.lat[k], l.lon[k]) - .5, y = cy(l.lat[k]) - .5;
                    long x0 = (long) Math.floor(x), y0 = (long) Math.floor(y);
                    for (long dx = 0; dx <= 1; dx++)
                        for (long dy = 0; dy <= 1; dy++) {
                            long c = cellKey(x0 + dx, y0 + dy);
                            counts.mark(dirKey(c, s1), st);
                            counts.mark(dirKey(c, s2), st);
                        }
                }
            }
        }
        // Score every window along every track by the fewest activities seen at any of its points.
        List<Candidate> cands = new ArrayList<>();
        for (Act a : acts) {
            for (int li = 0; li < a.lines.size(); li++) {
                Line l = a.lines.get(li);
                int[] c = new int[l.n];
                for (int k = 0; k < l.n; k++) {
                    if (edge(a, li, k)) continue;
                    int s = ((int) Math.round(heading(l, k))) & 7;
                    c[k] = counts.get(dirKey(cellKey((long) Math.floor(cx(l.lat[k], l.lon[k])), (long) Math.floor(cy(l.lat[k]))), s));
                }
                for (int w : kind.windows) {
                    if (w >= l.n) continue;
                    double lenFactor = Math.pow(w * STEP / 1000, 0.6);
                    int[] dq = new int[l.n];
                    int head = 0, tail = 0, bestMin = 0, bestS = -1;
                    // Only the best start in each stretch of 20 points (500 m) is kept: the rest are near-copies.
                    for (int k = 0; k < l.n; k++) {
                        while (tail > head && c[dq[tail - 1]] >= c[k]) tail--;
                        dq[tail++] = k;
                        int s = k - w;
                        if (s < 0) continue;
                        while (dq[head] < s) head++;
                        int min = c[dq[head]];
                        if (bestS >= 0 && s / 20 != bestS / 20) {
                            cands.add(new Candidate(bestMin * lenFactor, a.idx, li, bestS, w));
                            bestS = -1;
                        }
                        if (min >= MIN_COUNT && (bestS < 0 || min > bestMin)) {
                            bestMin = min;
                            bestS = s;
                        }
                    }
                    if (bestS >= 0) cands.add(new Candidate(bestMin * lenFactor, a.idx, li, bestS, w));
                }
            }
        }
        cands.sort(Comparator.comparingDouble(Candidate::value).reversed());
        // The best windows that do not overlap a better one (in either direction) and are not a loop.
        Set<Long> used = new HashSet<>();
        List<Found> picked = new ArrayList<>();
        for (Candidate cd : cands) {
            if (picked.size() >= MAX_SEGMENTS * 2) break;
            Line l = acts.get(cd.act).lines.get(cd.line);
            int overlap = 0;
            for (int k = cd.start; k <= cd.start + cd.len; k++)
                if (used.contains(cellKey((long) Math.floor(cx(l.lat[k], l.lon[k])), (long) Math.floor(cy(l.lat[k]))))) overlap++;
            if (overlap > (cd.len + 1) * 0.1) continue;
            double straight = metres(l.lat[cd.start], l.lon[cd.start], l.lat[cd.start + cd.len], l.lon[cd.start + cd.len]);
            if (straight < 0.35 * cd.len * STEP) continue;
            Found f = new Found();
            f.lat = Arrays.copyOfRange(l.lat, cd.start, cd.start + cd.len + 1);
            f.lon = Arrays.copyOfRange(l.lon, cd.start, cd.start + cd.len + 1);
            f.metres = cd.len * STEP;
            picked.add(f);
            for (int k = cd.start; k <= cd.start + cd.len; k++) {
                long x = (long) Math.floor(cx(l.lat[k], l.lon[k])), y = (long) Math.floor(cy(l.lat[k]));
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) used.add(cellKey(x + dx, y + dy));
            }
        }
        picked.parallelStream().forEach(f -> match(kind, f, acts));
        List<Found> out = new ArrayList<>();
        for (Found f : picked) {
            if (f.efforts.size() < MIN_EFFORTS) continue;
            f.score = f.efforts.size() * Math.pow(f.metres / 1000, 0.6);
            double[] climbs = f.efforts.stream().mapToDouble(Effort::climb).sorted().toArray();
            f.climb = climbs[climbs.length / 2];
            f.dir = compass(f.lat[0], f.lon[0], f.lat[f.lat.length - 1], f.lon[f.lon.length - 1]);
            out.add(f);
        }
        out.sort(Comparator.comparingDouble((Found f) -> f.score).reversed());
        return out.size() > MAX_SEGMENTS ? new ArrayList<>(out.subList(0, MAX_SEGMENTS)) : out;
    }

    /** Every time an activity went through the segment from start to end, in order, without stopping (much). */
    private static void match(Kind kind, Found f, List<Act> acts) {
        int n = f.lat.length;
        double pad = 0.001;
        double sMinLat = Arrays.stream(f.lat).min().orElse(0) - pad, sMaxLat = Arrays.stream(f.lat).max().orElse(0) + pad;
        double sMinLon = Arrays.stream(f.lon).min().orElse(0) - pad * 1.6, sMaxLon = Arrays.stream(f.lon).max().orElse(0) + pad * 1.6;
        for (Act a : acts) {
            for (Line l : a.lines) {
                if (l.maxLat < sMinLat || l.minLat > sMaxLat || l.maxLon < sMinLon || l.minLon > sMaxLon) continue;
                int i = 1;
                while (i < l.n - 1) {
                    double d = metres(l.lat[i], l.lon[i], f.lat[0], f.lon[0]);
                    if (d >= START_R || d > metres(l.lat[i - 1], l.lon[i - 1], f.lat[0], f.lon[0])
                            || d > metres(l.lat[i + 1], l.lon[i + 1], f.lat[0], f.lon[0])) {
                        i++;
                        continue;
                    }
                    int k = i;
                    boolean ok = true;
                    for (int j = 1; j < n && ok; j++) {
                        int best = -1;
                        double bestD = Double.MAX_VALUE;
                        for (int q = k; q < l.n && q <= k + 8; q++) {
                            double dq = metres(l.lat[q], l.lon[q], f.lat[j], f.lon[j]);
                            if (dq < bestD) {
                                bestD = dq;
                                best = q;
                            } else if (bestD < FOLLOW_R && dq > bestD + 30) break;
                        }
                        if (bestD > FOLLOW_R) ok = false;
                        else k = best;
                    }
                    if (!ok || k <= i || (k - i) * STEP > 1.25 * f.metres + 100
                            || metres(l.lat[k], l.lon[k], f.lat[n - 1], f.lon[n - 1]) > START_R) {
                        i++;
                        continue;
                    }
                    double tStart = timeAt(l, i, f.lat[0], f.lon[0]), tEnd = timeAt(l, k, f.lat[n - 1], f.lon[n - 1]);
                    double secs = tEnd - tStart, speed = f.metres / secs;
                    if (secs > 0 && !kind.stopsTooLong(l, i, k) && speed >= kind.minSpeed && speed <= kind.maxSpeed) {
                        synchronized (f) {
                            f.efforts.add(new Effort(a, tStart, secs, ascent(l, i, k)));
                        }
                    }
                    i = k + 1;
                }
            }
        }
    }

    /** The time at which the line passed closest to a point, between the points around index i. */
    private static double timeAt(Line l, int i, double lat, double lon) {
        double best = Double.MAX_VALUE, t = l.t[i];
        for (int a = Math.max(0, i - 1); a < Math.min(l.n - 1, i + 1); a++) {
            double mx = 111_320 * Math.cos(Math.toRadians(lat)), my = 110_540;
            double ax = (l.lon[a] - lon) * mx, ay = (l.lat[a] - lat) * my, bx = (l.lon[a + 1] - lon) * mx, by = (l.lat[a + 1] - lat) * my;
            double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
            double f = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
            double d = Math.hypot(ax + f * dx, ay + f * dy);
            if (d < best) {
                best = d;
                t = l.t[a] + (l.t[a + 1] - l.t[a]) * f;
            }
        }
        return t;
    }

    /** Metres climbed between two points, ignoring wiggles under 2 m. */
    private static double ascent(Line l, int from, int to) {
        double up = 0, low = l.ele[from], high = l.ele[from];
        boolean rising = false;
        for (int q = from + 1; q <= to; q++) {
            double e = l.ele[q];
            if (rising) {
                if (e > high) high = e;
                else if (high - e > 2) {
                    up += high - low;
                    rising = false;
                    low = e;
                }
            } else {
                if (e < low) low = e;
                else if (e - low > 2) {
                    rising = true;
                    high = e;
                }
            }
        }
        if (rising) up += high - low;
        return up;
    }

    private static String compass(double lat1, double lon1, double lat2, double lon2) {
        double dy = (lat2 - lat1) * 110_540, dx = (lon2 - lon1) * 111_320 * Math.cos(Math.toRadians(lat1));
        double ang = Math.toDegrees(Math.atan2(dx, dy));
        if (ang < 0) ang += 360;
        String[] w = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return w[(int) Math.round(ang / 45) & 7];
    }

    // ---- names ---------------------------------------------------------------------------------------------

    private static final Set<String> SPORT_WORDS = Set.of("hardlopen", "fietsen", "wegwielrennen", "wandelen", "veldrijden",
            "mountainbiken", "gravel", "running", "run", "cycling", "ride", "road", "basis", "trailrunning", "loop", "lopen",
            "wielrennen", "mountainbike", "biking", "walking", "trail", "course", "vélo", "velo", "radfahren", "laufen");

    /** Garmin names activities after where they started ("Sint-Pieters-Leeuw Hardlopen"): each such place and the
     *  middle of where its activities started. */
    private static Map<String, double[]> places(List<Act> acts) {
        Map<String, List<double[]>> starts = new HashMap<>();
        for (Act a : acts) {
            String p = place(a.name);
            if (p != null) starts.computeIfAbsent(p, x -> new ArrayList<>()).add(new double[]{a.startLat, a.startLon});
        }
        Map<String, double[]> out = new HashMap<>();
        starts.forEach((p, l) -> {
            if (l.size() < 3) return;
            double[] la = l.stream().mapToDouble(x -> x[0]).sorted().toArray(), lo = l.stream().mapToDouble(x -> x[1]).sorted().toArray();
            out.put(p, new double[]{la[la.length / 2], lo[lo.length / 2]});
        });
        return out;
    }

    private static String place(String name) {
        String[] w = name.trim().split("\\s+");
        int n = w.length;
        while (n > 1 && SPORT_WORDS.contains(w[n - 1].toLowerCase(Locale.ROOT))) n--;
        if (n == w.length) return null; // no sport word: a name the person typed
        while (n > 0 && w[n - 1].matches("[-–]+")) n--;
        if (n == 0) return null;
        String p = String.join(" ", Arrays.copyOf(w, n));
        if (p.length() < 3 || !Character.isUpperCase(p.charAt(0)) || p.matches(".*\\d.*")) return null;
        return p;
    }

    private static String nearestPlace(Map<String, double[]> places, double lat, double lon) {
        String best = "";
        double bestD = 8000;
        for (var e : places.entrySet()) {
            double d = metres(lat, lon, e.getValue()[0], e.getValue()[1]);
            if (d < bestD) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }

    // ---- output --------------------------------------------------------------------------------------------

    private static void replace(Path f, String content) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String page() {
        String label = DescribeGarminFile.accountLabelOf(DescribeGarminFile.account());
        LocalDateTime now = LocalDateTime.now();
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + DescribeGarminFile.favicon("#d95926")
                + "<title>Your segments · " + esc(label) + "</title>"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"><link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:wght@400;700"
                + "&family=Big+Shoulders+Display:wght@500;700;800&display=swap\">"
                + "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\">"
                + Site.HEAD + "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>"
                + "<style>" + CSS + "</style></head><body>"
                + BODY.replace("{{LABEL}}", esc(label)).replace("{{NAV}}", Site.nav(PAGE_FILE, 0))
                .replace("{{UPDATED}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH).format(now))
                .replace("{{UPDATED_NL}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.of("nl")).format(now))
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
            button{font:inherit;color:var(--moon);background:var(--deep);border:1px solid var(--rule);border-radius:999px;padding:6px 14px;cursor:pointer}
            button:hover{border-color:var(--dim)}
            button[aria-pressed=true]{background:var(--moon);color:var(--night);border-color:var(--moon)}
            .leaflet-tile-pane{filter:grayscale(1) invert(1) brightness(.34) contrast(1.3) sepia(.35) hue-rotate(185deg);opacity:.6}
            .leaflet-container{font:13px var(--text);background:var(--night)}
            .leaflet-control-attribution{background:rgba(10,18,32,.6)!important;color:var(--dim)!important}
            .leaflet-control-attribution a{color:var(--fog)!important}
            .leaflet-bar a{background:var(--deep);color:var(--moon);border-color:var(--rule)}
            .leaflet-bar a:hover{background:#15223a;color:var(--moon)}
            /* ---- hero: every segment on one map */
            #hero{position:relative;height:82vh;min-height:560px;overflow:hidden}
            #map{position:absolute;inset:0}
            #hero .leaflet-top{top:var(--top-h,64px)}
            .top{position:absolute;left:0;right:0;top:0;z-index:600;display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;
              gap:10px 24px;padding:12px 20px 24px 28px;pointer-events:none;background:linear-gradient(var(--night) 40%,rgba(10,18,32,0))}
            .top>*{pointer-events:auto}
            .title{font:800 22px/1 var(--display);color:var(--moon);margin:0;letter-spacing:.02em;flex:none}
            .title b{font-weight:inherit}
            .title span{color:var(--dim);font-weight:500}
            .top .site-nav{order:-1;flex:1 1 100%;min-width:0;margin-left:-9px;color:var(--fog);font-family:var(--text);
              --site-active-bg:var(--moon);--site-active-fg:var(--night)}
            .top .site-pages{min-width:0;max-width:100%}
            .top .site-switch{margin-left:auto}
            .hud{position:absolute;left:28px;bottom:32px;z-index:600;max-width:min(560px,calc(100% - 56px));pointer-events:none}
            .hud::before{content:'';position:absolute;z-index:-1;inset:-140px -220px -60px -140px;pointer-events:none;
              background:radial-gradient(closest-side,rgba(10,18,32,.9),rgba(10,18,32,.62) 55%,rgba(10,18,32,0))}
            .hud *{pointer-events:auto}
            h1{font:800 clamp(56px,9vw,124px)/.84 var(--display);color:var(--moon);margin:0;letter-spacing:-.005em}
            #lede{margin:16px 0 0;max-width:52ch}
            .pick{display:flex;flex-wrap:wrap;gap:8px;margin-top:18px}
            .pick button i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:8px;vertical-align:1px}
            .pin{display:grid;place-items:center;width:24px;height:24px;border-radius:50%;font:700 14px/1 var(--display);color:#fff;
              border:2px solid var(--night);box-shadow:0 0 0 1px rgba(238,241,247,.35)}
            /* ---- the list */
            main{max-width:1180px;margin:0 auto;padding:0 28px}
            #list{list-style:none;margin:0;padding:0}
            .card{display:grid;grid-template-columns:minmax(0,340px) minmax(0,1fr);gap:22px 40px;padding:64px 0 56px;border-top:1px solid var(--rule)}
            .card:first-child{border-top:0;padding-top:80px}
            .mini{height:300px;border-radius:10px;border:1px solid var(--rule);overflow:hidden}
            .card h2{display:flex;align-items:center;gap:14px;font:700 clamp(34px,4.4vw,52px)/.95 var(--display);color:var(--moon);margin:0}
            .card h2 .pin{width:34px;height:34px;font-size:19px;flex:none}
            .meta{margin:10px 0 0;font-size:15px;color:var(--dim)}
            .meta .from,.meta .to{display:inline-block;width:10px;height:10px;border-radius:50%;vertical-align:0}
            .meta .from{border:2px solid var(--moon);background:var(--night)}
            .say{font-size:21px;line-height:1.4;color:var(--moon);margin:18px 0 0;max-width:40ch}
            .facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:18px 28px;margin:26px 0 0}
            .facts div{border-top:1px solid var(--rule);padding-top:10px}
            .facts dt{font-size:14px;color:var(--dim)}
            .facts dd{margin:2px 0 0;font:700 32px/1.05 var(--display);color:var(--moon);font-variant-numeric:tabular-nums}
            .facts dd small{display:block;font:400 14px/1.4 var(--text);color:var(--fog);margin-top:4px}
            .chart{grid-column:1/-1}
            .chart p{margin:0 0 6px;font-size:14px;color:var(--dim)}
            .chart svg{display:block;width:100%;height:auto;overflow:visible;touch-action:manipulation}
            .keys{display:flex;flex-wrap:wrap;gap:6px 20px;margin:10px 0 0;padding:0;list-style:none;font-size:14px;color:var(--fog)}
            .keys li{display:flex;align-items:center;gap:8px}
            .keys i{display:inline-block;width:9px;height:9px;border-radius:50%}
            .keys .ln{width:22px;height:2px;border-radius:1px;background:var(--moon)}
            .keys .best{width:12px;height:12px;border:2px solid var(--moon);background:none}
            .fast{margin:12px 0 0;font-size:14px;color:var(--dim)}
            .fast a{color:var(--fog);margin-right:12px;display:inline-block}
            .fast a:hover{color:var(--moon)}
            .empty{padding:80px 0;font-size:19px}
            footer{border-top:1px solid var(--rule);margin-top:40px;padding:22px 0 40px;font-size:14px;color:var(--dim)}
            footer a{color:var(--fog);margin-right:16px}
            #tip{position:fixed;z-index:1000;pointer-events:none;background:var(--deep);border:1px solid var(--rule);border-radius:8px;
              padding:9px 12px;font-size:14px;line-height:1.45;color:var(--fog);max-width:320px;box-shadow:0 8px 30px rgba(0,0,0,.45)}
            #tip b{color:var(--moon)}
            #tip span{color:var(--dim)}
            @media (max-width:800px){
              .top{padding:12px 16px 18px;gap:8px}
              #hero{height:88vh}.hud{left:16px;bottom:20px;max-width:calc(100% - 32px)}
              main{padding:0 16px}.card{grid-template-columns:minmax(0,1fr);padding:48px 0 40px}.card:first-child{padding-top:56px}
              .mini{height:240px}.say{font-size:19px}.facts dd{font-size:28px}
            }
            @media (prefers-reduced-motion:reduce){html{scroll-behavior:auto}}
            """;

    private static final String BODY = """
            <section id="hero" aria-label="Map of your segments" data-nl-aria="Kaart van jouw segmenten">
              <div id="map"></div>
              <div class="top"><p class="title"><b data-nl="Jouw segmenten">Your segments</b> <span>{{LABEL}}</span></p>
                {{NAV}}</div>
              <div class="hud">
                <h1 data-nl="Jouw segmenten">Your segments</h1>
                <p id="lede"></p>
                <div class="pick" id="pick" role="group" aria-label="Sport" data-nl-aria="Sport"></div>
              </div>
            </section>
            <main>
              <ol id="list"></ol>
              <footer data-nl="Gevonden in je eigen GPX-sporen op {{UPDATED_NL}}. Een segment is een stuk dat veel van je activiteiten
                van begin tot eind in dezelfde richting volgen. Een keer telt als het spoor langs het begin komt, binnen 35 m van het
                hele segment blijft en het einde haalt, zonder lang te stoppen (lopend mag geen 25 m langer dan 25 s duren; fietsend
                mag je in totaal hooguit een minuut stilstaan, voor verkeerslichten bijvoorbeeld) en aan een geloofwaardig tempo.
                Loopbandsessies en virtuele ritten hebben geen weg onder zich en tellen niet mee.">
                Found in your own GPX tracks on {{UPDATED}}. A segment is a stretch that many of your activities follow from start
                to end in the same direction. A time through it counts when the track passes the start, stays within 35 m of the
                whole segment and reaches the end, without stopping long (running, no 25 m may take longer than 25 s; cycling,
                you may stand still for up to a minute in all, at traffic lights for instance) and at a believable pace.
                Treadmill runs and virtual rides have no road and are left out.</footer>
            </main>
            <div id="tip" hidden></div>
            """;

    private static final String JS = """
            (function(){
            'use strict';
            var D=window.SEGMENTS||{},YEAR=D.year||new Date().getFullYear();
            var NL=window.LANG=='nl';
            var COLOR={run:'#d95926',ride:'#3987e5'},WORD={run:L('running','hardlopen'),ride:L('cycling','fietsen')},NAME={run:L('Running','Hardlopen'),ride:L('Cycling','Fietsen')};
            var MON=NL?['jan','feb','mrt','apr','mei','jun','jul','aug','sep','okt','nov','dec']:['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
            var WD=NL?['zondag','maandag','dinsdag','woensdag','donderdag','vrijdag','zaterdag']:['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];
            var DIR_NL={'north':'noord','north-east':'noordoost','east':'oost','south-east':'zuidoost','south':'zuid','south-west':'zuidwest','west':'west','north-west':'noordwest'};
            if(NL)document.title=document.title.replace('Your segments','Jouw segmenten');
            // The zoom buttons sit just below the top bar, however high it wraps.
            var topBar=document.querySelector('.top'),hero=document.getElementById('hero');
            function topH(){hero.style.setProperty('--top-h',(topBar.offsetHeight+4)+'px')}
            topH();if(window.ResizeObserver)new ResizeObserver(topH).observe(topBar);else addEventListener('resize',topH);
            // On a phone the menu scrolls sideways: start it at this page.
            (function(){var cur=document.querySelector('.site-pages [aria-current]'),row=cur&&cur.parentNode;
              if(row&&row.scrollWidth>row.clientWidth)row.scrollLeft+=cur.getBoundingClientRect().left-row.getBoundingClientRect().left-row.clientWidth/2+cur.offsetWidth/2})();
            function dec(s){return NL?String(s).replace('.',','):String(s)}
            var reduced=window.matchMedia&&matchMedia('(prefers-reduced-motion: reduce)').matches;
            var TILES='https://tile.openstreetmap.org/{z}/{x}/{y}.png',ATTR='&copy; OpenStreetMap';
            function pad(n){return String(n).padStart(2,'0')}
            function fmtInt(v){return Math.round(v).toLocaleString('en-US').replace(/,/g,' ')}
            function clock(s){s=Math.round(s);var h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;return h?h+':'+pad(m)+':'+pad(x):m+':'+pad(x)}
            function dayNo(d){return Date.UTC(+d.slice(0,4),+d.slice(5,7)-1,+d.slice(8,10))/864e5}
            function fmtDate(d){return +d.slice(8,10)+' '+MON[+d.slice(5,7)-1]+' '+d.slice(0,4)}
            function weekday(d){return WD[new Date(dayNo(d)*864e5).getUTCDay()]}
            function href(e){return e.date.slice(0,4)+'/'+e.date.slice(5,7)+'/'+e.date+'-report.html'+(e.id?'#a'+e.id:'')}
            function esc(s){return String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
            function decode(s){var pts=[],i=0,lat=0,lon=0;
              while(i<s.length){for(var k=0;k<2;k++){var r=0,sh=0,b;do{b=s.charCodeAt(i++)-63;r|=(b&31)<<sh;sh+=5}while(b>=32);
                var d=(r&1)?~(r>>1):(r>>1);if(k==0)lat+=d;else lon+=d}pts.push([lat/1e5,lon/1e5])}
              return pts}
            function median(a){var b=a.slice().sort(function(x,y){return x-y}),m=b.length>>1;return b.length%2?b[m]:(b[m-1]+b[m])/2}
            // Running is told in pace (seconds per km, lower is faster), cycling in speed (km/h, higher is faster).
            function val(c,seg,secs){return c=='run'?secs/(seg.m/1000):seg.m/secs*3.6}
            function fmtVal(c,v){return c=='run'?clock(v)+'/km':dec(v.toFixed(1))+' km/h'}
            function better(c,a,b){return c=='run'?a<b:a>b}
            function km(m){return dec((m/1000).toFixed(m<10000?2:1).replace(/0$/,''))+' km'}
            var tip=document.getElementById('tip');
            function showTip(html,x,y){tip.innerHTML=html;tip.hidden=false;var w=tip.offsetWidth,h=tip.offsetHeight;
              tip.style.left=Math.max(8,Math.min(innerWidth-w-8,x+14))+'px';tip.style.top=(y+h+20>innerHeight?y-h-14:y+16)+'px'}
            function hideTip(){tip.hidden=true}

            var SEGS={};
            ['run','ride'].forEach(function(c){SEGS[c]=(D[c]||[]).map(function(s,i){
              var seg={c:c,no:i+1,name:s.name||('Segment '+(i+1)),dir:s.dir,m:s.m,climb:s.climb,pts:decode(s.line)};
              seg.efforts=s.efforts.map(function(e){return {date:e[0],id:e[1],secs:e[2],time:e[3],name:e[4],n:dayNo(e[0])}});
              seg.efforts.forEach(function(e){e.v=val(c,seg,e.secs)});
              seg.best=seg.efforts.reduce(function(b,e){return !b||e.secs<b.secs?e:b},null);
              seg.title=seg.name+L(', heading '+seg.dir,', richting '+(DIR_NL[seg.dir]||seg.dir));
              return seg})});
            var CLASSES=['run','ride'].filter(function(c){return SEGS[c].length});

            // ---- the sentence: this year (or the latest year) against the first year with enough efforts
            function byYear(seg){var y={};seg.efforts.forEach(function(e){var k=+e.date.slice(0,4);(y[k]=y[k]||[]).push(e.v)});
              var out=[];Object.keys(y).forEach(function(k){if(y[k].length>=3)out.push({y:+k,n:y[k].length,med:median(y[k])})});
              return out.sort(function(a,b){return a.y-b.y})}
            function headline(seg){
              var c=seg.c,ys=byYear(seg),all=seg.efforts.map(function(e){return e.v});
              if(!ys.length)return L('Your median is '+fmtVal(c,median(all))+' over '+seg.efforts.length+' times through.',
                'Je mediaan over '+seg.efforts.length+' keer is '+fmtVal(c,median(all))+'.');
              var last=ys[ys.length-1],first=ys[0],when=last.y==YEAR?L('this year','dit jaar'):'in '+last.y;
              if(first.y==last.y)return L('Your median ','Je mediaan ')+when+L(' is ',' is ')+fmtVal(c,last.med)+'.';
              var d=last.med-first.med,txt,same=L('the same as in ','hetzelfde als in ')+first.y,than=L(' than in ',' dan in ')+first.y;
              function fs(x){return x?L('faster','sneller'):L('slower','trager')}
              if(c=='run'){var s=Math.round(Math.abs(d));txt=s<1?same:s+' s '+fs(d<0)+than}
              else{var k=Math.abs(d);txt=k<.05?same:dec(k.toFixed(1))+' km/h '+fs(d>0)+than}
              var s2='',top=ys.reduce(function(b,y){return !b||better(c,y.med,b.med)?y:b},null);
              if(top&&top.y!=last.y&&top.y!=first.y)s2=L(' Your fastest year was '+top.y+', at ',' Je snelste jaar was '+top.y+', met ')+fmtVal(c,top.med)+'.';
              return L('Your median ','Je mediaan ')+when+' is '+fmtVal(c,last.med)+', '+txt+'.'+s2;
            }

            // ---- the hero map
            var map=L.map('map',{zoomControl:false,scrollWheelZoom:false,worldCopyJump:false});
            L.tileLayer(TILES,{maxZoom:19,attribution:ATTR}).addTo(map);
            L.control.zoom({position:'topright'}).addTo(map);
            var heroLayer=L.layerGroup().addTo(map),current=null;
            function pinIcon(seg,big){return L.divIcon({className:'',iconSize:[24,24],iconAnchor:[12,12],
              html:'<span class="pin" style="background:'+COLOR[seg.c]+'">'+seg.no+'</span>'})}
            function drawHero(c){heroLayer.clearLayers();var b=null;
              SEGS[c].forEach(function(seg){
                var glow=L.polyline(seg.pts,{color:COLOR[c],weight:10,opacity:.18,interactive:false});
                var line=L.polyline(seg.pts,{color:COLOR[c],weight:4,opacity:.95});
                var hit=L.polyline(seg.pts,{color:'#000',weight:18,opacity:0});
                var mid=seg.pts[seg.pts.length>>1],pin=L.marker(mid,{icon:pinIcon(seg),keyboard:false,title:seg.title});
                [hit,pin].forEach(function(l){
                  l.on('mouseover',function(e){line.setStyle({weight:6,color:'#eef1f7'});var o=e.originalEvent;
                    showTip('<b>'+seg.no+'. '+esc(seg.title)+'</b><br>'+km(seg.m)+(seg.climb>=10?', '+seg.climb+L(' m climb',' m klimmen'):'')+'<br>'
                      +seg.efforts.length+L(' times through',' keer')+'<br><span>'+L('Click to see them','Klik om ze te zien')+'</span>',o.clientX,o.clientY)});
                  l.on('mouseout',function(){line.setStyle({weight:4,color:COLOR[c]});hideTip()});
                  l.on('click',function(){hideTip();go(seg)})});
                heroLayer.addLayer(glow);heroLayer.addLayer(line);heroLayer.addLayer(hit);heroLayer.addLayer(pin);
                // Frame the segments around the most used one; one far away (a holiday) stays a zoom-out away.
                var home=SEGS[c][0].pts[SEGS[c][0].pts.length>>1];
                if(map.distance(home,mid)<40000)b=b?b.extend(line.getBounds()):line.getBounds()});
              if(b){var wide=innerWidth>800;map.fitBounds(b,{paddingTopLeft:[wide?Math.min(560,innerWidth*.42):24,80],
                paddingBottomRight:[40,wide?40:Math.min(360,innerHeight*.45)],maxZoom:15,animate:false})}
              else map.setView([50.85,4.35],10)}
            function go(seg){var el=document.getElementById('seg-'+seg.c+'-'+seg.no);if(!el)return;
              el.scrollIntoView({behavior:reduced?'auto':'smooth',block:'start'});el.focus({preventScroll:true});
              try{history.replaceState(null,'','#'+seg.c+'-'+seg.no)}catch(e){}}

            // ---- one card per segment
            var list=document.getElementById('list'),minis=[],io=window.IntersectionObserver?new IntersectionObserver(function(es){
              es.forEach(function(e){if(e.isIntersecting){io.unobserve(e.target);miniMap(e.target)}})},{rootMargin:'300px'}):null;
            function miniMap(el){var seg=el._seg;if(el._map)return;
              var m=L.map(el,{scrollWheelZoom:false,zoomControl:true,attributionControl:true});el._map=m;
              L.tileLayer(TILES,{maxZoom:19,attribution:ATTR}).addTo(m);
              L.polyline(seg.pts,{color:COLOR[seg.c],weight:10,opacity:.2,interactive:false}).addTo(m);
              var line=L.polyline(seg.pts,{color:COLOR[seg.c],weight:4,opacity:1,interactive:false}).addTo(m);
              L.circleMarker(seg.pts[0],{radius:6,color:'#eef1f7',weight:2,fillColor:'#0a1220',fillOpacity:1,interactive:false}).addTo(m);
              L.circleMarker(seg.pts[seg.pts.length-1],{radius:6,color:'#0a1220',weight:2,fillColor:COLOR[seg.c],fillOpacity:1,interactive:false}).addTo(m);
              m.fitBounds(line.getBounds(),{padding:[26,26],maxZoom:17})}
            function card(seg){
              var c=seg.c,li=document.createElement('li'),es=seg.efforts,vals=es.map(function(e){return e.v}),
                  years=es.length?es[0].date.slice(0,4)+(es[es.length-1].date.slice(0,4)!=es[0].date.slice(0,4)?'–'+es[es.length-1].date.slice(0,4):''):'',
                  fastest=es.slice().sort(function(a,b){return a.secs-b.secs}).slice(0,3),latest=es[es.length-1];
              li.className='card';li.id='seg-'+c+'-'+seg.no;li.tabIndex=-1;li.setAttribute('aria-labelledby','h-'+li.id);
              li.innerHTML='<div class="mini" role="img" aria-label="'+L('Map of segment ','Kaart van segment ')+seg.no+'"></div>'
                +'<div class="info"><h2 id="h-'+li.id+'"><span class="pin" style="background:'+COLOR[c]+'">'+seg.no+'</span>'+esc(seg.title)+'</h2>'
                +'<p class="meta">'+km(seg.m)+(seg.climb>=10?' · '+seg.climb+L(' m climb',' m klimmen'):'')+L(' · from ',' · van ')+'<i class="from"></i>'+L(' to ',' naar ')+'<i class="to" style="background:'+COLOR[c]+'"></i></p>'
                +'<p class="say">'+esc(headline(seg))+'</p>'
                +'<dl class="facts"><div><dt>'+L('Times through','Aantal keer')+'</dt><dd>'+fmtInt(es.length)+'<small>'+years+'</small></dd></div>'
                +'<div><dt>'+L('Best','Beste')+'</dt><dd>'+clock(seg.best.secs)+'<small>'+fmtVal(c,seg.best.v)+', '+fmtDate(seg.best.date)+'</small></dd></div>'
                +'<div><dt>'+L('Median','Mediaan')+'</dt><dd>'+clock(median(es.map(function(e){return e.secs})))+'<small>'+fmtVal(c,median(vals))+'</small></dd></div></dl></div>'
                +'<div class="chart"><p>'+(c=='run'?L('Pace per time through, in minutes per km','Tempo per keer, in minuten per km'):L('Speed per time through, in km/h','Snelheid per keer, in km/h'))
                +L('; faster is higher. Point at a dot to see it, click to open its day.','; sneller is hoger. Wijs een punt aan om het te zien, klik om die dag te openen.')+'</p>'
                +'<svg role="img" aria-label="'+L('Every time through segment '+seg.no+', '+(c=='run'?'pace':'speed')+' by date, with the rolling median',
                  'Elke keer door segment '+seg.no+', '+(c=='run'?'tempo':'snelheid')+' per datum, met de voortschrijdende mediaan')+'"></svg>'
                +'<ul class="keys"><li><i style="background:'+COLOR[c]+'"></i>'+L('One time through','Eén keer')+'</li><li><i class="ln"></i>'+L('Median of the year around it','Mediaan van het jaar eromheen')
                +'</li><li><i class="best"></i>'+L('Your best','Je beste')+'</li></ul>'
                +'<p class="fast">'+L('Fastest: ','Snelste: ')+fastest.map(function(e){return '<a href="'+href(e)+'">'+clock(e.secs)+L(' on ',' op ')+fmtDate(e.date)+'</a>'}).join('')
                +(latest?L(' Latest: ',' Laatste: ')+'<a href="'+href(latest)+'">'+clock(latest.secs)+L(' on ',' op ')+fmtDate(latest.date)+'</a>':'')+'</p></div>';
              var mini=li.querySelector('.mini');mini._seg=seg;
              list.appendChild(li);
              if(io)io.observe(mini);else miniMap(mini);
              scatter(li.querySelector('svg'),seg);
            }
            function ticks(lo,hi,steps){var span=hi-lo,st=steps[steps.length-1];
              for(var i=0;i<steps.length;i++)if(span/steps[i]<=5){st=steps[i];break}
              var out=[];for(var v=Math.ceil(lo/st)*st;v<=hi+1e-9;v+=st)out.push(+v.toFixed(3));return out}
            function scatter(svg,seg){
              var c=seg.c,es=seg.efforts,narrow=innerWidth<700,W=narrow?520:1000,H=narrow?280:270,L0=narrow?50:64,R=12,T=12,B=28,FS=narrow?15:13;if(!es.length)return;
              var vs=es.map(function(e){return e.v}).sort(function(a,b){return a-b});
              function q(p){return vs[Math.min(vs.length-1,Math.max(0,Math.round(p*(vs.length-1))))]}
              var lo=Math.min(q(.01),seg.best.v),hi=Math.max(q(.99),seg.best.v),pd=(hi-lo)*.1||(c=='run'?10:1);lo-=pd;hi+=pd;
              var n0=es[0].n-20,n1=es[es.length-1].n+20;if(n1-n0<120){n0-=60;n1+=60}
              function X(n){return L0+(n-n0)/(n1-n0)*(W-L0-R)}
              function Y(v){v=Math.max(lo,Math.min(hi,v));var f=(v-lo)/(hi-lo);return c=='run'?T+f*(H-T-B):T+(1-f)*(H-T-B)}
              var s='';
              ticks(lo,hi,c=='run'?[5,10,15,20,30,60,120,300]:[.5,1,2,2.5,5,10]).forEach(function(v){var y=Y(v);
                s+='<line x1="'+L0+'" x2="'+(W-R)+'" y1="'+y.toFixed(1)+'" y2="'+y.toFixed(1)+'" stroke="#1d2a44"/>'
                  +'<text x="'+(L0-10)+'" y="'+(y+4).toFixed(1)+'" fill="#76829b" font-size="'+FS+'" text-anchor="end">'+(c=='run'?clock(v):dec(v%1?v.toFixed(1):v))+'</text>'});
              var y0=new Date(n0*864e5).getUTCFullYear()+1,y1=new Date(n1*864e5).getUTCFullYear(),step=Math.max(1,Math.ceil((y1-y0+1)/10));
              for(var y=y0;y<=y1;y++){var x=X(Date.UTC(y,0,1)/864e5);
                s+='<line x1="'+x.toFixed(1)+'" x2="'+x.toFixed(1)+'" y1="'+T+'" y2="'+(H-B)+'" stroke="#1d2a44" stroke-dasharray="2 4"/>';
                if((y-y0)%step==0)s+='<text x="'+(x+4).toFixed(1)+'" y="'+(H-8)+'" fill="#76829b" font-size="'+FS+'">'+y+'</text>'}
              if(y0>y1)s+='<text x="'+L0+'" y="'+(H-8)+'" fill="#76829b" font-size="'+FS+'">'+new Date(n0*864e5).getUTCFullYear()+'</text>';
              es.forEach(function(e){s+='<circle cx="'+X(e.n).toFixed(1)+'" cy="'+Y(e.v).toFixed(1)+'" r="3.4" fill="'+COLOR[c]+'" fill-opacity=".62"/>'});
              // Rolling median: the middle of every time through within half a year either side, broken where a year passed without any.
              var path='',prev=null,j0=0,j1=0;
              es.forEach(function(e,i){while(es[j0].n<e.n-182)j0++;while(j1<es.length&&es[j1].n<=e.n+182)j1++;
                if(j1-j0<3){prev=null;return}
                var m=median(es.slice(j0,j1).map(function(x){return x.v}));
                path+=(prev!=null&&e.n-prev<=365?'L':'M')+X(e.n).toFixed(1)+' '+Y(m).toFixed(1);prev=e.n});
              s+='<path d="'+path+'" fill="none" stroke="#0a1220" stroke-width="5" stroke-linejoin="round" stroke-linecap="round" opacity=".7"/>';
              s+='<path d="'+path+'" fill="none" stroke="#eef1f7" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round"/>';
              s+='<circle cx="'+X(seg.best.n).toFixed(1)+'" cy="'+Y(seg.best.v).toFixed(1)+'" r="7" fill="none" stroke="#eef1f7" stroke-width="2"/>';
              s+='<circle class="hl" r="6" fill="'+COLOR[c]+'" stroke="#eef1f7" stroke-width="2" visibility="hidden"/>';
              svg.setAttribute('viewBox','0 0 '+W+' '+H);svg.innerHTML=s;
              var hl=svg.querySelector('.hl'),hot=null;
              function near(ev){var r=svg.getBoundingClientRect(),sx=r.width/W,sy=r.height/H,px=(ev.clientX-r.left)/sx,py=(ev.clientY-r.top)/sy,best=null,bd=16;
                es.forEach(function(e){var d=Math.hypot((X(e.n)-px)*sx,(Y(e.v)-py)*sy);if(d<bd){bd=d;best=e}});return best}
              svg.addEventListener('mousemove',function(ev){var e=near(ev);hot=e;
                if(!e){hl.setAttribute('visibility','hidden');svg.style.cursor='default';hideTip();return}
                svg.style.cursor='pointer';hl.setAttribute('cx',X(e.n));hl.setAttribute('cy',Y(e.v));hl.setAttribute('visibility','visible');
                showTip('<b>'+weekday(e.date)+' '+fmtDate(e.date)+(e.time?', '+e.time:'')+'</b><br>'+clock(e.secs)+' · '+fmtVal(c,e.v)
                  +(e===seg.best?L(' · your best',' · je beste'):'')+'<br>'+esc(e.name||WORD[c])+'<br><span>'+L('Click to open the day','Klik om de dag te openen')+'</span>',ev.clientX,ev.clientY)});
              svg.addEventListener('mouseleave',function(){hot=null;hl.setAttribute('visibility','hidden');hideTip()});
              svg.addEventListener('click',function(ev){var e=near(ev)||hot;if(e)location.href=href(e)});
            }

            // ---- choosing a sport
            var pick=document.getElementById('pick');
            pick.innerHTML=CLASSES.map(function(c){return '<button type="button" data-c="'+c+'"><i style="background:'+COLOR[c]+'"></i>'
              +NAME[c]+', '+SEGS[c].length+'</button>'}).join('');
            pick.onclick=function(e){var b=e.target.closest('button');if(b)show(b.dataset.c,true)};
            function show(c,user){current=c;
              pick.querySelectorAll('button').forEach(function(b){b.setAttribute('aria-pressed',b.dataset.c==c)});
              var segs=SEGS[c],eff=segs.reduce(function(s,x){return s+x.efforts.length},0);
              document.getElementById('lede').textContent=L('The '+segs.length+' stretches you have gone '+WORD[c]+' through most often, found in your own tracks, and '
                +fmtInt(eff)+' times through them from start to finish without stopping long. Not a race against anyone: are you getting faster, or older?',
                'De '+segs.length+' stukken die je het vaakst '+(c=='run'?'gelopen':'gefietst')+' hebt, gevonden in je eigen sporen, en '
                +fmtInt(eff)+' keer erdoor van begin tot eind zonder lang te stoppen. Geen wedstrijd tegen anderen: word je sneller, of ouder?');
              list.innerHTML='';segs.forEach(card);drawHero(c);
              if(user){try{history.replaceState(null,'','#'+c)}catch(e){}try{localStorage.setItem('garmin-segments-sport',c)}catch(e){}}
            }
            if(!CLASSES.length){document.getElementById('lede').textContent=L('No segments yet: there are not enough repeated runs or rides with GPS.','Nog geen segmenten: er zijn niet genoeg herhaalde loopjes of ritten met GPS.');
              map.setView([50.85,4.35],10);return}
            var h=/^#(run|ride)(?:-(\\d+))?$/.exec(location.hash),saved=null;
            try{saved=localStorage.getItem('garmin-segments-sport')}catch(e){}
            var start=h&&SEGS[h[1]].length?h[1]:(saved&&SEGS[saved]&&SEGS[saved].length?saved:CLASSES[0]);
            show(start,false);
            if(h&&h[2]){var seg=SEGS[start][+h[2]-1];if(seg)setTimeout(function(){go(seg)},50)}
            window.addEventListener('resize',function(){if(current)drawHero(current)});
            })();
            """;
}
