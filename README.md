# Tab Volume Regelaar — versie 1.0.3 (build 4)

Twee Android-apps:
- **Volume Receiver** op de tablet: draait als foreground-service, ook wanneer de app-UI op de achtergrond staat.
- **Volume Controller** op de telefoon: bedient de tablet via de Supabase-cloudrelay.

## Controller
- Twee verticale regelaars tegelijk: links helderheid, rechts volume.
- Brede YouTube Kids-snelkoppeling.
- Stand-by / Wakker maken met bevestiging.
- Wifi-hulp via cloudrelay.

## Batterijvriendelijke werking
- Geen permanente CPU partial wake lock.
- Geen permanente Wi-Fi lock.
- Alleen tijdens een daadwerkelijke cloudcontrole wordt een tijdelijke CPU wake lock gebruikt (maximaal 12 seconden).
- Als Receiver op de achtergrond/stand-by staat, controleert hij ongeveer elke 45 seconden op opdrachten.
- Als Receiver zichtbaar is, controleert hij ongeveer elke 10 seconden.
- Hierdoor kan een remote wake-opdracht langer op zich laten wachten. Android Doze/batterijbeheer kan controles bovendien verder uitstellen.
- De controller vereist niet langer dat de gebruiker batterijoptimalisatie uitschakelt.
- Voor snelle wake-up én laag verbruik is push via Firebase Cloud Messaging (FCM) de aanbevolen volgende stap; daarvoor is een Firebase-project met Android-appregistratie en FCM-configuratie nodig.

## Stand-by en terugkeren naar waar je was
- Stand-by gebruikt Android Device Admin `DevicePolicyManager.lockNow()`.
- Android toont daarna het normale beveiligde vergrendelscherm.
- Receiver start bij het wakker maken geen Activity en haalt zichzelf niet naar de voorgrond. Na Android-ontgrendeling hoort Android de app te herstellen die vóór stand-by actief was.
- De wake-functie gebruikt een Android wake-lock om het scherm op te laten lichten zonder een app-Activity te starten. Deze API is verouderd en gedrag kan per Android-versie/fabrikant verschillen; echte wake-test is vereist.
- Als Android of de fabrikant de foreground service stopt of netwerkcontrole uitstelt, is remote wake tijdelijk niet beschikbaar.

## YouTube Kids
- Als Receiver zichtbaar is, verwerkt hij de opdracht en opent YouTube Kids.
- Op de achtergrond toont Android een tikbare melding als fallback; apps kunnen niet betrouwbaar vanuit de achtergrond gestart worden.

## Eenmalige instellingen op de tablet
1. Open Receiver en voer appcode `0000` in.
2. Sta Helderheid op afstand toe.
3. Activeer Stand-by op afstand bij Android Device Admin.
4. Zorg dat een normaal Android-patroon, pincode of wachtwoord is ingesteld.
5. Sta Wifi instellen op afstand toe.
6. Laat Receiver online koppelen zodat cloudreferenties in Android Keystore worden opgeslagen.
7. Batterijoptimalisatie uitsluiten is niet meer verplicht in de batterijvriendelijke modus.

## Internet koppelen
1. Open Receiver op de tablet; hij toont de internet-koppelcode.
2. Open Controller > Instellingen op de telefoon.
3. Vul de code in bij Internet-koppelcode en kies Opslaan en verbinden.
4. Telefoon en tablet hoeven niet op dezelfde wifi te zitten.

## Backend en beveiliging
- Supabase project: `nstpzmuimwjbvlvvbrsr` (EU West)
- Edge Function: `volume-remote`, versie 6
- RLS aan op de relevante tabellen.
- Service/secret key staat niet in de APK of openbare repository.
- Receiver bewaart de device-identiteit en het geheim versleuteld met Android Keystore (AES-GCM).

## Bouwen
```bash
flutter pub get
flutter test
flutter build apk --release --flavor receiver --dart-define=APP_ROLE=receiver
flutter build apk --release --flavor controller --dart-define=APP_ROLE=controller
```
- Widgettests: geslaagd.
- APK-builds: opnieuw gebouwd.
- Echte remote standby/wake en batterijverbruik moeten op de tablet gemeten worden.