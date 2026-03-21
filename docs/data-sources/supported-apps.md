# Supported CGM Apps

Strimma can read glucose from the following apps in **Companion mode**.

---

## Dexcom

- Dexcom G6 (all regional variants)
- Dexcom G7
- Dexcom ONE
- Dexcom D1+
- Dexcom Stelo

## Abbott / Libre

- Libre 3
- Libre 2 / LibreLink

## CamAPS FX

- CamAPS FX (all variants — mmol/L and mg/dL, including HX and Canadian editions)

## Medtronic

- Guardian Connect
- MiniMed Mobile
- Simplera

## Eversense

- Eversense
- Eversense Gen12
- Eversense 365

## Third-Party / DIY Apps

- xDrip+
- Juggluco
- Diabox

## Aidex

- Aidex (all variants — LinxNeo, Equil, DiaExport, Smart)

## Sinocare

- Sinocare CGM
- iCan Health

## Other

- Suswel
- GlucoTech
- OttAI SEAS
- OttAI TAG

---

## My CGM App Isn't Listed

If your CGM app shows glucose in a notification but isn't listed above:

1. It might still work — try it and check the **Debug Log** (Settings > Debug Log) for messages
2. If it doesn't work, [open an issue](https://github.com/psjostrom/Strimma/issues) on GitHub with:
    - The app name
    - A screenshot of the notification
    - Whether the app shows glucose in mmol/L or mg/dL

Adding support for a new app is usually straightforward and can happen in the next release.

---

## Alternative: xDrip Broadcast

If your CGM app isn't supported in Companion mode, you may be able to use [xDrip Broadcast mode](xdrip-broadcast.md) instead. Many intermediary apps (Juggluco, xDrip+, AAPS) can receive data from your sensor and broadcast it in a format Strimma understands.

---

??? info "Package names (for developers)"
    If you're a developer and need the exact Android package names Strimma monitors:

    **Dexcom:** `com.dexcom.g6`, `com.dexcom.g7`, `com.dexcom.dexcomone`, `com.dexcom.d1plus`, `com.dexcom.stelo`, plus 11 regional G6 variants

    **Abbott:** `com.freestylelibre3.app`, `com.freestylelibre3.app.de`, `com.freestylelibre.app`, `com.freestylelibre.app.de`

    **CamAPS FX:** `com.camdiab.fx_alert.mmoll`, `.mgdl`, `.hx.mmoll`, `.hx.mgdl`, `.mmoll.ca`

    **Medtronic:** `com.medtronic.diabetes.guardian`, `.guardianconnect`, `.guardianconnect.us`, `.minimedmobile.eu`, `.minimedmobile.us`, `.simplera.eu`

    **Eversense:** `com.senseonics.androidapp`, `.gen12androidapp`, `.eversense365.us`

    **Third-party:** `com.eveningoutpost.dexdrip`, `tk.glucodata`, `com.outshineiot.diabox`

    **Aidex:** `com.microtech.aidexx.mgdl`, `com.microtech.aidexx`, plus `linxneo.mmoll`, `equil.mmoll`, `diaexport.mmoll`, `smart.mmoll` variants

    **Sinocare:** `com.sinocare.cgm.ce`, `com.sinocare.ican.health.ce`, `.ican.health.ru`

    **Other:** `com.suswel.ai`, `com.glucotech.app.android`, `com.ottai.seas`, `com.ottai.tag`
