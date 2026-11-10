FLOATING SUBTITLE (Android) - cara bikin APK tanpa Android Studio
==================================================================

A. BUILD APK DI CLOUD (GitHub, gratis)
1. Extract file ZIP ini.
2. Buka github.com, daftar / login, lalu klik "New repository". Nama bebas (mis. floatsub), pilih Private, klik "Create repository".
3. Di halaman repo, klik link "uploading an existing file". Drag SEMUA isi folder hasil extract ke halaman itu
   (file settings.gradle.kts, build.gradle.kts, gradle.properties, folder app, dan folder .github). Klik "Commit changes".
   - Kalau folder .github tidak ikut ke-upload (karena namanya diawali titik):
     klik "Add file" > "Create new file", ketik nama  .github/workflows/build.yml
     (ketik garis miring supaya otomatis jadi folder), lalu tempel isi file build.yml yang ada di ZIP ini. Klik "Commit changes".
4. Buka tab "Actions". Tunggu workflow "Build APK" sampai ada centang hijau (sekitar 2-5 menit).
   Kalau jadi merah (X): klik workflow-nya, buka langkah "Build", copy pesan error-nya dan kirim ke Claude.
5. Klik workflow yang sukses, scroll ke bagian "Artifacts", download "FloatingSubtitle-APK". Extract, isinya app-debug.apk.

B. PASANG DI TABLET
1. Pindahin app-debug.apk ke tablet (USB, Google Drive, WhatsApp ke diri sendiri, dll).
2. Buka file-nya. Izinkan "pasang dari sumber ini" kalau diminta. Kalau ada pemindaian keamanan Huawei, pilih lanjutkan.

C. PENGATURAN PERTAMA KALI
1. Buka aplikasi, izinkan notifikasi.
2. Tekan "Izinkan tampil di atas aplikasi lain" lalu aktifkan untuk Floating Subtitle.
3. Biar nggak dimatikan sistem Huawei: Pengaturan > Baterai > Peluncuran aplikasi > Floating Subtitle > matikan "Kelola otomatis",
   lalu aktifkan semua: Peluncuran otomatis, Peluncuran sekunder, Berjalan di latar belakang.

D. PAKAI
1. "Pilih audio + subtitle": pilih keduanya sekaligus (tahan lama satu file, tap yang lain). Nama sama = otomatis berpasangan.
2. Tekan Putar, lalu pindah ke aplikasi lain. Subtitle melayang di atas semuanya; geser teksnya buat memindahkan.
3. Ukuran teks dan kepekatan latar bisa diatur. "Kunci overlay" bikin sentuhan tembus ke aplikasi di belakangnya
   (matiin dulu kalau mau menggeser).
