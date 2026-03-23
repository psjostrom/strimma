# Translating Strimma

Strimma uses Android's built-in string resource system for localization.

## Adding a new language

1. Copy `app/src/main/res/values/strings.xml` to `app/src/main/res/values-XX/strings.xml` where `XX` is the [ISO 639-1 language code](https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes) (e.g., `values-ja` for Japanese, `values-pt` for Portuguese).

2. Translate the values between `>` and `</string>`. Keep everything else unchanged:
   - String `name="..."` attributes (the keys)
   - Format placeholders: `%1$s`, `%1$d`, `%1$.1f`
   - Unicode escapes: `\u00B7`, `\u2014`, `\u2026`, etc.

3. Do **not** include strings marked `translatable="false"` in your translation file. These are technical terms and symbols that stay in English for all locales (Nightscout, IOB, mmol/L, etc.).

4. Do **not** translate or modify the `name="..."` attributes.

5. Test your translation: change your phone's language in Settings, then open Strimma and walk through every screen.

6. Submit a pull request.

## Currently supported languages

- English (default)
- Swedish (`values-sv`)
- Spanish (`values-es`)
- French (`values-fr`)
- German (`values-de`)
