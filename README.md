# Tab Volume Regelaar — versie 1.0.5 (build 6)

Twee Android-apps:
- **Volume Receiver** op de tablet: foreground service en Supabase-cloudrelay.
- **Volume Controller** op de telefoon: bediening via Supabase.

## Controller
- Twee verticale regelaars tegelijk: links helderheid, rechts volume.
- Brede YouTube Kids-snelkoppeling.
- Stand-by / Wakker maken met bevestiging.
- Wifi-hulp via cloudrelay.
- Cloudstatus wordt elke 15 seconden ververst wanneer de controller op de voorgrond staat (voorheen elke 2 seconden).
- Statuscontrole wordt gepauzeerd bij app lifecycle states inactive/hidden/paused en hervat bij terugkeer.
- Online-status gebruikt een tolerantie van 150 seconden, zodat de tablet niet direct offline lijkt tijdens de zuinige pollingcyclus.

## Batterijvriendelijke werking
- Geen permanente CPU partial wake lock.
- Geen permanente Wi-Fi lock.
- Alleen tijdens een daadwerkelijke cloudcontrole wordt een tijdelijke CPU wake lock gebruikt (maximaal 12 seconden).
- Receiver op achtergrond/stand-by: controle ongeveer elke 45 seconden.
- Receiver op achtergrond, niet in stand-by: controle ongeveer elke 60 seconden.
- Receiver zichtbaar: controle ongeveer elke 10 seconden.
- Receiver herprobeert een mislukte cloudregistratie na 30 seconden in plaats van elke 5 seconden.
- Controller-statuspolling stopt wanneer de app op de achtergrond gaat.
- Android Doze/batterijbeheer kan netwerkcontroles verder uitstellen.
- Batterijoptimalisatie uitsluiten is niet verplicht.
- FCM is nog niet geïntegreerd; hiervoor zijn Firebase appconfiguratie en servercredentials nodig.

## Stand-by en terugkeren naar waar je was
- Stand-by gebruikt Android Device Admin `DevicePolicyManager.lockNow()`.
- Android toont daarna het normale beveiligde vergrendelscherm.
- Receiver start bij wakker maken geen Activity en haalt zichzelf niet naar de voorgrond.
- Wake-functie gebruikt een tijdelijke Android wake-lock. Gedrag kan per Android-versie/fabrikant verschillen; echte wake-test is vereist.

## YouTube Kids
- Als Receiver zichtbaar is, verwerkt hij de opdracht en opent YouTube Kids.
- Op de achtergrond toont Android een tikbare melding als fallback.

## Eenmalige instellingen op de tablet
1. Open Receiver en voer appcode `0000` in.
2. Sta Helderheid op afstand toe.
3. Activeer Stand-by op afstand bij Android Device Admin.
4. Stel een normaal Android-patroon, pincode of wachtwoord in.
5. Sta Wifi instellen op afstand toe.
6. Laat Receiver online koppelen zodat cloudreferenties in Android Keystore worden opgeslagen.
7. Batterijoptimalisatie uitsluiten is niet verplicht.

## Backend en beveiliging
- Supabase project: `nstpzmuimwjbvlvvbrsr` (EU West)
- Edge Function: `volume-remote`, versie 6
- RLS staat aan op de relevante tabellen.
- Service/secret key staat niet in de APK of openbare repository.
- Receiver bewaart device-identiteit en geheim versleuteld met Android Keystore (AES-GCM).

## Bouwen en testen
```bash
flutter pub get
flutter test
flutter build apk --release --flavor receiver --dart-define=APP_ROLE=receiver --build-name=1.0.4 --build-number=5
flutter build apk --release --flavor controller --dart-define=APP_ROLE=controller --build-name=1.0.4 --build-number=5
```

- Widgettests: geslaagd.
- Fysieke standby/wake en batterijmeting: nog niet uitgevoerd.


## Update 6 — live bediening

- Supabase Realtime Broadcast / WebSocket voor directe opdrachtmeldingen.
- Controller verstuurt sliderwaarden met een throttle van 65 ms tijdens het slepen.
- Receiver haalt de opdracht direct na het Realtime-seintje op via de geauthenticeerde Edge Function.
- Na uitvoering stuurt de Receiver de actuele apparaatstatus terug via een heartbeat.
- Automatisch herverbinden en fallbackcontrole als de WebSocket wegvalt.
- Edge Function: volume-remote v8.

### Android-beperkingen

Android kan netwerkverkeer in diepe Doze/batterijbesparing uitstellen. Een open WebSocket garandeert daarom niet dat de tablet op elk moment direct vanuit diepe slaap wakker wordt. Firebase Cloud Messaging is nog niet ingesteld.

Wifi-verzoeken worden via Android WifiNetworkSuggestion aangeboden. Android bepaalt zelf of en wanneer een netwerk wordt gebruikt en kan goedkeuring vragen; de app kan de OS-verbinding niet forceren.

De praktische reactiesnelheid en het batterijverbruik moeten nog op de echte telefoon en tablet worden gemeten.

[executed on device: PC-van-Jake (1997e53f-651c-4a6a-959d-b2acccb4b133)]