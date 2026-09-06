# Kanakku Pulla PRO Android APK

Single-app Android build based on the supplied `kanakku-pullapro-mobile-main (7).zip`.

Included:
- Existing Kanakku Pulla PRO mobile UI/features/assets
- Supplied Kanakku Pulla PRO launcher icon
- Native Android Bluetooth Classic SPP printer bridge
- Existing bill renderer -> native direct print
- Existing barcode/label renderer and alignment -> native direct TSPL bitmap print
- Real barcode is rendered by the existing Kanakku Pulla PRO `renderRealCode128()` flow before printing

First printer use: pair the printer once in Android Bluetooth Settings. Receipt and Label modes still need to match the print job.

Note: the packaged web app keeps the original `/api/verify-gstin` server-proxy path. A local APK has no Vercel `/api` runtime, so GSTIN auto-lookup needs the production API endpoint wired into the APK if that feature is required natively. All other cloud calls present in the supplied app remain unchanged.
