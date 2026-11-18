# WLAN Logger (Android)

Android-App, die WLAN-Rohdaten aufzeichnet und als JSON/CSV exportiert – zum
Auswerten (z. B. mit Claude).

## Was erfasst wird

**Verbindung** (Intervall einstellbar, Standard 3 s):
SSID, BSSID, RSSI (dBm), Signalstufe, Link-Rate (TX/RX/max), Frequenz, Kanal, Band,
WLAN-Standard, IP, Gateway, DNS, Internet bestätigt (ja/nein), Bandbreitenschätzung,
Ping zum Router und zu einem Internet-Ziel (Standard 1.1.1.1).

**Umgebung** (Scan-Intervall einstellbar, Standard 30 s):
Alle sichtbaren Access Points mit SSID, BSSID, RSSI, Frequenz, Kanal, Kanalbreite,
Mittenfrequenzen, Sicherheit, Capabilities, WLAN-Standard.

**Ereignisse:** WLAN verbunden/verloren, Internet weg/wieder da, Roaming
(BSSID-Wechsel), gedrosselte Scans, Fehler.

## Installation

1. Auf GitHub unter **Releases → `apk-latest`** die Datei `wlan-logger.apk` am
   Handy herunterladen (alternativ: **Actions → letzter Lauf → Artifact**).
2. Öffnen und „Installation aus unbekannten Quellen“ für Browser/Dateimanager erlauben.
3. App starten → **Logging starten** → Berechtigungen erteilen
   (**genauer Standort** ist Pflicht, sonst liefert Android keine Scan-Daten).

## Export

- **JSON teilen (für Claude):** eine Datei mit `meta`, `summary` (vorberechnete
  Statistik: RSSI-Verteilung, Paketverlust, Ping-Perzentile, Kanalbelegung,
  Netzwerke nach Signalstärke, Ereigniszähler) und allen Rohdaten.
- **JSON speichern:** dieselbe Datei in einen Ordner deiner Wahl.
- **CSV teilen:** `connection`, `scans`, `events` als drei CSV-Dateien.

## Bekannte Grenzen (Android, nicht die App)

- **Scan-Drosselung:** Android erlaubt Apps ca. 4 Scans pro 2 Minuten. Abschaltbar
  unter *Entwickleroptionen → WLAN-Scan-Drosselung*.
- **Standort muss eingeschaltet sein**, sonst sind Scan-Ergebnisse leer und die SSID unbekannt.
- **Keine 802.11-Rohpakete:** Monitor-Mode/Paketmitschnitt ist ohne Root auf Android nicht möglich.
- **Akku:** Dauer-Logging hält die CPU wach. Manche Hersteller (Xiaomi, Huawei, Samsung)
  beenden Hintergrunddienste trotzdem → App in den Akku-Einstellungen auf „Nicht optimieren“ setzen.
- `link_speed_mbps` ist die ausgehandelte PHY-Rate, nicht der echte Durchsatz.

## Selbst bauen

```
./gradlew assembleDebug   # benötigt Android SDK (ANDROID_HOME)
```
Der Debug-Keystore liegt bewusst im Repo (`keystore/`), damit jeder Build gleich
signiert ist und sich Updates über die alte Version installieren lassen. Er ist
nur für diese private Debug-App gedacht, nicht für den Play Store.
