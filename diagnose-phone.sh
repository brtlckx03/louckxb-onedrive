#!/bin/bash
# Samsung Phone Detection Diagnostic Tool for Mac
# Run this to find where your Samsung phone is mounted

echo "╔════════════════════════════════════════════════════════════╗"
echo "║   Samsung Phone Detection Diagnostic Tool                 ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

echo "📱 Phone Connection Checklist:"
echo "  ☐ Phone connected via USB cable to Mac"
echo "  ☐ Phone is unlocked"
echo "  ☐ Tapped 'Allow' for file access on phone"
echo "  ☐ USB mode set to 'File Transfer' (not 'Charging Only')"
echo ""
echo "Press Enter when ready to scan..."
read

echo ""
echo "🔍 SCAN 1: Listing /Volumes..."
echo "────────────────────────────────────────────────────────────"
ls -la /Volumes/
echo ""

echo "🔍 SCAN 2: Finding all DCIM directories..."
echo "────────────────────────────────────────────────────────────"
find /Volumes -name DCIM -type d 2>/dev/null
if [ $? -eq 0 ] && [ -n "$(find /Volumes -name DCIM -type d 2>/dev/null)" ]; then
    echo "✓ Found DCIM directories above!"
else
    echo "✗ No DCIM directories found in /Volumes"
fi
echo ""

echo "🔍 SCAN 3: Finding Pictures directories..."
echo "────────────────────────────────────────────────────────────"
find /Volumes -name Pictures -type d 2>/dev/null
echo ""

echo "🔍 SCAN 4: Finding Cameras directories..."
echo "────────────────────────────────────────────────────────────"
find /Volumes -name Cameras -type d 2>/dev/null
echo ""

echo "🔍 SCAN 5: Checking mounted filesystems..."
echo "────────────────────────────────────────────────────────────"
echo "All mount points:"
mount | head -20
echo ""

echo "🔍 SCAN 6: Checking for removable media..."
echo "────────────────────────────────────────────────────────────"
diskutil list | grep -i external
diskutil list | grep -i removable
diskutil list | grep -i disk
echo ""

echo "🔍 SCAN 7: Checking for Android File Transfer..."
echo "────────────────────────────────────────────────────────────"
if [ -d "/Applications/Android File Transfer.app" ]; then
    echo "✓ Android File Transfer installed"
    echo "  Location: /Applications/Android File Transfer.app"
    echo "  You can launch it to transfer files"
else
    echo "✗ Android File Transfer NOT installed"
    echo "  Download from: https://www.android.com/filetransfer/"
fi
echo ""

echo "🔍 SCAN 8: Checking USB devices..."
echo "────────────────────────────────────────────────────────────"
system_profiler SPUSBDataType 2>/dev/null | grep -A 5 "Samsung\|Android\|Phone" || echo "No Android/Samsung USB devices found"
echo ""

echo "╔════════════════════════════════════════════════════════════╗"
echo "║   RESULTS SUMMARY                                         ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

PHONE_PATH=$(find /Volumes -name DCIM -type d 2>/dev/null | head -1 | xargs dirname)

if [ -n "$PHONE_PATH" ] && [ "$PHONE_PATH" != "/" ]; then
    echo "✓ Phone detected at: $PHONE_PATH"
    echo ""
    echo "To use this with the app:"
    echo "  1. Run: java -cp src ReadFilesOnPhone"
    echo "  2. When asked for path, enter: $PHONE_PATH"
    echo ""
else
    echo "✗ Phone not detected in standard locations"
    echo ""
    echo "TROUBLESHOOTING STEPS:"
    echo ""
    echo "1. Check phone USB settings:"
    echo "   • Go to Settings → Developer Options → USB Configuration"
    echo "   • Select 'File Transfer' or 'MTP' mode"
    echo "   • NOT 'Charging Only'"
    echo ""
    echo "2. Try downloading Android File Transfer:"
    echo "   • Download: https://www.android.com/filetransfer/"
    echo "   • Install and run on Mac"
    echo "   • Connect phone and see if it detects"
    echo ""
    echo "3. Manually check phone filesystem:"
    echo "   • On phone, enable USB Debugging"
    echo "   • Try: adb devices (if you have Android SDK)"
    echo ""
    echo "4. Run this diagnostic again after changing USB mode"
    echo ""
fi
