# Assistente para marcar peladas

Implementação da primeira etapa do [plano](PLANO-ASSISTENTE-E-WHATSAPP.md), em 08/10/2026.

## Uso

Na agenda do grupo, o organizador abre **Marcar com assistente**, escreve os detalhes e toca em **Preparar jogo**. O assistente apresenta os campos extraídos e pede os detalhes ausentes. É possível completar o pedido por texto ou editar os campos diretamente.

**Revisar detalhes** salva uma versão validada da proposta e abre um resumo com data absoluta, horário no fuso do grupo, local, times, vagas, recorrência e cobrança avulsa. Apenas **Criar jogo** cria a pelada, reutilizando `Games.create`. Interpretar e revisar nunca criam jogos nem cobranças. A cobrança avulsa, se habilitada, continua sendo gerada no início da partida pelas regras existentes.

O formulário tradicional permanece disponível. Sem configuração de IA, o assistente informa a indisponibilidade e oferece **Usar formulário**. A primeira etapa não envia mensagens pelo WhatsApp.

## Ativação no servidor

Publicar o backend com a migração `V14__assistant_proposals.sql`, após as migrações anteriores do projeto, e configurar:

| Variável | Configuração |
| --- | --- |
| `ASSISTANT_ENABLED` | `true` para habilitar; padrão `false` |
| `ASSISTANT_API_KEY` | Credencial do provedor, apenas no ambiente do servidor |
| `ASSISTANT_MODEL` | Identificador de modelo que suporte saída estruturada JSON Schema estrita |
| `ASSISTANT_BASE_URL` | API compatível com Chat Completions; padrão `https://api.openai.com/v1` |
| `ASSISTANT_DAILY_LIMIT` | Limite global de tentativas por dia UTC; padrão `20` |

Use HTTPS no provedor real. Não colocar a chave em arquivos versionados, frontend, conversas ou logs. Configure também o limite de gastos no provedor; o limite de pedidos não é um orçamento em moeda. A integração não exige n8n nesta etapa. O Blueprint usa `sync: false` nas quatro variáveis do assistente para preservar os valores definidos no painel do Render.

### Passos no painel da OpenAI e no Render

