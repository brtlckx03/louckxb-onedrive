package net.lckx.garmin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.Console;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads Garmin Connect activities (TCX, GPX, FIT, JSON summary) and the daily wellness snapshot
 * for a date range. Works day by day: each day's files are downloaded and its report is written
 * (see {@link DescribeGarminFile}) before the next day starts; the overview is rebuilt at the end.
 *
 * <p>Auth: Garmin has no public OAuth flow. This impersonates the mobile app's SSO
 * exchange to obtain an OAuth2 bearer + refresh token (persisted to ~/.garmin-token).
 * The refresh token lives ~1 year; the access token ~1 hour.
 *
 * <p>Usage:
 * <pre>
 *   java --enable-preview src/main/java/net/lckx/garmin/DownloadGarminFiles.java \
 *        --since 2026-01-01 [--until 2026-08-17]
 * </pre>
 * Must be run from the project root so {@code src/main/resources/garmin} resolves.
 */
public class DownloadGarminFiles {

    // Garmin mobile-app OAuth1 consumer credentials. Not secret — every open-source
    // Garmin client uses the same values; Garmin serves them to the iOS/Android app.
    // If preauthorized starts returning 401, Garmin rotated them; fetch the current pair from
    // https://thegarth.s3.amazonaws.com/oauth_consumer.json and update below.
    private static final String OAUTH_CONSUMER_KEY = "fc3e99d2-118c-44b8-8ae3-03370dde24c0";
    private static final String OAUTH_CONSUMER_SECRET = "E08WAR897WEy2knn7aFBrvegVAf0AFdWBBF";

    private static final String SSO_URL = "https://sso.garmin.com/sso";
    private static final String SIGNIN_URL = SSO_URL + "/signin";
    private static final String EMBED_URL = SSO_URL + "/embed";
    private static final String CONNECTAPI = "https://connectapi.garmin.com";

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final String APP_UA = "com.garmin.android.apps.connectmobile";

    // Per account (--account NAME): the main account uses ~/.garmin-token and ~/.garmin-credentials,
    // another one ~/.garmin-token-NAME and ~/.garmin-credentials-NAME, so the logins never mix.
    private static Path TOKEN_FILE =
            Path.of(System.getProperty("user.home"), ".garmin-token");
    private static Path CREDENTIALS_FILE =
            Path.of(System.getProperty("user.home"), ".garmin-credentials");
    private static Path DOWNLOAD_DIR = DescribeGarminFile.dataDir(DescribeGarminFile.MAIN_ACCOUNT);
    /** Suffix for the env vars of another account: GARMIN_EMAIL_PETER / GARMIN_PASSWORD_PETER. */
    private static String ENV_SUFFIX = "";

    private static void useAccount(String name) {
        DescribeGarminFile.useAccount(name);
        String a = DescribeGarminFile.account();
        DOWNLOAD_DIR = DescribeGarminFile.dataDir(a);
        if (a.equals(DescribeGarminFile.MAIN_ACCOUNT)) return;
        String home = System.getProperty("user.home");
        TOKEN_FILE = Path.of(home, ".garmin-token-" + a);
        CREDENTIALS_FILE = Path.of(home, ".garmin-credentials-" + a);
        ENV_SUFFIX = "_" + a.toUpperCase(Locale.ROOT).replace('-', '_');
    }
    private static final int WELLNESS_SCHEMA_VERSION = 3;

    private static final SecureRandom RNG = new SecureRandom();
    private static final HttpClient HTTP;

    static {
        CookieManager cm = new CookieManager();
        cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cm);
        HTTP = HttpClient.newBuilder()
                .sslContext(sslContext())
                .cookieHandler(cm)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Java's own CA list plus the CA certificates in the macOS System keychain. A company proxy that inspects HTTPS
     * (here Zscaler) re-signs Garmin's certificates with its own root, which IT installs in the System keychain but
     * Java does not read; and every JDK update resets Java's list (cacerts), so importing it there does not last.
     */
    private static SSLContext sslContext() {
        try {
            TrustManagerFactory jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            jdk.init((KeyStore) null);
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int n = 0;
            for (TrustManager tm : jdk.getTrustManagers())
                if (tm instanceof X509TrustManager x) for (X509Certificate c : x.getAcceptedIssuers()) ks.setCertificateEntry("jdk-" + n++, c);
            List<X509Certificate> extra = macSystemKeychainCAs();
            if (extra.isEmpty()) return SSLContext.getDefault();
            for (int i = 0; i < extra.size(); i++) ks.setCertificateEntry("keychain-" + i, extra.get(i));
            TrustManagerFactory both = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            both.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, both.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            System.err.println("Could not add the macOS keychain certificates; using Java's defaults: " + e.getMessage());
            try {
                return SSLContext.getDefault();
            } catch (Exception e2) {
                throw new IllegalStateException(e2);
            }
        }
    }

