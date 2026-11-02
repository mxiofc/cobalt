# Cobalt — Android APK

`Cobalt.apk` adalah aplikasi Android dari **web app Cobalt yang sudah ada** di repository ini.
Web app-nya tidak diubah: build produksi di `web/build` hanya dibungkus oleh shell Android
kecil di folder `App/`, lalu ditambahkan splash screen hitam, animasi, dan jembatan download
ke folder Downloads.

## Hasil build

| Item | Nilai |
|---|---|
| File APK | `App/Cobalt.apk` |
| Nama aplikasi | **Cobalt** |
| Package | `com.cobalt.app` |
| Versi | `11.7` (versionCode `1`) |
| Min SDK | Android 7.0 (API 24) |
| Target SDK | Android 14 (API 34) |
| Ukuran | ± 12 MB |
| Tanda tangan | APK Signature Scheme v2 + v3 (verified) |

## Fitur

- **Background hitam** — tema gelap dipaksa lewat `cobalt-bridge.js`, status bar dan
  navigation bar hitam, splash screen hitam dengan logo Cobalt.
- **Splash animasi** — logo fade + overshoot scale, teks fade + translate, dan ring
  gradient biru/ungu/cyan yang berputar. Splash memudar 500 ms setelah halaman selesai dimuat.
- **Web app Cobalt lengkap** — downloader, antrian, settings, about, changelog, donate,
  halaman updates, PWA manifest, ikon, font, dan update banners.
- **Native download bridge** — file blob dari web app disimpan ke folder **Downloads**
  lewat `MediaStore` (Android 10+) atau file langsung (Android 7–9). Link langsung
  dibuka di browser (download manager sistem), dan `DownloadListener` memakai
  `DownloadManager` dengan fallback ke browser.
- **Asset loader** — web app disajikan dari `assets/www` lewat origin HTTPS
  `https://appassets.androidplatform.net`, jadi absolute path, clipboard, dan fetch API
  tetap bekerja tanpa server.
- **File chooser, link eksternal, back navigation, dan izin storage** sudah ditangani.
- **API resmi** — `https://api.cobalt.tools` dipakai sebagai default. Bisa diganti ke
  instance lain lewat halaman Settings → Processing → Custom instance.

## Catatan

- **Local processing (`_libav`) tidak disertakan** (± 77 MB) supaya APK tetap ringan.
  Di Android, fitur ini memang disabled secara default. Tab **Remux** disembunyikan.
  Semua proses download memakai API.
- **Update banners tetap disertakan** supaya notifikasi update tidak rusak.
- Aplikasi ini hanya untuk konten publik yang memang boleh diunduh. Pengguna bertanggung
  jawab atas isi yang diunduh. Cobalt tidak menyimpan konten apa pun.
- Web app Cobalt berlisensi **CC-BY-NC-SA-4.0** dan branding Cobalt adalah hak cipta
  pemiliknya. Aplikasi ini non-komersial dan tetap mencantumkan atribusi ke Cobalt.

## Struktur folder

```
App/
├── Cobalt.apk                  # hasil build final (sudah ditandatangani)
├── build.sh                    # script build lengkap
├── README.md
├── src/
│   ├── AndroidManifest.xml
│   ├── java/com/cobalt/app/MainActivity.java
│   └── res/
│       ├── anim/               # splash_logo_in, splash_text_in
│       ├── drawable/           # ic_launcher, splash_logo, splash_background, splash_ring
│       ├── layout/activity_main.xml
│       └── values/             # strings, styles
└── tools/
    ├── cobalt-bridge.js        # bridge download + dark mode
    └── inject_bridge.py        # inject bridge ke HTML hasil build
```

## Cara build ulang

Build web app (dari root repository):

```bash
WEB_DEFAULT_API=https://api.cobalt.tools WEB_HOST=cobalt.tools \
  pnpm --filter @imput/cobalt-web build
```

Build APK:

```bash
./App/build.sh
```

`build.sh` akan:

1. menyalin `web/build` ke `App/build/assets/www`,
2. menghapus `_libav` dan menyuntikkan `cobalt-bridge.js` ke 22 file HTML,
3. meng-compile resource dengan `aapt2`,
4. meng-compile `MainActivity.java` dengan `ecj`,
5. mengubah bytecode ke DEX dengan `d8` (`--no-tree-shaking`),
6. mem-package APK, membuat keystore, menandatangani dengan `apksigner`, dan memverifikasi.

Tool yang dipakai (sudah tersedia di workspace ini):

- `aapt2` (Linux x86_64)
- `android.jar` API 34
- `d8.jar`, `apksigner.jar`, `ecj-3.45.0.jar`
- JDK 25 (`java`, `keytool`)

Path tool bisa di-override lewat environment variable `AAPT2`, `JAVA`, `KEYTOOL`,
`ANDROID_JAR`, `D8_JAR`, `APKSIGNER_JAR`, `ECJ_JAR`, dan `TOOLS`.

## Install

1. Buka `App/Cobalt.apk` di perangkat Android.
2. Izinkan **Install unknown apps** untuk aplikasi file manager/browser yang dipakai.
3. Install, buka **Cobalt**, lalu tempel link dan tekan download.
4. File hasil download masuk ke folder **Downloads** (bisa dilihat lewat notifikasi
   atau aplikasi Files).

## Verifikasi

```bash
aapt2 dump badging App/Cobalt.apk
apksigner verify --verbose App/Cobalt.apk
unzip -t App/Cobalt.apk
```

Hasil verifikasi build ini:

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
```
