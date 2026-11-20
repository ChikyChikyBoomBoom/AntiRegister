# AntiRegister | آنتی ریجستر

**English** · [فارسی](#فارسی)

> ⚠️ **Beta.** SMS, Bluetooth link, Contacts and read-only Call History are available. **The calling part is still under development and is switched off in this version.**

---

## English

### What is AntiRegister?

In many countries a phone that is not *registered* (IMEI registration) can't use a local SIM card. Many people end up carrying **two phones**: a registered one that holds the SIM, and the phone they actually want to use every day.

**AntiRegister connects those two phones over Bluetooth**, so you can use your daily phone for the things your SIM phone receives, without constantly picking up the second one.

- **SIM Phone** – the registered phone with your SIM card. It stays in your bag or at home.
- **Main Phone** – the phone you actually use. It shows and sends your messages through the SIM Phone.

### What works in this beta

- **SMS** – receive SMS that arrive on the SIM Phone and send SMS from the Main Phone through it.
- **Bluetooth link** – pair the two phones once; the app reconnects automatically.
- **Contacts** – optional. Shows names and photos instead of bare numbers. Read-only.
- **Call history** – shows the SIM Phone's recent calls. **Read-only**: the app can't place, answer or change calls.
- **Persian and English** interface, including right-to-left layout.

### Coming soon

**Calls (placing, answering and talking from the Main Phone) are still in development** and are disabled in this version. The app does not request the call or microphone permissions. Follow the repository for updates.

### Is it safe?

We built it to ask for as little as possible, and you don't have to take our word for it:

- **No internet permission.** The app doesn't declare `INTERNET`, so it has no way to send your data to a server. Your phones talk to each other over Bluetooth only.
- **No accounts, no ads, no analytics, no cloud.** Messages and history stay on your own phones.
- **Open source.** All the code is in this repository. Read it, or build the APK yourself with GitHub Actions.
- **Only the permissions it needs:** SMS (send/receive) and call-history *read* on the SIM Phone, optional contacts *read* on the Main Phone, and Bluetooth. No microphone, no call control, no write access to your contacts or call log.
- **Backups are off** (`allowBackup=false`).

**About the Play Protect warning:** because the app is installed outside Google Play and asks for SMS and call-history access, Google Play Protect may show *"Harmful app blocked"*. This is a general warning for unfamiliar apps with those permissions, not a verdict on this one. You can choose **Install anyway**. If you'd like to be sure first, review the code or build it yourself.

### Why you'll like it

- Stop switching between two phones all day.
- Read and answer your SMS (login codes, bank messages, delivery updates) from the phone you actually hold.
- Small, simple, no sign-up, no data leaving your devices.

### Install

1. Download **`AntiRegister.apk`** from the latest release (or from the GitHub Actions build artifact).
2. Allow installing from your file manager/browser if Android asks, then open the file. Choose **Install anyway** if Play Protect warns you.
3. Install it on **both** phones. Open the app and choose the role: **SIM Phone** on the registered phone, **Main Phone** on the one you use daily.
4. Follow the in-app setup: grant the permissions it asks for, pair the two phones over Bluetooth, and you're done.

Requires **Android 8.0 (API 26) or newer**.

### Feedback

This is a beta. If something doesn't work on your phone model, please open an issue and tell us your two phone models and Android versions.

---

<div dir="rtl">

## فارسی

<a id="فارسی"></a>

### آنتی ریجستر چیست؟

در خیلی از جاها گوشی‌ای که **ریجستر (ثبت IMEI)** نشده باشد نمی‌تواند با سیم‌کارت داخلی کار کند. نتیجه‌اش این است که خیلی‌ها مجبورند **دو گوشی** همراهشان داشته باشند: یک گوشی ریجستر‌شده که سیم‌کارت داخلش است، و گوشی‌ای که واقعاً دوست دارند هر روز با آن کار کنند.

**آنتی ریجستر این دو گوشی را با بلوتوث به هم وصل می‌کند** تا بتوانی با گوشی اصلی‌ات از چیزهایی که به گوشی سیم‌کارتی می‌رسد استفاده کنی، بدون اینکه مدام گوشی دوم را دست بگیری.

- **گوشی سیم‌کارت (SIM Phone):** گوشی ریجستر‌شده‌ای که سیم‌کارت داخل آن است. می‌تواند توی کیف یا خانه بماند.
- **گوشی اصلی (Main Phone):** گوشی‌ای که هر روز با آن کار می‌کنی. پیامک‌هایت را از طریق گوشی سیم‌کارت نشان می‌دهد و می‌فرستد.

### در این نسخه بتا چه کارهایی می‌شود کرد؟

- **پیامک:** پیامک‌هایی که به گوشی سیم‌کارت می‌رسند را ببین و از گوشی اصلی، از طریق آن پیامک بفرست.
- **اتصال بلوتوث:** یک بار دو گوشی را جفت کن؛ برنامه خودش دوباره وصل می‌شود.
- **مخاطبین:** اختیاری. به‌جای شماره، نام و عکس مخاطب را نشان می‌دهد. فقط خواندنی.
- **تاریخچه تماس:** تماس‌های اخیر گوشی سیم‌کارت را نشان می‌دهد. **فقط خواندنی:** برنامه نمی‌تواند تماس بگیرد، جواب بدهد یا تماسی را تغییر بدهد.
- **رابط فارسی و انگلیسی** با چیدمان راست‌به‌چپ.

### به‌زودی

**بخش تماس (گرفتن، جواب دادن و صحبت کردن از گوشی اصلی) هنوز در حال توسعه است** و در این نسخه غیرفعال است. برنامه دسترسی تماس و میکروفون را درخواست نمی‌کند. برای خبر آپدیت‌ها این مخزن را دنبال کن.

### آیا برنامه امن است؟

برنامه را طوری ساخته‌ایم که کمترین دسترسی ممکن را بخواهد، و لازم نیست حرف ما را بپذیری:

- **دسترسی اینترنت ندارد.** برنامه مجوز `INTERNET` را اعلام نکرده، پس راهی برای فرستادن اطلاعاتت به سرور ندارد. دو گوشی فقط از طریق بلوتوث با هم حرف می‌زنند.
- **بدون حساب کاربری، تبلیغ، آمارگیری و فضای ابری.** پیام‌ها و تاریخچه روی گوشی‌های خودت می‌مانند.
- **متن‌باز است.** تمام کد در همین مخزن است. می‌توانی بخوانی‌اش یا خودت با GitHub Actions فایل APK را بسازی.
- **فقط دسترسی‌های لازم:** پیامک (ارسال/دریافت) و *خواندن* تاریخچه تماس در گوشی سیم‌کارت، *خواندن* اختیاری مخاطبین در گوشی اصلی، و بلوتوث. بدون میکروفون، بدون کنترل تماس، و بدون دسترسی نوشتن روی مخاطبین یا تاریخچه تماس.
- **پشتیبان‌گیری خاموش است** (`allowBackup=false`).

**درباره هشدار Play Protect:** چون برنامه خارج از گوگل پلی نصب می‌شود و دسترسی پیامک و تاریخچه تماس می‌خواهد، ممکن است Google Play Protect پیام *«Harmful app blocked»* نشان دهد. این یک هشدار عمومی برای برنامه‌های ناشناس با این دسترسی‌هاست، نه قضاوت درباره همین برنامه. می‌توانی **Install anyway** را بزنی. اگر می‌خواهی قبلش مطمئن شوی، کد را بررسی کن یا خودت برنامه را بساز.

### چرا نصبش کنی؟

- دیگر لازم نیست تمام روز بین دو گوشی جابه‌جا شوی.
- پیامک‌هایت (کد ورود، پیام بانک، اطلاعات مرسوله) را از همان گوشی‌ای که دستت است بخوان و جواب بده.
- کوچک و ساده، بدون ثبت‌نام، و بدون اینکه اطلاعاتی از گوشی‌هایت بیرون برود.

### نصب

1. فایل **`AntiRegister.apk`** را از آخرین نسخه (Release) یا از خروجی GitHub Actions دانلود کن.
2. اگر اندروید خواست، اجازه نصب از فایل‌منیجر یا مرورگر را بده و فایل را باز کن. اگر Play Protect هشدار داد، **Install anyway** را بزن.
3. برنامه را روی **هر دو** گوشی نصب کن. نقش را انتخاب کن: **SIM Phone** روی گوشی ریجستر‌شده و **Main Phone** روی گوشی‌ای که هر روز استفاده می‌کنی.
4. مراحل راه‌اندازی داخل برنامه را دنبال کن: دسترسی‌ها را بده، دو گوشی را با بلوتوث جفت کن و تمام.

حداقل نسخه لازم: **اندروید ۸.۰ (API 26) یا بالاتر**.

### بازخورد

این نسخه بتاست. اگر روی مدل گوشی‌ات مشکلی بود، لطفاً یک Issue باز کن و مدل دو گوشی و نسخه اندرویدشان را بنویس.

</div>
