# Remote Codex

APK Android untuk mengobrol dengan Codex yang berjalan di komputer sendiri. HP menjadi remote; file, terminal, dan proses agent tetap di komputer.

## Yang tersedia

- Buat chat baru dengan memilih folder repo di komputer.
- Baca riwayat chat lokal, cari chat, dan muat pesan sebelumnya.
- Kirim arahan, baca balasan bertahap, dan hentikan turn yang aktif.
- Tinjau perintah/perubahan file sebelum menyetujui **sekali**, atau tolak.
- Jawab pertanyaan agent dari HP.
- Koneksi Wi-Fi lokal atau VPN privat seperti Tailscale; HTTPS untuk host publik.

Native Android (Java + platform UI), Node.js built-ins, tanpa framework UI, dependency npm, WebView, atau API key baru. Bridge menjalankan Codex CLI dengan login Codex yang sudah ada.

## Batas integrasi desktop

Bridge otomatis memilih:

| Mode | Perilaku |
| --- | --- |
| `shared` | Jika control socket Codex tersedia, gunakan `codex app-server proxy` ke proses yang sama. |
| `standalone` | Jalankan `codex app-server` dengan akun dan riwayat lokal yang sama. Chat baru milik bridge dapat dilanjutkan. Pesan pertama ke chat desktop membuat **fork/salinan** beserta konteksnya; tidak menjalankan turn kedua pada riwayat desktop yang sama. |

Instalasi desktop yang dipakai saat pengembangan belum menyediakan control socket. Karena itu **sinkronisasi langsung ke chat desktop yang sedang aktif belum tersedia**. Membuka riwayat desktop dari HP bersifat baca; mengirim pesan melanjutkan salinannya. Jangan menjalankan chat milik bridge di proses desktop lain secara bersamaan.

Protokol app-server masih eksperimental dan dapat berubah mengikuti versi Codex. Versi yang diverifikasi: `codex-cli 0.166.0-alpha.5.1`. [Dokumentasi resmi Codex App Server](https://learn.chatgpt.com/docs/app-server).

## Jalankan di komputer

Butuh Node.js 22+, Codex CLI yang sudah login (`codex login` bila diperlukan), dan komputer yang tetap menyala.

```sh
git clone https://github.com/kohryan/remote-codex.git
cd remote-codex
chmod +x start.sh
./start.sh
```

`start.sh` membuka port 8787 untuk koneksi LAN. Terminal menampilkan alamat IPv4 yang dapat dimasukkan ke HP. Biarkan terminal tersebut berjalan. Untuk loopback saja, gunakan `npm start`.

Token pairing dibuat otomatis di `.private/token`, dengan izin file `0600`. Buka file itu di komputer dan salin nilainya ke aplikasi Android. Token ini memberikan kendali atas Codex dalam workspace yang diizinkan; perlakukan sebagai kata sandi. Token tidak masuk Git, APK, atau log bridge.

Variabel opsional:

| Variabel | Default |
| --- | --- |
| `REMOTE_CODEX_ROOT` | Direktori induk repo `remote-codex`; hanya folder dalam root ini yang bisa dipilih/dibaca melalui bridge. |
| `REMOTE_CODEX_HOST` | `127.0.0.1` untuk `npm start`; `0.0.0.0` untuk `start.sh`. |
| `PORT` | `8787` |
| `CODEX_BIN` | `codex` pada PATH |
| `REMOTE_CODEX_SOCKET` | `$CODEX_HOME/app-server-control/app-server-control.sock` atau path setara dalam `~/.codex`. |

Contoh:

```sh
REMOTE_CODEX_ROOT="$HOME/Projects" ./start.sh
```

Bridge tidak mengubah konfigurasi global Codex. Chat baru/fork memakai sandbox `workspace-write`, reviewer pengguna, dan kebijakan `on-request`. Batas root pada API mengatur folder kerja/riwayat yang bisa dipilih, bukan sandbox OS tambahan; eksekusi agent tetap mengikuti sandbox Codex dan izin yang disetujui pengguna.

Untuk mencabut pairing, hentikan bridge, hapus `.private/token`, lalu jalankan bridge dan pair ulang. `.private/state.json` menyimpan ID chat yang dikelola bridge serta pasangan fork desktop; simpan file ini untuk melanjutkan chat remote setelah restart.

## Pasang di Android

1. Salin `remote-codex.apk` ke HP Android 8.0 atau lebih baru.
2. Buka APK dan izinkan instalasi dari sumber yang dipakai.
3. Jalankan **Remote Codex**.
4. Isi alamat komputer, misalnya `http://192.168.1.10:8787`, dan token pairing.
5. Tekan **Hubungkan**, lalu **Chat baru** untuk memilih repo.

Untuk akses di luar Wi-Fi, hubungkan komputer dan HP ke jaringan Tailscale yang sama lalu pakai IP Tailscale komputer. Alternatifnya gunakan reverse proxy HTTPS berautentikasi token ke bridge loopback. Jangan membuka port HTTP 8787 langsung ke internet. HTTP LAN mengirim token dan isi chat tanpa TLS; gunakan hanya jaringan yang dipercaya atau koneksi VPN terenkripsi.

Token di HP dienkripsi AES-GCM dengan kunci Android Keystore. Aplikasi menolak redirect dan alamat HTTP publik. Persetujuan hanya untuk satu tindakan; tidak ada tombol otomatis menyetujui semua.

## Build APK

Butuh JDK 17, Android SDK Platform 35, Build Tools 35.0.0, dan platform-tools. Contoh setelah Android command-line tools terpasang:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
npm run build:android
```

Hasil: `dist/remote-codex.apk` (debug APK bertanda tangan, untuk instalasi pribadi). Atur `ANDROID_HOME` bila SDK ada di lokasi lain. Untuk distribusi produksi, gunakan signing key rilis milik sendiri; jangan commit key. Wrapper Gradle dipin ke 8.11.1, Android Gradle Plugin 8.9.2.

## Verifikasi

```sh
npm test
cd android
./gradlew assembleDebug lintDebug
```

Test bridge memeriksa autentikasi, penolakan Origin, path/symlink di luar root, fork desktop, deduplikasi pesan, mode shared, stream, dan persetujuan. Uji nyata telah membuat chat lewat bridge dan menerima balasan `REMOTE_CODEX_OK` dari Codex lokal, lalu melanjutkan fork dengan konteks yang sama dan menerima `FORK_OK`.

## Batas versi awal

- Polling 1,2 detik saat layar chat terbuka; belum ada push notification atau foreground service Android. Agent di komputer tetap berjalan ketika HP ditutup.
- Pesan teks dan aktivitas dasar; lampiran, rich Markdown, review diff interaktif, elicitation MCP, dan izin sandbox khusus belum tersedia. Izin khusus ditolak, elicitation dibatalkan; gunakan desktop untuk alur tersebut.
- Tampilan approval menampilkan detail JSON termasuk perintah/diff bila tersedia.
- Tidak mengirim ulang pesan secara otomatis. Jika koneksi putus saat mengirim, periksa riwayat sebelum mencoba lagi. Deduplikasi berlaku selama proses bridge yang sama; setelah restart, periksa riwayat terlebih dahulu.
- Bila proses Codex terputus, mulai ulang bridge. Proses desktop tidak dihentikan oleh bridge.
- Mode `shared` diuji dengan mock protokol; belum diuji terhadap desktop dengan control socket aktif.
- Pengujian pada HP fisik masih perlu dilakukan setelah APK dipasang.
