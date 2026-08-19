package net.lckx.garmin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads TCX and GPX exports for Garmin Connect activities in a date range.
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

    private static final Path TOKEN_FILE =
            Path.of(System.getProperty("user.home"), ".garmin-token");
    private static final Path DOWNLOAD_DIR = Path.of("src/main/resources/garmin");

    private static final SecureRandom RNG = new SecureRandom();
    private static final HttpClient HTTP;

    static {
        CookieManager cm = new CookieManager();
        cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cm);
        HTTP = HttpClient.newBuilder()
                .cookieHandler(cm)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printUsageAndExit();
                }
            }
        }
        LocalDate today = LocalDate.now();
        LocalDate defaultSince = today.withDayOfMonth(1);
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

        int downloaded = 0, skipped = 0, failed = 0;
        for (Activity a : activities) {
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
                skipped++;
                continue;
            }
            try {
                token = ensureFreshToken(token);
                if (!Files.exists(tcx)) {
                    downloadExport(token, a.id, "tcx", tcx);
                    downloaded++;
                    System.out.printf("  [%d] %s.tcx%n", downloaded, base);
                }
                if (!Files.exists(gpx)) {
                    downloadExport(token, a.id, "gpx", gpx);
                    downloaded++;
                    System.out.printf("  [%d] %s.gpx%n", downloaded, base);
                }
                if (!Files.exists(fit)) {
                    downloadFit(token, a.id, fit);
                    downloaded++;
                    System.out.printf("  [%d] %s.fit%n", downloaded, base);
                }
                if (!Files.exists(json)) {
                    downloadSummary(token, a.id, json);
                    downloaded++;
                    System.out.printf("  [%d] %s.json%n", downloaded, base);
                }
                Thread.sleep(250);
            } catch (Exception e) {
                System.err.printf("  FAILED activity %d (%s): %s%n", a.id, a.name, e.getMessage());
                failed++;
            }
        }
        System.out.printf("Done. downloaded=%d skipped=%d failed=%d%n", downloaded, skipped, failed);

        System.out.println("Fetching daily training snapshots (VO2max, race predictions, training status)...");
        int wOk = 0, wSkip = 0, wFail = 0;
        for (LocalDate day = since; !day.isAfter(until); day = day.plusDays(1)) {
            Path dir = DOWNLOAD_DIR
                    .resolve(String.valueOf(day.getYear()))
                    .resolve(String.format("%02d", day.getMonthValue()))
                    .resolve("wellness");
            Files.createDirectories(dir);
            Path target = dir.resolve(day + ".json");
            if (Files.exists(target)) {
                wSkip++;
                continue;
            }
            try {
                token = ensureFreshToken(token);
                String snapshot = fetchDailySnapshot(token, day);
                Files.writeString(target, snapshot);
                wOk++;
                System.out.printf("  wellness %s%n", day);
                Thread.sleep(150);
            } catch (Exception e) {
                System.err.printf("  FAILED wellness %s: %s%n", day, e.getMessage());
                wFail++;
            }
        }
        System.out.printf("Wellness done. ok=%d skipped=%d failed=%d%n", wOk, wSkip, wFail);
    }

    private static void printUsageAndExit() {
        System.err.println("Usage: DownloadGarminFiles --since YYYY-MM-DD [--until YYYY-MM-DD]");
        System.exit(1);
    }

    /**
     * Returns {email, password}. Never echoes the password.
     * Order of sources: env vars first ({@code GARMIN_EMAIL}, {@code GARMIN_PASSWORD}), then a
     * real terminal via {@link System#console()}. If neither is available (e.g. an IntelliJ
     * "Run" configuration with no console), the user is told how to fix it and the process
     * exits — we refuse to read a password through echoing input streams.
     */
    private static String CREDENTIAL_SOURCE = "?";

    private static String[] readCredentials() {
        String envEmail = System.getenv("GARMIN_EMAIL");
        String envPassword = System.getenv("GARMIN_PASSWORD");
        if (envEmail != null && !envEmail.isBlank() && envPassword != null && !envPassword.isBlank()) {
            CREDENTIAL_SOURCE = "env vars GARMIN_EMAIL/GARMIN_PASSWORD";
            return new String[]{envEmail.trim(), envPassword};
        }
        Console console = System.console();
        if (console == null) {
            System.err.println("""
                    No secure input available.
                    Either:
                      • Run this from a real terminal (Terminal.app / iTerm), OR
                      • Set the GARMIN_EMAIL and GARMIN_PASSWORD environment variables.
                    In IntelliJ: Run > Edit Configurations > Environment variables.
                    """);
            System.exit(1);
        }
        CREDENTIAL_SOURCE = "interactive console";
        String email = console.readLine("Garmin email: ").trim();
        char[] pw = console.readPassword("Garmin password: ");
        return new String[]{email, new String(pw)};
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
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
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
     * Combined per-day training-state snapshot: VO2max, race predictions, training status.
     * Missing metrics (e.g. no VO2max because no running that day) come back as {@code null}
     * rather than aborting the day.
     */
    private static String fetchDailySnapshot(Token token, LocalDate day) throws Exception {
        String vo2 = bearerGetJsonOrNull(token,
                CONNECTAPI + "/metrics-service/metrics/maxmet/latest/" + day);
        String race = bearerGetJsonOrNull(token,
                CONNECTAPI + "/metrics-service/metrics/prediction/latest/" + day);
        String status = bearerGetJsonOrNull(token,
                CONNECTAPI + "/metrics-service/metrics/trainingstatus/aggregated/" + day);
        return "{\n"
                + "  \"date\": \"" + day + "\",\n"
                + "  \"vo2max\": " + vo2 + ",\n"
                + "  \"racePrediction\": " + race + ",\n"
                + "  \"trainingStatus\": " + status + "\n"
                + "}\n";
    }

    private static String bearerGetJsonOrNull(Token token, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token.accessToken)
                .header("User-Agent", APP_UA)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
            String body = resp.body() == null ? "" : resp.body().trim();
            return body.isEmpty() ? "null" : body;
        }
        return "null";
    }

    private static void bearerGetToFile(Token token, String url, String accept, Path target)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token.accessToken)
                .header("User-Agent", APP_UA);
        if (accept != null) b.header("Accept", accept);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        HttpResponse<Path> resp = HTTP.send(b.GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
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
