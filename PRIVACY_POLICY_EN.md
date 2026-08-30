# Folio Privacy Policy

**Last updated:** August 30, 2026  
**Document version:** 1.0

This Privacy Policy explains how **Folio** accesses, uses, stores, and shares data while operating as a web page and PDF reader with local narration. It applies to the Folio Android app and complements the [Terms of Use](TERMS_OF_USE_EN.md).

[Versão em português](PRIVACY_POLICY.md)

## 1. Identification and contact

The app is identified as **Folio** and is provided by the owner indicated in its Google Play listing. For questions, requests, or comments about privacy, use the project's support page:

<https://github.com/natanaelassis24/app/issues>

This channel is public: do not send documents, private URLs, credentials, or other personal data through it. Before publication on Google Play, the developer must also configure the contact channel displayed in the store listing.

## 2. Data Folio accesses and stores on the device

Folio does not create a user account and has no proprietary server that receives the content you read. To provide its features, it may process the following data locally:

| Data | Purpose | Storage location |
| --- | --- | --- |
| Theme, language, speech speed, and voice state | Preserve your reading preferences | Private app preferences |
| Up to four most recently accessed websites | Display shortcuts on the home screen | Private app preferences |
| Reading URL, position, and technical segment key | Resume reading where you left off | Private app preferences |
| Selected PDF and file name | Extract and display text from the chosen PDF | Processed locally; permission to read the URI may remain on the device |
| Temporary narration segments | Allow background playback | Private app cache, normally removed when narration stops or finishes |
| Local voice package | Generate speech on the device | Private app storage, after an optional download |
| Cache, first-party cookies, and web storage | Display websites you choose in the built-in browser | WebView storage on the device |

Folio does not request access to the camera, microphone, location, contacts, SMS, broad storage, or advertising identifier.

## 3. PDFs and reading content

You choose the PDF using Android's document picker. Folio reads the text layer locally and does not send the PDF or extracted text to a Folio server. To allow the selected file to be read, Android may grant permission to read the document URI; this permission may persist for as long as the app's data exists.

Recent URLs and the reading position are also stored on the device so that reading can be resumed. The resume URL may contain parameters included in the address you opened; therefore, do not use this feature with links containing confidential information.

## 4. Browsing third-party websites

When you open a website or perform a search through the app, your device connects directly to the selected destination. That website, internet service provider, and any search engines may receive normal technical browsing data, such as IP address, headers, and the query you type, under their own policies.

Folio only allows HTTPS pages, blocks mixed content, disables geolocation, blocks third-party cookies, and uses Android Safe Browsing when available. Even so, the website's own first-party cookies, cache, and local storage may be retained by the WebView.

## 5. Optional voice download

The neural voice package is downloaded only after you tap to download it and confirm the action. The download is obtained from the public Sherpa-ONNX repository hosted on GitHub. This provider may receive technical information necessary for the connection, such as IP address and the app version identifier.

Folio verifies the package's integrity using SHA-256 and stores it in private storage. After installation, text and audio are processed locally, without using Google's standard TTS or an external speech API.

## 6. Data sharing

Folio does not sell, rent, or share reading content, PDFs, preferences, or audio with a proprietary server. It does not include advertising, analytics, or crash-reporting SDKs.

The connections needed for use are those you initiate: web pages and searches you choose, as well as the optional voice-package download. These third-party services process data under their own policies.

## 7. Retention and deletion

Local data remains until it is replaced, deleted by the app's routines, removed by the system, or deleted by you. To delete preferences, recent items, reading position, app cache, and the local voice, go to:

**Android Settings → Apps → Folio → Storage → Clear data**.

Uninstalling the app also removes your private data and the voice package stored by Folio. Deletion does not erase records retained by external websites, search engines, or the download provider; for that, consult those services' policies.

## 8. Security

Folio uses HTTPS connections, does not allow cleartext HTTP traffic, disables automatic backup of app data, and keeps the narration service unavailable to other apps. No storage or transmission method is entirely fail-safe; keep your Android system up to date and avoid opening websites or PDFs from untrusted sources.

## 9. Children

Folio is not specifically directed at children and does not intentionally request personal data. The person responsible for the device should supervise the website and PDF content accessed by minors.

## 10. Changes to this Policy

This Policy may be updated when Folio's features or data practices change. Material changes will be reflected in the app and, when necessary, will require new consent.

## 11. Google Play requirements

To publish Folio on Google Play, this Policy must be made available at a public, active, non-geoblocked URL and also provided in the Play Console. The **Data safety** declaration in the Play Console must exactly reflect this Policy, the app's permissions, and the third-party components used.
