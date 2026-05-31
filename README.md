# OneDrive and Media Tools

Java CLI tools for browsing, downloading, and analyzing personal media across OneDrive,
Android phones, and local video files.

## Prerequisites

- **Java 25+** (the project is configured for Java 25; some tools use preview features)
- **Maven 3.9+** (optional, for building)
- A **personal Microsoft account** with OneDrive
- For video descriptions: **ffmpeg** and local **Ollama** with a vision model
- Default speech transcription: local **Whisper** CLI (`openai-whisper` or `whisper-cpp`)

## Build Instructions

### Using Maven

```bash
mvn clean package
```

This creates a JAR file in `target/louckxb-onedrive-1.0-SNAPSHOT.jar`.

### Direct Execution with Java

Each tool can be run directly with Java's preview mode. For example, to run the **SearchAndDownload** tool:

```bash
java --enable-preview --source 25 src/main/java/net/lckx/onedrive/SearchAndDownload.java
```

## Authentication

Both applications share the same authentication mechanism:

1. **First run** — Uses the [OAuth 2.0 device code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code).
   You are prompted to open https://www.microsoft.com/link and enter a one-time code.
   After signing in, a **refresh token** is saved to `~/.onedrive-token`.
2. **Subsequent runs** — The cached refresh token is used to silently restore the session.
   No sign-in required unless the token expires (~90 days).

The apps request **read-only** access (`Files.Read` + `offline_access`).

---

## net.lckx.video.DescribeVideo

Describes what is in a local video by sampling frames with `ffmpeg` and asking a local
Ollama vision model what each frame contains. It can identify scenes and tags such as
people talking, beach, swimming, kids, older people, objects, pets, sports, parties,
travel, and similar visible content. When no speech option is passed, it asks whether to
transcribe speech and defaults to yes. It can also keep a local known-people library so future
descriptions use names instead of generic labels such as "young girl".

The video is not uploaded to a cloud service. Extracted sample frames are sent to the
Ollama server you configure, which defaults to `http://localhost:11434`. Speech
transcription is local when using the documented Whisper commands.

### Setup

```bash
brew install ffmpeg
brew install ollama
ollama pull llama3.2-vision
ollama serve
```

Speech transcription:

```bash
pipx install openai-whisper
```

If pipx/uv reports an `invalid peer certificate: UnknownIssuer` error, use system
certificates and a compatible Python:

```bash
UV_SYSTEM_CERTS=1 pipx install --python python3.11 openai-whisper
```

Or use whisper.cpp:

```bash
brew install whisper-cpp
# Then pass --speech-model /path/to/ggml-model.bin when running
```

### Run

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java ~/Movies/holiday.mp4 --frames 12 --details
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java ~/Movies/holiday.mp4 --speech-language nl
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java ~/Movies/holiday.mp4 --no-transcribe
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java ~/Movies/holiday.mp4 --random-samples
```

### Known people workflow

By default, sampled frames that appear to contain people are saved under this repository:

```bash
video-people/<video-name>/
```

Rename candidate pictures to the person's name while keeping the frame location:

```bash
mv video-people/holiday/frame-01-01m26s.jpg video-people/holiday/Mila-01-01m26s.jpg
mv video-people/holiday/frame-02-02m53s.jpg video-people/holiday/Mila-02-02m53s.jpg
```

Future descriptions scan renamed pictures under `video-people/` and ask the local vision
model to use a name only when the visible person clearly matches a known reference.
Frame-location samples such as `Mila-01-01m26s.jpg` and `Mila-02-02m53s.jpg` are both
treated as the same person, `Mila`. If the model is not confident, it should keep using
a generic description.

You can also add a reference image directly:

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java --add-person "Mila" ~/Pictures/mila.jpg
```

To review remaining generated candidate pictures interactively:

```bash
java --enable-preview src/main/java/net/lckx/video/ReviewPersonCandidates.java
```

This scans `video-people/` for image filenames that still contain `frame`, opens each image
with the system image viewer, returns focus to the terminal on macOS, and asks whether to
rename it to a person or delete it. Typing `Mila` for `frame-01-01m26s.jpg` renames it to
`Mila-01-01m26s.jpg`. Use
`--viewer terminal` for an ASCII preview directly in the terminal, or `--viewer both` to show
the terminal preview and open the real image.

### Options

