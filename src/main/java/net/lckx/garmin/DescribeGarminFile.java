package net.lckx.garmin;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.Console;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;

import static net.lckx.util.JsonHelpers.jsonEscape;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds a readable HTML report of everything known about one day from the files written by
 * {@link DownloadGarminFiles}: every activity (.fit, .tcx, .gpx, .json) plus the daily wellness
 * snapshot. Each section says which file the data came from.
 *
 * <p>Usage: {@code DescribeGarminFile [FROM [UNTIL]] [--since D] [--until D] [--out report.html] [--open]}.
 * Without dates it asks for a from/until date, defaulting to the latest day that has an activity. Every day in
 * the range that has files gets a report next to its data in {@code src/main/resources/garmin/<yyyy>/<mm>/<date>-report.html};
 * the overview {@code src/main/resources/garmin/index.html} is rebuilt once at the end.
 *
 * User: louckxb, Date: 06/10/2026.
 */
public class DescribeGarminFile {

    /**
     * The Garmin account whose data is in {@code src/main/resources/garmin}; any other account gets its own folder next to
     * it ({@code garmin-<name>}), with its own reports and overview. Chosen with {@code --account <name>}.
     */
    static final String MAIN_ACCOUNT = "bart";
    private static final Path RESOURCES = Path.of("src/main/resources");
    private static String account = MAIN_ACCOUNT;
    private static Path DATA_DIR = dataDir(MAIN_ACCOUNT);

    static Path dataDir(String name) {
        return RESOURCES.resolve(name.equals(MAIN_ACCOUNT) ? "garmin" : "garmin-" + name);
    }

    /** Switches every path (reports, overview, corrections) to another account's folder. */
    static void useAccount(String name) {
        name = name.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Account name may only use a-z, 0-9, - and _: " + name);
        account = name;
        DATA_DIR = dataDir(name);
    }

    static String account() {
        return account;
    }

