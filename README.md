# Folio

[⬇ Baixar o APK mais recente](https://github.com/natanaelassis24/app/releases/latest/download/Folio-v1.9-debug.apk)

> O APK de download é publicado na última *release* do projeto. O modelo de IA não é incluído no APK, para manter o aplicativo leve e permitir atualizações separadas.

Aplicativo Android para leitura de novelas na web, com narração neural offline.

## Leitura do site

O botão de volume lê os parágrafos originais da página, sem resumir ou reescrever a história. O Folio mantém o site visível, destaca o trecho narrado e acompanha a rolagem em blocos pequenos. Menus, anúncios, comentários, botões e recomendações são descartados por regras locais baseadas na estrutura da página; esse fluxo não carrega nem pergunta ao modelo de IA. Conteúdo dentro de iframes de outros domínios não pode ser acessado pelo navegador incorporado.

## Retomada

O último ponto de leitura fica salvo no aparelho com a URL, o parágrafo atual e a posição de rolagem. Ao abrir o app novamente, a página é restaurada; toque no botão de volume para continuar a partir do trecho salvo. Ao concluir uma leitura naturalmente, esse ponto é removido.

## Requisitos

- JDK 21.
- Android SDK com a plataforma Android 35 (`compileSdk 35`).
- Android Studio recente ou o Gradle Wrapper incluído no projeto.

Na primeira compilação, o Gradle precisa de acesso à internet para obter dependências que ainda não estejam em cache.

## Como compilar

No Windows, execute no diretório raiz:

```powershell
.\gradlew.bat assembleDebug
```

O APK de depuração será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

Em macOS ou Linux, use `./gradlew assembleDebug`.

## Licença

O Folio é software proprietário de **Propriedade Digital Privada**. Não é
licenciado sob MIT nem sob outra licença de código aberto; cópia, modificação,
redistribuição e uso comercial exigem autorização expressa. Consulte
[`LICENSE`](LICENSE).

## Narração offline

Escolha o idioma no seletor antes de tocar no volume. Português usa a voz neural local Supertonic 3 M5, masculina e configurada para narração, com funcionamento offline. Inglês, espanhol e francês usam a voz correspondente instalada no celular, sem traduzir ou reescrever o texto do site. Caso uma voz não esteja disponível, instale-a nas configurações Android de **Texto para fala**.

A voz neural depende dos arquivos em `app/src/main/assets/tts/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/`. Preserve a estrutura desse diretório ao trocar ou atualizar o modelo de voz. Esses arquivos podem ser grandes e entram no APK quando presentes nos assets.

O modelo Supertonic 3 incluído nesse diretório é distribuído sob a licença OpenRAIL-M. O arquivo `LICENSE` que acompanha o modelo deve permanecer junto aos assets em qualquer redistribuição.
