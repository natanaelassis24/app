# Folio

<p align="center">
  <a href="https://github.com/natanaelassis24/app/releases/download/v2.00/Folio-v2.00-debug.apk"><img src="https://img.shields.io/badge/Folio%202.00-Baixar%20para%20Android-181818?style=for-the-badge&logo=android&logoColor=white" alt="Baixar Folio 2.00 para Android" /></a>
  <a href="https://github.com/natanaelassis24/app/releases"><img src="https://img.shields.io/badge/Ver%20releases-GitHub-58A6FF?style=for-the-badge&logo=github&logoColor=white" alt="Ver releases do Folio" /></a>
</p>

> Leitor Android para novelas na web, com narração offline, retomada de leitura e IA local opcional.

## Baixar Folio 2.00

[**⬇ Baixar o APK para Android**](https://github.com/natanaelassis24/app/releases/download/v2.00/Folio-v2.00-debug.apk) · [Ver todas as versões](https://github.com/natanaelassis24/app/releases)

O APK não inclui o modelo de IA, mantendo o download inicial menor. A IA pode ser baixada separadamente dentro do aplicativo.

> **Instalação:** este é um APK de depuração. Se o Android informar que a assinatura é incompatível com uma versão anterior, desinstale a versão antiga antes de instalar a 2.00.

## Principais recursos

- Leitura de novelas diretamente em sites HTTPS.
- Narração dos parágrafos originais, sem resumir ou alterar a história.
- Controles de idioma e velocidade de fala.
- Destaque do trecho narrado e rolagem acompanhando a leitura.
- Retomada do ponto de leitura e card com os últimos sites acessados.
- Filtro local de menus, anúncios, comentários e outros elementos que atrapalham a leitura.
- IA local opcional para identificar obras e traduzir páginas.

## IA local opcional

Na tela inicial, toque em “Gerenciar IA local” para abrir o gerenciador da IA. O Folio baixa, mediante confirmação, o modelo Qwen3 0.6B quantizado (~328 MB) para o armazenamento privado do aplicativo. O download mostra progresso, pode ser cancelado ou removido, e só é ativado após verificação de tamanho e SHA-256.

A IA funciona offline depois do download e está disponível em aparelhos Android ARM64. Para reduzir consumo de memória, ela pode ser desativada sem apagar o arquivo do modelo.

## Leitura do site

O botão de volume lê os parágrafos originais da página, sem resumir ou reescrever a história. O Folio mantém o site visível, destaca o trecho narrado e acompanha a rolagem em blocos pequenos. Menus, anúncios, comentários, botões e recomendações são descartados por regras locais baseadas na estrutura da página; esse fluxo não carrega nem pergunta ao modelo de IA. Conteúdo dentro de iframes de outros domínios não pode ser acessado pelo navegador incorporado.

## Retomada

O último ponto de leitura fica salvo no aparelho com a URL, o parágrafo atual e a posição de rolagem. Ao abrir o app novamente, a tela inicial mostra os últimos sites acessados; toque no card desejado e depois no botão de volume para continuar a partir do trecho salvo. Ao concluir uma leitura naturalmente, esse ponto é removido.

## Requisitos

- JDK 21.
- Android SDK com a plataforma Android 35 (`compileSdk 35`).
- Android Studio recente ou o Gradle Wrapper incluído no projeto.

Na primeira compilação, o Gradle precisa de acesso à internet para obter dependências que ainda não estejam em cache.

## Como instalar

1. Baixe o APK pelo botão no topo desta página.
2. Abra o arquivo no Android.
3. Se necessário, permita a instalação por essa fonte nas configurações do sistema.
4. Abra o Folio e escolha um site para começar a leitura.

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
