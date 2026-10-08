# Vinculação e autorizações do WhatsApp

Esta entrega implementa a etapa 2 do [plano](PLANO-ASSISTENTE-E-WHATSAPP.md):
vinculação com prova de posse, preferências por grupo e tipo, revogação e webhook
assinado. **Não envia mensagens, não usa OpenAI e não precisa de n8n nesta etapa.**
Convites, lembretes, fila persistente, status de entrega e automação são a etapa 3.

## Começar pelo ambiente de teste

1. Acesse [Meta for Developers](https://developers.facebook.com/apps/) com sua conta
   e crie um aplicativo com o produto WhatsApp. Os termos e as verificações da conta
   devem ser concluídos pelo proprietário.
2. No painel WhatsApp, abra a configuração/teste da API. Use o número de teste
   fornecido pela Meta. Adicione e confirme somente os participantes do piloto na
   lista permitida pelo ambiente de teste. Não migre seu número pessoal para testar.
3. Anote o número de teste (com código do país, somente dígitos) e seu **Phone number ID**.
   O ID não é o número de telefone nem o ID da conta WhatsApp Business.
4. No Render, configure as variáveis abaixo diretamente no painel. Não coloque
   segredos em conversas, repositório, frontend ou URLs. Esta etapa não precisa de
   access token de envio; ele será necessário para a etapa 3.
5. Publique o servidor, registre o callback HTTPS e assine o campo `messages` da
   conta WhatsApp correspondente. Validar o callback não basta: confira também a
   assinatura do aplicativo na conta WhatsApp e a chegada de uma mensagem real.
6. Em **Minha carreira → Avisos no WhatsApp**, gere um código, envie a mensagem pelo
   seu WhatsApp, clique em **Já enviei, conferir** e confirme o número mascarado.
   Só depois marque e salve os avisos em cada grupo.

| Variável no Render | Valor |
| --- | --- |
| `WHATSAPP_ENABLED` | `true` somente quando a conta de teste estiver preparada |
| `WHATSAPP_BUSINESS_NUMBER` | Número remetente de teste, com país, sem `+`, espaços ou pontuação |
| `WHATSAPP_PHONE_ID` | ID desse número no painel da Meta |
| `WHATSAPP_APP_SECRET` | Segredo do aplicativo, nas configurações básicas da Meta |
| `WHATSAPP_VERIFY_TOKEN` | Um segredo aleatório longo escolhido pelo proprietário, usado também ao cadastrar o callback |

Callback: `https://pelada-e9z1.onrender.com/api/whatsapp/webhook`.
Pode-se usar o proxy HTTPS do site, mas o endpoint direto evita um intermediário
na verificação do corpo bruto. `WHATSAPP_ENABLED=false` é o padrão. Configuração
incompleta também mantém a vinculação indisponível. Nunca use valores de teste do
repositório em produção.

O painel da Meta pode mudar os nomes das telas. Confira os recursos permitidos pela
sua conta antes do piloto. As regras e os custos dos futuros envios devem ser
verificados separadamente; esta entrega não contrata infraestrutura nem dispara
mensagens cobradas. Render Free pode dormir, portanto esta infraestrutura não garante
lembretes pontuais. A decisão de hospedagem da fila pertence à próxima etapa.

## Segurança e comportamento

- Código aleatório de 96 bits, hash SHA-256 no banco, validade de dez minutos,
  uso único; outro código invalida o anterior. Máximo de três emissões por hora e
  intervalo mínimo de um minuto, persistidos no banco. O usuário não digita um
  número para marcá-lo como verificado.
- A mensagem recebida identifica o remetente. A confirmação final exige a mesma
  conta autenticada e CSRF no site. Nunca confirme se os últimos dígitos exibidos
  forem de outra pessoa. Um código é pessoal; não o compartilhe.
- Um número só pode pertencer a uma conta. Confirmar/trocar/reconfirmar o vínculo
  cancela todas as autorizações anteriores; não as restaura automaticamente.
- Convites e lembretes exigem escolhas explícitas, separadas por grupo. O texto
  `whatsapp-v1` é o texto dos dois checkboxes da interface. Eventos registram
  escolhas, data, versão e motivo, sem guardar número nem mensagem.
- `SAIR` revoga todos os avisos daquele número e invalida vinculação pendente.
  Para voltar, é preciso provar novamente a posse e escolher as autorizações.
  Não há resposta automática no WhatsApp nesta etapa; o estado aparece no site.
- Desconectar apaga o telefone e o desafio e cancela autorizações. Preferências
  referenciam o vínculo de participação (`members.id`); excluir a participação as
  remove por FK. Entrar novamente no grupo começa sem autorização.
- Mutação do contato e consentimento usa lock transacional curto no PostgreSQL,
  inclusive entre instâncias. A unicidade de telefone também é garantida pelo banco.
- Webhook GET verifica `hub.mode`, token e challenge. POST autentica HMAC-SHA256
  com `X-Hub-Signature-256` sobre o corpo bruto e filtra o Phone number ID configurado.
  Só esse endpoint dispensa sessão/CSRF; o restante da API continua protegido.
- IDs de mensagens são deduplicados por hash em transação antes do HTTP 200.
  Repetições não vinculam de novo nem reaplicam um SAIR já processado. Eventos de
  status e outras mensagens são ignorados nesta etapa. Não interpretar comandos de
  presença/criação de jogo antes da etapa 5.
- API só mostra o telefone mascarado à própria conta. Listas de participantes e
  administração não recebem telefone. Não registrar corpo de webhook ou código
  nos logs da aplicação/proxy.
- Limpeza local a cada hora enquanto o processo está ativo, também antes das
  mutações: remove códigos vencidos, hashes de eventos com 30 dias e histórico de
  autorizações com 90 dias. Durante suspensão do servidor, a limpeza aguarda a
  próxima execução. Não faz chamadas externas para manter Render acordado.
- Meta processa a mensagem de vinculação. Render/Neon armazenam vínculo,
  preferências e metadados com os prazos descritos. n8n só entrará no fluxo de envio
  da próxima entrega. A chave OpenAI não participa deste fluxo.

## Verificação

`WhatsAppTest` usa somente PostgreSQL local e configuração fictícia: prova e
confirmação, validade/rotatividade/limites, SAIR, revogação, reconfirmação,
exclusividade concorrente, participação/texto, limpeza, assinatura do corpo bruto,
deduplicação, número destinatário e sessão/CSRF. Nenhum teste envia mensagens à Meta.
Os testes de interface cobrem disponibilidade, vínculo, escolhas, erros,
desconexão, navegação por teclado e celular/modo noturno.

Referências primárias: [documentação de webhook da Meta](https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/create-webhook-endpoint),
[autenticação de webhook no SDK oficial arquivado](https://whatsapp.github.io/WhatsApp-Nodejs-SDK/api-reference/webhooks/start/),
[coleção oficial da Meta no Postman](https://www.postman.com/meta/whatsapp-business-platform/documentation/wlk6lh4/whatsapp-cloud-api).
O SDK não foi instalado; serve apenas como referência do protocolo.
