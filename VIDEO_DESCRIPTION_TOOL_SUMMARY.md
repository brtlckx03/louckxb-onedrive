# Video Description Tool Summary

This project now includes a local Java CLI tool that can describe what is visible in a video, optionally transcribe speech, and use a local known-people image library so future descriptions can use names instead of generic labels.

The main program is:

```bash
src/main/java/net/lckx/video/DescribeVideo.java
```

## What was added

The tool can:

- Sample frames from a local video.
- Ask a local vision model what is visible in those frames.
- Summarize observed people, places, objects, activities, and tags.
- Transcribe speech with a local Whisper-compatible command.
- Save person candidate pictures under `video-people/<video-name>/`.
- Let you rename saved person pictures, for example `Miranda-01-01m26s.jpg`.
- Reuse renamed pictures as known-person references in future runs.
- Auto-tune frame count and image size based on video duration.
- Retry slow frame analysis with smaller images when Ollama times out.
- Sample densely with `--sample-every-seconds`, for example one frame every 5 seconds.
- Print elapsed timestamps and step durations so slow parts are visible.

The output is intentionally positive-only: it should describe what was seen or heard, instead of listing things that were not present.

## Tools used

| Tool | Purpose | Why this tool was chosen |
| --- | --- | --- |
| Java 25 | Main CLI implementation | The repository is already a Java CLI project and is configured for Java 25. |
| ffmpeg / ffprobe | Read video duration, extract frames, and extract audio | Free, mature, fast, local, and widely supported for video/audio processing. |
| Ollama | Run a local vision language model | Keeps analysis local and avoids paid cloud vision APIs. |
| llama3.2-vision | Default local vision model | Works with Ollama and can describe images from sampled video frames. |
| Whisper / openai-whisper | Local speech transcription | Free local transcription without uploading audio to a cloud API. |
| whisper.cpp | Alternative local speech transcription option | Useful when you prefer a native/local binary and local ggml models. |
| Maven | Build and test runner | Existing project build system. |

## How to install the tools

Install video and vision dependencies:

```bash
brew install ffmpeg
brew install ollama
ollama pull llama3.2-vision
ollama serve
```

Install local speech transcription with OpenAI Whisper:

```bash
pipx install openai-whisper
```

If `pipx` fails with an `invalid peer certificate: UnknownIssuer` error, use:

```bash
UV_SYSTEM_CERTS=1 pipx install --python python3.11 openai-whisper
```

Alternative transcription setup with whisper.cpp:

```bash
brew install whisper-cpp
```

Then pass a local ggml model path with `--speech-model`.

## How to use it

Basic run:

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java /path/to/video.mp4
```

When no speech option is passed, the tool asks:

```text
Speech transcription is enabled by default. Transcribe speech for this video? [Y/n]
```

Press Enter for yes, or type `n` for no.

Run without speech transcription:

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java /path/to/video.mp4 --no-transcribe
```

Run with speech transcription without prompting:

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java /path/to/video.mp4 --transcribe
```

Use a specific spoken language:

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java /path/to/video.mp4 --speech-language nl
```

Sample one frame every 5 seconds:

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java /path/to/video.mp4 --sample-every-seconds 5
```

Choose random timestamps so repeated runs can collect different fragments:

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java /path/to/video.mp4 --random-samples
```

Print detailed observations for each sampled frame:

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java /path/to/video.mp4 --details
```

## Known people workflow

By default, candidate pictures that appear to contain people are saved here:

```bash
video-people/<video-name>/
```

Example:

```bash
video-people/verstoppertje_20240527_235745068/frame-01-01m26s.jpg
```

Rename that file to the person's name while keeping the frame location:

```bash
mv video-people/verstoppertje_20240527_235745068/frame-01-01m26s.jpg \
   video-people/verstoppertje_20240527_235745068/Miranda-01-01m26s.jpg
