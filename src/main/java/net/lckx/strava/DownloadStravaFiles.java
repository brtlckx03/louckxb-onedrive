package net.lckx.strava;

import java.io.Console;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads Strava activities (GPX + TCX + original file + metadata JSON) into
 * {@code src/main/resources/strava/YYYY/MM/}.
 *
 * <p>Strava's public API was paywalled in 2024, so this uses the same web endpoints your
 * browser hits when you're logged in. Auth is a session cookie you paste from your browser
 * into the {@code STRAVA_SESSION_COOKIE} environment variable — no password ever touches
 * this tool. The cookie expires every ~2 weeks; refresh it when the tool starts failing.
 *
 * <p>To get the cookie:
 * <ol>
 *   <li>Log in to strava.com in Chrome/Firefox/Safari.</li>
 *   <li>Open DevTools → Application → Cookies → strava.com.</li>
 *   <li>Copy the value of the {@code _strava4_session} cookie.</li>
 *   <li>{@code export STRAVA_SESSION_COOKIE='<paste value>'} (or set it in IntelliJ Run config).</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   java --enable-preview src/main/java/net/lckx/strava/DownloadStravaFiles.java \
 *        --since 2020-01-01 [--until 2026-08-19] [--list-only]
 * </pre>
 * Must be run from the project root so {@code src/main/resources/strava} resolves.
 */
public class DownloadStravaFiles {

