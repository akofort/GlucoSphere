# GlucoSphere

Ein Android-Begleiter für Menschen mit Diabetes: ein KI-Chat- und Dashboard-Assistent, der reale
Messdaten (Blutzucker, Insulin, Sport, Körperzusammensetzung) über [MCP](https://modelcontextprotocol.io)-Server
oder direkte REST-APIs abruft und mit einem frei wählbaren LLM (lokal oder Cloud) auswertet.

> **Kein medizinisches Gerät.** GlucoSphere unterstützt bei der Auswertung vorhandener Daten, ersetzt
> aber keine ärztliche Beratung, Diagnose oder Therapieentscheidung.

## Features

- **Übersicht-Tab**: Ampel-Status (Grün/Gelb/Rot) mit Time-in-Range, %CV, Ø Glukose etc. Progressive
  Zwei-Stufen-Architektur: Stufe 1 liefert Ampel + aktueller BZ-Wert in unter 2 Sekunden per
  direkter Nightscout-REST-Abfrage, Stufe 2 lädt den KI-Vergleichsbericht asynchron nach.
  Pull-to-Refresh, Landscape-/Tablet-Layout, Teilen-Funktion.
- **Chat-Tab**: Gemini-Style-Eingabefeld mit Tool-Auswahl-Chips pro Datenquelle, natives
  Speech-to-Text/Text-to-Speech inkl. durchgängigem Sprachdialog-Modus, Kopieren/Teilen einzelner
  Antworten oder des kompletten Chatverlaufs (inkl. Zeitstempeln und genutzten Tools).
- **Multi-Provider-LLM**: lokales On-Device-Modell (Gemma via LiteRT-LM) oder Cloud-Provider
  (Google Gemini, Anthropic Claude, OpenAI/OpenRouter, DeepSeek) frei wählbar, inkl. Kosten-/
  Token-Tracking.
- **MCP-Tool-Calling**: bis zu 5 gleichzeitige MCP-Server (SSE oder Streamable HTTP), parallele
  Tool-Aufrufe, pro-Server Auto-Approve, sowie eine optionale direkte Nightscout-REST-API ohne
  MCP-Server.
- **Discovery Modus**: erkennt automatisch, welche Werkzeuge/Plugins ein neu verbundener MCP-Server
  oder Nightscout-Instanz anbietet, lässt ein schnelles LLM eine deutsche Zusammenfassung, passende
  Beispielfragen und die Datenstruktur (inkl. Lese-/Schreibrechte) generieren.
- **Sicherheit & Datenschutz**: bidirektionales Privacy-Shield (Name/E-Mail/Adresse werden vor dem
  Versand an Cloud-Provider maskiert und in der Antwort automatisch wieder entmaskiert), striktes
  Anti-Halluzinations-Regelwerk im System-Prompt, Client-seitiger No-Data-Fallback (fragt gar nicht
  erst das LLM, wenn keine Datenquelle aktiv ist), Erkennung/Unterbindung von als Text/XML
  "cosplayten" Tool-Aufrufen.

## Architektur

Kotlin, Jetpack Compose (Material 3), MVVM mit `ViewModel`/`StateFlow`, DataStore Preferences für
Einstellungen/Cache.

```
app/src/main/java/com/example/diabai/
├── data/       Einstellungen (DataStore), Server-/Provider-Konfigurationsmodelle
├── domain/     Agent-Loop (Tool-Calling), LLM-Provider-Abstraktion, Discovery Modus,
│               Privacy-Shield, Dashboard-Auswertung
├── network/    MCP-Client (SSE & Streamable HTTP), direkte Nightscout-REST-API
└── ui/         Compose-Screens (Übersicht, Chat, Einstellungen)
```

Provider-agnostische Schnittstelle (`LLMProvider`/`LlmConversation`) hinter der jede der vier
Cloud-Anbindungen sowie das lokale LiteRT-LM-Modell austauschbar stehen; `DiabetesAgent` orchestriert
den eigentlichen Tool-Calling-Loop (parallele Tool-Aufrufe, Bestätigungs-Gate, Streaming-Antworten)
unabhängig vom gewählten Provider.

## Setup

1. Android Studio (aktuell) mit installiertem SDK.
2. Projekt öffnen -- `local.properties` wird automatisch mit dem lokalen SDK-Pfad angelegt.
3. Build: `./gradlew assembleDebug`
4. Datenquelle(n) und LLM-Provider werden ausschließlich zur Laufzeit über die Einstellungen
   konfiguriert (keine Secrets im Repo).

## Datenquellen

- MCP-Server beliebiger Kategorie (Blutzucker/Behandlungen, Sport, Körperzusammensetzung), z. B.
  eigene Nightscout-/Glooko-/Withings-Anbindungen.
- Direkte Nightscout-REST-API als Alternative ohne eigenen MCP-Server.

Alle Zugangsdaten werden ausschließlich lokal auf dem Gerät (DataStore) gespeichert.
