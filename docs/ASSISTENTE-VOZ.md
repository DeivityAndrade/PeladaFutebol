# Assistente por áudio e mensagem

Atualizado em **09/10/2026**, fuso de São Paulo.

**Decisão atual:** áudios gravados e texto no site e no WhatsApp, sem ligação
ou conversa ao vivo. Substitui a preferência anterior por tempo real.
Monetização permanece para uma entrega futura.

## Interface minimalista

Após a revisão do proprietário, o agente usa uma janela menor, sem introdução,
atalhos ou avisos permanentes. Microfone, mensagem e envio ficam na mesma barra.
Grupo, nova conversa e informações sobre dados ficam em **Opções da conversa**.
A confirmação mostra partida, grupo, local e horário; configurações secundárias
ficam em **Detalhes**. Repetição e cobrança, quando presentes, continuam visíveis.
Funções, permissões e confirmação final foram preservadas. Verificado por build
e 18 cenários existentes de navegador, com capturas de computador e celular.

## Agente conversacional do site — correção da experiência

O pedido do proprietário em 09/10/2026 é que o microfone conduza e execute tarefas,
sem uma etapa manual de transcrição. O botão flutuante **Agente** abre uma conversa
para membros autenticados de grupos reais. Ao escolher **Enviar áudio**, ou atingir
60 segundos, o áudio é transcrito e interpretado automaticamente. Não há botão de
transcrição nem formulário obrigatório neste fluxo.

- Consulta os próximos jogos; cada horário é mostrado no fuso do respectivo grupo.
- Resolve grupo citado entre os grupos autorizados. Nomes repetidos exigem escolha
  explícita. O grupo atual é contexto inicial quando a conversa é aberta na agenda.
- Prepara partidas para organizadores, pergunta detalhes ausentes e aceita ajustes
  por novas mensagens/áudios, preservando a proposta anterior no servidor.
- Confirma ou retira presença após mostrar a partida e obter confirmação pelo botão.
  Reutiliza regras de vagas, fila de espera, escalação e permissões existentes.
- Mostra um resumo e **Criar jogo** diretamente na conversa; não exige **Preparar jogo**
  nem **Revisar detalhes**. Uma mensagem interpretada nunca cria/retira presença sozinha.
- Executa cada confirmação uma única vez. Alterar o pedido invalida a confirmação
  anterior; perda de resposta pode ser repetida com a mesma confirmação.
- Mantém o assistente de formulário existente como alternativa na agenda.

O agente usa a chave/modelo já configurados no Render, sem nova credencial ou fluxo n8n
para o site. Uma chamada de interpretação por mensagem, incluindo criação. Compartilha
a quota de 10 tentativas por pessoa/hora e o orçamento diário existente do assistente
(20 por padrão); transcrição mantém seus limites próprios.

Migração **V18** guarda conversa temporária, reserva de processamento e confirmação.
Contexto de até oito mensagens, 25 grupos autorizados e oito próximos jogos por grupo;
consultas retornam até 30 partidas. Dados de contato, lista de membros e valores financeiros
não são adicionados ao contexto do provedor. Mudança de acesso limpa o contexto anterior.
A conversa expira em 30 minutos sem interação; limpeza horária remove registros expirados.
Criação/consulta/presença continuam verificadas pelo servidor depois da interpretação.

Não adiciona cancelamento/edição de partidas existentes, cobrança ou campanhas livres
de WhatsApp. Convites de jogos criados seguem as regras já existentes de automação e
opt-in, sem afirmar entrega antes de ela ocorrer. n8n/Meta não mudam nesta correção.

Verificação desta correção: 135 testes Java em verify e dez testes finais do agente
após o ajuste da consulta entre grupos. Navegador: 62 cenários aprovados no total,
com três testes condicionais de carreira omitidos; nove cenários precisaram de
reexecução após habilitar demonstração e administração no servidor de testes local.
Microfone e OpenAI simulados nesses testes. Evidências visuais do novo fluxo:
docs/screenshots/agente-conversa-desktop.png e agente-conversa-celular.png.

