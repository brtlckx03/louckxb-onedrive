package net.lckx.garmin;

import static net.lckx.util.JsonHelpers.jsonEscape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import net.lckx.garmin.DescribeGarminFile.Activity;
import net.lckx.garmin.DescribeGarminFile.FitMessage;

/**
 * A corrected distance for pool swims, written to its own file so the Garmin data is never changed.
 *
 * <p>The watch counts lengths by detecting turns, and gets that wrong: it sees phantom turns (one real
 * length recorded as two short ones) or misses turns (two lengths recorded as one long one). The stroke
 * count is much more reliable, so each lap is checked against a reference length: strokes and seconds per
 * length. A consistent swim (length times with a small spread, see {@link #MAX_SPREAD}) is its own reference:
 * the medians of its normal-looking lengths. A messy swim cannot be: when most lengths are split, its "normal"
 * length is a split one. It uses the {@link #NEARBY} closest consistent swims (within {@link #MAX_DAYS} days)
 * instead, read from their correction files. A lap is
 * recounted as {@code strokes / reference} only when both
 * <ul>
 *   <li>its strokes per recorded length are more than {@link #TOLERANCE} off the reference, and</li>
 *   <li>at least {@link #MIN_OUTLIER_SHARE} of its lengths have an implausible time (under
 *       {@link #SHORT} or over {@link #LONG} times the reference time) — the trace of a missed or phantom turn.</li>
 * </ul>
 * Otherwise the watch's count stands: a swimmer who simply takes more strokes that day is not an error.
 *
 * <p>The file lives at {@code <month>/corrected/<activity base>.json}. Its {@code overrides} block is
 * kept when the file is regenerated: set {@code strokesPerLength} to use another reference for that swim,
 * or {@code distance} (metres) when the real distance is known, which then wins over the calculation.
 */
final class SwimCorrection {
    /** A lap whose strokes per length differ less than this from the reference is trusted as recorded. */
    static final double TOLERANCE = 0.15;
    /** Length times outside [SHORT, LONG] × the reference time are implausible. */
    static final double SHORT = 0.6, LONG = 1.6;
    static final double MIN_OUTLIER_SHARE = 0.10;
    /** Stroke counts above this per length are corrupt data, not swimming. */
    static final double MAX_STROKES = 60;
    /** A swim whose length times have an interquartile range up to this share of the median is consistent. */
    static final double MAX_SPREAD = 0.25;
    static final int MIN_LENGTHS_TRUSTED = 15, NEARBY = 5, MAX_DAYS = 365;
    static final String DIR = "corrected";

    record Lap(int lap, int garminLengths, double strokes, int lengths, boolean recounted, double seconds) {}

    /** This swim's own length statistics, also used as reference by nearby messy swims. */
    record Self(LocalDate date, double strokesPerLength, double secondsPerLength, double spread, int lengths) {
        boolean trusted() {
            return lengths >= MIN_LENGTHS_TRUSTED && spread <= MAX_SPREAD;
        }
    }

    /**
     * @param cumulative corrected distance after each pool length (index as in the FIT length messages)
     */
    record Result(Path file, double pool, double strokesPerLength, double secondsPerLength, String referenceSource,
                  List<LocalDate> referenceSwims, Self self, boolean customReference, Double manualDistance,
                  List<Lap> laps, double[] cumulative) {
        int garminLengths() {
            return laps.stream().mapToInt(Lap::garminLengths).sum();
        }

        int lengths() {
            return laps.stream().mapToInt(Lap::lengths).sum();
        }

        double garminDistance() {
            return garminLengths() * pool;
        }

        double distance() {
            return manualDistance != null ? manualDistance : lengths() * pool;
        }

        boolean differs() {
            return Math.abs(distance() - garminDistance()) >= 0.5;
        }

        String method() {
            return manualDistance != null ? "manual" : "strokes-per-length";
        }
    }

    private SwimCorrection() {
    }

    static Path pathFor(Activity a) {
        return a.files.fit().getParent().resolve(DIR).resolve(a.files.base() + ".json");
    }

