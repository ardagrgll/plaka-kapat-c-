# otodeğerle Plaka Kapatıcı (Android)

Çalışanlar için deneme uygulaması: araç fotoğraflarındaki plakayı bulur ve otodeğerle plakalığıyla otomatik kapatır.

## Ne yapar
- **Çalışan girişi:** İlk açılışta ad soyad sorulur; eklenen araçlar bu isimle kaydedilir (sağ üstten değiştirilebilir).
- **Araç bölümleri:** Her araç ayrı kart. Liste gün gün (Bugün / Dün / tarih) ayrılır; "Benim eklediklerim" filtresi var.
- **Otomatik plaka kapatma:** Fotoğraflar eklenince Türk plakası (örn. 34 ABC 123) internetsiz bulunur, plakalık perspektife uygun oturtulur.
- **Elle düzeltme:** Fotoğrafa dokun → köşeleri sürükle (büyüteç çıkar), ortadan tutup taşı, "Plaka Ekle / Kaldır", "Otomatik Bul".
- **Çıktı:** "Galeriye Kaydet" (Resimler › otodegerle) ve "Paylaş" (WhatsApp vb.).
- Galeriden/WhatsApp'tan **Paylaş → otodeğerle Plaka** ile fotoğraf gönderince yeni araç açılır.
- Plakalık: varsayılan otodeğerle plakalığı gömülü; ana ekrandaki **Değiştir** ile başka görsel seçilebilir.

Tüm veriler şimdilik telefonda tutulur (sunucu yok).

## APK nasıl alınır (bilgisayar/Android Studio gerekmez)
1. github.com'da hesap aç / giriş yap → **New repository** → ad: `otodegerle-plaka` (Private olabilir) → Create.
2. Bu klasördeki dosyaları repoya yükle (**Add file → Upload files**, zip'i açıp içindeki her şeyi sürükle).
   - `.github/workflows/build-apk.yml` dosyasının da yüklendiğinden emin ol (gizli klasör; görünmüyorsa **Add file → Create new file** ile bu yolu yazıp içeriğini yapıştır).
3. Repo'da **Actions** sekmesi → "APK Derle" çalışır (~5 dk).
4. Bitince repo ana sayfasında sağda **Releases** → `otodegerle-plaka.apk` → telefondan indir → kur ("Bilinmeyen kaynaklara izin ver" onayı istenir).

Her yeni yüklemede otomatik yeni APK çıkar.

## Teknik
Kotlin, minSdk 26 (Android 8+), Google ML Kit Text Recognition (cihaz içi), harici sunucu yok.
