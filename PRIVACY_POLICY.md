# Política de Privacidade do Folio

**Última atualização:** 30 de agosto de 2026  
**Versão do documento:** 1.0

**English version:** [Privacy Policy](PRIVACY_POLICY_EN.md)

Esta Política de Privacidade explica como o **Folio** acessa, usa, armazena e compartilha dados ao funcionar como leitor de páginas da web e PDFs com narração local. Ela se aplica ao aplicativo Android Folio e complementa os [Termos de Uso](TERMS_OF_USE.md).

## 1. Identificação e contato

O aplicativo é identificado como **Folio** e é disponibilizado pelo titular indicado na sua listagem do Google Play. Para dúvidas, solicitações ou comentários sobre privacidade, use a página de suporte do projeto:

<https://github.com/natanaelassis24/app/issues>

Esse canal é público: não envie nele documentos, URLs privadas, credenciais ou outros dados pessoais. Antes da publicação no Google Play, o desenvolvedor deve também configurar o canal de contato exibido na listagem da loja.

## 2. Dados que o Folio acessa e guarda no aparelho

O Folio não cria conta de usuário e não possui servidor próprio para receber o conteúdo lido. Para entregar seus recursos, ele pode tratar localmente:

| Dado | Finalidade | Onde fica |
| --- | --- | --- |
| Tema, idioma, velocidade e estado da voz | Manter suas preferências de leitura | Preferências privadas do aplicativo |
| Até quatro últimos sites acessados | Exibir atalhos na tela inicial | Preferências privadas do aplicativo |
| URL da leitura, posição e chave técnica do trecho | Retomar a leitura de onde você parou | Preferências privadas do aplicativo |
| PDF selecionado e nome do arquivo | Extrair e apresentar o texto do PDF escolhido | Processado localmente; a permissão de leitura do URI pode permanecer no aparelho |
| Trechos temporários da narração | Permitir a reprodução em segundo plano | Cache privado do aplicativo, removido normalmente ao parar ou concluir a narração |
| Pacote de voz local | Gerar fala no aparelho | Armazenamento privado do aplicativo, depois de download opcional |
| Cache, cookies de primeira parte e armazenamento web | Exibir os sites que você escolhe no navegador incorporado | Armazenamento do WebView no aparelho |

O Folio não pede acesso a câmera, microfone, localização, contatos, SMS, armazenamento amplo ou identificador de publicidade.

## 3. PDFs e conteúdo de leitura

O PDF é escolhido por você no seletor de documentos do Android. O Folio lê a camada de texto localmente e não envia o PDF nem o texto extraído para um servidor do Folio. Para permitir a leitura do arquivo selecionado, o Android pode conceder uma permissão de leitura ao URI do documento; essa permissão pode persistir enquanto os dados do aplicativo existirem.

As URLs recentes e o ponto de leitura também ficam no aparelho para possibilitar retomada. A URL de retomada pode conter parâmetros presentes no endereço que você abriu; por isso, não use o recurso com links que contenham informações confidenciais.

## 4. Navegação em sites de terceiros

Quando você abre um site ou faz uma busca pelo aplicativo, seu aparelho se conecta diretamente ao destino escolhido. Esse site, o provedor de internet e eventuais mecanismos de busca podem receber dados técnicos normais de navegação, como endereço IP, cabeçalhos e a consulta digitada, de acordo com suas próprias políticas.

O Folio permite somente páginas HTTPS, bloqueia conteúdo misto, desativa geolocalização, bloqueia cookies de terceiros e usa Navegação Segura do Android quando disponível. Ainda assim, cookies de primeira parte, cache e armazenamento local do próprio site podem ser mantidos pelo WebView.

## 5. Download opcional da voz

O pacote de voz neural é baixado somente depois que você toca para baixar e confirma a ação. O download é obtido do repositório público Sherpa-ONNX hospedado no GitHub. Esse fornecedor pode receber informações técnicas necessárias à conexão, como endereço IP e identificador da versão do aplicativo.

O Folio confere a integridade do pacote com SHA-256 e o guarda no armazenamento privado. Após a instalação, texto e áudio são processados localmente, sem uso do TTS padrão do Google e sem API externa de fala.

## 6. Compartilhamento de dados

O Folio não vende, aluga ou compartilha conteúdo de leitura, PDFs, preferências ou áudio com um servidor próprio. Não inclui SDK de anúncios, analytics ou relatório de falhas.

As conexões necessárias ao uso são as que você inicia: páginas da web e buscas escolhidas por você, além do download opcional do pacote de voz. Esses serviços de terceiros tratam dados segundo suas próprias políticas.

## 7. Retenção e exclusão

Os dados locais permanecem até que sejam substituídos, apagados pelas rotinas do aplicativo, removidos pelo sistema ou eliminados por você. Para apagar preferências, recentes, ponto de leitura, cache do aplicativo e a voz local, acesse:

**Configurações do Android → Apps → Folio → Armazenamento → Limpar dados**.

Desinstalar o aplicativo também remove seus dados privados e o pacote de voz guardado pelo Folio. A exclusão não apaga registros mantidos por sites externos, mecanismos de busca ou pelo fornecedor do download; para isso, consulte as políticas desses serviços.

## 8. Segurança

O Folio usa conexões HTTPS, não permite tráfego HTTP em texto claro, desabilita backup automático de dados do aplicativo e mantém o serviço de narração indisponível para outros aplicativos. Nenhum método de armazenamento ou transmissão é absolutamente infalível; mantenha o sistema Android atualizado e evite abrir sites ou PDFs de origem não confiável.

## 9. Crianças

O Folio não é direcionado especificamente a crianças e não solicita intencionalmente dados pessoais. A pessoa responsável pelo aparelho deve supervisionar o conteúdo de sites e PDFs acessados por menores.

## 10. Alterações nesta Política

Esta Política pode ser atualizada quando os recursos ou práticas de dados do Folio mudarem. Alterações relevantes serão refletidas no aplicativo e, quando necessário, exigirão novo aceite.

## 11. Requisitos do Google Play

Para publicar o Folio no Google Play, esta Política deve ser disponibilizada em uma URL pública, ativa e não geobloqueada e informada também na Play Console. A declaração de **Segurança dos dados** na Play Console deve refletir exatamente esta Política, as permissões do app e os componentes de terceiros utilizados.
