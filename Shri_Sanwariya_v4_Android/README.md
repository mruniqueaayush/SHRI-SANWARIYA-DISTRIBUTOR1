# Shri Sanwariya Distributor — v4 (Online Sync, Fixed Backup, Illustrative UI & PDF Invoices)

## What's Upgraded in v4
1. **Fixed Cross-Device Export & Import (`BackupManager.kt`)**:
   - Writes canonical UTF-8 `backup.json` + `manifest.json` (SHA-256 checksum) + portable `images/<id>.jpg` inside the `.zip` archive using `ContentResolver.openOutputStream(uri, "wt")` with full flush/close.
   - Restores product images to internal storage (eliminating broken `content://` URIs across devices) and also imports `.json` backups from the companion Web App.
2. **Real-Time Multi-Device Sync (`CloudSyncRepository.kt`)**:
   - Firebase Firestore `addSnapshotListener` syncs Products, Companies, Categories, Schemes, and Orders in real time across all phones, with 100 MB offline cache persistence.
3. **Illustrative Material 3 UI (`MainActivity.kt`)**:
   - Gradient hero banner with live sync badge, brand filter chips, product photo cards with MRP strikethrough & scheme tags, visual Add Product dialog, and Cart Checkout.
4. **Downloadable Party Order PDF Invoices (`OrderPdfGenerator.kt`)**:
   - Native A4 `PdfDocument` invoice showing **Item Name, Pack, MRP, Rate, Qty, Scheme (Free Units / % Off), Discount, Net Amount, Savings, and Grand Total** — auto-saved to `Downloads/ShriSanwariya_Invoices/` and shareable via WhatsApp/Email.

## How to Build the Installable `.apk`
### Option A — Free Automatic Cloud Build via GitHub (No Android Studio needed)
1. Create a new GitHub repository and upload all files from this ZIP.
2. *(Optional for Live Cloud Sync)*: In [Firebase Console](https://console.firebase.google.com/), create a project → Add Android app (`com.shrisanwariya.distributor`) → Enable **Cloud Firestore** → Download `google-services.json` and place it inside `app/google-services.json`. Deploy `firestore.rules`.
3. Open the **Actions** tab on GitHub → click **Build Shri Sanwariya v4 APK** → download `Shri_Sanwariya_v4_APK` (`app-debug.apk`) and install it on your phones!

### Option B — Android Studio
1. Extract this ZIP and open the folder in Android Studio (Ladybug or newer).
2. *(Optional)* Place your `google-services.json` into `app/`.
3. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