    /** Computes the correction for a pool swim and (re)writes its file; empty for anything that has no pool lengths. */
    static Optional<Result> update(Activity a) {
        if (a.fit == null || a.files.fit() == null || a.fit.count(101) == 0 || a.fit.count(19) == 0) return Optional.empty();
        Path file = pathFor(a);
        Map<String, Object> overrides = readOverrides(file);
        Double custom = positive(overrides.get("strokesPerLength"));
        Double manual = positive(overrides.get("distance"));

        FitMessage session = a.fit.first(18);
        Double p = session == null ? null : session.scaled(44);
        double pool = p == null || p <= 0 ? 25 : p;
        List<FitMessage> lens = a.fit.messages(101);
        // The swim's own reference, from lengths whose time is near the median (so not split or merged).
        List<double[]> act = new ArrayList<>();
        for (FitMessage len : lens) {
            Double t = len.scaled(4), s = strokes(len);
            if (active(len) && t != null && t > 0 && s != null) act.add(new double[]{t, s});
        }
        if (act.isEmpty()) return Optional.empty();
        double medT = median(act.stream().map(x -> x[0]).toList());
        List<double[]> clean = act.stream().filter(x -> x[0] >= 0.75 * medT && x[0] <= 1.33 * medT).toList();
        if (clean.isEmpty()) clean = act;
        List<Double> times = act.stream().map(x -> x[0]).sorted().toList();
        double spread = (times.get(times.size() * 3 / 4) - times.get(times.size() / 4)) / medT;
        LocalDate date = LocalDate.parse(a.files.base().substring(0, 10));
        Self self = new Self(date, median(clean.stream().map(x -> x[1]).toList()),
                median(clean.stream().map(x -> x[0]).toList()), spread, act.size());
        remember(a.files.fit(), file, self);

        double ref = self.strokesPerLength(), refT = self.secondsPerLength();
        String source = "this swim";
        List<LocalDate> from = List.of();
        if (!self.trusted()) {
            List<Self> near = nearbyTrusted(a.files.fit(), file, date);
            if (!near.isEmpty()) {
                // Nearby swims tell how long a real length takes; this swim's lengths of about that time then give
                // its own strokes per length (stroke counts differ more between days than length times do).
                double anchor = median(near.stream().map(Self::secondsPerLength).toList());
                List<double[]> real = act.stream().filter(x -> x[0] >= 0.75 * anchor && x[0] <= 1.33 * anchor).toList();
                if (real.size() >= Math.max(8, 0.2 * act.size())) {
                    ref = median(real.stream().map(x -> x[1]).toList());
                    refT = median(real.stream().map(x -> x[0]).toList());
                    source = "this swim's lengths that take as long as in nearby consistent swims";
                } else {
                    // Too few lengths look real: another day's stroke count would be a guess, so nothing is recounted.
                    source = "none: too few lengths of a plausible time, kept as recorded";
                }
                from = near.stream().map(Self::date).sorted().toList();
            }
        }
        if (custom != null) {
            ref = custom;
            source = "override";
        }

        List<Lap> laps = new ArrayList<>();
        int n = 0;
        for (FitMessage lap : a.fit.messages(19)) {
            n++;
            Double first = lap.num(35), count = lap.num(32);
            if (first == null || count == null || count <= 0 || first + count > lens.size()) continue;
            int active = 0, withoutStrokes = 0, outliers = 0;
            double strokes = 0, seconds = 0;
            for (FitMessage len : lens.subList(first.intValue(), first.intValue() + count.intValue())) {
                if (!active(len)) continue; // idle (rest) lengths carry no distance
                active++;
                Double t = len.scaled(4);
                if (t != null) {
                    seconds += t;
                    if (t < SHORT * refT || t > LONG * refT) outliers++;
                }
                Double s = strokes(len);
                if (s == null) withoutStrokes++;
                else strokes += s;
            }
            if (active == 0) continue;
            int counted = active - withoutStrokes;
            // Lengths without a usable stroke count (e.g. kicking) cannot be checked and are kept as recorded.
            boolean recount = !source.startsWith("none") && counted > 0 && Math.abs(strokes / counted / ref - 1) > TOLERANCE
                    && outliers >= Math.max(1, MIN_OUTLIER_SHARE * active);
            int lengths = recount ? Math.max(1, (int) Math.round(strokes / ref)) + withoutStrokes : active;
            laps.add(new Lap(n, active, strokes, lengths, recount, seconds));
        }
        if (laps.isEmpty()) return Optional.empty();
        double[] cum = cumulative(a, lens, laps, ref, pool);
        Result r = new Result(file, pool, ref, refT, source, from, self, custom != null, manual, laps, cum);
        try {
            write(a, r);
        } catch (IOException e) {
            System.err.println("Could not write " + file + ": " + e.getMessage());
        }
        return Optional.of(r);
    }

