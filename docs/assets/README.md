# Identitas Remote Codex

| Aset | Kegunaan |
| --- | --- |
| [logo.svg](logo.svg) | Logo vektor untuk README. Simbol, warna, dan stroke diambil dari `android/app/src/main/res/drawable/ic_launcher.xml`. |
| [social-preview.png](social-preview.png) | Kartu berbagi 1280 × 640 piksel untuk GitHub Social Preview. |
| [social-preview.svg](social-preview.svg) | Sumber vektor kartu berbagi, agar teks dan tata letaknya mudah diperbarui. |

Untuk memasang preview tautan, buka repo GitHub → **Settings → General → Social preview → Edit → Upload an image**, lalu pilih `social-preview.png`. Menyimpan gambar di repo saja belum mengganti preview tautan GitHub.

Palet: latar `#0C1118`, aksen mint `#6EE7C7`, teks `#EEF4F8`.

PNG dapat dibuat ulang dari sumber SVG di macOS:

```sh
sips -s format png docs/assets/social-preview.svg --out docs/assets/social-preview.png
```