O CI usa a imagem oficial PostgreSQL 18 Alpine pelo espelho Docker no ECR público.
Isso evita o erro de autenticação/download do Docker Hub observado antes dos testes.
O manifesto foi conferido com o mesmo digest do espelho do Google; não muda o banco
do site. Referência: [Docker Official Images no ECR](https://aws.amazon.com/blogs/containers/docker-official-images-now-available-on-amazon-elastic-container-registry-public/).

## Primeira implementação

Registro da entrega anterior. O fluxo flutuante de revisão abaixo foi substituído
pelo agente conversacional; continua disponível no assistente de formulário.

- Microfone no canto do site para organizadores autenticados e seleção explícita
  dos grupos que organizam. A fala não troca o grupo silenciosamente.
- Gravar, parar, ouvir, descartar e transcrever. O gravador para após 60 segundos;
  nenhum áudio é enviado antes de **Transcrever áudio**.
- Texto editável. **Preparar jogo**, **Revisar detalhes** e **Criar jogo** permanecem
  separados. Complementos podem ser escritos ou gravados. Fechar libera o microfone.
- n8n busca metadados na Meta, verifica tamanho, formato e domínio, baixa sem
  redirecionamentos e encaminha o áudio ao servidor. O texto transcrito segue as
  regras existentes de agenda, presença e proposta. Respostas usam texto/botões.
- Confirmação falada não resgata código de criação: usar o botão do WhatsApp.
  Falhas de mídia/transcrição pedem nova gravação ou texto.
- Modo opcional `WHATSAPP_REPLIES_ONLY=true` libera apenas respostas `REPLY`.
  Preserva convites/lembretes pendentes e revalida o modo antes de enviar.

Criar pode agendar os avisos da automação existente para participantes elegíveis
e autorizados. Não adicionamos uma campanha para convidar todas as pessoas citadas
em uma fala. Selecionar outro grupo descarta o pedido/gravação atual.

## Permissões, limites e retenção

Site: sessão, CSRF e organizador do grupo. WhatsApp: credencial do worker, reserva
válida, número ainda vinculado, lista piloto e grupo habilitado. A identidade vem
do servidor. A reserva é conferida novamente depois da chamada ao provedor.

- Até **2 MB** por arquivo; WebM, Ogg e M4A, com verificação do início do arquivo.
  No WhatsApp, áudios de voz Ogg e arquivos M4A.
- Até **5 tentativas por pessoa/hora** e **20 no piloto/dia UTC**, compartilhadas
  entre site e WhatsApp. Limite diário configurável de 1 a 100. Falhas contam.
- Até **1200 caracteres** transcritos. Os 60 segundos são limite do gravador;
  o servidor limita bytes e tentativas, sem garantir duração do áudio WhatsApp.

O backend não salva áudio no banco nem registra corpo de erro do provedor.
Texto e identificador de mídia do WhatsApp são apagados ao concluir/expirar o
trabalho. n8n mantém dados de execução desabilitados; conferir binários temporários
e retenção do host antes de produção. A política de privacidade e o aviso antes
do envio informam a transcrição pela OpenAI. A chave fica somente no servidor.

## Ativação

Publicar backend com migração **V17**; interface na Vercel, API no Render e dados
no Neon. No Render:

```env
ASSISTANT_AUDIO_ENABLED=true
ASSISTANT_TRANSCRIPTION_MODEL=gpt-4o-mini-transcribe
ASSISTANT_AUDIO_DAILY_LIMIT=20
```

Reutiliza `ASSISTANT_API_KEY`, sem copiar o segredo ao frontend, fluxos ou conversa.
Áudio desligado por padrão. O modelo precisa estar disponível no projeto da chave.

Para testar respostas sem liberar campanhas, após conferir destinatário/credenciais:

```env
WHATSAPP_AUTOMATION_ENABLED=true
WHATSAPP_REPLIES_ONLY=true
WHATSAPP_SEND_ENABLED=true
WHATSAPP_PILOT_NUMBERS=554899527415
```

Até essa conferência, manter `WHATSAPP_SEND_ENABLED=false`. Fora do modo somente
respostas, nomes configurados não comprovam aprovação dos modelos pela Meta.
Respostas continuam restritas ao piloto e à janela de 24 horas.

No n8n, atualizar `integrations/n8n/agent.json`, manter URL do Render e associar
as três credenciais existentes. Na credencial Meta HTTP Header Auth, permitir
exatamente `graph.facebook.com` e `lookaside.fbsbx.com`; nunca qualquer domínio.
O download requer o mesmo Bearer. O proprietário altera a credencial no n8n,
sem revelar/renovar o segredo se ainda válido.

## Verificação e pendências

Testes Java/PostgreSQL isolado: sessão, CSRF, organizador, webhook assinado e
duplicado, reserva inválida, idempotência, quotas/rollback, erro sem segredo,
nenhuma criação por confirmação falada e modo somente respostas.

Interface com microfone fictício: gravar, ouvir, transcrever, editar, descartar,
liberar microfone ao fechar, permissão negada, recuperação de erro, áudio
desabilitado e computador/celular. Nós reais n8n com APIs fictícias: texto,
áudio, mídia inválida/grande, erro de transcrição e contexto individual no agente.

- [ ] Ativar no Render e conferir domínio de mídia no n8n.
- [ ] Testar áudio real no site e do número autorizado pelo WhatsApp.
- [ ] Testar ida e volta, presença, esclarecimento e criação.
- [ ] Testar convites/lembretes após aprovação dos modelos.
- [ ] Medir consumo e decidir preço/franquia; assinatura/pagamento em outra entrega.

Testes simulados não comprovam acesso ao modelo pela chave de produção, entrega
real pela Meta nem aprovação dos modelos. Nenhuma cobrança foi adicionada.