    /** "Bart", "Peter": for page titles. */
    private static String accountLabel(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** localStorage key for the overview filter, per account (both overviews are served from the same origin). */
    private static String filterKey() {
        return "garmin-overview-filter" + (account.equals(MAIN_ACCOUNT) ? "" : "-" + account);
    }
    private static final String OVERVIEW_FILE = "index.html";
    private static final String SUMMARY_START = "<script type=\"application/json\" id=\"garmin-summary\">";
    private static final Pattern ACTIVITY_FILE =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})_(\\d+)_(.*)\\.(fit|tcx|gpx|json)");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        LocalDate since = null, until = null;
        Path out = null;
        boolean open = false, all = false;
        List<LocalDate> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--since", "--from" -> since = LocalDate.parse(args[++i]);
                case "--until", "--to" -> until = LocalDate.parse(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--open" -> open = true;
                case "--all" -> all = true;
                case "--account" -> useAccount(args[++i]);
                case "-h", "--help" -> {
                    printUsage();
                    return;
                }
                default -> positional.add(LocalDate.parse(args[i]));
            }
        }
        if (positional.size() > 2) {
            printUsage();
            System.exit(1);
        }
        if (since == null && !positional.isEmpty()) since = positional.getFirst();
        if (until == null && positional.size() == 2) until = positional.get(1);

        Optional<LocalDate> latestData = latestDate();
        if (latestData.isEmpty()) {
            System.err.println("No Garmin files found in " + DATA_DIR.toAbsolutePath()
                    + "\nRun DownloadGarminFiles first; it writes the day reports and the overview when it finishes.");
            System.exit(1);
        }
        LocalDate latest = latestData.get();
        if (all) {
            // Every day with data, e.g. after a change to how reports look.
            since = earliestDate().orElse(latest);
            until = latest;
        }
        if (since == null) {
            since = promptForDate("From date (YYYY-MM-DD)", latest);
            until = promptForDate("Until date (YYYY-MM-DD)", since);
        } else if (until == null) {
            // One date on the command line means one day; --since alone runs up to the latest data.
            until = positional.isEmpty() ? latest : since;
        }
        if (until.isBefore(since)) {
            LocalDate t = since;
            since = until;
            until = t;
        }
        boolean single = since.equals(until);
        if (out != null && !single) {
            System.err.println("--out can only be used for a single day.");
            System.exit(1);
        }

        Path lastReport = null;
        if (single) {
            lastReport = writeReport(since, out);
            if (lastReport == null) {
                System.err.println("No Garmin files found for " + since);
                System.exit(1);
            }
        } else if (writeReports(since, until) == 0) {
            System.exit(1);
        }
        Path overview = Overview.write();
        System.out.println("Overview updated: " + overview.toAbsolutePath());
        if (open) {
            Path target = single ? lastReport : overview;
            new ProcessBuilder("open", target.toString()).inheritIO().start().waitFor();
        }
    }

    private static void printUsage() {
        System.err.println("""
                Usage: DescribeGarminFile                              ask for a from/until date (default: latest day)
                       DescribeGarminFile YYYY-MM-DD [--out f.html]    one day
                       DescribeGarminFile YYYY-MM-DD YYYY-MM-DD        every day in the range
                       DescribeGarminFile --since YYYY-MM-DD [--until YYYY-MM-DD]
                       DescribeGarminFile --all                        every day with data (after a change to the reports)
                Options: --open  open the report (one day) or the overview (range) when done
                         --account NAME  another Garmin account's data (src/main/resources/garmin-NAME); default %s""".formatted(MAIN_ACCOUNT));
    }

    /**
     * Writes the reports for every day in the range that has data (also used by {@link DownloadGarminFiles}).
     * Does not rebuild the overview; call {@link Overview#write()} afterwards. Returns the number written.
     */
    static int writeReports(LocalDate since, LocalDate until) throws IOException {
        int written = 0, empty = 0;
        for (LocalDate date = since; !date.isAfter(until); date = date.plusDays(1)) {
            if (writeReport(date, null) == null) empty++;
            else written++;
        }
        System.out.printf("Reports: %d written, %d days without data skipped (%s → %s).%n", written, empty, since, until);
        return written;
    }

    /** A day report is stored next to the downloaded files, e.g. garmin/2026/02/2026-02-01-report.html. */
    static Path reportPath(LocalDate date) {
        return DATA_DIR
                .resolve(String.valueOf(date.getYear()))
                .resolve(String.format("%02d", date.getMonthValue()))
                .resolve(date + "-report.html");
    }

    /** Writes the report for one day; returns its path, or null when there are no files for that day. */
    static Path writeReport(LocalDate date, Path out) throws IOException {
        Day day = loadDay(date);
        Path defaultOut = reportPath(date);
        if (day.activities.isEmpty() && day.wellness == null) {
            // A report generated earlier (e.g. from an empty wellness snapshot) no longer has anything to show.
            if (out == null && Files.deleteIfExists(defaultOut)) System.out.println("Removed report without data: " + defaultOut);
            return null;
        }
        if (out == null) out = defaultOut;
        writeHtml(day, out);
        System.out.printf("Report for %s (%d activities, wellness=%s) written to %s%n",
                date, day.activities.size(), day.wellness != null ? "yes" : "no", out.toAbsolutePath());
        return out;
    }

    private static void writeHtml(Day day, Path out) throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        // The "All reports" link is relative, so it is computed for each location the page is written to.
        String overviewHref = out.toAbsolutePath().getParent()
                .relativize(DATA_DIR.toAbsolutePath().resolve(OVERVIEW_FILE)).toString();
        Files.writeString(out, new Report(day, overviewHref).render(), StandardCharsets.UTF_8);
    }

    /** Shared so consecutive prompts don't lose input buffered by an earlier Scanner. */
    private static final Scanner STDIN = new Scanner(System.in);

    private static LocalDate promptForDate(String label, LocalDate defaultValue) {
        Console console = System.console();
        Scanner scanner = STDIN;
        String prompt = label + " [" + defaultValue + "]: ";
        while (true) {
            String input;
            if (console != null) {
                input = console.readLine(prompt);
            } else {
                System.out.print(prompt);
                input = scanner.hasNextLine() ? scanner.nextLine() : null;
            }
            if (input == null) {
                System.err.println("No input; aborting.");
                System.exit(1);
            }
            input = input.trim();
            if (input.isEmpty()) return defaultValue;
            try {
                return LocalDate.parse(input);
            } catch (Exception e) {
                System.err.println("Not a valid YYYY-MM-DD date. Try again.");
            }
        }
    }

    // ------------------------------------------------------------------ locating files

    private static Optional<LocalDate> latestDate() throws IOException {
        return dataDate(true);
    }

    private static Optional<LocalDate> earliestDate() throws IOException {
        return dataDate(false);
    }

    /** The latest (or earliest) day with an activity, else with a wellness file. */
    private static Optional<LocalDate> dataDate(boolean latest) throws IOException {
        if (!Files.isDirectory(DATA_DIR)) return Optional.empty();
        LocalDate latestActivity = null, latestWellness = null;
        try (Stream<Path> s = Files.walk(DATA_DIR)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (p.getParent() != null && p.getParent().getFileName().toString().equals(SwimCorrection.DIR)) continue;
                var m = ACTIVITY_FILE.matcher(n);
                if (m.matches()) {
                    LocalDate d = LocalDate.parse(m.group(1));
                    if (latestActivity == null || (latest ? d.isAfter(latestActivity) : d.isBefore(latestActivity))) latestActivity = d;
                } else if (p.getParent() != null && p.getParent().getFileName().toString().equals("wellness")
                        && n.matches("\\d{4}-\\d{2}-\\d{2}\\.json")) {
                    LocalDate d = LocalDate.parse(n.substring(0, 10));
                    if (latestWellness == null || (latest ? d.isAfter(latestWellness) : d.isBefore(latestWellness))) latestWellness = d;
                }
            }
        }
        return Optional.ofNullable(latestActivity != null ? latestActivity : latestWellness);
    }

    record ActivityFiles(String base, long id, String name, Path fit, Path tcx, Path gpx, Path json) {}

    static final class Day {
        LocalDate date;
        List<Activity> activities = new ArrayList<>();
        Path wellnessFile;
        Map<String, Object> wellness;
    }

    private static Day loadDay(LocalDate date) throws IOException {
        Day day = new Day();
        day.date = date;
        Path monthDir = DATA_DIR.resolve(String.valueOf(date.getYear()))
                .resolve(String.format("%02d", date.getMonthValue()));
        Map<String, Path[]> byBase = new TreeMap<>();
        if (Files.isDirectory(monthDir)) {
            try (Stream<Path> s = Files.list(monthDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    var m = ACTIVITY_FILE.matcher(p.getFileName().toString());
                    if (!m.matches() || !m.group(1).equals(date.toString())) continue;
                    String base = p.getFileName().toString().replaceFirst("\\.(fit|tcx|gpx|json)$", "");
                    Path[] slots = byBase.computeIfAbsent(base, k -> new Path[4]);
                    switch (m.group(4)) {
                        case "fit" -> slots[0] = p;
                        case "tcx" -> slots[1] = p;
                        case "gpx" -> slots[2] = p;
                        default -> slots[3] = p;
                    }
                }
            }
        }
        for (var e : byBase.entrySet()) {
            var m = ACTIVITY_FILE.matcher(e.getKey() + ".json");
            m.matches();
            Path[] s = e.getValue();
            day.activities.add(Activity.load(new ActivityFiles(e.getKey(), Long.parseLong(m.group(2)),
                    m.group(3).replace('-', ' '), s[0], s[1], s[2], s[3])));
        }
        day.activities.sort(Comparator.comparing(a -> a.start == null ? Instant.MAX : a.start));
        Path w = monthDir.resolve("wellness").resolve(date + ".json");
        if (Files.exists(w)) {
            try {
                String text = Files.readString(w);
                // Snapshots for days without any recording (e.g. before the first watch) are ignored.
                if (!DownloadGarminFiles.hasWellnessData(text)) return day;
                day.wellnessFile = w;
                day.wellness = asMap(Json.parse(text));
            } catch (RuntimeException ex) {
                System.err.println("Could not parse " + w + ": " + ex.getMessage());
            }
        }
        return day;
    }

    // ------------------------------------------------------------------ activity model

    static final class Activity {
        ActivityFiles files;
        Map<String, Object> json;
        FitFile fit;
        Xml tcx;
        Xml gpx;
        List<String> errors = new ArrayList<>();
        Instant start;
        ZoneId zone = ZoneId.systemDefault();
        String sport = "";
        /** Corrected pool-swim distance (its own file next to the data), or null. */
        SwimCorrection.Result correction;

        static Activity load(ActivityFiles f) {
            Activity a = new Activity();
            a.files = f;
            if (f.json != null) {
                try {
                    a.json = asMap(Json.parse(Files.readString(f.json)));
                } catch (Exception e) {
                    a.errors.add(f.json.getFileName() + ": " + e.getMessage());
                }
            }
            if (f.fit != null) {
                try {
                    a.fit = FitFile.parse(Files.readAllBytes(f.fit));
                } catch (Exception e) {
                    a.errors.add(f.fit.getFileName() + ": " + e.getMessage());
                }
            }
            if (f.tcx != null) {
                try {
                    a.tcx = Xml.load(f.tcx);
                } catch (Exception e) {
                    a.errors.add(f.tcx.getFileName() + ": " + e.getMessage());
                }
            }
            if (f.gpx != null) {
                try {
                    a.gpx = Xml.load(f.gpx);
                } catch (Exception e) {
                    a.errors.add(f.gpx.getFileName() + ": " + e.getMessage());
                }
            }
            String tz = str(at(a.json, "timeZoneUnitDTO", "timeZone"));
            if (!tz.isEmpty()) {
                try {
                    a.zone = ZoneId.of(tz);
                } catch (Exception ignored) {
                }
            }
            String gmt = str(at(a.json, "summaryDTO", "startTimeGMT"));
            if (!gmt.isEmpty()) a.start = parseGarminTime(gmt).toInstant(ZoneOffset.UTC);
            if (a.start == null && a.fit != null) {
                FitMessage s = a.fit.first(18);
                if (s != null && s.num(2) != null) a.start = fitInstant(s.num(2));
            }
            a.sport = str(at(a.json, "activityTypeDTO", "typeKey"));
            if (a.sport.isEmpty() && a.fit != null && a.fit.first(18) != null) {
                a.sport = Objects2.toStr(a.fit.first(18).display(5));
            }
            a.correction = SwimCorrection.update(a).orElse(null);
            return a;
        }

        String title() {
            String n = str(at(json, "activityName"));
            return n.isEmpty() ? files.name : n;
        }
    }

    /** Tiny helpers so the code above stays readable. */
    static final class Objects2 {
        static String toStr(Object o) {
            return o == null ? "" : o.toString();
        }
    }

    // ------------------------------------------------------------------ report

    static final class Report {
        final Day day;
        final String overviewHref;
        final StringBuilder h = new StringBuilder();
        int chartSeq = 0;

        Report(Day day, String overviewHref) {
            this.day = day;
            this.overviewHref = overviewHref;
        }

        /** Machine-readable digest of the day, embedded in the page so the overview can be rebuilt from the reports. */
        String summaryJson() {
            StringBuilder j = new StringBuilder("{\"date\":\"").append(day.date).append("\",\"activities\":[");
            for (int i = 0; i < day.activities.size(); i++) {
                Activity a = day.activities.get(i);
                Map<String, Object> s = asMap(at(a.json, "summaryDTO"));
                if (i > 0) j.append(',');
                j.append("{\"name\":\"").append(jsonEscape(a.title())).append("\",\"sport\":\"").append(jsonEscape(a.sport))
                        .append("\",\"start\":\"").append(a.start == null ? "" : HM.format(a.start.atZone(a.zone))).append('"');
                jsonNum(j, "distance", at(s, "distance"));
                if (a.correction != null && a.correction.differs()) jsonNum(j, "correctedDistance", a.correction.distance());
                jsonNum(j, "duration", at(s, "duration"));
                jsonNum(j, "calories", at(s, "calories"));
                jsonNum(j, "averageHR", at(s, "averageHR"));
                jsonNum(j, "trainingEffect", at(s, "trainingEffect"));
                j.append('}');
            }
            j.append(']');
            Map<String, Object> ds = asMap(at(day.wellness, "dailySummary"));
            jsonNum(j, "steps", at(ds, "totalSteps"));
            jsonNum(j, "totalKcal", at(ds, "totalKilocalories"));
            jsonNum(j, "restingHR", at(ds, "restingHeartRate"));
            jsonNum(j, "avgStress", at(ds, "averageStressLevel"));
            jsonNum(j, "bodyBatteryHigh", at(ds, "bodyBatteryHighestValue"));
            jsonNum(j, "sleepScore", at(day.wellness, "sleep", "dailySleepDTO", "sleepScores", "overall", "value"));
            jsonNum(j, "sleepSeconds", at(day.wellness, "sleep", "dailySleepDTO", "sleepTimeSeconds"));
            jsonNum(j, "hrv", at(day.wellness, "hrv", "hrvSummary", "lastNightAvg"));
            return j.append('}').toString().replace("</", "<\\/");
        }

        private static void jsonNum(StringBuilder j, String key, Object v) {
            Double d = num(v);
            if (d != null && !d.isNaN()) j.append(",\"").append(key).append("\":").append(trimNum(d));
        }

        String render() {
            boolean anyMap = day.activities.stream().anyMatch(a -> !track(a).isEmpty());
            h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                    .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                    .append("<title>Garmin ").append(day.date).append("</title>");
            if (anyMap) {
                h.append("<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\">")
                        .append("<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>");
            }
            h.append("<style>").append(CSS).append("</style>").append(SUMMARY_START).append(summaryJson())
                    .append("</script></head><body><main>");
            h.append("<a class=\"back\" href=\"").append(esc(overviewHref.replace('\\', '/'))).append("\" data-day=\"").append(day.date).append("\" onclick=\"return toOverview(this)\">← All reports</a>");
            header();
            sources();
            nav();
            for (int i = 0; i < day.activities.size(); i++) activity(day.activities.get(i), i);
            if (day.wellness != null) wellness();
            h.append("<footer>Generated ").append(DT.format(LocalDateTime.now()))
                    .append(" by DescribeGarminFile from files in <code>").append(esc(DATA_DIR.toString()))
                    .append("</code>. Hover a chart for exact values; hover a tile to see the exact field.</footer>");
            String base = overviewHref.replace('\\', '/');
            base = base.contains("/") ? base.substring(0, base.lastIndexOf('/') + 1) : "";
            h.append("</main><div id=\"tip\"></div><script src=\"").append(esc(base)).append(DAYS_FILE).append("\"></script>")
                    .append("<script>var FILTER_KEY=").append(jsStr(filterKey())).append(",DAY_BASE=").append(jsStr(base)).append(",THIS_DAY=\"").append(day.date).append("\";")
                    .append(DAY_STEP_JS).append(JS).append("</script></body></html>");
            return h.toString();
        }

        // ---- header + overview

        void header() {
            String weekday = day.date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            h.append("<header><div class=\"eyebrow\">Garmin day report · ").append(esc(accountLabel(account))).append("</div><h1>")
                    .append(weekday).append(" ").append(day.date.getDayOfMonth()).append(" ")
                    .append(day.date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append(" ")
                    .append(day.date.getYear()).append("</h1>");
            String device = deviceName();
            if (!device.isEmpty()) h.append("<p class=\"sub\">Recorded with ").append(esc(device)).append("</p>");
            h.append("</header>");

            double dist = 0, time = 0, cal = 0;
            for (Activity a : day.activities) {
                dist += numOr(at(a.json, "summaryDTO", "distance"), 0);
                time += numOr(at(a.json, "summaryDTO", "duration"), 0);
                cal += numOr(at(a.json, "summaryDTO", "calories"), 0);
            }
            final double dist0 = dist, time0 = time, cal0 = cal;
            tiles(() -> {
                tile(String.valueOf(day.activities.size()), "Activities", "count of activity files for this date", null);
                if (dist0 > 0) tile(fmtDist(dist0), "Activity distance", "sum of summaryDTO.distance", "JSON");
                if (time0 > 0) tile(fmtDur(time0), "Activity time", "sum of summaryDTO.duration", "JSON");
                if (cal0 > 0) tile(fmtNum(cal0, 0) + " kcal", "Activity calories", "sum of summaryDTO.calories", "JSON");
                Map<String, Object> ds = asMap(at(day.wellness, "dailySummary"));
                if (ds != null) {
                    Double steps = num(ds.get("totalSteps"));
                    if (steps != null) tile(fmtNum(steps, 0), "Steps", "dailySummary.totalSteps", "Wellness");
                    Double kcal = num(ds.get("totalKilocalories"));
                    if (kcal != null) tile(fmtNum(kcal, 0) + " kcal", "Total burned", "dailySummary.totalKilocalories", "Wellness");
                    Double rhr = num(ds.get("restingHeartRate"));
                    if (rhr != null) tile(fmtNum(rhr, 0) + " bpm", "Resting HR", "dailySummary.restingHeartRate", "Wellness");
                    Double sleepScore = num(at(day.wellness, "sleep", "dailySleepDTO", "sleepScores", "overall", "value"));
                    if (sleepScore != null) tile(fmtNum(sleepScore, 0), "Sleep score", "sleep.dailySleepDTO.sleepScores.overall.value", "Wellness");
                }
            });
        }

        String deviceName() {
            for (Activity a : day.activities) {
                if (a.tcx != null) {
                    Element c = a.tcx.first("Creator");
                    if (c != null) {
                        String n = Xml.text(c, "Name");
                        if (!n.isEmpty()) return n;
                    }
                }
            }
            List<Object> devices = asList(at(day.wellness, "trainingStatus", "recordedDevices"));
            if (devices != null && !devices.isEmpty()) return str(at(devices.getFirst(), "deviceName"));
            return "";
        }

        void sources() {
            h.append("<section class=\"card\"><h2>Source files</h2><p class=\"muted\">Every value in this report is tagged with the file it came from: ")
                    .append(chip("FIT")).append(" binary recording from the watch, ")
                    .append(chip("TCX")).append(" Training Center XML export, ")
                    .append(chip("GPX")).append(" GPS track export, ")
                    .append(chip("JSON")).append(" Garmin Connect activity summary, ")
                    .append(chip("Wellness")).append(" Garmin Connect daily health snapshot.</p>")
                    .append("<div class=\"scroll\"><table><thead><tr><th>File</th><th>Type</th><th>Size</th><th>Contains</th></tr></thead><tbody>");
            for (Activity a : day.activities) {
                fileRow(a.files.fit, "FIT", a.fit == null ? "could not be read" : a.fit.summary());
                fileRow(a.files.tcx, "TCX", a.tcx == null ? "could not be read" : tcxSummary(a.tcx));
                fileRow(a.files.gpx, "GPX", a.gpx == null ? "could not be read" : gpxSummary(a.gpx));
                fileRow(a.files.json, "JSON", a.json == null ? "could not be read" : "activity summary: " + a.json.size() + " top-level sections, "
                        + Optional.ofNullable(asMap(a.json.get("summaryDTO"))).map(Map::size).orElse(0) + " summary fields");
            }
            if (day.wellnessFile != null) {
                fileRow(day.wellnessFile, "Wellness", day.wellness == null ? "could not be read"
                        : "daily snapshot sections: " + String.join(", ", day.wellness.keySet().stream()
                        .filter(k -> day.wellness.get(k) != null && !(day.wellness.get(k) instanceof List<?> l && l.isEmpty()))
                        .filter(k -> !k.equals("schemaVersion") && !k.equals("date")).toList()));
            }
            h.append("</tbody></table></div>");
            for (Activity a : day.activities) {
                for (String e : a.errors) h.append("<p class=\"warn\">⚠ ").append(esc(e)).append("</p>");
            }
            h.append("</section>");
        }

        void fileRow(Path p, String type, String what) {
            if (p == null) return;
            long size = 0;
            try {
                size = Files.size(p);
            } catch (IOException ignored) {
            }
            h.append("<tr><td><code>").append(esc(p.getFileName().toString())).append("</code></td><td>")
                    .append(chip(type)).append("</td><td class=\"num\">").append(fmtBytes(size))
                    .append("</td><td>").append(esc(what)).append("</td></tr>");
        }

        void nav() {
            // Sticky bar: back to the overview, plus jumps within the page when there is more than one section.
            h.append("<nav><a class=\"home\" href=\"").append(esc(overviewHref.replace('\\', '/'))).append("\" data-day=\"").append(day.date).append("\" onclick=\"return toOverview(this)\">← Overview</a>");
            // Filled in from days.js (written with the overview), so they stay right when later days are added.
            h.append("<a class=\"day-step\" id=\"prevDay\" hidden title=\"Previous day with a report (←)\"></a>")
                    .append("<a class=\"day-step\" id=\"nextDay\" hidden title=\"Next day with a report (→)\"></a>");
            if (day.activities.size() + (day.wellness != null ? 1 : 0) < 2) {
                h.append("</nav>");
                return;
            }
            for (int i = 0; i < day.activities.size(); i++) {
                Activity a = day.activities.get(i);
                h.append("<a href=\"#act").append(i).append("\">").append(sportIcon(a.sport)).append(" ")
                        .append(esc(a.title())).append(a.start != null ? " · " + HM.format(a.start.atZone(a.zone)) : "")
                        .append("</a>");
            }
            if (day.wellness != null) h.append("<a href=\"#wellness\">❤ Health &amp; wellness</a>");
            h.append("</nav>");
        }

        // ---- activity

        void activity(Activity a, int idx) {
            Map<String, Object> s = asMap(at(a.json, "summaryDTO"));
            String jsonName = a.files.json == null ? "" : a.files.json.getFileName().toString();
            h.append("<section class=\"card activity\" id=\"act").append(idx).append("\"><h2>")
                    .append(sportIcon(a.sport)).append(" ").append(esc(a.title())).append("</h2><p class=\"sub\">");
            if (a.start != null) {
                ZonedDateTime st = a.start.atZone(a.zone);
                h.append("Started ").append(HM.format(st));
                Double el = num(at(s, "elapsedDuration"));
                if (el != null) h.append(" – ended ").append(HM.format(st.plusSeconds(el.longValue())));
                h.append(" (").append(esc(a.zone.getId())).append(") · ");
            }
            h.append(esc(humanize(a.sport))).append(" · activity id ").append(a.files.id).append("</p>");

            if (s != null) activityTiles(a, s, jsonName);
            correctionNote(a);
            fitSessionTiles(a);
            mapBlock(a, idx);
            activityCharts(a);
            hrZones(a);
            laps(a);
            swimLengths(a);
            devices(a);
            events(a);

            h.append("<h3>Everything in the files</h3>");
            if (a.json != null) {
                details("Garmin Connect summary — all fields", "JSON", jsonName, () -> tree(a.json, 0));
            }
            if (a.fit != null) {
                details("FIT file — all messages", "FIT", a.files.fit.getFileName().toString(), () -> fitAll(a));
            }
            if (a.tcx != null) {
                details("TCX file — laps, creator and track content", "TCX", a.files.tcx.getFileName().toString(), () -> tcxDetails(a.tcx));
            }
            if (a.gpx != null) {
                details("GPX file — track content", "GPX", a.files.gpx.getFileName().toString(), () -> gpxDetails(a.gpx));
            }
            h.append("</section>");
        }

        void correctionNote(Activity a) {
            SwimCorrection.Result c = a.correction;
            if (c == null) return;
            long recounted = c.laps().stream().filter(SwimCorrection.Lap::recounted).count();
            h.append("<p class=\"note\"><b>Corrected distance: ").append(fmtDist(c.distance())).append("</b> (Garmin: ")
                    .append(fmtDist(c.garminDistance())).append(") · ");
            if (c.manualDistance() != null) h.append("set by hand in <code>overrides.distance</code>");
            else if (c.referenceSource().startsWith("none"))
                h.append("the length times are too irregular, and too few look like real lengths, to correct reliably, so Garmin's count is kept")
                        .append(" — set <code>overrides.distance</code> if you know the real distance");
            else if (recounted == 0) h.append("every lap's strokes per length look normal, so the watch's length count stands");
            else h.append(recounted).append(" of ").append(c.laps().size()).append(" laps recounted at ")
                    .append(fmtNum(c.strokesPerLength(), 1)).append(" strokes per length")
                    .append(c.customReference() ? " (from <code>overrides.strokesPerLength</code>)" : "")
                    .append(c.customReference() ? ""
                            : c.referenceSwims().isEmpty() ? " (this swim's normal lengths)"
                            : c.referenceSource().startsWith("this swim")
                            ? " (this swim's length times vary too much to be its own reference; its lengths taking about as long as in the consistent swims of "
                              + String.join(", ", c.referenceSwims().stream().map(LocalDate::toString).toList()) + " give the strokes per length)"
                            : " (this swim's length times vary too much to be its own reference, so taken from the consistent swims of "
                              + String.join(", ", c.referenceSwims().stream().map(LocalDate::toString).toList()) + ")")
                    .append(", because their strokes per length were more than ").append(fmtNum(SwimCorrection.TOLERANCE * 100, 0))
                    .append("% off and their length times show missed or phantom turns");
            h.append(". From <code>").append(esc(DATA_DIR.relativize(c.file()).toString())).append("</code>; the Garmin files are unchanged.</p>");
        }

        void activityTiles(Activity a, Map<String, Object> s, String file) {
            boolean swim = a.sport.contains("swim");
            boolean pace = swim || a.sport.contains("run") || a.sport.contains("walk") || a.sport.contains("hik");
            h.append(srcLine("JSON", file, "summaryDTO"));
            tiles(() -> {
                jt(s, "distance", "Distance", v -> fmtDist(v));
                jt(s, "duration", "Duration", v -> fmtDur(v));
                jt(s, "movingDuration", "Moving time", v -> fmtDur(v));
                jt(s, "elapsedDuration", "Elapsed time", v -> fmtDur(v));
                if (pace) jt(s, "averageSpeed", "Avg pace", v -> fmtPace(v, swim));
                jt(s, "averageSpeed", "Avg speed", v -> fmtNum(v * 3.6, 1) + " km/h");
                jt(s, "averageMovingSpeed", "Avg moving speed", v -> fmtNum(v * 3.6, 1) + " km/h");
                jt(s, "maxSpeed", "Max speed", v -> fmtNum(v * 3.6, 1) + " km/h");
                jt(s, "averageHR", "Avg heart rate", v -> fmtNum(v, 0) + " bpm");
                jt(s, "maxHR", "Max heart rate", v -> fmtNum(v, 0) + " bpm");
                jt(s, "minHR", "Min heart rate", v -> fmtNum(v, 0) + " bpm");
                jt(s, "calories", "Calories", v -> fmtNum(v, 0) + " kcal");
                jt(s, "elevationGain", "Elevation gain", v -> fmtNum(v, 0) + " m");
                jt(s, "elevationLoss", "Elevation loss", v -> fmtNum(v, 0) + " m");
                jt(s, "maxElevation", "Max elevation", v -> fmtNum(v, 0) + " m");
                jt(s, "minElevation", "Min elevation", v -> fmtNum(v, 0) + " m");
                jt(s, "averageRunCadence", "Avg cadence", v -> fmtNum(v, 0) + " spm");
                jt(s, "averageBikeCadence", "Avg cadence", v -> fmtNum(v, 0) + " rpm");
                jt(s, "averageSwimCadence", "Avg stroke rate", v -> fmtNum(v, 0) + " /min");
                jt(s, "averagePower", "Avg power", v -> fmtNum(v, 0) + " W");
                jt(s, "normalizedPower", "Normalized power", v -> fmtNum(v, 0) + " W");
                jt(s, "trainingEffect", "Aerobic TE <small>" + esc(humanize(str(s.get("aerobicTrainingEffectMessage")))) + "</small>", v -> fmtNum(v, 1));
                jt(s, "anaerobicTrainingEffect", "Anaerobic TE <small>" + esc(humanize(str(s.get("anaerobicTrainingEffectMessage")))) + "</small>", v -> fmtNum(v, 1));
                jt(s, "activityTrainingLoad", "Training load", v -> fmtNum(v, 0));
                jt(s, "moderateIntensityMinutes", "Moderate int. min", v -> fmtNum(v, 0));
                jt(s, "vigorousIntensityMinutes", "Vigorous int. min", v -> fmtNum(v, 0));
                jt(s, "poolLength", "Pool length", v -> fmtNum(v, 0) + " m");
                jt(s, "numberOfActiveLengths", "Active lengths", v -> fmtNum(v, 0));
                jt(s, "totalNumberOfStrokes", "Strokes", v -> fmtNum(v, 0));
                jt(s, "averageSWOLF", "Avg SWOLF", v -> fmtNum(v, 0));
                jt(s, "averageStrokes", "Avg strokes/length", v -> fmtNum(v, 0));
                jt(s, "steps", "Steps", v -> fmtNum(v, 0));
                jt(s, "averageTemperature", "Avg temperature", v -> fmtNum(v, 0) + " °C");
                jt(s, "waterEstimated", "Sweat loss (est.)", v -> fmtNum(v, 0) + " ml");
                jt(s, "differenceBodyBattery", "Body battery", v -> (v > 0 ? "+" : "") + fmtNum(v, 0));
                String label = str(s.get("trainingEffectLabel"));
                if (!label.isEmpty()) tile(humanize(label), "Primary benefit", "summaryDTO.trainingEffectLabel", "JSON");
            });
        }

        void jt(Map<String, Object> s, String key, String label, java.util.function.DoubleFunction<String> f) {
            Double v = num(s.get(key));
            if (v == null) return;
            tile(f.apply(v), label, "summaryDTO." + key + " = " + s.get(key), "JSON");
        }

        void fitSessionTiles(Activity a) {
            if (a.fit == null) return;
            FitMessage s = a.fit.first(18);
            if (s == null) return;
            List<String[]> extra = new ArrayList<>();
            // Values the FIT session holds that the Connect summary usually does not expose.
            int[] interesting = {110, 168, 33, 47, 44, 43, 57, 58, 150, 64, 132, 134, 89, 90, 91, 41, 34, 35, 36, 48, 192, 193};
            for (int f : interesting) {
                if (!s.has(f)) continue;
                FitProfile.F pf = FitProfile.field(18, f);
                if (pf == null) continue;
                extra.add(new String[]{s.display(f), humanize(pf.name()), "FIT session." + pf.name()});
            }
            if (extra.isEmpty()) return;
            h.append(srcLine("FIT", a.files.fit.getFileName().toString(), "session message (extra values not in the JSON summary)"));
            tiles(() -> {
                for (String[] e : extra) tile(e[0], e[1], e[2], "FIT");
            });
        }

        void mapBlock(Activity a, int idx) {
            List<double[]> pts = track(a);
            if (pts.isEmpty()) return;
            String src = a.fit != null && !fitTrack(a.fit).isEmpty() ? "FIT" : "GPX";
            Path f = src.equals("FIT") ? a.files.fit : a.files.gpx;
            h.append(srcLine(src, f.getFileName().toString(), src.equals("FIT") ? "record.position_lat / position_long" : "trkpt lat/lon"));
            StringBuilder js = new StringBuilder("[");
            int step = Math.max(1, pts.size() / 2500);
            for (int i = 0; i < pts.size(); i += step) {
                if (js.length() > 1) js.append(',');
                js.append('[').append(fmt6(pts.get(i)[0])).append(',').append(fmt6(pts.get(i)[1])).append(']');
            }
            js.append(']');
            h.append("<div class=\"map\" id=\"map").append(idx).append("\" data-track='").append(js).append("'></div>");
        }

        void activityCharts(Activity a) {
            Series hr = new Series(), speed = new Series(), alt = new Series(), cad = new Series(), pow = new Series(),
                    temp = new Series(), resp = new Series();
            String src;
            String file;
            boolean swim = a.sport.contains("swim");
            boolean run = a.sport.contains("run") || a.sport.contains("walk") || a.sport.contains("hik");
            if (a.fit != null && a.fit.count(20) > 0) {
                src = "FIT";
                file = a.files.fit.getFileName().toString();
                for (FitMessage r : a.fit.messages(20)) {
                    Double ts = r.num(253);
                    if (ts == null) continue;
                    long ms = fitInstant(ts).toEpochMilli();
                    hr.add(ms, r.scaled(3));
                    Double sp = r.has(73) ? r.scaled(73) : r.scaled(6);
                    if (sp != null) speed.add(ms, run ? (sp > 0.4 ? 1000 / 60.0 / sp : Double.NaN) : sp * 3.6);
                    alt.add(ms, r.has(78) ? r.scaled(78) : r.scaled(2));
                    Double c = r.scaled(4);
                    if (c != null && r.has(53)) c += r.scaled(53);
                    if (c != null && run) c *= 2;
                    cad.add(ms, c);
                    pow.add(ms, r.scaled(7));
                    temp.add(ms, r.scaled(13));
                    resp.add(ms, r.scaled(108));
                }
            } else if (a.tcx != null) {
                src = "TCX";
                file = a.files.tcx.getFileName().toString();
                for (Element tp : a.tcx.all("Trackpoint")) {
                    String t = Xml.text(tp, "Time");
                    if (t.isEmpty()) continue;
                    long ms = Instant.parse(t).toEpochMilli();
                    Element hrEl = Xml.child(tp, "HeartRateBpm");
                    hr.add(ms, hrEl == null ? null : parseD(Xml.text(hrEl, "Value")));
                    alt.add(ms, parseD(Xml.text(tp, "AltitudeMeters")));
                    Double sp = parseD(Xml.text(tp, "Speed"));
                    if (sp != null) speed.add(ms, run ? (sp > 0.4 ? 1000 / 60.0 / sp : Double.NaN) : sp * 3.6);
                    cad.add(ms, parseD(Xml.text(tp, "Cadence")));
                    pow.add(ms, parseD(Xml.text(tp, "Watts")));
                }
            } else {
                return;
            }
            if (hr.isEmpty() && speed.isEmpty() && alt.isEmpty()) return;
            h.append("<h3>Over time</h3>").append(srcLine(src, file, src.equals("FIT") ? "record messages (one per second)" : "Trackpoints"));
            h.append("<div class=\"charts\">");
            if (!hr.isEmpty()) h.append(lineChart("Heart rate", "bpm", hr, "var(--c-hr)", a.zone, false, 0));
            if (!speed.isEmpty() && !swim) {
                h.append(run ? lineChart("Pace", "min/km", speed.clip(2, 20), "var(--c-speed)", a.zone, true, 2)
                        : lineChart("Speed", "km/h", speed, "var(--c-speed)", a.zone, false, 1));
            }
            if (!alt.isEmpty() && alt.range() > 2) h.append(lineChart("Elevation", "m", alt, "var(--c-alt)", a.zone, false, 0));
            if (!cad.isEmpty() && cad.max() > 0) h.append(lineChart("Cadence", run ? "spm" : swim ? "strokes/min" : "rpm", cad, "var(--c-cad)", a.zone, false, 0));
            if (!pow.isEmpty() && pow.max() > 0) h.append(lineChart("Power", "W", pow, "var(--c-pow)", a.zone, false, 0));
            if (!resp.isEmpty()) h.append(lineChart("Respiration", "brpm", resp, "var(--c-resp)", a.zone, false, 1));
            if (!temp.isEmpty()) h.append(lineChart("Temperature (device)", "°C", temp, "var(--c-temp)", a.zone, false, 0));
            h.append("</div>");
        }

        void hrZones(Activity a) {
            if (a.fit == null) return;
            FitMessage tz = null;
            for (FitMessage m : a.fit.messages(216)) {
                if (Double.valueOf(18).equals(m.num(0)) && m.has(2)) tz = m;
            }
            if (tz == null) return;
            double[] secs = tz.scaledArray(2);
            double[] bounds = tz.scaledArray(6);
            if (secs == null) return;
            double total = 0;
            for (double v : secs) total += v;
            if (total <= 0) return;
            h.append("<h3>Heart-rate zones</h3>").append(srcLine("FIT", a.files.fit.getFileName().toString(),
                    "time_in_zone message for the session" + (tz.has(11) ? " · max HR " + tz.display(11) : "")
                            + (tz.has(12) ? " · resting HR " + tz.display(12) : "")
                            + (tz.has(10) ? " · zones based on " + tz.display(10) : "")));
            h.append("<div class=\"zones\">");
            String[] colors = {"#9aa5b1", "#5aa9e6", "#4cbb6c", "#f2c14e", "#f08a4b", "#e5484d"};
            for (int i = 0; i < secs.length; i++) {
                String range;
                if (bounds != null && i < bounds.length) {
                    double lo = i == 0 ? 0 : bounds[i - 1] + 1;
                    range = i == 0 ? "< " + fmtNum(bounds[0], 0) : fmtNum(lo, 0) + "–" + fmtNum(bounds[i], 0);
                } else if (bounds != null && i == bounds.length) {
                    range = "> " + fmtNum(bounds[bounds.length - 1], 0);
                } else range = "";
                double pct = secs[i] * 100 / total;
                h.append("<div class=\"zone\"><span class=\"zl\">Zone ").append(i).append(" <small>").append(range)
                        .append(range.isEmpty() ? "" : " bpm").append("</small></span><span class=\"bar\"><span style=\"width:")
                        .append(fmtNum(pct, 1).replace(',', '.')).append("%;background:").append(colors[Math.min(i, colors.length - 1)])
                        .append("\"></span></span><span class=\"zv\">").append(fmtDur(secs[i])).append(" · ")
                        .append(fmtNum(pct, 0)).append("%</span></div>");
            }
            h.append("</div>");
        }

        void laps(Activity a) {
            if (a.fit == null || a.fit.count(19) == 0) return;
            boolean swim = a.sport.contains("swim");
            boolean run = a.sport.contains("run") || a.sport.contains("walk") || a.sport.contains("hik");
            List<FitMessage> laps = a.fit.messages(19);
            h.append("<h3>Laps (").append(laps.size()).append(")</h3>")
                    .append(srcLine("FIT", a.files.fit.getFileName().toString(), "lap messages"));
            Map<Integer, SwimCorrection.Lap> fixed = new HashMap<>();
            if (swim && a.correction != null) a.correction.laps().forEach(cl -> fixed.put(cl.lap(), cl));
            boolean corr = !fixed.isEmpty();
            h.append("<div class=\"scroll\"><table><thead><tr><th>#</th><th>Start</th><th>Distance</th>")
                    .append(corr ? "<th title=\"Recounted from strokes per length where the watch's turn detection looks off\">Corrected</th>" : "")
                    .append("<th>Time</th><th>")
                    .append(run || swim ? "Pace" : "Speed").append("</th><th>Avg HR</th><th>Max HR</th><th>Cadence</th><th>Ascent</th><th>Calories</th>")
                    .append(swim ? "<th>Lengths</th><th>Stroke</th>" : "").append("<th>Intensity</th><th>Trigger</th></tr></thead><tbody>");
            List<FitMessage> lens = a.fit.messages(101);
            double pool = poolLength(a);
            int cols = (swim ? 14 : 12) + (corr ? 1 : 0);
            int n = 1;
            for (FitMessage l : laps) {
                // A swim lap points at its pool lengths through first_length_index + num_lengths.
                Double first = l.num(35), count = l.num(32);
                List<FitMessage> own = swim && first != null && count != null && count > 0 && first + count <= lens.size()
                        ? lens.subList(first.intValue(), first.intValue() + count.intValue()) : List.of();
                Double dist = l.scaled(9);
                Double time = l.scaled(8);
                // Derive speed from distance / timer time: the watch's own avg-speed field is occasionally
                // off (e.g. a factor 10 on a long swim lap), while it otherwise equals this ratio.
                // (Double) keeps the conditional boxed: a primitive branch would unbox a missing (null) speed.
                Double sp = dist != null && time != null && dist > 0 && time > 0 ? (Double) (dist / time)
                        : l.has(110) ? l.scaled(110) : l.scaled(13);
                Double start = l.num(2);
                int lapNo = n++;
                h.append(own.isEmpty() ? "<tr><td>" : "<tr class=\"has-sub\" onclick=\"toggleSub(this)\" title=\"Show this lap's lengths\"><td>")
                        .append(lapNo).append("</td><td>")
                        .append(start == null ? "" : HMS.format(fitInstant(start).atZone(a.zone)))
                        .append(dist == null ? "</td><td class=\"num\">" : "</td><td class=\"num\" data-value=\"" + dist + "\">" + fmtDist(dist)).append("</td>");
                if (corr) {
                    SwimCorrection.Lap cl = fixed.get(lapNo);
                    if (cl == null) h.append("<td class=\"num\"></td>");
                    else {
                        double cd = cl.lengths() * a.correction.pool();
                        h.append("<td class=\"num").append(cl.recounted() ? " fixed" : "").append("\" data-value=\"").append(cd).append("\" title=\"")
                                .append(cl.recounted()
                                        ? fmtNum(cl.strokes(), 0) + " strokes ÷ " + fmtNum(a.correction.strokesPerLength(), 1) + " per length = "
                                          + cl.lengths() + " lengths (watch: " + cl.garminLengths() + ")"
                                        : fmtNum(cl.strokes() / cl.garminLengths(), 1) + " strokes per length: within "
                                          + fmtNum(SwimCorrection.TOLERANCE * 100, 0) + "%, kept as recorded")
                                .append("\">").append(fmtDist(cd)).append("</td>");
                    }
                }
                h.append("<td class=\"num\">")
                        .append(time == null ? "" : fmtDur(time)).append("</td><td class=\"num\">")
                        .append(sp == null || sp == 0 ? "" : run || swim ? fmtPace(sp, swim) : fmtNum(sp * 3.6, 1) + " km/h")
                        .append("</td><td class=\"num\">").append(l.display(15)).append("</td><td class=\"num\">").append(l.display(16))
                        .append("</td><td class=\"num\">").append(run && l.scaled(17) != null ? fmtNum(l.scaled(17) * 2, 0) + " spm" : l.display(17))
                        .append("</td><td class=\"num\">").append(l.display(21))
                        .append("</td><td class=\"num\">").append(l.display(11)).append("</td>");
                if (swim) h.append("<td class=\"num\">").append(l.display(40)).append(own.isEmpty() ? "" : " <span class=\"caret\">▸</span>")
                        .append("</td><td>").append(l.display(38)).append("</td>");
                h.append("<td>").append(l.display(23)).append("</td><td>").append(l.display(24)).append("</td></tr>");
                if (!own.isEmpty()) {
                    h.append("<tr class=\"sub\" hidden><td colspan=\"").append(cols).append("\">");
                    lengthsTable(a, own, first.intValue() + 1, pool);
                    h.append("</td></tr>");
                }
            }
            h.append("</tbody></table></div>");
        }

        void swimLengths(Activity a) {
            if (a.fit == null || a.fit.count(101) == 0) return;
            List<FitMessage> lens = a.fit.messages(101);
            double pool = poolLength(a);
            h.append("<h3>Pool lengths (").append(lens.size()).append(")</h3>")
                    .append(srcLine("FIT", a.files.fit.getFileName().toString(), "length messages · pool " + fmtNum(pool, 0) + " m"));
            // Compact chart of time per active length.
            Series per = new Series();
            for (FitMessage l : lens) {
                if (!Double.valueOf(1).equals(l.num(12))) continue;
                Double ts = l.num(2);
                Double t = l.scaled(4);
                if (ts != null && t != null) per.add(fitInstant(ts).toEpochMilli(), t);
            }
            if (!per.isEmpty()) h.append("<div class=\"charts\">").append(lineChart("Seconds per active length", "s", per, "var(--c-speed)", a.zone, false, 1)).append("</div>");
            h.append("<details><summary>Show all lengths</summary>");
            lengthsTable(a, lens, 1, pool);
            h.append("</details>");
        }

        private static double poolLength(Activity a) {
            Double p = Optional.ofNullable(a.fit.first(18)).map(s -> s.scaled(44)).orElse(null);
            return p == null || p <= 0 ? 25.0 : p;
        }

        /** One row per pool length; {@code n} is the number of the first one in the whole swim. */
        private void lengthsTable(Activity a, List<FitMessage> lens, int n, double pool) {
            // Running totals over the whole swim, so a lap's sub-table continues where the previous lap ended.
            List<FitMessage> all = a.fit.messages(101);
            FitMessage session = a.fit.first(18);
            Double t0 = session != null && session.num(2) != null ? session.num(2) : all.isEmpty() ? null : all.getFirst().num(2);
            double[] garminCum = new double[all.size()];
            for (int i = 0, k = 0; i < all.size(); i++) {
                if (Double.valueOf(1).equals(all.get(i).num(12))) k++;
                garminCum[i] = k * pool;
            }
            double[] corrCum = a.correction != null && a.correction.laps().stream().anyMatch(SwimCorrection.Lap::recounted)
                    && a.correction.cumulative().length == all.size() ? a.correction.cumulative() : null;
            h.append("<div class=\"scroll\"><table><thead><tr><th>#</th><th>Start</th>")
                    .append("<th title=\"Time since the start of the swim, at the end of this length\">Timer</th>")
                    .append("<th title=\"Distance after this length, as counted by the watch\">Distance</th>")
                    .append(corrCum != null ? "<th title=\"Distance after this length, recounted from the strokes\">Corrected</th>" : "")
                    .append("<th>Type</th><th>Stroke</th><th>Time</th><th>Strokes</th><th>SWOLF</th><th>Pace /100m</th><th>Stroke rate</th></tr></thead><tbody>");
            for (FitMessage l : lens) {
                int idx = n - 1;
                Double t = l.scaled(4);
                Double strokes = l.scaled(5);
                Double start = l.num(2);
                Double elapsed = l.scaled(3);
                boolean active = Double.valueOf(1).equals(l.num(12));
                Double timer = t0 != null && start != null ? start - t0 + (elapsed != null ? elapsed : t != null ? t : 0) : null;
                h.append("<tr").append(active ? "" : " class=\"rest\"").append("><td>").append(n++).append("</td><td>")
                        .append(start == null ? "" : HMS.format(fitInstant(start).atZone(a.zone))).append("</td><td class=\"num\">")
                        .append(timer == null ? "" : fmtDur(timer)).append("</td><td class=\"num\">")
                        .append(idx < garminCum.length ? fmtNum(garminCum[idx], 0) + " m" : "").append("</td>");
                if (corrCum != null) h.append("<td class=\"num corr\">").append(idx < corrCum.length ? "≈ " + fmtNum(corrCum[idx], 0) + " m" : "").append("</td>");
                h.append("<td>")
                        .append(l.display(12)).append("</td><td>").append(active ? l.display(7) : "").append("</td><td class=\"num\">")
                        .append(t == null ? "" : fmtDur(t)).append("</td><td class=\"num\">").append(active ? l.display(5) : "")
                        .append("</td><td class=\"num\">").append(active && t != null && strokes != null ? fmtNum(t + strokes, 0) : "")
                        .append("</td><td class=\"num\">").append(active && t != null ? fmtDur(t * 100 / pool) : "")
                        .append("</td><td class=\"num\">").append(active ? l.display(9) : "").append("</td></tr>");
            }
            h.append("</tbody></table></div>");
        }

        void devices(Activity a) {
            if (a.fit == null) return;
            List<FitMessage> devs = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (FitMessage d : a.fit.messages(23)) {
                String key = d.display(0) + "|" + d.display(3) + "|" + d.display(1) + "|" + d.display(21);
                if (seen.add(key)) devs.add(d);
            }
            FitMessage fid = a.fit.first(0);
            if (devs.isEmpty() && fid == null) return;
            h.append("<h3>Devices &amp; sensors</h3>").append(srcLine("FIT", a.files.fit.getFileName().toString(), "file_id and device_info messages"));
            if (fid != null) {
                h.append("<p>File created ").append(fid.display(4)).append(" by ").append(fid.display(1)).append(" product ")
                        .append(fid.display(2)).append(fid.has(8) ? " (" + esc(fid.display(8)) + ")" : "")
                        .append(fid.has(3) ? ", serial " + fid.display(3) : "").append(".</p>");
            }
            if (devs.isEmpty()) return;
            h.append("<div class=\"scroll\"><table><thead><tr><th>Index</th><th>Type</th><th>Manufacturer</th><th>Product</th><th>Serial</th><th>Software</th><th>Source</th><th>Battery</th></tr></thead><tbody>");
            for (FitMessage d : devs) {
                String battery = d.has(32) ? d.display(32) : d.has(11) ? d.display(11) : "";
                if (d.has(10)) battery += (battery.isEmpty() ? "" : " · ") + d.display(10);
                h.append("<tr><td>").append(d.display(0)).append("</td><td>").append(esc(deviceType(d))).append("</td><td>")
                        .append(d.display(2)).append("</td><td>").append(d.has(27) ? esc(d.display(27)) : d.display(4))
                        .append("</td><td>").append(d.has(3) ? d.display(3) : d.display(21)).append("</td><td>").append(d.display(5))
                        .append("</td><td>").append(d.display(25)).append("</td><td>").append(esc(battery)).append("</td></tr>");
            }
            h.append("</tbody></table></div>");
        }

        String deviceType(FitMessage d) {
            Double t = d.num(1);
            if (t == null) return "";
            Map<Integer, String> m = Double.valueOf(5).equals(d.num(25)) ? FitProfile.LOCAL_DEVICE_TYPE : FitProfile.ANTPLUS_DEVICE_TYPE;
            return humanize(m.getOrDefault(t.intValue(), "type " + t.intValue()));
        }

        void events(Activity a) {
            if (a.fit == null || a.fit.count(21) == 0) return;
            List<FitMessage> ev = a.fit.messages(21);
            h.append("<details><summary>Events (").append(ev.size()).append(") ").append(chip("FIT")).append("</summary><div class=\"scroll\"><table><thead><tr><th>Time</th><th>Event</th><th>Type</th><th>Data</th></tr></thead><tbody>");
            for (FitMessage e : ev) {
                Double ts = e.num(253);
                h.append("<tr><td>").append(ts == null ? "" : HMS.format(fitInstant(ts).atZone(a.zone))).append("</td><td>")
                        .append(esc(humanize(e.display(0)))).append("</td><td>").append(esc(humanize(e.display(1))))
                        .append("</td><td>").append(e.display(3)).append("</td></tr>");
            }
            h.append("</tbody></table></div></details>");
        }

        void fitAll(Activity a) {
            FitFile f = a.fit;
            h.append("<p class=\"muted\">FIT protocol ").append(f.protocol).append(", profile ").append(f.profile / 100.0)
                    .append(", ").append(f.messages.size()).append(" messages, ").append(f.developerFields.size())
                    .append(" developer fields. Names and units follow the Garmin FIT SDK profile; fields marked <i>#n</i> are not in the public profile (Garmin-internal).</p>");
            Map<Integer, List<FitMessage>> byType = new TreeMap<>();
            for (FitMessage m : f.messages) byType.computeIfAbsent(m.global, k -> new ArrayList<>()).add(m);
            for (var e : byType.entrySet()) {
                List<FitMessage> list = e.getValue();
                String name = FitProfile.MESG_NAMES.getOrDefault(e.getKey(), "#" + e.getKey());
                h.append("<details class=\"inner\"><summary><b>").append(esc(name)).append("</b> <span class=\"muted\">× ")
                        .append(list.size()).append("</span></summary>");
                Set<String> cols = new LinkedHashSet<>();
                Map<String, Integer> colNums = new HashMap<>();
                for (FitMessage m : list) {
                    for (int fn : m.fields.keySet()) {
                        FitProfile.F pf = FitProfile.field(m.global, fn);
                        String c = pf == null ? "#" + fn : pf.name();
                        cols.add(c);
                        colNums.put(c, fn);
                    }
                    cols.addAll(m.dev.keySet());
                }
                int limit = e.getKey() == 20 ? 20 : 200;
                if (list.size() > limit) {
                    h.append("<p class=\"muted\">Showing ").append(limit).append(" of ").append(list.size())
                            .append(e.getKey() == 20 ? " (the full series is charted above)" : "").append(".</p>");
                }
                h.append("<div class=\"scroll\"><table class=\"dense\"><thead><tr>");
                for (String c : cols) h.append("<th>").append(esc(c)).append("</th>");
                h.append("</tr></thead><tbody>");
                int stride = Math.max(1, list.size() / limit);
                int shown = 0;
                for (int i = 0; i < list.size() && shown < limit; i += stride, shown++) {
                    FitMessage m = list.get(i);
                    h.append("<tr>");
                    for (String c : cols) {
                        String v;
                        if (m.dev.containsKey(c)) v = Objects2.toStr(m.dev.get(c));
                        else {
                            Integer fn = colNums.get(c);
                            v = fn != null && m.has(fn) ? m.display(fn) : "";
                        }
                        h.append("<td>").append(esc(v)).append("</td>");
                    }
                    h.append("</tr>");
                }
                h.append("</tbody></table></div></details>");
            }
        }

        void tcxDetails(Xml x) {
            Element act = x.first("Activity");
            if (act != null) {
                h.append("<p>Sport: <b>").append(esc(act.getAttribute("Sport"))).append("</b> · Id ").append(esc(Xml.text(act, "Id"))).append("</p>");
            }
            Element creator = x.first("Creator");
            if (creator != null) {
                Element v = Xml.child(creator, "Version");
                h.append("<p>Creator: <b>").append(esc(Xml.text(creator, "Name"))).append("</b>, unit id ")
                        .append(esc(Xml.text(creator, "UnitId"))).append(", product id ").append(esc(Xml.text(creator, "ProductID")));
                if (v != null) h.append(", firmware ").append(esc(Xml.text(v, "VersionMajor") + "." + Xml.text(v, "VersionMinor")));
                h.append("</p>");
            }
            Element author = x.first("Author");
            if (author != null) {
                h.append("<p>Exported by: ").append(esc(Xml.text(author, "Name"))).append(", part number ")
                        .append(esc(Xml.text(author, "PartNumber"))).append(", language ").append(esc(Xml.text(author, "LangID"))).append("</p>");
            }
            List<Element> laps = x.all("Lap");
            h.append("<div class=\"scroll\"><table><thead><tr><th>#</th><th>Start</th><th>Time</th><th>Distance</th><th>Max speed</th><th>Calories</th><th>Avg HR</th><th>Max HR</th><th>Intensity</th><th>Trigger</th><th>Cadence</th><th>Avg speed (ext)</th><th>Trackpoints</th></tr></thead><tbody>");
            int n = 1;
            for (Element l : laps) {
                Element ahr = Xml.child(l, "AverageHeartRateBpm");
                Element mhr = Xml.child(l, "MaximumHeartRateBpm");
                Double t = parseD(Xml.text(l, "TotalTimeSeconds"));
                Double d = parseD(Xml.text(l, "DistanceMeters"));
                Double ms = parseD(Xml.text(l, "MaximumSpeed"));
                Double as = parseD(Xml.textDeep(l, "AvgSpeed"));
                h.append("<tr><td>").append(n++).append("</td><td>").append(esc(l.getAttribute("StartTime"))).append("</td><td class=\"num\">")
                        .append(t == null ? "" : fmtDur(t)).append("</td><td class=\"num\">").append(d == null ? "" : fmtDist(d))
                        .append("</td><td class=\"num\">").append(ms == null ? "" : fmtNum(ms * 3.6, 1) + " km/h").append("</td><td class=\"num\">")
                        .append(esc(Xml.text(l, "Calories"))).append("</td><td class=\"num\">").append(ahr == null ? "" : esc(Xml.text(ahr, "Value")))
                        .append("</td><td class=\"num\">").append(mhr == null ? "" : esc(Xml.text(mhr, "Value"))).append("</td><td>")
                        .append(esc(Xml.text(l, "Intensity"))).append("</td><td>").append(esc(Xml.text(l, "TriggerMethod"))).append("</td><td class=\"num\">")
                        .append(esc(Xml.text(l, "Cadence"))).append("</td><td class=\"num\">").append(as == null ? "" : fmtNum(as * 3.6, 1) + " km/h")
                        .append("</td><td class=\"num\">").append(l.getElementsByTagNameNS("*", "Trackpoint").getLength()).append("</td></tr>");
            }
            h.append("</tbody></table></div>");
            h.append("<p class=\"muted\">").append(esc(tcxSummary(x))).append("</p>");
        }

        void gpxDetails(Xml x) {
            Element root = x.doc.getDocumentElement();
            h.append("<p>Creator: <b>").append(esc(root.getAttribute("creator"))).append("</b>, GPX ").append(esc(root.getAttribute("version")));
            Element meta = x.first("metadata");
            if (meta != null) h.append(", metadata time ").append(esc(Xml.text(meta, "time")));
            h.append("</p>");
            for (Element trk : x.all("trk")) {
                h.append("<p>Track <b>").append(esc(Xml.text(trk, "name"))).append("</b>, type ").append(esc(Xml.text(trk, "type"))).append("</p>");
            }
            h.append("<p class=\"muted\">").append(esc(gpxSummary(x))).append("</p>");
        }

        // ---- wellness

        void wellness() {
            Map<String, Object> w = day.wellness;
            String file = day.wellnessFile.getFileName().toString();
            ZoneId zone = ZoneId.systemDefault();
            h.append("<section class=\"card\" id=\"wellness\"><h2>❤ Health &amp; wellness</h2>");
            h.append(srcLine("Wellness", file, "Garmin Connect daily snapshot (" + day.wellnessFile + ")"));

            Map<String, Object> ds = asMap(w.get("dailySummary"));
            if (ds != null) {
                h.append("<h3>Day summary</h3>");
                tiles(() -> {
                    wt(ds, "totalSteps", "Steps", v -> fmtNum(v, 0) + goal(ds, "dailyStepGoal"));
                    wt(ds, "totalDistanceMeters", "Total distance", v -> fmtDist(v));
                    wt(ds, "totalKilocalories", "Total calories", v -> fmtNum(v, 0) + " kcal");
                    wt(ds, "activeKilocalories", "Active calories", v -> fmtNum(v, 0) + " kcal");
                    wt(ds, "bmrKilocalories", "Resting (BMR) calories", v -> fmtNum(v, 0) + " kcal");
                    wt(ds, "restingHeartRate", "Resting HR", v -> fmtNum(v, 0) + " bpm" + sub(ds, "lastSevenDaysAvgRestingHeartRate", "7-day avg "));
                    wt(ds, "minHeartRate", "Min HR", v -> fmtNum(v, 0) + " bpm");
                    wt(ds, "maxHeartRate", "Max HR", v -> fmtNum(v, 0) + " bpm");
                    wt(ds, "moderateIntensityMinutes", "Moderate intensity", v -> fmtNum(v, 0) + " min");
                    wt(ds, "vigorousIntensityMinutes", "Vigorous intensity", v -> fmtNum(v, 0) + " min");
                    wt(ds, "floorsAscended", "Floors up", v -> fmtNum(v, 0) + goal(ds, "userFloorsAscendedGoal"));
                    wt(ds, "floorsDescended", "Floors down", v -> fmtNum(v, 0));
                    wt(ds, "averageStressLevel", "Avg stress", v -> fmtNum(v, 0) + sub(ds, "maxStressLevel", "max "));
                    wt(ds, "bodyBatteryHighestValue", "Body battery high", v -> fmtNum(v, 0));
                    wt(ds, "bodyBatteryLowestValue", "Body battery low", v -> fmtNum(v, 0));
                    wt(ds, "bodyBatteryChargedValue", "Body battery charged", v -> "+" + fmtNum(v, 0));
                    wt(ds, "bodyBatteryDrainedValue", "Body battery drained", v -> "−" + fmtNum(v, 0));
                    wt(ds, "bodyBatteryAtWakeTime", "Body battery at wake", v -> fmtNum(v, 0));
                    wt(ds, "avgWakingRespirationValue", "Waking respiration", v -> fmtNum(v, 0) + " brpm");
                    wt(ds, "averageSpo2", "Avg SpO₂", v -> fmtNum(v, 0) + " %");
                    wt(ds, "lowestSpo2", "Lowest SpO₂", v -> fmtNum(v, 0) + " %");
                    wt(ds, "highlyActiveSeconds", "Highly active", v -> fmtDur(v));
                    wt(ds, "activeSeconds", "Active", v -> fmtDur(v));
                    wt(ds, "sedentarySeconds", "Sedentary", v -> fmtDur(v));
                    wt(ds, "sleepingSeconds", "Sleeping", v -> fmtDur(v));
                });
                String fb = str(at(ds, "bodyBatteryDynamicFeedbackEvent", "feedbackLongType"));
                if (!fb.isEmpty()) h.append("<p class=\"note\">Body battery feedback: ").append(esc(humanize(fb))).append("</p>");
            }

            sleep(w, zone);

            Series hrS = pairs(at(w, "heartRate", "heartRateValues"), 1);
            Series stress = pairs(at(w, "stress", "stressValuesArray"), 1).positiveOnly();
            Series bb = pairs(at(w, "stress", "bodyBatteryValuesArray"), 2);
            Series resp = pairs(at(w, "respiration", "respirationValuesArray"), 1).positiveOnly();
            if (!hrS.isEmpty() || !stress.isEmpty() || !bb.isEmpty() || !resp.isEmpty()) {
                h.append("<h3>Through the day</h3><div class=\"charts\">");
                if (!hrS.isEmpty()) h.append(lineChart("Heart rate", "bpm", hrS, "var(--c-hr)", zone, false, 0, "heartRate.heartRateValues"));
                if (!stress.isEmpty()) h.append(lineChart("Stress", "level 0–100", stress, "var(--c-stress)", zone, false, 0, "stress.stressValuesArray"));
                if (!bb.isEmpty()) h.append(lineChart("Body battery", "level", bb, "var(--c-bb)", zone, false, 0, "stress.bodyBatteryValuesArray"));
                if (!resp.isEmpty()) h.append(lineChart("Respiration", "breaths/min", resp, "var(--c-resp)", zone, false, 1, "respiration.respirationValuesArray"));
                h.append("</div>");
            }

            Map<String, Object> stressM = asMap(ds);
            if (stressM != null && num(stressM.get("restStressDuration")) != null) {
                h.append("<h3>Stress breakdown</h3><div class=\"zones\">");
                String[][] parts = {{"restStressDuration", "Rest", "#5aa9e6"}, {"lowStressDuration", "Low", "#f2c14e"},
                        {"mediumStressDuration", "Medium", "#f08a4b"}, {"highStressDuration", "High", "#e5484d"},
                        {"activityStressDuration", "Activity", "#9aa5b1"}, {"uncategorizedStressDuration", "Not measured", "#cfd6dd"}};
                double total = 0;
                for (String[] p : parts) total += numOr(stressM.get(p[0]), 0);
                for (String[] p : parts) {
                    double v = numOr(stressM.get(p[0]), 0);
                    zoneBar(p[1], v, total, p[2], "dailySummary." + p[0]);
                }
                h.append("</div>");
            }

            bodyBatteryEvents(w, zone);
            hrv(w, zone);
            readiness(w);
            training(w);

            Series im = pairs(at(w, "intensityMinutes", "imValuesArray"), 1);
            if (!im.isEmpty()) {
                h.append("<h3>Intensity minutes</h3>");
                tiles(() -> {
                    Map<String, Object> m = asMap(w.get("intensityMinutes"));
                    wt(m, "weeklyTotal", "This week", v -> fmtNum(v, 0) + goal(m, "weekGoal"), "intensityMinutes.");
                    wt(m, "weeklyModerate", "Week moderate", v -> fmtNum(v, 0), "intensityMinutes.");
                    wt(m, "weeklyVigorous", "Week vigorous", v -> fmtNum(v, 0), "intensityMinutes.");
                });
                h.append("<div class=\"charts\">").append(lineChart("Intensity minutes per 15 min", "min", im, "var(--c-cad)", zone, false, 0, "intensityMinutes.imValuesArray")).append("</div>");
            }

            h.append("<h3>Everything in the file</h3>");
            for (var e : w.entrySet()) {
                Object v = e.getValue();
                if (v == null || v instanceof List<?> l && l.isEmpty()) continue;
                if (!(v instanceof Map || v instanceof List)) continue;
                boolean staleVo2 = e.getKey().equals("vo2max") && vo2maxEntries(w).stream().anyMatch(Vo2Entry::stale);
                details(humanize(e.getKey()) + (staleVo2 ? " — ⚠ not from this day (latest value at download time)" : ""),
                        "Wellness", file + " → " + e.getKey(), () -> tree(v, 0));
            }
            List<String> empty = new ArrayList<>();
            for (var e : w.entrySet()) {
                if (e.getValue() == null || e.getValue() instanceof List<?> l && l.isEmpty()) empty.add(humanize(e.getKey()));
            }
            if (!empty.isEmpty()) h.append("<p class=\"muted\">No data in this snapshot for: ").append(esc(String.join(", ", empty))).append(".</p>");
            h.append("</section>");
        }

        void sleep(Map<String, Object> w, ZoneId zone) {
            Map<String, Object> d = asMap(at(w, "sleep", "dailySleepDTO"));
            if (d == null || num(d.get("sleepTimeSeconds")) == null) return;
            h.append("<h3>Sleep</h3>");
            tiles(() -> {
                Double score = num(at(d, "sleepScores", "overall", "value"));
                if (score != null) tile(fmtNum(score, 0) + " · " + humanize(str(at(d, "sleepScores", "overall", "qualifierKey"))),
                        "Sleep score", "sleep.dailySleepDTO.sleepScores.overall", "Wellness");
                Double s = num(d.get("sleepStartTimestampGMT"));
                Double e = num(d.get("sleepEndTimestampGMT"));
                if (s != null && e != null) tile(HM.format(Instant.ofEpochMilli(s.longValue()).atZone(zone)) + " – "
                        + HM.format(Instant.ofEpochMilli(e.longValue()).atZone(zone)), "Asleep", "sleep.dailySleepDTO.sleepStart/EndTimestampGMT", "Wellness");
                wt(d, "sleepTimeSeconds", "Total sleep", v -> fmtDur(v), "sleep.dailySleepDTO.");
                wt(d, "deepSleepSeconds", "Deep", v -> fmtDur(v) + pctOf(v, d), "sleep.dailySleepDTO.");
                wt(d, "lightSleepSeconds", "Light", v -> fmtDur(v) + pctOf(v, d), "sleep.dailySleepDTO.");
                wt(d, "remSleepSeconds", "REM", v -> fmtDur(v) + pctOf(v, d), "sleep.dailySleepDTO.");
                wt(d, "awakeSleepSeconds", "Awake", v -> fmtDur(v), "sleep.dailySleepDTO.");
                wt(d, "awakeCount", "Times awake", v -> fmtNum(v, 0), "sleep.dailySleepDTO.");
                wt(d, "avgHeartRate", "Avg sleep HR", v -> fmtNum(v, 0) + " bpm", "sleep.dailySleepDTO.");
                wt(d, "averageRespirationValue", "Avg respiration", v -> fmtNum(v, 0) + " brpm", "sleep.dailySleepDTO.");
                wt(d, "avgSleepStress", "Avg sleep stress", v -> fmtNum(v, 0), "sleep.dailySleepDTO.");
                Map<String, Object> sl = asMap(w.get("sleep"));
                wt(sl, "avgOvernightHrv", "Overnight HRV", v -> fmtNum(v, 0) + " ms", "sleep.");
                wt(sl, "bodyBatteryChange", "Body battery gained", v -> "+" + fmtNum(v, 0), "sleep.");
                wt(sl, "restlessMomentsCount", "Restless moments", v -> fmtNum(v, 0), "sleep.");
                Double need = num(at(d, "sleepNeed", "actual"));
                if (need != null) tile(fmtDur(need * 60), "Sleep need", "sleep.dailySleepDTO.sleepNeed.actual (minutes)", "Wellness");
            });
            Map<String, Object> scores = asMap(d.get("sleepScores"));
            if (scores != null) {
                h.append("<div class=\"chips\">");
                for (var e : scores.entrySet()) {
                    if (e.getKey().equals("overall")) continue;
                    String q = str(at(e.getValue(), "qualifierKey"));
                    if (q.isEmpty()) continue;
                    Double v = num(at(e.getValue(), "value"));
                    h.append("<span class=\"q q-").append(esc(q.toLowerCase())).append("\" title=\"sleep.dailySleepDTO.sleepScores.")
                            .append(esc(e.getKey())).append("\">").append(esc(humanize(e.getKey()))).append(v != null ? " " + fmtNum(v, 0) : "")
                            .append(": <b>").append(esc(humanize(q))).append("</b></span>");
                }
                h.append("</div>");
            }
            String insight = str(d.get("sleepScorePersonalizedInsight"));
            if (!insight.isEmpty() && !insight.equals("NONE")) h.append("<p class=\"note\">Insight: ").append(esc(humanize(insight))).append("</p>");
            List<Object> levels = asList(at(w, "sleep", "sleepLevels"));
            if (levels != null && !levels.isEmpty()) h.append(sleepChart(levels, zone));
            Series sleepHr = new Series();
            List<Object> shr = asList(at(w, "sleep", "sleepHeartRate"));
            if (shr != null) for (Object o : shr) sleepHr.add(numOr(at(o, "startGMT"), 0), num(at(o, "value")));
            Series hrvS = new Series();
            List<Object> hd = asList(at(w, "sleep", "hrvData"));
            if (hd != null) for (Object o : hd) hrvS.add(numOr(at(o, "startGMT"), 0), num(at(o, "value")));
            Series sbb = new Series();
            List<Object> sb = asList(at(w, "sleep", "sleepBodyBattery"));
            if (sb != null) for (Object o : sb) sbb.add(numOr(at(o, "startGMT"), 0), num(at(o, "value")));
            if (!sleepHr.isEmpty() || !hrvS.isEmpty() || !sbb.isEmpty()) {
                h.append("<div class=\"charts\">");
                if (!sleepHr.isEmpty()) h.append(lineChart("Heart rate during sleep", "bpm", sleepHr, "var(--c-hr)", zone, false, 0, "sleep.sleepHeartRate"));
                if (!hrvS.isEmpty()) h.append(lineChart("HRV during sleep", "ms", hrvS, "var(--c-resp)", zone, false, 0, "sleep.hrvData"));
                if (!sbb.isEmpty()) h.append(lineChart("Body battery during sleep", "level", sbb, "var(--c-bb)", zone, false, 0, "sleep.sleepBodyBattery"));
                h.append("</div>");
            }
        }

        String sleepChart(List<Object> levels, ZoneId zone) {
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            List<long[]> segs = new ArrayList<>();
            for (Object o : levels) {
                String s = str(at(o, "startGMT"));
                String e = str(at(o, "endGMT"));
                Double lvl = num(at(o, "activityLevel"));
                if (s.isEmpty() || e.isEmpty() || lvl == null) continue;
                long a = parseGarminTime(s).toInstant(ZoneOffset.UTC).toEpochMilli();
                long b = parseGarminTime(e).toInstant(ZoneOffset.UTC).toEpochMilli();
                segs.add(new long[]{a, b, Math.round(lvl)});
                min = Math.min(min, a);
                max = Math.max(max, b);
            }
            if (segs.isEmpty() || max <= min) return "";
            int W = 900, H = 130, L = 52, R = 8, T = 6, B = 20;
            String[] names = {"Deep", "Light", "REM", "Awake"};
            String[] colors = {"var(--c-deep)", "var(--c-light)", "var(--c-rem)", "var(--c-awake)"};
            int[] row = {3, 2, 1, 0}; // awake on top, deep at the bottom
            double rh = (H - T - B) / 4.0;
            StringBuilder s = new StringBuilder("<figure class=\"chart wide\"><figcaption>Sleep stages <span class=\"muted\">· ")
                    .append(chip("Wellness")).append(" sleep.sleepLevels</span></figcaption><svg viewBox=\"0 0 ")
                    .append(W).append(' ').append(H).append("\" role=\"img\" aria-label=\"Sleep stages\">");
            for (int i = 0; i < 4; i++) {
                double y = T + row[i] * rh;
                s.append("<text x=\"").append(L - 6).append("\" y=\"").append(fmt1(y + rh / 2 + 4)).append("\" class=\"ax\" text-anchor=\"end\">").append(names[i]).append("</text>");
            }
            Map<Integer, Long> totals = new TreeMap<>();
            for (long[] g : segs) {
                int lv = (int) Math.max(0, Math.min(3, g[2]));
                double x1 = L + (g[0] - min) * (double) (W - L - R) / (max - min);
                double x2 = L + (g[1] - min) * (double) (W - L - R) / (max - min);
                double y = T + row[lv] * rh;
                s.append("<rect x=\"").append(fmt1(x1)).append("\" y=\"").append(fmt1(y + 2)).append("\" width=\"").append(fmt1(Math.max(0.5, x2 - x1)))
                        .append("\" height=\"").append(fmt1(rh - 4)).append("\" rx=\"2\" fill=\"").append(colors[lv]).append("\"><title>")
                        .append(names[lv]).append(" ").append(HM.format(Instant.ofEpochMilli(g[0]).atZone(zone))).append("–")
                        .append(HM.format(Instant.ofEpochMilli(g[1]).atZone(zone))).append("</title></rect>");
                totals.merge(lv, g[1] - g[0], Long::sum);
            }
            for (int i = 0; i <= 6; i++) {
                long t = min + (max - min) * i / 6;
                double x = L + (W - L - R) * i / 6.0;
                s.append("<text x=\"").append(fmt1(x)).append("\" y=\"").append(H - 6).append("\" class=\"ax\" text-anchor=\"middle\">")
                        .append(HM.format(Instant.ofEpochMilli(t).atZone(zone))).append("</text>");
            }
            s.append("</svg><div class=\"legend\">");
            for (int i = 3; i >= 0; i--) {
                s.append("<span><i style=\"background:").append(colors[i]).append("\"></i>").append(names[i]).append(" ")
                        .append(fmtDur(totals.getOrDefault(i, 0L) / 1000.0)).append("</span>");
            }
            return s.append("</div></figure>").toString();
        }

        void bodyBatteryEvents(Map<String, Object> w, ZoneId zone) {
            List<Object> ev = asList(at(w, "bodyBatteryEvents"));
            if (ev == null || ev.isEmpty()) return;
            h.append("<h3>Body battery events</h3>").append(srcLine("Wellness", day.wellnessFile.getFileName().toString(), "bodyBatteryEvents"));
            h.append("<div class=\"scroll\"><table><thead><tr><th>Start</th><th>Type</th><th>Duration</th><th>Impact</th><th>Avg stress</th><th>Feedback</th></tr></thead><tbody>");
            for (Object o : ev) {
                Map<String, Object> e = asMap(at(o, "event"));
                if (e == null) continue;
                String st = str(e.get("eventStartTimeGmt"));
                Double impact = num(e.get("bodyBatteryImpact"));
                h.append("<tr><td>").append(st.isEmpty() ? "" : HM.format(parseGarminTime(st).atZone(ZoneOffset.UTC).withZoneSameInstant(zone)))
                        .append("</td><td>").append(esc(humanize(str(e.get("eventType")))))
                        .append(str(at(o, "activityName")).isEmpty() ? "" : " · " + esc(str(at(o, "activityName"))))
                        .append("</td><td class=\"num\">").append(fmtDur(numOr(e.get("durationInMilliseconds"), 0) / 1000))
                        .append("</td><td class=\"num ").append(impact != null && impact < 0 ? "neg" : "pos").append("\">")
                        .append(impact == null ? "" : (impact > 0 ? "+" : "") + fmtNum(impact, 0)).append("</td><td class=\"num\">")
                        .append(num(at(o, "averageStress")) == null ? "" : fmtNum(num(at(o, "averageStress")), 0)).append("</td><td>")
                        .append(esc(humanize(str(e.get("shortFeedback"))))).append("</td></tr>");
            }
            h.append("</tbody></table></div>");
        }

        void hrv(Map<String, Object> w, ZoneId zone) {
            Map<String, Object> hs = asMap(at(w, "hrv", "hrvSummary"));
            if (hs == null) return;
            h.append("<h3>Heart-rate variability</h3>");
            tiles(() -> {
                wt(hs, "lastNightAvg", "Last night avg", v -> fmtNum(v, 0) + " ms", "hrv.hrvSummary.");
                wt(hs, "lastNight5MinHigh", "Highest 5-min", v -> fmtNum(v, 0) + " ms", "hrv.hrvSummary.");
                wt(hs, "weeklyAvg", "7-day avg", v -> fmtNum(v, 0) + " ms", "hrv.hrvSummary.");
                Double lo = num(at(hs, "baseline", "balancedLow"));
                Double hi = num(at(hs, "baseline", "balancedUpper"));
                if (lo != null && hi != null) tile(fmtNum(lo, 0) + "–" + fmtNum(hi, 0) + " ms", "Balanced range", "hrv.hrvSummary.baseline", "Wellness");
                String st = str(hs.get("status"));
                if (!st.isEmpty()) tile(humanize(st), "Status", "hrv.hrvSummary.status · " + str(hs.get("feedbackPhrase")), "Wellness");
            });
            Series r = new Series();
            List<Object> rs = asList(at(w, "hrv", "hrvReadings"));
            if (rs != null) {
                for (Object o : rs) {
                    String t = str(at(o, "readingTimeGMT"));
                    if (!t.isEmpty()) r.add(parseGarminTime(t).toInstant(ZoneOffset.UTC).toEpochMilli(), num(at(o, "hrvValue")));
                }
            }
            if (!r.isEmpty()) h.append("<div class=\"charts\">").append(lineChart("HRV readings (5-min)", "ms", r, "var(--c-resp)", zone, false, 0, "hrv.hrvReadings")).append("</div>");
        }

        void readiness(Map<String, Object> w) {
            List<Object> tr = asList(w.get("trainingReadiness"));
            if (tr == null || tr.isEmpty()) return;
            h.append("<h3>Training readiness</h3>").append(srcLine("Wellness", day.wellnessFile.getFileName().toString(), "trainingReadiness (one row per update during the day)"));
            h.append("<div class=\"scroll\"><table><thead><tr><th>Time</th><th>Score</th><th>Level</th><th>Feedback</th><th>Sleep</th><th>Recovery time</th><th>HRV</th><th>Load (ACWR)</th><th>Stress history</th><th>Sleep history</th></tr></thead><tbody>");
            List<Object> rows = new ArrayList<>(tr);
            rows.sort(Comparator.comparing(o -> str(at(o, "timestampLocal"))));
            for (Object o : rows) {
                String t = str(at(o, "timestampLocal"));
                h.append("<tr><td>").append(t.length() >= 16 ? esc(t.substring(11, 16)) : esc(t)).append("</td><td class=\"num\"><b>")
                        .append(fmtNum(numOr(at(o, "score"), 0), 0)).append("</b></td><td>").append(esc(humanize(str(at(o, "level")))))
                        .append("</td><td>").append(esc(humanize(str(at(o, "feedbackShort"))))).append("</td><td>")
                        .append(factor(o, "sleepScore", "sleepScoreFactorPercent", "sleepScoreFactorFeedback")).append("</td><td>")
                        .append(fmtDur(numOr(at(o, "recoveryTime"), 0) * 60)).append(" · ").append(factor(o, null, "recoveryTimeFactorPercent", "recoveryTimeFactorFeedback"))
                        .append("</td><td>").append(factor(o, "hrvWeeklyAverage", "hrvFactorPercent", "hrvFactorFeedback")).append("</td><td>")
                        .append(factor(o, "acuteLoad", "acwrFactorPercent", "acwrFactorFeedback")).append("</td><td>")
                        .append(factor(o, null, "stressHistoryFactorPercent", "stressHistoryFactorFeedback")).append("</td><td>")
                        .append(factor(o, null, "sleepHistoryFactorPercent", "sleepHistoryFactorFeedback")).append("</td></tr>");
            }
            h.append("</tbody></table></div><p class=\"muted\">Factor cells: raw value · contribution % · Garmin rating.</p>");
        }

        String factor(Object o, String valueKey, String pctKey, String fbKey) {
            StringBuilder s = new StringBuilder();
            if (valueKey != null && num(at(o, valueKey)) != null) s.append(fmtNum(num(at(o, valueKey)), 0)).append(" · ");
            if (num(at(o, pctKey)) != null) s.append(fmtNum(num(at(o, pctKey)), 0)).append("% · ");
            s.append(humanize(str(at(o, fbKey))));
            return esc(s.toString());
        }

        void training(Map<String, Object> w) {
            Map<String, Object> ts = asMap(w.get("trainingStatus"));
            Map<String, Object> es = asMap(w.get("enduranceScore"));
            Map<String, Object> hs = asMap(w.get("hillScore"));
            List<Vo2Entry> vo2Entries = vo2maxEntries(w);
            boolean stale = vo2Entries.stream().anyMatch(Vo2Entry::stale);
            if (ts == null && es == null && hs == null && vo2Entries.isEmpty()) return;
            int start = h.length();
            h.append("<h3>Fitness &amp; training status</h3>");
            int afterHeader = h.length();
            if (stale) {
                h.append("<p class=\"warn\">⚠ The <code>vo2max</code> section of this file is <b>not from this day</b>: it holds ")
                        .append(esc(String.join(", ", vo2Entries.stream().filter(Vo2Entry::stale)
                                .map(v -> humanize(v.sport) + " " + fmtNum(v.value, 1) + " measured " + v.date).toList())))
                        .append(". The file was downloaded with an older DownloadGarminFiles that asked Garmin for the <i>latest</i> VO₂ max ")
                        .append("instead of the value on this date, so it is ignored here. Run DownloadGarminFiles again for this period to replace it.</p>");
            }
            tiles(() -> {
                for (Vo2Entry v : vo2Entries) {
                    if (v.stale) continue;
                    tile(fmtNum(v.value, 1), "VO₂ max " + (v.sport.equals("generic") ? "running" : v.sport) + " <small>(measured this day)</small>",
                            "vo2max." + v.sport + ".vo2MaxPreciseValue, calendarDate " + v.date, "Wellness");
                }
                Map<String, Object> vo2 = asMap(at(ts, "mostRecentVO2Max"));
                if (vo2 != null) {
                    for (String k : List.of("generic", "cycling")) {
                        Double v = num(at(vo2, k, "vo2MaxPreciseValue"));
                        String when = str(at(vo2, k, "calendarDate"));
                        boolean shown = vo2Entries.stream().anyMatch(e -> !e.stale() && e.sport().equals(k) && e.date().equals(when));
                        if (v != null && !shown) tile(fmtNum(v, 1), "VO₂ max " + (k.equals("generic") ? "running" : k) + " <small>(" + esc(str(at(vo2, k, "calendarDate"))) + ")</small>",
                                "trainingStatus.mostRecentVO2Max." + k, "Wellness");
                    }
                }
                Map<String, Object> latest = firstValue(asMap(at(ts, "mostRecentTrainingStatus", "latestTrainingStatusData")));
                if (latest != null) {
                    String p = str(latest.get("trainingStatusFeedbackPhrase"));
                    if (!p.isEmpty()) tile(humanize(p), "Training status", "trainingStatus.mostRecentTrainingStatus…trainingStatusFeedbackPhrase", "Wellness");
                    Map<String, Object> acute = asMap(latest.get("acuteTrainingLoadDTO"));
                    if (acute != null) {
                        Double al = num(acute.get("dailyTrainingLoadAcute"));
                        if (al != null) tile(fmtNum(al, 0) + " <small>(" + fmtNum(numOr(acute.get("minTrainingLoadChronic"), 0), 0) + "–"
                                + fmtNum(numOr(acute.get("maxTrainingLoadChronic"), 0), 0) + ")</small>", "Acute load", "acuteTrainingLoadDTO.dailyTrainingLoadAcute (optimal range)", "Wellness");
                        Double ratio = num(acute.get("dailyAcuteChronicWorkloadRatio"));
                        if (ratio != null) tile(fmtNum(ratio, 1) + " · " + humanize(str(acute.get("acwrStatus"))), "Acute:chronic ratio", "acuteTrainingLoadDTO.dailyAcuteChronicWorkloadRatio", "Wellness");
                    }
                }
                Map<String, Object> bal = firstValue(asMap(at(ts, "mostRecentTrainingLoadBalance", "metricsTrainingLoadBalanceDTOMap")));
                if (bal != null) {
                    String p = str(bal.get("trainingBalanceFeedbackPhrase"));
                    if (!p.isEmpty()) tile(humanize(p), "Load focus", "metricsTrainingLoadBalanceDTOMap.trainingBalanceFeedbackPhrase", "Wellness");
                }
                if (es != null && num(es.get("overallScore")) != null) {
                    tile(fmtNum(num(es.get("overallScore")), 0) + " · " + enduranceClass(es), "Endurance score", "enduranceScore.overallScore", "Wellness");
                }
                if (hs != null && num(hs.get("overallScore")) != null) {
                    tile(fmtNum(num(hs.get("overallScore")), 0) + " <small>(strength " + fmtNum(numOr(hs.get("strengthScore"), 0), 0)
                            + ", endurance " + fmtNum(numOr(hs.get("enduranceScore"), 0), 0) + ")</small>", "Hill score", "hillScore", "Wellness");
                }
            });
            Map<String, Object> bal = firstValue(asMap(at(ts, "mostRecentTrainingLoadBalance", "metricsTrainingLoadBalanceDTOMap")));
            if (bal != null && num(bal.get("monthlyLoadAerobicLow")) != null) {
                h.append("<p class=\"muted\">4-week load focus ").append(chip("Wellness")).append(" (bar = your load, band = Garmin target):</p><div class=\"zones\">");
                String[][] parts = {{"monthlyLoadAnaerobic", "Anaerobic", "#a855f7"}, {"monthlyLoadAerobicHigh", "High aerobic", "#f08a4b"},
                        {"monthlyLoadAerobicLow", "Low aerobic", "#5aa9e6"}};
                double max = 0;
                for (String[] p : parts) max = Math.max(max, Math.max(numOr(bal.get(p[0]), 0), numOr(bal.get(p[0] + "TargetMax"), 0)));
                for (String[] p : parts) {
                    double v = numOr(bal.get(p[0]), 0);
                    double lo = numOr(bal.get(p[0] + "TargetMin"), 0), hi = numOr(bal.get(p[0] + "TargetMax"), 0);
                    h.append("<div class=\"zone\"><span class=\"zl\">").append(p[1]).append("</span><span class=\"bar\"><span class=\"band\" style=\"left:")
                            .append(pct(lo, max)).append("%;width:").append(pct(hi - lo, max)).append("%\"></span><span style=\"width:")
                            .append(pct(v, max)).append("%;background:").append(p[2]).append("\"></span></span><span class=\"zv\">")
                            .append(fmtNum(v, 0)).append(" <small>(target ").append(fmtNum(lo, 0)).append("–").append(fmtNum(hi, 0)).append(")</small></span></div>");
                }
                h.append("</div>");
            }
            if (h.length() == afterHeader) h.setLength(start);
        }

        record Vo2Entry(String sport, double value, String date, boolean stale) {}

        /**
         * VO₂ max values in the snapshot's {@code vo2max} section. Current files hold a list of that day's measurements;
         * files from older downloaders hold one object with the account's latest values, usually from another date.
         */
        List<Vo2Entry> vo2maxEntries(Map<String, Object> w) {
            List<Vo2Entry> out = new ArrayList<>();
            Object v = w.get("vo2max");
            List<Object> items = v instanceof List<?> ? asList(v) : v instanceof Map ? List.of(v) : List.of();
            String day = str(w.get("date"));
            for (Object item : items) {
                for (String sport : List.of("generic", "cycling")) {
                    Double val = num(at(item, sport, "vo2MaxPreciseValue"));
                    if (val == null) val = num(at(item, sport, "vo2MaxValue"));
                    String date = str(at(item, sport, "calendarDate"));
                    if (val == null) continue;
                    out.add(new Vo2Entry(sport, val, date, !date.isEmpty() && !date.equals(day)));
                }
            }
            return out;
        }

        String enduranceClass(Map<String, Object> es) {
            double v = numOr(es.get("overallScore"), 0);
            String[][] c = {{"classificationLowerLimitElite", "Elite"}, {"classificationLowerLimitSuperior", "Superior"},
                    {"classificationLowerLimitExpert", "Expert"}, {"classificationLowerLimitWellTrained", "Well trained"},
                    {"classificationLowerLimitTrained", "Trained"}, {"classificationLowerLimitIntermediate", "Intermediate"}};
            for (String[] x : c) {
                Double lim = num(es.get(x[0]));
                if (lim != null && v >= lim) return x[1];
            }
            return "Recreational";
        }

        // ---- building blocks

        interface Body {
            void run();
        }

        void tiles(Body b) {
            int before = h.length();
            h.append("<div class=\"tiles\">");
            int inner = h.length();
            b.run();
            if (h.length() == inner) h.setLength(before);
            else h.append("</div>");
        }

        void tile(String value, String label, String field, String src) {
            h.append("<div class=\"tile\" title=\"").append(esc(field)).append("\"><div class=\"v\">").append(value.contains("<small>") ? value : esc(value))
                    .append("</div><div class=\"l\">").append(label.contains("<small>") ? label : esc(label));
            if (src != null) h.append(" ").append(chip(src));
            h.append("</div></div>");
        }

        void wt(Map<String, Object> m, String key, String label, java.util.function.DoubleFunction<String> f) {
            wt(m, key, label, f, "dailySummary.");
        }

        void wt(Map<String, Object> m, String key, String label, java.util.function.DoubleFunction<String> f, String prefix) {
            if (m == null) return;
            Double v = num(m.get(key));
            if (v == null) return;
            tile(f.apply(v), label, prefix + key + " = " + m.get(key), "Wellness");
        }

        void zoneBar(String label, double v, double total, String color, String field) {
            double p = total <= 0 ? 0 : v * 100 / total;
            h.append("<div class=\"zone\" title=\"").append(esc(field)).append("\"><span class=\"zl\">").append(esc(label))
                    .append("</span><span class=\"bar\"><span style=\"width:").append(fmtNum(p, 1).replace(',', '.'))
                    .append("%;background:").append(color).append("\"></span></span><span class=\"zv\">").append(fmtDur(v))
                    .append(" · ").append(fmtNum(p, 0)).append("%</span></div>");
        }

        void details(String title, String src, String file, Body b) {
            h.append("<details><summary>").append(esc(title)).append(" ").append(chip(src)).append(" <code class=\"muted\">")
                    .append(esc(file)).append("</code></summary><div class=\"det\">");
            b.run();
            h.append("</div></details>");
        }

        /** Generic renderer for any JSON value: nothing in the file is left out. */
        void tree(Object v, int depth) {
            if (v instanceof Map<?, ?> m) {
                List<Map.Entry<?, ?>> scalars = new ArrayList<>(), nested = new ArrayList<>();
                for (var e : m.entrySet()) {
                    if (e.getValue() instanceof Map || e.getValue() instanceof List<?> l && !l.isEmpty()) nested.add(e);
                    else scalars.add(e);
                }
                if (!scalars.isEmpty()) {
                    h.append("<table class=\"kv\"><tbody>");
                    for (var e : scalars) {
                        String k = String.valueOf(e.getKey());
                        String pretty = prettyValue(k, e.getValue());
                        String raw = e.getValue() == null ? "—" : String.valueOf(e.getValue());
                        h.append("<tr><th title=\"").append(esc(k)).append("\">").append(esc(humanize(k))).append("</th><td>")
                                .append(esc(pretty));
                        if (!pretty.equals(raw) && e.getValue() != null) h.append(" <span class=\"muted\">(").append(esc(raw)).append(")</span>");
                        h.append("</td></tr>");
                    }
                    h.append("</tbody></table>");
                }
                for (var e : nested) {
                    h.append("<details class=\"inner\"").append(depth == 0 && nested.size() <= 3 ? " open" : "").append("><summary>")
                            .append(esc(humanize(String.valueOf(e.getKey())))).append(sizeHint(e.getValue())).append("</summary><div class=\"det\">");
                    tree(e.getValue(), depth + 1);
                    h.append("</div></details>");
                }
            } else if (v instanceof List<?> l) {
                if (l.isEmpty()) {
                    h.append("<p class=\"muted\">(empty)</p>");
                } else if (l.stream().allMatch(x -> x instanceof Map)) {
                    Set<String> cols = new LinkedHashSet<>();
                    boolean simple = true;
                    for (Object o : l) {
                        for (var e : ((Map<?, ?>) o).entrySet()) {
                            cols.add(String.valueOf(e.getKey()));
                            if (e.getValue() instanceof Map || e.getValue() instanceof List) simple = false;
                        }
                    }
                    if (simple || l.size() > 5) {
                        tableOfMaps(l, cols);
                    } else {
                        int i = 1;
                        for (Object o : l) {
                            h.append("<details class=\"inner\"><summary>#").append(i++).append("</summary><div class=\"det\">");
                            tree(o, depth + 1);
                            h.append("</div></details>");
                        }
                    }
                } else if (l.stream().allMatch(x -> x instanceof List)) {
                    h.append("<p class=\"muted\">").append(l.size()).append(" samples");
                    h.append(l.size() > 10 ? " (charted above where meaningful). First and last: " : ": ");
                    h.append("</p><div class=\"scroll\"><table class=\"dense\"><tbody>");
                    for (int i = 0; i < l.size(); i++) {
                        if (l.size() > 10 && i == 5) {
                            h.append("<tr><td colspan=\"9\">…</td></tr>");
                            i = l.size() - 5;
                        }
                        h.append("<tr>");
                        for (Object c : (List<?>) l.get(i)) h.append("<td>").append(esc(prettyValue("timestamp", c))).append("</td>");
                        h.append("</tr>");
                    }
                    h.append("</tbody></table></div>");
                } else {
                    h.append("<p>").append(esc(String.join(", ", l.stream().map(String::valueOf).toList()))).append("</p>");
                }
            } else {
                h.append("<p>").append(esc(String.valueOf(v))).append("</p>");
            }
        }

        void tableOfMaps(List<?> l, Set<String> cols) {
            int limit = 300;
            h.append("<div class=\"scroll\"><table class=\"dense\"><thead><tr>");
            for (String c : cols) h.append("<th title=\"").append(esc(c)).append("\">").append(esc(humanize(c))).append("</th>");
            h.append("</tr></thead><tbody>");
            int n = 0;
            for (Object o : l) {
                if (n++ >= limit) break;
                Map<?, ?> m = (Map<?, ?>) o;
                h.append("<tr>");
                for (String c : cols) {
                    Object v = m.get(c);
                    String s = v instanceof Map || v instanceof List ? Json.compact(v, 160) : prettyValue(c, v);
                    h.append("<td>").append(esc(s)).append("</td>");
                }
                h.append("</tr>");
            }
            h.append("</tbody></table></div>");
            if (l.size() > limit) h.append("<p class=\"muted\">… ").append(l.size() - limit).append(" more rows</p>");
        }

        String sizeHint(Object v) {
            if (v instanceof List<?> l) return " <span class=\"muted\">(" + l.size() + ")</span>";
            return "";
        }

        // ---- charts

        String lineChart(String title, String unit, Series s, String color, ZoneId zone, boolean invert, int decimals) {
            return lineChart(title, unit, s, color, zone, invert, decimals, null);
        }

        String lineChart(String title, String unit, Series s, String color, ZoneId zone, boolean invert, int decimals, String field) {
            Series d = s.downsample(700);
            int W = 600, H = 200, L = 40, R = 8, T = 10, B = 22;
            double lo = d.min(), hi = d.max();
            if (hi - lo < 1e-9) {
                lo -= 1;
                hi += 1;
            }
            double[] ticks = niceTicks(lo, hi, 4);
            lo = Math.min(lo, ticks[0]);
            hi = Math.max(hi, ticks[ticks.length - 1]);
            long x0 = d.xs.getFirst(), x1 = d.xs.getLast();
            if (x1 == x0) x1 = x0 + 1;
            final double flo = lo, fhi = hi;
            final long fx0 = x0, fx1 = x1;
            java.util.function.LongToDoubleFunction px = x -> L + (x - fx0) * (double) (W - L - R) / (fx1 - fx0);
            java.util.function.DoubleUnaryOperator py = y -> invert
                    ? T + (y - flo) * (H - T - B) / (fhi - flo)
                    : H - B - (y - flo) * (H - T - B) / (fhi - flo);
            StringBuilder path = new StringBuilder(), area = new StringBuilder(), pts = new StringBuilder("[");
            boolean pen = false;
            double firstX = 0, lastX = 0;
            boolean anySeg = false;
            for (int i = 0; i < d.size(); i++) {
                double y = d.ys.get(i);
                if (Double.isNaN(y)) {
                    if (pen && anySeg) area.append("L").append(fmt1(lastX)).append(",").append(H - B).append("L").append(fmt1(firstX)).append(",").append(H - B).append("Z");
                    pen = false;
                    continue;
                }
                double X = px.applyAsDouble(d.xs.get(i)), Y = py.applyAsDouble(y);
                if (!pen) {
                    path.append("M");
                    area.append("M").append(fmt1(X)).append(",").append(H - B).append("L");
                    firstX = X;
                } else {
                    path.append("L");
                    area.append("L");
                }
                path.append(fmt1(X)).append(",").append(fmt1(Y));
                area.append(fmt1(X)).append(",").append(fmt1(Y));
                lastX = X;
                pen = true;
                anySeg = true;
                if (pts.length() > 1) pts.append(',');
                pts.append('[').append(fmt1(X)).append(',').append(fmt1(Y)).append(",\"")
                        .append(HMS.format(Instant.ofEpochMilli(d.xs.get(i)).atZone(zone))).append(" · ")
                        .append(invert ? fmtPaceMin(y) : fmtNum(y, decimals)).append(' ').append(unit).append("\"]");
            }
            if (pen) area.append("L").append(fmt1(lastX)).append(",").append(H - B).append("Z");
            pts.append(']');
            String id = "c" + (chartSeq++);
            Series valid = s.validOnly();
            String avg = valid.isEmpty() ? "" : invert ? fmtPaceMin(valid.avg()) : fmtNum(valid.avg(), decimals);
            String mx = valid.isEmpty() ? "" : invert ? fmtPaceMin(valid.min()) : fmtNum(valid.max(), decimals);
            String mn = valid.isEmpty() ? "" : invert ? fmtPaceMin(valid.max()) : fmtNum(valid.min(), decimals);
            StringBuilder svg = new StringBuilder("<figure class=\"chart\"><figcaption>").append(esc(title))
                    .append(" <span class=\"muted\">").append(esc(unit)).append(field != null ? " · " + esc(field) : "")
                    .append("</span><span class=\"stats\">min ").append(mn).append(" · avg ").append(avg).append(" · max ").append(mx)
                    .append("</span></figcaption><svg viewBox=\"0 0 ").append(W).append(' ').append(H).append("\" id=\"").append(id)
                    .append("\" data-pts='").append(pts).append("' role=\"img\" aria-label=\"").append(esc(title)).append("\">");
            for (double t : ticks) {
                double y = py.applyAsDouble(t);
                svg.append("<line x1=\"").append(L).append("\" x2=\"").append(W - R).append("\" y1=\"").append(fmt1(y)).append("\" y2=\"").append(fmt1(y))
                        .append("\" class=\"grid\"/><text x=\"").append(L - 6).append("\" y=\"").append(fmt1(y + 4)).append("\" class=\"ax\" text-anchor=\"end\">")
                        .append(invert ? fmtPaceMin(t) : fmtTick(t)).append("</text>");
            }
            for (int i = 0; i <= 6; i++) {
                long t = x0 + (x1 - x0) * i / 6;
                svg.append("<text x=\"").append(fmt1(px.applyAsDouble(t))).append("\" y=\"").append(H - 7).append("\" class=\"ax\" text-anchor=\"")
                        .append(i == 0 ? "start" : i == 6 ? "end" : "middle").append("\">").append(HM.format(Instant.ofEpochMilli(t).atZone(zone))).append("</text>");
            }
            svg.append("<path d=\"").append(area).append("\" fill=\"").append(color).append("\" opacity=\".12\"/>")
                    .append("<path d=\"").append(path).append("\" fill=\"none\" stroke=\"").append(color)
                    .append("\" stroke-width=\"1.6\" stroke-linejoin=\"round\" vector-effect=\"non-scaling-stroke\"/>")
                    .append("<line class=\"cursor\" y1=\"").append(T).append("\" y2=\"").append(H - B).append("\" x1=\"-10\" x2=\"-10\"/>")
                    .append("<circle class=\"dot\" r=\"3.5\" cx=\"-10\" cy=\"-10\" fill=\"").append(color).append("\"/>")
                    .append("</svg></figure>");
            return svg.toString();
        }
    }

    // ------------------------------------------------------------------ overview

    /** Rebuilds {@code garmin/index.html} from the summaries embedded in every day report. */
    static final class Overview {
        private static final Pattern REPORT_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-report\\.html");

        record Entry(LocalDate date, String href, Map<String, Object> summary) {}

        /** Summaries of all reports, kept after the first scan so {@link #update} only reads the changed day. */
        private static TreeMap<LocalDate, Entry> cache;

        /** Rebuilds the overview from every report on disk. */
        static Path write() throws IOException {
            cache = new TreeMap<>();
            if (Files.isDirectory(DATA_DIR)) {
                try (Stream<Path> s = Files.walk(DATA_DIR)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        if (!REPORT_NAME.matcher(p.getFileName().toString()).matches()) continue;
                        LocalDate d = LocalDate.parse(p.getFileName().toString().substring(0, 10));
                        cache.put(d, entry(d, p));
                    }
                }
            }
            return render();
        }

        /** Updates the overview for one day whose report was written or removed; scans everything the first time. */
        static Path update(LocalDate date) throws IOException {
            if (cache == null) return write();
            Path p = reportPath(date);
            if (Files.exists(p)) cache.put(date, entry(date, p));
            else cache.remove(date);
            return render();
        }

        private static Entry entry(LocalDate d, Path p) {
            return new Entry(d, DATA_DIR.relativize(p).toString().replace('\\', '/'), readSummary(p));
        }

        private static Path render() throws IOException {
            List<Entry> entries = new ArrayList<>(cache.descendingMap().values());
            Path out = DATA_DIR.resolve(OVERVIEW_FILE);
            Files.createDirectories(DATA_DIR);
            Path tmp = out.resolveSibling(OVERVIEW_FILE + ".tmp");
            Files.writeString(tmp, render(entries), StandardCharsets.UTF_8);
            // Replace in one step, so a browser refreshing during a download never sees half a page.
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            // The list of all reports, oldest first, for the previous/next day links in the reports.
            StringBuilder js = new StringBuilder("window.GARMIN_DAYS=[");
            for (Entry e : cache.values()) js.append(js.charAt(js.length() - 1) == '[' ? "" : ",").append(jsStr(e.href));
            Files.writeString(DATA_DIR.resolve(DAYS_FILE), js.append("];\n"), StandardCharsets.UTF_8);
            return out;
        }

        private static Map<String, Object> readSummary(Path p) {
            try {
                // The summary sits in <head>, so the first part of the file is enough (reports can be several MB).
                String html;
                try (var in = Files.newInputStream(p)) {
                    html = new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
                }
                int i = html.indexOf(SUMMARY_START);
                if (i < 0) return Map.of();
                int j = html.indexOf("</script>", i);
                if (j < 0) return Map.of();
                Map<String, Object> m = asMap(Json.parse(html.substring(i + SUMMARY_START.length(), j).replace("<\\/", "</")));
                return m == null ? Map.of() : m;
            } catch (Exception e) {
                System.err.println("Could not read summary from " + p + ": " + e.getMessage());
                return Map.of();
            }
        }

        private static String render(List<Entry> entries) {
            StringBuilder h = new StringBuilder();
            h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                    .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                    .append("<title>Garmin reports · ").append(esc(accountLabel(account))).append("</title><style>").append(CSS).append("</style></head><body><main>")
                    .append("<header><div class=\"eyebrow\">Garmin day reports · ").append(esc(accountLabel(account))).append("</div>")
                    .append(accountLinks()).append("<h1>Overview</h1><p class=\"sub\">")
                    .append(entries.size()).append(" days · updated ").append(DT.format(LocalDateTime.now()))
                    .append(" · regenerated every time DescribeGarminFile writes a report</p></header>");

            Totals all = new Totals();
            entries.forEach(all::add);
            h.append("<div class=\"tiles\" id=\"totals\">");
            all.tiles(h);
            h.append("</div>");
            h.append("<div class=\"filters\"><input class=\"filter\" id=\"q\" type=\"search\" ")
                    .append("placeholder=\"Text, e.g. Zwembad, 2026-09…\" oninput=\"filterRows()\">")
                    .append("<select id=\"sport\" onchange=\"autoUnit();filterRows()\"><option value=\"\">All activities</option>");
            for (var g : all.byCount()) {
                h.append("<option value=\"").append(esc(g.getKey())).append("\">").append(esc(sportName(g.getKey())))
                        .append(" (").append(g.getValue().values().stream().mapToInt(Integer::intValue).sum()).append(")</option>");
                // A group of several Garmin types can also be narrowed to one of them ("t:<type>").
                if (g.getValue().size() > 1) for (var t : Totals.typesByCount(g.getValue()))
                    h.append("<option value=\"t:").append(esc(t.getKey())).append("\">\u2003– ").append(esc(sportName(t.getKey())))
                            .append(" (").append(t.getValue()).append(")</option>");
            }
            h.append("</select><label>Distance <input id=\"dmin\" type=\"text\" inputmode=\"decimal\" placeholder=\"min\" oninput=\"filterRows()\">")
                    .append(" – <input id=\"dmax\" type=\"text\" inputmode=\"decimal\" placeholder=\"max\" oninput=\"filterRows()\"> km</label>")
                    .append("<label>Speed <input id=\"smin\" type=\"text\" inputmode=\"decimal\" placeholder=\"min\" oninput=\"filterRows()\">")
                    .append(" – <input id=\"smax\" type=\"text\" inputmode=\"decimal\" placeholder=\"max\" oninput=\"filterRows()\"> ")
                    // Pace units for running/walking (min/km) and swimming (min/100 m); "5:00" or "5.0" both work.
                    .append("<select id=\"sunit\" onchange=\"updUnit();filterRows()\"><option value=\"kmh\">km/h</option>")
                    .append("<option value=\"km\">min/km</option><option value=\"100\">min/100 m</option></select></label>")
                    .append("<label>From <input id=\"dfrom\" type=\"date\" onchange=\"filterRows()\"></label>")
                    .append("<label>to <input id=\"dto\" type=\"date\" onchange=\"filterRows()\"></label><span class=\"ranges\">")
                    .append("<a href=\"#\" data-range=\"30\" onclick=\"return setRange('30')\">Last 30 days</a>")
                    .append("<a href=\"#\" data-range=\"month\" onclick=\"return setRange('month')\">This month</a>")
                    .append("<a href=\"#\" data-range=\"year\" onclick=\"return setRange('year')\">This year</a>")
                    .append("<a href=\"#\" data-range=\"lastyear\" onclick=\"return setRange('lastyear')\">Last year</a></span>")
                    .append("<span id=\"filterStatus\"></span></div>");

            // Filled in by the page script from the visible rows, so the records follow the filters.
            // The chart, calendar and records are folded away by default; these buttons open them.
            h.append("<div class=\"ranges panels\"><a href=\"#\" data-panel=\"trend\" onclick=\"return togglePanel('trend')\">📊 Chart</a>")
                    .append("<a href=\"#\" data-panel=\"calendar\" onclick=\"return togglePanel('calendar')\">📅 Calendar</a>")
                    .append("<a href=\"#\" data-panel=\"records\" onclick=\"return togglePanel('records')\">🏆 Records</a></div>");
            h.append("<section class=\"card panel folded\" id=\"trend\" hidden><div class=\"trend-head\"><h2 id=\"trendTitle\">Per month</h2>")
                    .append("<span class=\"ranges\" id=\"trendMetric\"><a href=\"#\" data-m=\"dist\" onclick=\"return setMetric('dist')\">Distance</a>")
                    .append("<a href=\"#\" data-m=\"dur\" onclick=\"return setMetric('dur')\">Time</a>")
                    .append("<a href=\"#\" data-m=\"n\" onclick=\"return setMetric('n')\">Activities</a></span></div>")
                    .append("<p class=\"sub\" id=\"trendSub\"></p><div class=\"legend\" id=\"trendLegend\"></div><div id=\"trendChart\"></div></section>");
            h.append("<section class=\"card panel folded\" id=\"calendar\" hidden><div class=\"trend-head\"><h2 id=\"calTitle\">Calendar</h2>")
                    .append("<span class=\"ranges\"><a href=\"#\" id=\"calPrev\" onclick=\"return stepCal(-1)\">‹</a>")
                    .append("<a href=\"#\" id=\"calNext\" onclick=\"return stepCal(1)\">›</a></span></div>")
                    .append("<p class=\"sub\" id=\"calSub\"></p><div class=\"legend\" id=\"calLegend\"></div><div id=\"calChart\"></div></section>");
            h.append("<section class=\"card panel folded\" id=\"records\" hidden><h2>Records</h2><p class=\"sub\" id=\"recordsSub\"></p>")
                    .append("<div class=\"tiles\" id=\"recordTiles\"></div></section>");
            Map<String, List<Entry>> byMonth = new LinkedHashMap<>();
            for (Entry e : entries) byMonth.computeIfAbsent(e.date.toString().substring(0, 7), k -> new ArrayList<>()).add(e);
            for (var m : byMonth.entrySet()) {
                LocalDate first = LocalDate.parse(m.getKey() + "-01");
                Totals t = new Totals();
                m.getValue().forEach(t::add);
                h.append("<section class=\"card month\"><h2>").append(first.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                        .append(" ").append(first.getYear()).append("</h2><p class=\"sub\">").append(t.line()).append("</p>")
                        .append("<div class=\"scroll\"><table><thead><tr><th>Date</th><th>Day</th><th>Activities</th><th>Distance</th><th>Pace / speed</th>")
                        .append("<th>Activity time</th><th>Activity kcal</th><th>Steps</th><th>Sleep score</th><th>Sleep</th>")
                        .append("<th>Resting HR</th><th>HRV</th><th>Body battery max</th><th>Avg stress</th></tr></thead><tbody>");
                for (Entry e : m.getValue()) row(h, e);
                h.append("</tbody></table></div></section>");
            }
            h.append("<footer>Click a column header to sort. A distance marked ≈ is a corrected pool-swim distance ")
                    .append("(recounted from the stroke count; hover for Garmin's value, details in the day report). Reports live in <code>").append(esc(DATA_DIR.toString()))
                    .append("</code>.</footer></main><div id=\"tip\"></div><script>var FILTER_KEY=").append(jsStr(filterKey())).append(";").append(JS)
                    .append(OVERVIEW_JS)
                    .append("</script></body></html>");
            return h.toString();
        }

        /** Links to the other accounts' overviews (folders garmin and garmin-* next to this one), when there are any. */
        private static String accountLinks() {
            List<String> names = new ArrayList<>();
            try (Stream<Path> s = Files.list(RESOURCES)) {
                s.map(p -> p.getFileName().toString()).filter(n -> n.equals("garmin") || n.matches("garmin-[a-z0-9_-]+"))
                        .filter(n -> Files.exists(RESOURCES.resolve(n).resolve(OVERVIEW_FILE)) || RESOURCES.resolve(n).equals(DATA_DIR))
                        .sorted().forEach(n -> names.add(n.equals("garmin") ? MAIN_ACCOUNT : n.substring(7)));
            } catch (IOException e) {
                return "";
            }
            if (names.size() < 2) return "";
            StringBuilder h = new StringBuilder("<div class=\"ranges accounts\">");
            for (String n : names) {
                String href = "../" + dataDir(n).getFileName() + "/" + OVERVIEW_FILE;
                h.append("<a href=\"").append(esc(href)).append("\"").append(n.equals(account) ? " class=\"on\"" : "").append(">")
                        .append(esc(accountLabel(n))).append("</a>");
            }
            return h.append("</div>").toString();
        }

        private static void row(StringBuilder h, Entry e) {
            Map<String, Object> s = e.summary;
            List<Object> acts = shown(s);
            double dist = 0, time = 0, kcal = 0;
            StringBuilder names = new StringBuilder("<div class=\"acts stack\">");
            // One line per activity, aligned with the activity names, instead of a day total.
            StringBuilder dists = new StringBuilder("<div class=\"stack dists\">");
            StringBuilder paces = new StringBuilder("<div class=\"stack paces\">");
            for (int ai = 0; ai < acts.size(); ai++) {
                Object o = acts.get(ai);
                dist += numOr(distanceOf(o), 0);
                time += numOr(at(o, "duration"), 0);
                kcal += numOr(at(o, "calories"), 0);
                Double d = distanceOf(o), dur = num(at(o, "duration")), garmin = num(at(o, "distance"));
                boolean corrected = num(at(o, "correctedDistance")) != null;
                // Garmin's averageSpeed is exactly distance / (timer) duration, so it can be derived here.
                double kmh = d != null && dur != null && d > 0 && dur > 0 ? d / dur * 3.6 : 0;
                // Links to the activity's section in the day report (sections are #act0, #act1, … in the same order).
                names.append("<a class=\"act\" href=\"").append(esc(e.href)).append("#act").append(ai)
                        .append("\" data-sport=\"").append(esc(str(at(o, "sport")))).append("\" data-group=\"")
                        .append(esc(sportGroup(str(at(o, "sport"))))).append("\" data-dist=\"")
                        .append(d == null ? 0 : Math.round(d)).append("\" data-kmh=\"").append(Math.round(kmh * 100) / 100.0)
                        .append("\" data-dur=\"").append(dur == null ? 0 : Math.round(dur))
                        .append(NOT_A_RECORD.contains(e.date + " " + str(at(o, "start"))) ? "\" data-norec=\"1" : "")
                        .append("\" title=\"").append(esc(sportName(str(at(o, "sport")))))
                        .append(kmh > 0 ? " · " + fmtNum(kmh, 1) + " km/h" : "").append("\">")
                        .append(sportIcon(str(at(o, "sport")))).append(" ").append(esc(str(at(o, "name")))).append("</a>");
                dists.append(d == null || d <= 0 ? "<span class=\"muted\">–</span>"
                        : corrected ? "<span class=\"corr\" title=\"Corrected from the stroke count; Garmin: "
                                      + (garmin == null ? "–" : fmtDist(garmin)) + "\">≈ " + fmtDist(d) + "</span>"
                        : "<span>" + fmtDist(d) + "</span>");
                paces.append("<span>").append(kmh > 0 ? esc(paceOrSpeed(str(at(o, "sport")), kmh)) : "<span class=\"muted\">–</span>").append("</span>");
            }
            names.append("</div>");
            dists.append("</div>");
            paces.append("</div>");
            Double steps = num(s.get("steps"));
            h.append("<tr data-date=\"").append(e.date).append("\" data-steps=\"").append(steps == null ? 0 : Math.round(steps))
                    .append("\"><td><a href=\"").append(esc(e.href)).append("\">").append(e.date).append("</a></td><td>")
                    .append(e.date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).append("</td><td>")
                    .append(acts.isEmpty() ? "<span class=\"muted\">rest day</span>" : names).append("</td><td class=\"num\"")
                    .append(dist > 0 ? " data-value=\"" + Math.round(dist) + "\">" + dists : ">").append("</td><td class=\"num\">")
                    .append(dist > 0 ? paces : "").append("</td><td class=\"num\">").append(time > 0 ? fmtDur(time) : "")
                    .append("</td><td class=\"num\">").append(kcal > 0 ? fmtNum(kcal, 0) : "").append("</td><td class=\"num\">")
                    .append(cell(s, "steps", 0)).append("</td><td class=\"num\">").append(cell(s, "sleepScore", 0))
                    .append("</td><td class=\"num\">").append(num(s.get("sleepSeconds")) == null ? "" : fmtDur(num(s.get("sleepSeconds"))))
                    .append("</td><td class=\"num\">").append(cell(s, "restingHR", 0)).append("</td><td class=\"num\">")
                    .append(cell(s, "hrv", 0)).append("</td><td class=\"num\">").append(cell(s, "bodyBatteryHigh", 0))
                    .append("</td><td class=\"num\">").append(cell(s, "avgStress", 0)).append("</td></tr>");
        }

        /** Pace for sports where that is how you think about it (min/km, min/100 m), else km/h. */
        static String paceOrSpeed(String sport, double kmh) {
            String g = sportGroup(sport);
            if (g.equals("running") || g.equals("walking")) return fmtPace(kmh / 3.6, false);
            if (g.equals("swimming")) return fmtPace(kmh / 3.6, true);
            return fmtNum(kmh, 1) + " km/h";
        }

        /**
         * Garmin activity types that the overview counts together. Garmin's own parent types would put indoor and
         * outdoor rides in one group, so this follows how the rides are actually done; the data keeps Garmin's type.
         */
        private static final Map<String, String> SPORT_GROUPS = Map.ofEntries(
                Map.entry("road_biking", "cycling"),
                Map.entry("mountain_biking", "cycling"),
                Map.entry("virtual_ride", "indoor_cycling"),
                Map.entry("casual_walking", "walking"),
                Map.entry("hiking", "walking"),
                Map.entry("steps", "walking"),
                Map.entry("rowing_v2", "rowing_and_paddling"),
                Map.entry("kayaking_v2", "rowing_and_paddling"),
                Map.entry("paddling_v2", "rowing_and_paddling"),
                Map.entry("whitewater_rafting_v2", "rowing_and_paddling"),
                Map.entry("track_running", "running"),
                Map.entry("trail_running", "running"),
                Map.entry("obstacle_run", "running"),
                Map.entry("street_running", "running"),
                Map.entry("badminton", "racket_sports"),
                Map.entry("table_tennis", "racket_sports"),
                Map.entry("multi_sport", "others"),
                Map.entry("breathwork", "others"),
                Map.entry("lap_swimming", "swimming"),
                Map.entry("open_water_swimming", "swimming"));

        /**
         * Activities ("date start") left out of the records only, because their numbers are not real; they still count in
         * the totals. 2012-12-13 20:33: a 53 km "run" of 8:53, not an actual run (confirmed by the user);
         * 2012-12-17 13:02: the same pattern (46 km in 8:48, also just named "running").
         */
        private static final Set<String> NOT_A_RECORD = Set.of("2012-12-13 20:33", "2012-12-17 13:02");

        /**
         * Activity types left out of the overview (tiles, totals and rows); the day report still shows them.
         * overland: a single 5-second recording without distance (2025-02-27), started by accident.
         */
        private static final Set<String> HIDDEN_SPORTS = Set.of("overland");

        /** The day's activities as the overview counts them. */
        private static List<Object> shown(Map<String, Object> summary) {
            List<Object> acts = Optional.ofNullable(asList(summary.get("activities"))).orElse(List.of());
            return acts.stream().filter(o -> !HIDDEN_SPORTS.contains(str(at(o, "sport")))).toList();
        }

        /** Garmin's sport key for display, without its version suffix (kayaking_v2 → Kayaking). */
        static String sportName(String sport) {
            return humanize(sport.replaceAll("_v\\d+$", ""));
        }

        static String sportGroup(String sport) {
            return SPORT_GROUPS.getOrDefault(sport, sport);
        }

        /** The corrected distance where one exists (pool swims), else Garmin's. */
        private static Double distanceOf(Object activity) {
            Double c = num(at(activity, "correctedDistance"));
            return c != null ? c : num(at(activity, "distance"));
        }

        private static String cell(Map<String, Object> s, String key, int decimals) {
            Double v = num(s.get(key));
            return v == null ? "" : fmtNum(v, decimals);
        }

        static final class Totals {
            int days, activeDays, activities;
            double dist, time, steps;
            /** Overview group → Garmin activity type → count. */
            Map<String, Map<String, Integer>> groups = new TreeMap<>();

            int count(String group) {
                return groups.get(group).values().stream().mapToInt(Integer::intValue).sum();
            }

            /** Groups with the most activities first (ties by name). */
            List<Map.Entry<String, Map<String, Integer>>> byCount() {
                return groups.entrySet().stream()
                        // "others" is the catch-all, so it always comes last.
                        .sorted(Comparator.<Map.Entry<String, Map<String, Integer>>, Boolean>comparing(g -> g.getKey().equals("others"))
                                .thenComparingInt(g -> -count(g.getKey()))
                                .thenComparing(Map.Entry::getKey))
                        .toList();
            }

            /** A group's Garmin types, most activities first. */
            static List<Map.Entry<String, Integer>> typesByCount(Map<String, Integer> types) {
                return types.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                        .toList();
            }

            void add(Entry e) {
                days++;
                List<Object> acts = shown(e.summary);
                if (!acts.isEmpty()) activeDays++;
                for (Object o : acts) {
                    activities++;
                    dist += numOr(distanceOf(o), 0);
                    time += numOr(at(o, "duration"), 0);
                    String sp = str(at(o, "sport"));
                    if (!sp.isEmpty()) groups.computeIfAbsent(sportGroup(sp), k -> new TreeMap<>()).merge(sp, 1, Integer::sum);
                }
                steps += numOr(e.summary.get("steps"), 0);
            }

            String line() {
                StringBuilder s = new StringBuilder();
                s.append(days).append(" days · ").append(activities).append(" activities · ").append(fmtDist(dist)).append(" · ").append(fmtDur(time));
                if (!groups.isEmpty()) {
                    s.append(" · ");
                    groups.keySet().stream().sorted((a, b) -> count(b) - count(a))
                            .forEach(g -> s.append(sportIcon(g)).append(" ").append(count(g)).append("  "));
                }
                return s.toString().trim();
            }

            void tiles(StringBuilder h) {
                // The ids let the filter show the totals of what is selected.
                tile(h, String.valueOf(days), "Days with a report", "tDays");
                tile(h, String.valueOf(activities), "Activities", "tActs");
                tile(h, fmtDist(dist), "Total distance", "tDist");
                tile(h, fmtDur(time), "Total activity time", "tTime");
                if (steps > 0) tile(h, fmtNum(steps, 0), "Steps", "tSteps");
                // The clickable activity tiles get their own grid, so they always start on a new row.
                h.append("</div><div class=\"tiles sports\">");
                for (var g : byCount()) {
                    String key = g.getKey();
                    h.append("<a class=\"tile link\" href=\"#sport=").append(esc(key)).append("\" data-sport=\"").append(esc(key))
                            .append("\" data-types=\"").append(esc(String.join(" ", g.getValue().keySet())))
                            .append("\" title=\"Show only ").append(esc(sportName(key)))
                            .append(" activities\" onclick=\"return toggleSport(this.dataset.sport)\"><div class=\"v\">")
                            .append(esc(sportIcon(key))).append(" <span class=\"n\">").append(count(key)).append("</span></div><div class=\"l\">").append(esc(sportName(key)));
                    if (g.getValue().size() > 1) {
                        h.append("<small>");
                        for (var t : typesByCount(g.getValue()))
                            h.append("<br><span data-type=\"").append(esc(t.getKey())).append("\">").append(t.getValue()).append("</span> ")
                                    .append(esc(sportName(t.getKey()).toLowerCase(Locale.ROOT)));
                        h.append("</small>");
                    }
                    h.append("</div></a>");
                }
            }

            private static void tile(StringBuilder h, String v, String l, String id) {
                h.append("<div class=\"tile\"><div class=\"v\" id=\"").append(id).append("\">").append(esc(v)).append("</div><div class=\"l\">").append(esc(l)).append("</div></div>");
            }
        }
    }

    // ------------------------------------------------------------------ series

    static final class Series {
        final List<Long> xs = new ArrayList<>();
        final List<Double> ys = new ArrayList<>();

        void add(double x, Double y) {
            add((long) x, y);
        }

        void add(long x, Double y) {
            if (y == null) return;
            xs.add(x);
            ys.add(y);
        }

        int size() {
            return xs.size();
        }

        boolean isEmpty() {
            return validOnly().xs.isEmpty();
        }

        Series validOnly() {
            Series s = new Series();
            for (int i = 0; i < size(); i++) if (!Double.isNaN(ys.get(i))) s.add(xs.get(i), ys.get(i));
            return s;
        }

        Series positiveOnly() {
            Series s = new Series();
            for (int i = 0; i < size(); i++) s.add(xs.get(i), ys.get(i) < 0 ? Double.NaN : ys.get(i));
            return s;
        }

        Series clip(double lo, double hi) {
            Series s = new Series();
            for (int i = 0; i < size(); i++) {
                double y = ys.get(i);
                s.add(xs.get(i), y < lo || y > hi ? Double.NaN : y);
            }
            return s;
        }

        double min() {
            return ys.stream().filter(v -> !Double.isNaN(v)).mapToDouble(Double::doubleValue).min().orElse(0);
        }

        double max() {
            return ys.stream().filter(v -> !Double.isNaN(v)).mapToDouble(Double::doubleValue).max().orElse(0);
        }

        double avg() {
            return ys.stream().filter(v -> !Double.isNaN(v)).mapToDouble(Double::doubleValue).average().orElse(0);
        }

        double range() {
            return max() - min();
        }

        /** Averages into buckets; a bucket with only gaps stays a gap. Also splits on long time gaps. */
        Series downsample(int buckets) {
            Series out = new Series();
            if (size() == 0) return out;
            long x0 = xs.getFirst(), x1 = xs.getLast();
            long medianStep = size() > 2 ? Math.max(1, (x1 - x0) / (size() - 1)) : 1;
            if (size() <= buckets) {
                for (int i = 0; i < size(); i++) {
                    if (i > 0 && xs.get(i) - xs.get(i - 1) > Math.max(medianStep * 10, 600_000)) out.add(xs.get(i - 1) + 1, Double.NaN);
                    out.add(xs.get(i), ys.get(i));
                }
                return out;
            }
            double width = (x1 - x0 + 1) / (double) buckets;
            int i = 0;
            for (int b = 0; b < buckets; b++) {
                long end = (long) (x0 + (b + 1) * width);
                double sum = 0;
                int n = 0, total = 0;
                long sx = 0;
                while (i < size() && xs.get(i) < end) {
                    total++;
                    if (!Double.isNaN(ys.get(i))) {
                        sum += ys.get(i);
                        sx += xs.get(i) - x0;
                        n++;
                    }
                    i++;
                }
                if (total == 0) {
                    // No samples in this bucket (pause, watch off): break the line instead of bridging it.
                    if (out.size() > 0 && !Double.isNaN(out.ys.getLast())) out.add(end, Double.NaN);
                    continue;
                }
                if (n == 0) out.add(end, Double.NaN);
                else out.add(x0 + sx / n, sum / n);
            }
            return out;
        }
    }

    /** Converts a Garmin [[timestamp, ..., value], ...] array into a series. */
    private static Series pairs(Object arr, int valueIndex) {
        Series s = new Series();
        List<Object> l = asList(arr);
        if (l == null) return s;
        for (Object o : l) {
            List<Object> row = asList(o);
            if (row == null || row.size() <= valueIndex) continue;
            Double t = num(row.getFirst());
            Double v = num(row.get(valueIndex));
            if (t != null) s.add(t.longValue(), v == null ? Double.NaN : v);
        }
        return s;
    }

    /** Lat/long track from the FIT records, falling back to GPX. */
    private static List<double[]> track(Activity a) {
        if (a.fit != null) {
            List<double[]> t = fitTrack(a.fit);
            if (!t.isEmpty()) return t;
        }
        List<double[]> t = new ArrayList<>();
        if (a.gpx != null) {
            for (Element p : a.gpx.all("trkpt")) {
                Double lat = parseD(p.getAttribute("lat")), lon = parseD(p.getAttribute("lon"));
                if (lat != null && lon != null) t.add(new double[]{lat, lon});
            }
        }
        return t;
    }

    /** Lat/long points of the FIT records; cached on the file itself so it is freed together with the day. */
    private static List<double[]> fitTrack(FitFile f) {
        if (f.track == null) {
            List<double[]> t = new ArrayList<>();
            for (FitMessage r : f.messages(20)) {
                Double lat = r.scaled(0), lon = r.scaled(1);
                if (lat != null && lon != null) t.add(new double[]{lat, lon});
            }
            f.track = t;
        }
        return f.track;
    }

    private static String tcxSummary(Xml x) {
        List<Element> tps = x.all("Trackpoint");
        int pos = 0, hr = 0, alt = 0, cad = 0, spd = 0, watts = 0;
        for (Element tp : tps) {
            if (Xml.child(tp, "Position") != null) pos++;
            if (Xml.child(tp, "HeartRateBpm") != null) hr++;
            if (Xml.child(tp, "AltitudeMeters") != null) alt++;
            if (Xml.child(tp, "Cadence") != null) cad++;
            if (!Xml.textDeep(tp, "Speed").isEmpty()) spd++;
            if (!Xml.textDeep(tp, "Watts").isEmpty()) watts++;
        }
        StringBuilder s = new StringBuilder();
        s.append(x.all("Lap").size()).append(" laps, ").append(tps.size()).append(" trackpoints");
        if (!tps.isEmpty()) {
            s.append(" (with position ").append(pos).append(", heart rate ").append(hr).append(", altitude ").append(alt)
                    .append(", speed ").append(spd).append(", cadence ").append(cad).append(", power ").append(watts).append(")");
        }
        Element c = x.first("Creator");
        if (c != null) s.append(", device ").append(Xml.text(c, "Name"));
        return s.toString();
    }

    private static String gpxSummary(Xml x) {
        List<Element> pts = x.all("trkpt");
        int hr = 0, cad = 0, temp = 0, ele = 0;
        double minE = Double.MAX_VALUE, maxE = -Double.MAX_VALUE;
        for (Element p : pts) {
            if (!Xml.textDeep(p, "hr").isEmpty()) hr++;
            if (!Xml.textDeep(p, "cad").isEmpty()) cad++;
            if (!Xml.textDeep(p, "atemp").isEmpty()) temp++;
            Double e = parseD(Xml.text(p, "ele"));
            if (e != null) {
                ele++;
                minE = Math.min(minE, e);
                maxE = Math.max(maxE, e);
            }
        }
        StringBuilder s = new StringBuilder();
        s.append(x.all("trk").size()).append(" track(s), ").append(pts.size()).append(" GPS points");
        if (!pts.isEmpty()) {
            s.append(" (elevation ").append(ele).append(", heart rate ").append(hr).append(", cadence ").append(cad)
                    .append(", temperature ").append(temp).append(")");
            if (ele > 0) s.append(", elevation ").append(fmtNum(minE, 0)).append("–").append(fmtNum(maxE, 0)).append(" m");
            String t0 = Xml.text(pts.getFirst(), "time"), t1 = Xml.text(pts.getLast(), "time");
            if (!t0.isEmpty() && !t1.isEmpty()) s.append(", ").append(t0).append(" → ").append(t1);
        } else {
            s.append(" (no GPS — e.g. pool swim or indoor)");
        }
        return s.toString();
    }

    // ------------------------------------------------------------------ XML

    static final class Xml {
        final Document doc;

        Xml(Document doc) {
            this.doc = doc;
        }

        static Xml load(Path p) throws Exception {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setExpandEntityReferences(false);
            return new Xml(f.newDocumentBuilder().parse(p.toFile()));
        }

        List<Element> all(String local) {
            NodeList nl = doc.getElementsByTagNameNS("*", local);
            List<Element> out = new ArrayList<>(nl.getLength());
            for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
            return out;
        }

        Element first(String local) {
            NodeList nl = doc.getElementsByTagNameNS("*", local);
            return nl.getLength() == 0 ? null : (Element) nl.item(0);
        }

        static Element child(Element e, String local) {
            for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element c && local.equals(c.getLocalName())) return c;
            }
            return null;
        }

        static String text(Element e, String local) {
            Element c = child(e, local);
            return c == null ? "" : c.getTextContent().trim();
        }

        static String textDeep(Element e, String local) {
            NodeList nl = e.getElementsByTagNameNS("*", local);
            return nl.getLength() == 0 ? "" : nl.item(0).getTextContent().trim();
        }
    }

    // ------------------------------------------------------------------ JSON

    /** Small recursive-descent JSON parser producing Map / List / String / Long / Double / Boolean / null. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json j = new Json(s);
            Object v = j.value();
            j.ws();
            if (j.i != s.length()) throw new IllegalArgumentException("Trailing data at " + j.i);
            return v;
        }

        static String compact(Object v, int max) {
            StringBuilder b = new StringBuilder();
            write(b, v);
            return b.length() > max ? b.substring(0, max) + "…" : b.toString();
        }

        private static void write(StringBuilder b, Object v) {
            if (v instanceof Map<?, ?> m) {
                b.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) b.append(", ");
                    first = false;
                    b.append(e.getKey()).append(": ");
                    write(b, e.getValue());
                }
                b.append('}');
            } else if (v instanceof List<?> l) {
                b.append('[');
                for (int k = 0; k < l.size(); k++) {
                    if (k > 0) b.append(", ");
                    write(b, l.get(k));
                }
                b.append(']');
            } else b.append(v);
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(i);
            switch (c) {
                case '{' -> {
                    i++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    ws();
                    if (s.charAt(i) == '}') {
                        i++;
                        return m;
                    }
                    while (true) {
                        ws();
                        String k = string();
                        ws();
                        expect(':');
                        m.put(k, value());
                        ws();
                        if (s.charAt(i) == ',') i++;
                        else {
                            expect('}');
                            return m;
                        }
                    }
                }
                case '[' -> {
                    i++;
                    List<Object> l = new ArrayList<>();
                    ws();
                    if (s.charAt(i) == ']') {
                        i++;
                        return l;
                    }
                    while (true) {
                        l.add(value());
                        ws();
                        if (s.charAt(i) == ',') i++;
                        else {
                            expect(']');
                            return l;
                        }
                    }
                }
                case '"' -> {
                    return string();
                }
                default -> {
                    if (s.startsWith("true", i)) {
                        i += 4;
                        return Boolean.TRUE;
                    }
                    if (s.startsWith("false", i)) {
                        i += 5;
                        return Boolean.FALSE;
                    }
                    if (s.startsWith("null", i)) {
                        i += 4;
                        return null;
                    }
                    int st = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                    String n = s.substring(st, i);
                    if (n.isEmpty()) throw new IllegalArgumentException("Unexpected '" + c + "' at " + st);
                    if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
                        try {
                            return Long.parseLong(n);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    return Double.parseDouble(n);
                }
            }
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("Expected '" + c + "' at " + i);
            i++;
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : null;
    }

    private static Map<String, Object> firstValue(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return null;
        return asMap(m.values().iterator().next());
    }

    private static Object at(Object root, String... path) {
        Object cur = root;
        for (String p : path) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(p);
        }
        return cur;
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return null;
    }

    private static double numOr(Object o, double def) {
        Double d = num(o);
        return d == null ? def : d;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    // ------------------------------------------------------------------ FIT

    record FitFieldDef(int num, int size, int baseType) {}

    record FitDevFieldDef(int num, int size, int devIndex) {}

    record FitDef(int global, boolean bigEndian, List<FitFieldDef> fields, List<FitDevFieldDef> devFields) {}

    record DevField(String name, int baseType, String units, double scale, double offset) {}

    static final class FitMessage {
        final int global;
        final Map<Integer, Object> fields = new LinkedHashMap<>();
        final Map<String, Object> dev = new LinkedHashMap<>();

        FitMessage(int global) {
            this.global = global;
        }

        boolean has(int f) {
            return fields.containsKey(f);
        }

        /** Raw (unscaled) numeric value, or null. */
        Double num(int f) {
            Object v = fields.get(f);
            return v instanceof Number n ? n.doubleValue() : null;
        }

        /** Value with profile scale/offset applied (semicircles become degrees). */
        Double scaled(int f) {
            Double raw = num(f);
            if (raw == null) return null;
            FitProfile.F pf = FitProfile.field(global, f);
            if (pf == null) return raw;
            if (pf.type().equals("semi")) return raw * (180.0 / 2147483648.0);
            return raw / pf.scale() - pf.offset();
        }

        double[] scaledArray(int f) {
            Object v = fields.get(f);
            FitProfile.F pf = FitProfile.field(global, f);
            double scale = pf == null ? 1 : pf.scale(), off = pf == null ? 0 : pf.offset();
            if (v instanceof Number n) return new double[]{n.doubleValue() / scale - off};
            if (v instanceof double[] a) {
                double[] r = new double[a.length];
                for (int k = 0; k < a.length; k++) r[k] = Double.isNaN(a[k]) ? 0 : a[k] / scale - off;
                return r;
            }
            return null;
        }

        /** Human-readable value with units, enum names and dates. */
        String display(int f) {
            Object v = fields.get(f);
            if (v == null) return "";
            FitProfile.F pf = FitProfile.field(global, f);
            if (v instanceof String s) return s;
            if (v instanceof double[] a) {
                StringBuilder b = new StringBuilder();
                for (int k = 0; k < a.length; k++) {
                    if (k > 0) b.append(", ");
                    b.append(Double.isNaN(a[k]) ? "–" : formatOne(pf, a[k], false));
                }
                if (pf != null && !pf.units().isEmpty()) b.append(' ').append(pf.units());
                return b.toString();
            }
            if (v instanceof byte[] bytes) {
                StringBuilder b = new StringBuilder("0x");
                for (byte x : bytes) b.append(String.format("%02x", x));
                return b.toString();
            }
            return formatOne(pf, ((Number) v).doubleValue(), true);
        }

        private static String formatOne(FitProfile.F pf, double raw, boolean withUnits) {
            if (pf == null) return trimNum(raw);
            switch (pf.type()) {
                case "time" -> {
                    return raw < 0x10000000 ? trimNum(raw) + " s" : DT.format(fitInstant(raw).atZone(ZoneId.systemDefault()));
                }
                case "localtime" -> {
                    return DT.format(LocalDateTime.ofEpochSecond((long) raw + 631065600L, 0, ZoneOffset.UTC));
                }
                case "semi" -> {
                    return String.format(Locale.ROOT, "%.6f°", raw * (180.0 / 2147483648.0));
                }
                case "dur" -> {
                    return fmtDur(raw / pf.scale());
                }
                default -> {
                    if (pf.type().startsWith("enum:")) {
                        Map<Integer, String> m = FitProfile.ENUMS.get(pf.type().substring(5));
                        String n = m == null ? null : m.get((int) raw);
                        return n != null ? n : trimNum(raw);
                    }
                }
            }
            double v = raw / pf.scale() - pf.offset();
            String s = trimNum(v);
            return withUnits && !pf.units().isEmpty() ? s + " " + pf.units() : s;
        }
    }

    static final class FitFile {
        int protocol;
        int profile;
        final List<FitMessage> messages = new ArrayList<>();
        final Map<String, DevField> developerFields = new LinkedHashMap<>();
        private final Map<Integer, List<FitMessage>> index = new HashMap<>();
        List<double[]> track;

        List<FitMessage> messages(int global) {
            return index.getOrDefault(global, List.of());
        }

        FitMessage first(int global) {
            List<FitMessage> l = messages(global);
            return l.isEmpty() ? null : l.getFirst();
        }

        int count(int global) {
            return messages(global).size();
        }

        String summary() {
            Map<Integer, Integer> counts = new TreeMap<>();
            for (FitMessage m : messages) counts.merge(m.global, 1, Integer::sum);
            StringBuilder s = new StringBuilder();
            s.append(messages.size()).append(" messages: ");
            List<Map.Entry<Integer, Integer>> top = new ArrayList<>(counts.entrySet());
            top.sort((a, b) -> b.getValue() - a.getValue());
            int n = 0;
            for (var e : top) {
                if (n++ >= 8) {
                    s.append(", +").append(top.size() - 8).append(" more types");
                    break;
                }
                if (n > 1) s.append(", ");
                s.append(e.getValue()).append(" ").append(FitProfile.MESG_NAMES.getOrDefault(e.getKey(), "#" + e.getKey()));
            }
            return s.toString();
        }

        static FitFile parse(byte[] b) {
            if (b.length < 12) throw new IllegalArgumentException("Too short for a FIT file");
            int hs = b[0] & 0xff;
            FitFile f = new FitFile();
            f.protocol = b[1] & 0xff;
            f.profile = (b[2] & 0xff) | (b[3] & 0xff) << 8;
            long dataSize = (b[4] & 0xffL) | (b[5] & 0xffL) << 8 | (b[6] & 0xffL) << 16 | (b[7] & 0xffL) << 24;
            if (b[8] != '.' || b[9] != 'F' || b[10] != 'I' || b[11] != 'T') throw new IllegalArgumentException("Missing .FIT signature");
            int pos = hs;
            long end = Math.min(b.length, hs + dataSize);
            FitDef[] defs = new FitDef[16];
            long lastTs = 0;
            ByteBuffer buf = ByteBuffer.wrap(b);
            while (pos < end) {
                int h = b[pos++] & 0xff;
                if ((h & 0x80) != 0) {
                    int local = (h >> 5) & 0x3;
                    int off = h & 0x1f;
                    long ts = (lastTs & ~0x1FL) + off;
                    if (off < (lastTs & 0x1F)) ts += 0x20;
                    lastTs = ts;
                    FitDef d = defs[local];
                    if (d == null) throw new IllegalArgumentException("Compressed record without definition at " + pos);
                    FitMessage m = new FitMessage(d.global);
                    pos = readData(f, buf, b, pos, d, m);
                    m.fields.put(253, (double) ts);
                    f.add(m);
                } else if ((h & 0x40) != 0) {
                    int local = h & 0x0f;
                    pos++; // reserved
                    boolean big = b[pos++] == 1;
                    int global = big ? ((b[pos] & 0xff) << 8 | (b[pos + 1] & 0xff)) : ((b[pos] & 0xff) | (b[pos + 1] & 0xff) << 8);
                    pos += 2;
                    int n = b[pos++] & 0xff;
                    List<FitFieldDef> fields = new ArrayList<>(n);
                    for (int k = 0; k < n; k++, pos += 3) fields.add(new FitFieldDef(b[pos] & 0xff, b[pos + 1] & 0xff, b[pos + 2] & 0xff));
                    List<FitDevFieldDef> dev = new ArrayList<>();
                    if ((h & 0x20) != 0) {
                        int nd = b[pos++] & 0xff;
                        for (int k = 0; k < nd; k++, pos += 3) dev.add(new FitDevFieldDef(b[pos] & 0xff, b[pos + 1] & 0xff, b[pos + 2] & 0xff));
                    }
                    defs[local] = new FitDef(global, big, fields, dev);
                } else {
                    FitDef d = defs[h & 0x0f];
                    if (d == null) throw new IllegalArgumentException("Data record without definition at " + pos);
                    FitMessage m = new FitMessage(d.global);
                    pos = readData(f, buf, b, pos, d, m);
                    Double ts = m.num(253);
                    if (ts != null) lastTs = ts.longValue();
                    f.add(m);
                    if (d.global == 206) f.registerDevField(m);
                }
            }
            return f;
        }

        private void add(FitMessage m) {
            messages.add(m);
            index.computeIfAbsent(m.global, k -> new ArrayList<>()).add(m);
        }

        private void registerDevField(FitMessage m) {
            Double di = m.num(0), fn = m.num(1), bt = m.num(2);
            if (di == null || fn == null) return;
            Object name = m.fields.get(3);
            Object units = m.fields.get(8);
            double scale = Optional.ofNullable(m.num(6)).orElse(1.0);
            double offset = Optional.ofNullable(m.num(7)).orElse(0.0);
            developerFields.put(di.intValue() + ":" + fn.intValue(), new DevField(
                    name == null ? "dev " + di.intValue() + ":" + fn.intValue() : name.toString(),
                    bt == null ? 0x0D : bt.intValue(), units == null ? "" : units.toString(), scale == 0 ? 1 : scale, offset));
        }

        private static int readData(FitFile f, ByteBuffer buf, byte[] b, int pos, FitDef d, FitMessage m) {
            buf.order(d.bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
            for (FitFieldDef fd : d.fields) {
                Object v = readValue(buf, b, pos, fd.size, fd.baseType);
                if (v != null) m.fields.put(fd.num, v);
                pos += fd.size;
            }
            for (FitDevFieldDef dd : d.devFields) {
                DevField df = f.developerFields.get(dd.devIndex + ":" + dd.num);
                Object v = readValue(buf, b, pos, dd.size, df == null ? 0x0D : df.baseType);
                if (v != null) {
                    String name = df == null ? "dev " + dd.devIndex + ":" + dd.num : df.name;
                    String units = df == null || df.units.isEmpty() ? "" : " " + df.units;
                    String shown;
                    if (v instanceof Number n && df != null) shown = trimNum(n.doubleValue() / df.scale - df.offset) + units;
                    else if (v instanceof double[] a) {
                        StringBuilder sb = new StringBuilder();
                        for (double x : a) sb.append(sb.isEmpty() ? "" : ", ").append(trimNum(x));
                        shown = sb + units;
                    } else shown = v + units;
                    m.dev.put(name, shown);
                }
                pos += dd.size;
            }
            return pos;
        }

        private static int baseSize(int t) {
            return switch (t & 0x1F) {
                case 0, 1, 2, 7, 10, 13 -> 1;
                case 3, 4, 11 -> 2;
                case 5, 6, 8, 12 -> 4;
                case 9, 14, 15, 16 -> 8;
                default -> 1;
            };
        }

        private static Object readValue(ByteBuffer buf, byte[] b, int pos, int size, int baseType) {
            int t = baseType & 0x1F;
            if (pos + size > b.length) return null;
            if (t == 7) {
                int len = 0;
                while (len < size && b[pos + len] != 0) len++;
                if (len == 0) return null;
                return new String(b, pos, len, StandardCharsets.UTF_8);
            }
            int bs = baseSize(t);
            if (t == 13 || size % bs != 0) {
                boolean allFF = true;
                for (int k = 0; k < size; k++) if (b[pos + k] != (byte) 0xFF) allFF = false;
                if (allFF) return null;
                if (size == 1) return (double) (b[pos] & 0xff);
                byte[] out = new byte[size];
                System.arraycopy(b, pos, out, 0, size);
                return out;
            }
            int count = size / bs;
            double[] vals = new double[count];
            boolean anyValid = false;
            for (int k = 0; k < count; k++) {
                int p = pos + k * bs;
                double v;
                boolean invalid;
                switch (t) {
                    case 0, 2 -> {
                        int x = b[p] & 0xff;
                        invalid = x == 0xFF;
                        v = x;
                    }
                    case 10 -> {
                        int x = b[p] & 0xff;
                        invalid = x == 0;
                        v = x;
                    }
                    case 1 -> {
                        int x = b[p];
                        invalid = x == 0x7F;
                        v = x;
                    }
                    case 3 -> {
                        short x = buf.getShort(p);
                        invalid = x == 0x7FFF;
                        v = x;
                    }
                    case 4, 11 -> {
                        int x = buf.getShort(p) & 0xffff;
                        invalid = t == 4 ? x == 0xFFFF : x == 0;
                        v = x;
                    }
                    case 5 -> {
                        int x = buf.getInt(p);
                        invalid = x == 0x7FFFFFFF;
                        v = x;
                    }
                    case 6, 12 -> {
                        long x = buf.getInt(p) & 0xffffffffL;
                        invalid = t == 6 ? x == 0xFFFFFFFFL : x == 0;
                        v = x;
                    }
                    case 8 -> {
                        int bits = buf.getInt(p);
                        invalid = bits == 0xFFFFFFFF;
                        v = Float.intBitsToFloat(bits);
                    }
                    case 9 -> {
                        long bits = buf.getLong(p);
                        invalid = bits == 0xFFFFFFFFFFFFFFFFL;
                        v = Double.longBitsToDouble(bits);
                    }
                    case 14 -> {
                        long x = buf.getLong(p);
                        invalid = x == 0x7FFFFFFFFFFFFFFFL;
                        v = x;
                    }
                    default -> { // 15 uint64, 16 uint64z
                        long x = buf.getLong(p);
                        invalid = t == 15 ? x == -1L : x == 0;
                        v = x < 0 ? x + 18446744073709551616.0 : x;
                    }
                }
                vals[k] = invalid ? Double.NaN : v;
                anyValid |= !invalid;
            }
            if (!anyValid) return null;
            return count == 1 ? (Object) vals[0] : vals;
        }
    }

    private static Instant fitInstant(double fitSeconds) {
        return Instant.ofEpochSecond((long) fitSeconds + 631065600L);
    }

    /** Subset of the Garmin FIT SDK profile: message names, field names, scales, units and enums. */
    static final class FitProfile {
        record F(String name, double scale, double offset, String units, String type) {}

        static final Map<Integer, String> MESG_NAMES = new HashMap<>();
        static final Map<Integer, Map<Integer, F>> FIELDS = new HashMap<>();
        static final Map<String, Map<Integer, String>> ENUMS = new HashMap<>();
        static final Map<Integer, String> LOCAL_DEVICE_TYPE = new HashMap<>();
        static final Map<Integer, String> ANTPLUS_DEVICE_TYPE = new HashMap<>();

        static F field(int mesg, int num) {
            if (num == 253) return new F("timestamp", 1, 0, "", "time");
            if (num == 254) return new F("message_index", 1, 0, "", "num");
            if (num == 250) return new F("part_index", 1, 0, "", "num");
            Map<Integer, F> m = FIELDS.get(mesg);
            return m == null ? null : m.get(num);
        }

        private static void enumOf(String name, String spec) {
            Map<Integer, String> m = new HashMap<>();
            for (String part : spec.split(",")) {
                String[] kv = part.trim().split("=");
                m.put(Integer.parseInt(kv[0].trim()), kv[1].trim());
            }
            ENUMS.put(name, m);
        }

        /**
         * Field spec: {@code num name [time|localtime|semi|dur|enum:x] [/scale] [-offset] [units]; ...}
         */
        private static void mesg(int num, String name, String spec) {
            MESG_NAMES.put(num, name);
            Map<Integer, F> m = new HashMap<>();
            for (String part : spec.split(";")) {
                String[] t = part.trim().split("\\s+");
                if (t.length < 2) continue;
                int fn = Integer.parseInt(t[0]);
                double scale = 1, offset = 0;
                String type = "num", units = "";
                for (int k = 2; k < t.length; k++) {
                    String x = t[k];
                    if (x.equals("time") || x.equals("localtime") || x.equals("semi") || x.startsWith("enum:")) type = x;
                    else if (x.equals("dur")) type = "dur";
                    else if (x.startsWith("/")) scale = Double.parseDouble(x.substring(1));
                    else if (x.startsWith("-") && x.length() > 1 && Character.isDigit(x.charAt(1))) offset = Double.parseDouble(x.substring(1));
                    else units = x;
                }
                m.put(fn, new F(t[1], scale, offset, units, type));
            }
            FIELDS.put(num, m);
        }

        static {
            enumOf("sport", "0=generic,1=running,2=cycling,3=transition,4=fitness_equipment,5=swimming,6=basketball,7=soccer,8=tennis,"
                    + "9=american_football,10=training,11=walking,12=cross_country_skiing,13=alpine_skiing,14=snowboarding,15=rowing,"
                    + "16=mountaineering,17=hiking,18=multisport,19=paddling,20=flying,21=e_biking,22=motorcycling,23=boating,24=driving,"
                    + "25=golf,26=hang_gliding,27=horseback_riding,28=hunting,29=fishing,30=inline_skating,31=rock_climbing,32=sailing,"
                    + "33=ice_skating,34=sky_diving,35=snowshoeing,36=snowmobiling,37=stand_up_paddleboarding,38=surfing,39=wakeboarding,"
                    + "40=water_skiing,41=kayaking,42=rafting,43=windsurfing,44=kitesurfing,45=tactical,46=jumpmaster,47=boxing,"
                    + "48=floor_climbing,53=diving,62=hiit,64=racket,67=meditation,69=disc_golf,83=dance,84=jump_rope,254=all");
            enumOf("sub_sport", "0=generic,1=treadmill,2=street,3=trail,4=track,5=spin,6=indoor_cycling,7=road,8=mountain,9=downhill,"
                    + "10=recumbent,11=cyclocross,12=hand_cycling,13=track_cycling,14=indoor_rowing,15=elliptical,16=stair_climbing,"
                    + "17=lap_swimming,18=open_water,19=flexibility_training,20=strength_training,21=warm_up,22=match,23=exercise,"
                    + "24=challenge,25=indoor_skiing,26=cardio_training,27=indoor_walking,28=e_bike_fitness,29=bmx,30=casual_walking,"
                    + "31=speed_walking,32=bike_to_run_transition,33=run_to_bike_transition,34=swim_to_bike_transition,35=atv,"
                    + "36=motocross,37=backcountry,38=resort,39=rc_drone,40=wingsuit,41=whitewater,42=skate_skiing,43=yoga,44=pilates,"
                    + "45=indoor_running,46=gravel_cycling,47=e_bike_mountain,48=commuting,49=mixed_surface,50=navigate,51=track_me,"
                    + "52=map,58=virtual_activity,59=obstacle,62=breathing,67=ultra,68=indoor_climbing,69=bouldering,70=hiit,254=all");
            enumOf("event", "0=timer,3=workout,4=workout_step,5=power_down,6=power_up,7=off_course,8=session,9=lap,10=course_point,"
                    + "11=battery,12=virtual_partner_pace,13=hr_high_alert,14=hr_low_alert,15=speed_high_alert,16=speed_low_alert,"
                    + "17=cad_high_alert,18=cad_low_alert,19=power_high_alert,20=power_low_alert,21=recovery_hr,22=battery_low,"
                    + "23=time_duration_alert,24=distance_duration_alert,25=calorie_duration_alert,26=activity,27=fitness_equipment,"
                    + "28=length,32=user_marker,33=sport_point,36=calibration,42=front_gear_change,43=rear_gear_change,"
                    + "44=rider_position_change,45=elev_high_alert,46=elev_low_alert,47=comm_timeout,75=radar_threat_alert");
            enumOf("event_type", "0=start,1=stop,2=consecutive_depreciated,3=marker,4=stop_all,5=begin_depreciated,6=end_depreciated,"
                    + "7=end_all_depreciated,8=stop_disable,9=stop_disable_all");
            enumOf("manufacturer", "1=garmin,2=garmin_fr405_antfs,13=dynastream_oem,15=dynastream,23=suunto,32=wahoo_fitness,"
                    + "69=stages_cycling,89=tacx,123=polar_electro,255=development,260=zwift,263=favero_electronics,265=strava");
            enumOf("lap_trigger", "0=manual,1=time,2=distance,3=position_start,4=position_lap,5=position_waypoint,6=position_marked,"
                    + "7=session_end,8=fitness_equipment");
            enumOf("session_trigger", "0=activity_end,1=manual,2=auto_multi_sport,3=fitness_equipment");
            enumOf("intensity", "0=active,1=rest,2=warmup,3=cooldown,4=recovery,5=interval,6=other");
            enumOf("swim_stroke", "0=freestyle,1=backstroke,2=breaststroke,3=butterfly,4=drill,5=mixed,6=im");
            enumOf("length_type", "0=idle,1=active");
            enumOf("file", "1=device,2=settings,3=sport,4=activity,5=workout,6=course,7=schedules,9=weight,10=totals,11=goals,"
                    + "14=blood_pressure,15=monitoring_a,20=activity_summary,28=monitoring_daily,32=monitoring_b,34=segment,"
                    + "35=segment_list,40=exd_configuration");
            enumOf("gender", "0=female,1=male");
            enumOf("activity", "0=manual,1=auto_multi_sport");
            enumOf("battery_status", "1=new,2=good,3=ok,4=low,5=critical,6=charging,7=unknown");
            enumOf("source_type", "0=ant,1=antplus,2=bluetooth,3=bluetooth_low_energy,4=wifi,5=local");
            enumOf("hr_zone_calc", "0=custom,1=percent_max_hr,2=percent_hrr,3=percent_lthr");
            enumOf("pwr_zone_calc", "0=custom,1=percent_ftp");
            enumOf("display_measure", "0=metric,1=statute,2=nautical");
            enumOf("display_heart", "0=bpm,1=max,2=reserve");
            enumOf("display_position", "0=degree,1=degree_minute,2=degree_minute_second");
            enumOf("language", "0=english,1=french,2=italian,3=german,4=spanish,5=croatian,6=czech,7=danish,8=dutch,9=finnish,"
                    + "10=greek,11=hungarian,12=norwegian,13=polish,14=portuguese,15=slovakian,16=slovenian,17=swedish,18=russian,"
                    + "19=turkish,20=latvian,21=ukrainian,22=arabic,23=farsi,24=bulgarian,25=romanian,26=chinese,27=japanese,"
                    + "28=korean,29=taiwanese,30=thai,31=hebrew,32=brazilian_portuguese,33=indonesian,34=malaysian,35=vietnamese");
            enumOf("split_type", "1=ascent_split,2=descent_split,3=interval_active,4=interval_rest,5=interval_warmup,"
                    + "6=interval_cooldown,7=interval_recovery,8=interval_other,9=climb_active,10=climb_rest,11=surf_active,"
                    + "12=run_active,13=run_rest,14=workout_round,17=rwd_run,18=rwd_walk,21=windsurf_active,22=rwd_stand,"
                    + "23=transition,28=ski_lift_split,29=ski_run_split");
            enumOf("mesg", "18=session,19=lap,101=length,312=split");

            LOCAL_DEVICE_TYPE.putAll(Map.ofEntries(Map.entry(0, "gps"), Map.entry(1, "glonass"), Map.entry(2, "gps_glonass"),
                    Map.entry(3, "accelerometer"), Map.entry(4, "barometer"), Map.entry(5, "temperature"), Map.entry(10, "wrist_heart_rate"),
                    Map.entry(12, "sensor_hub"), Map.entry(9, "watch"), Map.entry(11, "gps_galileo?"), Map.entry(13, "bluetooth?")));
            ANTPLUS_DEVICE_TYPE.putAll(Map.ofEntries(Map.entry(1, "antfs"), Map.entry(11, "bike_power"), Map.entry(12, "environment_sensor_legacy"),
                    Map.entry(15, "multi_sport_speed_distance"), Map.entry(16, "control"), Map.entry(17, "fitness_equipment"),
                    Map.entry(18, "blood_pressure"), Map.entry(19, "geocache_node"), Map.entry(20, "light_electric_vehicle"),
                    Map.entry(25, "env_sensor"), Map.entry(26, "racquet"), Map.entry(27, "control_hub"), Map.entry(31, "muscle_oxygen"),
                    Map.entry(34, "shifting"), Map.entry(35, "bike_light_main"), Map.entry(36, "bike_light_shared"), Map.entry(38, "exd"),
                    Map.entry(40, "bike_radar"), Map.entry(46, "bike_aero"), Map.entry(119, "weight_scale"), Map.entry(120, "heart_rate"),
                    Map.entry(121, "bike_speed_cadence"), Map.entry(122, "bike_cadence"), Map.entry(123, "bike_speed"),
                    Map.entry(124, "stride_speed_distance")));

            MESG_NAMES.putAll(Map.ofEntries(Map.entry(1, "capabilities"), Map.entry(2, "device_settings"), Map.entry(4, "hrm_profile"),
                    Map.entry(5, "sdm_profile"), Map.entry(6, "bike_profile"), Map.entry(9, "power_zone"), Map.entry(10, "met_zone"),
                    Map.entry(13, "training_settings"), Map.entry(15, "goal"), Map.entry(26, "workout"), Map.entry(27, "workout_step"),
                    Map.entry(28, "schedule"), Map.entry(30, "weight_scale"), Map.entry(31, "course"), Map.entry(32, "course_point"),
                    Map.entry(33, "totals"), Map.entry(35, "software"), Map.entry(37, "file_capabilities"), Map.entry(38, "mesg_capabilities"),
                    Map.entry(39, "field_capabilities"), Map.entry(51, "blood_pressure"), Map.entry(53, "speed_zone"),
                    Map.entry(55, "monitoring"), Map.entry(72, "training_file"), Map.entry(80, "ant_rx"), Map.entry(81, "ant_tx"),
                    Map.entry(82, "ant_channel_id"), Map.entry(103, "monitoring_info"), Map.entry(105, "pad"), Map.entry(106, "slave_device"),
                    Map.entry(127, "connectivity"), Map.entry(128, "weather_conditions"), Map.entry(129, "weather_alert"),
                    Map.entry(131, "cadence_zone"), Map.entry(132, "hr"), Map.entry(142, "segment_lap"), Map.entry(145, "memo_glob"),
                    Map.entry(148, "segment_id"), Map.entry(149, "segment_leaderboard_entry"), Map.entry(150, "segment_point"),
                    Map.entry(151, "segment_file"), Map.entry(158, "workout_session"), Map.entry(159, "watchface_settings"),
                    Map.entry(160, "gps_metadata"), Map.entry(161, "camera_event"), Map.entry(162, "timestamp_correlation"),
                    Map.entry(164, "gyroscope_data"), Map.entry(165, "accelerometer_data"), Map.entry(167, "three_d_sensor_calibration"),
                    Map.entry(169, "video_frame"), Map.entry(174, "obdii_data"), Map.entry(177, "nmea_sentence"),
                    Map.entry(178, "aviation_attitude"), Map.entry(184, "video"), Map.entry(188, "ohr_settings"),
                    Map.entry(200, "exd_screen_configuration"), Map.entry(201, "exd_data_field_configuration"),
                    Map.entry(202, "exd_data_concept_configuration"), Map.entry(208, "magnetometer_data"), Map.entry(209, "barometer_data"),
                    Map.entry(210, "one_d_sensor_calibration"), Map.entry(225, "set"), Map.entry(227, "stress_level"),
                    Map.entry(229, "max_met_data"), Map.entry(258, "dive_settings"), Map.entry(259, "dive_gas"), Map.entry(262, "dive_alarm"),
                    Map.entry(264, "exercise_title"), Map.entry(268, "dive_summary"), Map.entry(269, "spo2_data"), Map.entry(275, "sleep_level"),
                    Map.entry(285, "jump"), Map.entry(290, "beat_intervals"), Map.entry(297, "respiration_rate"), Map.entry(313, "split_summary"),
                    Map.entry(314, "climb_pro"), Map.entry(317, "tank_update"), Map.entry(319, "tank_summary"),
                    Map.entry(323, "sleep_assessment"), Map.entry(346, "hrv_status_summary"), Map.entry(370, "hrv_value"),
                    Map.entry(371, "raw_bbi"), Map.entry(375, "device_aux_battery_info")));

            mesg(0, "file_id", "0 type enum:file; 1 manufacturer enum:manufacturer; 2 product; 3 serial_number; 4 time_created time; 5 number; 8 product_name");
            mesg(49, "file_creator", "0 software_version; 1 hardware_version");
            mesg(3, "user_profile", "0 friendly_name; 1 gender enum:gender; 2 age years; 3 height /100 m; 4 weight /10 kg; 5 language enum:language;"
                    + "6 elev_setting enum:display_measure; 7 weight_setting enum:display_measure; 8 resting_heart_rate bpm;"
                    + "9 default_max_running_heart_rate bpm; 10 default_max_biking_heart_rate bpm; 11 default_max_heart_rate bpm;"
                    + "12 hr_setting enum:display_heart; 13 speed_setting enum:display_measure; 14 dist_setting enum:display_measure;"
                    + "16 power_setting; 17 activity_class; 18 position_setting enum:display_position; 21 temperature_setting enum:display_measure;"
                    + "22 local_id; 28 wake_time; 29 sleep_time; 30 height_setting enum:display_measure; 31 user_running_step_length /1000 m;"
                    + "32 user_walking_step_length /1000 m");
            mesg(7, "zones_target", "1 max_heart_rate bpm; 2 threshold_heart_rate bpm; 3 functional_threshold_power W; 5 hr_calc_type enum:hr_zone_calc; 7 pwr_calc_type enum:pwr_zone_calc");
            mesg(8, "hr_zone", "1 high_bpm bpm; 2 name");
            mesg(12, "sport", "0 sport enum:sport; 1 sub_sport enum:sub_sport; 3 name");
            mesg(18, "session", "0 event enum:event; 1 event_type enum:event_type; 2 start_time time; 3 start_position_lat semi;"
                    + "4 start_position_long semi; 5 sport enum:sport; 6 sub_sport enum:sub_sport; 7 total_elapsed_time dur /1000;"
                    + "8 total_timer_time dur /1000; 9 total_distance /100 m; 10 total_cycles cycles; 11 total_calories kcal; 13 total_fat_calories kcal;"
                    + "14 avg_speed /1000 m/s; 15 max_speed /1000 m/s; 16 avg_heart_rate bpm; 17 max_heart_rate bpm; 18 avg_cadence rpm;"
                    + "19 max_cadence rpm; 20 avg_power W; 21 max_power W; 22 total_ascent m; 23 total_descent m; 24 total_training_effect /10;"
                    + "25 first_lap_index; 26 num_laps; 27 event_group; 28 trigger enum:session_trigger; 29 nec_lat semi; 30 nec_long semi;"
                    + "31 swc_lat semi; 32 swc_long semi; 33 num_lengths lengths; 34 normalized_power W; 35 training_stress_score /10 tss;"
                    + "36 intensity_factor /1000 if; 37 left_right_balance; 38 end_position_lat semi; 39 end_position_long semi;"
                    + "41 avg_stroke_count /10 strokes/lap; 42 avg_stroke_distance /100 m; 43 swim_stroke enum:swim_stroke; 44 pool_length /100 m;"
                    + "45 threshold_power W; 46 pool_length_unit enum:display_measure; 47 num_active_lengths lengths; 48 total_work J;"
                    + "49 avg_altitude /5 -500 m; 50 max_altitude /5 -500 m; 51 gps_accuracy m; 52 avg_grade /100 %; 53 avg_pos_grade /100 %;"
                    + "54 avg_neg_grade /100 %; 55 max_pos_grade /100 %; 56 max_neg_grade /100 %; 57 avg_temperature °C; 58 max_temperature °C;"
                    + "59 total_moving_time dur /1000; 60 avg_pos_vertical_speed /1000 m/s; 61 avg_neg_vertical_speed /1000 m/s;"
                    + "62 max_pos_vertical_speed /1000 m/s; 63 max_neg_vertical_speed /1000 m/s; 64 min_heart_rate bpm; 65 time_in_hr_zone /1000 s;"
                    + "69 avg_lap_time dur /1000; 70 best_lap_index; 71 min_altitude /5 -500 m; 89 avg_vertical_oscillation /10 mm;"
                    + "90 avg_stance_time_percent /100 %; 91 avg_stance_time /10 ms; 92 avg_fractional_cadence /128 rpm; 93 max_fractional_cadence /128 rpm;"
                    + "94 total_fractional_cycles /128 cycles; 110 sport_profile_name; 111 sport_index; 112 time_standing dur /1000; 113 stand_count;"
                    + "124 enhanced_avg_speed /1000 m/s; 125 enhanced_max_speed /1000 m/s; 126 enhanced_avg_altitude /5 -500 m;"
                    + "127 enhanced_min_altitude /5 -500 m; 128 enhanced_max_altitude /5 -500 m; 132 avg_vertical_ratio /100 %;"
                    + "133 avg_stance_time_balance /100 %; 134 avg_step_length /10 mm; 137 total_anaerobic_training_effect /10;"
                    + "139 avg_vam /1000 m/s; 150 min_temperature °C; 168 training_load_peak /65536; 169 enhanced_avg_respiration_rate /100 brpm;"
                    + "170 enhanced_max_respiration_rate /100 brpm; 180 enhanced_min_respiration_rate /100 brpm; 188 workout_feel; 189 workout_rpe /10;"
                    + "192 avg_spo2 %; 193 avg_stress; 196 resting_calories kcal; 197 hrv_sdrr ms; 198 hrv_rmssd ms; 199 total_fractional_ascent /100 m;"
                    + "200 total_fractional_descent /100 m");
            mesg(19, "lap", "0 event enum:event; 1 event_type enum:event_type; 2 start_time time; 3 start_position_lat semi; 4 start_position_long semi;"
                    + "5 end_position_lat semi; 6 end_position_long semi; 7 total_elapsed_time dur /1000; 8 total_timer_time dur /1000;"
                    + "9 total_distance /100 m; 10 total_cycles cycles; 11 total_calories kcal; 12 total_fat_calories kcal; 13 avg_speed /1000 m/s;"
                    + "14 max_speed /1000 m/s; 15 avg_heart_rate bpm; 16 max_heart_rate bpm; 17 avg_cadence rpm; 18 max_cadence rpm; 19 avg_power W;"
                    + "20 max_power W; 21 total_ascent m; 22 total_descent m; 23 intensity enum:intensity; 24 lap_trigger enum:lap_trigger;"
                    + "25 sport enum:sport; 26 event_group; 32 num_lengths lengths; 33 normalized_power W; 34 left_right_balance;"
                    + "35 first_length_index; 37 avg_stroke_distance /100 m; 38 swim_stroke enum:swim_stroke; 39 sub_sport enum:sub_sport;"
                    + "40 num_active_lengths lengths; 41 total_work J; 42 avg_altitude /5 -500 m; 43 max_altitude /5 -500 m; 44 gps_accuracy m;"
                    + "45 avg_grade /100 %; 46 avg_pos_grade /100 %; 47 avg_neg_grade /100 %; 48 max_pos_grade /100 %; 49 max_neg_grade /100 %;"
                    + "50 avg_temperature °C; 51 max_temperature °C; 52 total_moving_time dur /1000; 53 avg_pos_vertical_speed /1000 m/s;"
                    + "54 avg_neg_vertical_speed /1000 m/s; 55 max_pos_vertical_speed /1000 m/s; 56 max_neg_vertical_speed /1000 m/s;"
                    + "57 time_in_hr_zone /1000 s; 61 repetition_num; 62 min_altitude /5 -500 m; 63 min_heart_rate bpm; 71 wkt_step_index;"
                    + "74 opponent_score; 75 stroke_count; 76 zone_count; 77 avg_vertical_oscillation /10 mm; 78 avg_stance_time_percent /100 %;"
                    + "79 avg_stance_time /10 ms; 80 avg_fractional_cadence /128 rpm; 81 max_fractional_cadence /128 rpm;"
                    + "82 total_fractional_cycles /128 cycles; 98 time_standing dur /1000; 99 stand_count; 110 enhanced_avg_speed /1000 m/s;"
                    + "111 enhanced_max_speed /1000 m/s; 112 enhanced_avg_altitude /5 -500 m; 113 enhanced_min_altitude /5 -500 m;"
                    + "114 enhanced_max_altitude /5 -500 m; 118 avg_vertical_ratio /100 %; 119 avg_stance_time_balance /100 %;"
                    + "120 avg_step_length /10 mm; 121 avg_vam /1000 m/s; 136 enhanced_avg_respiration_rate /100 brpm;"
                    + "137 enhanced_max_respiration_rate /100 brpm; 149 total_grit; 150 total_flow; 151 jump_count; 153 avg_grit; 154 avg_flow;"
                    + "156 total_fractional_ascent /100 m; 157 total_fractional_descent /100 m; 158 avg_core_temperature /100 °C");
            mesg(20, "record", "0 position_lat semi; 1 position_long semi; 2 altitude /5 -500 m; 3 heart_rate bpm; 4 cadence rpm;"
                    + "5 distance /100 m; 6 speed /1000 m/s; 7 power W; 8 compressed_speed_distance; 9 grade /100 %; 10 resistance;"
                    + "11 time_from_course dur /1000; 12 cycle_length /100 m; 13 temperature °C; 17 speed_1s /16 m/s; 18 cycles; 19 total_cycles;"
                    + "28 compressed_accumulated_power W; 29 accumulated_power W; 30 left_right_balance; 31 gps_accuracy m;"
                    + "32 vertical_speed /1000 m/s; 33 calories kcal; 39 vertical_oscillation /10 mm; 40 stance_time_percent /100 %;"
                    + "41 stance_time /10 ms; 42 activity_type; 43 left_torque_effectiveness /2 %; 44 right_torque_effectiveness /2 %;"
                    + "45 left_pedal_smoothness /2 %; 46 right_pedal_smoothness /2 %; 47 combined_pedal_smoothness /2 %; 48 time128 /128 s;"
                    + "49 stroke_type; 50 zone; 51 ball_speed /100 m/s; 52 cadence256 /256 rpm; 53 fractional_cadence /128 rpm;"
                    + "54 total_hemoglobin_conc /100 g/dL; 57 saturated_hemoglobin_percent /10 %; 62 device_index; 67 left_pco mm; 68 right_pco mm;"
                    + "73 enhanced_speed /1000 m/s; 78 enhanced_altitude /5 -500 m; 81 battery_soc /2 %; 82 motor_power W;"
                    + "83 vertical_ratio /100 %; 84 stance_time_balance /100 %; 85 step_length /10 mm; 87 cycle_length16 /100 m;"
                    + "91 absolute_pressure Pa; 92 depth /1000 m; 93 next_stop_depth /1000 m; 94 next_stop_time s; 95 time_to_surface s;"
                    + "96 ndl_time s; 97 cns_load %; 98 n2_load %; 99 respiration_rate brpm; 108 enhanced_respiration_rate /100 brpm;"
                    + "114 grit; 115 flow; 116 current_stress /100; 117 ebike_travel_range km; 118 ebike_battery_level %;"
                    + "119 ebike_assist_mode; 120 ebike_assist_level_percent %; 123 air_time_remaining s; 124 pressure_sac /100 bar/min;"
                    + "125 volume_sac /100 L/min; 126 rmv /100 L/min; 127 ascent_rate /1000 m/s; 129 po2 /100 percent; 139 core_temperature /100 °C");
            mesg(21, "event", "0 event enum:event; 1 event_type enum:event_type; 2 data16; 3 data; 4 event_group; 7 score; 8 opponent_score;"
                    + "9 front_gear_num; 10 front_gear; 11 rear_gear_num; 12 rear_gear; 13 device_index; 14 activity_type; 15 start_timestamp time;"
                    + "21 radar_threat_level_max; 22 radar_threat_count; 23 radar_threat_avg_approach_speed /10 m/s; 24 radar_threat_max_approach_speed /10 m/s");
            mesg(23, "device_info", "0 device_index; 1 device_type; 2 manufacturer enum:manufacturer; 3 serial_number; 4 product;"
                    + "5 software_version /100; 6 hardware_version; 7 cum_operating_time dur; 10 battery_voltage /256 V; 11 battery_status enum:battery_status;"
                    + "18 sensor_position; 19 descriptor; 20 ant_transmission_type; 21 ant_device_number; 22 ant_network; 25 source_type enum:source_type;"
                    + "27 product_name; 32 battery_level %");
            mesg(34, "activity", "0 total_timer_time dur /1000; 1 num_sessions; 2 type enum:activity; 3 event enum:event; 4 event_type enum:event_type;"
                    + "5 local_timestamp localtime; 6 event_group");
            mesg(101, "length", "0 event enum:event; 1 event_type enum:event_type; 2 start_time time; 3 total_elapsed_time dur /1000;"
                    + "4 total_timer_time dur /1000; 5 total_strokes strokes; 6 avg_speed /1000 m/s; 7 swim_stroke enum:swim_stroke;"
                    + "9 avg_swimming_cadence strokes/min; 10 event_group; 11 total_calories kcal; 12 length_type enum:length_type;"
                    + "18 player_score; 19 opponent_score; 20 stroke_count; 21 zone_count; 22 enhanced_avg_respiration_rate /100 brpm;"
                    + "23 enhanced_max_respiration_rate /100 brpm; 24 avg_respiration_rate brpm; 25 max_respiration_rate brpm");
            mesg(78, "hrv", "0 time /1000 s");
            mesg(206, "field_description", "0 developer_data_index; 1 field_definition_number; 2 fit_base_type_id; 3 field_name; 4 array;"
                    + "5 components; 6 scale; 7 offset; 8 units; 9 bits; 10 accumulate; 13 fit_base_unit_id; 14 native_mesg_num; 15 native_field_num");
            mesg(207, "developer_data_id", "0 developer_id; 1 application_id; 2 manufacturer_id enum:manufacturer; 3 developer_data_index; 4 application_version");
            mesg(216, "time_in_zone", "0 reference_mesg enum:mesg; 1 reference_index; 2 time_in_hr_zone /1000 s; 3 time_in_speed_zone /1000 s;"
                    + "4 time_in_cadence_zone /1000 s; 5 time_in_power_zone /1000 s; 6 hr_zone_high_boundary bpm; 7 speed_zone_high_boundary /1000 m/s;"
                    + "8 cadence_zone_high_bondary rpm; 9 power_zone_high_boundary W; 10 hr_calc_type enum:hr_zone_calc; 11 max_heart_rate bpm;"
                    + "12 resting_heart_rate bpm; 13 threshold_heart_rate bpm; 14 pwr_calc_type enum:pwr_zone_calc; 15 functional_threshold_power W");
            mesg(312, "split", "0 split_type enum:split_type; 1 total_elapsed_time dur /1000; 2 total_timer_time dur /1000; 3 total_distance /100 m;"
                    + "4 avg_speed /1000 m/s; 9 start_time time; 13 total_ascent m; 14 total_descent m; 21 start_position_lat semi;"
                    + "22 start_position_long semi; 23 end_position_lat semi; 24 end_position_long semi; 25 max_speed /1000 m/s;"
                    + "26 avg_vert_speed /1000 m/s; 27 end_time time; 28 total_calories kcal; 74 start_elevation /5 -500 m; 110 total_moving_time dur /1000");
            mesg(313, "split_summary", "0 split_type enum:split_type; 3 num_splits; 4 total_timer_time dur /1000; 5 total_distance /100 m;"
                    + "6 avg_speed /1000 m/s; 7 max_speed /1000 m/s; 8 total_ascent m; 9 total_descent m; 10 avg_heart_rate bpm;"
                    + "11 max_heart_rate bpm; 12 avg_vert_speed /1000 m/s; 13 total_calories kcal; 77 total_moving_time dur /1000");
            mesg(22, "device_aux", "");
            MESG_NAMES.remove(22);
            mesg(297, "respiration_rate", "0 respiration_rate /100 brpm");
            mesg(227, "stress_level", "0 stress_level_value; 1 stress_level_time time");
            mesg(229, "max_met_data", "0 update_time time; 2 vo2_max /10 mL/kg/min; 5 sport enum:sport; 6 sub_sport enum:sub_sport;"
                    + "8 max_met_category; 9 calibrated_data; 12 hr_source; 13 speed_source");
            mesg(346, "hrv_status_summary", "0 weekly_average /128 ms; 1 last_night_average /128 ms; 2 last_night_5_min_high /128 ms;"
                    + "3 baseline_low_upper /128 ms; 4 baseline_balanced_lower /128 ms; 5 baseline_balanced_upper /128 ms; 6 status");
            mesg(370, "hrv_value", "0 value /128 ms");
            mesg(285, "jump", "0 distance m; 1 height m; 2 rotations; 3 hang_time s; 4 score; 5 position_lat semi; 6 position_long semi;"
                    + "7 speed /1000 m/s; 8 enhanced_speed /1000 m/s");
            mesg(225, "set", "0 duration dur /1000; 3 repetitions; 4 weight /16 kg; 5 set_type; 6 start_time time; 7 category; 8 category_subtype;"
                    + "9 weight_display_unit; 10 message_index; 11 wkt_step_index");
            mesg(26, "workout", "4 sport enum:sport; 5 capabilities; 6 num_valid_steps; 8 wkt_name; 11 sub_sport enum:sub_sport; 14 pool_length /100 m");
            mesg(27, "workout_step", "0 wkt_step_name; 1 duration_type; 2 duration_value; 3 target_type; 4 target_value; 5 custom_target_value_low;"
                    + "6 custom_target_value_high; 7 intensity enum:intensity; 8 notes; 9 equipment");
            mesg(13, "training_settings", "");
            mesg(2, "device_settings", "0 active_time_zone; 1 utc_offset; 2 time_offset s; 4 time_mode; 5 time_zone_offset /4 h;"
                    + "12 backlight_mode; 36 activity_tracker_enabled; 39 clock_time time; 40 pages_enabled; 46 move_alert_enabled;"
                    + "47 date_mode; 55 display_orientation; 56 mounting_side; 57 default_page; 58 autosync_min_steps steps;"
                    + "59 autosync_min_time min; 80 lactate_threshold_autodetect_enabled; 86 ble_auto_upload_enabled; 89 auto_sync_frequency;"
                    + "90 auto_activity_detect; 94 number_of_screens; 95 smart_notification_display_orientation; 134 tap_interface; 174 tap_sensitivity");
            mesg(140, "#140", "");
            MESG_NAMES.remove(140);
        }
    }

    // ------------------------------------------------------------------ formatting

    private static LocalDateTime parseGarminTime(String s) {
        String t = s.endsWith("Z") ? s.substring(0, s.length() - 1) : s;
        return LocalDateTime.parse(t);
    }

    private static final Pattern MS_KEY = Pattern.compile("(?i).*(timestamp|gmt|local|date|time|startgmt|endgmt).*");

    /** Best-effort formatting of a JSON scalar based on its key. */
    private static String prettyValue(String key, Object v) {
        if (v == null) return "—";
        if (v instanceof Boolean b) return b ? "yes" : "no";
        if (v instanceof String s) {
            if (s.matches("[A-Z0-9_]+") && s.contains("_") || s.matches("[A-Z]{3,}")) return humanize(s);
            return s;
        }
        if (!(v instanceof Number n)) return String.valueOf(v);
        double d = n.doubleValue();
        String k = key.toLowerCase(Locale.ROOT);
        if (d > 9.0e11 && d < 4.0e12 && MS_KEY.matcher(key).matches()) {
            Instant i = Instant.ofEpochMilli((long) d);
            return k.contains("local") ? DT.format(i.atZone(ZoneOffset.UTC)) : DT.format(i.atZone(ZoneId.systemDefault()));
        }
        if (k.endsWith("seconds") || k.endsWith("duration") && !k.contains("milli")) return fmtDur(d);
        if (k.contains("milliseconds") || k.endsWith("inmilliseconds")) return fmtDur(d / 1000);
        if ((k.endsWith("distance") || k.endsWith("meters")) && !k.contains("stroke") && d >= 1000) return fmtDist(d);
        if (k.contains("speed") && !k.contains("vertical") && d > 0 && d < 100) return fmtNum(d * 3.6, 2) + " km/h";
        return trimNum(d);
    }

    static String humanize(String key) {
        if (key == null || key.isEmpty()) return "";
        String s = key;
        if (s.matches("[A-Z0-9_]+")) {
            s = s.replaceAll("_\\d+$", "").replace('_', ' ').toLowerCase(Locale.ROOT);
        } else {
            s = s.replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
            s = s.replace("DTO", "").replace("Dto", "").trim();
            s = s.toLowerCase(Locale.ROOT).replace(" gmt", " (GMT)").replace("hr ", "HR ").replace("vo2", "VO₂");
            if (s.endsWith(" hr")) s = s.substring(0, s.length() - 3) + " HR";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? key : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String sportIcon(String sport) {
        String s = sport.toLowerCase(Locale.ROOT);
        if (s.contains("swim")) return "🏊";
        if (s.contains("cycl") || s.contains("bik") || s.contains("ride")) return "🚴";
        if (s.contains("run")) return "🏃";
        if (s.contains("walk")) return "🚶";
        if (s.contains("hik")) return "🥾";
        if (s.contains("strength") || s.contains("training")) return "🏋";
        if (s.contains("yoga")) return "🧘";
        if (s.contains("table_tennis")) return "🏓";
        if (s.contains("badminton") || s.contains("racket")) return "🏸";
        if (s.contains("row") || s.contains("kayak") || s.contains("paddl") || s.contains("raft")) return "🚣";
        return "⏱";
    }

    static String fmtDur(double secs) {
        if (Double.isNaN(secs)) return "";
        long t = Math.round(secs);
        long h = t / 3600, m = (t % 3600) / 60, s = t % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    static String fmtDist(double m) {
        return m >= 1000 ? fmtNum(m / 1000, 2) + " km" : fmtNum(m, 0) + " m";
    }

    static String fmtPace(double mps, boolean swim) {
        if (mps <= 0) return "";
        double secs = swim ? 100 / mps : 1000 / mps;
        return fmtDur(secs) + (swim ? " /100 m" : " /km");
    }

    static String fmtPaceMin(double minPerKm) {
        return fmtDur(minPerKm * 60);
    }

    /** A JavaScript string literal (also safe inside a <script> element). */
    static String jsStr(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\"";
    }

    static String fmtNum(double v, int decimals) {
        return String.format(Locale.ROOT, "%,." + decimals + "f", v).replace(",", " ");
    }

    static String trimNum(double v) {
        if (Double.isNaN(v)) return "";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        String s = String.format(Locale.ROOT, "%.4f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String fmtTick(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt6(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String pct(double v, double max) {
        return String.format(Locale.ROOT, "%.1f", max <= 0 ? 0 : Math.max(0, Math.min(100, v * 100 / max)));
    }

    private static String fmtBytes(long b) {
        if (b >= 1 << 20) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        if (b >= 1 << 10) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        return b + " B";
    }

    private static Double parseD(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String goal(Map<String, Object> m, String key) {
        Double g = num(m == null ? null : m.get(key));
        return g == null ? "" : " / " + fmtNum(g, 0);
    }

    private static String sub(Map<String, Object> m, String key, String prefix) {
        Double g = num(m.get(key));
        return g == null ? "" : " (" + prefix + fmtNum(g, 0) + ")";
    }

    private static String pctOf(double v, Map<String, Object> sleep) {
        double total = numOr(sleep.get("sleepTimeSeconds"), 0);
        return total <= 0 ? "" : " (" + fmtNum(v * 100 / total, 0) + "%)";
    }

    private static double[] niceTicks(double lo, double hi, int n) {
        double range = hi - lo;
        double rough = range / n;
        double mag = Math.pow(10, Math.floor(Math.log10(rough)));
        double norm = rough / mag;
        double step = (norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10) * mag;
        double start = Math.floor(lo / step) * step;
        double end = Math.ceil(hi / step) * step;
        int count = (int) Math.round((end - start) / step) + 1;
        double[] t = new double[count];
        for (int i = 0; i < count; i++) t[i] = start + i * step;
        return t;
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String chip(String src) {
        return "<span class=\"src src-" + src.toLowerCase(Locale.ROOT) + "\">" + esc(src) + "</span>";
    }

    private static String srcLine(String src, String file, String what) {
        return "<p class=\"from\">" + chip(src) + " from <code>" + esc(file) + "</code> · " + esc(what) + "</p>";
    }

    private static final String CSS = """
            :root{--bg:#f6f7f9;--card:#fff;--fg:#1d2329;--muted:#6b7682;--line:#e3e7ec;--accent:#2563eb;
              --c-hr:#e5484d;--c-speed:#2563eb;--c-alt:#16a34a;--c-cad:#d97706;--c-pow:#9333ea;--c-temp:#0891b2;--c-resp:#0d9488;
              --c-stress:#ea580c;--c-bb:#2563eb;--c-deep:#1e3a8a;--c-light:#60a5fa;--c-rem:#c084fc;--c-awake:#f472b6;
              --fit:#7c3aed;--tcx:#0891b2;--gpx:#16a34a;--json:#d97706;--wellness:#e11d48;
              --s1:#2a78d6;--s2:#eb6834;--s3:#1baf7a;--s4:#eda100;--s5:#e87ba4;--s6:#008300}
            @media (prefers-color-scheme:dark){:root{--bg:#0f1317;--card:#171c21;--fg:#e6e9ec;--muted:#8d98a3;--line:#2a3138;
              --c-deep:#3b5bdb;--c-light:#74a9f7;--s1:#3987e5;--s2:#d95926;--s3:#199e70;--s4:#c98500;--s5:#d55181;--s6:#008300}}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Inter,Roboto,sans-serif}
            main{max-width:1100px;margin:0 auto;padding:24px 16px 60px}
            header{margin:8px 0 18px}.eyebrow{text-transform:uppercase;letter-spacing:.08em;font-size:12px;color:var(--muted);font-weight:600}
            h1{font-size:30px;margin:2px 0 4px;letter-spacing:-.01em}h2{font-size:21px;margin:0 0 4px}
            h3{font-size:15px;margin:26px 0 8px;text-transform:uppercase;letter-spacing:.05em;color:var(--muted)}
            .sub{color:var(--muted);margin:0 0 12px}.muted{color:var(--muted)}
            .card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:20px 22px;margin:18px 0;box-shadow:0 1px 2px rgba(0,0,0,.04)}
            nav{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0;position:sticky;top:0;z-index:1000;padding:8px 0;background:var(--bg)}
            nav a{background:var(--card);border:1px solid var(--line);border-radius:999px;padding:5px 12px;color:var(--fg);text-decoration:none;font-size:14px}
            nav a:hover{border-color:var(--accent)}nav a.home{font-weight:600;color:var(--accent)}
            .tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px;margin:8px 0 6px}
            .tile{border:1px solid var(--line);border-radius:10px;padding:10px 12px;cursor:help}
            .tile .v{font-size:19px;font-weight:650;font-variant-numeric:tabular-nums;line-height:1.25}
            .tile .v small,.tile .l small{font-weight:400;color:var(--muted);font-size:12px}
            .tile .l{font-size:12.5px;color:var(--muted);margin-top:2px}
            a.tile.link{display:block;color:inherit;text-decoration:none;cursor:pointer}
            a.tile.link:hover{border-color:var(--accent)}
            a.tile.link.on{border-color:var(--accent);box-shadow:0 0 0 1px var(--accent) inset}
            .acts .dim,.dists .dim{opacity:.35}
            div.stack{display:flex;flex-direction:column;gap:2px}.stack>*{white-space:nowrap;line-height:22px}
            .filters{display:flex;flex-wrap:wrap;align-items:center;gap:8px 12px;margin:4px 0 8px}
            .filters .filter{margin:0}
            .filters select,.filters label input{padding:8px 10px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--fg);font:inherit}
            .filters label{color:var(--muted);font-size:14px}
            .filters label input{width:72px}
            #filterStatus{color:var(--muted);font-size:14px}
            .filters label input[type=date]{width:auto}.filters label select{padding:8px 6px}
            .ranges{display:flex;flex-wrap:wrap;gap:6px}
            .ranges a{border:1px solid var(--line);border-radius:999px;padding:4px 10px;font-size:13px;color:var(--fg);text-decoration:none;background:var(--card)}
            .ranges a:hover{border-color:var(--accent)}.ranges a.on{border-color:var(--accent);color:var(--accent);font-weight:600}
            #totals.filtered .tile .v{color:var(--accent)}#totals.filtered .tile .l::after{content:" · selection"}
            a.tile.link.zero{opacity:.4}
            .trend-head{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:8px}
            #trendChart svg{width:100%;height:auto;display:block}#trendChart rect.hit:hover{fill:color-mix(in srgb,var(--fg) 6%,transparent)}
            #tip b{font-weight:650}
            .accounts{float:right;margin-top:-2px}
            section.panel.folded{display:none}.panels{margin:4px 0 8px}
            #calChart svg{width:100%;height:auto;display:block;max-width:900px}#calChart rect.day:hover{stroke:var(--fg);stroke-width:1.5}
            #calendar .ranges a{min-width:34px;text-align:center}
            .acts .dim,.dists .dim,.paces .dim{opacity:.35}
            .src{display:inline-block;font-size:10.5px;font-weight:700;letter-spacing:.03em;padding:0 6px;border-radius:5px;color:#fff;vertical-align:1px;line-height:17px}
            .src-fit{background:var(--fit)}.src-tcx{background:var(--tcx)}.src-gpx{background:var(--gpx)}.src-json{background:var(--json)}.src-wellness{background:var(--wellness)}
            .from{font-size:13px;color:var(--muted);margin:14px 0 6px}
            code{font:12.5px ui-monospace,SFMono-Regular,Menlo,monospace}
            .scroll{overflow-x:auto}
            table{border-collapse:collapse;width:100%;font-size:13.5px;font-variant-numeric:tabular-nums}
            th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
            th{font-weight:600;color:var(--muted);white-space:nowrap}
            th.sortable{cursor:pointer;user-select:none}th.sortable:hover{color:var(--fg)}
            th[data-sort]{color:var(--fg)}th[data-sort=asc]::after{content:" ▲";font-size:10px}th[data-sort=desc]::after{content:" ▼";font-size:10px}td.num{text-align:right;white-space:nowrap}
            table.dense{font-size:12px}table.dense td,table.dense th{padding:3px 6px;white-space:nowrap}
            table.kv th{width:38%;white-space:normal;color:var(--fg);font-weight:500}table.kv{margin-bottom:6px}
            tr.rest td{color:var(--muted);font-style:italic}
            tr.has-sub{cursor:pointer}tr.has-sub:hover td{background:var(--bg)}
            tr.has-sub .caret{display:inline-block;color:var(--muted);transition:transform .15s}tr.has-sub.open .caret{transform:rotate(90deg)}
            tr.sub>td{padding:4px 0 12px 24px;background:var(--bg)}
            td.neg{color:#e5484d}td.pos{color:#16a34a}
            details{border:1px solid var(--line);border-radius:10px;margin:8px 0;padding:0 12px}
            details>summary{cursor:pointer;padding:9px 0;font-weight:550}
            details.inner{border-radius:8px;margin:6px 0}details.inner>summary{font-weight:500;padding:6px 0}
            .det{padding:0 0 10px}
            .charts{display:grid;grid-template-columns:repeat(auto-fill,minmax(440px,1fr));gap:12px}
            @media (max-width:560px){.charts{grid-template-columns:1fr}}
            .chart{margin:0;border:1px solid var(--line);border-radius:10px;padding:8px 10px 4px;position:relative}
            .chart.wide{grid-column:1/-1;margin-top:10px}
            figcaption{font-size:13.5px;font-weight:600;display:flex;flex-wrap:wrap;gap:6px;align-items:baseline}
            figcaption .muted{font-weight:400;font-size:12px}figcaption .stats{margin-left:auto;font-weight:400;font-size:12px;color:var(--muted)}
            .chart svg{width:100%;height:auto;display:block}
            .grid{stroke:var(--line);stroke-width:1}.ax{fill:var(--muted);font-size:11px}
            .cursor{stroke:var(--muted);stroke-dasharray:3 3}
            #tip{position:fixed;pointer-events:none;background:var(--fg);color:var(--bg);font-size:12px;padding:3px 8px;border-radius:6px;display:none;z-index:2000;white-space:nowrap}
            .legend{display:flex;gap:14px;flex-wrap:wrap;font-size:12.5px;color:var(--muted);padding:4px 0 6px}
            .legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:5px;vertical-align:-1px}
            .map{height:380px;border-radius:10px;border:1px solid var(--line);margin:6px 0;z-index:0}
            .zones{display:flex;flex-direction:column;gap:6px;margin:6px 0}
            .zone{display:grid;grid-template-columns:150px 1fr 150px;gap:10px;align-items:center;font-size:13.5px}
            .zone small{color:var(--muted)}
            .bar{height:14px;background:var(--line);border-radius:7px;overflow:hidden;position:relative}
            .bar>span{display:block;height:100%;border-radius:7px;position:relative}
            .bar>span.band{position:absolute;top:0;background:rgba(127,127,127,.28);border-radius:0}
            .zv{font-variant-numeric:tabular-nums;text-align:right}
            .chips{display:flex;flex-wrap:wrap;gap:6px;margin:8px 0}
            .q{font-size:12.5px;border:1px solid var(--line);border-radius:999px;padding:2px 10px}
            .q-excellent b{color:#16a34a}.q-good b{color:#22c55e}.q-fair b{color:#d97706}.q-poor b{color:#e5484d}
            tr.back-here td{background:color-mix(in srgb,var(--accent) 14%,transparent);transition:background .6s}
            td.fixed{color:var(--accent);font-weight:600}td.corr{color:var(--accent)}.dists .corr{color:var(--accent)}
            .note{background:color-mix(in srgb,var(--accent) 8%,transparent);border-radius:8px;padding:8px 12px;font-size:14px}
            .warn{color:#b45309}
            footer{color:var(--muted);font-size:12.5px;margin-top:30px}
            a.back{display:inline-block;color:var(--accent);text-decoration:none;font-size:14px;margin-bottom:4px}a.back:hover{text-decoration:underline}
            a{color:var(--accent)}
            .filter{width:100%;max-width:360px;padding:8px 12px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--fg);font:inherit;margin:4px 0 8px}
            .month td:first-child{white-space:nowrap}
            .acts{display:flex;flex-wrap:wrap;gap:4px 10px}.acts>*{white-space:nowrap}
            a.act{color:inherit;text-decoration:none}a.act:hover{color:var(--accent);text-decoration:underline}
            @media (max-width:560px){.zone{grid-template-columns:90px 1fr 110px}h1{font-size:24px}.card{padding:16px}}
            """;

    /** Overview filters: text, date range, activity type, distance and speed/pace, combined per activity; the tiles show the selection's totals; state is kept in the URL hash. */
    private static final String OVERVIEW_JS = """
            var F={q:document.getElementById('q'),sport:document.getElementById('sport'),
              min:document.getElementById('dmin'),max:document.getElementById('dmax'),
              smin:document.getElementById('smin'),smax:document.getElementById('smax'),su:document.getElementById('sunit'),
              from:document.getElementById('dfrom'),to:document.getElementById('dto')};
            var KEYS=['q','sport','min','max','smin','smax','su','from','to'];
            function val(el){var v=parseFloat(el.value.replace(',','.'));return isNaN(v)?null:v;}
            function km(el){var v=val(el);return v===null?null:v*1000;}
            // A pace as minutes: "5:30" or "5.5".
            function paceVal(el){var t=el.value.trim(),m=t.match(/^(\\d+):(\\d{1,2})$/);if(m)return +m[1]+m[2]/60;
              var v=parseFloat(t.replace(',','.'));return isNaN(v)||v<=0?null:v;}
            // The speed range in km/h; for a pace the faster (lower) pace is the higher speed.
            function speedRange(){var u=F.su.value;if(u!=='km'&&u!=='100')return [val(F.smin),val(F.smax)];
              var k=u==='km'?60:6,a=paceVal(F.smin),b=paceVal(F.smax);return [b===null?null:k/b,a===null?null:k/a];}
            function groupOf(sp){if(sp.indexOf('t:')!==0)return sp;var t=sp.slice(2),g=t;
              document.querySelectorAll('.tile.link').forEach(function(x){if((x.dataset.types||'').split(' ').indexOf(t)>=0)g=x.dataset.sport;});return g;}
            function updUnit(){var p=F.su.value==='kmh'?['min','max']:['m:ss','m:ss'];F.smin.placeholder=p[0];F.smax.placeholder=p[1];}
            // Picking a sport switches the speed unit to how that sport is measured, unless a speed is already typed.
            function autoUnit(){if(F.smin.value||F.smax.value)return;var g=groupOf(F.sport.value);
              F.su.value=g==='running'||g==='walking'?'km':g==='swimming'?'100':'kmh';updUnit();}
            function ymd(d){return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');}
            function rangeOf(k){var t=new Date(),y=t.getFullYear();
              if(k==='30')return [ymd(new Date(y,t.getMonth(),t.getDate()-29)),ymd(t)];
              if(k==='month')return [ymd(new Date(y,t.getMonth(),1)),ymd(t)];
              if(k==='year')return [y+'-01-01',ymd(t)];
              return [(y-1)+'-01-01',(y-1)+'-12-31'];}
            function setRange(k){var r=rangeOf(k);if(F.from.value===r[0]&&F.to.value===r[1])r=['',''];
              F.from.value=r[0];F.to.value=r[1];filterRows();return false;}
            function toggleSport(sp){F.sport.value=F.sport.value===sp?'':sp;autoUnit();filterRows();
              var f=document.querySelector('section.month:not([style*=none])');
              if(F.sport.value&&f)f.scrollIntoView({behavior:'smooth'});return false;}
            function clearFilters(){KEYS.forEach(function(k){F[k].value='';});F.su.value='kmh';updUnit();filterRows();return false;}
            function fmtKm(m){return m>=1000?(m/1000).toFixed(2).replace(/\\B(?=(\\d{3})+(?!\\d))/g,' ')+' km':Math.round(m)+' m';}
            function fmtDur(s){s=Math.round(s);var h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;
              return (h?h+':'+String(m).padStart(2,'0'):m)+':'+String(x).padStart(2,'0');}
            function fmtInt(v){return String(Math.round(v)).replace(/\\B(?=(\\d{3})+(?!\\d))/g,' ');}
            var ALL={};['tDays','tActs','tDist','tTime','tSteps'].forEach(function(id){var e=document.getElementById(id);if(e)ALL[id]=e.textContent;});
            function setTile(id,v){var e=document.getElementById(id);if(e)e.textContent=v===null?ALL[id]:v;}
            function filterRows(){
              var q=F.q.value.toLowerCase().trim(),sp=F.sport.value,mn=km(F.min),mx=km(F.max),
                  sr=speedRange(),smn=sr[0],smx=sr[1],from=F.from.value,to=F.to.value,
                  act=!!sp||mn!==null||mx!==null||smn!==null||smx!==null,any=act||!!q||!!from||!!to,
                  n=0,tot=0,dur=0,steps=0,days=0,gc={},tc={},recs=[],
      // A pace only makes sense for the sports measured in it; other sports never match a pace filter.
      pg=smn===null&&smx===null?null:F.su.value==='km'?['running','walking']:F.su.value==='100'?['swimming']:null;
              var typ=sp.indexOf('t:')===0?sp.slice(2):null;
              document.querySelectorAll('section.month').forEach(function(s){var shown=false;
                s.querySelectorAll('tbody tr').forEach(function(r){
                  var rowActs=[];
      var dt=r.dataset.date||'',ok=(!from||dt>=from)&&(!to||dt<=to)&&(!q||r.textContent.toLowerCase().indexOf(q)>=0),hit=0,d=0,t=0;
                  var dl=r.querySelectorAll('.dists > span'),pl=r.querySelectorAll('.paces > span');
                  r.querySelectorAll('a.act').forEach(function(a,i){
                    var dist=parseFloat(a.dataset.dist||'0'),v=parseFloat(a.dataset.kmh||'0'),
                        other=(mn===null||dist>=mn)&&(mx===null||dist<=mx)&&(smn===null||(v>0&&v>=smn))&&(smx===null||(v>0&&v<=smx))
              &&(!pg||pg.indexOf(a.dataset.group)>=0),
                        m=other&&(!sp||(typ!==null?a.dataset.sport===typ:a.dataset.group===sp));
                    [a,dl[i],pl[i]].forEach(function(x){if(x)x.classList.toggle('dim',act&&!m);});
                    // The activity tiles count everything that passes the other filters, so they show what each sport adds.
                    if(ok&&other){gc[a.dataset.group]=(gc[a.dataset.group]||0)+1;tc[a.dataset.sport]=(tc[a.dataset.sport]||0)+1;}
                    if(m){hit++;d+=dist;t+=parseFloat(a.dataset.dur||'0');
          rowActs.push({a:a,date:dt,group:a.dataset.group,dist:dist,kmh:v,dur:parseFloat(a.dataset.dur||'0')});}});
                  if(act&&!hit)ok=false;
                  if(ok){days++;n+=hit;tot+=d;dur+=t;steps+=parseFloat(r.dataset.steps||'0');recs.push.apply(recs,rowActs);}
                  r.style.display=ok?'':'none';shown=shown||ok;});
                s.style.display=shown?'':'none';});
              renderRecords(recs,any);renderTrend(recs,from,to);CAL.year=null;renderCalendar(recs,to);
  document.getElementById('totals').classList.toggle('filtered',any);
              setTile('tDays',any?String(days):null);setTile('tActs',any?String(n):null);
              setTile('tDist',any?fmtKm(tot):null);setTile('tTime',any?fmtDur(dur):null);setTile('tSteps',any?fmtInt(steps):null);
              document.querySelectorAll('.tile.link').forEach(function(t){
                t.classList.toggle('on',!!sp&&(t.dataset.sport===sp||(typ!==null&&(t.dataset.types||'').split(' ').indexOf(typ)>=0)));
                var c=gc[t.dataset.sport]||0;t.querySelector('.n').textContent=c;t.classList.toggle('zero',c===0);
                t.querySelectorAll('[data-type]').forEach(function(x){x.textContent=tc[x.dataset.type]||0;});});
              document.querySelectorAll('.ranges a').forEach(function(a){var r=rangeOf(a.dataset.range);
                a.classList.toggle('on',F.from.value===r[0]&&F.to.value===r[1]);});
              var st=document.getElementById('filterStatus');st.innerHTML='';
              if(any){
                st.textContent=n+(n===1?' activity on ':' activities on ')+days+(days===1?' day':' days')+(tot>0?' · '+fmtKm(tot):'')+(dur>0?' · '+fmtDur(dur):'')+' ';
                var x=document.createElement('a');x.href='#';x.textContent='✕ clear';x.onclick=clearFilters;st.appendChild(x);}
              var p=new URLSearchParams();
              if(sp)p.set('sport',sp);['min','max','smin','smax','from','to'].forEach(function(k){if(F[k].value)p.set(k,F[k].value);});
              if((F.smin.value||F.smax.value)&&F.su.value!=='kmh')p.set('su',F.su.value);
              if(q)p.set('q',F.q.value);
              var h=p.toString();history.replaceState(null,'',h?'#'+h:location.pathname+location.search);
              // Remembered so a day report's "← Overview" link can come back to the same filtered view.
              try{localStorage.setItem(window.FILTER_KEY||'garmin-overview-filter',h);}catch(e){}}
            // ---- records, from the activities the filters leave
            // Speeds above these are GPS glitches (or a ride saved as a run), not records.
            var MAX_KMH={running:20,walking:12,swimming:8,cycling:65,indoor_cycling:70,rowing_and_paddling:20};
            function paceStr(kmh,per100){return fmtDur((per100?360:3600)/kmh)+(per100?' /100 m':' /km');}
            var RECORDS=[
              {label:'Longest run',g:'running',by:'dist'},
              {label:'Fastest run, 5 km or more',g:'running',min:5000,by:'kmh'},
              {label:'Fastest run, 10 km or more',g:'running',min:10000,by:'kmh'},
              {label:'Fastest half marathon or more',g:'running',min:21000,by:'kmh'},
              {label:'Fastest marathon or more',g:'running',min:42000,by:'kmh'},
              {label:'Longest ride',g:'cycling',by:'dist'},
              {label:'Fastest ride, 40 km or more',g:'cycling',min:40000,by:'kmh'},
              {label:'Fastest ride, 100 km or more',g:'cycling',min:100000,by:'kmh'},
              {label:'Longest indoor ride',g:'indoor_cycling',by:'dur'},
              {label:'Longest walk or hike',g:'walking',by:'dist'},
              {label:'Longest swim',g:'swimming',by:'dist'},
              {label:'Fastest swim, 1 km or more',g:'swimming',min:1000,by:'kmh'},
              {label:'Longest row or paddle',g:'rowing_and_paddling',by:'dist'}];
            function renderRecords(list,any){
              var box=document.getElementById('records'),tiles=document.getElementById('recordTiles');tiles.innerHTML='';
              RECORDS.forEach(function(rec){var best=null;
                list.forEach(function(x){
                  if(x.a.dataset.norec)return;if(rec.g&&x.group!==rec.g)return;if(rec.min&&x.dist<rec.min)return;
                  if(rec.by==='kmh'&&(!(x.kmh>0)||x.kmh>(MAX_KMH[x.group]||999)))return;
                  var v=x[rec.by];if(!(v>0))return;if(!best||v>best[rec.by])best=x;});
                if(!best)return;
                var per100=best.group==='swimming',pace=best.group==='running'||best.group==='walking'||per100;
                var v=rec.by==='dist'?fmtKm(best.dist):rec.by==='dur'?fmtDur(best.dur):pace?paceStr(best.kmh,per100):best.kmh.toFixed(1)+' km/h';
                var extra=rec.by==='kmh'?fmtKm(best.dist)+' in '+fmtDur(best.dur):rec.by==='dist'?fmtDur(best.dur)+(best.kmh>0?' · '+(pace?paceStr(best.kmh,per100):best.kmh.toFixed(1)+' km/h'):''):(best.dist>0?fmtKm(best.dist):'');
                var t=document.createElement('a');t.className='tile link';t.href=best.a.getAttribute('href');t.title='Open this activity';
                var vd=document.createElement('div');vd.className='v';vd.textContent=v;
                var ld=document.createElement('div');ld.className='l';ld.textContent=rec.label;
                var sm=document.createElement('small');sm.innerHTML='<br>';sm.appendChild(document.createTextNode(best.date+' · '+best.a.textContent.trim()));
                sm.appendChild(document.createElement('br'));sm.appendChild(document.createTextNode(extra));
                ld.appendChild(sm);t.appendChild(vd);t.appendChild(ld);tiles.appendChild(t);});
              box.hidden=!tiles.children.length;
              document.getElementById('recordsSub').textContent=any?'Within the current selection. Click a record to open the activity.'
                :'All activities. They follow the filters above, e.g. pick This year for this year’s records. Click a record to open the activity.';}
            // ---- per period chart (distance, time or count), stacked by sport; also follows the filters
            var SERIES=[['running','Running'],['cycling','Cycling'],['indoor_cycling','Indoor cycling'],['walking','Walking'],['swimming','Swimming'],['other','Other']];
            var METRIC='dist';try{METRIC=localStorage.getItem('garmin-overview-metric')||'dist';}catch(e){}
            var LAST={recs:[],from:'',to:''};
            function setMetric(m){METRIC=m;try{localStorage.setItem('garmin-overview-metric',m);}catch(e){}renderTrend(LAST.recs,LAST.from,LAST.to);return false;}
            var MONTHS=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
            var MONTHS_FULL=['January','February','March','April','May','June','July','August','September','October','November','December'];
            function seriesOf(g){for(var i=0;i<5;i++)if(SERIES[i][0]===g)return i;return 5;}
            function utc(s){return new Date(Date.UTC(+s.slice(0,4),+s.slice(5,7)-1,+s.slice(8,10)));}
            function iso(d){return d.toISOString().slice(0,10);}
            function metricStr(v){return METRIC==='dist'?(v>=100?fmtInt(v):v.toFixed(1))+' km':METRIC==='dur'?(v>=10?fmtInt(v):Math.floor(v)+':'+String(Math.round(v%1*60)).padStart(2,'0'))+' h':fmtInt(v);}
            function renderTrend(recs,from,to){LAST={recs:recs,from:from,to:to};
              var box=document.getElementById('trend');if(!recs.length){box.hidden=true;return;}box.hidden=false;
              var lo=from,hi=to;recs.forEach(function(r){if(!from&&(!lo||r.date<lo))lo=r.date;if(!to&&(!hi||r.date>hi))hi=r.date;});
              var a=utc(lo),b=utc(hi),months=(b.getUTCFullYear()-a.getUTCFullYear())*12+b.getUTCMonth()-a.getUTCMonth()+1;
              var unit=(b-a)/864e5<=62?'day':months<=36?'month':'year',keys=[],idx={};
              if(unit==='day')for(var d=new Date(a);d<=b;d.setUTCDate(d.getUTCDate()+1))keys.push(iso(d));
              else if(unit==='month')for(var y=a.getUTCFullYear(),m=a.getUTCMonth();y*12+m<=b.getUTCFullYear()*12+b.getUTCMonth();m++){if(m===12){m=0;y++;}keys.push(y+'-'+String(m+1).padStart(2,'0'));}
              else for(var y2=a.getUTCFullYear();y2<=b.getUTCFullYear();y2++)keys.push(String(y2));
              var cut=unit==='day'?10:unit==='month'?7:4;keys.forEach(function(k,i){idx[k]=i;});
              var vals=keys.map(function(){return [0,0,0,0,0,0];}),used=[0,0,0,0,0,0];
              recs.forEach(function(r){var i=idx[r.date.slice(0,cut)];if(i===undefined)return;
                var v=METRIC==='dist'?r.dist/1000:METRIC==='dur'?r.dur/3600:1;var s=seriesOf(r.group);vals[i][s]+=v;used[s]+=v;});
              var totals=vals.map(function(v){return v.reduce(function(x,y){return x+y;},0);}),max=Math.max.apply(null,totals.concat([0]));
              var what=METRIC==='dist'?'Distance':METRIC==='dur'?'Time':'Activities';
              document.getElementById('trendTitle').textContent=what+' per '+unit;
              document.getElementById('trendSub').textContent=(unit==='day'?'':'Click a bar to zoom in to that '+unit+'. ')+'Follows the filters above.';
              document.querySelectorAll('#trendMetric a').forEach(function(x){x.classList.toggle('on',x.dataset.m===METRIC);});
              var lg=document.getElementById('trendLegend');lg.innerHTML='';
              SERIES.forEach(function(s,i){if(!used[i])return;var e=document.createElement('span');var sw=document.createElement('i');sw.style.background='var(--s'+(i+1)+')';
                e.appendChild(sw);e.appendChild(document.createTextNode(s[1]+' '+metricStr(used[i])));lg.appendChild(e);});
              // Axis: a round step so there are 3-5 grid lines.
              var step=1;if(max>0){var raw=max/4,p=Math.pow(10,Math.floor(Math.log10(raw)));step=[1,2,2.5,5,10].map(function(f){return f*p;}).find(function(x){return x>=raw;});}
              var top=max>0?Math.ceil(max/step)*step:1,W=1000,H=240,L=56,R=8,T=10,B=26,pw=W-L-R,ph=H-T-B,bw=pw/keys.length,w=Math.max(1,Math.min(bw*0.72,48));
              var o=['<svg viewBox="0 0 '+W+' '+H+'" role="img" aria-label="'+what+' per '+unit+'">'];
              for(var g=0;g<=top+1e-9;g+=step){var gy=T+ph-g/top*ph;o.push('<line class="grid" x1="'+L+'" x2="'+(W-R)+'" y1="'+gy+'" y2="'+gy+'"/><text class="ax" x="'+(L-6)+'" y="'+(gy+4)+'" text-anchor="end">'+(g%1?g.toFixed(1):fmtInt(g))+'</text>');}
              var every=Math.ceil(keys.length/(unit==='year'?20:12));
              keys.forEach(function(k,i){var x=L+i*bw+(bw-w)/2,y=T+ph,segs=[];
                for(var s=0;s<6;s++){var v=vals[i][s];if(!v)continue;var hgt=v/top*ph;segs.push([s,y-hgt,hgt]);y-=hgt;}
                segs.forEach(function(sg,j){var gap=j<segs.length-1&&sg[2]>3?2:0,yy=sg[1]+gap,hh=Math.max(sg[2]-gap,0.5);
                  if(j===segs.length-1&&w>=6&&hh>4){var r=Math.min(4,w/2);
                    o.push('<path fill="var(--s'+(sg[0]+1)+')" d="M'+x+' '+(yy+hh)+'V'+(yy+r)+'Q'+x+' '+yy+' '+(x+r)+' '+yy+'H'+(x+w-r)+'Q'+(x+w)+' '+yy+' '+(x+w)+' '+(yy+r)+'V'+(yy+hh)+'Z"/>');}
                  else o.push('<rect fill="var(--s'+(sg[0]+1)+')" x="'+x+'" y="'+yy+'" width="'+w+'" height="'+hh+'"/>');});
                if(i%every===0){var lab=unit==='year'?k:unit==='month'?MONTHS[+k.slice(5)-1]+(k.slice(5)==='01'||i===0?' '+k.slice(0,4):''):(+k.slice(8))+(k.slice(8)==='01'||i===0?' '+MONTHS[+k.slice(5,7)-1]:'');
                  o.push('<text class="ax" x="'+(L+i*bw+bw/2)+'" y="'+(H-8)+'" text-anchor="middle">'+lab+'</text>');}
                o.push('<rect class="hit" data-i="'+i+'" x="'+(L+i*bw)+'" y="'+T+'" width="'+bw+'" height="'+ph+'" fill="transparent"/>');});
              o.push('</svg>');
              var c=document.getElementById('trendChart');c.innerHTML=o.join('');
              var tip=document.getElementById('tip');
              function name(k){return unit==='year'?k:unit==='month'?MONTHS_FULL[+k.slice(5)-1]+' '+k.slice(0,4):k+' '+['Sun','Mon','Tue','Wed','Thu','Fri','Sat'][utc(k).getUTCDay()];}
              c.querySelectorAll('rect.hit').forEach(function(h){var i=+h.dataset.i,k=keys[i];
                if(unit!=='day')h.style.cursor='pointer';
                h.addEventListener('mousemove',function(ev){var t='<b>'+name(k)+' · '+metricStr(totals[i])+'</b>';
                  for(var s=0;s<6;s++)if(vals[i][s])t+='<br>'+SERIES[s][1]+': '+metricStr(vals[i][s]);
                  tip.innerHTML=t;tip.style.display='block';tip.style.left=Math.min(ev.clientX+12,window.innerWidth-tip.offsetWidth-8)+'px';tip.style.top=(ev.clientY-tip.offsetHeight-10)+'px';});
                h.addEventListener('mouseleave',function(){tip.style.display='none';});
                h.addEventListener('click',function(){if(unit==='day'){var row=document.querySelector('tr[data-date="'+k+'"]');if(row)row.scrollIntoView({block:'center'});return;}
                  tip.style.display='none';
                  if(unit==='year'){F.from.value=k+'-01-01';F.to.value=k+'-12-31';}
                  else{var e=new Date(Date.UTC(+k.slice(0,4),+k.slice(5,7),0));F.from.value=k+'-01';F.to.value=iso(e);}
                  filterRows();});});}
            // ---- calendar: one square per day of a year, coloured by the day's main sport (most time)
            var CAL={year:null,recs:[],to:''},DAY_HREF=null;
            function stepCal(d){CAL.year+=d;renderCalendar(CAL.recs,CAL.to);return false;}
            function renderCalendar(recs,to){CAL.recs=recs;CAL.to=to;
              var box=document.getElementById('calendar');if(!recs.length){box.hidden=true;return;}box.hidden=false;
              if(!DAY_HREF){DAY_HREF={};document.querySelectorAll('tr[data-date]').forEach(function(r){var a=r.querySelector('td a');if(a)DAY_HREF[r.dataset.date]=a.getAttribute('href');});}
              var minY=+recs.reduce(function(m,r){return r.date<m?r.date:m;},'9999').slice(0,4),maxY=+recs.reduce(function(m,r){return r.date>m?r.date:m;},'0000').slice(0,4);
              if(CAL.year===null)CAL.year=to?Math.min(+to.slice(0,4),maxY):maxY;
              CAL.year=Math.max(minY,Math.min(maxY,CAL.year));var Y=CAL.year;
              document.getElementById('calPrev').style.visibility=Y>minY?'':'hidden';document.getElementById('calNext').style.visibility=Y<maxY?'':'hidden';
              var byDay={};recs.forEach(function(r){if(+r.date.slice(0,4)!==Y)return;(byDay[r.date]=byDay[r.date]||[]).push(r);});
              var C=14,G=3,L=30,T=18,jan1=new Date(Date.UTC(Y,0,1)),off=(jan1.getUTCDay()+6)%7,o=[],used=[0,0,0,0,0,0];
              var days=(Date.UTC(Y+1,0,1)-jan1)/864e5,weeks=Math.ceil((days+off)/7),W=L+weeks*(C+G),H=T+7*(C+G);
              o.push('<svg viewBox="0 0 '+W+' '+H+'" role="img" aria-label="Activity calendar '+Y+'">');
              ['Mon','','Wed','','Fri','',''].forEach(function(n,i){if(n)o.push('<text class="ax" x="0" y="'+(T+i*(C+G)+C-3)+'">'+n+'</text>');});
              var active=0,streak=0,best=0,run=0;
              for(var i=0;i<days;i++){var d=new Date(jan1.getTime()+i*864e5),k=iso(d),wk=Math.floor((i+off)/7),wd=(i+off)%7,x=L+wk*(C+G),y=T+wd*(C+G);
                if(d.getUTCDate()===1)o.push('<text class="ax" x="'+x+'" y="'+(T-6)+'">'+MONTHS[d.getUTCMonth()]+'</text>');
                var list=byDay[k],fill;
                if(list){var t=[0,0,0,0,0,0];list.forEach(function(r){t[seriesOf(r.group)]+=r.dur||1;});var s=t.indexOf(Math.max.apply(null,t));used[s]++;
                  fill='var(--s'+(s+1)+')';active++;run++;best=Math.max(best,run);}
                else{run=0;fill=DAY_HREF[k]?'var(--line)':'transparent';}
                o.push('<rect class="day" data-d="'+k+'" x="'+x+'" y="'+y+'" width="'+C+'" height="'+C+'" rx="3" fill="'+fill+'"'+(fill==='transparent'?' stroke="var(--line)"':'')+'/>');}
              o.push('</svg>');
              var c=document.getElementById('calChart');c.innerHTML=o.join('');
              document.getElementById('calTitle').textContent='Calendar '+Y;
              document.getElementById('calSub').textContent=active+' active days · longest streak '+best+(best===1?' day':' days')+'. Coloured by the sport with the most time that day; click a day to open it.';
              var lg=document.getElementById('calLegend');lg.innerHTML='';
              SERIES.forEach(function(s,i){if(!used[i])return;var e=document.createElement('span'),sw=document.createElement('i');sw.style.background='var(--s'+(i+1)+')';
                e.appendChild(sw);e.appendChild(document.createTextNode(s[1]+' '+used[i]+(used[i]===1?' day':' days')));lg.appendChild(e);});
              var e=document.createElement('span'),sw=document.createElement('i');sw.style.background='var(--line)';e.appendChild(sw);e.appendChild(document.createTextNode('nothing that matches'));lg.appendChild(e);
              var tip=document.getElementById('tip');
              c.querySelectorAll('rect.day').forEach(function(r){var k=r.dataset.d;if(DAY_HREF[k])r.style.cursor='pointer';
                r.addEventListener('mousemove',function(ev){var list=byDay[k],t='<b>'+k+' '+['Sun','Mon','Tue','Wed','Thu','Fri','Sat'][utc(k).getUTCDay()]+'</b>';
                  if(list)list.forEach(function(x){t+='<br>';var sp=document.createElement('span');sp.textContent=x.a.textContent.trim()+(x.dist>0?' · '+fmtKm(x.dist):'')+(x.dur>0?' · '+fmtDur(x.dur):'');t+=sp.innerHTML;});
                  else t+='<br>'+(DAY_HREF[k]?'nothing that matches':'no data');
                  tip.innerHTML=t;tip.style.display='block';tip.style.left=Math.min(ev.clientX+12,window.innerWidth-tip.offsetWidth-8)+'px';tip.style.top=(ev.clientY-tip.offsetHeight-10)+'px';});
                r.addEventListener('mouseleave',function(){tip.style.display='none';});
                r.addEventListener('click',function(){if(DAY_HREF[k])location.href=DAY_HREF[k];});});}
            // ---- folding panels (chart, calendar, records); which ones are open is remembered
            var OPEN={};try{OPEN=JSON.parse(localStorage.getItem('garmin-overview-panels')||'{}')||{};}catch(e){}
            function showPanels(){document.querySelectorAll('[data-panel]').forEach(function(b){var id=b.dataset.panel,on=!!OPEN[id];
              b.classList.toggle('on',on);document.getElementById(id).classList.toggle('folded',!on);});}
            function togglePanel(id){OPEN[id]=!OPEN[id];try{localStorage.setItem('garmin-overview-panels',JSON.stringify(OPEN));}catch(e){}showPanels();return false;}
            showPanels();
            (function(){if(location.hash.length<2){try{localStorage.setItem(window.FILTER_KEY||'garmin-overview-filter','');}catch(e){}filterRows();return;}
              var p=new URLSearchParams(location.hash.slice(1));
              KEYS.forEach(function(k){F[k].value=p.get(k)||'';});
              // An older link may name a single Garmin type that is now part of a group.
              var want=p.get('sport');if(want&&F.sport.value!==want){F.sport.value='t:'+want;if(F.sport.value!=='t:'+want)F.sport.value='';}
              if(p.get('su'))F.su.value=p.get('su');else{F.su.value='kmh';autoUnit();}updUnit();
              filterRows();
              // Coming back from a day report: scroll to that day and mark it briefly ('at' is dropped from the URL).
              var at=p.get('at');if(!at)return;
              var a=Array.prototype.find.call(document.querySelectorAll('tbody tr td:first-child a'),function(x){return x.textContent===at;});
              if(a){var tr=a.closest('tr');tr.scrollIntoView({block:'center'});tr.classList.add('back-here');
                setTimeout(function(){tr.classList.remove('back-here');},2500);}})();
            """;

    /** Next to the overview; lists every report so a report can link to the previous and next day. */
    static final String DAYS_FILE = "days.js";

    /** Previous/next day links in a report's top bar, from days.js; the ← and → keys follow them too. */
    private static final String DAY_STEP_JS = """
            (function(){var d=window.GARMIN_DAYS;if(!d)return;
              var i=d.findIndex(function(h){return h.slice(-22,-12)===THIS_DAY;});if(i<0)return;
              function set(id,j,label){var a=document.getElementById(id);if(j<0||j>=d.length)return null;
                a.href=DAY_BASE+d[j];a.textContent=label(d[j].slice(-22,-12));a.hidden=false;return a;}
              var p=set('prevDay',i-1,function(x){return '‹ '+x;}),n=set('nextDay',i+1,function(x){return x+' ›';});
              document.addEventListener('keydown',function(e){
                if(e.altKey||e.ctrlKey||e.metaKey||e.shiftKey||/INPUT|SELECT|TEXTAREA/.test(e.target.tagName))return;
                if(e.key==='ArrowLeft'&&p)location.href=p.href;else if(e.key==='ArrowRight'&&n)location.href=n.href;});})();
            """;

    private static final String JS = """
            (function(){
              var tip=document.getElementById('tip');
              document.querySelectorAll('svg[data-pts]').forEach(function(svg){
                var pts; try{pts=JSON.parse(svg.getAttribute('data-pts'));}catch(e){return;}
                if(!pts.length) return;
                var cur=svg.querySelector('.cursor'), dot=svg.querySelector('.dot');
                svg.addEventListener('mousemove',function(ev){
                  var r=svg.getBoundingClientRect(), vb=svg.viewBox.baseVal;
                  var x=(ev.clientX-r.left)*vb.width/r.width, lo=0, hi=pts.length-1;
                  while(hi-lo>1){var m=(lo+hi)>>1; if(pts[m][0]<x) lo=m; else hi=m;}
                  var p=Math.abs(pts[lo][0]-x)<Math.abs(pts[hi][0]-x)?pts[lo]:pts[hi];
                  cur.setAttribute('x1',p[0]);cur.setAttribute('x2',p[0]);dot.setAttribute('cx',p[0]);dot.setAttribute('cy',p[1]);
                  tip.textContent=p[2];tip.style.display='block';
                  tip.style.left=Math.min(ev.clientX+12,window.innerWidth-tip.offsetWidth-8)+'px';tip.style.top=(ev.clientY-30)+'px';
                });
                svg.addEventListener('mouseleave',function(){tip.style.display='none';cur.setAttribute('x1',-10);cur.setAttribute('x2',-10);dot.setAttribute('cx',-10);});
              });
              // Click a column header to sort; click again to reverse. Durations (m:ss, h:mm:ss) sort as seconds,
              // numbers ignore units and thousands spaces, empty cells always go last.
              // Back to the overview with the filter it last had, scrolled to this day.
              window.toOverview=function(link){var f='';try{f=localStorage.getItem(window.FILTER_KEY||'garmin-overview-filter')||'';}catch(e){}
                var p=new URLSearchParams(f);if(link.dataset.day)p.set('at',link.dataset.day);
                link.href=link.href.split('#')[0]+'#'+p.toString();return true;};
              window.toggleSub=function(tr){var s=tr.nextElementSibling;if(!s||!s.classList.contains('sub'))return;
                s.hidden=!s.hidden;tr.classList.toggle('open',!s.hidden);};
              function key(td){
                if(td&&td.hasAttribute('data-value')) return parseFloat(td.getAttribute('data-value'));
                var t=(td?td.textContent:'').trim();
                if(!t||t==='–'||t==='—') return null;
                var d=t.match(/^-?\\d+(:\\d\\d)+/);
                if(d){var p=d[0].replace('-','').split(':'),v=0;p.forEach(function(x){v=v*60+ +x;});return t[0]==='-'?-v:v;}
                var n=t.replace(/(\\d) (?=\\d{3}\\b)/g,'$1').match(/^[-+−]?\\d+(\\.\\d+)?/);
                if(n) return parseFloat(n[0].replace('−','-'));
                return t.toLowerCase();
              }
              document.querySelectorAll('table').forEach(function(table){
                var head=table.tHead, body=table.tBodies[0];
                if(!head||!body) return;
                Array.prototype.forEach.call(head.rows[0].cells,function(th,col){
                  th.classList.add('sortable');
                  th.addEventListener('click',function(){
                    var asc=th.getAttribute('data-sort')!=='asc';
                    Array.prototype.forEach.call(head.rows[0].cells,function(o){o.removeAttribute('data-sort');});
                    th.setAttribute('data-sort',asc?'asc':'desc');
                    // Expandable detail rows (tr.sub) travel with the row above them.
                    var rows=Array.prototype.filter.call(body.rows,function(r){
                      if(r.classList.contains('sub')){var p=r.previousElementSibling;if(p)p._sub=r;return false;}return true;});
                    rows.sort(function(a,b){
                      var x=key(a.cells[col]),y=key(b.cells[col]);
                      if(x===null) return y===null?0:1;
                      if(y===null) return -1;
                      var r=(typeof x===typeof y)?(x<y?-1:x>y?1:0):(typeof x==='number'?-1:1);
                      return asc?r:-r;
                    });
                    rows.forEach(function(r){body.appendChild(r);if(r._sub)body.appendChild(r._sub);});
                  });
                });
              });
              if(window.L){
                document.querySelectorAll('.map[data-track]').forEach(function(el){
                  var t=JSON.parse(el.getAttribute('data-track')); if(!t.length) return;
                  var map=L.map(el,{scrollWheelZoom:false,zoomAnimation:false});
                  var line=L.polyline(t,{color:'#e5484d',weight:4,opacity:.85}).addTo(map);
                  map.fitBounds(line.getBounds(),{padding:[20,20],animate:false});
                  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap'}).addTo(map);
                  L.circleMarker(t[0],{radius:6,color:'#16a34a',fillOpacity:1}).addTo(map).bindTooltip('Start');
                  L.circleMarker(t[t.length-1],{radius:6,color:'#1d2329',fillOpacity:1}).addTo(map).bindTooltip('Finish');
                });
              } else {
                document.querySelectorAll('.map').forEach(function(el){el.innerHTML='<p style="padding:12px">Map needs an internet connection (Leaflet + OpenStreetMap).</p>';});
              }
            })();
            """;
}
