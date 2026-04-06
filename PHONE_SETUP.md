# Samsung Phone Photo Gallery Reader - Phone Setup Guide

## 🚨 Current Issue: Phone Not Auto-Detected

If you're getting "Samsung phone not detected", it's likely because **Mac doesn't automatically mount Samsung phones** the way it does with USB drives. This is normal!

## ✅ Solution Options

### **OPTION 1: Use Android File Transfer (Recommended)**

This is the official Google tool for Mac-to-Android file transfer.

1. **Download Android File Transfer**
   - Go to: https://www.android.com/filetransfer/
   - Download for Mac
   - Install in /Applications

2. **On Your Samsung Phone**
   - Connect via USB cable
   - Unlock the phone
   - Tap "Allow" when prompted
   - In Settings, change USB mode to "File Transfer" or "MTP"
   - NOT "Charging Only"

3. **Open Android File Transfer**
   - Launch the app
   - Your phone should appear automatically
   - You'll see the folder structure including DCIM/Camera

4. **Find the Mount Path**
   - In Android File Transfer, note where your photos are
   - Common path: `/Volumes/[Device Name]` or through the app interface

5. **Use with Our App**
   - Run: `./diagnose-phone.sh` (in the repository)
   - Or run the Java app and manually enter the path

### **OPTION 2: Manual Diagnostic (Advanced)**

Run the provided diagnostic script:

```bash
cd /Users/louckxb/Documents/wd-brtlckx/louckxb-onedrive
./diagnose-phone.sh
```

This will:
- ✓ List all /Volumes
- ✓ Find DCIM/Pictures/Cameras directories
- ✓ Check for removable media
- ✓ Detect USB devices
- ✓ Report the phone mount path if found

### **OPTION 3: Manual Path Entry**

If you find your phone's path:

1. Run: `java -cp src ReadFilesOnPhone`
2. Choose "Option 2: Manual Mount Path"
3. Enter the path (e.g., `/Volumes/Galaxy`)

## 🔧 Phone Settings Checklist

Before connecting, ensure on your Samsung phone:

- [ ] **USB Connection Mode**: File Transfer (not Charging)
  - Settings → Developer Options → USB Configuration → File Transfer
  - OR: When plugged in, swipe down and change "Charging Only" to "File Transfer"

- [ ] **Developer Options Enabled**: 
  - Settings → About Phone → tap Build Number 7 times
  - Settings → Developer Options → USB Debugging (may need to enable)

- [ ] **File Access Granted**:
  - When connected, phone will prompt "Allow file access?"
  - Tap **ALLOW**
  - Don't tap "Charge Only"

- [ ] **Phone Unlocked**:
  - Phone must be unlocked during file transfer
  - Can use fingerprint or pattern lock

## 📋 Troubleshooting Commands

Run these in Terminal to diagnose:

```bash
# List all volumes
ls -la /Volumes/

# Find DCIM directories (photo folders)
find /Volumes -name DCIM -type d 2>/dev/null

# Find Pictures directories
find /Volumes -name Pictures -type d 2>/dev/null

# Check for external drives/devices
diskutil list

# Check USB devices
system_profiler SPUSBDataType

# Check all mounts
mount | grep -i mtp
mount | grep -i media
```

## 🔌 USB Cable & Connection

- Use a **good quality USB cable**
- Connect directly to Mac (not through a hub)
- Try different USB ports on Mac
- Wait 2-3 seconds after connecting before running the app

## 📱 Using Android File Transfer

1. Download: https://www.android.com/filetransfer/
2. Install on Mac
3. Launch Android File Transfer
4. Connect phone
5. Browse to DCIM/Camera folder
6. Note the full path shown in the app

Then use that path with our app!

## ✅ Recommended Workflow

1. **Install Android File Transfer**
   ```
   https://www.android.com/filetransfer/
   ```

2. **Configure Phone**
   - Settings → USB mode: File Transfer
   - Developer Options → USB Debugging: ON

3. **Connect Phone**
   - Connect via USB cable
   - Unlock phone
   - Tap "Allow"

4. **Test Connection**
   ```bash
   ./diagnose-phone.sh
   ```

5. **Run the App**
   ```bash
   java -cp src ReadFilesOnPhone
   ```

6. **Enter Phone Path**
   - If auto-detection fails
   - Choose Manual Path option
   - Enter: `/Volumes/Galaxy` (or whatever it shows)

## 📞 If Still Having Issues

1. **Try a different USB cable** (seriously!)
2. **Try a different USB port** on Mac
3. **Restart both phone and Mac**
4. **Update Android on phone** (Settings → About → System Update)
5. **Install Android File Transfer** and verify connection there first
6. **Check Mac System Report** (Apple Menu → About This Mac → System Report → USB)

## 🎯 Expected Result

Once connected properly, you should see:

```
📱 Connecting to Samsung phone...
✓ Connected successfully

=== Photo Summary ===
Total photos: 342
Date range: 2026-01-15 to 2026-04-03
```

Then you can filter by date and upload to OneDrive!

## 💡 Pro Tips

- Keep the phone **unlocked** while transferring
- **Don't use** the phone while transfer is happening
- Use **File Transfer mode**, not MTP or Charging Only
- Close other apps that access the phone
- If you see "permission denied", it may be a security setting on the phone

---

**For more help**, run: `./diagnose-phone.sh`
