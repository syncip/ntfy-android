# ntfy Sync – Änderungen gegenüber ntfy-android

Basis: https://github.com/binwiederhier/ntfy-android (Apache 2.0)

## Konto-Synchronisierung
- Neue Klasse `app/src/main/java/io/heckel/ntfy/msg/AccountSyncer.kt`.
- Für jeden in der App hinterlegten Benutzer wird `GET <server>/v1/account` abgefragt und alle dort
  gespeicherten Abos (dieselbe Liste wie in der ntfy-Web-App) werden automatisch abonniert
  (Sofortzustellung, alte Nachrichten werden still nachgeladen, ohne Benachrichtigungs-Flut).
- Wird ein Topic im Konto entfernt, verschwindet es auch aus der App – aber nur Topics, die der Sync
  selbst angelegt hat. Manuell angelegte Topics und UnifiedPush-Abos bleiben unangetastet.
- Ist der Server nicht erreichbar, wird nichts gelöscht.
- In der App abonnierte Topics werden ins Konto übernommen (`POST /v1/account/subscription`),
  gelöschte dort entfernt (`DELETE /v1/account/subscription`).
- Ausgelöst: beim App-Start, beim Herunterziehen der Liste, beim Anlegen/Ändern eines Benutzers,
  stündlich im Hintergrund und bei jedem Verbindungsaufbau zum Server (höchstens alle 5 Minuten;
  das Funkmodul ist dann ohnehin aktiv).
- Abschaltbar: Einstellungen → Allgemein → „Themen aus Konto synchronisieren“.

## Anmeldung per Token
- Neue Klasse `app/src/main/java/io/heckel/ntfy/msg/AccountTokens.kt`.
- Beim Anlegen eines Benutzers meldet sich die App einmal mit dem Kennwort an, erstellt ein
  Zugangs-Token (`POST /v1/account/token`, Bezeichnung „ntfy Sync (Gerätename)“) und speichert nur
  das Token. Das Kennwort wird nie gespeichert.
- Das Token läuft nie ab (`expires: 0`). Ohne diese Angabe würde der Server es nach 72 Stunden
  ungültig machen. Noch gültige Tokens älterer App-Versionen stellt der Sync automatisch um
  (`PATCH /v1/account/token`). Ein bereits abgelaufenes Token lässt sich nicht retten: Dann den
  Benutzer einmal in den Einstellungen mit Kennwort neu anmelden.
- Tokens werden als `Authorization: Bearer` gesendet.
- Beim Löschen oder Ändern eines Benutzers wird das alte Token auf dem Server widerrufen.
- Bereits gespeicherte Kennwörter werden beim nächsten Sync automatisch durch ein Token ersetzt.
- Schlägt die Anmeldung fehl, wird der Benutzer nicht gespeichert. Nur wenn ein Server gar keine
  Tokens kennt (404), bleibt das Kennwort als einzige Möglichkeit gespeichert.

## Benachrichtigungen
- Jede Nachricht bekommt eine eigene Benachrichtigung mit Ton, auch Priorität 1 und 2
  (eigene hörbare Kanäle `-min-sound` und `-low-sound`).
- Die dauerhafte Benachrichtigung zeigt die Zahl der ungelesenen Nachrichten und, aufgeklappt,
  „Alle als gelesen markieren“.
- Zeitstempel in der Nachrichtenliste sekundengenau.

## Dauerbetrieb
- Beendet Android den Dienst, baut er nach dem automatischen Neustart sofort wieder die Verbindung
  auf (vorher erst beim Prüfjob, bis zu 3 Stunden später). Im Test: etwa 4 Sekunden.
- Neustart nach dem Wischen aus „Letzte Apps“ repariert.
- Prüfjob alle 30 Minuten statt alle 3 Stunden, als Sicherheitsnetz.
- Exakte Alarme für das Wiederverbinden sind automatisch erlaubt (`USE_EXACT_ALARM`), der
  entsprechende Hinweis entfällt. Das geht, weil die App nicht über Google Play verteilt wird.
- Dienst-Typ `specialUse`: kein Zeitlimit für Vordergrunddienste unter Android 15.
- Weiterhin nötig: Akku-Optimierung für die App ausschalten.

## Beenden
- Hauptmenü → „Beenden“ (mit Rückfrage): stoppt Dienst, Verbindung und Hintergrundabfragen.
- Die App bleibt aus, auch nach einem Geräteneustart, bis sie wieder geöffnet wird.

## Alle als gelesen markieren
- Häkchen-Symbol oben auf der Hauptseite, nur sichtbar, wenn es ungelesene Nachrichten gibt.
- Zusätzlich als Button in der dauerhaften ntfy-Benachrichtigung in der Statusleiste (aufklappen).
- Entfernt alle offenen Benachrichtigungen und die „Neu“-Zähler aller Themen.

## Gelesen-Status zwischen App und Web-App
Nutzt die eingebaute „clear“-Funktion von ntfy (`PUT /<topic>/<sequence_id>/clear`, ab Server
v2.17.0). Der Server macht daraus ein `message_clear`-Ereignis, das Web-App und Android-App als
„gelesen + Benachrichtigung entfernen“ verstehen. Keine Serveränderung nötig.