1. Entre na [plataforma da OpenAI](https://platform.openai.com/) e selecione ou crie um projeto para o Tô Dentro.
2. Abra [Billing](https://platform.openai.com/settings/organization/billing/overview) e configure a cobrança/créditos conforme as opções da sua conta. O uso desta integração por API tem cobrança própria, separada da assinatura do ChatGPT.
3. Abra [API keys](https://platform.openai.com/api-keys), crie uma chave para o projeto e dê um nome como `Tô Dentro`. Guarde-a em local privado e copie somente para o ambiente do servidor.
4. No [Render](https://dashboard.render.com/), selecione o serviço do backend do Tô Dentro e abra **Environment**. Adicione `ASSISTANT_API_KEY` com a chave criada; use `ASSISTANT_MODEL=gpt-4.1-mini` como opção inicial compatível, `ASSISTANT_ENABLED=true` e `ASSISTANT_DAILY_LIMIT=20` para começar o piloto com volume limitado.
5. Depois que o código atualizado do backend estiver no serviço, salve e publique as variáveis. Apenas reiniciar o backend antigo não instala o assistente; é necessário publicar o código e a migração também.
6. Entre no grupo como organizador e experimente um pedido com data/local conhecidos, revisando o resumo antes de criar.

`gpt-4.1-mini` é uma opção inicial para extração de campos, não uma exigência do produto nem uma validação semântica já feita com chave real. A documentação confirma suporte a Chat Completions e Structured Outputs. Conferir disponibilidade/custos da conta antes de ativar. O limite de 20 pedidos é global por dia UTC e pode ser ajustado.

Fontes: [criação de chave e cobrança da API](https://developers.openai.com/api/docs/quickstart),
[cobrança da API separada do uso de assinatura](https://learn.chatgpt.com/docs/pricing),
[modelo GPT-4.1 Mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini) e
[variáveis no Render](https://render.com/docs/configure-environment-variables).

A disponibilidade exige habilitação, chave e modelo. O navegador acessa somente `/api` da própria origem. Quando houver publicação separada na Vercel e Render, ambos precisam receber as respectivas mudanças; publicar somente a interface apresenta o formulário de indisponibilidade enquanto o backend antigo não tiver as novas rotas.

O conteúdo do pedido, o nome/fuso do grupo, a data atual e os campos da proposta anterior são enviados ao provedor para interpretação. Não são enviados e-mails, lista de membros, cobranças ou comprovantes. O formulário informa esse envio antes de preparar o jogo. `store=false` solicita que a resposta não seja armazenada como objeto de conversa no provedor; isso não substitui suas políticas de tratamento de dados.

## Proteções e limites

- Apenas o organizador do grupo interpreta pedidos, revisa e confirma sua própria proposta. Todas as permissões são conferidas no servidor, inclusive na confirmação.
- API preserva sessão e CSRF. Pedidos limitados a 1.200 caracteres; até dez tentativas por organizador por hora UTC e limite diário global configurável, persistidos no PostgreSQL. Falhas do provedor também consomem tentativa.
- Até 1.000 tokens de conclusão por chamada; conexão de até cinco segundos e leitura de até 25 segundos. Respostas recusadas, incompletas, inválidas e erros do provedor produzem mensagem segura e opção de formulário.
- A interpretação ocorre fora de transação e sem manter bloqueios de jogos durante a chamada externa.
- Propostas expiram após 30 minutos. Limpeza de propostas ocorre no próximo pedido autorizado após 24 horas do vencimento; não há histórico permanente de conversa. Propostas não armazenam o texto original do pedido.
- Editar incrementa versão. Uma confirmação de versão antiga é rejeitada; complementar o texto invalida a proposta anterior. Mudança do fuso do grupo invalida o contexto antigo.
- Confirmação e gravação do jogo acontecem na mesma transação, sob bloqueio da proposta. Repetir uma confirmação retorna o mesmo jogo enquanto a proposta for retida. Pedidos distintos continuam sendo propostas distintas.
- Datas passadas, horários inexistentes ou ambíguos no fuso, parâmetros fora dos limites e cobrança avulsa sem valor positivo impedem criar o jogo.
- A IA interpreta datas relativas usando o fuso do grupo. A correção semântica de um modelo real depende do modelo escolhido e precisa ser conferida no piloto; o resumo sempre mostra a data completa para revisão humana.

## Verificação

Os testes usam PostgreSQL local isolado e provedor simulado. Não executar testes que limpam banco contra Neon/produção.

Backend: `AssistantTest` cobre criação separada da interpretação, permissões, sessão/CSRF, tamanho do pedido, edição/versionamento, expiração, datas passadas, complementos, parâmetros inválidos, quotas, confirmação concorrente e série semanal com cobrança. `AiInterpreterTest` verifica formato estrito, autenticação ao provedor e tratamento de falhas sem expor dados.

Navegador: `frontend/e2e/assistant.spec.ts` cobre desktop, celular, edição, foco, detalhes ausentes, confirmação, falhas/repetição e formulário alternativo. Suas APIs são simuladas; testes de serviço verificam a persistência real. Capturas da revisão ficam em `docs/screenshots/assistente-*-revisao.png`.

Uma chave/modelo reais são necessários para verificar a interpretação em produção. O piloto deve incluir “amanhã”, dia da semana, mudança de mês/ano, grupos com fusos diferentes, local ausente, pedido incompatível com as regras e falha do provedor.

Resultado local em 08/10/2026: compilação do Angular e empacotamento do servidor aprovados;
90 testes Java sem falhas (incluindo os 12 específicos do assistente/provedor), seis
testes de navegador do assistente aprovados e dez testes de agenda aprovados.
Revisão visual das capturas em desktop/celular e temas claro/noturno concluída.
O provedor de IA foi simulado nesses testes; não foi utilizada uma chave real.

A interface usa o projeto existente da Vercel:
[Tô Dentro](https://todentro-vercel-teste.vercel.app). A publicação da interface não
publica o servidor do Render nem configura a IA. A ativação exige publicar as rotas
e a migração V14 no Render e conferir as quatro variáveis no ambiente do serviço.

Na preparação da ativação em 08/10/2026, a versão foi isolada a partir da branch
principal atualizada. Novamente, 90 testes Java e seis testes de navegador do
assistente passaram, com build do Angular e formatação dos arquivos alterados
aprovados. O limite padrão foi reduzido a 20 pedidos diários para o piloto.
As chaves permanecem somente no Render. A validade da chave e a interpretação
com o modelo real devem ser verificadas em uma sessão de organizador; os testes
automatizados continuam usando provedor simulado.

Verificação pública da Vercel aprovada: página, arquivos da interface, health check,
demonstrações, sessão/CSRF, cookie seguro e proteção das rotas privadas. Nenhum dado
real foi criado. A primeira tentativa excedeu o tempo no backend; a nova execução,
após resposta do health check, concluiu com sucesso. A documentação OpenAPI pública
excedeu o tempo e não foi usada como evidência de publicação das novas rotas.

## Próximas entregas

Vínculo de telefone e autorização de avisos, fila de notificações, n8n e WhatsApp Business permanecem nas etapas seguintes do plano. Não estão ativados por esta implementação.

Referência do contrato do provedor: [Structured Outputs — documentação oficial](https://developers.openai.com/api/docs/guides/structured-outputs).
