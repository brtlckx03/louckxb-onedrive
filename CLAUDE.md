# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build fat JAR with all dependencies
mvn clean package

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=OneDriveHelpersTest
mvn test -Dtest=UploadPathTransformTest
mvn test -Dtest=AndroidPhoneTest

# Run tools directly (requires Java 25 with preview features)
java --enable-preview src/main/java/net/lckx/onedrive/SearchAndDownload.java
java --enable-preview src/main/java/net/lckx/onedrive/Upload.java
java --enable-preview src/main/java/net/lckx/onedrive/FindBiggestFolders.java
java --enable-preview src/main/java/net/lckx/ReadFilesOnPhone.java
java --enable-preview src/main/java/net/lckx/phone/ReadFilesOnPhoneADB.java
```

Java 25 preview features are required — the source files use unnamed classes (no `class` declaration wrapper).

## Architecture

This is a personal CLI toolkit for managing files across OneDrive and Android phones. It is split into two domains:

### OneDrive Tools (`net.lckx.onedrive`)

Three standalone CLI programs that share the same Microsoft Graph API + OAuth 2.0 device code flow pattern. Authentication tokens are cached to disk (`~/.onedrive-token` for read-only, `~/.onedrive-rw-token` for read-write).

- **SearchAndDownload**: Interactive OneDrive folder browser. Navigates the folder hierarchy with numbered menus, then downloads recursively. Skips files that already exist locally with a matching size.
- **Upload**: Uploads local folders to OneDrive. Uses simple PUT for files < 4 MB and chunked resumable sessions (5 MB chunks) for larger files. Skips already-uploaded files by size. After upload, archives the local folder. Suggests a destination path based on date prefix in the folder name.
- **FindBiggestFolders**: Recursively scans all of OneDrive, accumulates sizes, and prints the 200 largest folders.

### Phone Tools (`net.lckx` and `net.lckx.phone`)

Two independent approaches to browsing photos on an Android phone:

- **ReadFilesOnPhone + Phone**: Reads photos from a USB-mounted phone volume. Auto-detects Samsung phones via `/Volumes`, or accepts a manual mount path. `Phone.PhotoFile` is a record holding path, mod-time, and size.
- **ReadFilesOnPhoneADB + AndroidPhone**: Reads photos via ADB without manual export. Locates the `adb` binary, lists connected devices, parses `ls -l` output for metadata, and supports `adb pull` downloads. `AndroidPhone.PhotoFile` is a record holding filename, remote path, and device ID. Integration tests are skipped when no device is connected.

### Key Implementation Details

**No external dependencies** — all JSON parsing is done with handwritten regex helpers (`jsonString`, `jsonInt`, `jsonLong`, `jsonArray`) that are duplicated across the three OneDrive tools. Network I/O uses `java.net.http`. This is intentional; avoid introducing library dependencies.

**Shared helper methods** duplicated across OneDrive files: `jsonString()`, `jsonInt()`, `jsonLong()`, `jsonArray()`, `encodePath()`, `formatSize()`, `formatDuration()`. When fixing a bug in one, check whether the same bug exists in the others.

**Authentication flow**: device code is printed to stdout; the user authenticates in a browser; only the refresh token is persisted to disk. The access token lives only in memory.