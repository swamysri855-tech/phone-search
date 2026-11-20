# 🔍 Phone Search

ఫోన్‌లో ఉన్న ప్రతిదాన్నీ ఒకే చోట వెతికే Android app:

- 👤 **Contacts**: పేరు లేదా నంబర్‌తో వెతకవచ్చు
- 💬 **Messages (SMS)**: మెసేజ్‌లోని పదాలు, నంబర్, లేదా పంపినవారి పేరుతో వెతకవచ్చు
- 🖼️ **Photos (JPG, PNG…)**, 🎬 **Videos**, 🎵 **Audio**
- 📄 **Documents**: PDF, Word, Excel, TXT…
- 📦 **APK** files, 📁 **అన్ని ఇతర files**

## ఎలా వెతకవచ్చు

- **పేరు ప్రకారం**: ఫైల్ పేరు, folder పేరు (ఉదా: `WhatsApp`, `Screenshots`), లేదా రకం (`jpg`, `pdf`) టైప్ చేయండి
- **ఎక్కువ పదాలు**: `whatsapp jpg` అని టైప్ చేస్తే రెండు పదాలూ ఉన్నవి మాత్రమే వస్తాయి
- **తేదీ ప్రకారం**: ఈ రోజు, నిన్న, గత 7/30 రోజులు, ఈ సంవత్సరం, లేదా మీరు ఎంచుకున్న తేదీల మధ్య
- **రకం ప్రకారం**: పైన ఉన్న chips (Contacts, Messages, Photos…)
- **వరుస**: కొత్తవి ముందు, పాతవి ముందు, పేరు A→Z, పెద్ద సైజు ముందు
- ఫలితంపై **tap** చేస్తే తెరుచుకుంటుంది, **long-press** చేస్తే నంబర్/మెసేజ్/path కాపీ అవుతుంది

## Install చేయడం

1. ఈ repo లోని **Releases → latest** కి వెళ్లి `PhoneSearch.apk` download చేయండి
2. ఫోన్‌లో ఆ file తెరిచి **Install** నొక్కండి ("Unknown sources" అనుమతి అడిగితే Allow చేయండి)
3. App తెరిచాక Contacts, SMS, Photos అనుమతులు **Allow** చేయండి
4. PDF, APK లాంటి అన్ని files కనబడాలంటే, పసుపు banner లోని బటన్ నొక్కి **All files access** ఆన్ చేయండి

## Build

ప్రతి push కి GitHub Actions (`.github/workflows/build.yml`) APK ని build చేసి `latest` release లో పెడుతుంది.
Local గా build చేయాలంటే: `./gradlew assembleDebug`
