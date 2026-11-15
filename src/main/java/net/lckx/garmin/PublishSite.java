package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Puts the pages of every account online with Cloudflare Pages, without the Garmin files behind them.
 *
 * <p>First the site files (html, js and css; no FIT, GPX, JSON or caches) of each account folder are copied to
 * {@code target/site}, keeping the folders ({@code garmin/}, {@code garmin-peter/}, …), so the links between the
 * accounts keep working. Only changed files are copied; files that are gone are removed. A root page goes to the main
 * account, and robots.txt and a header keep search engines out.
 *
 * <p>With {@code --deploy} the folder is then uploaded with Cloudflare's own tool, wrangler (through npx; Node is
 * needed). It only sends files that changed. The login is wrangler's: run {@code npx wrangler@4 login} once, in a
 * browser; this class never sees a password or token.
 *
 * <p>The pages hold GPS tracks (where the accounts live) and heart rates: protect the project with Cloudflare Access
 * <b>before</b> the first deploy (see {@link #ACCESS_HINT}).
 *
 * <p>Usage: {@code PublishSite [--deploy] [--project NAME] [--out DIR]}, from the project folder.
 */
final class PublishSite {
    static final String DEFAULT_PROJECT = "garmin-lckx";
    private static final Set<String> SITE_FILES = Set.of("html", "js", "css", "svg", "png", "ico");
    /** Cloudflare Pages limits: files per deployment and bytes per file. */
    private static final int MAX_FILES = 20_000;
    private static final long MAX_FILE = 25L * 1024 * 1024;
    private static final String WRANGLER = "wrangler@4";

    static final String ACCESS_HINT = """
            Before the first deploy, so nothing is public even for a minute:
              1. npx %1$s login                                        (once; in the browser)
              2. npx %1$s pages project create %2$s --production-branch main
              3. Cloudflare dashboard → Workers & Pages → %2$s → Settings → General → Access policy → Enable.
                 That protects the preview addresses; then Zero Trust → Access → Applications → that application →
                 add the public hostname %2$s.pages.dev as well, so the main address asks for a login too.
                 Policy: Allow, Include → Emails → the addresses that may look (a code is mailed to them).
              4. PublishSite --deploy
            """;

    private PublishSite() {}

    static void main(String[] args) throws Exception {
        boolean deploy = false;
        String project = DEFAULT_PROJECT;
        Path out = Path.of("target", "site");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--deploy" -> deploy = true;
                case "--project" -> project = args[++i].toLowerCase(Locale.ROOT);
                case "--out" -> out = Path.of(args[++i]);
                case "-h", "--help" -> {
                    System.out.println("Usage: PublishSite [--deploy] [--project NAME] [--out DIR]");
                    System.out.printf(ACCESS_HINT, WRANGLER, project);
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i] + " (see --help)");
            }
        }
        List<String> accounts = Together.accounts();
        if (accounts.isEmpty()) {
            System.err.println("No account folders with an index.html under " + DescribeGarminFile.dataDir(DescribeGarminFile.MAIN_ACCOUNT).getParent()
                    + "; run this from the project folder.");
            System.exit(1);
        }
        Files.createDirectories(out);
        Set<Path> keep = new HashSet<>();
        int copied = 0, total = 0;
        for (String account : accounts) {
            Path src = DescribeGarminFile.dataDir(account);
            Path dst = out.resolve(src.getFileName().toString());
            List<Path> files;
            try (Stream<Path> s = Files.walk(src)) {
                files = s.filter(Files::isRegularFile).filter(PublishSite::isSiteFile).toList();
            }
            for (Path f : files) {
                Path to = dst.resolve(src.relativize(f).toString());
                keep.add(to.toAbsolutePath().normalize());
                total++;
                if (Files.size(f) > MAX_FILE) System.err.println("Too big for Cloudflare Pages (25 MB): " + f);
                if (Files.exists(to) && Files.size(to) == Files.size(f)
                        && Files.getLastModifiedTime(to).compareTo(Files.getLastModifiedTime(f)) == 0) continue;
                Files.createDirectories(to.getParent());
                Files.copy(f, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            }
            System.out.printf("%s: %d site files%n", DescribeGarminFile.accountLabelOf(account), files.size());
        }
        String main = DescribeGarminFile.dataDir(DescribeGarminFile.MAIN_ACCOUNT).getFileName().toString();
        total += writeExtra(out, keep, "index.html", """
                <!doctype html><meta charset="utf-8"><meta name="robots" content="noindex">
                <meta http-equiv="refresh" content="0; url=%1$s/index.html"><title>Garmin</title>
                <a href="%1$s/index.html">Garmin</a>
                """.formatted(main));
        total += writeExtra(out, keep, "robots.txt", "User-agent: *\nDisallow: /\n");
        total += writeExtra(out, keep, "_headers", "/*\n  X-Robots-Tag: noindex, nofollow\n  Referrer-Policy: strict-origin-when-cross-origin\n");
        int removed = removeOthers(out, keep);
        System.out.printf("%s: %d files (%d copied, %d removed)%n", out, total, copied, removed);
        if (total > MAX_FILES) {
            System.err.printf("More than %d files: Cloudflare Pages will refuse this deployment.%n", MAX_FILES);
            System.exit(1);
        }
        if (!deploy) {
            System.out.println("Not uploaded; add --deploy to put it online.");
            System.out.printf(ACCESS_HINT, WRANGLER, project);
            return;
        }
        List<String> cmd = List.of("npx", "--yes", WRANGLER, "pages", "deploy", out.toString(), "--project-name", project,
                "--branch", "main", "--commit-dirty=true");
        System.out.println("> " + String.join(" ", cmd));
        int code = new ProcessBuilder(cmd).inheritIO().start().waitFor();
        if (code != 0) {
            System.err.println("wrangler failed (exit " + code + "). Not logged in? Run: npx " + WRANGLER + " login");
            System.exit(code);
        }
        System.out.printf("Online at https://%s.pages.dev/ — check that it asks for a login.%n", project);
    }

    private static boolean isSiteFile(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 && !n.startsWith(".") && SITE_FILES.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static int writeExtra(Path out, Set<Path> keep, String name, String content) throws IOException {
        Path f = out.resolve(name);
        keep.add(f.toAbsolutePath().normalize());
        if (!Files.exists(f) || !Files.readString(f, StandardCharsets.UTF_8).equals(content)) Util.replace(f, content);
        return 1;
    }

    /** Removes what is no longer part of the site (a renamed report, a removed account), and empty folders. */
    private static int removeOthers(Path out, Set<Path> keep) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.walk(out)) {
            all = new ArrayList<>(s.toList());
        }
        int removed = 0;
        for (Path p : all.reversed()) {
            if (p.equals(out)) continue;
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    if (s.findAny().isEmpty()) Files.delete(p);
                }
            } else if (!keep.contains(p.toAbsolutePath().normalize())) {
                Files.delete(p);
                removed++;
            }
        }
        return removed;
    }
}