    private static final String STRAVA_BASE = "https://www.strava.com";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private static final Path DOWNLOAD_DIR = Path.of("src/main/resources/strava");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static void main(String[] args) throws Exception {
        LocalDate since = null;
        LocalDate until = null;
        boolean listOnly = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--since" -> since = LocalDate.parse(args[++i]);
                case "--until" -> until = LocalDate.parse(args[++i]);
                case "--list-only" -> listOnly = true;
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println("Usage: DownloadStravaFiles --since YYYY-MM-DD [--until YYYY-MM-DD] [--list-only]");
                    System.exit(1);
                }
            }
        }
        LocalDate today = LocalDate.now();
        LocalDate defaultSince = today.withDayOfMonth(1);
        if (since == null) since = promptForDate("--since date (YYYY-MM-DD)", defaultSince);
        if (until == null) until = promptForDate("--until date (YYYY-MM-DD)", today);

        String cookie = readCookie();
        Files.createDirectories(DOWNLOAD_DIR);

        System.out.printf("Fetching Strava activities from %s to %s...%n", since, until);
        List<Activity> activities = listActivities(cookie, since, until);
        System.out.printf("Found %d activities.%n", activities.size());

        if (listOnly) {
            for (Activity a : activities) {
                System.out.printf("  %s  id=%d  %s%n", a.date, a.id, a.name);
            }
            System.out.println("(list-only mode; no downloads)");
            return;
        }

        int downloaded = 0, skipped = 0, failed = 0;
        for (Activity a : activities) {
            String base = a.date + "_" + a.id + "_" + sanitize(a.name);
            Path dir = DOWNLOAD_DIR
                    .resolve(a.date.substring(0, 4))
                    .resolve(a.date.substring(5, 7));
            Files.createDirectories(dir);
            Path gpx = dir.resolve(base + ".gpx");
            Path tcx = dir.resolve(base + ".tcx");
            Path fit = dir.resolve(base + ".fit");
            Path json = dir.resolve(base + ".json");
            if (Files.exists(gpx) && Files.exists(tcx) && Files.exists(fit) && Files.exists(json)) {
                skipped++;
                continue;
            }
            try {
                if (!Files.exists(json)) {
                    Files.writeString(json, a.rawJson);
                    downloaded++;
                    System.out.printf("  [%d] %s.json%n", downloaded, base);
                }
                if (!Files.exists(gpx)) {
                    if (downloadExport(cookie, a.id, "gpx", gpx)) {
                        downloaded++;
                        System.out.printf("  [%d] %s.gpx%n", downloaded, base);
                    }
                }
                if (!Files.exists(tcx)) {
                    if (downloadExport(cookie, a.id, "tcx", tcx)) {
                        downloaded++;
                        System.out.printf("  [%d] %s.tcx%n", downloaded, base);
                    }
                }
                if (!Files.exists(fit)) {
                    if (downloadOriginal(cookie, a.id, fit)) {
                        downloaded++;
                        System.out.printf("  [%d] %s.fit%n", downloaded, base);
                    }
                }
                Thread.sleep(500);
            } catch (Exception e) {
                System.err.printf("  FAILED activity %d (%s): %s%n", a.id, a.name, e.getMessage());
                failed++;
            }
        }
        System.out.printf("Done. downloaded=%d skipped=%d failed=%d%n", downloaded, skipped, failed);
    }

    // ==================== Auth ====================

    private static String readCookie() {
        String c = System.getenv("STRAVA_SESSION_COOKIE");
        if (c == null || c.isBlank()) {
            System.err.println("""
                    No STRAVA_SESSION_COOKIE set.
                    Get it from your browser:
                      1. Log in to strava.com
                      2. DevTools > Application > Cookies > strava.com
                      3. Copy the value of the "_strava4_session" cookie
                      4. export STRAVA_SESSION_COOKIE='<paste value>'  (or set it in IntelliJ Run config)
                    The cookie expires every ~2 weeks; refresh when the tool starts failing.
                    """);
            System.exit(1);
        }
        return c.trim();
    }

    /**
     * Accepts either just the {@code _strava4_session} value, or a full cookie header string
     * ({@code name=value; other=value}). Auto-wraps the bare value if there's no {@code =}.
     */
    private static String cookieHeader(String cookie) {
        return cookie.contains("=") ? cookie : "_strava4_session=" + cookie;
    }

    // ==================== Activities ====================

    private record Activity(long id, String date, String name, String rawJson) {
    }

    private static List<Activity> listActivities(String cookie, LocalDate since, LocalDate until) throws Exception {
        LocalDate today = LocalDate.now();
        if (until.isAfter(today)) until = today;
        List<Activity> out = new ArrayList<>();
        int page = 1;
        int perPage = 200;
        while (true) {
            String url = STRAVA_BASE + "/athlete/training_activities"
                    + "?keywords="
                    + "&activity_type="
                    + "&workout_type="
                    + "&commute="
                    + "&private_activities="
                    + "&trainer="
                    + "&gear="
                    + "&per_page=" + perPage
                    + "&page=" + page
                    + "&new_activity_only=false";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Cookie", cookieHeader(cookie))
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("training_activities failed: " + resp.statusCode()
                        + " " + trimForLog(resp.body()));
            }
            String body = resp.body();
            if (looksLikeHtml(body)) {
                throw new IOException(
                        "Got HTML instead of JSON — session cookie invalid or expired. "
                                + "Refresh STRAVA_SESSION_COOKIE from your browser.");
            }
            List<String> items = extractStravaActivities(body);
            boolean wentBelowSince = false;
            for (String item : items) {
                long id = jsonLong(item, "id");
                if (id == 0) continue;
                String dateStr = jsonString(item, "start_date_local");
                if (dateStr.length() < 10) continue;
                String date = dateStr.substring(0, 10);
                LocalDate d;
                try {
                    d = LocalDate.parse(date);
                } catch (Exception e) {
                    continue;
                }
                if (d.isBefore(since)) {
                    wentBelowSince = true;
                    continue;
                }
                if (d.isAfter(until)) continue;
                String name = jsonString(item, "name");
                if (name.isEmpty()) name = jsonString(item, "type");
                if (name.isEmpty()) name = "activity";
                out.add(new Activity(id, date, name, item));
            }
            // Results come back newest-first; stop paging once we cross the since date.
            if (wentBelowSince || items.size() < perPage) break;
            page++;
        }
        out.sort(Comparator.comparing(Activity::date).thenComparingLong(Activity::id));
        return out;
    }

    /**
     * Strava's response is typically {@code {"models":[...], "page":..., "perPage":...}} but
     * some endpoints return a bare array. Handle both by locating the {@code "models"} key
     * when present, then reusing the generic top-level array walker.
     */
    private static List<String> extractStravaActivities(String json) {
        int idx = json.indexOf("\"models\"");
        return idx < 0
                ? jsonTopLevelArrayObjects(json)
                : jsonTopLevelArrayObjects(json.substring(idx));
    }

    // ==================== Downloads ====================

    /** Returns true if downloaded, false if Strava has no such export for the activity. */
    private static boolean downloadExport(String cookie, long activityId, String format, Path target)
            throws Exception {
        String url = STRAVA_BASE + "/activities/" + activityId + "/export_" + format;
        return downloadWithCookie(cookie, url, target);
    }

    /** Original uploaded file (usually .fit). Saved as {@code base.fit} regardless of true type. */
    private static boolean downloadOriginal(String cookie, long activityId, Path target) throws Exception {
        String url = STRAVA_BASE + "/activities/" + activityId + "/export_original";
        return downloadWithCookie(cookie, url, target);
    }

    private static boolean downloadWithCookie(String cookie, String url, Path target) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Cookie", cookieHeader(cookie))
                .header("User-Agent", BROWSER_UA)
                .GET().build();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        HttpResponse<Path> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            // 302 → login redirect, 404 → no such export, both non-fatal.
            if (resp.statusCode() == 302 || resp.statusCode() == 404) return false;
            throw new IOException("HTTP " + resp.statusCode());
        }
        String contentType = resp.headers().firstValue("content-type").orElse("").toLowerCase();
        if (contentType.contains("text/html")) {
            String head = "";
            try { head = Files.readString(tmp); } catch (Exception ignored) {}
            Files.deleteIfExists(tmp);
            if (head.contains("sign_in") || head.contains("login") || head.contains("Log In")) {
                throw new IOException("Session cookie invalid or expired. Refresh STRAVA_SESSION_COOKIE.");
            }
            // Indoor / manual / no-GPS activities: Strava returns an HTML page saying so.
            return false;
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    // ==================== Prompts & utilities ====================

    private static LocalDate promptForDate(String label, LocalDate defaultValue) {
        Console console = System.console();
        Scanner scanner = new Scanner(System.in);
        String prompt = label + " [" + defaultValue + "]: ";
        while (true) {
            String input;
            if (console != null) {
                input = console.readLine(prompt);
            } else {
                System.out.print(prompt);
                input = scanner.nextLine();
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

    private static String sanitize(String name) {
        if (name == null) return "activity";
        String cleaned = name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_")
                .replaceAll("\\s+", "-")
                .replaceAll("_+", "_")
                .replaceAll("-+", "-")
                .trim();
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60);
        return cleaned.isEmpty() ? "activity" : cleaned;
    }

    private static boolean looksLikeHtml(String body) {
        if (body == null) return false;
        String head = body.stripLeading();
        return head.startsWith("<") || head.startsWith("<!DOCTYPE");
    }

    private static String trimForLog(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    // ==================== JSON helpers (regex, matching the Garmin/OneDrive tools) ====================

    private static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? unescapeJson(m.group(1)) : "";
    }

    private static long jsonLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static List<String> jsonTopLevelArrayObjects(String json) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < json.length() && json.charAt(i) != '[') i++;
        if (i >= json.length()) return out;
        i++;
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        boolean esc = false;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(json.substring(start, i + 1));
                    start = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
