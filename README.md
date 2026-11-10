# Nizx WaBot App (PoC)

Aplikasi Android yang menjalankan bot WhatsApp (Node.js + Baileys) di dalam APK.
Ini masih PoC: tujuannya membuktikan Baileys berjalan dan bertahan di dalam aplikasi.

Ada dua cara membangun APK:

| | GitHub Actions | PC lokal |
|---|---|---|
| Perlu PC | Tidak | Ya |
| Waktu | 5 sampai 10 menit per build | Pertama 15 sampai 30 menit, berikutnya 1 sampai 3 menit |
| Memasang | Unduh dari Releases | USB (`adb install`) atau tombol Run di Android Studio |
| Kunci debug | Dari cache Actions | Tetap di PC (`~/.android/debug.keystore`) |

APK dari Actions dan dari PC memakai kunci debug yang berbeda. Kalau berpindah di antara
keduanya, uninstall dulu aplikasinya.

## A. Build di GitHub Actions

1. Buka tab Actions, pilih "Build PoC APK", lalu Run workflow.
2. Setelah hijau, unduh `nizx-poc.apk` dari halaman Releases.

## B. Build di PC

### Yang perlu dipasang (sekali saja)

| Alat | Versi | Catatan |
|---|---|---|
| Git | terbaru | Wajib. npm memakai git untuk satu dependensi Baileys |
| Node.js | 18 atau lebih baru | Wajib. Termasuk npm |
| Android Studio | terbaru | Membawa JDK dan SDK Manager |
| JDK | 17 atau lebih baru | Cukup JDK bawaan Android Studio |
| Linux saja | `unzip` | `sudo apt install unzip` |

Di Android Studio buka SDK Manager (More Actions > SDK Manager), lalu pasang:

- SDK Platforms: Android 14 (API 34)
- SDK Tools: Android SDK Build-Tools 34, NDK (Side by side), CMake, Android SDK Platform-Tools

Di HP: aktifkan Opsi pengembang, lalu USB debugging. Sambungkan lewat kabel dan izinkan
debugging saat diminta. Merek tertentu (misalnya Xiaomi) juga meminta izin tambahan untuk
"Install via USB".

Windows: letakkan folder proyek di jalur pendek (misalnya `C:\dev\Nizx_WaBot_App`) karena
node_modules punya jalur yang sangat panjang.

### Langkah

    git clone https://github.com/nizxreal/Nizx_WaBot_App.git
    cd Nizx_WaBot_App
    node scripts/prepare.js

`prepare.js` melakukan empat hal:

1. Mengunduh `libnode.so` (Node.js untuk Android) dari rilis nodejs-mobile.
2. Memasang Baileys ke `build/njs` (npm install) dan menyalin `poc/nodejs-project/main.js`.
3. Membungkus semuanya menjadi `app/src/main/assets/nodejs-project.zip`.
4. Menyiapkan Gradle wrapper dan `local.properties` (bila ANDROID_HOME diatur).

Pilihan: `--force` (ulang dari awal), `--nodejs-mobile=v18.17.2`, `--baileys=6.7`.

Lalu pilih salah satu.

**Opsi 1: terminal**

    ./gradlew assembleDebug            (Windows: gradlew.bat assembleDebug)
    adb devices                        (HP harus terdaftar dan berstatus "device")
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Jalur terminal butuh variabel ANDROID_HOME (folder SDK, misalnya
`C:\Users\NAMA\AppData\Local\Android\Sdk` di Windows atau `~/Android/Sdk` di Linux) atau
`local.properties` berisi `sdk.dir=...`. Jalankan `node scripts/prepare.js` lagi setelah
mengatur ANDROID_HOME agar `local.properties` dibuat otomatis. Kalau JAVA_HOME belum ada,
arahkan ke JDK Android Studio (Windows: `C:\Program Files\Android\Android Studio\jbr`).

**Opsi 2: Android Studio**

1. Jalankan `node scripts/prepare.js` LEBIH DULU, baru buka proyek. Tanpa itu sinkronisasi
   gagal karena libnode.so belum ada.
2. File > Open, pilih folder proyek, lalu tunggu Gradle sync.
3. Kalau diminta memperbarui Android Gradle Plugin, pilih "Don't remind me" atau abaikan
   (proyek ini memakai versi yang sudah teruji).
4. Kalau diminta memasang NDK atau CMake yang kurang, setujui.
5. Pilih HP di bilah atas, lalu tekan Run.

### Commit wrapper (disarankan, sekali saja)

