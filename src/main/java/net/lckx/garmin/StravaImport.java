package net.lckx.garmin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Adds the activities that Strava has but Garmin Connect does not, from a Strava account export
 * (Settings → My Account → Download or Delete Your Account → Request your archive, unzipped).
 * <p>Each missing activity is written to {@code <account folder>/strava/YYYY/MM/} as its original FIT, TCX or GPX
 * file plus a small summary JSON in the shape of Garmin's (marked {@code "source":"strava"}), so the day reports and
 * every page include it while the Garmin files stay untouched. An activity counts as missing when no Garmin activity
 * started within 15 minutes of it; activities under 1 km (empty recordings) are skipped. Running it again only adds
 * what is new.
 * <pre>
 * java --enable-preview -cp target/classes net.lckx.garmin.StravaImport EXPORT_FOLDER [--account NAME] [--dry-run]
 * </pre>
 */
public final class StravaImport {
    static final String FOLDER = "strava";
    private static final long SAME_START = 15 * 60;
    private static final double MIN_METRES = 1000;
    private static final ZoneId LOCAL = ZoneId.of("Europe/Brussels");
    private static final Pattern GARMIN_JSON = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d+_.*\\.json");
    /** "24 sep 2026, 17:14:44" (Dutch export; English month abbreviations work too). */
    private static final Pattern DATE = Pattern.compile("(\\d{1,2}) (\\p{L}+)\\.? (\\d{4}),? (\\d{1,2}):(\\d{2}):(\\d{2})(?: ?([AP]M))?");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mrt", 3), Map.entry("mar", 3), Map.entry("apr", 4), Map.entry("mei", 5),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9), Map.entry("okt", 10),
            Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));
    /** Strava's activity types (Dutch and English export) → Garmin's type keys. */
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("hardloopsessie", "running"), Map.entry("run", "running"),
            Map.entry("fietsrit", "cycling"), Map.entry("ride", "cycling"),
            Map.entry("virtuele fietsrit", "virtual_ride"), Map.entry("virtual ride", "virtual_ride"),
            Map.entry("wandeling", "walking"), Map.entry("walk", "walking"),
            Map.entry("hiken", "hiking"), Map.entry("hike", "hiking"),
            Map.entry("zwemmen", "lap_swimming"), Map.entry("swim", "lap_swimming"),
            Map.entry("kayakken", "kayaking_v2"), Map.entry("kayaking", "kayaking_v2"),
            Map.entry("roeien", "rowing_v2"), Map.entry("rowing", "rowing_v2"),
            Map.entry("training", "training"), Map.entry("workout", "training"));

    private StravaImport() {}

    public static void main(String[] args) throws IOException {
        Path export = null;
        boolean dry = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--account" -> DescribeGarminFile.useAccount(args[++i]);
                case "--dry-run" -> dry = true;
                default -> export = Path.of(args[i]);
            }
        }
        if (export == null || !Files.exists(export.resolve("activities.csv"))) {
            System.err.println("Usage: StravaImport EXPORT_FOLDER [--account NAME] [--dry-run]  (the folder with activities.csv)");
            System.exit(1);
        }
        run(export, dry);
    }

    record Row(String id, Instant start, String name, String type, double seconds, double moving, double metres,
               double avgHr, double maxHr, double kcal, double climb, double maxSpeed, String file) {}

    static void run(Path export, boolean dry) throws IOException {
        Path dir = DescribeGarminFile.dataDir(DescribeGarminFile.account());
        long[] garmin = garminStarts(dir);
        List<Row> rows = read(export.resolve("activities.csv"));
        int added = 0, present = 0, small = 0, known = 0, noFile = 0;
        double km = 0;
        for (Row r : rows) {
            long t = r.start.getEpochSecond();
            int i = Arrays.binarySearch(garmin, t - SAME_START);
            if (i < 0) i = -i - 1;
            if (i < garmin.length && garmin[i] <= t + SAME_START) {
                present++;
                continue;
            }
            if (r.metres < MIN_METRES) {
                small++;
                continue;
            }
            if (r.file.isEmpty() || !Files.exists(export.resolve(r.file))) {
                noFile++;
                continue;
            }
            LocalDateTime local = LocalDateTime.ofInstant(r.start, LOCAL);
            String date = local.toLocalDate().toString();
            Path month = dir.resolve(FOLDER).resolve(date.substring(0, 4)).resolve(date.substring(5, 7));
            String base = date + "_" + r.id + "_" + slug(r.name);
            Path json = month.resolve(base + ".json");
            if (Files.exists(json)) {
                known++;
                continue;
            }
            added++;
            km += r.metres / 1000;
            System.out.printf("%s %s  %-14s %6.1f km  %s%n", dry ? "would add" : "add", local.toString().replace('T', ' ').substring(0, 16),
                    TYPES.getOrDefault(r.type.toLowerCase(Locale.ROOT), "other"), r.metres / 1000, r.name);
            if (dry) continue;
            Files.createDirectories(month);
            String ext = r.file.replaceFirst("\\.gz$", "").replaceFirst("^.*\\.", "").toLowerCase(Locale.ROOT);
            Path track = month.resolve(base + "." + ext);
            try (InputStream in = r.file.endsWith(".gz") ? new GZIPInputStream(Files.newInputStream(export.resolve(r.file)))
                    : Files.newInputStream(export.resolve(r.file))) {
                byte[] bytes = in.readAllBytes();
                // Strava's TCX files start with spaces before "<?xml", which XML readers reject.
                if (!ext.equals("fit")) bytes = new String(bytes, StandardCharsets.UTF_8).stripLeading().getBytes(StandardCharsets.UTF_8);
                Files.write(track, bytes);
            }
            Files.writeString(json, summary(r, local), StandardCharsets.UTF_8);
        }
        int routes = dry ? 0 : addRoutes(dir.resolve(FOLDER));
        if (routes > 0) System.out.printf("Strava: wrote %d GPX routes from TCX/FIT tracks.%n", routes);
        System.out.printf("Strava: %d activities in the export; %d already from Garmin, %d under 1 km skipped, %d without a file, "
                + "%d imported before; %s %d (%.0f km) into %s.%n", rows.size(), present, small, noFile, known,
                dry ? "would add" : "added", added, km, dir.resolve(FOLDER));
        if (!dry && added > 0) System.out.println("Now run DescribeGarminFile --all (with --account if needed) to put them in the reports and pages.");
    }

    /**
     * The heatmap, Explorer, Segments and Together read GPX: for every imported activity that came as TCX or FIT,
     * a GPX with the same track points is written next to it. Returns how many were written.
     */
    static int addRoutes(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return 0;
        int written = 0;
        List<Path> jsons;
        try (Stream<Path> s = Files.walk(folder)) {
            jsons = s.filter(p -> GARMIN_JSON.matcher(p.getFileName().toString()).matches()).toList();
        }
        for (Path json : jsons) {
            String base = json.getFileName().toString().replaceFirst("\\.json$", "");
            Path gpx = json.resolveSibling(base + ".gpx"), tcx = json.resolveSibling(base + ".tcx"), fit = json.resolveSibling(base + ".fit");
            if (Files.exists(gpx)) continue;
            List<String[]> pts = new ArrayList<>(); // lat, lon, ele, ISO time
            try {
                if (Files.exists(tcx)) {
                    Matcher m = Pattern.compile("<Trackpoint>(.*?)</Trackpoint>", Pattern.DOTALL).matcher(Files.readString(tcx));
                    while (m.find()) {
                        String t = tag(m.group(1), "Time"), la = tag(m.group(1), "LatitudeDegrees"), lo = tag(m.group(1), "LongitudeDegrees");
                        if (t != null && la != null && lo != null) pts.add(new String[]{la, lo, tag(m.group(1), "AltitudeMeters"), t});
                    }
                } else if (Files.exists(fit)) {
                    DescribeGarminFile.FitFile f = DescribeGarminFile.FitFile.parse(Files.readAllBytes(fit));
                    for (DescribeGarminFile.FitMessage r : f.messages(20)) {
                        Double la = r.scaled(0), lo = r.scaled(1), ts = r.num(253), ele = r.scaled(2);
                        if (la == null || lo == null || ts == null || Math.abs(la) > 90) continue;
                        pts.add(new String[]{String.format(Locale.ROOT, "%.7f", la), String.format(Locale.ROOT, "%.7f", lo),
                                ele == null ? null : String.format(Locale.ROOT, "%.1f", ele),
                                Instant.ofEpochSecond(ts.longValue() + 631065600L).toString()});
                    }
                }
            } catch (Exception e) {
                System.err.println("Strava: no route from " + json.getFileName() + ": " + e.getMessage());
                continue;
            }
            if (pts.size() < 2) continue;
            StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"StravaImport\" "
                    + "xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>\n");
            for (String[] p : pts) {
                b.append("<trkpt lat=\"").append(p[0]).append("\" lon=\"").append(p[1]).append("\">");
                if (p[2] != null) b.append("<ele>").append(p[2]).append("</ele>");
                b.append("<time>").append(p[3]).append("</time></trkpt>\n");
            }
            Files.writeString(gpx, b.append("</trkseg></trk></gpx>\n"), StandardCharsets.UTF_8);
            written++;
        }
        return written;
    }

    private static String tag(String xml, String name) {
        int i = xml.indexOf("<" + name + ">");
        if (i < 0) return null;
        int j = xml.indexOf("</" + name + ">", i);
        return j < 0 ? null : xml.substring(i + name.length() + 2, j).trim();
    }

    /** Start times (UTC seconds) of every Garmin activity, sorted. */
    private static long[] garminStarts(Path dir) throws IOException {
        List<Long> out = new ArrayList<>();
        Pattern gmt = Pattern.compile("\"startTimeGMT\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})");
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!GARMIN_JSON.matcher(p.getFileName().toString()).matches()) continue;
                if (dir.relativize(p).startsWith(FOLDER) || p.toString().contains("/corrected/")) continue;
                String text = Files.readString(p);
                Matcher m = gmt.matcher(text);
                if (m.find()) out.add(LocalDateTime.parse(m.group(1) + "T" + m.group(2)).toEpochSecond(ZoneOffset.UTC));
            }
        }
        return out.stream().mapToLong(Long::longValue).sorted().toArray();
    }

    private static List<Row> read(Path csv) throws IOException {
        List<List<String>> lines = parseCsv(Files.readString(csv, StandardCharsets.UTF_8));
        List<String> h = lines.getFirst();
        // The header repeats some names (a formatted and a raw column); the raw ones come later, so take the last.
        int id = 0, date = 1, name = 2, type = 3, file = index(h, "Bestandsnaam", "Filename"),
                secs = last(h, "Verstreken tijd", "Elapsed Time"), moving = last(h, "Beweegtijd", "Moving Time"),
                dist = last(h, "Afstand", "Distance"), avgHr = last(h, "Gemiddelde hartslag", "Average Heart Rate"),
                maxHr = last(h, "Max. hartslag", "Max Heart Rate"), kcal = last(h, "Calorieën", "Calories"),
                climb = last(h, "Totale stijging", "Elevation Gain"), maxSpeed = last(h, "Max. snelheid", "Max Speed");
        List<Row> rows = new ArrayList<>();
        for (List<String> l : lines.subList(1, lines.size())) {
            if (l.size() <= Math.max(file, dist)) continue;
            Instant start = parseDate(l.get(date));
            if (start == null) continue;
            rows.add(new Row(l.get(id), start, l.get(name), l.get(type), num(l, secs), num(l, moving), num(l, dist), num(l, avgHr),
                    num(l, maxHr), num(l, kcal), num(l, climb), num(l, maxSpeed), l.get(file)));
        }
        return rows;
    }

    private static int index(List<String> h, String... names) {
        for (String n : names) if (h.contains(n)) return h.indexOf(n);
        return -1;
    }

    private static int last(List<String> h, String... names) {
        for (String n : names) if (h.contains(n)) return h.lastIndexOf(n);
        return -1;
    }

    private static double num(List<String> l, int i) {
        if (i < 0 || i >= l.size() || l.get(i).isBlank()) return 0;
        try {
            return Double.parseDouble(l.get(i).replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static Instant parseDate(String s) {
        Matcher m = DATE.matcher(s.trim());
        if (!m.matches()) return null;
        Integer month = MONTHS.get(m.group(2).toLowerCase(Locale.ROOT).substring(0, Math.min(3, m.group(2).length())));
        if (month == null) return null;
        int hour = Integer.parseInt(m.group(4));
        if ("PM".equals(m.group(7)) && hour < 12) hour += 12;
        if ("AM".equals(m.group(7)) && hour == 12) hour = 0;
        // Strava's export gives the start in UTC.
        return LocalDateTime.of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1)), hour,
                Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6))).toInstant(ZoneOffset.UTC);
    }

    /** RFC 4180: quoted fields may hold commas, quotes ("") and line breaks. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (c == '"') quoted = false;
                else f.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') {
                row.add(f.toString());
                f.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(f.toString());
                f.setLength(0);
                if (!(row.size() == 1 && row.getFirst().isEmpty())) out.add(row);
                row = new ArrayList<>();
            } else f.append(c);
        }
        if (f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            out.add(row);
        }
        return out;
    }

    private static String slug(String name) {
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (s.length() > 60) s = s.substring(0, 60).replaceAll("-$", "");
        return s.isEmpty() ? "strava" : s;
    }

    /** A summary in the shape of Garmin's activity JSON, with only the fields the reports and pages read. */
    private static String summary(Row r, LocalDateTime local) {
        String type = TYPES.getOrDefault(r.type.toLowerCase(Locale.ROOT), "other");
        if (type.equals("running") && r.name.toLowerCase(Locale.ROOT).matches(".*(loopband|treadmill).*")) type = "treadmill_running";
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.0'");
        Map<String, String> s = new TreeMap<>();
        s.put("startTimeLocal", q(f.format(local)));
        s.put("startTimeGMT", q(f.format(LocalDateTime.ofInstant(r.start, ZoneOffset.UTC))));
        s.put("distance", n(r.metres));
        s.put("duration", n(r.seconds));
        if (r.moving > 0) s.put("movingDuration", n(r.moving));
        if (r.avgHr > 0) s.put("averageHR", n(r.avgHr));
        if (r.maxHr > 0) s.put("maxHR", n(r.maxHr));
        if (r.kcal > 0) s.put("calories", n(r.kcal));
        if (r.climb > 0) s.put("elevationGain", n(r.climb));
        if (r.maxSpeed > 0) s.put("maxSpeed", n(r.maxSpeed));
        if (r.seconds > 0) s.put("averageSpeed", n(r.metres / r.seconds));
        StringBuilder b = new StringBuilder("{\n  \"source\": \"strava\",\n  \"stravaId\": ").append(q(r.id))
                .append(",\n  \"stravaUrl\": ").append(q("https://www.strava.com/activities/" + r.id))
                .append(",\n  \"activityId\": ").append(r.id)
                .append(",\n  \"activityName\": ").append(q(r.name))
                .append(",\n  \"activityTypeDTO\": {\"typeKey\": ").append(q(type)).append("},\n  \"summaryDTO\": {");
        boolean first = true;
        for (var e : s.entrySet()) {
            b.append(first ? "\n    " : ",\n    ").append(q(e.getKey())).append(": ").append(e.getValue());
            first = false;
        }
        return b.append("\n  }\n}\n").toString();
    }

    private static String n(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
