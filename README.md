# Settore Zero

Sparatutto 3D in verticale per iPhone, giocabile con un dito solo.
Questo ramo è indipendente da Forge: contiene solo il gioco e la build dell'IPA.

- `www/index.html` — il gioco completo (una sola pagina, funziona offline)
- `App/` — involucro iOS (WKWebView a schermo intero), icona e Info.plist
- `.github/workflows/settore-zero-ipa.yml` — compila l'IPA a ogni push su questo ramo

L'IPA compilata si trova nella release **Settore Zero (IPA)** del repository
(o tra gli artifact della build). Non è firmata: installala con TrollStore,
Sideloadly o AltStore.

## Crediti dei modelli 3D

- Soldato: personaggio Mixamo, dagli esempi di three.js
- «Space Ship Hallway» di yeeyeeman (Sketchfab), licenza CC-BY 4.0
- Barile: dagli asset di Babylon.js, licenza CC-BY 4.0
