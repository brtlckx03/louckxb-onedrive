# OpenMTP Setup Guide

## ✅ You've Found the Solution!

You have **OpenMTP** installed, which is perfect! OpenMTP can see your phone, but it doesn't create a mounted volume at `/Volumes`. Instead, we'll export the photos and use them with our app.

## 🚀 Quick Setup (5 minutes)

### Step 1: Export Photos from OpenMTP

1. **Open OpenMTP app** (it's already running)
2. **Wait for your phone to appear** in the left sidebar
3. **Click on your phone** to connect
4. **Navigate to**: DCIM → Camera (or Pictures folder)
5. **Select all photos**: Press `Cmd+A`
6. **Right-click** → **Export** (or use menu: File → Export)
7. **Choose destination**:
   ```
   ~/Downloads/phone-photos/
   ```
   - Or any folder you prefer (Desktop, Documents, etc.)
8. **Click Export** and wait for the copy to complete
9. **Verify**: Check that photos appear in the exported folder

### Step 2: Run Our App

```bash
cd /Users/louckxb/Documents/wd-brtlckx/louckxb-onedrive
java -cp src ReadFilesOnPhone
```

### Step 3: Enter the Export Path

When the app asks for a path:
- **Choose Option 2**: Manual Mount Path
- **Enter path to your exported folder**:
  ```
  ~/Downloads/phone-photos/
  ```
  or
  ```
  /Users/louckxb/Downloads/phone-photos/
  ```

### Step 4: Select Photos by Date

- Enter date range (e.g., `1/03/2026 till 3/03/2026`)
- View all matching photos
- net.lckx.onedrive.Upload to OneDrive if you want

## 📝 Detailed Steps

### Export from OpenMTP - Detailed

**Option A: Export All Photos**
1. Click phone in OpenMTP
2. Navigate to: `DCIM/Camera`
3. Click `Select All` (Cmd+A)
4. Right-click → Export
5. Choose folder → Export

**Option B: Export Photos by Date**
1. Click phone in OpenMTP
2. Navigate to: `DCIM/Camera`
3. Look for date-based folders (like `2026-03-01`)
4. Open the date folder
5. Select all photos in that date
6. Right-click → Export
7. Name the folder with the date

**Option C: Export to Existing Folder**
- If you already have a folder with phone photos:
  - Use the full path to that folder
  - Example: `/Users/louckxb/Pictures/phone-photos`

### Using the App After Export

```bash
$ java -cp src ReadFilesOnPhone

📱 Connecting to Samsung phone...
❌ Error: Samsung phone not detected

Troubleshooting Options:
1. Run diagnostics to find the phone
2. Manually enter the phone mount path
3. View troubleshooting guide

Choose an option (1-3): 2
```

Then enter your export folder path!

## 🎯 Pro Tips

### Automation Tip
If you frequently export photos:

1. Export to: `~/Downloads/phone-photos-temp/`
2. Create a shell script to automate:
```bash
#!/bin/bash
# Quick export and run
export_folder="$HOME/Downloads/phone-photos"
java -cp /Users/louckxb/Documents/wd-brtlckx/louckxb-onedrive/src ReadFilesOnPhone
```

### Organize by Date
In OpenMTP, create separate exports for each date range:
- `~/Downloads/photos-march/`
- `~/Downloads/photos-april/`
- `~/Downloads/photos-2026-q1/`

Then run the app pointing to each folder separately.

### Keep Photos Organized
After uploading to OneDrive, you can:
1. Archive the exported folder
2. Delete the temp export folder
3. Keep originals on phone

## ✅ Workflow Summary

```
OpenMTP (see files)
    ↓
Export to folder
    ↓
Run our app
    ↓
Point to export folder
    ↓
Filter by date
    ↓
net.lckx.onedrive.Upload to OneDrive
```

## 🔍 Verify Export Worked

To check that export was successful:

```bash
# List exported photos
ls -lh ~/Downloads/phone-photos/ | head -20

# Count how many photos
ls -1 ~/Downloads/phone-photos/ | wc -l

# Check file types
file ~/Downloads/phone-photos/*
```

## ⚠️ Common Issues

### Export folder empty?
- Make sure you clicked Export (not just selecting)
- Wait for copy operation to complete
- Check you chose the right folder

### App still can't find folder?
- Use full path: `/Users/louckxb/Downloads/phone-photos/`
- Not just: `~/Downloads/phone-photos/`
- Check folder name has no spaces (or quote it)

### Missing photos?
- Export might only get some types
- Check DCIM folder has all photos
- Try exporting from Pictures folder too

## 🚀 Next Steps

1. **Open OpenMTP** (already running)
2. **Export photos** to `~/Downloads/phone-photos/`
3. **Run the app**: `java -cp src ReadFilesOnPhone`
4. **Choose Option 2**, enter your export path
5. **Filter by date** and upload! 🎉

---

**Ready?** Open OpenMTP and start exporting! 📱
