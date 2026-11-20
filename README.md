# Tab Volume Regelaar — versie 1.0.2 (build 3)

Twee Android-apps:
- **Volume Receiver** op de tablet: draait als foreground-service, ook wanneer de app-UI op de achtergrond staat.
- **Volume Controller** op de telefoon: bedient de tablet via de Supabase-cloudrelay.

## Controller
- Twee verticale regelaars tegelijk: links **helderheid**, rechts **volume**, met ieder een eigen percentage.
- Brede **YouTube Kids**-snelkoppeling met app-logo.
- **Stand-by / Wakker maken** met bevestigingsvenster.
- Wifi-hulp: telefoon scant wifi-netwerken, kiest netwerk/wachtwoord en verstuurt dit via de cloudrelay.

## Stand-by en terugkeren naar waar je was
- Stand-by gebruikt Android Device Admin `DevicePolicyManager.lockNow()`.
- Android toont daarna zijn eigen beveiligde vergrendelscherm. De gebruiker ontgrendelt met het normale tabletpatroon, de normale pincode of het wachtwoord.
- Receiver start bij het wakker maken **geen Activity** en haalt zichzelf niet naar de voorgrond. Na de Android-ontgrendeling hoort Android dus de app te herstellen die vóór stand-by actief was (bijvoorbeeld YouTube Kids, browser of een andere app).
- De extra Receiver-appcode `0000` wordt niet opnieuw gevraagd zodra Receiver eenmaal ontgrendeld is. Alleen de expliciete Receiver-afsluitactie reset deze app-gate.
- Tijdens remote stand-by houdt de native foreground-service tijdelijk een CPU partial wake lock en wifi-lock vast en pollt Supabase ongeveer elke 6 seconden. Dit kost extra batterij.
- Voor betrouwbare achtergrondwerking moet in Receiver **Achtergrond-wakeverbinding** worden toegestaan zodat Android batterijoptimalisatie voor deze app uitsluit. De gebruiker kan dit ook later weer intrekken.
- De wake-functie gebruikt de Android wake-lock vlag `ACQUIRE_CAUSES_WAKEUP` om het scherm te laten oplichten zonder een app-Activity te starten. Deze API is verouderd en gedrag kan per Android-versie/fabrikant verschillen; daarom moet het echte wakker maken nog op de doeltablet worden getest.
- Als Android, de fabrikant, de batterij of de netwerkverbinding de service toch stopt, is remote wake tijdelijk niet beschikbaar. Start Receiver opnieuw en controleer de achtergrondtoestemming.

## YouTube Kids
- Als Receiver zichtbaar is op de tablet, verwerkt de app de opdracht en opent YouTube Kids rechtstreeks.
- Staat Receiver op de achtergrond, dan beperkt Android het automatisch starten van een andere app. In dat geval plaatst de Receiver een melding **YouTube Kids openen** op de tablet; tik daarop om YouTube Kids te openen.
- Wanneer YouTube Kids niet geïnstalleerd is, opent de snelkoppeling de Play Store-pagina.

## Eenmalige instellingen op de tablet
1. Open Receiver en voer de appcode `0000` in.
2. Sta **Helderheid op afstand** toe in Android-instellingen.
3. Activeer **Stand-by op afstand** bij Android Device Admin.
4. Zorg dat een Android-patroon, pincode of wachtwoord is ingesteld.
5. Sta **Wifi instellen op afstand** toe.
6. Sta **Achtergrond-wakeverbinding** toe (batterijoptimalisatie uitsluiten).
7. Laat Receiver één keer online koppelen zodat de cloudreferenties in Android Keystore worden opgeslagen.

## Internet koppelen
1. Open Receiver op de tablet; hij toont de zescijferige internet-koppelcode.
2. Open Controller > Instellingen op de telefoon.
3. Vul de code in bij **Internet-koppelcode** en kies **Opslaan en verbinden**.
4. De code verloopt na 10 minuten. Telefoon en tablet hoeven niet op dezelfde wifi te zitten.

## Wifi op afstand
- De telefoon scant de zichtbare netwerken en verstuurt SSID/wachtwoord via een geauthenticeerde Supabase-opdracht.
- De tablet voegt het netwerk toe als Android Wifi Network Suggestion.
- Android kan op de tablet nog bevestiging tonen en kiest zelf wanneer de suggestie wordt gebruikt; een stille, gegarandeerde onmiddellijke omschakeling is niet mogelijk met een gewone app.
- Het wifi-wachtwoord wordt na ontvangst/acknowledgement uit de serverwachtrij gewist.

## Backend en beveiliging
- Supabase project: `nstpzmuimwjbvlvvbrsr` (EU West)
- Edge Function: `volume-remote`, versie 6
- De Edge Function beheert pairing, controller-autorisatie, opdrachten, wake en YouTube Kids-fallback.
- Database-tabellen hebben RLS aan; de service/secret key staat niet in de APK of openbare repository.
- De Receiver bewaart de achtergrond-device-identiteit en het device secret versleuteld met Android Keystore (AES-GCM).

## Bouwen en controles
```bash
flutter pub get
flutter test
flutter build apk --release --flavor receiver --dart-define=APP_ROLE=receiver
flutter build apk --release --flavor controller --dart-define=APP_ROLE=controller
```
- Widgettests: geslaagd.
- Receiver en Controller release-APK's: gebouwd.
- APK-handtekeningen: worden voor distributie opnieuw gecontroleerd.
- Er waren geen fysieke Android-apparaten via ADB aangesloten; remote standby/wake en herstel van de vorige app moeten nog op de echte tablet worden getest.