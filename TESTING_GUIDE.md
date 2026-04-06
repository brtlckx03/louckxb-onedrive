# Testing Guide - Samsung Photo Manager

## Overview

There are two test classes in this project:

| Class | Purpose | Status |
|-------|---------|--------|
| **PhoneTest.java** | Tests old mounted volume approach | ⚠️ Deprecated (for reference only) |
| **net.lckx.phone.AndroidPhoneTest.java** | Tests new ADB direct connection | ✅ Active (use this one) |

## Why the Change?

### PhoneTest.java (Old Approach)
- Tests `Phone.java` which looks for phone in `/Volumes` directory
- **Problem**: macOS doesn't automatically mount Android phones via USB
- **Result**: Tests fail with "Phone not detected" message
- **Used for**: Reference and understanding how USB mounting would work (not practical on Mac)

### net.lckx.phone.AndroidPhoneTest.java (New Approach)
- Tests `net.lckx.phone.AndroidPhone.java` which uses ADB direct connection
- **Benefit**: Works with any Android phone via USB Debugging
- **Standard**: Uses same method as professional Android development
- **Reliable**: Works on Mac, Linux, and Windows

## Running the New Test Suite

### Prerequisites
```bash
# 1. Ensure ADB is installed
adb version

# 2. Connect Samsung phone via USB
# 3. Phone shows "Allow USB Debugging?" popup - tap Allow
# 4. Verify phone is recognized
adb devices
```

### Compile and Run
```bash
# Navigate to project directory
cd /Users/louckxb/Documents/wd-brtlckx/louckxb-onedrive

# Compile the test
javac src/net.lckx.phone.AndroidPhone.java src/net.lckx.phone.AndroidPhoneTest.java

# Run the test suite
java -cp src net.lckx.phone.AndroidPhoneTest
```

## Test Coverage

### Test 1: ADB Availability
**What it does:** Verifies ADB is installed and working

**Expected output:**
```
Test 1: Check ADB availability...
✓ ADB is available and working
```

**If it fails:**
```
❌ ADB not available: ADB not found. Please install Android SDK Platform Tools.

To fix this:
  1. Install Android SDK Platform Tools:
     brew install android-platform-tools
  2. Verify: adb version
```

---

### Test 2: Phone Connection
**What it does:** Connects to phone via ADB and loads all photos

**Expected output:**
```
Test 2: Connect to phone via ADB and load photos...
🔗 Creating net.lckx.phone.AndroidPhone instance...
✓ Successfully created net.lckx.phone.AndroidPhone object

📱 Attempting to load photos from phone...
✓ Successfully connected and loaded photos

📊 Photo Statistics:
   Total photos: 7705
   Load time: 2.43 seconds
   First photo: IMG_20250515_100200.jpg
   Last photo: IMG_20260329_210500.jpg
```

**If it fails:**
```
❌ Phone connection failed: No Android devices connected...

Troubleshooting:
  1. Ensure phone is connected via USB cable
  2. Unlock your phone
  3. Check if 'Allow USB Debugging?' popup is showing - tap Allow
  4. Verify with: adb devices
```

---

### Test 3: Single Date Filtering
**What it does:** Tests filtering photos by a specific date

**Expected output:**
```
Test 3: Filter photos by single date...
Testing with date: 2026-03-15

✓ Found 47 photos from 2026-03-15
  Search time: 3ms

  Sample photos:
    - IMG_20260315_095000.jpg (3.2 MB)
    - IMG_20260315_105500.jpg (4.1 MB)
    - IMG_20260315_140300.jpg (2.8 MB)
    ... and 44 more
```

**What it verifies:**
- Date filtering works correctly
- Search is fast (1-10ms)
- Timestamps are readable

---

### Test 4: Date Range Filtering
**What it does:** Tests filtering photos within a date range

**Expected output:**
```
Test 4: Filter photos by date range...
Testing range: 2026-03-15 to 2026-03-22

✓ Found 315 photos in date range
  Search time: 8ms

  Date distribution in range:
    2026-03-15: 47 photos
    2026-03-16: 52 photos
    ...
```

**What it verifies:**
- Date range filtering works correctly
- Can handle multiple dates
- Filtering remains fast

---

### Test 5: Performance Timing
**What it does:** Verifies load and filter times are accurate

**Expected output:**
```
Test 5: Verify performance timing...
✓ Load time recorded: 2.43 seconds
  Total elapsed time: 2.45 seconds
  Difference: 20ms (expected ~0-50ms)

✓ Filter time recorded: 3ms
  (Should be < 100ms for cached filtering)

✓ Performance is excellent (instant filtering)
```

**What it verifies:**
- Timing measurements are accurate
- Filtering is instant (cached data)
- Loading takes 2-5 seconds for 7000+ photos

## Troubleshooting

### Common Issues

#### "ADB not found"
```bash
# Install ADB
brew install android-platform-tools

# Verify installation
adb version
```

#### "No Android devices connected"
```bash
# Check if phone is visible
adb devices

# If blank, check:
# 1. USB cable is connected properly
# 2. Phone is unlocked
# 3. "Allow USB Debugging?" popup - tap Allow on phone
# 4. Try different USB port
# 5. Restart phone and Mac
```

#### "Phone detected but no photos found"
- Photos might be in a different directory than expected
- Check if `/sdcard/DCIM/Camera` exists on your phone
- Fallback directories checked: `/sdcard/DCIM`, `/sdcard/Pictures`, `/storage/emulated/0/DCIM/Camera`

## Next Steps

After running the test suite successfully:

1. **Run the main app:**
   ```bash
   java -cp src net.lckx.phone.ReadFilesOnPhoneADB
   ```

2. **Enter a date to search for photos:**
   ```
   Enter date(s): 1/03/2026 till 3/03/2026
   ```

3. **Download selected photos:**
   ```
   Download these photos from phone? (yes/no): yes
   ```

4. **Search for more photos:**
   ```
   Search more photos? (yes/no/exit): yes
   ```

## Test Results Guide

| Metric | Good | Acceptable | Poor |
|--------|------|------------|------|
| **Load time** | 2-5 sec | 5-15 sec | >15 sec |
| **Single date search** | 1-5 ms | 5-50 ms | >50 ms |
| **Date range search** | 3-15 ms | 15-100 ms | >100 ms |

All timing is for 7000+ photo library.
