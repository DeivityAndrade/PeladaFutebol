# Assistente por voz — direção e plano

Registro de produto em **09/10/2026**, fuso de São Paulo.
**Estado: planejado, ainda não implementado nem publicado.**

O proprietário propôs um microfone no canto do site para pedidos como “marcar
um jogo sexta-feira no Gools no grupo do Corrêa e convidar todo mundo”. Deseja
usar áudio pelo WhatsApp e oferecer essa função como recurso pago futuramente.
Após comparar gravação curta com conversa ao vivo, indicou **preferência por
conversa em tempo real**. Modelo, preço e franquia ainda não foram definidos.

## Experiência no site

1. Usuário autenticado toca no microfone e inicia uma conversa explicitamente.
2. Assistente escuta e responde por voz, com legenda/transcrição na tela.
3. Resolve grupo autorizado, data e local; pergunta horário ou dados ausentes.
4. Apresenta proposta com grupo, data absoluta/fuso, local, times, vagas,
   recorrência, cobrança e intenção de convite.
5. Usuário revisa e confirma; servidor revalida e cria uma vez. Convites entram
   na fila para destinatários elegíveis quando o canal estiver habilitado.
6. Resultado aparece na tela e pode ser falado. Distinguir jogo criado,
   convite agendado, aceitação pela Meta e entrega efetiva.

Exemplo ilustrativo, sem criação ou envio real:

> Usuário: “Marca um jogo sexta no Gools no grupo do Corrêa e convida o pessoal.”
>
> Assistente: “Qual horário?”
>
> Usuário: “Sete e meia da noite.”
>
> Assistente: “Vou preparar o jogo para sexta, às 19h30. Confira a data e o grupo
> no resumo. Deseja criar a partida e convidar os participantes elegíveis?”

Resolver “sexta” no fuso correto e mostrar a data completa; não fixar uma data
no exemplo nem inventar número de convidados. Perguntar quando houver grupos
com nomes semelhantes ou quando a transcrição não resolver Corrêa/Gools.

Na primeira entrega, reaproveitar a confirmação visual explícita das propostas.
Confirmação exclusivamente por voz exige contrato vinculado à proposta/versão;
qualquer “sim” não pode virar autorização genérica para criar ou enviar.

## Interface

- Microfone discreto em áreas autenticadas, sem cobrir navegação, teclado ou
  controles da partida ao vivo no celular.
- Painel aberto pelo usuário; estados de conexão, escuta, fala, processamento,
  revisão, erro, microfone pausado e sessão encerrada.
- Permissão solicitada ao iniciar; controles visíveis para pausar/encerrar.
  Encerrar libera os recursos do microfone.
- Legendas, alternativa por texto, teclado, foco, toque e movimento reduzido.
- Falha de rede/microfone preserva formulário e assistente por texto.
- Inspecionar tela/estilos antes da implementação visual; preservar Angular
  standalone, signals, CSS próprio e identidade atual.

## Arquitetura proposta

O navegador conversa com o provedor de voz. Ferramentas consultam/preparam ações
no servidor do Tô Dentro, que mantém identidade, autorização, confirmação e
regras. O n8n continua com as filas WhatsApp; sua consulta atual a cada minuto
não transportará a conversa ao vivo do site.