```

More samples for the same person can be added like this:

```bash
video-people/verstoppertje_20240527_235745068/Miranda-01-01m26s.jpg
video-people/verstoppertje_20240527_235745068/Miranda-02-02m53s.jpg
video-people/verstoppertje_20240527_235745068/Miranda-03-04m19s.jpg
```

All of those are treated as references for `Miranda`.

Future runs scan renamed images under `video-people/` and ask the local vision model to use a known name only when the visible person clearly matches a reference image. If the model is not confident, it should keep using a generic description.

You can also add a reference image directly:

```bash
java --enable-preview src/main/java/net/lckx/video/DescribeVideo.java --add-person "Miranda" ~/Pictures/miranda.jpg
```

To clean up generated candidate pictures interactively:

```bash
java --enable-preview src/main/java/net/lckx/video/ReviewPersonCandidates.java
```

This scans `video-people/` for image filenames that still contain `frame`. For each one, it
opens the image, returns focus to the terminal on macOS, and asks for a person name, delete,
skip, or quit. Typing `Miranda` for `frame-01-01m26s.jpg` renames it to
`Miranda-01-01m26s.jpg`. Use `--viewer terminal` for an ASCII preview in the terminal, or
`--viewer both` to print the ASCII preview and open the real image.

## Why these choices were made

The goal was to build a useful personal video description tool without sending private videos to a cloud service.

The chosen design keeps each step simple and local:

- `ffmpeg` extracts representative images and audio.
- Ollama describes sampled frames locally.
- Whisper transcribes speech locally.
- Java coordinates the workflow inside the existing repository.
- `video-people/` stores local reference images that you control.

This avoids monthly API costs and avoids uploading private family videos, photos, or speech to external AI services.

## Free and cost details

The software stack is free to use:

- Java is free.
- Maven is free.
- ffmpeg is free.
- Ollama is free.
- The documented local Whisper tools are free.
- There are no cloud API calls and no per-video API charges.

Practical costs still exist:

- The computer uses CPU/GPU power while analyzing videos.
- Long videos or dense sampling can take a lot of time.
- Large local models use disk space and memory.
- Individual model licenses can differ, so check the license of any model you choose to download.

## Security and privacy

The intended setup is local-first:

- The video file stays on your computer.
- Frames are extracted locally with `ffmpeg`.
- Audio is extracted and transcribed locally.
- The default Ollama host is `http://localhost:11434`.
- Known-person images are stored locally under `video-people/`.
- `video-people/` is ignored by git so personal face/reference pictures are not accidentally committed.

Important security note: this privacy model depends on using a local Ollama server and local transcription command. If you change `--host` to a remote Ollama server, frames will be sent to that remote server. If you replace Whisper with a cloud transcription command, audio may leave your machine.

## Limitations

The tool is useful, but it is not perfect:

- It describes sampled frames, not every frame of the video unless you choose dense sampling.
- Dense sampling such as `--sample-every-seconds 5` gives better coverage but can be slow because each sampled frame is a separate vision request.
- The known-person feature is prompt-based visual comparison, not professional biometric face recognition.
- Person names are used only when the model appears confident, but mistakes are still possible.
- Candidate person pictures are full sampled frames, not guaranteed face crops.
- Transcription accuracy depends on audio quality, background noise, language, accents, and the selected Whisper model.
- Vision models can miss details, confuse similar people, or hallucinate details.
- Ollama vision requests can time out on slower machines, large images, large frame counts, or long videos.
- `llama3.2-vision` accepts one image input per request, so the tool uses a contact-sheet workaround when comparing a video frame with known-person reference images.
- The tool does not understand off-screen events unless they are visible in sampled frames or present in the transcript.

## Useful options

| Option | Action |
| --- | --- |
| `--model <name>` | Ollama vision model. Default: `llama3.2-vision`. |
| `--host <url>` | Ollama host. Default: `http://localhost:11434`. |
| `--frames <number>` | Number of frames to sample. Auto-tuned unless provided. |
| `--sample-every-seconds <n>` | Sample one frame every `n` seconds. |
| `--random-samples` | Choose random timestamps instead of the same evenly spaced samples. |
| `--random-seed <n>` | Use repeatable random timestamps; implies `--random-samples`. |
| `--image-width <px>` | Width of extracted frame images. Auto-tuned unless provided. |
| `--timeout-minutes <n>` | Ollama request timeout. |
| `--transcribe` | Transcribe speech and skip the prompt. |
| `--no-transcribe` | Disable speech transcription and skip the prompt. |
| `--speech-language <code>` | Spoken language code, for example `auto`, `en`, or `nl`. |
| `--people-dir <path>` | Known people library. Default: `./video-people`. |
| `--add-person <name> <image>` | Add a known-person reference image. |
| `--max-person-refs <n>` | Max known-person references sent to Ollama. |
| `--details` | Print individual frame observations. |
| `--keep-frames` | Keep extracted sampled frames. |

## Current project files

Main implementation:

```bash
src/main/java/net/lckx/video/DescribeVideo.java
```

Tests:

```bash
src/test/java/net/lckx/video/DescribeVideoTest.java
```

Documentation:

```bash
README.md
VIDEO_DESCRIPTION_TOOL_SUMMARY.md
```

Private local people images:

```bash
video-people/
```

The `video-people/` directory should stay local and should not be committed.
