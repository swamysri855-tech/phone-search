# 🔍 Phone Search

ఫోన్‌లో ఉన్న ప్రతిదాన్నీ ఒకే చోట వెతికే Android app:

- 👤 **Contacts**: పేరు లేదా నంబర్‌తో వెతకవచ్చు
- 💬 **Messages (SMS)**: మెసేజ్‌లోని పదాలు, నంబర్, లేదా పంపినవారి పేరుతో వెతకవచ్చు
- 🖼️ **Photos (JPG, PNG…)**, 🎬 **Videos**, 🎵 **Audio**
- 📄 **Documents**: PDF, Word, Excel, TXT…
- 📦 **APK** files, 📁 **అన్ని ఇతర files**
- 🟢 **WhatsApp / WhatsApp Business / Telegram folders**: Gallery లో కనబడని Sent files, Voice notes, Statuses కూడా (All files access ఆన్ చేసి ఉంటే)

## ఎలా వెతకవచ్చు

- **పేరు ప్రకారం**: ఫైల్ పేరు, folder పేరు (ఉదా: `WhatsApp`, `Screenshots`), లేదా రకం (`jpg`, `pdf`) టైప్ చేయండి
- **ఎక్కువ పదాలు**: `whatsapp jpg` అని టైప్ చేస్తే రెండు పదాలూ ఉన్నవి మాత్రమే వస్తాయి
- **తేదీ ప్రకారం**: ఈ రోజు, నిన్న, గత 7/30 రోజులు, ఈ సంవత్సరం, లేదా మీరు ఎంచుకున్న తేదీల మధ్య
- **రకం ప్రకారం**: పైన ఉన్న chips (Contacts, Messages, Photos…)
- **వరుస**: కొత్తవి ముందు, పాతవి ముందు, పేరు A→Z, పెద్ద సైజు ముందు
- ఫలితంపై **tap** చేస్తే తెరుచుకుంటుంది

## ఎంచుకుని తరలించడం (Multi-select)

- ఏదైనా ఫలితంపై **long-press** చేస్తే selection మొదలవుతుంది. ఆ తర్వాత tap చేస్తూ ఇంకా ఎంచుకోవచ్చు
- క్రింద వచ్చే bar లో:
  - **📂 తరలించు**: ఎంచుకున్న files ని వేరే folder కి move చేస్తుంది (కొత్త folder కూడా తయారు చేయవచ్చు)
  - **📑 Folder కి కాపీ**: అసలు file అక్కడే ఉంచి, కాపీ మాత్రమే వేరే folder లో పెడుతుంది
  - **📤 పంపు**: WhatsApp, Bluetooth, Gmail మొదలైన వాటి ద్వారా పంపవచ్చు
  - **📋 Text కాపీ**: contacts / messages ని text గా కాపీ చేస్తుంది
  - **☑ అన్నీ ఎంచుకో** / **✕** (లేదా Back బటన్) తో selection రద్దు
- File తేదీ మారదు, కాబట్టి తరలించిన తర్వాత కూడా తేదీ ప్రకారం వెతకవచ్చు
- తరలించడానికి **All files access** ఆన్ చేసి ఉండాలి

## 🤖 AI తో ఫోటోలు సర్దడం (Offline)

1. 🖼️ **Photos** chip నొక్కండి (కావాలంటే `whatsapp` లాంటి పదం లేదా 📅 తేదీ కూడా ఎంచుకోండి)
2. ఒక ఫోటోపై long-press చేసి **☑ అన్నీ ఎంచుకో** నొక్కండి
3. **🤖 AI తో సర్దు** నొక్కండి. ఫోన్ లోపలే ఉన్న AI ప్రతి ఫోటోను చూసి అందులో ఏముందో గుర్తిస్తుంది
4. ఏ రకానికి ఎన్ని ఫోటోలు వచ్చాయో చూపిస్తుంది. **కాపీ చేయి** నొక్కితే కొత్త పేర్లతో
   `Pictures/AI_Sorted/` లోని folders లో కాపీలు పెడుతుంది: `Plants_Herbs`, `Food`, `People`, `Animals`,
   `Documents`, `Vehicles`, `Nature`, `Buildings_Places`, `Events`, `Screenshots`, `Others`
   - ఉదాహరణ: `IMG_1234.jpg` → `Plants_Herbs/Leaf_Flower_20261120_054427.jpg`
- అసలు ఫోటోలు మారవు. ఇంటర్నెట్ అవసరం లేదు, ఫోటోలు ఫోన్ బయటికి వెళ్ళవు
- ఇది సాధారణ రకాలు (మొక్క, ఆకు, పువ్వు…) మాత్రమే గుర్తిస్తుంది. తులసి, వేప లాంటి మొక్కల పేర్లు చెప్పలేదు

## Install చేయడం

1. ఈ repo లోని **Releases → latest** కి వెళ్లి `PhoneSearch.apk` download చేయండి
2. ఫోన్‌లో ఆ file తెరిచి **Install** నొక్కండి ("Unknown sources" అనుమతి అడిగితే Allow చేయండి)
3. App తెరిచాక Contacts, SMS, Photos అనుమతులు **Allow** చేయండి
4. PDF, APK లాంటి అన్ని files కనబడాలంటే, పసుపు banner లోని బటన్ నొక్కి **All files access** ఆన్ చేయండి

## Build

ప్రతి push కి GitHub Actions (`.github/workflows/build.yml`) APK ని build చేసి `latest` release లో పెడుతుంది.
Local గా build చేయాలంటే: `./gradlew assembleDebug`