    /** CA certificates (not end-entity ones) in /Library/Keychains/System.keychain; empty when not on macOS. */
    private static List<X509Certificate> macSystemKeychainCAs() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return List.of();
        Path keychain = Path.of("/Library/Keychains/System.keychain");
        if (!Files.exists(keychain)) return List.of();
        Process p = new ProcessBuilder("/usr/bin/security", "find-certificate", "-a", "-p", keychain.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] pem = p.getInputStream().readAllBytes();
        p.waitFor();
        List<X509Certificate> out = new ArrayList<>();
        if (pem.length == 0) return out;
        for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem)))
            if (c instanceof X509Certificate x && x.getBasicConstraints() >= 0) out.add(x);
        return out;
    }

    /** Garmin sometimes accepts a request and never answers; without a timeout the run hangs forever. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final int ATTEMPTS = 3;

    /** Sends an API request, retrying a few times when it times out or the connection drops. */
    private static <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return HTTP.send(req, handler);
            } catch (IOException e) {
                if (attempt >= ATTEMPTS) throw e;
                System.err.printf("  retry %d/%d after %s: %s%n", attempt, ATTEMPTS - 1,
                        e.getClass().getSimpleName(), req.uri().getPath());
                Thread.sleep(2000L * attempt);
            }
        }
    }

    static void main(String[] args) throws Exception {
        LocalDate since = null;
        LocalDate until = null;
        boolean listOnly = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--since" -> since = LocalDate.parse(args[++i]);
                case "--until" -> until = LocalDate.parse(args[++i]);
                case "--list-only" -> listOnly = true;
                case "--account" -> useAccount(args[++i]);
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printUsageAndExit();
                }
            }
        }
        LocalDate today = LocalDate.now();
        LocalDate defaultSince = today.minusDays(30);
        if (since == null) since = promptForDate("--since date (YYYY-MM-DD)", defaultSince);
        if (until == null) until = promptForDate("--until date (YYYY-MM-DD)", today);
        Files.createDirectories(DOWNLOAD_DIR);

        Token token = loadOrLogin();
        System.out.printf("Fetching activities from %s to %s...%n", since, until);
        List<Activity> activities = listActivities(token, since, until);
        System.out.printf("Found %d activities.%n", activities.size());
        if (listOnly) {
            for (Activity a : activities) {
                System.out.printf("  %s  id=%d  %s%n", a.date, a.id, a.name);
            }
            System.out.println("(list-only mode; no downloads, no wellness)");
            return;
        }

        // Work day by day, so an interrupted run leaves complete days (files + report) behind.
        Run run = new Run(token);
        run.displayName = getDisplayName(run.token);
        Map<String, List<Activity>> byDate = new TreeMap<>();
        for (Activity a : activities) byDate.computeIfAbsent(a.date, k -> new ArrayList<>()).add(a);
        for (LocalDate day = since; !day.isAfter(until); day = day.plusDays(1)) {
            List<Activity> ofDay = byDate.remove(day.toString());
            boolean changed = false;
            if (ofDay != null) for (Activity a : ofDay) changed |= downloadActivity(run, a);
            changed |= downloadWellness(run, day);
            writeReportIfNeeded(run, day, changed);
        }
        // Activities whose local start date falls outside the requested range (time zones) are still downloaded.
        for (var e : byDate.entrySet()) {
            boolean changed = false;
            for (Activity a : e.getValue()) changed |= downloadActivity(run, a);
            if (e.getKey().matches("\\d{4}-\\d{2}-\\d{2}")) writeReportIfNeeded(run, LocalDate.parse(e.getKey()), changed);
        }
        System.out.printf("Activities done. downloaded=%d skipped=%d failed=%d%n", run.downloaded, run.skipped, run.failed);
        System.out.printf("Wellness done. ok=%d skipped=%d no-data=%d failed=%d%n", run.wOk, run.wSkip, run.wEmpty, run.wFail);
        System.out.printf("Reports done. written=%d unchanged=%d%n", run.reports, run.reportsKept);
        if (run.reports == 0) DescribeGarminFile.Overview.write();
        DescribeGarminFile.writePages();
        System.out.println("Overview: " + DOWNLOAD_DIR.resolve("index.html").toAbsolutePath());
    }

    /** State shared by the per-day steps: the (refreshed) token and the counters for the final summary. */
    private static final class Run {
        Token token;
        String displayName;
        int downloaded, skipped, failed;
        int wOk, wSkip, wEmpty, wFail;
        int reports, reportsKept;

        Run(Token token) {
            this.token = token;
        }
    }

    /**
     * Rewrites a day's report only when its files changed in this run or the report doesn't exist yet,
     * and then updates the overview so it is current after every day.
     */
    private static void writeReportIfNeeded(Run run, LocalDate day, boolean changed) throws IOException {
        if (!changed && Files.exists(DescribeGarminFile.reportPath(day))) {
            run.reportsKept++;
            return;
        }
        boolean existed = Files.exists(DescribeGarminFile.reportPath(day));
        boolean written = DescribeGarminFile.writeReport(day, null) != null;
        if (written) run.reports++;
        // Days without data have no report; only touch the overview when a row appears, changes or disappears.
        if (written || existed) DescribeGarminFile.Overview.update(day);
    }

    /** Returns true when a file of the activity was downloaded. */
    private static boolean downloadActivity(Run run, Activity a) throws Exception {
        String base = a.date + "_" + a.id + "_" + sanitize(a.name);
        Path activityDir = DOWNLOAD_DIR
                .resolve(a.date.substring(0, 4))
                .resolve(a.date.substring(5, 7));
        Files.createDirectories(activityDir);
        Path tcx = activityDir.resolve(base + ".tcx");
        Path gpx = activityDir.resolve(base + ".gpx");
        Path fit = activityDir.resolve(base + ".fit");
        Path json = activityDir.resolve(base + ".json");
        if (Files.exists(tcx) && Files.exists(gpx) && Files.exists(fit) && Files.exists(json)) {
            run.skipped++;
            return false;
        }
        try {
            run.token = ensureFreshToken(run.token);
            if (!Files.exists(tcx)) {
                downloadExport(run.token, a.id, "tcx", tcx);
                run.downloaded++;
                System.out.printf("  [%d] %s.tcx%n", run.downloaded, base);
            }
            if (!Files.exists(gpx)) {
                downloadExport(run.token, a.id, "gpx", gpx);
                run.downloaded++;
                System.out.printf("  [%d] %s.gpx%n", run.downloaded, base);
            }
            if (!Files.exists(fit)) {
                downloadFit(run.token, a.id, fit);
                run.downloaded++;
                System.out.printf("  [%d] %s.fit%n", run.downloaded, base);
            }
            if (!Files.exists(json)) {
                downloadSummary(run.token, a.id, json);
                run.downloaded++;
                System.out.printf("  [%d] %s.json%n", run.downloaded, base);
            }
            Thread.sleep(250);
        } catch (Exception e) {
            System.err.printf("  FAILED activity %d (%s): %s%n", a.id, a.name, e.getMessage());
            run.failed++;
        }
        // Even after a failure, files downloaded before it are new for the report.
        return true;
    }

    /** Returns true when the day's snapshot was written or removed. */
    private static boolean downloadWellness(Run run, LocalDate day) {
        Path dir = DOWNLOAD_DIR
                .resolve(String.valueOf(day.getYear()))
                .resolve(String.format("%02d", day.getMonthValue()))
                .resolve("wellness");
        Path target = dir.resolve(day + ".json");
        // Today and yesterday are always fetched again: the watch may not have synced the whole day yet.
        boolean recent = !day.isBefore(LocalDate.now().minusDays(1));
        if (!recent && isCurrentWellnessSnapshot(target)) {
            run.wSkip++;
            return false;
        }
        try {
            run.token = ensureFreshToken(run.token);
            String snapshot = fetchDailySnapshot(run.token, run.displayName, day);
            Thread.sleep(150);
            if (!hasWellnessData(snapshot)) {
                // Nothing recorded that day (e.g. before the first watch): don't keep an empty snapshot.
                // An older-format file for this day only held the account's "latest" VO2 max, so drop it too.
                boolean removed = Files.deleteIfExists(target);
                if (removed) System.out.printf("  wellness %s: no data, removed old snapshot%n", day);
                run.wEmpty++;
                return removed;
            }
            Files.createDirectories(dir);
            writeAtomically(target, snapshot);
            run.wOk++;
            System.out.printf("  wellness %s%n", day);
            return true;
        } catch (Exception e) {
            System.err.printf("  FAILED wellness %s: %s%n", day, e.getMessage());
            run.wFail++;
            return false;
        }
    }

    /** Signs that Garmin actually recorded something for the day; checked on the raw snapshot JSON. */
    private static final List<Pattern> WELLNESS_DATA_SIGNS = List.of(
            Pattern.compile("\"totalSteps\"\\s*:\\s*[1-9]"),
            Pattern.compile("\"sleepTimeSeconds\"\\s*:\\s*[1-9]"),
            Pattern.compile("\"heartRateValues\"\\s*:\\s*\\[\\s*\\["),
            Pattern.compile("\"stressValuesArray\"\\s*:\\s*\\[\\s*\\["),
            Pattern.compile("\"hrvSummary\"\\s*:\\s*\\{"),
            Pattern.compile("\"overallScore\"\\s*:\\s*[1-9]"),
            Pattern.compile("\"mostRecentVO2Max\"\\s*:\\s*\\{"),
            Pattern.compile("\"trainingStatusFeedbackPhrase\"\\s*:\\s*\""));

    static boolean hasWellnessData(String snapshot) {
        for (Pattern p : WELLNESS_DATA_SIGNS) {
            if (p.matcher(snapshot).find()) return true;
        }
        return false;
    }

    private static void printUsageAndExit() {
        System.err.println("Usage: DownloadGarminFiles [--account NAME] --since YYYY-MM-DD [--until YYYY-MM-DD]");
        System.exit(1);
    }

    /**
     * Returns {email, password}. Never echoes the password.
     * Order of sources: env vars ({@code GARMIN_EMAIL}, {@code GARMIN_PASSWORD}), then a
     * config file (~/.garmin-credentials, {@code email=...} / {@code password=...} properties,
     * only honored if its permissions are not group/world readable), then a real terminal via
     * {@link System#console()}. If none is available (e.g. an IntelliJ "Run" configuration with
     * no console), the user is told how to fix it and the process exits — we refuse to read a
     * password through echoing input streams.
     */
    private static String CREDENTIAL_SOURCE = "?";

    private static String[] readCredentials() {
        String envEmail = System.getenv("GARMIN_EMAIL" + ENV_SUFFIX);
        String envPassword = System.getenv("GARMIN_PASSWORD" + ENV_SUFFIX);
        if (envEmail != null && !envEmail.isBlank() && envPassword != null && !envPassword.isBlank()) {
            CREDENTIAL_SOURCE = "env vars GARMIN_EMAIL" + ENV_SUFFIX + "/GARMIN_PASSWORD" + ENV_SUFFIX;
            return new String[]{envEmail.trim(), envPassword};
        }
        String[] fromFile = readCredentialsFromFile();
        if (fromFile != null) {
            CREDENTIAL_SOURCE = "config file " + CREDENTIALS_FILE;
            return fromFile;
        }
        Console console = System.console();
        if (console == null) {
            System.err.println("""
                    No secure input available.
                    Either:
                      • Run this from a real terminal (Terminal.app / iTerm), OR
                      • Set the GARMIN_EMAIL%2$s and GARMIN_PASSWORD%2$s environment variables, OR
                      • Create %s with "email=..." and "password=..." lines (chmod 600).
                    In IntelliJ: Run > Edit Configurations > Environment variables.
                    """.formatted(CREDENTIALS_FILE, ENV_SUFFIX));
            System.exit(1);
        }
        CREDENTIAL_SOURCE = "interactive console";
        String email = console.readLine("Garmin email" + (ENV_SUFFIX.isEmpty() ? "" : " for account " + DescribeGarminFile.account()) + ": ").trim();
        char[] pw = console.readPassword("Garmin password: ");
        return new String[]{email, new String(pw)};
    }

    /**
     * Reads email/password from {@link #CREDENTIALS_FILE} if present. The file must be a
     * simple {@code key=value} properties file with {@code email} and {@code password} keys.
     * Refuses to use it if permissions allow group/world access, to avoid leaking the
     * plaintext password.
     */
    private static String[] readCredentialsFromFile() {
        if (!Files.isRegularFile(CREDENTIALS_FILE)) return null;
        try {
            var perms = Files.getPosixFilePermissions(CREDENTIALS_FILE);
            boolean tooOpen = perms.stream().anyMatch(p -> p.name().startsWith("GROUP") || p.name().startsWith("OTHERS"));
            if (tooOpen) {
                System.err.printf(
                        "Ignoring %s: permissions are too open (run `chmod 600 %s`).%n",
                        CREDENTIALS_FILE, CREDENTIALS_FILE);
                return null;
            }
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows); skip the permission check.
        } catch (IOException e) {
            System.err.println("Could not check permissions on " + CREDENTIALS_FILE + ": " + e.getMessage());
            return null;
        }
        java.util.Properties props = new java.util.Properties();
        try (var in = Files.newInputStream(CREDENTIALS_FILE)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("Could not read " + CREDENTIALS_FILE + ": " + e.getMessage());
            return null;
        }
        String email = props.getProperty("email");
        String password = props.getProperty("password");
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            System.err.println(CREDENTIALS_FILE + " is missing 'email' or 'password'.");
            return null;
        }
        return new String[]{email.trim(), password};
    }

    private static String[] browserHeaders(String referer) {
        List<String> h = new ArrayList<>(List.of(
                "User-Agent", BROWSER_UA,
                "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language", "en-US,en;q=0.9",
                "Upgrade-Insecure-Requests", "1",
                "Sec-Fetch-Dest", "document",
                "Sec-Fetch-Mode", "navigate",
                "Sec-Fetch-Site", referer == null ? "none" : "same-origin"));
        if (referer != null) {
            h.add("Referer");
            h.add(referer);
        }
        return h.toArray(new String[0]);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
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

    // ==================== Activities & downloads ====================

    private record Activity(long id, String date, String name) {
    }

    private static List<Activity> listActivities(Token token, LocalDate since, LocalDate until)
            throws Exception {
        // Garmin's activitylist-service returns 0 results (200 OK, empty array) on wide ranges
        // and on endDate values in the future. Clamp to today and chunk by year to be safe.
        LocalDate today = LocalDate.now();
        if (until.isAfter(today)) until = today;
        List<Activity> out = new ArrayList<>();
        LocalDate chunkStart = since;
        while (!chunkStart.isAfter(until)) {
            LocalDate chunkEnd = chunkStart.plusYears(1).minusDays(1);
            if (chunkEnd.isAfter(until)) chunkEnd = until;
            out.addAll(listActivitiesRange(token, chunkStart, chunkEnd));
            chunkStart = chunkEnd.plusDays(1);
        }
        out.sort(Comparator.comparing(Activity::date).thenComparingLong(Activity::id));
        return out;
    }

    private static List<Activity> listActivitiesRange(Token token, LocalDate since, LocalDate until)
            throws Exception {
        List<Activity> out = new ArrayList<>();
        int start = 0;
        int limit = 100;
        while (true) {
            String url = CONNECTAPI + "/activitylist-service/activities/search/activities"
                    + "?startDate=" + since
                    + "&endDate=" + until
                    + "&start=" + start
                    + "&limit=" + limit;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token.accessToken)
                    .header("User-Agent", APP_UA)
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("List activities failed: " + resp.statusCode() + " " + resp.body());
            }
            List<String> items = jsonTopLevelArrayObjects(resp.body());
            for (String item : items) {
                long id = jsonLong(item, "activityId");
                String startLocal = jsonString(item, "startTimeLocal");
                String name = jsonString(item, "activityName");
                if (name.isEmpty()) {
                    name = jsonNested(item, "activityType", "typeKey");
                }
                if (name.isEmpty()) name = "activity";
                String date = startLocal.length() >= 10 ? startLocal.substring(0, 10) : "unknown";
                out.add(new Activity(id, date, name));
            }
            if (items.size() < limit) break;
            start += limit;
        }
        return out;
    }

    private static void downloadExport(Token token, long activityId, String format, Path target)
            throws Exception {
        String url = CONNECTAPI + "/download-service/export/" + format + "/activity/" + activityId;
        bearerGetToFile(token, url, null, target);
    }

    /** Rich activity metadata JSON (HR, calories, training effect, weather, gear, PRs, ...). */
    private static void downloadSummary(Token token, long activityId, Path target) throws Exception {
        String url = CONNECTAPI + "/activity-service/activity/" + activityId;
        bearerGetToFile(token, url, "application/json", target);
    }

    /**
     * Downloads the raw FIT file. Garmin serves it as a ZIP; we extract the single
     * {@code .fit} entry to the target path.
     */
    private static void downloadFit(Token token, long activityId, Path target) throws Exception {
        String url = CONNECTAPI + "/download-service/files/activity/" + activityId;
        Path zipTmp = target.resolveSibling(target.getFileName() + ".zip.tmp");
        bearerGetToFile(token, url, null, zipTmp);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        boolean found = false;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipTmp))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().toLowerCase().endsWith(".fit")) {
                    Files.copy(zis, tmp, StandardCopyOption.REPLACE_EXISTING);
                    found = true;
                    break;
                }
            }
        } finally {
            Files.deleteIfExists(zipTmp);
        }
        if (!found) throw new IOException("No .fit entry in downloaded archive");
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Combined raw per-day wellness and training snapshot. Missing device-gated metrics come
     * back as {@code null} rather than aborting the day.
     */
    private static String fetchDailySnapshot(Token token, String displayName, LocalDate day)
            throws Exception {
        String encodedDisplayName = encodePathSegment(displayName);
        String dailySummary = bearerGetJsonOrNull(token,
                CONNECTAPI + "/usersummary-service/usersummary/daily/" + encodedDisplayName
                        + "?calendarDate=" + day);
        String sleep = bearerGetJsonOrNull(token,
                CONNECTAPI + "/wellness-service/wellness/dailySleepData/" + encodedDisplayName
                        + "?date=" + day + "&nonSleepBufferMinutes=60");
        String hrv = bearerGetJsonOrNull(token,
                CONNECTAPI + "/hrv-service/hrv/" + day);
        String stress = bearerGetJsonOrNull(token,
                CONNECTAPI + "/wellness-service/wellness/dailyStress/" + day);
        String bodyBattery = bearerGetJsonOrNull(token,
                CONNECTAPI + "/wellness-service/wellness/bodyBattery/reports/daily"
                        + "?startDate=" + day + "&endDate=" + day);
        String bodyBatteryEvents = bearerGetJsonOrNull(token,
                CONNECTAPI + "/wellness-service/wellness/bodyBattery/events/" + day);
        String heartRate = bearerGetJsonOrNull(token,
                CONNECTAPI + "/wellness-service/wellness/dailyHeartRate/" + encodedDisplayName
                        + "?date=" + day);
        String respiration = bearerGetJsonOrNull(token,
                CONNECTAPI + "/wellness-service/wellness/daily/respiration/" + day);
        String spo2 = bearerGetJsonOrNull(token,
                CONNECTAPI + "/wellness-service/wellness/daily/spo2/" + day);
        String intensityMinutes = bearerGetJsonOrNull(token,
                CONNECTAPI + "/wellness-service/wellness/daily/im/" + day);
        String vo2 = bearerGetJsonOrNull(token,
                CONNECTAPI + "/metrics-service/metrics/maxmet/daily/" + day + "/" + day);
        String race = bearerGetJsonOrNull(token,
                CONNECTAPI + "/metrics-service/metrics/prediction/latest/" + day);
        String status = bearerGetJsonOrNull(token,
                CONNECTAPI + "/metrics-service/metrics/trainingstatus/aggregated/" + day);
        String readiness = bearerGetJsonOrNull(token,
                CONNECTAPI + "/metrics-service/metrics/trainingreadiness/" + day);
        String enduranceScore = bearerGetJsonOrNull(token,
                CONNECTAPI + "/metrics-service/metrics/endurancescore?calendarDate=" + day);
        String hillScore = bearerGetJsonOrNull(token,
                CONNECTAPI + "/metrics-service/metrics/hillscore?calendarDate=" + day);
        return "{\n"
                + "  \"schemaVersion\": " + WELLNESS_SCHEMA_VERSION + ",\n"
                + "  \"date\": \"" + day + "\",\n"
                + "  \"dailySummary\": " + dailySummary + ",\n"
                + "  \"sleep\": " + sleep + ",\n"
                + "  \"hrv\": " + hrv + ",\n"
                + "  \"stress\": " + stress + ",\n"
                + "  \"bodyBattery\": " + bodyBattery + ",\n"
                + "  \"bodyBatteryEvents\": " + bodyBatteryEvents + ",\n"
                + "  \"heartRate\": " + heartRate + ",\n"
                + "  \"respiration\": " + respiration + ",\n"
                + "  \"spo2\": " + spo2 + ",\n"
                + "  \"intensityMinutes\": " + intensityMinutes + ",\n"
                + "  \"vo2max\": " + vo2 + ",\n"
                + "  \"racePrediction\": " + race + ",\n"
                + "  \"trainingStatus\": " + status + ",\n"
                + "  \"trainingReadiness\": " + readiness + ",\n"
                + "  \"enduranceScore\": " + enduranceScore + ",\n"
                + "  \"hillScore\": " + hillScore + "\n"
                + "}\n";
    }

    private static String getDisplayName(Token token) throws Exception {
        String profile = bearerGetJson(token,
                CONNECTAPI + "/userprofile-service/socialProfile");
        String displayName = jsonString(profile, "displayName");
        if (displayName.isBlank()) {
            throw new IOException("Garmin profile did not contain displayName");
        }
        return displayName;
    }

    private static boolean isCurrentWellnessSnapshot(Path target) {
        if (!Files.isRegularFile(target)) return false;
        try {
            return Files.readString(target)
                    .contains("\"schemaVersion\": " + WELLNESS_SCHEMA_VERSION);
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String bearerGetJsonOrNull(Token token, String url) throws Exception {
        HttpResponse<String> resp = bearerGetJsonResponse(token, url);
        if (resp.statusCode() == 204 || resp.statusCode() == 404) return "null";
        if (resp.statusCode() != 200) {
            throw new IOException("Garmin API failed: " + resp.statusCode() + " "
                    + trimForLog(resp.body()));
        }
        String body = resp.body() == null ? "" : resp.body().trim();
        return body.isEmpty() ? "null" : body;
    }

    private static String bearerGetJson(Token token, String url) throws Exception {
        HttpResponse<String> resp = bearerGetJsonResponse(token, url);
        if (resp.statusCode() != 200) {
            throw new IOException("Garmin API failed: " + resp.statusCode() + " "
                    + trimForLog(resp.body()));
        }
        return resp.body();
    }

    private static HttpResponse<String> bearerGetJsonResponse(Token token, String url)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token.accessToken)
                .header("User-Agent", APP_UA)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET().build();
        return send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void bearerGetToFile(Token token, String url, String accept, Path target)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token.accessToken)
                .header("User-Agent", APP_UA)
                .timeout(DOWNLOAD_TIMEOUT);
        if (accept != null) b.header("Accept", accept);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        HttpResponse<Path> resp = send(b.GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() != 200) {
            String body = "";
            try {
                body = Files.readString(tmp);
            } catch (Exception ignored) {
            }
            Files.deleteIfExists(tmp);
            throw new IOException("HTTP " + resp.statusCode() + " " + body);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    // ==================== Auth: load / refresh / login ====================

    private static final class Token {
        String accessToken;
        String refreshToken;
        long expiresAt; // epoch seconds

        Token(String a, String r, long e) {
            this.accessToken = a;
            this.refreshToken = r;
            this.expiresAt = e;
        }
    }

    private static Token loadOrLogin() throws Exception {
        if (Files.exists(TOKEN_FILE)) {
            try {
                String json = Files.readString(TOKEN_FILE);
                Token t = new Token(
                        jsonString(json, "access_token"),
                        jsonString(json, "refresh_token"),
                        jsonLong(json, "expires_at"));
                if (!t.accessToken.isEmpty() && !t.refreshToken.isEmpty()) {
                    return ensureFreshToken(t);
                }
            } catch (Exception e) {
                System.err.println("Could not use cached token (" + e.getMessage() + "); logging in.");
            }
        }
        Token t = login();
        saveToken(t);
        return t;
    }

    private static Token ensureFreshToken(Token t) throws Exception {
        if (Instant.now().getEpochSecond() < t.expiresAt - 60) return t;
        try {
            Token refreshed = refreshToken(t.refreshToken);
            saveToken(refreshed);
            return refreshed;
        } catch (Exception e) {
            System.err.println("Token refresh failed (" + e.getMessage() + "); logging in again.");
            Token fresh = login();
            saveToken(fresh);
            return fresh;
        }
    }

    private static void saveToken(Token t) throws IOException {
        String json = "{"
                + "\"access_token\":\"" + t.accessToken + "\","
                + "\"refresh_token\":\"" + t.refreshToken + "\","
                + "\"expires_at\":" + t.expiresAt
                + "}";
        Files.writeString(TOKEN_FILE, json);
        try {
            Files.setPosixFilePermissions(TOKEN_FILE,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Not POSIX (Windows) — skip
        }
    }

    private static Token refreshToken(String refreshToken) throws Exception {
        String body = "refresh_token=" + urlEncode(refreshToken);
        Map<String, String> oauthParams = baseOauthParams();
        String url = CONNECTAPI + "/oauth-service/oauth/exchange/user/2.0";
        String signature = oauth1Signature("POST", url, oauthParams, parseForm(body), "");
        oauthParams.put("oauth_signature", signature);
        String authHeader = buildOauthHeader(oauthParams);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authHeader)
                .header("User-Agent", APP_UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Refresh failed: " + resp.statusCode() + " " + resp.body());
        }
        return parseOauth2Json(resp.body());
    }

    private static Token login() throws Exception {
        String[] creds = readCredentials();
        String email = creds[0];
        String password = creds[1];

        Map<String, String> ssoParams = new java.util.LinkedHashMap<>();
        ssoParams.put("id", "gauth-widget");
        ssoParams.put("embedWidget", "true");
        ssoParams.put("gauthHost", SSO_URL);
        ssoParams.put("service", EMBED_URL);
        ssoParams.put("source", EMBED_URL);
        ssoParams.put("redirectAfterAccountLoginUrl", EMBED_URL);
        ssoParams.put("redirectAfterAccountCreationUrl", EMBED_URL);
        String signinWithParams = SIGNIN_URL + "?" + encodeQuery(ssoParams);
        String embedWithParams = EMBED_URL + "?" + encodeQuery(ssoParams);

        // Warm up cookies via the embed page.
        HTTP.send(HttpRequest.newBuilder(URI.create(embedWithParams))
                .headers(browserHeaders(null))
                .GET().build(), HttpResponse.BodyHandlers.discarding());

        HttpResponse<String> formResp = HTTP.send(
                HttpRequest.newBuilder(URI.create(signinWithParams))
                        .headers(browserHeaders(embedWithParams))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (formResp.statusCode() != 200) {
            throw new IOException("SSO signin GET failed: " + formResp.statusCode()
                    + "\nBody: " + trimForLog(formResp.body()));
        }
        String csrf = extractCsrf(formResp.body());

        String postBody = "username=" + urlEncode(email)
                + "&password=" + urlEncode(password)
                + "&embed=true"
                + "&_csrf=" + urlEncode(csrf);

        String[] postHeaders = concat(browserHeaders(signinWithParams), new String[]{
                "Content-Type", "application/x-www-form-urlencoded",
                "Origin", "https://sso.garmin.com",
                "Sec-Fetch-User", "?1",
        });

        HttpResponse<String> loginResp = HTTP.send(
                HttpRequest.newBuilder(URI.create(signinWithParams))
                        .headers(postHeaders)
                        .POST(HttpRequest.BodyPublishers.ofString(postBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (loginResp.statusCode() != 200 && loginResp.statusCode() != 302) {
            Path dump = Path.of(System.getProperty("java.io.tmpdir"), "garmin-signin-response.html");
            Files.writeString(dump, loginResp.body());
            System.err.println("SSO signin POST failed: " + loginResp.statusCode());
            System.err.println("Full body written to: " + dump);
            System.err.println("credentials source: " + CREDENTIAL_SOURCE);
            System.err.println("email used: " + email);
            System.err.println("password length: " + password.length()
                    + " (has trailing whitespace: " + !password.equals(password.stripTrailing()) + ")");
            Matcher errMsg = Pattern.compile("class=\"error\">([^<]+)").matcher(loginResp.body());
            if (errMsg.find()) {
                System.err.println("Garmin says: " + errMsg.group(1).trim());
            }
            throw new IOException("SSO signin POST failed: " + loginResp.statusCode());
        }
        String body = loginResp.body();

        String ticket = extractTicket(body);
        if (ticket == null) {
            // MFA path
            if (body.contains("verifyMFA") || body.contains("MFA")) {
                ticket = handleMfa(body, ssoParams);
            }
        }
        if (ticket == null) {
            throw new IOException("Login did not return a service ticket. "
                    + "Check credentials or MFA. Body excerpt: " + trimForLog(body));
        }

        return exchangeTicketForOauth2(ticket);
    }

    private static String handleMfa(String body, Map<String, String> ssoParams) throws Exception {
        Matcher m = Pattern.compile("action=\"([^\"]*verifyMFA[^\"]*)\"").matcher(body);
        String mfaUrl;
        if (m.find()) {
            mfaUrl = m.group(1).replace("&amp;", "&");
            if (mfaUrl.startsWith("/")) mfaUrl = "https://sso.garmin.com" + mfaUrl;
        } else {
            throw new IOException("MFA challenge detected but action URL not found.");
        }
        String csrf = extractCsrf(body);
        Console console = System.console();
        String code;
        if (console != null) {
            code = console.readLine("Garmin MFA code: ").trim();
        } else {
            String envCode = System.getenv("GARMIN_MFA_CODE");
            if (envCode == null || envCode.isBlank()) {
                throw new IOException(
                        "MFA required but no terminal available. Set GARMIN_MFA_CODE env var and re-run.");
            }
            code = envCode.trim();
        }
        String mfaBody = "mfa-code=" + urlEncode(code)
                + "&embed=true"
                + "&_csrf=" + urlEncode(csrf)
                + "&fromPage=setupEnterMfaCode";
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(mfaUrl))
                        .header("User-Agent", BROWSER_UA)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(mfaBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 && resp.statusCode() != 302) {
            throw new IOException("MFA POST failed: " + resp.statusCode()
                    + " " + trimForLog(resp.body()));
        }
        return extractTicket(resp.body());
    }

    private static Token exchangeTicketForOauth2(String ticket) throws Exception {
        // Step 1: OAuth1-signed GET → returns application/x-www-form-urlencoded body
        //         with oauth_token and oauth_token_secret for the user.
        String preauthUrl = CONNECTAPI + "/oauth-service/oauth/preauthorized";
        Map<String, String> queryParams = new TreeMap<>();
        queryParams.put("ticket", ticket);
        queryParams.put("login-url", EMBED_URL);
        queryParams.put("accepts-mfa-tokens", "true");

        Map<String, String> oauth1 = baseOauthParams();
        String signature = oauth1Signature("GET", preauthUrl, oauth1, queryParams, "");
        oauth1.put("oauth_signature", signature);

        String url = preauthUrl + "?" + encodeQuery(queryParams);
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", buildOauthHeader(oauth1))
                        .header("User-Agent", APP_UA)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("OAuth1 preauthorized failed: " + resp.statusCode()
                    + " " + trimForLog(resp.body()));
        }
        Map<String, String> tokenForm = parseForm(resp.body());
        String userOauthToken = tokenForm.get("oauth_token");
        String userOauthSecret = tokenForm.get("oauth_token_secret");
        if (userOauthToken == null || userOauthSecret == null) {
            throw new IOException("OAuth1 response missing tokens: " + trimForLog(resp.body()));
        }

        // Step 2: OAuth1-signed POST → OAuth2 bearer + refresh token
        String exchangeUrl = CONNECTAPI + "/oauth-service/oauth/exchange/user/2.0";
        Map<String, String> oauth2 = baseOauthParams();
        oauth2.put("oauth_token", userOauthToken);
        String sig2 = oauth1Signature("POST", exchangeUrl, oauth2, Map.of(), userOauthSecret);
        oauth2.put("oauth_signature", sig2);

        HttpResponse<String> tokenResp = HTTP.send(
                HttpRequest.newBuilder(URI.create(exchangeUrl))
                        .header("Authorization", buildOauthHeader(oauth2))
                        .header("User-Agent", APP_UA)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (tokenResp.statusCode() != 200) {
            throw new IOException("OAuth2 exchange failed: " + tokenResp.statusCode()
                    + " " + trimForLog(tokenResp.body()));
        }
        return parseOauth2Json(tokenResp.body());
    }

    private static Token parseOauth2Json(String json) {
        String access = jsonString(json, "access_token");
        String refresh = jsonString(json, "refresh_token");
        long expiresIn = jsonLong(json, "expires_in");
        if (expiresIn == 0) expiresIn = 3600;
        long expiresAt = Instant.now().getEpochSecond() + expiresIn;
        return new Token(access, refresh, expiresAt);
    }

    // ==================== OAuth1 signing ====================

    private static Map<String, String> baseOauthParams() {
        Map<String, String> p = new TreeMap<>();
        p.put("oauth_consumer_key", OAUTH_CONSUMER_KEY);
        p.put("oauth_nonce", nonce());
        p.put("oauth_signature_method", "HMAC-SHA1");
        p.put("oauth_timestamp", String.valueOf(Instant.now().getEpochSecond()));
        p.put("oauth_version", "1.0");
        return p;
    }

    private static String oauth1Signature(String method, String url,
                                          Map<String, String> oauthParams,
                                          Map<String, String> extraParams,
                                          String tokenSecret) throws Exception {
        TreeMap<String, String> all = new TreeMap<>();
        all.putAll(oauthParams);
        all.putAll(extraParams);
        StringBuilder sb = new StringBuilder();
        for (var e : all.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(rfc3986(e.getKey())).append('=').append(rfc3986(e.getValue()));
        }
        String base = method.toUpperCase() + "&" + rfc3986(url) + "&" + rfc3986(sb.toString());
        String key = rfc3986(OAUTH_CONSUMER_SECRET) + "&" + rfc3986(tokenSecret);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] digest = mac.doFinal(base.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static String buildOauthHeader(Map<String, String> oauthParams) {
        StringBuilder sb = new StringBuilder("OAuth ");
        boolean first = true;
        for (var e : oauthParams.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(rfc3986(e.getKey())).append("=\"").append(rfc3986(e.getValue())).append("\"");
            first = false;
        }
        return sb.toString();
    }

    private static String nonce() {
        byte[] buf = new byte[16];
        RNG.nextBytes(buf);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ==================== Utilities ====================

    private static String rfc3986(String s) {
        // OAuth1 percent-encoding: only unreserved chars A-Z a-z 0-9 - _ . ~ pass through.
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String encodePathSegment(String s) {
        return urlEncode(s).replace("+", "%20");
    }

    private static String encodeQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (var e : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new TreeMap<>();
        if (body == null || body.isEmpty()) return out;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String val = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, val);
        }
        return out;
    }

    private static String extractCsrf(String html) throws IOException {
        Matcher m = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) throw new IOException("Could not find CSRF token in signin form.");
        return m.group(1);
    }

    private static String extractTicket(String body) {
        Matcher m = Pattern.compile("[?&]ticket=([^\"'&\\s]+)").matcher(body);
        return m.find() ? m.group(1) : null;
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

    private static String trimForLog(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    // ==================== JSON helpers (regex, matching OneDrive tools) ====================

    private static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? unescapeJson(m.group(1)) : "";
    }

    private static long jsonLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    /** Returns the string value of {@code inner} inside the object under {@code outer}. */
    private static String jsonNested(String json, String outer, String inner) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(outer) + "\"\\s*:\\s*\\{([^{}]*)}")
                .matcher(json);
        return m.find() ? jsonString(m.group(1), inner) : "";
    }

    /**
     * Splits a top-level JSON array {@code [ {...}, {...} ]} into its object elements.
     * Returns each object's raw text (without the surrounding braces trimmed).
     */
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