**App → Web** (`msg/ReadSync.kt`)
- Beim Öffnen eines Themas und bei „Alle als gelesen“ meldet die App die ungelesenen Nachrichten
  (höchstens die 50 neuesten pro Aktion) als gelesen.
- Optional auch beim Wegwischen einer Benachrichtigung (Standard: aus).
- Einstellungen → Benachrichtigungen: „Gelesen-Status mit Web-App abgleichen“ und
  „Wegwischen markiert als gelesen“.
- Gesendet wird per WorkManager, nur mit Netz, gedrosselt (200 ms Abstand), mit Wiederholung bei
  Netzfehlern oder Rate-Limit. Kein Dauer-Polling.
- Braucht Schreibrecht auf das Thema; ohne Schreibrecht wird es still übersprungen.

**Web → App** (`userscript/ntfy-read-sync.user.js`)
- Die unveränderte Web-App speichert „gelesen“ nur lokal im Browser. Das Userscript erkennt,
  wenn du dort etwas liest, und sendet dafür die „clear“-Meldung. Die App empfängt sie über ihre
  bestehende Verbindung, markiert die Nachricht als gelesen und entfernt die Benachrichtigung.
- Installation: Browser-Erweiterung Tampermonkey oder Violentmonkey installieren, dann die Datei
  `userscript/ntfy-read-sync.user.js` dort als neues Skript einfügen und speichern. Es läuft nur auf
  `https://ntfy.1337733.xyz/*`.
- Prüft alle 2 Sekunden, solange der Tab sichtbar ist. Alter Verlauf wird nie gemeldet, nur was
  ab dem Öffnen der Seite gelesen wird. Was von anderen Geräten als gelesen kommt, wird nicht
  zurückgemeldet (kein Ping-Pong).
- Kann bei größeren Änderungen der ntfy-Web-App brechen (liest deren Browser-Datenbank).

**Hinweis Rate-Limit:** Jede Meldung ist eine Anfrage an den Server. Wenn du auf einmal sehr viele
Nachrichten liest, kann das Rate-Limit des Servers (`visitor-request-limit-*`) greifen; die App
wiederholt dann später.

## Konto-Seite
- Hauptmenü → „Konto“ oder Einstellungen → „Konto“ (`ui/AccountActivity.kt`, `msg/AccountApi.kt`).
- Zeigt Benutzer, Rolle/Tarif, Nutzung mit Limits (nur Zeilen mit Limit), reservierte Themen
  (anlegen/aufheben), Zugangs-Tokens (fremde widerrufen; das Token dieser App ist geschützt) und
  „Kennwort ändern“.
- Daten werden nur geladen, solange die Seite offen ist (Herunterziehen = neu laden).

## Aufgeräumt
- „Fehler melden“ im Menü ausgeblendet (würde zum Original-Projekt führen).

## Akku / Zuverlässigkeit
- Standard-Verbindungsprotokoll ist WebSocket statt JSON-Stream (Client-Ping alle 3 Minuten statt
  Server-Keepalive-Text alle 45 s).
- Wakelock bei eingehenden Nachrichten wird immer freigegeben (`try/finally`) und ist auf 1 Minute
  statt 10 Minuten begrenzt.

## Design
- Dynamische Farben (Material You, Android 12+) standardmäßig an.
- Eigenes App-Icon (Vektor): ntfy-Sprechblase mit Sync-Pfeilen auf petrolblauem Verlauf.
  Dateien: `app/src/main/res/drawable/ic_launcher_{foreground,background,monochrome}.xml`.

## Sonstiges
- App-ID `com.wagnermartin.ntfysync`, Name „ntfy Sync“ – läuft parallel zur Original-App.
- Standard-Server: `https://ntfy.1337733.xyz` (`app_base_url` in `res/values/values.xml`).

## Signierschlüssel (wichtig: Backup!)
Release-Builds werden mit einem eigenen Schlüssel signiert. Er liegt **außerhalb** des Repos:

```
C:\Users\Martin\.ntfy-sync-signing\
    ntfy-sync-release.jks     (Schlüssel)
    keystore.properties       (Pfad, Alias und Passwort)
```

Diesen Ordner komplett sichern (z. B. Passwort-Manager oder verschlüsselter USB-Stick). Ohne ihn
lassen sich installierte Apps nicht mehr aktualisieren, nur deinstallieren und neu installieren.
Anderen Speicherort nutzen: in `local.properties` `ntfySync.keystoreProperties=<pfad>` eintragen.

## Updates vom Original übernehmen
```
git fetch upstream
git merge upstream/main
```

## Bauen
```
./gradlew assembleFdroidRelease
```
APK nach dem Bauen: `app/build/outputs/apk/fdroid/release/app-fdroid-release.apk`

Fertig gebaute APK zum Installieren: `apk/ntfy-sync.apk` (nicht im Git; nach einem Build von Hand aus dem Build-Ordner kopieren)
