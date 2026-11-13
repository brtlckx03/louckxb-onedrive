package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static net.lckx.garmin.DescribeGarminFile.esc;
import static net.lckx.garmin.DescribeGarminFile.jsStr;

/**
 * "Together": the activities two accounts did together, found from the GPS tracks.
 * <p>Two activities count as <em>side by side</em> when, for at least ten minutes, both watches were within
 * {@value #NEAR} m of each other at the same moment. They count as the <em>same event</em> when they started at the
 * same place and time but then went their own way (a race, a group ride). Writes {@code together-NAME.html} for every
 * other account with data. The downloaded files are not touched; results are cached per pair of files.
 * <pre>
 * java --enable-preview -cp target/classes net.lckx.garmin.Together [--account NAME]
 * </pre>
 */
public final class Together {
    private static final String CACHE_VERSION = "t2";
    /** Closer than this at the same moment counts as together (metres): GPS error plus a few bike lengths. */
    private static final double NEAR = 80;
    /** Points of the two tracks are compared when recorded at most this far apart in time (seconds). */
    private static final long MAX_DT = 20;
    private static final long SIDE_BY_SIDE_MIN = 600, SHORT_MIN = 300;
    private static final double EVENT_START_M = 1000;
    private static final long EVENT_START_S = 30 * 60;
    /** The replay uses a point every this many seconds. */
    private static final long REPLAY_STEP = 10;

