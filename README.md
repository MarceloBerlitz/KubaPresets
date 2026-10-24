# Kuba Presets (Android)

App Android que carrega https://equalizador.kuba.audio/ em um WebView e adiciona uma ponte
WebHID/WebUSB -> Android USB Host. O site continua sendo o dono de toda a lógica de DSP/presets;
o app só repassa os bytes ao fone via USB (OTG).

## Como compilar
1. Abra a pasta no Android Studio (Koala ou mais novo) e aguarde o Gradle Sync.
2. Conecte o celular (depuração USB) e clique em Run, ou Build > Build APK(s).

## Uso
1. Conecte o fone ao celular (cabo/adaptador USB-C OTG).
2. Abra o app, use o botão de conectar do site e aceite a permissão USB do Android.

## Depuração
- Logcat, filtro `KubaBridge`: mostra cada chamada USB feita pelo site.
- Em builds debug, `chrome://inspect` no PC permite ver o console do site.
