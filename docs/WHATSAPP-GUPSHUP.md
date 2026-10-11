# Piloto pela Gupshup

Preparação de 10/10/2026. A conta foi criada pelo proprietário, com MFA no
Google Authenticator. O aplicativo ToDentro e o número irlandês aparecem como
Live / Active / Connected no painel. Há uma pendência de configuração interna
do MM Lite, separada do teste de respostas. Isso ainda não comprova entrega.

## Escopo desta etapa

Entrada v3 no formato Meta, autenticação própria de callback, agenda, presença
e criação com confirmação pelo agente existente. O transporte Gupshup aceita
**somente respostas**, restritas à lista do piloto, janela de atendimento e
limite diário existentes. Não são enviados convites, lembretes ou campanhas.
Áudio pelo novo provedor ainda não é baixado/transcrito: o servidor responde
pedindo texto, sem enviar a credencial Meta à Gupshup. O áudio no site e o
transporte Meta anterior permanecem disponíveis com suas configurações próprias.

Não é preciso importar contatos nem conversas antigas. A advertência de
sincronização de coexistência não deve motivar uma importação de histórico
sem decisão específica do proprietário.

## Servidor

Publicar o código antes de configurar o callback. Durante a troca, deixar
`WHATSAPP_SEND_ENABLED=false` e pausar o executor de entrega anterior.

| Variável | Valor |
| --- | --- |
| WHATSAPP_PROVIDER | GUPSHUP |
| WHATSAPP_GUPSHUP_APP_ID | App ID do ToDentro na Gupshup |
| WHATSAPP_GUPSHUP_WEBHOOK_TOKEN | Segredo novo e exclusivo: 64 caracteres hexadecimais aleatórios |
| WHATSAPP_BUSINESS_NUMBER | Número irlandês, país + número, somente dígitos |
| WHATSAPP_PHONE_ID | Phone Number ID do número irlandês, na aba Account |
| WHATSAPP_AUTOMATION_ENABLED | true |
| WHATSAPP_REPLIES_ONLY | true |
| WHATSAPP_SEND_ENABLED | false até a configuração e validação do executor |

Manter `WHATSAPP_ENABLED=true`, o único destinatário já autorizado em
`WHATSAPP_PILOT_NUMBERS` e a credencial existente `WHATSAPP_INTEGRATION_TOKEN`.
O segredo do callback é diferente dessa credencial, da OpenAI e da Gupshup.
Não colocar segredos em URLs, JSON de workflow, Git, mensagens ou capturas.
Não é necessária migração de banco, mudança da Vercel ou exclusão do aplicativo
WhatsApp Business. Não alterar o callback Meta antigo para usar uma chave comum.

## Callback Gupshup

Na aba Webhooks, Add Webhook:

- Name: `ToDentro-piloto`.
- Callback: `https://pelada-e9z1.onrender.com/api/whatsapp/webhook/gupshup`.
- Includes headers: chave `X-ToDentro-Webhook`; valor do segredo exclusivo acima.
- Format: **Meta format (v3)**.
- Events: **Message, Failed, Sent, Delivered, Read**.
- Não selecionar histórico, contatos, Billing ou pagamentos.

O proprietário entra e salva o segredo diretamente no Render e na Gupshup.
Só salvar o callback depois que o deploy e as variáveis estiverem prontos.
O servidor exige o segredo em comparação constante, o App ID em `gs_app_id`
e o Phone Number ID configurados. Eventos são limitados a 128 KiB. Repetições
conservam deduplicação e as autorizações existentes.

## n8n

O recebimento continua usando `agent.json`, a OpenAI e a integração existentes.
Importar **delivery-gupshup.json** desativado. Selecionar a credencial de
integração nos nós do backend. Configuração aponta ao Render. Manter o fluxo
Meta de entrega pausado; a API também impede esse fluxo de reservar entregas
quando o servidor está em modo Gupshup.

O proprietário cria a credencial **Gupshup — Tô Dentro**, tipo Header Auth:

- Name: `apikey`.
- Value: App API Key do aplicativo, **sem Bearer**.
- Allowed HTTP Request Domains: Specific Domains; `api.gupshup.io` somente.

Selecionar essa credencial apenas no nó de envio do novo workflow. O nó legado
se chama “Enviar pela Meta” por compatibilidade com o gerador de fluxos, mas
nessa variante usa a credencial Gupshup e a URL v3 da Gupshup. Redirecionamentos
e tentativas automáticas ficam desabilitados. A URL é validada antes de anexar
a credencial. Não guardar dados de execuções ou fixar dados reais nos nós.

Esta chave é o mecanismo legado, ainda suportado. **Até 31/03/2027**, migrar
para CAT: segredo de conta com até 90 dias e token de app com 24 horas, renovado
automaticamente. A renovação automática CAT ainda não está implementada neste
piloto; não prometer uma integração sem manutenção indefinidamente.

## Verificação e custos

Depois de configurar recebimento, pedir uma consulta nova pelo número autorizado.
Conferir entrada DONE e resposta PENDING, mantendo entrega desligada. Antes de
ativar o novo workflow e o envio, conferir saldo, tarifas e o limite diário.
Uma eventual recarga exige decisão do proprietário; nenhuma foi feita nesta
preparação. Após ativar, usar nova consulta, confirmar aceite pela API,
DELIVERED/READ pelo callback e resposta no dispositivo. Não reenviar falhas
antigas ou resultados UNKNOWN.

Cadastro/aplicativo Live não significam mensagem entregue nem garantia de
gratuidade. A Gupshup informa taxa própria por uso e recarga mínima de US$10;
créditos não reembolsáveis. O aviso de outubro/2026 informa cobrança Meta de
serviço depois das primeiras 1.000 mensagens por número/mês e mudanças nas
mensagens de utilidade. Conferir a tarifa vigente para o destinatário antes
de ampliar. Limite de tentativas não equivale a teto financeiro em reais.

Próximas entregas: ida e volta real por texto, presença/criação confirmadas,
áudio via API de mídia adequada, CAT automático, modelos/convites autorizados
na conta nova e hospedagem contínua do n8n. O vínculo existente do participante
não deve ser apagado só por trocar o remetente.

Validação desta preparação: 40 testes Java das áreas WhatsApp aprovados,
incluindo cinco de Gupshup; após acrescentar correlação `gs_id`, os cinco
foram executados novamente com sucesso. Os três fluxos foram executados no
n8n 2.42.5 com banco local exclusivo e serviços/credenciais fictícios. O teste
Gupshup verificou cabeçalho próprio, seleção do provedor, aceitação, falha,
transporte incerto e ausência de reenvio. Não houve envio real nesses testes.

Referências oficiais: [coexistência](https://partner-docs.gupshup.io/docs/co-existence-closed-beta-phase),
[passthrough v3](https://partner-docs.gupshup.io/docs/whatsapp-passthrough-apis-for-partners),
[v3 para clientes](https://support.gupshup.io/hc/en-us/articles/55873677826713-Username-Business-Scoped-User-ID),
[CAT](https://docs.gupshup.io/docs/cats), [carteira](https://docs.gupshup.io/docs/wallet),
[custos de serviço em outubro/2026](https://support.gupshup.io/hc/en-us/articles/62362400519705-WhatsApp-Service-Messages-Pricing-w-e-f-01-Oct-2026).