    /** Corrected distance after each length: recounted laps spread their corrected total over their strokes. */
    private static double[] cumulative(Activity a, List<FitMessage> lens, List<Lap> laps, double ref, double pool) {
        double[] inc = new double[lens.size()];
        for (int i = 0; i < lens.size(); i++) inc[i] = active(lens.get(i)) ? pool : 0;
        List<FitMessage> lapMsgs = a.fit.messages(19);
        for (Lap l : laps) {
            if (!l.recounted()) continue;
            FitMessage m = lapMsgs.get(l.lap() - 1);
            int first = m.num(35).intValue(), count = m.num(32).intValue();
            double sum = 0;
            for (int i = first; i < first + count; i++) {
                if (!active(lens.get(i))) continue;
                Double s = strokes(lens.get(i));
                inc[i] = s == null ? pool : s / ref * pool;
                sum += inc[i];
            }
            double scale = sum > 0 ? l.lengths() * pool / sum : 1;
            for (int i = first; i < first + count; i++) inc[i] *= scale;
        }
        double[] cum = new double[lens.size()];
        double run = 0;
        for (int i = 0; i < lens.size(); i++) cum[i] = run += inc[i];
        return cum;
    }

    // ---- reference swims: every correction file records its own statistics ("self")

    private static Map<Path, Self> known;

    private static Path root(Path fit) {
        return fit.getParent().getParent().getParent(); // <root>/YYYY/MM/file.fit
    }

    private static synchronized void remember(Path fit, Path file, Self self) {
        if (known == null) load(root(fit));
        known.put(file.toAbsolutePath().normalize(), self);
    }

    private static synchronized List<Self> nearbyTrusted(Path fit, Path file, LocalDate date) {
        if (known == null) load(root(fit));
        Path me = file.toAbsolutePath().normalize();
        return known.entrySet().stream()
                .filter(e -> !e.getKey().equals(me) && e.getValue().trusted())
                .map(Map.Entry::getValue)
                .filter(s -> Math.abs(ChronoUnit.DAYS.between(s.date(), date)) <= MAX_DAYS)
                .sorted(Comparator.comparingLong(s -> Math.abs(ChronoUnit.DAYS.between(s.date(), date))))
                .limit(NEARBY).toList();
    }

