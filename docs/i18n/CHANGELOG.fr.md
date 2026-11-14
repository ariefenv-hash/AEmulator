# AEmulator 0.0.0.4

### Un vrai root
- Un vrai su transparent pour le guest (`tools/romroot/su.c`, arm64 sans libc, ~2 Ko) : exec le `/system/bin/sh` de la firmware avec stdin/out/err intacts. L'ancien su répondait une fois puis sortait — les checkers passaient, mais libsu de Shizuku garde une session ouverte et écrit dans stdin, donc chaque lancement root se figeait. Désormais `su`, `su CMD`, `su -c CMD`, `su -mm`, `su -v/-V` se comportent comme libsu, Shizuku et les checkers l'attendent.
- `tools/romroot/rootify.py` transforme un GSI ext4 en image rootée sur place (debugfs) : su dans `/system/xbin`, `/system/bin`, `/su/bin`, propriétés (`ro.debuggable=1`, `ro.secure=0`, test-keys), couche Xposed et fichier marqueur. Sauvegarde de build.prop incluse ; `--undo` restaure tout.
- La chaîne de lancement root de Shizuku fonctionne de bout en bout : app_process vérifié, `/data/local/tmp` créé au premier démarrage, serveur exécuté en uid 0.

### Xposed API 100
- Le guest embarque une couche de déclaration de l'API Xposed niveau 100 (`tools/xposed`, ECJ+D8) : `de.robv.android.xposed.*` dans le boot classpath de chaque app — `XposedBridge.getXposedVersion()` renvoie 100. Par honnêteté : `hookMethod` est déclaré mais aucun moteur de hook ART n'est fourni ; l'appel échoue avec un message clair.

### Réglages
- Nouvelle section Root pour les images qui embarquent la surface rootify : un interrupteur retire le su (`su` → `su.off`) côté hôte avant tout service guest et le remet. Effectif au prochain démarrage.

---

# AEmulator 0.0.0.3

[🇬🇧 English](../../CHANGELOG.md) · [🇷🇺 Русский](CHANGELOG.ru.md) · [🇺🇦 Українська](CHANGELOG.uk.md) · [🇩🇪 Deutsch](CHANGELOG.de.md) · **🇫🇷 Français** · [🇪🇸 Español](CHANGELOG.es.md) · [🇧🇷 Português](CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](CHANGELOG.it.md) · [🇵🇱 Polski](CHANGELOG.pl.md) · [🇹🇷 Türkçe](CHANGELOG.tr.md) · [🇸🇦 العربية](CHANGELOG.ar.md) · [🇮🇷 فارسی](CHANGELOG.fa.md) · [🇮🇳 हिन्दी](CHANGELOG.hi.md) · [🇮🇩 Indonesia](CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](CHANGELOG.vi.md) · [🇨🇳 简体中文](CHANGELOG.zh-CN.md) · [🇯🇵 日本語](CHANGELOG.ja.md) · [🇰🇷 한국어](CHANGELOG.ko.md)

### Émulation
- Nouveau moteur arm64 natif : Android 5.0–17 et les GSI (8–17) tournent directement sur le téléphone ; Android 2.3–4.4 via QEMU.
- Prise en charge des firmwares 64 bits ; leurs programmes 32 bits passent par qemu-arm.
- Prise en charge complète d’Android 8, GSI compris.
- zygote et mediaserver plantent beaucoup moins (corrections de binder, blocage de system_server corrigé).
- `/system` est en lecture seule pour l’invité : le code précompilé du framework n’est plus perdu.
- Premier démarrage plus rapide des firmwares Dalvik (Galaxy S III : 104 s → 59 s).
- Option d’exécution 32 bits matérielle (AArch32) sans traducteur, avec QEMU en secours.
- GLES 1/2/3 via SwiftShader.

### Firmwares et constructeurs
- TouchWiz : `ro.tvout.enable=false`, `ro.SecEDS.enable=false` et correctifs binder.
- Firmwares Qualcomm : suppression de `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager`.
- Infinix (AOSP / XUI / XOS) et Tecno (AOSP / HiOS) détectés selon la version d’Android.
- Sony : émulation de la LED de notification.
- Corrections pour MIUI, HTC Sense, TouchWiz, Xperia et AOSP. Testé sur Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### Importation
- Nouveaux formats : 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### Fonctionnalités
- Internet fonctionne dans davantage de firmwares.
- Son corrigé ; microphone, caméra, accéléromètre et vibration ajoutés.
- Barre de navigation : Retour, Accueil et Récents fonctionnent sur tous les firmwares.
- ADB sur le réseau local et terminal ADB intégré.
- Menu à trois points corrigé dans un système lancé : captures, enregistrement d’écran, VNC, SMS, mode avion, installation d’APK, etc.
- Le bouton de menu peut être déplacé dans un système lancé.
- Option pour masquer le voyant LED : dans les valeurs par défaut des nouveaux systèmes et par système.

### Services de l'hôte
- Toutes les sockets visibles depuis l'invité sont désormais déclarées à un seul endroit (`EndpointRegistry`) et démarrées dans l'ordre de déclaration : la liste des services et leur état en direct ont une source unique. Redémarrer une instance n'hérite plus des déclarations du démarrage précédent.
- Nouvelle socket de contrôle `aemu_bridge` (bproto2) à côté des points d'accès existants : propriétés, alimentation, presse-papiers et tueur de mémoire basse, chacune avec son propre octet d'opération. Les anciens protocoles (`rild`, `vold`, `dnsproxyd`, `lmkd`) restent intacts.
- Les services de l'hôte sont accordés par instance depuis un ensemble fermé de capacités (`hostCaps` dans les réglages de l'image) ; un service non accordé répond `EPERM`, et le chemin de distribution n'utilise aucune réflexion.
- Le mode récupération ne laisse plus une socket d'entrée liée après sa sortie.
- Disposition de `ab_hello_rep` corrigée : l'enregistrement fait 76 octets sur le fil (2 octets de remplissage avant `pid`), et non 74. Une assertion statique C11 fait maintenant échouer la compilation s'il change encore.
- `lmkd` peut enfin être accordé : il vit hors de la table des services de l'hôte mais dans l'ensemble des capacités, et une autorisation qui ne correspondait à rien disparaissait silencieusement.
- L’ensemble des capacités est désormais visible : les réglages ont une section Services de l’hôte avec un interrupteur par capacité, uniquement pour les firmwares dont le noyau exporte le pont (Android 9+), car sur les images plus anciennes les interrupteurs ne pourraient rien faire.

### Compilation
- GitHub Actions compile à chaque push : le test hôte du pont (gcc, `SOCK_SEQPACKET`), l'autotest de disposition d'octets de l'hôte factice (Python), une compilation armv7 de `libaemushim.so` avec vérification des exports `ab_call`/`ab_status`/`ab_last_len`, puis les tests unitaires JVM et un APK de débogage.
- Les échecs de compilation sont republiés en annotations de vérification, car les journaux de tâche d'un dépôt public exigent des droits d'administrateur pour être lus.
