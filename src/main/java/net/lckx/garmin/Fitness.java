package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static net.lckx.garmin.DescribeGarminFile.esc;
import static net.lckx.garmin.DescribeGarminFile.jsStr;

/**
 * "Fitness and form": a training load per activity from heart rate, and from it fitness, fatigue and form for every
 * day since the first activity.
 * <ul>
 *   <li>Load is Banister's TRIMP: minutes × HRr × 0.64 × e^(1.92 × HRr), with HRr = (average HR − resting HR) /
 *       (maximum HR − resting HR). Resting HR is the median of Garmin's resting heart rate on nights the watch saw at
 *       least 3 hours of sleep; maximum HR, per year, is the 99.5th percentile of the activities' maximum heart rate in
 *       that year and the years either side (values above {@value #MAX_PLAUSIBLE_HR} are sensor noise).</li>
 *   <li>Without heart rate, Garmin's own training load is used, scaled to TRIMP by the median ratio of activities that
 *       have both; without either, the activity's duration times the median TRIMP per minute of that sport. Both are
 *       flagged on the page.</li>
 *   <li>Activities that overlap in time with another one for more than half of the shorter (the same ride recorded
 *       twice, a multisport parent and its parts) count once: the one with the best source, then the longest.</li>
 *   <li>Fitness (CTL) is the 42-day exponentially weighted average of daily load, fatigue (ATL) the 7-day one, form
 *       (TSB) their difference.</li>
 * </ul>
 * Reads the activity JSON files and the summaries in the day reports, with a cache ({@code fitness-cache.tsv}) keyed
 * by file and modification time.
 * <pre>
 * java --enable-preview -cp target/classes net.lckx.garmin.Fitness [--account NAME]
 * </pre>
 */
