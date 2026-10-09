# Assistente por áudio e mensagem

Atualizado em **09/10/2026**, fuso de São Paulo.

**Decisão atual:** áudios gravados e texto no site e no WhatsApp, sem ligação
ou conversa ao vivo. Substitui a preferência anterior por tempo real.
Monetização permanece para uma entrega futura.

## Primeira implementação

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