A documentação oficial OpenAI descreve agentes de voz e conexão WebRTC para
conversa no navegador. Escolher arquitetura/modelo após conferir acesso da
conta, compatibilidade e consumo; não presumir que o modelo de texto atual
atende à sessão de voz. [Agentes de voz](https://developers.openai.com/api/docs/guides/voice-agents),
[WebRTC](https://developers.openai.com/api/docs/guides/voice-webrtc).

- Sessão criada/autorizada pelo servidor; chave permanente e configuração
  protegidas, sem chave do projeto no Angular/Git.
- Reutilizar `Assistant`, propostas versionadas e `Games`. Identidade vem da
  sessão; a IA não escolhe usuário ou concede permissão.
- Resolver grupos autorizados globalmente, pois o assistente atual parte de um
  grupo selecionado. Consultas e preparação usam ferramentas restritas.
- Acrescentar a opção “criar e convidar” separando intenção, elegibilidade e
  entrega; voz não elimina as autorizações individuais.
- “Todo mundo” corresponde a membros elegíveis com vínculo/autorização, sujeito
  aos limites do piloto. Não ampliar destinatários automaticamente.
- Conferir política de conteúdo e acrescentar somente origens necessárias ao
  transporte escolhido, preservando a política restritiva do aplicativo.

## WhatsApp por áudio

Primeira evolução: mensagens de áudio recebidas, mídia obtida de forma
autorizada, transcrição, interpretação pelo fluxo existente e resposta com
confirmação da proposta. Isso não é uma ligação ao vivo pelo WhatsApp.

Hoje a integração trata texto e botões; áudio/recuperação de mídia precisam de
implementação. Validar formatos/limites da Meta e do provedor antes de habilitar.
Não registrar token, URL autenticada de mídia ou áudio bruto nas execuções n8n.

Transcrever arquivo e manter sessão ao vivo são fluxos diferentes, conforme a
[documentação oficial OpenAI](https://developers.openai.com/api/docs/guides/transcription).
Ligações pelo WhatsApp, se desejadas, exigem estudo próprio de API, elegibilidade,
disponibilidade e custos; não estão prometidas nesta primeira evolução.

## Oferta paga — proposta para decisão posterior

Recomenda-se assinatura do organizador/grupo com franquia de uso do assistente.
Definir preço, franquia, pagador e inclusão do WhatsApp após medir o piloto.
Nenhuma cobrança/contratação foi feita nesta etapa.

- Validar acesso pelo plano no servidor, inclusive pelo WhatsApp; esconder botão
  não protege API. Assinatura comercial é diferente das cobranças das peladas.
- Medir voz, interpretação e mensagens separadamente; limite por sessão,
  inatividade, consumo acumulado e custo global.
- Encerrar sessão abandonada; reconexão não duplica proposta, jogo ou convite.
- Mostrar franquia/limite e impedir excesso não contratado; não assumir uso
  ilimitado nem cobrar excedente automaticamente.
- Recomenda-se preservar presença e recursos básicos do site aos participantes.
- Pagamentos, cancelamento, acesso por plano e eventos do provedor financeiro
  terão implementação/testes próprios. Preço ainda não escolhido.

## Ordem de entrega

### A. Concluir o piloto de conversa WhatsApp

- [ ] Implementar modo somente de respostas para testar sem modelos aprovados;
  essa opção foi identificada, mas ainda não implementada.
- [ ] Validar consulta, presença e criação com ida e volta real.
- [ ] Validar convites/lembretes quando os respectivos modelos estiverem prontos.

### B. Voz em tempo real no site — preferência atual

- [ ] Escolher transporte/modelo e limites após prova de acesso/consumo.
- [ ] Implementar sessão autorizada, painel e ferramentas de grupos/jogos.
- [ ] Preparar propostas na conversa e reaproveitar confirmação visual.
- [ ] Acrescentar opção explícita de convite e resultado verdadeiro do servidor.
- [ ] Testar ruído, nomes, datas relativas, interrupção, correção, reconexão,
  expiração, usuário sem permissão, microfone negado e confirmação repetida.
- [ ] Conferir consumo, encerramento, celular e acessibilidade.

**Concluída quando:** organizador conversa, corrige, revisa e cria uma vez;
usuário comum não cria; resultados correspondem ao estado real.

### C. Mensagens de áudio WhatsApp

- [ ] Implementar mídia/transcrição com limites e retenção definidos.
- [ ] Usar o mesmo fluxo de ferramentas/propostas para texto transcrito.
- [ ] Testar áudio real, informação ausente, repetição e falha do provedor.

**Concluída quando:** áudio produz a mesma operação autorizada do texto, com
esclarecimento/confirmação e sem duplicação ou dados privados nos logs.

### D. Oferta paga

- [ ] Medir consumo e decidir preço/franquia com o proprietário.
- [ ] Implementar autorização por plano, quotas e informação de uso.
- [ ] Definir pagamentos, ativação/cancelamento e testes de eventos.
- [ ] Garantir hospedagem contínua, custos e recuperação antes de ampliar.

**Concluída quando:** acesso/limites correspondem ao plano real, custo conhecido
e cancelamento preserva recursos básicos do aplicativo.

## Dados e validação

Informar microfone/fornecedor e definir retenção antes do piloto. Não guardar
áudio integral por padrão; minimizar contexto e reaproveitar tratamento privado
de propostas. Usar dados de teste durante desenvolvimento.

Este documento registra uma direção de produto, sem comprovar implementação,
assinatura, envio ou funcionamento de voz na conta atual.
