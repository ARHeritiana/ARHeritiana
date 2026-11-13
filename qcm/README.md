# QCM personnalisé : sujets chiffrés + correction par photo

Un sujet par élève (10 questions A–D), un QR code **chiffré** contenant le nom et les
bonnes réponses, et une application Android qui corrige une copie à partir d'une photo.

| Élément | Rôle |
|---|---|
| `qcm_personnalise.tex` | Source LuaLaTeX unique. Écrit aussi `qcm_chiffre.lua`, `qcm_generateur.lua`, `corriger.lua` à chaque compilation. |
| `qcm_cle.txt` | Clé secrète du lot (non versionnée, à créer). |
| `android/` | Application « QCM Scan » (`core` = logique testée sur PC, `app` = écrans Android). |

## Utilisation

1. **Créer la clé du lot** (une par classe / devoir), au choix :
   - `texlua corriger.lua --nouvelle-cle "DS1-2026"` (Linux/macOS ; les scripts existent après une première compilation, qui s'arrête sur « fichier de clé introuvable ») ;
   - dans l'application : *Nouvelle clé*, puis *Partager*, et coller la ligne `QCMKEY1:…` dans un fichier `qcm_cle.txt` à côté du `.tex` (seule méthode sur Overleaf ou Windows).
2. **Compiler** `qcm_personnalise.tex` avec LuaLaTeX (liste des noms et `\Graine` en tête de fichier). `\def\PROF{}` donne le corrigé.
3. **Importer la clé dans le téléphone** (*Importer une clé* : texte collé ou fichier `qcm_cle.txt`) si elle n'y a pas été créée.
4. **Corriger** : *Photographier une copie* → vérifier la lecture (toucher une case pour la corriger ; les cases douteuses sont en orange) → *Enregistrer*.
5. **Exporter** : écran du lot → `.xlsx` ou `.csv` (Nom, R1…R10 données, Note /10, C1…C10 attendues, Lot, Scan).

Sans téléphone : `texlua corriger.lua "TEXTE_DU_QR" ABACBBDA.D` (voir l'en-tête de `corriger.lua`).

## Chiffrement du QR

- **ChaCha20-Poly1305 (RFC 8439)**, écrit en Lua pur (LuaTeX n'a pas de bibliothèque de chiffrement) et en Kotlin pur ; les deux sont vérifiés sur les vecteurs de la RFC, et le Kotlin contre l'implémentation du JDK.
- Texte du QR = base64url de `01 | id de clé (3 octets) | nonce (12) | chiffré | tag (16)`. Clair = 10 réponses (2 bits chacune) + nom en UTF-8 (accents conservés). 66 caractères pour un nom de 13 lettres.
- **Clé** : 32 octets aléatoires, indépendants de `\Graine` et des noms. L'id de clé (en clair) permet à l'application de choisir la bonne clé ; il est dérivé de la clé et ne la révèle pas.
- **Nonce** : déterministe, dérivé de la clé et du contenu (CBC-MAC sur le bloc ChaCha20). Mêmes noms + même graine + même clé = mêmes feuilles ; un contenu différent donne un nonce différent.
- Un QR modifié, fabriqué ou d'un autre lot est refusé (tag d'authentification).
- Dans le téléphone, les clés sont stockées chiffrées par une clé AES-GCM de l'Android Keystore ; l'application n'a aucune permission (ni Internet, ni appareil photo : la photo passe par l'application Appareil photo).

**Limites** : la clé est en clair dans `qcm_cle.txt` (et dans le projet Overleaf si on l'y met) : ne pas partager ce dossier. Le chiffrement protège le QR, pas le sujet : qui possède le `.tex`, un nom et la graine peut régénérer le sujet et ses réponses.

## Lecture de la grille

Le bas de page (178 × 42 mm) contient 4 carrés noirs de repérage, la grille 4 × 10 et le QR. Sa géométrie est définie dans la table `Z` du `.tex` et dans `android/core/.../Zone.kt` : **modifier les deux ensemble**.

Le QR donne la position approximative ; les 4 carrés donnent la transformation exacte (perspective, rotation quelconque) ; l'intérieur de chaque case est échantillonné. Taux de noir < 4 % : vide ; ≥ 10 % : cochée ; entre les deux : douteuse. Aucune case = `.`, plusieurs = `*` (0 point), 1 point par bonne réponse, comme `corriger.lua`.

## Construire et tester

```sh
cd android
./gradlew :core:test          # chiffrement, lecture de 4 photos simulées, export
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

Android 7.0 minimum. Les photos de test sont produites par `android/outils/simule.py` à partir des vraies pages PDF (clé de test publique `AAEC…`, lot `TEST`).

## État des vérifications

- Vérifié : compilation LuaLaTeX (élève, corrigé, 60 noms sans débordement), vecteurs RFC 8439, déchiffrement croisé Lua / Kotlin / Python, tous les modes de `corriger.lua`, lecture des 4 photos simulées, fichiers CSV et XLSX (relus avec openpyxl), construction de l'APK, lint sans erreur.
- **Non vérifié** : l'application n'a été lancée sur aucun téléphone ni émulateur, et aucune vraie photo n'a été testée (ombres dures, feuille froissée, crayon pâle, impression de mauvaise qualité). Les seuils 4 % / 10 % sont à ajuster sur de vraies copies.
