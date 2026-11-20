# Tab Volume Regelaar — testbuild 1.0.1

Flutter Android-app met twee APK's: **Volume Receiver** (tablet) en **Volume Controller** (telefoon).

## Functies
- Twee gelijktijdige verticale regelaars op de telefoon: links helderheid, rechts mediavolume.
- Beide regelaars hebben dezelfde stijl en tonen hun eigen percentage (0–100%).
- Stand-by via Android Device Admin: scherm uit en tablet vergrendeld, vergelijkbaar met één korte druk op de fysieke aan/uit-knop. Geen volledige power-off.
- Wifi-hulp: telefoon scant netwerken, kiest SSID en wachtwoord, verstuurt dit via de beveiligde cloudrelay; tablet voegt een Android Wifi Network Suggestion toe.
- YouTube Kids-snelkoppeling met echt app-icoon wanneer de app op de tablet aanwezig is.
- Lokale bediening via TCP en cloudbediening via Supabase, dus telefoon en tablet hoeven niet op hetzelfde netwerk te zitten.

## Internet koppelen
1. Open Volume Receiver op de tablet en ontgrendel met de viercijferige app-PIN.
2. Laat Volume Receiver actief; de tablet toont een zescijferige internet-koppelcode.
3. Open Instellingen in Volume Controller, vul de code in bij **Internet-koppelcode** en kies **Opslaan en verbinden**.
4. De koppelcode verloopt na 10 minuten. Het apparaat- en controllergeheim worden veilig op de apparaten bewaard.

## Eenmalige toestemmingen
Op de tablet:
- Sta **Helderheid op afstand** toe via Android-instellingen.
- Activeer **Stand-by op afstand** bij Android Device Admin.
- Sta **Wifi instellen op afstand** toe als Android daarom vraagt.

Op de telefoon:
- Sta de gevraagde locatietoegang toe om beschikbare wifi-netwerken te kunnen scannen. Android beperkt wifi-scans afhankelijk van versie en systeeminstellingen.

## Wifi instellen
- De telefoon scant de netwerken die op dat moment in de buurt van de telefoon zichtbaar zijn.
- Kies het netwerk, voer het wachtwoord in en verstuur het naar de tablet.
- De tablet moet online zijn om het verzoek via de cloud te ontvangen.
- Android gebruikt een wifi-netwerksuggestie. Android kan een melding/toestemmingsscherm tonen en beslist zelf wanneer er verbinding mee wordt gemaakt. Dit forceert dus geen gegarandeerde onmiddellijke of stille netwerkovergang.
- De wifi-gegevens worden uit de serverwachtrij gewist zodra de tablet de opdracht bevestigt.

## Stand-by
De knop **Stand-by** vraagt eerst om bevestiging en gebruikt Android Device Admin om het scherm uit te zetten en het apparaat te vergrendelen. Zet de tablet daarna met de fysieke aan/uit-knop weer aan en ontgrendel hem normaal.

## Belangrijke testbeperking
De cloudpolling loopt vanuit de geopende Receiver-app. Houd de Receiver actief op de tablet; Android kan netwerkcommando's vertragen wanneer de app op de achtergrond staat of het apparaat in diepe slaap is. Stand-by is bedoeld om het scherm uit te zetten, niet om de tablet op afstand weer wakker te maken.

## Tests
- `flutter test`: geslaagd.
- Receiver release-APK en Controller release-APK: gebouwd.
- Beide APK-handtekeningen: geverifieerd.
- Er waren geen fysieke Android-apparaten via ADB aangesloten; bediening op echte apparaten moet nog samen getest worden.

## Supabase
- Project: `nstpzmuimwjbvlvvbrsr` (EU West)
- Edge Function: `volume-remote` (versie 5)
- Database-tabellen gebruiken RLS; geheime serverkey staat niet in de mobiele app.