| Option | Action |
|--------|--------|
| `--model <name>` | Ollama vision model to use. Default: `llama3.2-vision` |
| `--host <url>` | Ollama host. Default: `http://localhost:11434` |
| `--frames <number>` | Number of frames to sample. Auto-tuned by duration unless provided. Max: `50` |
| `--sample-every-seconds <n>` | Sample one frame every `n` seconds instead of using `--frames`; for example `5` seconds gives about `86` frames for a 07:12 video |
| `--random-samples` | Choose random timestamps instead of the same evenly spaced samples, useful when collecting new person candidates |
| `--random-seed <n>` | Use repeatable random timestamps for debugging; implies `--random-samples` |
| `--image-width <px>` | Width of sampled frame images. Auto-tuned by duration unless provided |
| `--timeout-minutes <n>` | Ollama request timeout. Default: `15`, max: `120` |
| `--no-auto-tune` | Disable duration-aware defaults and timeout retries |
| `--transcribe` | Skip the prompt and transcribe speech using a local Whisper command |
| `--no-transcribe` | Skip the prompt and only describe sampled video frames, without speech transcription |
| `--transcriber <name>` | Speech transcriber: `auto`, `whisper`, `whisper-cli`, or `whisper-cpp` |
| `--speech-model <name-or-path>` | Whisper model name for `whisper`, or ggml model path for whisper.cpp. Default: `small` |
| `--speech-language <code>` | Spoken language code, e.g. `auto`, `en`, `nl`. Default: `auto` |
| `--speech-timeout-minutes <n>` | Speech transcription timeout. Default: `30`, max: `240` |
| `--people-dir <path>` | Known people library. Default: `./video-people` |
| `--add-person <name> <image>` | Add a reference picture for a known person, then exit |
| `--max-person-refs <n>` | Max known-person reference pictures sent to Ollama. Default: `8` |
| `--save-person-candidates` | Save sampled frames that appear to contain people. Enabled by default |
| `--no-save-person-candidates` | Do not save candidate person pictures |
| `--details` | Print every sampled-frame observation after the final summary |
| `--keep-frames` | Keep extracted sample frames instead of deleting them |

The summary is intentionally positive-only: it reports observed or transcribed content
and omits categories that were not seen or heard.

Progress messages include elapsed timestamps and durations for frame extraction, Ollama frame
analysis, transcription, summary generation, and total processing time.

Frame analysis uses duration-aware defaults by default. For example, a several-minute
video uses more samples at a lower resolution so it covers more of the video without
returning to the slow 512 px default. If Ollama still times out on a frame, the tool
retries that frame at a smaller image size and continues with the other sampled frames
instead of aborting the whole run.

For deeper coverage you can explicitly sample more densely:

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java ~/Movies/holiday.mp4 --sample-every-seconds 5
```

This is easy for `ffmpeg`, but it can be slow for Ollama because every sampled frame is a
separate vision request.

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
louckxb-onedrive/
├── pom.xml                                          # Maven configuration
├── src/
│   ├── main/java/net/lckx/
│   │   ├── onedrive/
│   │   │   ├── SearchAndDownload.java              # Interactive browser & downloader
│   │   │   ├── FindBiggestFolders.java             # Folder size analyzer
│   │   │   └── Upload.java                         # Upload files to OneDrive
│   │   ├── video/
│   │   │   └── DescribeVideo.java                  # Local video description tool
│   │   └── phone/
│   │       ├── AndroidPhone.java                   # Phone model
│   │       └── ReadFilesOnPhoneADB.java            # ADB integration
│   ├── cli-tests/                                   # Standalone CLI tests
│   │   ├── OneDriveHelpersTest.java                # Tests for shared helper methods
│   │   ├── UploadPathTransformTest.java            # Tests for path transformation
│   │   └── AndroidPhoneTest.java                   # Tests for phone integration
│   └── main/resources/                              # Resource files (if needed)
└── target/                                          # Build output (created by Maven)
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
- Only the **refresh token** is stored on disk (`~/.onedrive-token` for read, `~/.onedrive-rw-token` for uploads). Access tokens are kept in memory only.
- All communication uses **HTTPS/TLS**.
- Scopes: **read-only** (`Files.Read`) for browsing/downloading, **read-write** (`Files.ReadWrite`) for uploads.

## Testing

### CLI Tests

Standalone test files in `src/cli-tests/` can be run directly with Java:

```bash
# OneDrive helpers test
java --enable-preview --source 25 src/cli-tests/OneDriveHelpersTest.java

# Upload path transformation test
java --enable-preview --source 25 src/cli-tests/UploadPathTransformTest.java

# Android phone test
java --enable-preview --source 25 src/cli-tests/AndroidPhoneTest.java
```

These tests use Java 25 preview features for streamlined test code without external test frameworks.
