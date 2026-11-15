package net.lckx.garmin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Downloads every account (garmin, garmin-NAME folders) in one go: {@link DownloadGarminFiles} once per account, each
 * from the last day it already has (that day again, it may have been incomplete) up to today.
 *
 * <p>Usage: {@code DownloadAllAccounts [--since YYYY-MM-DD] [--until YYYY-MM-DD] [ACCOUNT …]}; without account names
 * every account with a folder is downloaded, the main account first. Run it from the project folder, like the others.
 *
 * <p>Each account runs in its own Java process, because DownloadGarminFiles keeps the login (token, cookies) and the
 * page caches per process. Logins are as before: ~/.garmin-token(-NAME), ~/.garmin-credentials(-NAME) or the prompt.
 * Afterwards the pages of the accounts downloaded first are rebuilt, so their "Together" pages see the later ones'
 * new activities too.
 */
final class DownloadAllAccounts {
    private static final Pattern DAY_FILE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})[_.-].*");

    private DownloadAllAccounts() {}

    static void main(String[] args) throws Exception {
        LocalDate since = null, until = null;
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--since" -> since = LocalDate.parse(args[++i]);
                case "--until" -> until = LocalDate.parse(args[++i]);
                case "-h", "--help" -> {
                    System.out.println("Usage: DownloadAllAccounts [--since YYYY-MM-DD] [--until YYYY-MM-DD] [ACCOUNT …]");
                    return;
                }
                default -> accounts.add(args[i].toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (accounts.isEmpty()) accounts.addAll(Together.accounts());
        if (accounts.isEmpty()) accounts.add(DescribeGarminFile.MAIN_ACCOUNT);
        // The main account first, then the others in name order.
        accounts.sort((a, b) -> a.equals(DescribeGarminFile.MAIN_ACCOUNT) ? -1 : b.equals(DescribeGarminFile.MAIN_ACCOUNT) ? 1 : a.compareTo(b));
        LocalDate to = until != null ? until : LocalDate.now();

        List<String> failed = new ArrayList<>(), done = new ArrayList<>();
        for (String account : accounts) {
            LocalDate from = since != null ? since : lastDay(DescribeGarminFile.dataDir(account)).orElse(to.minusDays(30));
            if (from.isAfter(to)) from = to;
            System.out.printf("%n=== %s: %s to %s ===%n", DescribeGarminFile.accountLabelOf(account), from, to);
            int code = run("net.lckx.garmin.DownloadGarminFiles", "--account", account, "--since", from.toString(), "--until", to.toString());
            if (code == 0) done.add(account);
            else failed.add(account + " (exit " + code + ")");
        }
        // Every other account's pages (its "Together" page) were written before the last download had its new data.
        if (!done.isEmpty()) {
            String last = done.getLast();
            for (String other : Together.accounts()) {
                if (other.equals(last)) continue;
                System.out.printf("%n=== %s: pages again, with the other accounts' new data ===%n", DescribeGarminFile.accountLabelOf(other));
                if (run("net.lckx.garmin.DescribeGarminFile", "--pages", "--account", other) != 0) failed.add(other + " (pages)");
            }
        }
        System.out.println();
        System.out.println(failed.isEmpty() ? "All accounts done: " + String.join(", ", done) : "Failed: " + String.join(", ", failed));
        if (!failed.isEmpty()) System.exit(1);
    }

    /** The latest day with a downloaded file (activity or wellness); the Strava import does not count. */
    static java.util.Optional<LocalDate> lastDay(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return java.util.Optional.empty();
        LocalDate last = null;
        try (Stream<Path> s = Files.walk(dir, 4)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (dir.relativize(p).startsWith(StravaImport.FOLDER)) continue;
                String n = p.getFileName().toString();
                if (n.endsWith("-report.html")) continue;
                Matcher m = DAY_FILE.matcher(n);
                if (!m.matches()) continue;
                try {
                    LocalDate d = LocalDate.parse(m.group(1));
                    if (last == null || d.isAfter(last)) last = d;
                } catch (Exception ignored) {
                    // not a date after all
                }
            }
        }
        return java.util.Optional.ofNullable(last);
    }

    /** Runs another tool of this project in its own Java process, sharing this console (for a login prompt). */
    private static int run(String mainClass, String... args) throws IOException, InterruptedException {
        // The children need the compiled classes: run this with -cp target/classes (or from the IDE), not as a source file.
        if (!System.getProperty("java.class.path", "").contains("target")) System.err.println(
                "Warning: class path " + System.getProperty("java.class.path") + " may not hold the compiled classes; use -cp target/classes.");
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-preview", "-cp", System.getProperty("java.class.path"), mainClass));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }
}