    private static final Pattern JSON_FILE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})_(\\d+)_.*\\.json");
    private static final Pattern TRKPT = Pattern.compile(
            "<trkpt\\s+lat=\"([-0-9.eE]+)\"\\s+lon=\"([-0-9.eE]+)\"[^>]*>(.*?)</trkpt>", Pattern.DOTALL);
    private static final Pattern TIME = Pattern.compile("<time>([^<]+)</time>");

    private Together() {}

    public static void main(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--account")) DescribeGarminFile.useAccount(args[++i]);
            else {
                System.err.println("Usage: Together [--account NAME]");
                System.exit(1);
            }
        }
        write();
    }

    static String pageFile(String partner) {
        return "together-" + partner + ".html";
    }

    /** The other accounts that have downloaded data, for the links on the overview. */
    private static List<String> accounts;

    /** Every account folder with data (garmin, garmin-NAME holding an overview), listed once per run. */
    static synchronized List<String> accounts() {
        if (accounts != null) return accounts;
        List<String> out = new ArrayList<>();
        Path root = DescribeGarminFile.dataDir(DescribeGarminFile.MAIN_ACCOUNT).getParent();
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.list(root)) {
                s.filter(Files::isDirectory).sorted().forEach(p -> {
                    String n = p.getFileName().toString();
                    String name = n.equals("garmin") ? DescribeGarminFile.MAIN_ACCOUNT : n.startsWith("garmin-") ? n.substring(7) : null;
                    if (name != null && name.matches("[a-z0-9_-]+") && Files.exists(p.resolve("index.html"))) out.add(name);
                });
            } catch (IOException ignored) {
                // no accounts then
            }
        }
        return accounts = List.copyOf(out);
    }

    /** The other accounts that have downloaded data, for the menu and the Together pages. */
    static List<String> partners() {
        return accounts().stream().filter(a -> !a.equals(DescribeGarminFile.account())).toList();
    }

    // ---- activities ------------------------------------------------------------------------------------------

    record Act(String key, long mtime, long id, String date, String time, String sport, String name, double km, double secs,
               long start, long end) {}

    record Pt(long t, double lat, double lon) {}

    /** Every activity with a GPX file, from the cache when its JSON did not change. */
    private static List<Act> activities(Path dir, Map<String, String> cache, Map<String, String> fresh) throws IOException {
        List<Act> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Matcher m = JSON_FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                Path gpx = p.resolveSibling(p.getFileName().toString().replaceFirst("\\.json$", ".gpx"));
                if (!Files.exists(gpx)) continue;
                String key = dir.getFileName() + "/" + dir.relativize(gpx).toString().replace('\\', '/');
                // Activities imported from Strava can be copies of the other account's recording (one watch, two
                // accounts): two watches side by side is what this page is about, so they are left out.
                if (dir.relativize(gpx).startsWith(StravaImport.FOLDER)) continue;
                long mtime = Files.getLastModifiedTime(p).toMillis();
                String line = cache.get("A\t" + key);
                Act a = line != null ? parseAct(key, line) : null;
                if (a == null || a.mtime != mtime) {
                    a = readAct(p, key, mtime, m.group(1), Long.parseLong(m.group(2)));
                    if (a == null) continue;
                }
                if (DescribeGarminFile.Overview.HIDDEN_SPORTS.contains(a.sport)) continue;
                fresh.put("A\t" + key, actLine(a));
                out.add(a);
            }
        }
        return out;
    }

    private static Act readAct(Path json, String key, long mtime, String date, long id) {
        try {
            Map<?, ?> j = (Map<?, ?>) DescribeGarminFile.Json.parse(Files.readString(json));
            String gmt = str(get(j, "summaryDTO", "startTimeGMT")), local = str(get(j, "summaryDTO", "startTimeLocal"));
            double secs = number(get(j, "summaryDTO", "duration")), metres = number(get(j, "summaryDTO", "distance"));
            if (gmt.length() < 19 || secs <= 0) return null;
            long start = LocalDateTime.parse(gmt.substring(0, 19)).toEpochSecond(ZoneOffset.UTC);
            String sport = SportCorrection.sport(id, str(get(j, "activityTypeDTO", "typeKey")), metres, secs);
            return new Act(key, mtime, id, date, local.length() >= 16 ? local.substring(11, 16) : "", sport,
                    str(get(j, "activityName")), metres / 1000, secs, start, start + Math.round(secs));
        } catch (Exception e) {
            return null;
        }
    }

    private static String actLine(Act a) {
        return a.mtime + "\t" + a.id + "\t" + a.date + "\t" + a.time + "\t" + a.sport + "\t" + a.km + "\t" + a.secs + "\t"
                + a.start + "\t" + a.end + "\t" + a.name.replace('\t', ' ').replace('\n', ' ');
    }

    private static Act parseAct(String key, String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 10) return null;
        try {
            return new Act(key, Long.parseLong(f[0]), Long.parseLong(f[1]), f[2], f[3], f[4], f[9], Double.parseDouble(f[5]),
                    Double.parseDouble(f[6]), Long.parseLong(f[7]), Long.parseLong(f[8]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Pt> points(Path root, Act a) throws IOException {
        String xml = Files.readString(root.resolve(a.key));
        List<Pt> out = new ArrayList<>();
        Matcher m = TRKPT.matcher(xml);
        while (m.find()) {
            Matcher t = TIME.matcher(m.group(3));
            if (!t.find()) continue;
            try {
                out.add(new Pt(Instant.parse(t.group(1).trim()).getEpochSecond(), Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))));
            } catch (Exception ignored) {
                // a point without a readable time cannot be compared
            }
        }
        out.sort(Comparator.comparingLong(Pt::t));
        return out;
    }

    // ---- comparing two tracks --------------------------------------------------------------------------------

    /** The result for one pair: what the page needs, or null when they were not together. */
    private static String compare(Path root, Act a, Act b) throws IOException {
        List<Pt> pa = points(root, a), pb = points(root, b);
        if (pa.size() < 2 || pb.size() < 2) return null;
        long[] ta = pa.stream().mapToLong(Pt::t).toArray();
        long together = 0, compared = 0, prev = Long.MIN_VALUE;
        double togetherM = 0;
        Pt lastTogether = null;
        List<List<Pt>> segs = new ArrayList<>();
        List<Pt> seg = null;
        for (Pt p : pb) {
            if (prev != Long.MIN_VALUE && p.t - prev < 5) continue;
            long step = prev == Long.MIN_VALUE ? 5 : Math.min(30, p.t - prev);
            prev = p.t;
            int i = Arrays.binarySearch(ta, p.t);
            if (i < 0) i = -i - 1;
            Pt best = null;
            for (int k = i - 1; k <= i; k++) {
                if (k < 0 || k >= pa.size() || Math.abs(pa.get(k).t - p.t) > MAX_DT) continue;
                if (best == null || Math.abs(pa.get(k).t - p.t) < Math.abs(best.t - p.t)) best = pa.get(k);
            }
            if (best == null) {
                seg = null;
                continue;
            }
            compared += step;
            if (dist(p, best) <= NEAR) {
                together += step;
                if (lastTogether != null && seg != null) togetherM += dist(lastTogether, p);
                if (seg == null) segs.add(seg = new ArrayList<>());
                seg.add(p);
                lastTogether = p;
            } else {
                seg = null;
                lastTogether = null;
            }
        }
        String kind;
        if (together >= SIDE_BY_SIDE_MIN || (together >= SHORT_MIN && together >= compared / 2)) kind = "t";
        else if (dist(pa.get(0), pb.get(0)) <= EVENT_START_M && Math.abs(pa.get(0).t - pb.get(0).t) <= EVENT_START_S) kind = "e";
        else return null;
        long t0 = Math.min(pa.get(0).t, pb.get(0).t);
        StringBuilder o = new StringBuilder("{k:").append(jsStr(kind)).append(",ts:").append(together)
                .append(",tk:").append(String.format(Locale.ROOT, "%.2f", togetherM / 1000)).append(",t0:").append(t0)
                .append(",ra:").append(replay(pa, t0)).append(",rb:").append(replay(pb, t0)).append(",seg:[");
        boolean first = true;
        for (List<Pt> s : segs) {
            if (s.size() < 2) continue;
            o.append(first ? "" : ",").append(jsStr(Heatmap.encode(s.stream().map(p -> new double[]{p.lat, p.lon}).toList())));
            first = false;
        }
        return o.append("]}").toString();
    }

    /** A track every {@value #REPLAY_STEP} s: [encoded positions, encoded seconds since t0]. */
    private static String replay(List<Pt> pts, long t0) {
        List<double[]> pos = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        long last = Long.MIN_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            Pt p = pts.get(i);
            if (last != Long.MIN_VALUE && p.t - last < REPLAY_STEP && i < pts.size() - 1) continue;
            pos.add(new double[]{p.lat, p.lon});
            times.add(p.t - t0);
            last = p.t;
        }
        StringBuilder t = new StringBuilder();
        long prev = 0;
        for (long v : times) {
            Heatmap.encodeValue(t, v - prev);
            prev = v;
        }
        return "[" + jsStr(Heatmap.encode(pos)) + "," + jsStr(t.toString()) + "]";
    }

    private static double dist(Pt a, Pt b) {
        double dy = (a.lat - b.lat) * 110540, dx = (a.lon - b.lon) * 111320 * Math.cos(Math.toRadians((a.lat + b.lat) / 2));
        return Math.hypot(dx, dy);
    }

    // ---- the page --------------------------------------------------------------------------------------------

    static void write() throws IOException {
        for (String partner : partners()) {
            try {
                write(partner);
            } catch (Exception e) {
                System.err.println("Together with " + partner + " not updated: " + e.getMessage());
            }
        }
    }

    private static void write(String partner) throws IOException {
        long t0 = System.nanoTime();
        String me = DescribeGarminFile.account();
        Path dirA = DescribeGarminFile.dataDir(me), dirB = DescribeGarminFile.dataDir(partner), root = dirA.getParent();
        Path cacheFile = dirA.resolve("together-" + partner + "-cache.tsv");
        SportCorrection.dropIfStale(cacheFile);
        Map<String, String> cache = readCache(cacheFile), fresh = new LinkedHashMap<>();
        List<Act> as = activities(dirA, cache, fresh), bs = activities(dirB, cache, fresh);
        if (as.isEmpty() || bs.isEmpty()) return;
        Map<String, List<Act>> bByDate = new HashMap<>();
        for (Act b : bs) bByDate.computeIfAbsent(b.date, k -> new ArrayList<>()).add(b);
        record Found(Act a, Act b, String json, long together, String kind) {}
        List<Found> found = new ArrayList<>();
        int compared = 0;
        for (Act a : as) {
            for (Act b : bByDate.getOrDefault(a.date, List.of())) {
                if (Math.min(a.end, b.end) - Math.max(a.start, b.start) < SHORT_MIN) continue;
                String key = "P\t" + a.key + "\t" + b.key, want = a.mtime + "\t" + b.mtime + "\t";
                String line = cache.get(key), json;
                if (line != null && line.startsWith(want)) json = line.substring(want.length());
                else {
                    String r = compare(root, a, b);
                    json = r == null ? "-" : r;
                    compared++;
                }
                fresh.put(key, want + json);
                if (json.equals("-")) continue;
                Matcher ts = Pattern.compile("ts:(\\d+)").matcher(json), k = Pattern.compile("k:\"(\\w)\"").matcher(json);
                found.add(new Found(a, b, json, ts.find() ? Long.parseLong(ts.group(1)) : 0, k.find() ? k.group(1) : "e"));
            }
        }
        writeCache(cacheFile, fresh);
        // One outing per activity: a watch and a bike computer recording the same ride count once, with the best match.
        found.sort(Comparator.comparing((Found f) -> f.kind.equals("t") ? 0 : 1).thenComparing(f -> -f.together));
        Set<String> used = new HashSet<>();
        TreeMap<String, Found> outings = new TreeMap<>();
        for (Found f : found) {
            if (!used.add(f.a.key) | !used.add(f.b.key)) continue;
            outings.put(f.a.date + " " + f.a.time + " " + f.a.id, f);
        }
        String toB = "../" + dirB.getFileName() + "/";
        StringBuilder js = new StringBuilder("window.TOGETHER=[");
        boolean first = true;
        for (Found f : outings.values()) {
            js.append(first ? "\n" : ",\n").append("{d:").append(jsStr(f.a.date)).append(",a:").append(side(f.a, ""))
                    .append(",b:").append(side(f.b, toB)).append(',').append(f.json, 1, f.json.length());
            first = false;
        }
        js.append("\n];\n");
        String dataFile = "together-" + partner + "-data.js";
        replace(dirA.resolve(dataFile), js.toString());
        Path page = dirA.resolve(pageFile(partner));
        replace(page, page(me, partner, dataFile));
        long side = outings.values().stream().filter(f -> f.kind.equals("t")).count();
        System.out.printf("Together with %s: %d outings side by side, %d at the same event (%d pairs compared) in %.1fs.%n",
                DescribeGarminFile.accountLabelOf(partner), side, outings.size() - side, compared, (System.nanoTime() - t0) / 1e9);
    }

    private static String side(Act a, String prefix) {
        String href = prefix + a.date.substring(0, 4) + "/" + a.date.substring(5, 7) + "/" + a.date + "-report.html#a" + a.id;
        return "{s:" + jsStr(a.sport) + ",g:" + jsStr(DescribeGarminFile.Overview.sportGroup(a.sport)) + ",i:" + jsStr(DescribeGarminFile.sportIcon(a.sport))
                + ",n:" + jsStr(a.name) + ",t:" + jsStr(a.time) + ",km:" + String.format(Locale.ROOT, "%.2f", a.km)
                + ",secs:" + Math.round(a.secs) + ",h:" + jsStr(href) + "}";
    }

    private static Map<String, String> readCache(Path f) {
        Map<String, String> out = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(CACHE_VERSION)) return out;
            for (String l : lines.subList(1, lines.size())) {
                // "A\tkey\t…" or "P\tkeyA\tkeyB\t…"
                int cut = l.startsWith("P\t") ? nth(l, '\t', 3) : nth(l, '\t', 2);
                if (cut > 0) out.put(l.substring(0, cut), l.substring(cut + 1));
            }
        } catch (IOException e) {
            // no cache yet
        }
        return out;
    }

    private static int nth(String s, char c, int n) {
        int i = -1;
        for (int k = 0; k < n; k++) if ((i = s.indexOf(c, i + 1)) < 0) return -1;
        return i;
    }

    private static void writeCache(Path f, Map<String, String> entries) throws IOException {
        StringBuilder b = new StringBuilder(CACHE_VERSION).append('\n');
        entries.forEach((k, v) -> b.append(k).append('\t').append(v).append('\n'));
        replace(f, b.toString());
    }

    private static void replace(Path f, String content) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Object get(Object o, String... path) {
        for (String p : path) {
            if (!(o instanceof Map<?, ?> m)) return null;
            o = m.get(p);
        }
        return o;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String page(String me, String partner, String dataFile) {
        String a = DescribeGarminFile.accountLabelOf(me), b = DescribeGarminFile.accountLabelOf(partner);
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + DescribeGarminFile.favicon("#7a5cd6")
                + "<title>Together · " + esc(a) + " and " + esc(b) + "</title>" + Site.HEAD
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"><link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:wght@400;700"
                + "&family=Big+Shoulders+Display:wght@500;700;800&display=swap\">"
                + "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\">"
                + "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>"
                + "<style>" + CSS + "</style></head><body>"
                + BODY.replace("{{NAV}}", Site.nav(pageFile(partner), 0)).replace("{{A}}", esc(a)).replace("{{B}}", esc(b))
                .replace("{{UPDATED}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH).format(LocalDateTime.now()))
                .replace("{{UPDATED_NL}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.of("nl")).format(LocalDateTime.now()))
                + "<script src=\"" + dataFile + "\"></script><script>var NAME_A=" + jsStr(a) + ",NAME_B=" + jsStr(b) + ";" + JS + "</script></body></html>";
    }

    private static final String CSS = """
            :root{--night:#0a1220;--deep:#0f1a2e;--rule:#1d2a44;--fog:#a9b4ca;--moon:#eef1f7;--dim:#76829b;
              --a:#c98500;--b:#9085e9;--hover:#15223a;--sel:#1b2a47;
              --display:'Big Shoulders Display',Impact,'Arial Narrow',sans-serif;--text:'Atkinson Hyperlegible',system-ui,sans-serif;color-scheme:dark}
            *{box-sizing:border-box}
            html,body{margin:0;background:var(--night);color:var(--fog);font:17px/1.55 var(--text)}
            a{color:var(--moon)}
            :focus-visible{outline:2px solid var(--moon);outline-offset:2px}
            button{font:inherit;color:var(--moon);background:var(--deep);border:1px solid var(--rule);border-radius:999px;padding:6px 14px;cursor:pointer}
            button:hover{border-color:var(--dim)}
            button[aria-pressed=true]{background:var(--moon);color:var(--night);border-color:var(--moon)}
            .app{display:grid;grid-template-columns:minmax(0,1fr) 400px;height:calc(100vh - var(--site-h,44px));min-height:600px}
            .stage{position:relative;overflow:hidden}
            #map{position:absolute;inset:0;background:var(--night)}
            #map .leaflet-tile-pane{filter:grayscale(1) invert(1) brightness(.34) contrast(1.3) sepia(.35) hue-rotate(185deg);opacity:.6}
            .leaflet-container{font:13px var(--text)}
            .leaflet-top.leaflet-right{top:calc(var(--top-h,64px) - 10px)}
            .leaflet-control-attribution{background:rgba(10,18,32,.6)!important;color:var(--dim)!important}
            .leaflet-control-attribution a{color:var(--fog)!important}
            .top{position:absolute;left:0;right:0;top:0;z-index:600;display:flex;flex-direction:column;align-items:stretch;
              gap:10px;padding:12px 20px 30px 28px;pointer-events:none;background:linear-gradient(var(--night) 50%,rgba(10,18,32,0))}
            .top>*{pointer-events:auto}
            .title{font:800 22px/1 var(--display);color:var(--moon);margin:0;letter-spacing:.02em}
            .brand{display:flex;align-items:center;gap:16px}
            .mapmode{display:inline-flex;border:1px solid var(--rule);border-radius:999px;padding:2px;background:var(--deep)}
            .mapmode button{border:0;padding:4px 11px;font-size:13px;background:none}
            .mapmode button[aria-pressed=true]{background:var(--moon);color:var(--night)}
            .top .site-nav{order:-1;color:var(--moon);min-width:0;margin-left:-8px;font-size:13.5px;--site-active-bg:var(--moon);--site-active-fg:var(--night)}
            .top .site-nav a{padding:4px 8px}
            .top .site-seg a{padding:3px 8px}
            .top .site-switch{margin-left:auto}
            .hud{position:absolute;left:28px;bottom:28px;z-index:600;max-width:min(640px,calc(100% - 56px));pointer-events:none}
            .hud::before{content:'';position:absolute;z-index:-1;inset:-120px -220px -60px -120px;
              background:radial-gradient(closest-side,rgba(10,18,32,.9),rgba(10,18,32,.6) 55%,rgba(10,18,32,0))}
            .hud *{pointer-events:auto}
            #big{font:800 clamp(56px,9vw,124px)/.84 var(--display);color:var(--moon);margin:0}
            #big small{display:block;font-weight:500;font-size:.34em;color:var(--fog);margin:0 0 .3em .04em}
            #sub{margin:14px 0 0;font-size:16px;max-width:52ch}
            .who{display:flex;gap:18px;margin:12px 0 0;padding:0;list-style:none;font-size:15px}
            .who li{display:flex;align-items:center;gap:8px}
            .who i{width:22px;height:3px;border-radius:2px}
            .who .both{background:var(--moon);box-shadow:0 0 8px var(--moon)}
            #play{min-width:88px;font-weight:700}
            .ctrl{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-top:16px}
            .ctrl[hidden]{display:none}
            #clock{font:700 26px/1 var(--display);color:var(--moon);font-variant-numeric:tabular-nums;min-width:86px}
            #apart{font-size:15px;color:var(--fog);min-width:12ch}
            .log{background:var(--deep);border-left:1px solid var(--rule);overflow-y:auto;display:flex;flex-direction:column}
            .log header{padding:22px 22px 10px}
            .log h2{font:700 30px/1 var(--display);color:var(--moon);margin:0}
            .log header p{margin:6px 0 0;font-size:14px;color:var(--dim)}
            .filters{display:flex;gap:6px;margin-top:12px;flex-wrap:wrap}
            .filters button{padding:4px 11px;font-size:14px}
            #list{list-style:none;margin:0;padding:0 10px 30px}
            #list h3{font:700 20px/1 var(--display);color:var(--dim);margin:18px 12px 6px}
            #list button{display:block;width:100%;text-align:left;border:0;border-radius:10px;background:none;padding:9px 12px;color:var(--fog)}
            #list button:hover{background:var(--hover)}
            #list button[aria-current=true]{background:var(--sel);color:var(--moon)}
            #list b{display:block;color:var(--moon);font-weight:700;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            #list span{font-size:13px;color:var(--dim)}
            #list .ev b{font-weight:400}
            #detail{margin-top:10px;font-size:14px}
            #detail a{display:inline-block;margin-right:14px}
            main{max-width:1180px;margin:0 auto;padding:0 28px}
            section.part{padding:100px 0 20px}
            section.part h2{font:700 clamp(40px,6vw,64px)/.95 var(--display);color:var(--moon);margin:0 0 12px}
            .lede{max-width:62ch;margin:0 0 28px}
            #years{display:block;width:100%;max-width:1000px}
            .facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:24px 40px;margin:50px 0 0}
            .facts div{border-top:1px solid var(--rule);padding-top:12px}
            .facts dt{font-size:14px;color:var(--dim)}
            .facts dd{margin:2px 0 0;font:700 36px/1.05 var(--display);color:var(--moon)}
            .facts dd small{display:block;font:400 14px/1.4 var(--text);color:var(--fog);margin-top:4px}
            .words{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:18px 36px;margin:0;padding:0;list-style:none}
            .words li{border-left:3px solid var(--b);padding:2px 0 2px 14px}
            .words q{display:block;font:700 26px/1.1 var(--display);color:var(--moon);quotes:none}
            .words span{font-size:13px;color:var(--dim)}
            footer{border-top:1px solid var(--rule);margin-top:100px;padding:22px 0 40px;font-size:14px;color:var(--dim)}
            footer a{color:var(--fog);margin-right:16px}
            #tip{position:fixed;z-index:1000;pointer-events:none;background:var(--deep);border:1px solid var(--rule);border-radius:8px;
              padding:8px 11px;font-size:14px;color:var(--fog);box-shadow:0 8px 30px rgba(0,0,0,.45)}
            #tip b{color:var(--moon)}
            /* ---- light map: the map and the logbook go light, the story below stays dark */
            body.light .app{--night:#f2f2ee;--deep:#fbfbf8;--rule:#d8dad9;--fog:#3a4354;--moon:#1d2433;--dim:#5f6878;
              --a:#a86d00;--b:#6f62d6;--hover:#eceeea;--sel:#e1e5ee;color-scheme:light;color:var(--fog)}
            body.light #map .leaflet-tile-pane{filter:saturate(.45) contrast(.94) brightness(1.03);opacity:1}
            body.light .top{background:linear-gradient(rgba(242,242,238,.97) 45%,rgba(242,242,238,0))}
            body.light .hud::before{inset:-16px -20px -14px -18px;border-radius:16px;background:rgba(250,250,247,.84);
              -webkit-backdrop-filter:blur(6px);backdrop-filter:blur(6px);box-shadow:0 6px 28px rgba(29,36,51,.14)}
            body.light .who .both{box-shadow:0 0 0 2px #fff}
            body.light .leaflet-control-attribution{background:rgba(255,255,255,.75)!important}
            body.light .log{background:var(--deep)}
            @media (max-width:900px){.app{grid-template-columns:minmax(0,1fr);height:auto}.stage{height:80vh}.log{max-height:70vh;border-left:0;border-top:1px solid var(--rule)}
              .hud{left:16px;bottom:30px;max-width:calc(100% - 32px)}.top{padding:10px 16px 24px}main{padding:0 16px}}
            @media (max-width:520px){#big{font-size:56px}.brand{gap:10px}}
            """;

    private static final String BODY = """
            <div class="app">
              <section class="stage" aria-label="Map" data-nl-aria="Kaart">
                <div id="map"></div>
                <div class="top" id="top"><div class="brand"><p class="title" data-nl="Samen">Together</p>
                  <span class="mapmode" role="group" aria-label="Map style" data-nl-aria="Kaartstijl"><button type="button" data-theme="dark" data-nl="Donkere kaart">Dark map</button><button
                    type="button" data-theme="light" data-nl="Lichte kaart">Light map</button></span></div>
                  {{NAV}}</div>
                <div class="hud">
                  <p id="big"></p>
                  <p id="sub"></p>
                  <ul class="who"><li><i style="background:var(--a)"></i>{{A}}</li><li><i style="background:var(--b)"></i>{{B}}</li>
                    <li><i class="both"></i><span data-nl="zij aan zij">side by side</span></li></ul>
                  <div class="ctrl" id="ctrl" hidden>
                    <button id="play" type="button">Pause</button><span id="clock">0:00</span><span id="apart"></span>
                    <span role="group" aria-label="Replay speed" data-nl-aria="Afspeelsnelheid"><button type="button" data-speed="30">30×</button><button type="button" data-speed="120">120×</button>
                    <button type="button" data-speed="480">480×</button></span>
                    <button id="back" type="button" data-nl="Alle tochten">All outings</button>
                  </div>
                  <div id="detail"></div>
                </div>
              </section>
              <aside class="log" aria-label="Logbook" data-nl-aria="Logboek">
                <header><h2 data-nl="Logboek">Logbook</h2><p data-nl="Kies een tocht om ze opnieuw te bekijken.">Pick an outing to see it again.</p>
                  <div class="filters" role="group" aria-label="Show" data-nl-aria="Toon"><button type="button" data-kind="t" aria-pressed="true" data-nl="Zij aan zij">Side by side</button>
                  <button type="button" data-kind="e" aria-pressed="false" data-nl="Zelfde evenement">Same event</button></div></header>
                <ul id="list"></ul>
              </aside>
            </div>
            <main>
              <section class="part"><h2 data-nl="Jaar na jaar">Year after year</h2><p class="lede" id="yearsLede"></p>
                <svg id="years" role="img" aria-label="Outings together per year" data-nl-aria="Samen op pad per jaar"></svg>
                <dl class="facts" id="facts"></dl></section>
              <section class="part" id="wordsPart" hidden><h2 data-nl="In jullie eigen woorden">In your own words</h2>
                <p class="lede" data-nl="Hoe jullie deze tochten zelf noemden.">What you called these outings yourselves.</p><ul class="words" id="words"></ul></section>
              <footer><a href="index.html" data-nl="Overzicht">Overview</a><a href="years.html" data-nl="Jaren in beweging">Years in motion</a>
                <span data-nl="Gevonden uit de GPS-sporen van beide horloges op {{UPDATED_NL}}: zij aan zij betekent op hetzelfde moment binnen 80 m
                van elkaar, minstens tien minuten lang; zelfde evenement betekent dat jullie samen startten en daarna ieder je eigen weg gingen.">Found from the GPS tracks of both watches on {{UPDATED}}: side by side means within 80 m of each other at the same moment
                for at least ten minutes; same event means you started together and then each went your own way.</span></footer>
            </main>
            <div id="tip" hidden></div>
            """;

    private static final String JS = """
            (function(){
            'use strict';
            var ALL=window.TOGETHER||[],MOON='#eef1f7',NL=LANG=='nl';
            // The map in two styles. On light tiles the routes "side by side" turn to dark ink with a white halo, and the two
            // colours are a shade darker so they keep 3:1 contrast against the paper.
            var THEMES={dark:{a:'#c98500',b:'#9085e9',ink:MOON,halo:MOON,haloW:7,haloO:.07,lineO:.75,glowW:10,glowO:.14,ring:'#0a1220'},
                        light:{a:'#a86d00',b:'#6f62d6',ink:'#1d2433',halo:'#ffffff',haloW:6,haloO:.85,lineO:.85,glowW:8,glowO:.9,ring:'#ffffff'}};
            var THEME='dark',qm=/[?&]map=(dark|light)/.exec(location.search);try{if(localStorage.getItem('garmin-together-map')=='light')THEME='light'}catch(e){}
            if(qm)THEME=qm[1];
            if(NL)document.title='Samen · '+NAME_A+' en '+NAME_B;
            var TH=THEMES[THEME];document.body.classList.toggle('light',THEME=='light');
            var MONTHS=NL?['januari','februari','maart','april','mei','juni','juli','augustus','september','oktober','november','december']
              :['January','February','March','April','May','June','July','August','September','October','November','December'];
            var MON=NL?['jan','feb','mrt','apr','mei','jun','jul','aug','sep','okt','nov','dec']:MONTHS.map(function(m){return m.slice(0,3)});
            var WD=NL?['zondag','maandag','dinsdag','woensdag','donderdag','vrijdag','zaterdag']:['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];
            function cap(s){return s.charAt(0).toUpperCase()+s.slice(1)}
            function fx(v,d){var s=v.toFixed(d);return NL?s.replace('.',','):s}
            var reduced=window.matchMedia&&matchMedia('(prefers-reduced-motion: reduce)').matches;
            function fmtInt(v){return Math.round(v).toLocaleString('en-US').replace(/,/g,' ')}
            function fmtDate(s){return +s.slice(8,10)+' '+MON[+s.slice(5,7)-1]+' '+s.slice(0,4)}
            function longDate(s){var d=new Date(s+'T12:00:00Z');return cap(WD[d.getUTCDay()])+' '+(+s.slice(8,10))+' '+MONTHS[+s.slice(5,7)-1]+' '+s.slice(0,4)}
            function fmtDur(s){var h=Math.floor(s/3600),m=Math.round(s%3600/60);if(m==60){h++;m=0}return h?h+' h '+String(m).padStart(2,'0'):m+' min'}
            function clock(s){s=Math.max(0,Math.round(s));var h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;return h+':'+String(m).padStart(2,'0')+':'+String(x).padStart(2,'0')}
            function esc(s){return String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
            function ints(s){var out=[],i=0,v=0;while(i<s.length){var r=0,sh=0,b;do{b=s.charCodeAt(i++)-63;r|=(b&31)<<sh;sh+=5}while(b>=32);v+=(r&1)?~(r>>1):(r>>1);out.push(v)}return out}
            function line(s){var v=ints(s),out=[],la=0,lo=0;for(var i=0;i+1<v.length;i+=2)out.push([v[i]/1e5,v[i+1]/1e5]);return out}
            // ints() already sums the deltas; positions are stored as deltas too, so undo the running sum per coordinate.
            function positions(s){var v=[],i=0,lat=0,lon=0;
              while(i<s.length){for(var k=0;k<2;k++){var r=0,sh=0,b;do{b=s.charCodeAt(i++)-63;r|=(b&31)<<sh;sh+=5}while(b>=32);
                var d=(r&1)?~(r>>1):(r>>1);if(k==0)lat+=d;else lon+=d}v.push([lat/1e5,lon/1e5])}return v}
            function track(r){var p=positions(r[0]),t=ints(r[1]);return {p:p,t:t}}
            var tip=document.getElementById('tip');
            function showTip(h,x,y){tip.innerHTML=h;tip.hidden=false;tip.style.left=Math.min(innerWidth-tip.offsetWidth-8,x+14)+'px';tip.style.top=(y-tip.offsetHeight-12)+'px'}
            function hideTip(){tip.hidden=true}
            ALL.forEach(function(o,i){o.i=i;o.segs=o.seg.map(positions)});
            var SIDE=ALL.filter(function(o){return o.k=='t'}),EV=ALL.filter(function(o){return o.k=='e'});
            var big=document.getElementById('big'),sub=document.getElementById('sub'),detail=document.getElementById('detail'),ctrl=document.getElementById('ctrl');
            if(!ALL.length){big.textContent=L('Not yet','Nog niet');sub.textContent=L('No outings found where both watches were close together.','Geen tochten gevonden waarbij beide horloges dicht bij elkaar waren.');return}

            // ---- map: every stretch you did side by side, as one glowing web
            var map=L.map('map',{zoomControl:false,zoomAnimation:true,preferCanvas:true});
            L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap'}).addTo(map);
            L.control.zoom({position:'topright',zoomInTitle:L('Zoom in','Inzoomen'),zoomOutTitle:L('Zoom out','Uitzoomen')}).addTo(map);
            // The zoom buttons sit just below the top bar, however many lines the menu wraps to.
            (function(){var top=document.getElementById('top'),st=top.parentNode;
              function fit(){st.style.setProperty('--top-h',top.offsetHeight+'px')}fit();
              if(window.ResizeObserver)new ResizeObserver(fit).observe(top);else window.addEventListener('resize',fit)})();
            var R=L.canvas({padding:.3}),web=L.layerGroup().addTo(map),one=L.layerGroup().addTo(map),webHalo=[],webLine=[];
            SIDE.forEach(function(o){o.segs.forEach(function(s){
              webHalo.push(L.polyline(s,{renderer:R,color:TH.halo,weight:TH.haloW,opacity:TH.haloO,interactive:false}).addTo(web));
              var pl=L.polyline(s,{renderer:R,color:TH.ink,weight:2,opacity:TH.lineO}).addTo(web);webLine.push(pl);
              pl.on('click',function(){select(o)});
              pl.on('mouseover',function(e){showTip('<b>'+fmtDate(o.d)+'</b><br>'+esc(title(o)),e.originalEvent.clientX,e.originalEvent.clientY)});
              pl.on('mouseout',hideTip)})});
            function boundsOf(list){var pts=[];list.forEach(function(o){o.segs.forEach(function(s){pts.push(s[0])});if(!o.segs.length)pts.push(track(o.ra).p[0])});
              var la=pts.map(function(p){return p[0]}).sort(function(a,b){return a-b}),lo=pts.map(function(p){return p[1]}).sort(function(a,b){return a-b});
              function q(v,f){return v[Math.min(v.length-1,Math.floor(f*v.length))]}var f=pts.length>20?.08:0;
              return L.latLngBounds([q(la,f),q(lo,f)],[q(la,1-f),q(lo,1-f)])}
            function title(o){
              // Prefer a name someone typed over Garmin's "Place Sport".
              var auto=/ (Hardlopen|Fietsen|Wandelen|Wegwielrennen|Mountainbiken|Zwemmen|Running|Cycling|Walking|Hiking|Wielrennen|Veldrijden|Gravelfietsen)$/;
              var na=o.a.n||'',nb=o.b.n||'';if(nb&&!auto.test(nb))return nb;if(na&&!auto.test(na))return na;return na||nb||L('Out together','Samen op pad')}
            var sideKm=SIDE.reduce(function(s,o){return s+o.tk},0),sideSecs=SIDE.reduce(function(s,o){return s+o.ts},0);
            function overview(){
              stop();one.clearLayers();map.addLayer(web);ctrl.hidden=true;detail.innerHTML='';
              document.querySelectorAll('#list button[aria-current]').forEach(function(b){b.removeAttribute('aria-current')});
              big.innerHTML='<small>'+esc(NAME_A)+L(' and ',' en ')+esc(NAME_B)+'</small>'+SIDE.length+L(' times',' keer');
              sub.textContent=NL?'Sinds '+fmtDate((SIDE[0]||ALL[0]).d)+' '+SIDE.length+' keer zij aan zij op pad: '+fmtInt(sideKm)+' km en '+fmtDur(sideSecs)
                +' samen. '+(EV.length?'Nog '+EV.length+' keer startten jullie op hetzelfde evenement en ging ieder op zijn eigen tempo. ':'')+'Kies er een in het logboek om ze opnieuw te bekijken.'
                :'Out side by side '+SIDE.length+' times since '+fmtDate((SIDE[0]||ALL[0]).d)+': '+fmtInt(sideKm)+' km and '+fmtDur(sideSecs)
                +' together. '+(EV.length?'Another '+EV.length+' times you started the same event and each went your own pace. ':'')+'Pick one in the logbook to see it again.';
              map.fitBounds(boundsOf(SIDE.length?SIDE:ALL).pad(.1));if(location.hash)history.replaceState(null,'',location.pathname)}

            // ---- one outing: both tracks, and the two of you moving along them
            var cur=null,T=0,END=0,speed=120,playing=false,last=0,raf=0,dots=null,trA,trB,live={};
            function at(tr,t){var ts=tr.t,n=ts.length;if(t<=ts[0])return tr.p[0];if(t>=ts[n-1])return tr.p[n-1];
              var lo=0,hi=n-1;while(hi-lo>1){var m=(lo+hi)>>1;if(ts[m]<=t)lo=m;else hi=m}
              var f=(t-ts[lo])/Math.max(1,ts[hi]-ts[lo]),p=tr.p[lo],q=tr.p[hi];return [p[0]+(q[0]-p[0])*f,p[1]+(q[1]-p[1])*f]}
            function upto(tr,t){var out=[];for(var i=0;i<tr.t.length&&tr.t[i]<=t;i++)out.push(tr.p[i]);out.push(at(tr,t));return out}
            function select(o,noPlay){
              cur=o;map.removeLayer(web);one.clearLayers();hideTip();
              trA=track(o.ra);trB=track(o.rb);END=Math.max(trA.t[trA.t.length-1],trB.t[trB.t.length-1]);
              live.ga=L.polyline(trA.p,{renderer:R,weight:2,opacity:.25}).addTo(one);live.gb=L.polyline(trB.p,{renderer:R,weight:2,opacity:.25}).addTo(one);
              live.a=L.polyline([],{renderer:R,weight:3,opacity:.95}).addTo(one);live.b=L.polyline([],{renderer:R,weight:3,opacity:.95}).addTo(one);
              // Where the two of you were within 80 m at the same moment, the line turns white as the replay passes.
              trB.near=trB.p.map(function(p,i){return map.distance(p,at(trA,trB.t[i]))<=80});
              live.glow=L.polyline([],{renderer:R}).addTo(one);live.w=L.polyline([],{renderer:R,weight:3.5,opacity:.95}).addTo(one);
              dots={a:L.circleMarker(trA.p[0],{renderer:R,radius:7,weight:2,fillOpacity:1}).addTo(one),
                    b:L.circleMarker(trB.p[0],{renderer:R,radius:7,weight:2,fillOpacity:1}).addTo(one)};
              styleOne();
              map.fitBounds(L.latLngBounds(trA.p.concat(trB.p)).pad(.15));
              big.innerHTML='<small>'+longDate(o.d)+'</small>'+esc(title(o));big.style.fontSize='clamp(40px,5vw,72px)';
              sub.textContent=o.k=='t'?fmtDur(o.ts)+L(' and ',' en ')+fx(Math.round(o.tk*10)/10,1)+L(' km side by side.',' km zij aan zij.')
                :L('The same start, then each your own pace.','Dezelfde start, daarna ieder op zijn eigen tempo.');
              detail.innerHTML='<a href="'+esc(o.a.h)+'">'+o.a.i+' '+esc(NAME_A)+': '+esc(o.a.n||o.a.s)+', '+fx(o.a.km,1)+' km</a>'
                +'<a href="'+esc(o.b.h)+'">'+o.b.i+' '+esc(NAME_B)+': '+esc(o.b.n||o.b.s)+', '+fx(o.b.km,1)+' km</a>';
              ctrl.hidden=false;
              document.querySelectorAll('#list button').forEach(function(b){if(+b.dataset.i==o.i)b.setAttribute('aria-current','true');else b.removeAttribute('aria-current')});
              history.replaceState(null,'','#'+o.d+'-'+o.i);
              T=0;draw();if(reduced||noPlay){T=END;draw();setPlaying(false)}else setPlaying(true)}
            function styleOne(){if(!cur)return;
              live.ga.setStyle({color:TH.a});live.gb.setStyle({color:TH.b});live.a.setStyle({color:TH.a});live.b.setStyle({color:TH.b});
              live.glow.setStyle({color:TH.halo,weight:TH.glowW,opacity:TH.glowO});live.w.setStyle({color:TH.ink});
              dots.a.setStyle({color:TH.ring,fillColor:TH.a});dots.b.setStyle({color:TH.ring,fillColor:TH.b})}
            function setTheme(t){THEME=t;TH=THEMES[t];document.body.classList.toggle('light',t=='light');
              try{localStorage.setItem('garmin-together-map',t)}catch(e){}
              document.querySelectorAll('[data-theme]').forEach(function(b){b.setAttribute('aria-pressed',b.dataset.theme==t)});
              webHalo.forEach(function(p){p.setStyle({color:TH.halo,weight:TH.haloW,opacity:TH.haloO})});
              webLine.forEach(function(p){p.setStyle({color:TH.ink,opacity:TH.lineO})});styleOne()}
            document.querySelectorAll('[data-theme]').forEach(function(b){b.onclick=function(){setTheme(b.dataset.theme)}});
            setTheme(THEME);
            function draw(){if(!cur)return;var pa=at(trA,T),pb=at(trB,T);dots.a.setLatLng(pa);dots.b.setLatLng(pb);
              live.a.setLatLngs(upto(trA,T));live.b.setLatLngs(upto(trB,T));
              var parts=[],run=null;for(var i=0;i<trB.t.length&&trB.t[i]<=T;i++){if(trB.near[i]){if(!run)parts.push(run=[]);run.push(trB.p[i])}else run=null}
              if(run&&trB.near[i-1])run.push(pb);live.w.setLatLngs(parts);live.glow.setLatLngs(parts);
              dots.a.bringToFront();dots.b.bringToFront();
              document.getElementById('clock').textContent=clock(T);
              var d=map.distance(pa,pb),apart=document.getElementById('apart');
              apart.textContent=T>trA.t[trA.t.length-1]&&T>trB.t[trB.t.length-1]?L('Done','Klaar'):d<=80?L('Side by side','Zij aan zij')
                :d<1000?Math.round(d/10)*10+L(' m apart',' m uit elkaar'):fx(d/1000,1)+L(' km apart',' km uit elkaar')}
            function frame(now){raf=0;var dt=Math.min(100,now-(last||now));last=now;
              if(playing){T+=dt/1000*speed;if(T>=END){T=END;setPlaying(false)}draw()}
              if(playing)raf=requestAnimationFrame(frame)}
            function setPlaying(p){playing=p;document.getElementById('play').textContent=p?L('Pause','Pauze'):(T>=END?L('Again','Opnieuw'):L('Play','Afspelen'));
              if(p){if(T>=END)T=0;last=0;if(!raf)raf=requestAnimationFrame(frame)}}
            function stop(){playing=false;cur=null}
            function setSpeed(s){speed=s;document.querySelectorAll('[data-speed]').forEach(function(b){b.setAttribute('aria-pressed',+b.dataset.speed==s)})}
            document.getElementById('play').onclick=function(){setPlaying(!playing)};
            document.querySelectorAll('[data-speed]').forEach(function(b){b.onclick=function(){setSpeed(+b.dataset.speed)}});
            document.getElementById('back').onclick=function(){big.style.fontSize='';overview()};
            document.addEventListener('keydown',function(e){if(e.key=='Escape'&&cur){big.style.fontSize='';overview()}});
            setSpeed(120);

            // ---- logbook
            var shown={t:true,e:false};
            function renderList(){var h='',year='';
              ALL.slice().reverse().forEach(function(o){if(!shown[o.k])return;var y=o.d.slice(0,4);if(y!=year){h+='<li><h3>'+y+'</h3></li>';year=y}
                h+='<li><button type="button" class="'+(o.k=='e'?'ev':'')+'" data-i="'+o.i+'"><b>'+o.a.i+' '+esc(title(o))+'</b><span>'+fmtDate(o.d)
                  +', '+(o.k=='t'?fx(Math.round(o.tk*10)/10,1)+L(' km together in ',' km samen in ')+fmtDur(o.ts)
                    :L('same event: ','zelfde evenement: ')+fx(o.a.km,1)+L(' and ',' en ')+fx(o.b.km,1)+' km')+'</span></button></li>'});
              var list=document.getElementById('list');list.innerHTML=h||'<li style="padding:12px">'+L('Nothing to show.','Niets te tonen.')+'</li>';
              list.querySelectorAll('button').forEach(function(b){b.onclick=function(){select(ALL[+b.dataset.i])}})}
            document.querySelectorAll('[data-kind]').forEach(function(b){b.onclick=function(){shown[b.dataset.kind]=!shown[b.dataset.kind];
              b.setAttribute('aria-pressed',shown[b.dataset.kind]);renderList()}});
            renderList();

            // ---- year after year
            (function(){
              var y0=+ALL[0].d.slice(0,4),y1=+ALL[ALL.length-1].d.slice(0,4),rows=[],max=1;
              for(var y=y0;y<=y1;y++){var r={y:y,t:0,e:0,km:0};ALL.forEach(function(o){if(+o.d.slice(0,4)==y){r[o.k]++;if(o.k=='t')r.km+=o.tk}});rows.push(r);if(r.t+r.e>max)max=r.t+r.e}
              var W=1000,H=230,B=26,bw=(W-40)/rows.length,s='';
              [0,.5,1].forEach(function(f){var v=Math.round(max*f),yy=H-B-(H-B-10)*v/max;s+='<line x1="30" x2="'+W+'" y1="'+yy+'" y2="'+yy+'" stroke="#1d2a44"/><text x="22" y="'+(yy+4)+'" fill="#76829b" font-size="12" text-anchor="end">'+v+'</text>'});
              rows.forEach(function(r,i){var x=40+i*bw+bw*.18,w=bw*.64,ht=(H-B-10)*r.t/max,he=(H-B-10)*r.e/max,base=H-B;
                var t='<b>'+r.y+'</b><br>'+r.t+L(' side by side',' zij aan zij')+(r.km?', '+fmtInt(r.km)+' km':'')+'<br>'+r.e+L(' same event',' zelfde evenement');
                if(r.t)s+='<rect x="'+x+'" y="'+(base-ht)+'" width="'+w+'" height="'+ht+'" rx="3" fill="#eef1f7" data-tip="'+esc(t)+'"/>';
                if(r.e)s+='<rect x="'+x+'" y="'+(base-ht-he-(r.t?2:0))+'" width="'+w+'" height="'+he+'" rx="3" fill="#4a5878" data-tip="'+esc(t)+'"/>';
                s+='<rect x="'+(40+i*bw)+'" y="0" width="'+bw+'" height="'+(H-B)+'" fill="transparent" data-tip="'+esc(t)+'"/>';
                s+='<text x="'+(40+i*bw+bw/2)+'" y="'+(H-6)+'" fill="#a9b4ca" font-size="13" text-anchor="middle">'+r.y+'</text>'});
              var svg=document.getElementById('years');svg.setAttribute('viewBox','0 0 '+W+' '+H);svg.innerHTML=s;
              svg.addEventListener('mousemove',function(e){var t=e.target.getAttribute('data-tip');if(t)showTip(t,e.clientX,e.clientY);else hideTip()});
              svg.addEventListener('mouseleave',hideTip);
              var best=rows.slice().sort(function(a,b){return b.t-a.t})[0];
              document.getElementById('yearsLede').innerHTML=NL
                ?'Tochten per jaar: <span style="color:#eef1f7">zij aan zij</span> en <span style="color:#8f9bb8">zelfde evenement</span>. '
                  +(best&&best.t?'Jullie beste jaar samen was '+best.y+', met '+best.t+' tochten zij aan zij.':'')
                :'Outings per year: <span style="color:#eef1f7">side by side</span> and <span style="color:#8f9bb8">same event</span>. '
                  +(best&&best.t?'Your best year together was '+best.y+', with '+best.t+' outings side by side.':'');
              var list=SIDE.length?SIDE:ALL,first=list[0],longest=list.reduce(function(m,o){return o.ts>m.ts?o:m},list[0]),
                lastO=list[list.length-1];
              var sports={};SIDE.forEach(function(o){sports[o.a.g]=(sports[o.a.g]||0)+1});var fav=Object.keys(sports).sort(function(a,b){return sports[b]-sports[a]})[0];
              var f=[[L('The first time','De eerste keer'),fmtDate(first.d),esc(title(first))],[L('The longest together','Het langst samen'),fmtDur(longest.ts),esc(title(longest))+', '+fmtDate(longest.d)+', '+fmtInt(longest.tk)+' km'],
                [L('The latest','De laatste'),fmtDate(lastO.d),esc(title(lastO))]];
              var SPORTS=NL?{running:'Hardlopen',cycling:'Fietsen',walking:'Wandelen',swimming:'Zwemmen'}:{running:'Running',cycling:'Cycling',walking:'Walking',swimming:'Swimming'};
              if(fav)f.push([L('Most often','Het vaakst'),SPORTS[fav]||fav,sports[fav]+L(' of the ',' van de ')+SIDE.length+L(' outings',' tochten')]);
              document.getElementById('facts').innerHTML=f.map(function(x){return '<div><dt>'+x[0]+'</dt><dd>'+x[1]+'<small>'+x[2]+'</small></dd></div>'}).join('');
            })();

            // ---- in your own words: names that mention the other one
            (function(){
              var a=NAME_A.toLowerCase(),b=NAME_B.toLowerCase(),out=[];
              ALL.forEach(function(o){
                if((o.b.n||'').toLowerCase().indexOf(a)>=0)out.push([o.b.n,NAME_B,o]);
                if((o.a.n||'').toLowerCase().indexOf(b)>=0)out.push([o.a.n,NAME_A,o])});
              if(!out.length)return;document.getElementById('wordsPart').hidden=false;
              document.getElementById('words').innerHTML=out.reverse().slice(0,24).map(function(w){return '<li><q>'+esc(w[0])+'</q><span>'+w[1]+', '+fmtDate(w[2].d)+'</span></li>'}).join('');
            })();

            var m=/^#(\\d{4}-\\d{2}-\\d{2})-(\\d+)/.exec(location.hash);
            if(m&&ALL[+m[2]]&&ALL[+m[2]].d==m[1])select(ALL[+m[2]],true);else overview();
            })();
            """;
}
