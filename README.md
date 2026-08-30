# Folio

<p align="center">
  <a href="https://github.com/natanaelassis24/app/releases/download/v2.01/Folio-v2.01-debug.apk"><img src="https://img.shields.io/badge/Folio%202.01-Baixar%20para%20Android-181818?style=for-the-badge&logo=android&logoColor=white" alt="Baixar Folio 2.01 para Android" /></a>
  <a href="https://github.com/natanaelassis24/app/releases"><img src="https://img.shields.io/badge/Ver%20releases-GitHub-58A6FF?style=for-the-badge&logo=github&logoColor=white" alt="Ver releases do Folio" /></a>
</p>

> Leitor Android para novelas na web e PDFs, com voz offline, retomada de leitura e privacidade local.

## Baixar Folio 2.01

[**⬇ Baixar o APK para Android**](https://github.com/natanaelassis24/app/releases/download/v2.01/Folio-v2.01-debug.apk) · [Ver todas as versões](https://github.com/natanaelassis24/app/releases)

> **Instalação:** este é um APK de depuração. Se o Android informar que a assinatura é incompatível com uma versão anterior, desinstale a versão antiga antes de instalar a 2.01.

## Build atual

**Folio 2.01 — build 12**

| Plataforma | Android 8.0 ou superior |
| --- | --- |
| Versão do app | 2.01 (`versionCode 12`) |
| Vozes disponíveis | Português e Inglês |
| Processamento | Local, no próprio aparelho |

## Novidades desta build

### Adicionado

- Narração em segundo plano com controles de **pausar**, **retomar** e **parar** na notificação e na tela de bloqueio.
- Voz neural local sob demanda: o motor fica no app e a voz é baixada uma única vez para uso offline.
- Seletor de voz em cartões separados para Português e Inglês, além do seletor de velocidade.
- Leitura de PDFs no formato de e-book: capítulos, parágrafos, separadores de cena, recuo de primeira linha e texto justificado.
- Card dos últimos sites acessados na tela inicial e retomada do ponto de leitura.

### Melhorado

- PDFs não são mais exibidos como linhas soltas: o Folio recompõe parágrafos, remove cabeçalhos, rodapés e números de página repetidos e corrige hifens de quebra de linha.
- A narração de PDF é entregue em blocos de texto, deixando a leitura contínua e sem pausas desnecessárias entre páginas.
- Tema claro e escuro em preto e branco, com controles e seletores mais consistentes.

### Removido

- IA local, tradução automática e identificação automática de idioma do texto.
- Modelo Qwen e arquivos antigos de tradução.
- Dependência do TTS padrão do Google/Android: a narração usa a voz neural local do Folio.
- Outros idiomas de voz: esta versão oferece apenas Português e Inglês para manter o app mais leve.
- Pesos grandes da voz dentro do APK; agora são baixados somente quando a pessoa escolhe usar a voz offline.

## Principais recursos

- Leitura de novelas diretamente em sites HTTPS.
- Narração dos parágrafos originais, sem resumir, alterar ou traduzir a história.
- Controles de idioma da voz e velocidade de fala.
- Destaque do trecho narrado e rolagem acompanhando a leitura.
- Retomada do ponto de leitura e card com os últimos sites acessados.
- Abertura local de PDFs com texto selecionável, formatados para leitura em voz alta.
- Narração contínua em segundo plano pela notificação e tela de bloqueio.
- Filtro local de menus, anúncios, comentários e outros elementos que atrapalham a leitura.

## Leitura do site

Abra um site HTTPS e toque no botão de volume. O Folio encontra os parágrafos da história e os entrega diretamente ao TTS, preservando a retomada, o destaque no site e o carregamento contínuo de capítulos longos. Menus, anúncios, comentários, botões e recomendações são descartados por regras locais baseadas na estrutura da página; conteúdo dentro de iframes de outros domínios não pode ser acessado pelo navegador incorporado.

## Retomada

O último ponto de leitura fica salvo no aparelho com a URL, o parágrafo atual e a posição de rolagem. Ao abrir o app novamente, a tela inicial mostra os últimos sites acessados; toque no card desejado e depois no botão de volume para continuar a partir do trecho salvo. Ao concluir uma leitura naturalmente, esse ponto é removido.

## Narração em segundo plano

Depois de iniciar a leitura, o Folio pode permanecer em segundo plano. A notificação e a tela de bloqueio exibem controles para pausar, retomar ou parar a narração. O áudio é tratado como mídia, para que os controles do sistema também acompanhem a leitura.

## PDFs

O leitor transforma a camada de texto do PDF em uma leitura parecida com e-book: recompõe parágrafos, reconhece títulos curtos de capítulo, preserva separadores de cena e remove cabeçalhos, rodapés e números de página repetidos. A formatação visual usa texto justificado e recuo na primeira linha; a voz recebe blocos de parágrafos para não interromper a leitura a cada página.

Na tela inicial, toque em **Abrir um PDF** e escolha um arquivo do celular. O Folio lê a camada de texto do documento localmente, sem enviar o PDF à internet, e deixa o conteúdo pronto para ser ouvido pelo botão **Ouvir**. PDFs protegidos por senha, corrompidos ou feitos apenas de imagens ainda não podem ser lidos; o reconhecimento OCR de documentos escaneados será incluído em uma próxima etapa. Para proteger o celular, o app aceita arquivos de até 50 MB e carrega até 120 páginas por vez.

## Voz local sob demanda

Escolha o idioma da voz e a velocidade antes de tocar no volume. O Folio não traduz nem identifica automaticamente o idioma do conteúdo: o seletor informa à voz local como pronunciar o texto original.

O motor Sherpa-ONNX continua incluído no APK, mas os pesos pesados da voz não. Ao pedir uma voz pela primeira vez, o Folio mostra a confirmação e baixa o pacote local Supertonic M5, confere o tamanho e SHA-256 de todos os arquivos e o guarda no armazenamento privado do app. O pacote tem cerca de 139 MB e é baixado uma única vez; ele atende **Português** e **Inglês**, pois o modelo M5 é multilíngue. Depois da instalação, a fala é gerada dentro do celular, sem usar o TTS do Google, sem API externa e sem conexão durante a leitura.

## Requisitos

- JDK 21.
- Android SDK com a plataforma Android 35 (`compileSdk 35`).
- Android Studio recente ou o Gradle Wrapper incluído no projeto.

Na primeira compilação, o Gradle precisa de acesso à internet para obter dependências que ainda não estejam em cache.

Para baixar a voz local pela primeira vez, o aplicativo também precisa de conexão e de aproximadamente 390 MB livres temporariamente. Após a conferência e a instalação, o arquivo temporário é removido.

## Como instalar

1. Baixe o APK pelo botão no topo desta página.
2. Abra o arquivo no Android.
3. Se necessário, permita a instalação por essa fonte nas configurações do sistema.
4. Abra o Folio e escolha um site ou PDF para começar a leitura.

## Como compilar

No Windows, execute no diretório raiz:

```powershell
.\gradlew.bat assembleDebug
```

O APK de depuração será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

Em macOS ou Linux, use `./gradlew assembleDebug`.

## Licença

O Folio é software proprietário de **Propriedade Digital Privada**. Não é licenciado sob MIT nem sob outra licença de código aberto; cópia, modificação, redistribuição e uso comercial exigem autorização expressa. Consulte [`LICENSE`](LICENSE) e os avisos de componentes incluídos em [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

