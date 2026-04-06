# OneDrive Tools

Two Java CLI tools for browsing, downloading, and analyzing your personal OneDrive account
via the [Microsoft Graph API](https://learn.microsoft.com/en-us/graph/overview).

## Prerequisites

- **Java 21+** (uses unnamed classes / preview features)
- A **personal Microsoft account** with OneDrive

## Authentication

Both applications share the same authentication mechanism:

1. **First run** — Uses the [OAuth 2.0 device code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code).
   You are prompted to open https://www.microsoft.com/link and enter a one-time code.
   After signing in, a **refresh token** is saved to `~/.onedrive-token`.
2. **Subsequent runs** — The cached refresh token is used to silently restore the session.
   No sign-in required unless the token expires (~90 days).

The apps request **read-only** access (`Files.Read` + `offline_access`).

---

## net.lckx.onedrive.SearchAndDownload

Interactive folder browser with download capability.

### Run

```bash
java --enable-preview src/net.lckx.onedrive.SearchAndDownload.java
```

### Features

| Command | Action |
|---------|--------|
| *number* | Open the folder at that position (e.g. `3`) |
| *folder name* | Open a folder by name (e.g. `Joost wereld fotos`) |
| `..` | Navigate to the parent folder |
| `d` | Download all files in the current folder (recursively) |
| `q` or Enter | Quit |

### How it works

1. **Authenticates** using the device code flow (or cached token).
2. **Prompts** for a starting folder (default: `2025`).
3. **Lists** all subfolders (numbered) and files with sizes.
4. **Navigates** interactively — enter a number, folder name, or `..` to go back.
5. **Downloads** (`d`) all files in the current folder and its subfolders to
   `~/Downloads/OneDrive/<folder-path>/`, preserving the folder structure.
   - Shows a live **progress percentage** per file.
   - **Skips** files that already exist locally with the same size.

### Example session

```
Which folder do you want to browse? [2025]:

=== FOLDERS (44) ===
   1. 📁 _vanAlles                                  (0 items)
   2. 📁 20250121 Club Brugge - Juventus            (14 items)
   3. 📁 20250212 Club Brugge - Atalanta            (10 items)

📂 2025
--------------------------------------------------
  ..                (go back)
  d                 (download this folder)
  q                 (quit)

Enter folder name or number to open: 3

=== FILES (10) ===
      📄 IMG_001.jpg                                (3.2 MB)
      ...

Enter folder name or number to open: d
Download to: ~/Downloads/OneDrive/2025/20250212 Club Brugge - Atalanta
Proceed? [Y/n]: y
  ⬇️  IMG_001.jpg (3.2 MB) — 45,20% DONE
```

---

## net.lckx.onedrive.FindBiggestFolders

Scans your entire OneDrive and ranks folders by total size.

### Run

```bash
java --enable-preview src/net.lckx.onedrive.FindBiggestFolders.java
```

### How it works

1. **Authenticates** using the device code flow (or cached token).
2. **Recursively scans** every folder in your OneDrive, collecting each folder's
   total size (including all nested content) and item count.
3. **Displays** the top 200 largest folders, sorted by size descending.
4. **Shows** total OneDrive usage at the bottom.

### Example output

```
==========================================================================================
 TOP 200 LARGEST FOLDERS
==========================================================================================
 Rank  Size          Items   Folder path
------------------------------------------------------------------------------------------
    1. 4.23 GB         152   📁 2025/20250220 aan zee met Regis, Eric, Margo en ook sneeuw
    2. 2.81 GB          95   📁 2025/20250125 weekend Indonesiers - Vielsalm
    3. 1.54 GB         113   📁 2025/20250113 Rochehaut laddertjes wandeling
    ...
==========================================================================================

Total OneDrive usage: 42.3 GB
```

---

## Project structure

```
src/
├── net.lckx.onedrive.SearchAndDownload.java      # Interactive browser & downloader
├── net.lckx.onedrive.FindBiggestFolders.java     # Folder size analyzer
└── net.lckx.onedrive.OneDriveHelpersTest.java    # Unit tests for shared helper methods
```

## Shared internals

Both applications include the same set of helper methods (no external dependencies):

| Method | Purpose |
|--------|---------|
| `authenticate()` | Device code flow with token caching |
| `jsonString()` / `jsonInt()` / `jsonLong()` | Regex-based JSON value extraction |
| `jsonArray()` | Extracts top-level objects from a JSON array (handles nested braces and strings) |
| `encodePath()` | URL-encodes each path segment while preserving `/` separators |
| `formatSize()` | Converts bytes to human-readable sizes (B / KB / MB / GB) |

## Security notes

- Uses Microsoft's public client ID (`14d82eec...`) for the device code flow — suitable for personal use.
- Only the **refresh token** is stored on disk (`~/.onedrive-token`). Access tokens are kept in memory only.
- All communication uses **HTTPS/TLS**.
- Scope is **read-only** (`Files.Read`).