public final class Fitness {
    static final String PAGE_FILE = "fitness.html";
    private static final String DATA_FILE = "fitness-data.js";
    private static final String CACHE_FILE = "fitness-cache.tsv";
    private static final String CACHE_VERSION = "fitness-cache v1";
    private static final String SUMMARY_START = "<script type=\"application/json\" id=\"garmin-summary\">";
    private static final Pattern ACTIVITY_FILE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})_(\\d+)_.*\\.json");
    private static final Pattern REPORT_FILE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-report\\.html");
    private static final Pattern RACE_NAME = Pattern.compile(
            "(?iu)marathon|triathlon|duathlon|corrida|wedstrijd|\\bkoers\\b|hindernisbaan|ironman|\\brace\\b|jogging|"
                    + "\\b\\d+ ?km (van|door)\\b|\\bloop van\\b|\\btrail (run|race)\\b|cyclo'? ?s?\\b");
    private static final Pattern NOT_RACE = Pattern.compile("(?iu)\\btoer|losloop|loslopen|uitlopen|training|trage|traag|voor marathon");
    static final int MAX_PLAUSIBLE_HR = 215;
    private static final int MIN_MAX_SAMPLES = 40;
    /** Below these speeds (km/h) a long recording is a watch left running: only the plausible part counts. */
    private static final Map<String, Double> MIN_KMH = Map.of("running", 6.0, "cycling", 12.0, "indoor_cycling", 12.0,
            "walking", 2.5, "swimming", 1.0);
    private static final double MAX_SECS = 12 * 3600, MAX_SECS_NO_DISTANCE = 4 * 3600,
            MAX_SECS_ESTIMATED = 5 * 3600;
    private static final double CTL_DAYS = 42, ATL_DAYS = 7;
    /** Form zones: above FRESH is fresh, down to NEUTRAL neutral, down to TIRED tired, below that overreaching. */
    static final double FRESH = 5, NEUTRAL = -10, TIRED = -30;
    private static final int GAP_DAYS = 28;

    private Fitness() {}

    /** One activity as read from its JSON file (NaN where Garmin has no value). */
    record Act(long mtime, String date, String id, long start, String time, String type, String name, double secs,
               double metres, double avgHR, double maxHR, double garminLoad, double te, String event) {}

    /** One day report's resting heart rate and sleep. */
    record Night(long mtime, String date, double rhr, double sleep) {}

    /** An activity with its load: src h = heart rate, g = Garmin's load, d = duration estimate; kind 1 race, 2 big day. */
    static final class Scored {
        final Act a;
        final String sport, group;
        /** The duration that counts: a watch left running for a day does not make a day of training. */
        final double effSecs;
        double load;
        char src;
        int kind;
        boolean dropped;

        Scored(Act a) {
            this.a = a;
            this.sport = SportCorrection.sport(SportCorrection.id(a.id), a.type, nz(a.metres), nz(a.secs));
            this.group = DescribeGarminFile.Overview.sportGroup(sport);
            double secs = Math.min(nz(a.secs), MAX_SECS);
            Double kmh = MIN_KMH.get(group);
            if (a.metres > 0 && kmh != null) secs = Math.min(secs, Math.max(3600, a.metres / 1000 / kmh * 3600));
            else secs = Math.min(secs, MAX_SECS_NO_DISTANCE);
            this.effSecs = secs;
        }

        long end() {
            return a.start + Math.round(nz(a.secs));
        }
    }

    public static void main(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--account")) DescribeGarminFile.useAccount(args[++i]);
            else {
                System.err.println("Usage: Fitness [--account NAME]");
                System.exit(1);
            }
        }
        System.out.println("Fitness and form updated: " + write().toAbsolutePath());
    }

    static Path write() throws IOException {
        Path dir = DescribeGarminFile.dataDir(DescribeGarminFile.account());
        Files.createDirectories(dir);
        Map<String, Object> cache = readCache(dir.resolve(CACHE_FILE));
        TreeMap<String, Object> files = new TreeMap<>();
        int read = 0, failed = 0;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String fn = p.getFileName().toString();
                Matcher am = ACTIVITY_FILE.matcher(fn), rm = REPORT_FILE.matcher(fn);
                boolean act = am.matches(), rep = !act && rm.matches();
                if (!act && !rep) continue;
                String key = dir.relativize(p).toString().replace('\\', '/');
                if (key.contains("/wellness/")) continue;
                long mtime = Files.getLastModifiedTime(p).toMillis();
                Object c = cache.get(key);
                long cm = c instanceof Act a ? a.mtime : c instanceof Night n ? n.mtime : -1;
                if (cm != mtime) {
                    try {
                        c = act ? loadActivity(p, am.group(1), am.group(2), mtime) : loadNight(p, rm.group(1), mtime);
                        read++;
                    } catch (Exception e) {
                        failed++;
                        System.err.println("Fitness: could not read " + p + ": " + e.getMessage());
                        continue;
                    }
                }
                if (c != null) files.put(key, c);
            }
        }
        writeCache(dir.resolve(CACHE_FILE), files);
        List<Act> acts = new ArrayList<>();
        List<Night> nights = new ArrayList<>();
        for (Object o : files.values()) {
            if (o instanceof Act a) acts.add(a);
            else if (o instanceof Night n) nights.add(n);
        }
        Model m = new Model(acts, nights, LocalDate.now());
        replace(dir.resolve(DATA_FILE), m.js());
        Path page = dir.resolve(PAGE_FILE);
        replace(page, page(m));
        System.out.printf(Locale.ROOT, "Fitness: %d activities (%d heart rate, %d Garmin load, %d estimated, %d duplicates), "
                        + "rest %.0f / max %.0f bpm, fitness today %.0f, form %.0f (%s); %d files read, %d unreadable.%n",
                m.counted, m.bySrc[0], m.bySrc[1], m.bySrc[2], m.duplicates, m.rest, m.max, m.ctl[m.n - 1], m.tsb(m.n - 1),
                zone(m.tsb(m.n - 1)), read, failed);
        return page;
    }

    // ---- reading -------------------------------------------------------------------------------------------

    private static Act loadActivity(Path p, String date, String id, long mtime) throws IOException {
        if (!(DescribeGarminFile.Json.parse(Files.readString(p, StandardCharsets.UTF_8)) instanceof Map<?, ?> j)) return null;
        Object s = j.get("summaryDTO");
        String type = str(at(j, "activityTypeDTO", "typeKey"));
        if (type.isEmpty()) return null;
        String local = str(at(s, "startTimeLocal")), gmt = str(at(s, "startTimeGMT"));
        long start = -1;
        String t = gmt.length() >= 19 ? gmt : local;
        if (t.length() >= 19) start = LocalDateTime.parse(t.substring(0, 19)).toEpochSecond(ZoneOffset.UTC);
        String time = local.length() >= 16 ? local.substring(11, 16) : "";
        if (start < 0) start = LocalDate.parse(date).atStartOfDay().toEpochSecond(ZoneOffset.UTC) + 12 * 3600;
        return new Act(mtime, date, id, start, time, type, clean(str(j.get("activityName"))), num(at(s, "duration")),
                num(at(s, "distance")), num(at(s, "averageHR")), num(at(s, "maxHR")), num(at(s, "activityTrainingLoad")),
                num(at(s, "trainingEffect")), str(at(j, "eventTypeDTO", "typeKey")));
    }

    private static Night loadNight(Path p, String date, long mtime) throws IOException {
        String h = Files.readString(p, StandardCharsets.UTF_8);
        int i = h.indexOf(SUMMARY_START);
        double rhr = Double.NaN, sleep = Double.NaN;
        if (i >= 0) {
            int e = h.indexOf("</script>", i);
            if (e > 0 && DescribeGarminFile.Json.parse(h.substring(i + SUMMARY_START.length(), e)) instanceof Map<?, ?> m) {
                rhr = num(m.get("restingHR"));
                sleep = num(m.get("sleepSeconds"));
            }
        }
        return new Night(mtime, date, rhr, sleep);
    }

    private static Object at(Object o, String... keys) {
        for (String k : keys) {
            if (!(o instanceof Map<?, ?> m)) return null;
            o = m.get(k);
        }
        return o;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    // ---- cache ---------------------------------------------------------------------------------------------

    private static Map<String, Object> readCache(Path f) {
        Map<String, Object> m = new HashMap<>();
        if (!Files.exists(f)) return m;
        try {
            List<String> rows = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (rows.isEmpty() || !rows.getFirst().equals(CACHE_VERSION)) return m;
            for (String row : rows.subList(1, rows.size())) {
                String[] c = row.split("\t", -1);
                if (c[0].equals("A") && c.length >= 16) {
                    m.put(c[1], new Act(Long.parseLong(c[2]), c[3], c[4], Long.parseLong(c[5]), c[6], c[7], c[8],
                            d(c[9]), d(c[10]), d(c[11]), d(c[12]), d(c[13]), d(c[14]), c[15]));
                } else if (c[0].equals("D") && c.length >= 6) {
                    m.put(c[1], new Night(Long.parseLong(c[2]), c[3], d(c[4]), d(c[5])));
                }
            }
        } catch (Exception e) {
            System.err.println("Fitness: cache unreadable, reading every file again (" + e.getMessage() + ")");
            m.clear();
        }
        return m;
    }

    private static double d(String s) {
        return Double.parseDouble(s);
    }

    private static void writeCache(Path f, Map<String, Object> files) throws IOException {
        StringBuilder b = new StringBuilder(CACHE_VERSION).append('\n');
        for (var e : files.entrySet()) {
            if (e.getValue() instanceof Act a) {
                b.append("A\t").append(e.getKey()).append('\t').append(a.mtime).append('\t').append(a.date).append('\t')
                        .append(a.id).append('\t').append(a.start).append('\t').append(a.time).append('\t').append(a.type)
                        .append('\t').append(a.name).append('\t').append(a.secs).append('\t').append(a.metres).append('\t')
                        .append(a.avgHR).append('\t').append(a.maxHR).append('\t').append(a.garminLoad).append('\t')
                        .append(a.te).append('\t').append(a.event).append('\n');
            } else if (e.getValue() instanceof Night n) {
                b.append("D\t").append(e.getKey()).append('\t').append(n.mtime).append('\t').append(n.date).append('\t')
                        .append(n.rhr).append('\t').append(n.sleep).append('\n');
            }
        }
        replace(f, b.toString());
    }

    private static void replace(Path f, String content) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- the model -----------------------------------------------------------------------------------------

    static String zone(double tsb) {
        return tsb > FRESH ? "fresh" : tsb >= NEUTRAL ? "neutral" : tsb >= TIRED ? "tired" : "overreaching";
    }

    static double trimp(double minutes, double hrr) {
        return minutes * hrr * 0.64 * Math.exp(1.92 * hrr);
    }

    private static double percentile(List<Double> v, double p) {
        if (v.isEmpty()) return Double.NaN;
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return a[(int) Math.min(a.length - 1, Math.max(0, Math.ceil(p * a.length) - 1))];
    }

    static final class Model {
        final double rest, max, fallbackMax, garminScale, fallbackPerMin;
        final Map<Integer, Double> maxByYear = new TreeMap<>();
        final int restNights;
        final Map<String, Double> perMin = new TreeMap<>();
        final List<Scored> scored = new ArrayList<>();
        final LocalDate start, today;
        final int n;
        final double[] load, ctl, atl;
        final boolean[] seen;
        final List<int[]> gaps = new ArrayList<>();
        final List<Integer> peaks = new ArrayList<>();
        int counted, duplicates;
        final int[] bySrc = new int[3];

        Model(List<Act> acts, List<Night> nights, LocalDate today) {
            // Resting heart rate: Garmin's value is only real on nights the watch was worn.
            List<Double> rhr = new ArrayList<>();
            for (Night x : nights)
                if (x.rhr >= 30 && x.rhr <= 100 && x.sleep >= 3 * 3600 && x.sleep <= 14 * 3600) rhr.add(x.rhr);
            restNights = rhr.size();
            rest = rhr.isEmpty() ? 60 : percentile(rhr, .5);
            // Maximum heart rate: a high percentile of the activities' maximum in a three-year window around each year (the
            // maximum drops with age), without sensor spikes; years with too few values use the whole history.
            Map<Integer, List<Double>> hrByYear = new TreeMap<>();
            List<Double> allMax = new ArrayList<>();
            for (Act a : acts) {
                if (!(a.maxHR >= 100 && a.maxHR <= MAX_PLAUSIBLE_HR)) continue;
                hrByYear.computeIfAbsent(Integer.parseInt(a.date.substring(0, 4)), k -> new ArrayList<>()).add(a.maxHR);
                allMax.add(a.maxHR);
            }
            double whole = percentile(allMax, .995);
            fallbackMax = Double.isNaN(whole) || whole < rest + 40 ? 190 : whole;
            int y0 = acts.stream().mapToInt(a -> Integer.parseInt(a.date.substring(0, 4))).min().orElse(today.getYear());
            for (int y = y0; y <= today.getYear(); y++) {
                List<Double> w = new ArrayList<>();
                for (int k = y - 1; k <= y + 1; k++) w.addAll(hrByYear.getOrDefault(k, List.of()));
                double v = w.size() >= MIN_MAX_SAMPLES ? percentile(w, .995) : fallbackMax;
                maxByYear.put(y, v < rest + 40 ? fallbackMax : v);
            }
            // Maximum heart rate does not rise with age: an early year above the later ones is an old sensor's noise.
            double later = Double.NaN;
            for (int y = today.getYear(); y >= y0; y--) {
                boolean measured = hrByYear.getOrDefault(y, List.of()).size() >= 10;
                if (!Double.isNaN(later)) maxByYear.put(y, Math.min(maxByYear.get(y), later + 8));
                if (measured) later = Double.isNaN(later) ? maxByYear.get(y) : Math.max(later, maxByYear.get(y));
            }
            max = maxByYear.getOrDefault(today.getYear(), fallbackMax);

            for (Act a : acts) {
                Scored s = new Scored(a);
                if (DescribeGarminFile.Overview.HIDDEN_SPORTS.contains(s.sport) || !(a.secs > 0)) continue;
                double hrMax = maxByYear.getOrDefault(Integer.parseInt(a.date.substring(0, 4)), fallbackMax);
                if (a.avgHR > 0 && a.avgHR <= hrMax + 5) {
                    double hrr = Math.max(0, Math.min(1, (a.avgHR - rest) / (hrMax - rest)));
                    s.load = trimp(s.effSecs / 60, hrr);
                    s.src = 'h';
                } else if (a.garminLoad >= 5 && a.garminLoad >= 10 * s.effSecs / 3600) s.src = 'g';  // without heart rate Garmin stores a token load
                else s.src = 'd';
                scored.add(s);
            }
            // Calibrate the fallbacks against the activities that have heart rate.
            List<Double> ratios = new ArrayList<>(), all = new ArrayList<>();
            Map<String, List<Double>> byGroup = new HashMap<>();
            for (Scored s : scored) {
                if (s.src != 'h') continue;
                if (s.a.garminLoad >= 5 && s.a.garminLoad >= 10 * s.effSecs / 3600 && s.load >= 5) ratios.add(s.load / s.a.garminLoad);
                if (s.effSecs >= 600) {
                    double pm = s.load / (s.effSecs / 60);
                    all.add(pm);
                    byGroup.computeIfAbsent(s.group, k -> new ArrayList<>()).add(pm);
                }
            }
            garminScale = ratios.size() >= 10 ? percentile(ratios, .5) : 1;
            fallbackPerMin = all.isEmpty() ? 1 : percentile(all, .5);
            byGroup.forEach((g, v) -> { if (v.size() >= 10) perMin.put(g, percentile(v, .5)); });
            for (Scored s : scored) {
                if (s.src == 'g') s.load = s.a.garminLoad * garminScale;
                else if (s.src == 'd') s.load = Math.min(s.effSecs, MAX_SECS_ESTIMATED) / 60 * perMin.getOrDefault(s.group, fallbackPerMin);
            }
            // The same effort recorded twice counts once.
            scored.sort(Comparator.comparingLong((Scored s) -> s.a.start));
            List<Scored> kept = new ArrayList<>();
            for (Scored s : scored) {
                for (int k = kept.size() - 1; k >= 0 && k >= kept.size() - 6; k--) {
                    Scored o = kept.get(k);
                    if (o.dropped) continue;
                    long overlap = Math.min(o.end(), s.end()) - Math.max(o.a.start, s.a.start);
                    double shorter = Math.min(o.a.secs, s.a.secs);
                    if (overlap <= 0 || overlap < .5 * shorter) continue;
                    if (better(s, o)) o.dropped = true;
                    else s.dropped = true;
                    break;
                }
                kept.add(s);
            }
            // Days.
            start = scored.stream().filter(s -> !s.dropped).map(s -> LocalDate.parse(s.a.date)).min(LocalDate::compareTo)
                    .orElse(today.minusYears(1));
            this.today = today.isBefore(start) ? start : today;
            n = (int) ChronoUnit.DAYS.between(start, this.today) + 1;
            load = new double[n];
            ctl = new double[n];
            atl = new double[n];
            seen = new boolean[n];
            for (Scored s : scored) {
                if (s.dropped) {
                    duplicates++;
                    continue;
                }
                int i = idx(s.a.date);
                if (i < 0 || i >= n) continue;
                load[i] += s.load;
                seen[i] = true;
                counted++;
                bySrc[s.src == 'h' ? 0 : s.src == 'g' ? 1 : 2]++;
            }
            for (Night x : nights) {
                int i = idx(x.date);
                if (i >= 0 && i < n) seen[i] = true;
            }
            // Start from the average of the first six weeks, not from zero: the first activity on record is rarely the first ever.
            double seed = 0;
            for (int i = 0; i < Math.min(n, 42); i++) seed += load[i] / Math.min(n, 42);
            double kc = 1 - Math.exp(-1 / CTL_DAYS), ka = 1 - Math.exp(-1 / ATL_DAYS), c = seed, f = seed;
            for (int i = 0; i < n; i++) {
                c += (load[i] - c) * kc;
                f += (load[i] - f) * ka;
                ctl[i] = c;
                atl[i] = f;
            }
            for (int i = 0, g = -1; i <= n; i++) {
                if (i < n && !seen[i]) {
                    if (g < 0) g = i;
                } else if (g >= 0) {
                    if (i - g >= GAP_DAYS) gaps.add(new int[]{g, i - 1});
                    g = -1;
                }
            }
            // The three highest peaks, at least four months apart.
            boolean[] taken = new boolean[n];
            for (int p = 0; p < 3; p++) {
                int best = -1;
                for (int i = 0; i < n; i++) if (!taken[i] && (best < 0 || ctl[i] > ctl[best])) best = i;
                if (best < 0 || ctl[best] < 1) break;
                peaks.add(best);
                for (int i = Math.max(0, best - 120); i < Math.min(n, best + 121); i++) taken[i] = true;
            }
            // Key activities: races by event type or name; the three biggest loads of each year.
            Map<String, List<Scored>> byYear = new TreeMap<>();
            for (Scored s : scored) {
                if (s.dropped) continue;
                if (s.a.event.equals("race") || (RACE_NAME.matcher(s.a.name).find() && !NOT_RACE.matcher(s.a.name).find()
                        && !s.group.equals("walking"))) s.kind = 1;
                else if (s.src != 'd') byYear.computeIfAbsent(s.a.date.substring(0, 4), k -> new ArrayList<>()).add(s);
            }
            byYear.values().forEach(l -> {
                l.sort(Comparator.comparingDouble((Scored s) -> -s.load));
                for (int i = 0; i < Math.min(3, l.size()); i++) l.get(i).kind = 2;
            });
        }

        private static boolean better(Scored a, Scored b) {
            int ra = "hgd".indexOf(a.src), rb = "hgd".indexOf(b.src);
            return ra != rb ? ra < rb : a.a.secs > b.a.secs;
        }

        int idx(String date) {
            return (int) ChronoUnit.DAYS.between(start, LocalDate.parse(date));
        }

        LocalDate day(int i) {
            return start.plusDays(i);
        }

        double tsb(int i) {
            return ctl[i] - atl[i];
        }

        String js() {
            StringBuilder b = new StringBuilder("window.FITNESS={start:").append(jsStr(start.toString()));
            b.append(",rest:").append(Math.round(rest)).append(",max:").append(Math.round(max));
            b.append(",fresh:").append(FRESH).append(",neutral:").append(NEUTRAL).append(",tired:").append(TIRED);
            arr(b.append(",ctl:"), ctl);
            arr(b.append(",atl:"), atl);
            arr(b.append(",load:"), load);
            b.append(",gaps:[");
            for (int i = 0; i < gaps.size(); i++) b.append(i > 0 ? "," : "").append('[').append(gaps.get(i)[0]).append(',').append(gaps.get(i)[1]).append(']');
            b.append("],peaks:").append(peaks.toString().replace(" ", ""));
            // [day index, garmin id, sport, name, start time, seconds, load, source, kind]
            b.append(",acts:[");
            Map<String, String> icons = new TreeMap<>();
            boolean first = true;
            for (Scored s : scored) {
                if (s.dropped) continue;
                b.append(first ? "\n" : ",\n");
                first = false;
                b.append('[').append(idx(s.a.date)).append(',').append(jsStr(s.a.id)).append(',').append(jsStr(s.sport)).append(',')
                        .append(jsStr(s.a.name)).append(',').append(jsStr(s.a.time)).append(',').append(Math.round(s.a.secs)).append(',')
                        .append(Math.round(s.load)).append(",\"").append(s.src).append("\",").append(s.kind).append(']');
                icons.put(s.sport, DescribeGarminFile.sportIcon(s.sport));
            }
            b.append("\n],icons:{");
            first = true;
            for (var e : icons.entrySet()) {
                b.append(first ? "" : ",").append(jsStr(e.getKey())).append(':').append(jsStr(e.getValue()));
                first = false;
            }
            return b.append("}};\n").toString();
        }

        private static void arr(StringBuilder b, double[] v) {
            b.append('[');
            for (int i = 0; i < v.length; i++) {
                if (i > 0) b.append(',');
                double r = Math.round(v[i] * 10) / 10.0;
                b.append(r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r));
            }
            b.append(']');
        }

        // ---- the sentences

        /** The lowest and highest day of the last 12 months: [low, high]. */
        private int[] yearRange() {
            int t = n - 1, from = Math.max(0, t - 364), lo = from, hi = from;
            for (int i = from; i <= t; i++) {
                if (ctl[i] < ctl[lo]) lo = i;
                if (ctl[i] > ctl[hi]) hi = i;
            }
            return new int[]{lo, hi};
        }

        /** Today against the last 12 months: an all-time peak from years ago says little about now. */
        String headline(boolean nl) {
            int t = n - 1;
            int[] r = yearRange();
            long now = Math.round(ctl[t]), lo = Math.round(ctl[r[0]]), hi = Math.round(ctl[r[1]]);
            double pos = hi > lo ? (ctl[t] - ctl[r[0]]) / (ctl[r[1]] - ctl[r[0]]) : 1;
            if (nl) {
                String where = now >= hi ? "de hoogste van de voorbije 12 maanden"
                        : pos >= .8 ? "dicht bij je beste van het jaar (" + hi + ")"
                        : pos >= .5 ? "in de bovenste helft van je jaar (" + lo + " tot " + hi + ")"
                        : pos >= .2 ? "in de onderste helft van je jaar (" + lo + " tot " + hi + ")"
                        : "dicht bij je laagste van het jaar (" + lo + ")";
                return "Je conditie is vandaag " + now + ", " + where + ".";
            }
            String where = now >= hi ? "the highest of the last 12 months"
                    : pos >= .8 ? "close to your best of the year (" + hi + ")"
                    : pos >= .5 ? "in the upper half of your year (" + lo + " to " + hi + ")"
                    : pos >= .2 ? "in the lower half of your year (" + lo + " to " + hi + ")"
                    : "close to your low of the year (" + lo + ")";
            return "Your fitness today is " + now + ", " + where + ".";
        }

        String formLine(boolean nl) {
            int t = n - 1;
            double f = tsb(t);
            int lastAct = -1;
            for (int i = t; i >= 0 && lastAct < 0; i--) if (load[i] > 0) lastAct = i;
            String z = zone(f), what = switch (z) {
                case "fresh" -> t - lastAct > 10 ? "well rested, but after more than a week without training fitness starts to fade."
                        : "a good week for a race.";
                case "neutral" -> "training and recovery are in balance.";
                case "tired" -> "you are building fitness; give it a few easy days before a race.";
                default -> "a lot of load lately; plan some rest.";
            };
            if (nl) {
                what = switch (z) {
                    case "fresh" -> t - lastAct > 10 ? "goed uitgerust, maar na meer dan een week zonder training begint de conditie te zakken."
                            : "een goede week voor een wedstrijd.";
                    case "neutral" -> "training en herstel zijn in evenwicht.";
                    case "tired" -> "je bouwt conditie op; neem een paar rustige dagen voor een wedstrijd.";
                    default -> "veel belasting de laatste tijd; plan wat rust.";
                };
                return "Vorm: " + zoneNl(z) + " — " + what;
            }
            return "Form: " + z + " — " + what;
        }

        /** The trend: four weeks ago, a year ago, recent training against the year, and the year's high and low. */
        String yearLine(boolean nl) {
            int t = n - 1, from = Math.max(0, t - 364), month = Math.max(0, t - 28), yearAgo = Math.max(0, t - 365);
            int[] r = yearRange();
            double hoursYear = 0, hours4 = 0;
            for (Scored s : scored) {
                if (s.dropped) continue;
                int i = idx(s.a.date);
                if (i >= from) hoursYear += s.a.secs / 3600;
                if (i > t - 28) hours4 += s.a.secs / 3600;
            }
            long now = Math.round(ctl[t]), before = Math.round(ctl[month]), ago = Math.round(ctl[yearAgo]);
            long diff = now - before;
            String w4 = String.format(Locale.ROOT, "%.1f", hours4 / 4), wy = String.format(Locale.ROOT, "%.1f", hoursYear / 52.14);
            if (nl) {
                String trend = Math.abs(diff) <= 2 ? "Vier weken geleden was het " + before + ": stabiel."
                        : "Vier weken geleden was het " + before + ", dus " + (diff > 0 ? "stijgend (+" + diff + ")." : "dalend (" + diff + ").");
                return trend + " Een jaar geleden: " + ago + ". De laatste 4 weken trainde je " + w4.replace('.', ',') + " u per week, tegen "
                        + wy.replace('.', ',') + " u gemiddeld over het jaar. Beste van het jaar: " + Math.round(ctl[r[1]]) + " op "
                        + fmtDate(day(r[1]), true) + "; laagste: " + Math.round(ctl[r[0]]) + " op " + fmtDate(day(r[0]), true) + ".";
            }
            String trend = Math.abs(diff) <= 2 ? "Four weeks ago it was " + before + ": steady."
                    : "Four weeks ago it was " + before + ", so it is " + (diff > 0 ? "rising (+" + diff + ")." : "falling (" + diff + ").");
            return trend + " A year ago: " + ago + ". The last 4 weeks you trained " + w4 + " h a week, against " + wy
                    + " h on average over the year. Best of the year: " + Math.round(ctl[r[1]]) + " on " + fmtDate(day(r[1]), false)
                    + "; lowest: " + Math.round(ctl[r[0]]) + " on " + fmtDate(day(r[0]), false) + ".";
        }

        /** [season, value, date, the six weeks before, day index] per peak. */
        /** The three highest peaks from day {@code from} on, at least {@code apart} days apart. */
        List<Integer> peaksFrom(int from, int apart) {
            List<Integer> out = new ArrayList<>();
            boolean[] taken = new boolean[n];
            for (int k = 0; k < 3; k++) {
                int best = -1;
                for (int i = Math.max(0, from); i < n; i++) if (!taken[i] && (best < 0 || ctl[i] > ctl[best])) best = i;
                if (best < 0 || ctl[best] < 1) break;
                out.add(best);
                for (int i = Math.max(0, best - apart); i < Math.min(n, best + apart + 1); i++) taken[i] = true;
            }
            return out;
        }

        List<String[]> peakRows(boolean nl) {
            return peakRows(nl, peaks);
        }

        List<String[]> peakRows(boolean nl, List<Integer> list) {
            List<String[]> rows = new ArrayList<>();
            for (int p : list) {
                double secs = 0;
                Map<String, Double> g = new HashMap<>();
                for (Scored s : scored) {
                    if (s.dropped) continue;
                    int i = idx(s.a.date);
                    if (i > p - 42 && i <= p) {
                        secs += s.a.secs;
                        g.merge(s.group, s.a.secs, Double::sum);
                    }
                }
                String main = g.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> label(e.getKey(), nl)).orElse("");
                String races = scored.stream().filter(s -> !s.dropped && s.kind == 1 && Math.abs(idx(s.a.date) - p) <= 21)
                        .map(s -> s.a.name).distinct().limit(2).reduce((x, y) -> x + ", " + y).orElse("");
                String before = nl
                        ? String.format(Locale.ROOT, "%.1f u per week in de zes weken ervoor, vooral %s.", secs / 3600 / 6, main).replaceFirst("^(\\d+)\\.", "$1,")
                                + (races.isEmpty() ? "" : " Rond die tijd: " + races + ".")
                        : String.format(Locale.ROOT, "%.1f h a week in the six weeks before, mostly %s.", secs / 3600 / 6, main)
                                + (races.isEmpty() ? "" : " Around then: " + races + ".");
                rows.add(new String[]{season(day(p), nl), Long.toString(Math.round(ctl[p])), fmtDate(day(p), nl), before,
                        Integer.toString(p)});
            }
            return rows;
        }

        String maxLine(boolean nl) {
            int top = maxByYear.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(today.getYear());
            return Math.round(maxByYear.get(top)) + (nl ? " slagen/min in " : " bpm in ") + top + ", " + Math.round(max)
                    + (nl ? " slagen/min nu" : " bpm now");
        }

        String sourceLine(boolean nl) {
            if (nl) return String.format(Locale.ROOT, "%d van de %d activiteiten hebben hartslag; %d gebruiken Garmins eigen trainingsbelasting "
                            + "(geschaald met %s om te passen), %d hebben geen van beide en worden geschat uit hun duur. %d dubbele opnames telden één keer.",
                    bySrc[0], counted, bySrc[1], String.format(Locale.ROOT, "%.2f", garminScale).replace('.', ','), bySrc[2], duplicates);
            return String.format(Locale.ROOT, "%d of %d activities have heart rate; %d use Garmin's own training load (scaled by %.2f to match), "
                            + "%d have neither and are estimated from their duration. %d duplicate recordings were counted once.",
                    bySrc[0], counted, bySrc[1], garminScale, bySrc[2], duplicates);
        }
    }

    private static final Map<String, String> GROUPS_NL = Map.ofEntries(Map.entry("running", "lopen"), Map.entry("cycling", "fietsen"),
            Map.entry("indoor_cycling", "binnenfietsen"), Map.entry("walking", "wandelen"), Map.entry("swimming", "zwemmen"),
            Map.entry("lap_swimming", "zwemmen"), Map.entry("open_water_swimming", "openwaterzwemmen"),
            Map.entry("rowing_and_paddling", "roeien en peddelen"), Map.entry("racket_sports", "racketsporten"),
            Map.entry("strength_training", "krachttraining"), Map.entry("fitness_equipment", "fitness"), Map.entry("others", "andere sporten"),
            Map.entry("hiit", "hiit"), Map.entry("yoga", "yoga"), Map.entry("cardio", "cardio"), Map.entry("skiing", "skiën"),
            Map.entry("resort_skiing_snowboarding_ws", "skiën"), Map.entry("tennis", "tennis"), Map.entry("padel", "padel"));

    private static String label(String group, boolean nl) {
        return nl ? GROUPS_NL.getOrDefault(group, group.replace('_', ' ')) : group.replace('_', ' ');
    }

    private static String zoneNl(String z) {
        return switch (z) {
            case "fresh" -> "fris";
            case "neutral" -> "neutraal";
            case "tired" -> "vermoeid";
            default -> "overbelast";
        };
    }

    static String season(LocalDate d) {
        return season(d, false);
    }

    static String season(LocalDate d, boolean nl) {
        int m = d.getMonthValue();
        String s = m >= 3 && m <= 5 ? "spring" : m >= 6 && m <= 8 ? "summer" : m >= 9 && m <= 11 ? "autumn" : "winter";
        if (nl) s = switch (s) {
            case "spring" -> "lente";
            case "summer" -> "zomer";
            case "autumn" -> "herfst";
            default -> "winter";
        };
        if (!s.equals("winter")) return s + " " + d.getYear();
        int y = m == 12 ? d.getYear() : d.getYear() - 1;
        return "winter " + y + "\u2013" + String.format("%02d", (y + 1) % 100);
    }

    private static final String[] MONTHS_NL = {"jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"};

    static String fmtDate(LocalDate d) {
        return fmtDate(d, false);
    }

    static String fmtDate(LocalDate d, boolean nl) {
        return d.getDayOfMonth() + " " + (nl ? MONTHS_NL[d.getMonthValue() - 1]
                : d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)) + " " + d.getYear();
    }


    // ---- page ----------------------------------------------------------------------------------------------

    private static String page(Model m) {
        String label = DescribeGarminFile.accountLabelOf(DescribeGarminFile.account());
        // A list of peaks per range button: the last 12 months, the last 3 years, all years.
        StringBuilder peaks = new StringBuilder();
        Object[][] spans = {{"365", m.n - 365, 30}, {"1096", m.n - 1096, 90}, {"all", 0, 120}};
        for (Object[] sp : spans) {
            List<Integer> list = m.peaksFrom((int) sp[1], (int) sp[2]);
            List<String[]> en = m.peakRows(false, list), nl = m.peakRows(true, list);
            peaks.append("<ol class=\"peaks\" data-span=\"").append(sp[0]).append('"').append(sp[0].equals("365") ? "" : " hidden").append('>');
            for (int k = 0; k < en.size(); k++) {
                String[] r = en.get(k), q = nl.get(k);
                String dutch = "<b>" + esc(cap(q[0])) + "</b> · piek op " + esc(q[2]) + "<small>" + esc(q[3]) + "</small>";
                peaks.append("<li><button type=\"button\" class=\"peak\" data-day=\"").append(r[4]).append("\"><span class=\"rank\">")
                        .append(k + 1).append("</span><span class=\"pv\">").append(r[1]).append("</span><span class=\"pt\" data-nl=\"")
                        .append(esc(dutch)).append("\"><b>")
                        .append(esc(cap(r[0]))).append("</b> · peak on ").append(esc(r[2])).append("<small>").append(esc(r[3]))
                        .append("</small></span></button></li>");
            }
            peaks.append("</ol>");
        }
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + DescribeGarminFile.favicon("#3d8fd6")
                + "<title>Fitness and form · " + esc(label) + "</title>"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"><link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:wght@400;700"
                + "&family=Big+Shoulders+Display:wght@500;700;800&display=swap\">"
                + "<style>" + CSS + "</style>" + Site.HEAD + "</head><body>"
                + BODY.replace("{{NAV}}", Site.nav(PAGE_FILE, 0))
                .replace("{{LABEL}}", esc(label))
                .replace("{{HEADLINE}}", esc(m.headline(false)))
                .replace("{{HEADLINE_NL}}", esc(esc(m.headline(true))))
                .replace("{{FORM}}", esc(m.formLine(false)))
                .replace("{{FORM_NL}}", esc(esc(m.formLine(true))))
                .replace("{{YEAR}}", esc(m.yearLine(false)))
                .replace("{{YEAR_NL}}", esc(esc(m.yearLine(true))))
                .replace("{{PEAKS}}", peaks.toString())
                .replace("{{REST}}", Long.toString(Math.round(m.rest)))
                .replace("{{RESTN}}", Integer.toString(m.restNights))
                .replace("{{MAX}}", esc(m.maxLine(false)))
                .replace("{{MAX_NL}}", esc(esc(m.maxLine(true))))
                .replace("{{SOURCES}}", esc(m.sourceLine(false)))
                .replace("{{SOURCES_NL}}", esc(esc(m.sourceLine(true))))
                .replace("{{UPDATED}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH).format(LocalDateTime.now()))
                .replace("{{UPDATED_NL}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("nl")).format(LocalDateTime.now()))
                + "<script src=\"" + DATA_FILE + "\"></script>"
                + "<script>" + JS + "</script></body></html>";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static final String CSS = """
            :root{--night:#0a1220;--deep:#0f1a2e;--rule:#1d2a44;--fog:#a9b4ca;--moon:#eef1f7;--dim:#76829b;
              --fresh:#3d8fd6;--tired:#d9702a;--zero:#76829b;
              --display:'Big Shoulders Display',Impact,'Arial Narrow',sans-serif;--text:'Atkinson Hyperlegible',system-ui,sans-serif;color-scheme:dark}
            *{box-sizing:border-box}
            html,body{margin:0;background:var(--night);color:var(--fog);font:17px/1.55 var(--text)}
            a{color:var(--moon)}
            :focus-visible{outline:2px solid var(--moon);outline-offset:2px}
            .top{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px 24px;padding:16px 28px;color:var(--fog)}
            .title{font:800 22px/1 var(--display);color:var(--moon);margin:0;letter-spacing:.02em;white-space:nowrap}
            .title span{color:var(--dim);font-weight:500}.title .t{color:var(--moon);font-weight:800}
            .top .site-nav{flex:1 1 auto;justify-content:flex-end;--site-active-bg:var(--moon);--site-active-fg:var(--night)}
            main{max-width:1180px;margin:0 auto;padding:0 28px}
            .hero{padding:56px 0 28px}
            h1{font:800 clamp(40px,6.4vw,76px)/.98 var(--display);color:var(--moon);margin:0;max-width:20ch;letter-spacing:.005em}
            .form{font:700 clamp(24px,3vw,34px)/1.15 var(--display);margin:22px 0 0;color:var(--moon)}
            .form i{display:inline-block;width:14px;height:14px;border-radius:50%;margin-right:10px;vertical-align:.05em}
            .sub{max-width:62ch;margin:12px 0 0}
            h2{font:700 clamp(32px,4.4vw,46px)/1 var(--display);color:var(--moon);margin:0 0 6px;letter-spacing:.005em}
            section.part{padding:56px 0 8px}
            .head{display:flex;justify-content:space-between;align-items:flex-end;flex-wrap:wrap;gap:12px 24px;margin-bottom:12px}
            .head p{margin:0;max-width:62ch;font-size:15px}
            .keys{display:flex;flex-wrap:wrap;gap:6px 20px;margin:0;padding:0;list-style:none;font-size:14px}
            .keys li{display:flex;align-items:center;gap:8px}
            .sw{display:inline-block;width:22px;height:0;border-top:2px solid var(--moon)}
            .sw.faint{border-top:2px solid rgba(238,241,247,.42)}
            .dot{display:inline-block;width:10px;height:10px;border-radius:50%;background:var(--moon);border:2px solid var(--night);box-shadow:0 0 0 1px var(--moon)}
            .ring{display:inline-block;width:10px;height:10px;border-radius:50%;border:2px solid var(--moon)}
            .band{display:inline-block;width:14px;height:12px;border-radius:3px}
            .seg{display:flex;gap:4px;align-items:center}
            button{font:inherit;color:var(--moon);background:var(--deep);border:1px solid var(--rule);border-radius:999px;padding:6px 14px;cursor:pointer}
            button:hover{border-color:var(--dim)}
            button[aria-pressed=true]{background:var(--moon);color:var(--night);border-color:var(--moon)}
            .seg button{padding:5px 12px;font-size:14px}
            .chart{position:relative}
            .chart svg{display:block;width:100%;overflow:visible;touch-action:pan-y}
            svg text{font-family:var(--text)}
            #brush{margin-top:6px;cursor:crosshair;touch-action:none}
            #brush .win{cursor:grab}
            #brush .h{cursor:ew-resize}
            .hint{font-size:13px;color:var(--dim);margin:6px 0 0}
            .peaks{list-style:none;margin:16px 0 0;padding:0;display:grid;gap:12px}.peaks[hidden]{display:none}
            .peak{display:flex;align-items:center;gap:20px;width:100%;text-align:left;border-radius:12px;padding:14px 18px;background:var(--deep)}
            .peak .rank{font:700 18px/1 var(--display);color:var(--dim);width:14px}
            .peak .pv{font:800 52px/1 var(--display);color:var(--moon);min-width:2.1ch;font-variant-numeric:tabular-nums}
            .peak .pt{color:var(--fog);font-size:16px}
            .peak .pt b{color:var(--moon)}
            .peak small{display:block;font-size:14px;color:var(--dim);margin-top:2px}
            .explain{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,300px),1fr));gap:20px 40px;margin-top:8px}
            .explain p{margin:0}
            .explain b{color:var(--moon)}
            .method{font-size:14px;color:var(--dim);max-width:80ch;margin-top:24px}
            footer{border-top:1px solid var(--rule);margin-top:64px;padding:22px 0 40px;font-size:14px;color:var(--dim)}
            footer a{color:var(--fog);margin-right:16px}
            #tip{position:fixed;z-index:1000;pointer-events:none;background:var(--deep);border:1px solid var(--rule);border-radius:8px;
              padding:9px 12px;font-size:14px;line-height:1.45;color:var(--fog);max-width:340px;box-shadow:0 8px 30px rgba(0,0,0,.45)}
            #tip b{color:var(--moon)}
            #tip .row{display:flex;justify-content:space-between;gap:16px}
            #tip .row span:last-child{color:var(--moon);font-variant-numeric:tabular-nums}
            #tip .acts{margin-top:6px;padding-top:6px;border-top:1px solid var(--rule)}
            .sr{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0)}
            @media (max-width:700px){.top{padding:12px 16px}.top .site-nav{justify-content:flex-start;min-width:0;max-width:100%}main{padding:0 16px}
              .hero{padding-top:28px}.peak{gap:14px;padding:12px 14px}.peak .pv{font-size:40px}}
            @media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
            """;

    private static final String BODY = """
            <header class="top"><p class="title"><span class="t" data-nl="Conditie en vorm">Fitness and form</span> <span>{{LABEL}}</span></p>
              {{NAV}}</header>
            <main>
              <section class="hero">
                <h1 data-nl="{{HEADLINE_NL}}">{{HEADLINE}}</h1>
                <p class="form"><i id="formDot"></i><span data-nl="{{FORM_NL}}">{{FORM}}</span></p>
                <p class="sub" data-nl="{{YEAR_NL}}">{{YEAR}}</p>
              </section>
              <section class="part" aria-labelledby="fitH">
                <div class="head"><div><h2 id="fitH" data-nl="Conditie">Fitness</h2><p id="rangeText"></p></div>
                  <div class="seg" role="group" aria-label="Period shown" data-nl-aria="Getoonde periode">
                    <button type="button" data-span="365" data-nl="Laatste 12 maanden">Last 12 months</button><button type="button" data-span="1096" data-nl="Laatste 3 jaar">Last 3 years</button>
                    <button type="button" data-span="all" data-nl="Alle jaren">All years</button></div></div>
                <ul class="keys"><li><span class="sw"></span><span data-nl="Conditie">Fitness</span></li><li><span class="sw faint"></span><span data-nl="Vermoeidheid">Fatigue</span></li>
                  <li><span class="dot"></span><span data-nl="Wedstrijd">Race</span></li><li><span class="ring"></span><span data-nl="Zwaarste trainingen van het jaar">Biggest sessions of the year</span></li></ul>
                <div class="chart"><svg id="main" tabindex="0" role="img" aria-label="Fitness and fatigue per day. Use the left and right arrow keys to read a day."
                  data-nl-aria="Conditie en vermoeidheid per dag. Gebruik de pijltjestoetsen links en rechts om een dag te lezen."></svg></div>
                <div class="chart"><svg id="brush" tabindex="0" role="slider" aria-label="Period shown: drag to pick a period, arrow keys move it, plus and minus zoom"
                  data-nl-aria="Getoonde periode: sleep om een periode te kiezen, pijltjestoetsen verschuiven, plus en min zoomen"></svg></div>
                <p class="hint" data-nl="Sleep over de strook hierboven om een periode te kiezen; sleep het venster om het te verschuiven. Klik op een stip om het dagverslag van die dag te openen.">Drag across the strip above to pick a period; drag the window to move it. Click a dot to open that day's report.</p>
              </section>
              <section class="part" aria-labelledby="formH">
                <div class="head"><div><h2 id="formH" data-nl="Vorm">Form</h2><p data-nl="Conditie min vermoeidheid. Boven nul ben je uitgerust, eronder draag je vermoeidheid mee.">Fitness minus fatigue. Above zero you are rested, below it you carry fatigue.</p></div>
                  <ul class="keys"><li><span class="band" style="background:rgba(61,143,214,.28)"></span><span data-nl="Fris">Fresh</span></li>
                    <li><span class="band" style="background:rgba(118,130,155,.22)"></span><span data-nl="Neutraal">Neutral</span></li>
                    <li><span class="band" style="background:rgba(217,112,42,.22)"></span><span data-nl="Vermoeid">Tired</span></li>
                    <li><span class="band" style="background:rgba(217,112,42,.48)"></span><span data-nl="Overbelast">Overreaching</span></li></ul></div>
                <div class="chart"><svg id="formChart" tabindex="0" role="img" aria-label="Form per day. Use the left and right arrow keys to read a day."
                  data-nl-aria="Vorm per dag. Gebruik de pijltjestoetsen links en rechts om een dag te lezen."></svg></div>
              </section>
              <section class="part" aria-labelledby="bestH">
                <h2 id="bestH"></h2>
                <p class="head" id="bestLede" style="margin:0"></p>
                {{PEAKS}}
              </section>
              <section class="part" aria-labelledby="whatH">
                <h2 id="whatH" data-nl="Wat de cijfers betekenen">What the numbers mean</h2>
                <div class="explain">
                  <p data-nl="Elke training voegt &lt;b&gt;belasting&lt;/b&gt; toe: hoe lang je bezig was, gewogen naar hoe hard je hart werkte. &lt;b&gt;Conditie&lt;/b&gt; is de belasting van
                    ongeveer de laatste zes weken, zo gemiddeld dat oudere dagen minder meetellen. Ze stijgt langzaam als je regelmatig traint en zakt langzaam
                    als je stopt. &lt;b&gt;Vermoeidheid&lt;/b&gt; is hetzelfde over ongeveer een week: ze schiet omhoog na een paar zware dagen en is weg na een paar rustige.
                    &lt;b&gt;Vorm&lt;/b&gt; is conditie min vermoeidheid. Vermoeid betekent dat je hard traint en conditie opbouwt; fris betekent dat de vermoeidheid
                    weggeëbd is terwijl de meeste conditie er nog is, en dan loop of rijd je je beste wedstrijden. Blijf je lang overbelast, dan riskeer je ziekte
                    of een blessure.">Every workout adds <b>load</b>: how long you went, weighted by how hard your heart worked. <b>Fitness</b> is the load of
                    roughly the last six weeks, averaged so that older days count less. It rises slowly when you train steadily and fades slowly
                    when you stop. <b>Fatigue</b> is the same over about one week: it jumps after a hard few days and is gone after a few easy ones.
                    <b>Form</b> is fitness minus fatigue. Tired means you are training hard and building fitness; fresh means the fatigue has
                    drained away while most fitness is still there, which is when you race best. Stay overreaching for long and you risk illness
                    or injury.</p>
                </div>
                <p class="method" data-nl="Hoe het geteld wordt: belasting is Banisters TRIMP uit de gemiddelde hartslag, met een rusthartslag van {{REST}} slagen/min
                  (de middelste waarde van {{RESTN}} nachten waarin het horloge minstens drie uur slaap zag) en een maximum dat de leeftijd volgt: de bovenste halve
                  procent van de maximale hartslagen van de activiteiten in dat jaar en de jaren ernaast, zonder pieken van de sensor ({{MAX_NL}}). {{SOURCES_NL}} Conditie is een
                  exponentieel gewogen gemiddelde over 42 dagen en vermoeidheid over 7 dagen; de zones zijn fris boven +5, neutraal tot −10, vermoeid tot −30 en
                  overbelast daaronder. Gearceerde stukken hadden vier weken of langer geen opnames: het horloge werd niet gedragen, dus de dip daar
                  is misschien niet echt.">How it is counted: load is Banister's TRIMP from average heart rate, with a resting heart rate of {{REST}} bpm
                  (the middle value of {{RESTN}} nights the watch saw at least three hours of sleep) and a maximum that follows age: the top half
                  percent of the activities' maximum heart rates in that year and the years either side, ignoring sensor spikes ({{MAX}}). {{SOURCES}} Fitness is a 42-day and fatigue a
                  7-day exponentially weighted average; the zones are fresh above +5, neutral down to &minus;10, tired down to &minus;30, and
                  overreaching below that. Hatched stretches had no recordings for four weeks or more: the watch was not worn, so the dip there
                  may not be real.</p>
              </section>
              <footer><span data-nl="Gemaakt uit je Garmin-bestanden op {{UPDATED_NL}}.">Built from your Garmin files on {{UPDATED}}.</span></footer>
            </main>
            <div id="tip" hidden></div>
            """;

    private static final String JS = """
            (function(){
            'use strict';
            var F=window.FITNESS;if(!F||!F.ctl.length)return;
            var CTL=F.ctl,ATL=F.atl,LOAD=F.load,N=CTL.length,ICONS=F.icons||{};
            var NL=window.LANG=='nl',T=window.L||function(e){return e};
            var MONTHS=NL?['jan','feb','mrt','apr','mei','jun','jul','aug','sep','okt','nov','dec']:['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
            var WEEKDAYS=NL?['zo','ma','di','wo','do','vr','za']:['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];
            if(NL)document.title=document.title.replace('Fitness and form','Conditie en vorm');
            var C={fresh:'#3d8fd6',tired:'#d9702a',moon:'#eef1f7',fog:'#a9b4ca',dim:'#76829b',rule:'#1d2a44',night:'#0a1220'};
            var S0=Date.UTC(+F.start.slice(0,4),+F.start.slice(5,7)-1,+F.start.slice(8,10));
            function dt(i){return new Date(S0+i*864e5)}
            function iso(i){return dt(i).toISOString().slice(0,10)}
            function fmtDate(i){var d=dt(i);return d.getUTCDate()+' '+MONTHS[d.getUTCMonth()]+' '+d.getUTCFullYear()}
            function href(i,id){var s=iso(i);return s.slice(0,4)+'/'+s.slice(5,7)+'/'+s+'-report.html'+(id?'#a'+id:'')}
            function esc(s){return String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
            function fmtDur(s){var h=Math.floor(s/3600),m=Math.round(s%3600/60);return h?h+(NL?' u ':' h ')+String(m).padStart(2,'0'):m+' min'}
            function tsb(i){return CTL[i]-ATL[i]}
            function zone(v){return v>F.fresh?'fresh':v>=F.neutral?'neutral':v>=F.tired?'tired':'overreaching'}
            var ZONE_NL={fresh:'fris',neutral:'neutraal',tired:'vermoeid',overreaching:'overbelast'};
            function zoneName(v){var z=zone(v);return NL?ZONE_NL[z]:z}
            var SRC={h:'',g:T(' (Garmin load)',' (Garmin-belasting)'),d:T(' (estimated, no heart rate)',' (geschat, geen hartslag)')};
            var byDay={},KEY=[];
            F.acts.forEach(function(a){var x={i:a[0],id:a[1],sport:a[2],name:a[3],time:a[4],secs:a[5],load:a[6],src:a[7],kind:a[8]};
              (byDay[x.i]=byDay[x.i]||[]).push(x);if(x.kind)KEY.push(x)});
            var zl=zone(tsb(N-1));
            document.getElementById('formDot').style.background=zl=='fresh'?C.fresh:zl=='neutral'?C.dim:C.tired;

            var tip=document.getElementById('tip');
            function showTip(html,x,y){tip.innerHTML=html;tip.hidden=false;var w=tip.offsetWidth,h=tip.offsetHeight;
              var l=x+16;if(l+w>innerWidth-8)l=x-w-16;tip.style.left=Math.max(8,l)+'px';
              tip.style.top=Math.max(8,Math.min(innerHeight-h-8,y-h/2))+'px'}
            function hideTip(){tip.hidden=true}

            // ---- shared view: the days [v0, v1]
            var v0=Math.max(0,N-365),v1=N-1,hover=-1;
            var main=document.getElementById('main'),formC=document.getElementById('formChart'),brush=document.getElementById('brush');
            var L=44,R=16;
            function W(el){return Math.max(300,el.parentNode.clientWidth)}
            function X(i,w){return L+(i-v0)/Math.max(1,v1-v0)*(w-L-R)}
            function dayAt(px,w){return Math.round(v0+(px-L)/(w-L-R)*(v1-v0))}
            function nice(m){var p=Math.pow(10,Math.floor(Math.log10(m))),f=m/p;return (f<=1?1:f<=2?2:f<=2.5?2.5:f<=5?5:10)*p}
            function yearTicks(w){var s='',a=dt(v0),b=dt(v1),span=v1-v0;
              if(span<=500){var pm=(w-L-R)/(span/30.4),every=pm>=60?1:pm>=30?2:pm>=18?3:6;for(var y=a.getUTCFullYear(),m=a.getUTCMonth();;){var t=(Date.UTC(y,m,1)-S0)/864e5;if(t>v1)break;
                if(t>=v0){var x=X(t,w);s+='<line x1="'+x+'" x2="'+x+'" y1="0" y2="6" stroke="'+C.rule+'"/>';
                  if(m%every==0)s+='<text x="'+(x+3)+'" y="16" fill="'+C.dim+'" font-size="12">'+MONTHS[m]+(m==0?' '+y:'')+'</text>'}
                if(++m>11){m=0;y++}}}
              else{var step=span>3650?2:1;for(var y=a.getUTCFullYear()+1;y<=b.getUTCFullYear();y++){if(y%step)continue;var t=(Date.UTC(y,0,1)-S0)/864e5,x=X(t,w);
                s+='<line x1="'+x+'" x2="'+x+'" y1="0" y2="6" stroke="'+C.rule+'"/><text x="'+(x+3)+'" y="16" fill="'+C.dim+'" font-size="12">'+y+'</text>'}}
              return s}
            function gapRects(w,top,h){return F.gaps.filter(function(g){return g[1]>=v0&&g[0]<=v1}).map(function(g){
              var a=X(Math.max(v0,g[0]),w),b=X(Math.min(v1,g[1]),w);return '<rect x="'+a+'" y="'+top+'" width="'+Math.max(1,b-a)+'" height="'+h+'" fill="url(#hatch)"/>'}).join('')}
            var HATCH='<defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="rgba(15,26,46,.6)"/><line x1="0" y1="0" x2="0" y2="6" stroke="#1d2a44" stroke-width="2"/></pattern></defs>';
            function line(arr,w,Y){var s='',step=Math.max(1,Math.floor((v1-v0)/(w*1.5)));
              for(var i=v0;i<=v1;i+=step)s+=(i==v0?'M':'L')+X(i,w).toFixed(1)+' '+Y(arr[i]).toFixed(1);
              if((v1-v0)%step)s+='L'+X(v1,w).toFixed(1)+' '+Y(arr[v1]).toFixed(1);return s}

            // ---- fitness and fatigue
            var mainY,mainH;
            function drawMain(){var w=W(main),h=mainH=innerWidth<700?280:400,top=10,bot=h-26,mx=1;
              for(var i=v0;i<=v1;i++){if(CTL[i]>mx)mx=CTL[i];if(ATL[i]>mx)mx=ATL[i]}
              var st=nice(mx/4),ymax=Math.ceil(mx*1.05/st)*st;
              mainY=function(v){return bot-v/ymax*(bot-top)};
              var s=HATCH;
              for(var v=0;v<=ymax+1e-9;v+=st){var y=mainY(v);s+='<line x1="'+L+'" x2="'+(w-R)+'" y1="'+y+'" y2="'+y+'" stroke="'+C.rule+'"/>'
                +'<text x="'+(L-8)+'" y="'+(y+4)+'" fill="'+C.dim+'" font-size="12" text-anchor="end">'+v+'</text>'}
              s+=gapRects(w,top,bot-top);
              s+='<g transform="translate(0,'+bot+')">'+yearTicks(w)+'</g>';
              s+='<path d="'+line(ATL,w,mainY)+'" fill="none" stroke="rgba(238,241,247,.38)" stroke-width="1.25" stroke-linejoin="round"/>';
              s+='<path d="'+line(CTL,w,mainY)+'" fill="none" stroke="'+C.moon+'" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/>';
              F.peaks.forEach(function(p,k){if(p<v0||p>v1)return;var x=X(p,w),y=mainY(CTL[p]);
                s+='<text x="'+x+'" y="'+(y-12)+'" fill="'+C.moon+'" font-size="13" font-weight="700" text-anchor="middle">'+Math.round(CTL[p])+'</text>'});
              var tx=X(N-1,w),ty=mainY(CTL[N-1]);
              if(N-1<=v1&&N-1>=v0)s+='<circle cx="'+tx+'" cy="'+ty+'" r="4" fill="'+C.moon+'"/><text x="'+(tx-8)+'" y="'+(ty-18)+'" fill="'+C.moon+'" font-size="13" font-weight="700" text-anchor="end">'+T('Today ','Vandaag ')+Math.round(CTL[N-1])+'</text>';
              s+='<g class="cross" visibility="hidden"><line y1="'+top+'" y2="'+bot+'" stroke="'+C.dim+'"/><circle r="4" fill="'+C.moon+'" stroke="'+C.night+'" stroke-width="2"/></g>';
              s+='<rect class="hit" x="'+L+'" y="'+top+'" width="'+(w-L-R)+'" height="'+(bot-top)+'" fill="transparent"/>';
              var keys=KEY.filter(function(a){return a.i>=v0&&a.i<=v1}),many=(v1-v0)>1500;
              keys.sort(function(a,b){return a.kind-b.kind});
              s+=keys.map(function(a){var x=X(a.i,w),y=mainY(CTL[a.i]),r=many?3.5:5,k=KEY.indexOf(a);
                return '<a href="'+href(a.i,a.id)+'" data-k="'+k+'" aria-label="'+esc((a.kind==1?T('Race: ','Wedstrijd: '):T('Big session: ','Zware training: '))+a.name+', '+fmtDate(a.i))+'">'
                  +(a.kind==1?'<circle cx="'+x+'" cy="'+y+'" r="'+r+'" fill="'+C.moon+'" stroke="'+C.night+'" stroke-width="2"/>'
                    :'<circle cx="'+x+'" cy="'+y+'" r="'+r+'" fill="'+C.night+'" stroke="'+C.moon+'" stroke-width="1.75"/>')+'</a>'}).join('');
              main.setAttribute('viewBox','0 0 '+w+' '+h);main.setAttribute('height',h);main.innerHTML=s;
              var cl=Math.round(Math.min.apply(null,CTL.slice(v0,v1+1))),ch=Math.round(Math.max.apply(null,CTL.slice(v0,v1+1)));
              document.getElementById('rangeText').textContent=NL?fmtDate(v0)+' tot '+fmtDate(v1)+'. De conditie ging van '+cl+' tot '+ch+'.'
                :fmtDate(v0)+' to '+fmtDate(v1)+'. Fitness ranged from '+cl+' to '+ch+'.'}

            // ---- form, with its zones
            var formY;
            function drawForm(){var w=W(formC),h=innerWidth<700?190:240,top=8,bot=h-26,lo=F.tired-15,hi=F.fresh+20;
              for(var i=v0;i<=v1;i++){var t=tsb(i);if(t<lo)lo=t;if(t>hi)hi=t}
              var st=nice((hi-lo)/4);lo=Math.floor(lo/st)*st;hi=Math.ceil(hi/st)*st;
              formY=function(v){return top+(hi-v)/(hi-lo)*(bot-top)};
              var s=HATCH+'<defs><clipPath id="above"><rect x="0" y="0" width="'+w+'" height="'+formY(0)+'"/></clipPath>'
                +'<clipPath id="below"><rect x="0" y="'+formY(0)+'" width="'+w+'" height="'+(h-formY(0))+'"/></clipPath></defs>';
              function band(a,b,fill,label){var ya=formY(Math.min(hi,a)),yb=formY(Math.max(lo,b));if(yb<=ya)return '';
                return '<rect x="'+L+'" y="'+ya+'" width="'+(w-L-R)+'" height="'+(yb-ya)+'" fill="'+fill+'"/>'
                  +'<text x="'+(w-R-6)+'" y="'+(ya+15)+'" fill="'+C.fog+'" font-size="12" text-anchor="end">'+label+'</text>'}
              s+=band(hi,F.fresh,'rgba(61,143,214,.10)',T('Fresh','Fris'))+band(F.fresh,F.neutral,'rgba(118,130,155,.07)',T('Neutral','Neutraal'))
                +band(F.neutral,F.tired,'rgba(217,112,42,.08)',T('Tired','Vermoeid'))+band(F.tired,lo,'rgba(217,112,42,.17)',T('Overreaching','Overbelast'));
              for(var v=lo;v<=hi+1e-9;v+=st){var y=formY(v);s+='<text x="'+(L-8)+'" y="'+(y+4)+'" fill="'+C.dim+'" font-size="12" text-anchor="end">'+(v>0?'+':'')+v+'</text>'}
              s+=gapRects(w,top,bot-top);
              s+='<g transform="translate(0,'+bot+')">'+yearTicks(w)+'</g>';
              var p=line(CTL.map(function(c,i){return c-ATL[i]}),w,formY),y0=formY(0),area=p+'L'+X(v1,w).toFixed(1)+' '+y0+'L'+X(v0,w).toFixed(1)+' '+y0+'Z';
              s+='<path d="'+area+'" fill="rgba(61,143,214,.55)" clip-path="url(#above)"/><path d="'+area+'" fill="rgba(217,112,42,.55)" clip-path="url(#below)"/>';
              s+='<path d="'+p+'" fill="none" stroke="'+C.fresh+'" stroke-width="1.25" clip-path="url(#above)"/><path d="'+p+'" fill="none" stroke="'+C.tired+'" stroke-width="1.25" clip-path="url(#below)"/>';
              s+='<line x1="'+L+'" x2="'+(w-R)+'" y1="'+y0+'" y2="'+y0+'" stroke="'+C.dim+'"/>';
              s+='<g class="cross" visibility="hidden"><line y1="'+top+'" y2="'+bot+'" stroke="'+C.dim+'"/><circle r="4" fill="'+C.moon+'" stroke="'+C.night+'" stroke-width="2"/></g>';
              s+='<rect class="hit" x="'+L+'" y="'+top+'" width="'+(w-L-R)+'" height="'+(bot-top)+'" fill="transparent"/>';
              formC.setAttribute('viewBox','0 0 '+w+' '+h);formC.setAttribute('height',h);formC.innerHTML=s}

            // ---- brush: all years, the window is the period shown
            var bw=0,BH=64;
            function BX(i){return L+i/Math.max(1,N-1)*(bw-L-R)}
            function bDay(px){return Math.round((px-L)/(bw-L-R)*(N-1))}
            function drawBrush(){bw=W(brush);var mx=Math.max.apply(null,CTL)||1,top=4,bot=BH-20,s=HATCH,step=Math.max(1,Math.floor(N/bw));
              var p='M'+BX(0)+' '+bot;for(var i=0;i<N;i+=step)p+='L'+BX(i).toFixed(1)+' '+(bot-CTL[i]/mx*(bot-top)).toFixed(1);
              p+='L'+BX(N-1)+' '+bot+'Z';
              s+='<rect x="'+L+'" y="'+top+'" width="'+(bw-L-R)+'" height="'+(bot-top)+'" fill="'+C.night+'" stroke="'+C.rule+'"/>';
              s+='<path d="'+p+'" fill="rgba(169,180,202,.35)"/>';
              var a=dt(0).getUTCFullYear(),b=dt(N-1).getUTCFullYear(),each=(bw/(b-a+1))<36?2:1;
              for(var y=a+1;y<=b;y++){if(y%each)continue;var x=BX((Date.UTC(y,0,1)-S0)/864e5);s+='<text x="'+(x+2)+'" y="'+(BH-5)+'" fill="'+C.dim+'" font-size="11">'+y+'</text>'
                +'<line x1="'+x+'" x2="'+x+'" y1="'+bot+'" y2="'+(bot+4)+'" stroke="'+C.rule+'"/>'}
              var x0=BX(v0),x1=BX(v1);
              s+='<rect x="'+L+'" y="'+top+'" width="'+Math.max(0,x0-L)+'" height="'+(bot-top)+'" fill="rgba(10,18,32,.55)"/>';
              s+='<rect x="'+x1+'" y="'+top+'" width="'+Math.max(0,bw-R-x1)+'" height="'+(bot-top)+'" fill="rgba(10,18,32,.55)"/>';
              s+='<rect class="win" x="'+x0+'" y="'+top+'" width="'+Math.max(2,x1-x0)+'" height="'+(bot-top)+'" fill="rgba(238,241,247,.10)" stroke="'+C.moon+'" stroke-width="1.5" rx="2"/>';
              s+='<rect class="h" data-h="0" x="'+(x0-5)+'" y="'+top+'" width="10" height="'+(bot-top)+'" fill="transparent"/><rect class="h" data-h="1" x="'+(x1-5)+'" y="'+top+'" width="10" height="'+(bot-top)+'" fill="transparent"/>';
              s+='<rect x="'+(x0-2)+'" y="'+(top+(bot-top)/2-9)+'" width="4" height="18" rx="2" fill="'+C.moon+'" pointer-events="none"/><rect x="'+(x1-2)+'" y="'+(top+(bot-top)/2-9)+'" width="4" height="18" rx="2" fill="'+C.moon+'" pointer-events="none"/>';
              brush.setAttribute('viewBox','0 0 '+bw+' '+BH);brush.setAttribute('height',BH);brush.innerHTML=s;
              brush.setAttribute('aria-valuetext',fmtDate(v0)+T(' to ',' tot ')+fmtDate(v1))}

            function setView(a,b){var span=Math.max(30,b-a);a=Math.round(a);b=Math.round(a+span);
              if(a<0){b-=a;a=0}if(b>N-1){a-=b-(N-1);b=N-1}a=Math.max(0,a);v0=a;v1=b;draw();
              document.querySelectorAll('.seg button').forEach(function(btn){var sp=btn.dataset.span;
                btn.setAttribute('aria-pressed',v1==N-1&&(sp=='all'?v0==0:Math.abs((v1-v0)-(+sp-1))<=1))})}
            function draw(){drawMain();drawForm();drawBrush();if(hover>=0)cross(hover)}

            // ---- crosshair and tooltip
            function cross(i){[[main,mainY,CTL[i]],[formC,formY,tsb(i)]].forEach(function(c){var g=c[0].querySelector('.cross');if(!g)return;
              if(i<v0||i>v1){g.setAttribute('visibility','hidden');return}var x=X(i,W(c[0]));
              g.querySelector('line').setAttribute('x1',x);g.querySelector('line').setAttribute('x2',x);
              g.querySelector('circle').setAttribute('cx',x);g.querySelector('circle').setAttribute('cy',c[1](c[2]));g.setAttribute('visibility','visible')})}
            function dayTip(i){var d=dt(i),t=tsb(i),acts=byDay[i]||[];
              var s='<b>'+WEEKDAYS[d.getUTCDay()]+' '+fmtDate(i)+'</b>'
                +'<div class="row"><span>'+T('Fitness','Conditie')+'</span><span>'+Math.round(CTL[i])+'</span></div>'
                +'<div class="row"><span>'+T('Fatigue','Vermoeidheid')+'</span><span>'+Math.round(ATL[i])+'</span></div>'
                +'<div class="row"><span>'+T('Form','Vorm')+'</span><span>'+(t>0?'+':'')+Math.round(t)+', '+zoneName(t)+'</span></div>';
              if(acts.length)s+='<div class="acts">'+acts.map(function(a){return (ICONS[a.sport]||'')+' '+esc(a.name||a.sport)+', '+fmtDur(a.secs)+', '+T('load ','belasting ')+a.load+SRC[a.src]}).join('<br>')+'</div>';
              else if(F.gaps.some(function(g){return i>=g[0]&&i<=g[1]}))s+='<div class="acts">'+T('No recordings in this stretch','Geen opnames in deze periode')+'</div>';
              else s+='<div class="acts">'+T('Rest day','Rustdag')+'</div>';
              return s}
            function keyTip(a){return '<b>'+(a.kind==1?T('Race','Wedstrijd'):T('One of the year\\'s biggest sessions','Een van de zwaarste trainingen van het jaar'))+'</b><br>'+(ICONS[a.sport]||'')+' '+esc(a.name||a.sport)
              +'<br>'+fmtDate(a.i)+(a.time?', '+a.time:'')+', '+fmtDur(a.secs)+'<br>'+T('Load ','Belasting ')+a.load+SRC[a.src]+'<br>'+T('Fitness that day ','Conditie die dag ')+Math.round(CTL[a.i])
              +T(', form ',', vorm ')+Math.round(tsb(a.i))+' ('+zoneName(tsb(a.i))+')<br><span style="color:#76829b">'+T('Click to open the report','Klik om het verslag te openen')+'</span>'}
            [main,formC].forEach(function(svg){
              svg.addEventListener('pointermove',function(e){var k=e.target.closest&&e.target.closest('a[data-k]');
                var r=svg.getBoundingClientRect(),w=W(svg),i=Math.max(v0,Math.min(v1,dayAt((e.clientX-r.left)/r.width*w,w)));
                if(k){var a=KEY[+k.dataset.k];hover=a.i;cross(a.i);showTip(keyTip(a),e.clientX,e.clientY);return}
                if(e.target.classList.contains('hit')){hover=i;cross(i);showTip(dayTip(i),e.clientX,e.clientY)}});
              svg.addEventListener('pointerleave',function(){hover=-1;hideTip();[main,formC].forEach(function(s){var g=s.querySelector('.cross');if(g)g.setAttribute('visibility','hidden')})});
              svg.addEventListener('click',function(e){if(e.target.classList.contains('hit')&&hover>=0&&byDay[hover])location.href=href(hover,byDay[hover][0].id)});
              svg.addEventListener('keydown',function(e){var st=e.shiftKey?7:1;
                if(e.key=='ArrowLeft'||e.key=='ArrowRight'){e.preventDefault();if(hover<0)hover=v1;hover=Math.max(v0,Math.min(v1,hover+(e.key=='ArrowLeft'?-st:st)));
                  cross(hover);var r=svg.getBoundingClientRect(),w=W(svg);showTip(dayTip(hover),r.left+X(hover,w)/w*r.width,r.top+r.height/2)}
                else if(e.key=='Enter'&&hover>=0&&byDay[hover])location.href=href(hover,byDay[hover][0].id);
                else if(e.key=='Escape'){hover=-1;hideTip()}});
              svg.addEventListener('blur',function(){hideTip()});
            });
            main.addEventListener('focusin',function(e){var k=e.target.closest&&e.target.closest('a[data-k]');if(!k)return;var a=KEY[+k.dataset.k],r=k.getBoundingClientRect();
              cross(a.i);showTip(keyTip(a),r.right,r.top+r.height/2)});
            main.addEventListener('focusout',function(e){if(e.target.closest&&e.target.closest('a[data-k]'))hideTip()});

            // ---- brushing
            var drag=null;
            brush.addEventListener('pointerdown',function(e){var r=brush.getBoundingClientRect(),px=(e.clientX-r.left)/r.width*bw,d=Math.max(0,Math.min(N-1,bDay(px)));
              var h=e.target.dataset&&e.target.dataset.h;
              if(h!=null)drag={mode:'h'+h};else if(e.target.classList.contains('win'))drag={mode:'move',d:d,a:v0,b:v1};else drag={mode:'new',d:d};
              brush.setPointerCapture(e.pointerId);e.preventDefault()});
            brush.addEventListener('pointermove',function(e){if(!drag)return;var r=brush.getBoundingClientRect(),px=(e.clientX-r.left)/r.width*bw,d=Math.max(0,Math.min(N-1,bDay(px)));
              if(drag.mode=='move')setView(drag.a+d-drag.d,drag.b+d-drag.d);
              else if(drag.mode=='h0')setView(Math.min(d,v1-30),v1);
              else if(drag.mode=='h1')setView(v0,Math.max(d,v0+30));
              else if(Math.abs(d-drag.d)>=3)setView(Math.min(d,drag.d),Math.max(d,drag.d))});
            brush.addEventListener('pointerup',function(e){if(drag&&drag.mode=='new'&&!(v0<=drag.d&&drag.d<=v1)){var sp=v1-v0;setView(drag.d-sp/2,drag.d+sp/2)}drag=null});
            brush.addEventListener('keydown',function(e){var sp=v1-v0,st=e.shiftKey?Math.max(30,Math.round(sp/2)):Math.max(7,Math.round(sp/12));
              if(e.key=='ArrowLeft'){e.preventDefault();setView(v0-st,v1-st)}else if(e.key=='ArrowRight'){e.preventDefault();setView(v0+st,v1+st)}
              else if(e.key=='+'||e.key=='='){e.preventDefault();setView(v0+sp/4,v1-sp/4)}else if(e.key=='-'){e.preventDefault();setView(v0-sp/2,v1+sp/2)}
              else if(e.key=='Home'){e.preventDefault();setView(0,sp)}else if(e.key=='End'){e.preventDefault();setView(N-1-sp,N-1)}});
            // The peaks follow the range buttons (not the brush or a click on a peak, so the list does not jump away).
            var PEAKTEXT={'365':[T('Your peaks in the last 12 months','Je pieken in de laatste 12 maanden'),
                T('The three highest points of the past year, at least a month apart. Pick one to see it on the chart.','De drie hoogste punten van het voorbije jaar, minstens een maand uit elkaar. Kies er een om hem op de grafiek te zien.')],
              '1096':[T('Your peaks in the last 3 years','Je pieken in de laatste 3 jaar'),
                T('The three highest points of the past three years, at least three months apart. Pick one to see it on the chart.','De drie hoogste punten van de voorbije drie jaar, minstens drie maanden uit elkaar. Kies er een om hem op de grafiek te zien.')],
              'all':[T('Your peaks over the years','Je pieken door de jaren heen'),
                T('For the record: your three highest peaks ever, at least four months apart. Not a yardstick for today. Pick one to see it on the chart.','Ter herinnering: je drie hoogste pieken ooit, minstens vier maanden uit elkaar. Geen maatstaf voor vandaag. Kies er een om hem op de grafiek te zien.')]};
            function showPeaks(sp){document.querySelectorAll('ol.peaks').forEach(function(o){o.hidden=o.dataset.span!=sp});
              document.getElementById('bestH').textContent=PEAKTEXT[sp][0];document.getElementById('bestLede').textContent=PEAKTEXT[sp][1]}
            showPeaks('365');
            document.querySelectorAll('.seg button').forEach(function(btn){btn.onclick=function(){var sp=btn.dataset.span;
              if(sp=='all')setView(0,N-1);else setView(N-+sp,N-1);showPeaks(sp)}});
            document.querySelectorAll('.peak').forEach(function(btn){btn.onclick=function(){var p=+btn.dataset.day;setView(p-200,p+165);
              document.getElementById('fitH').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'})}});
            var rt=0;window.addEventListener('resize',function(){clearTimeout(rt);rt=setTimeout(draw,80)});
            setView(Math.max(0,N-365),N-1);
            })();
            """;
}
