# Tab Volume Regelaar

Flutter Android-app met twee APK's: **Volume Receiver** (tablet) en **Volume Controller** (telefoon).

## Functies
- Media volume en schermhelderheid op afstand regelen.
- Tablet op afstand vergrendelen en scherm uitschakelen (geen volledige Android power-off).
- YouTube Kids-snelkoppeling met het echte app-icoon als YouTube Kids is geïnstalleerd.
- Lokale TCP-bediening op hetzelfde netwerk.
- Cloudrelay via Supabase voor bediening vanaf een ander wifi-netwerk of mobiel internet.

## Cloudverbinding
1. Open Volume Receiver op de tablet en ontgrendel met de ingestelde receiver-PIN.
2. De tablet registreert zichzelf automatisch en toont een zescijferige code.
3. Open Instellingen in Volume Controller, vul de code in bij **Internet-koppelcode** en kies **Opslaan en verbinden**.
4. De code is 10 minuten geldig. Na koppelen wordt een willekeurig controllergeheim veilig op de telefoon opgeslagen.

De cloudfunctie gebruikt een Supabase Edge Function. De client bevat alleen de **publishable key** (bedoeld voor clients); de Supabase secret/service-role key staat uitsluitend in de Edge Function-omgeving. Apparaat- en controllergeheimen worden als SHA-256-hash opgeslagen. De tabellen hebben RLS aan en geven geen directe API-toegang aan anon/authenticated.

## Belangrijke beperkingen
- De tablet moet Volume Receiver geopend en actief houden om de huidige cloudpolling te laten draaien. Als Android de app volledig naar de achtergrond zet of beëindigt, pauzeren cloudcommando's totdat Receiver weer actief is.
- Android vereist toestemming op de tablet voor helderheidsaanpassing en Device Admin voor vergrendelen.
- **Volledig op afstand wifi-netwerken instellen en het wachtwoord stil invoeren is nog niet ondersteund.** Android vereist systeembevestiging/extra device-owner-rechten voor het toevoegen van wifi-netwerken. De bestaande knop opent de wifi-instellingen op de tablet.
- Vergrendelen is geen volledige uitschakeling van de tablet.

## Bouwen
```bash
flutter pub get
flutter test
flutter build apk --release --flavor receiver --dart-define=APP_ROLE=receiver
flutter build apk --release --flavor controller --dart-define=APP_ROLE=controller
```

## Backend
- Supabase project: `nstpzmuimwjbvlvvbrsr` (EU West)
- Edge Function: `volume-remote`
- Database migrations: `supabase/migrations/`