Setelah `prepare.js` pertama berhasil, simpan wrapper supaya orang lain tidak perlu mengunduhnya:

    git add gradlew gradlew.bat gradle
    git update-index --chmod=+x gradlew
    git commit -m "Gradle wrapper"
    git push

## Memasang APK di HP

- **Lewat USB (disarankan):** `adb install -r ...apk`. Ini jalur yang diizinkan untuk APK dari
  developer yang belum terdaftar.
- **Langsung di HP:** Android 10 ke atas memaksa konfirmasi, dan sejak 30 September 2026 di
  Indonesia, pemasangan APK dari developer yang belum terverifikasi lewat file memerlukan
  "advanced flow" dari Google. Ikuti petunjuk di layar.

## Memakai aplikasi

1. Buka aplikasi, tekan Start Node (izinkan notifikasi). Pertama kali butuh 30 sampai 90
   detik untuk mengekstrak.
2. Ketik `net` di kolom perintah untuk menguji jaringan ke WhatsApp.
3. Isi nomor (format 628xxx, pakai nomor cadangan), tekan Minta kode pairing, lalu masukkan
   kode di WhatsApp (Perangkat tertaut > Tautkan dengan nomor telepon).
4. Kirim `.ping` dari nomor lain. Harus dibalas `pong`.
5. Tekan Baterai dan pilih "Tanpa batasan" agar bot tidak dimatikan sistem.

## Alur kerja saat mengubah kode

| Yang diubah | Yang dijalankan |
|---|---|
| `poc/nodejs-project/main.js` | `node scripts/prepare.js`, lalu Run atau `gradlew installDebug` |
| `main.js` tanpa membangun APK | `git push`, lalu ketik `selfupdate` di aplikasi, lalu Stop dan Start Node |
| Kode Kotlin atau C++ | Run atau `gradlew installDebug` |

`selfupdate` khusus PoC. Perintah itu mengunduh `main.js` dari repo ini lewat internet,
jadi harus dihapus sebelum aplikasi dirilis ke pengguna.

## Debug

- Keluaran Node (logcat): `adb logcat -s NIZX-NODE`
- Log galat dari sisi aplikasi (bila Node gagal start): kotak kecil di bawah status
  aplikasi, atau file `files/native.log`
- Cek isi zip: `tar -tf app/src/main/assets/nodejs-project.zip` (Windows dan macOS), atau
  `unzip -l ...` (Linux). Jalur harus memakai `/`, bukan `\`.

## Struktur proyek

    app/                          proyek Android (Kotlin dan C++)
      src/main/cpp/               jembatan C++ ke libnode
      src/main/java/.../          MainActivity, NodeService, Support
    poc/nodejs-project/main.js    kode Node (bot, server lokal, perintah)
    scripts/prepare.js            persiapan build untuk PC
    .github/workflows/            build otomatis di GitHub Actions

Hasil persiapan (tidak ikut git): `app/libnode/`, `app/src/main/assets/`, `build/`.

## Masalah umum

| Gejala | Penyebab dan solusi |
|---|---|
| `libnode.so` tidak ditemukan saat sinkronisasi atau CMake | `node scripts/prepare.js` belum dijalankan |
| `SDK location not found` | Buat `local.properties` (`sdk.dir=...`) atau set ANDROID_HOME |
| NDK atau CMake belum terpasang | SDK Manager > SDK Tools > NDK (Side by side) dan CMake |
| Lisensi SDK belum diterima | `sdkmanager --licenses` (di `cmdline-tools` SDK) |
| `npm install` gagal di bagian git | Pasang Git dan pastikan ada di PATH |
| Unduhan libnode gagal (HTTP 403) | Batas API GitHub. Set `GITHUB_TOKEN` atau tunggu satu jam |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Kunci tanda tangan berbeda. Uninstall aplikasinya lalu pasang ulang |
| `adb: no devices` | Cek kabel, USB debugging, dan izin di HP. Jalankan `adb kill-server` lalu `adb devices` |
| Emulator tidak jalan | APK hanya arm64-v8a. Pakai HP fisik atau emulator ARM64 |
| Build lambat atau gagal "path too long" (Windows) | Pindahkan proyek ke jalur pendek, misalnya `C:\dev` |
| Wrapper tidak bisa diunduh | Pasang Gradle 8.9 lalu jalankan `gradle wrapper --gradle-version 8.9` |

## Keamanan

- Kunci debug hanya untuk uji. APK rilis nanti memakai keystore sendiri yang disimpan aman
  (jangan masuk repo).
- Server lokal Node hanya bind ke 127.0.0.1 dan memakai kunci rahasia per instalasi.
