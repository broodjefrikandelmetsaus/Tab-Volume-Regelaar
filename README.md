TAB VOLUME REGELAAR
Versie 1.0.7 (build 8)

Twee Android-apps:
- Volume Receiver op de tablet.
- Volume Controller op de telefoon.

FUNCTIES
- Volume en helderheid op afstand regelen.
- Tablet op afstand in stand-by zetten en wakker maken.
- Wifi-netwerksuggestie op afstand toevoegen.
- YouTube Kids automatisch openen, ook als Receiver op de achtergrond staat (na eenmalige Android-overlaytoestemming).
- Kijkvergrendeling: blokkeert aanrakingen terwijl video blijft afspelen; via Controller weer uit te zetten.
- Supabase Realtime/WebSocket met polling als fallback.

UPDATE 7
- Achtergrondstart van YouTube Kids toegevoegd via een korte zichtbare Android-overlay. Geen aparte YouTube Kids-notificatie; de vaste Receiver-foregroundservice-melding blijft.
- Edge Function v9 accepteert openKids, screenLock en screenUnlock.
- Kijkvergrendeling gebruikt een transparante full-screen overlay en slaat de status op.
- Kijkvergrendeling is geen volledige kioskmodus. Android-systeemgebaren en fysieke knoppen kunnen nog steeds systeemnavigatie activeren. Voor echte kioskmodus is Device Owner / Lock Task Mode nodig.
- De nieuwe achtergrondopenfunctie en kijkvergrendeling moeten nog op de fysieke tablet worden getest.

EENMALIGE INSTELLINGEN
1. Open Receiver op de tablet (app-pincode 0000).
2. Geef de gevraagde Android-toestemmingen voor helderheid, stand-by en wifi.
3. Sta "Weergeven over andere apps" toe voor YouTube Kids automatisch openen en kijkvergrendeling.
4. Laat Receiver online koppelen; dezelfde 6-cijferige code wordt gebruikt.

BOUWEN
flutter pub get
flutter test
flutter build apk --release --flavor receiver --dart-define=APP_ROLE=receiver
flutter build apk --release --flavor controller --dart-define=APP_ROLE=controller

BELANGRIJK: geef bij elke build expliciet APP_ROLE mee. Zonder deze define valt de app terug op de standaardrol Controller, waardoor de Receiver ook het Controller-scherm kan tonen.

Cloudfunctie: volume-remote v9.