    private static void load(Path root) {
        known = new HashMap<>();
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> st = Files.walk(root, 4)) {
            for (Path p : (Iterable<Path>) st::iterator) {
                if (p.getParent() == null || !p.getParent().getFileName().toString().equals(DIR) || !p.toString().endsWith(".json")) continue;
                try {
                    if (DescribeGarminFile.Json.parse(Files.readString(p)) instanceof Map<?, ?> m && m.get("self") instanceof Map<?, ?> s
                            && s.get("strokesPerLength") instanceof Number spl && s.get("secondsPerLength") instanceof Number sec
                            && s.get("spread") instanceof Number sp && s.get("lengths") instanceof Number n) {
                        known.put(p.toAbsolutePath().normalize(), new Self(LocalDate.parse(p.getFileName().toString().substring(0, 10)),
                                spl.doubleValue(), sec.doubleValue(), sp.doubleValue(), n.intValue()));
                    }
                } catch (Exception ignored) {
                    // an unreadable file just isn't used as a reference
                }
            }
        } catch (IOException e) {
            System.err.println("Could not scan " + root + " for swim references: " + e.getMessage());
        }
    }

    private static boolean active(FitMessage len) {
        return Double.valueOf(1).equals(len.num(12));
    }

    private static Double strokes(FitMessage len) {
        Double s = len.num(5);
        return s == null || s <= 0 || s > MAX_STROKES ? null : s;
    }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.sort(null);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private static Map<String, Object> readOverrides(Path file) {
        if (!Files.exists(file)) return Map.of();
        try {
            Object root = DescribeGarminFile.Json.parse(Files.readString(file));
            if (root instanceof Map<?, ?> m && m.get("overrides") instanceof Map<?, ?> o) {
                @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) o;
                return cast;
            }
        } catch (Exception e) {
            System.err.println("Ignoring unreadable " + file + ": " + e.getMessage());
        }
        return Map.of();
    }

    private static Double positive(Object o) {
        return o instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : null;
    }

    private static void write(Activity a, Result r) throws IOException {
        StringBuilder j = new StringBuilder("{\n");
        j.append("  \"activityId\": ").append(a.files.id()).append(",\n");
        j.append("  \"source\": \"").append(jsonEscape(a.files.fit().getFileName().toString())).append("\",\n");
        j.append("  \"note\": \"Derived by DescribeGarminFile; the Garmin files are not changed. Edit only 'overrides'.\",\n");
        j.append("  \"method\": \"").append(r.method()).append("\",\n");
        j.append("  \"poolLength\": ").append(fmt(r.pool())).append(",\n");
        j.append("  \"self\": {\"strokesPerLength\": ").append(fmt(r.self().strokesPerLength()))
                .append(", \"secondsPerLength\": ").append(fmt(r.self().secondsPerLength()))
                .append(", \"spread\": ").append(fmt(r.self().spread())).append(", \"lengths\": ").append(r.self().lengths())
                .append(", \"consistent\": ").append(r.self().trusted()).append("},\n");
        j.append("  \"reference\": {\"source\": \"").append(r.referenceSource()).append("\", \"swims\": [");
        for (int i = 0; i < r.referenceSwims().size(); i++) j.append(i > 0 ? ", " : "").append('"').append(r.referenceSwims().get(i)).append('"');
        j.append("]},\n");
        j.append("  \"strokesPerLength\": ").append(fmt(r.strokesPerLength())).append(",\n");
        j.append("  \"secondsPerLength\": ").append(fmt(r.secondsPerLength())).append(",\n");
        j.append("  \"rule\": \"recount a lap as strokes / strokesPerLength when its strokes per length are more than ")
                .append(fmt(TOLERANCE * 100)).append("% off and at least ").append(fmt(MIN_OUTLIER_SHARE * 100))
                .append("% of its lengths take under ").append(fmt(SHORT)).append(" or over ").append(fmt(LONG))
                .append(" x secondsPerLength\",\n");
        j.append("  \"garmin\": {\"lengths\": ").append(r.garminLengths()).append(", \"distance\": ").append(fmt(r.garminDistance())).append("},\n");
        j.append("  \"corrected\": {\"lengths\": ").append(r.lengths()).append(", \"distance\": ").append(fmt(r.distance())).append("},\n");
        j.append("  \"laps\": [");
        for (int i = 0; i < r.laps().size(); i++) {
            Lap l = r.laps().get(i);
            j.append(i == 0 ? "\n" : ",\n").append("    {\"lap\": ").append(l.lap())
                    .append(", \"garminLengths\": ").append(l.garminLengths())
                    .append(", \"strokes\": ").append(fmt(l.strokes()))
                    .append(", \"strokesPerGarminLength\": ").append(fmt(l.strokes() / l.garminLengths()))
                    .append(", \"seconds\": ").append(fmt(l.seconds()))
                    .append(", \"recounted\": ").append(l.recounted())
                    .append(", \"lengths\": ").append(l.lengths())
                    .append(", \"distance\": ").append(fmt(l.lengths() * r.pool())).append('}');
        }
        j.append("\n  ],\n");
        j.append("  \"overrides\": {\"strokesPerLength\": ").append(r.customReference() ? fmt(r.strokesPerLength()) : "null")
                .append(", \"distance\": ").append(r.manualDistance() == null ? "null" : fmt(r.manualDistance())).append("}\n}\n");
        String text = j.toString();
        if (Files.exists(r.file()) && Files.readString(r.file()).equals(text)) return; // unchanged: keep the timestamp
        Files.createDirectories(r.file().getParent());
        Files.writeString(r.file(), text, StandardCharsets.UTF_8);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }
}
