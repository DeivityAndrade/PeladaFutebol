# Agente WhatsApp e piloto local

Implementação de 08/10/2026. Código e fluxos disponíveis; esta entrega não ativa envios
reais por padrão. Não é necessário contratar n8n Cloud para os testes locais.

## O que o piloto faz

- Convida membros com número verificado e autorização de convites naquele grupo.
- Aceita os botões **Vou jogar / Não vou**. Textos claros como “tô dentro” funcionam
  quando há uma única partida possível. Se há dúvida, mostra a partida e pede confirmação.
- Aplica as regras existentes de vagas, fila e retirada de presença. Uma repetição do
  webhook ou da ferramenta não duplica a ação nem a resposta.
- Consulta a agenda e prepara partidas a pedido do organizador. Mostra data, fuso,
  local, times, jogadores, recorrência e cobrança antes de criar. Somente o botão
  de confirmação ou o código explícito mostrado no resumo autoriza a criação.
- Envia lembrete para confirmados que autorizaram lembretes, no intervalo escolhido.
- Permite ao organizador ativar/pausar por grupo na **Agenda de jogos → WhatsApp do grupo**.
  Mostra resultados dos últimos sete dias e respostas de presença dos próximos jogos.

Áudio, edição/cancelamento pelo chat, cobrança automática, preenchimento de vagas
por mensagens e transferência para atendimento humano são próximas fases.

## Como funciona

Meta → webhook assinado no servidor → fila no PostgreSQL → n8n → ferramentas do
servidor → resposta na fila → n8n → Meta. Os status de entrega retornam ao webhook.

O servidor continua responsável por identidade, grupos, permissões e vagas. O n8n
nunca conecta diretamente ao banco do Tô Dentro. O agente usa IDs de grupos e partidas
autorizados; não recebe telefone, e-mail, senha ou credencial do usuário. Sua frase
livre não é enviada ao participante: a resposta final é construída pelo servidor.

O n8n usa OpenAI para interpretar frases que o servidor não reconhece diretamente.
Preparar os campos de uma partida reutiliza o assistente do servidor, já configurado
no Render. São duas funções de IA; os pedidos diretos por botão não usam IA.

## Iniciar neste computador

n8n **2.42.5** foi instalado separadamente em
`%USERPROFILE%\.codex\tools\todentro-n8n`, com Node 24. O banco local `todentro_n8n`
usa PostgreSQL em `127.0.0.1:55432`; não é o banco da aplicação. Os dois fluxos foram
importados desativados. Credenciais reais ainda precisam ser configuradas pelo proprietário.

```powershell
powershell -ExecutionPolicy Bypass -File integrations/n8n/start-local.ps1
```

Abra `http://localhost:5679` e crie sua conta local de proprietário. Guarde a senha
e a chave de criptografia da instalação; elas não pertencem ao Git. A conta local
não é a conta do site. O PostgreSQL precisa estar em execução antes do n8n.
O arquivo de dados e os logs ficam fora do repositório. Para encerrar, pare o
processo informado pelo script. Não execute o script novamente enquanto ele estiver ativo.

Se precisar instalar em outro computador, use Node 24 e `npm install n8n@2.42.5`
em uma pasta dedicada. Informe os caminhos e parâmetros de banco ao script.
Existe também a alternativa Docker abaixo, que não foi executada nesta máquina.

## Alternativa Docker local

Copie `integrations/n8n/.env.example` para `.env` na mesma pasta e preencha dois
segredos aleatórios diferentes. Não envie esses valores em mensagens.

```powershell
cd integrations/n8n
docker compose up -d
```

Abra `http://localhost:5678`. Banco e credenciais ficam em volumes locais; não use
`docker compose down -v` se quiser preservá-los. A porta só está exposta no próprio
computador. Esta configuração HTTP é para testes locais, não hospedagem pública.

## Importar e configurar os fluxos

Importe `integrations/n8n/agent.json` e `integrations/n8n/delivery.json` pelo menu de
importação do n8n. Atualizações usam IDs estáveis. Ambos começam **desativados**.
No nó **Configuração**, escolha uma única URL de backend de teste:

- n8n instalado no Windows: `http://127.0.0.1:8081` para o servidor local de testes.
- n8n no Docker: `http://host.docker.internal:8081` para esse servidor no Windows.
- backend remoto: sua origem HTTPS, apenas quando a versão e o piloto estiverem preparados.

O n8n consulta o servidor a cada minuto. Não precisa de túnel ou webhook público
para o n8n. Para mensagens reais, a Meta ainda precisa alcançar o webhook HTTPS do
servidor. Não altere o callback que já funciona para apontar a localhost.

Crie e selecione estas credenciais em **todos** os nós que indicarem configuração pendente:

| Nome sugerido | Tipo | Configuração |
| --- | --- | --- |
| Tô Dentro — integração | Header Auth | `Authorization`: `Bearer ` seguido do mesmo `WHATSAPP_INTEGRATION_TOKEN` do servidor |
| Meta — WhatsApp | Header Auth | `Authorization`: `Bearer ` seguido do token de acesso da Meta |
| OpenAI — Tô Dentro | OpenAI | Chave do projeto e endpoint oficial; modelo inicial `gpt-4.1-mini` |

Os IDs no JSON identificam credenciais esperadas, não contêm chaves. Ao criar
credenciais pelo painel, selecione as novas credenciais nos nós; seus IDs serão
os gerados pela sua instalação. Tokens temporários da Meta expiram. Para operação
contínua, prepare credencial apropriada e permissões na Meta, sem publicá-la.

Antes de ligar o agendamento, execute **Testar manualmente** com dados fictícios.
O fluxo de entrega não faz reenvio cego; nunca habilite “Retry on Fail” no nó Meta.
Falha de transporte pode significar que a mensagem já foi aceita: fica **Entrega
a conferir**, até investigar o resultado no provedor. O sistema não oferece reenvio
manual automático desses casos. Não duplique o registro na fila para tentar de novo.

## Variáveis do servidor para o piloto

As variáveis existentes de vínculo continuam necessárias; consulte [WHATSAPP.md](WHATSAPP.md).

| Variável | Preparação / efeito |
| --- | --- |
| `WHATSAPP_AUTOMATION_ENABLED` | `false` por padrão; `true` habilita o processamento de comandos e a API do executor |
| `WHATSAPP_SEND_ENABLED` | `false` por padrão; `true` permite reservar envios, inclusive respostas ao chat |
| `WHATSAPP_INTEGRATION_TOKEN` | Segredo aleatório de 32 a 256 caracteres, compartilhado apenas com a credencial n8n |
| `WHATSAPP_PILOT_NUMBERS` | Lista explícita de telefones de teste, país + número, só dígitos, separados por vírgula |
| `WHATSAPP_INVITATION_TEMPLATE` | Nome do modelo de convite aprovado em `pt_BR` |
| `WHATSAPP_REMINDER_TEMPLATE` | Nome do modelo de lembrete aprovado em `pt_BR` |
| `WHATSAPP_GRAPH_VERSION` | Versão habilitada para o aplicativo; inicial `v26.0` |
| `WHATSAPP_DAILY_SEND_LIMIT` | Inicial 20 tentativas por dia UTC, somando todos os grupos e respostas |
| `PUBLIC_APP_URL` | Origem HTTPS do site; localhost é permitido só para os testes |

Além da lista do servidor, o número de teste da Meta exige destinatários autorizados
no próprio painel da Meta. Vincular um número no site não o cadastra na Meta.
Somente grupos ativados pelo seu organizador recebem convites e comandos. Membros
também precisam marcar suas autorizações para convites/lembretes. `SAIR` ou
desconectar revoga avisos, invalida comandos pendentes e impede novos envios.
Uma mensagem que já foi submetida à Meta ainda pode chegar após pausar.

Para testar só o recebimento/interpretação, mantenha `SEND_ENABLED=false`; os
resultados ficam na fila. Para testar respostas reais, complete os dois modelos
e a lista do piloto antes de habilitar o envio. Se qualquer configuração faltar,
a API de envios não envia mensagens.

## Modelos na Meta

Os dois modelos devem ter um corpo com **cinco variáveis**, nesta ordem:
`{{1}}` grupo; `{{2}}` título; `{{3}}` data/hora no fuso do grupo; `{{4}}` local;
`{{5}}` link completo para a partida. Use amostras fictícias no cadastro.

Convite: “O grupo {{1}} marcou {{2}} para {{3}}, em {{4}}. Você vai jogar?
Veja a partida: {{5}}. Para cancelar avisos, envie SAIR.”
Configure dois botões de resposta rápida, nesta ordem: **Vou jogar**, **Não vou**.
O servidor fornece os identificadores seguros dos botões em cada envio.

Lembrete: “Seu jogo {{2}} do grupo {{1}} começa em {{3}}, em {{4}}.
Sua presença está confirmada. Veja a partida: {{5}}. Para cancelar avisos, envie SAIR.”
O lembrete não tem botões no contrato atual.

Envios iniciados pelo sistema usam modelos aprovados. Respostas de texto livre
usam a janela de atendimento de 24 horas aberta pela mensagem do usuário.
Não presumir aprovação, categoria ou tarifa: a Meta define esses resultados.
O teto de tentativas controla volume, não garante um teto financeiro em reais.

## Limites operacionais e dados

Textos recebidos têm até 1.200 caracteres, processamento por até dez minutos e
quota de 30 comandos/hora por jogador do piloto. O executor usa reservas temporárias;
um processo interrompido pode retomar uma ação ainda não enviada, sem duplicá-la.
O orçamento de IA deve ser limitado também no projeto OpenAI; criação reutiliza
a quota diária do assistente do servidor. O modelo tem no máximo duas iterações
por mensagem, timeout e nenhuma tentativa automática extra.

Texto da entrada é apagado ao concluir/expirar. Resultados de atendimento ficam
sete dias; a fila de envios fica 30 dias; eventos de entrega, sete dias. Texto da
resposta enviada é removido quando aceita pela Meta. Configure n8n sem guardar
execuções manuais, de sucesso ou de erro; os fluxos e os iniciadores já trazem
essa configuração. Nunca fixe dados reais nos nós nem registre mensagens/chaves
em logs. Consulte a [política de privacidade](../frontend/public/privacidade.html).

O computador precisa estar ligado para executar o n8n local. Esse agendador
consulta o backend e pode acordar um servidor suspenso; ele não elimina o atraso
do primeiro pedido. O piloto local e Render Free não garantem horário pontual
de entrega. Antes de ampliar para usuários reais, definir execução contínua,
custos e atendimento das falhas. Não é uma migração de banco ou hospedagem.

## Verificação

`WhatsAppAgentTest` testa o domínio e as rotas com PostgreSQL local isolado e IA
simulada. `test-workflows.mjs` executa os fluxos no n8n real contra servidor, modelo
e Meta simulados, incluindo contexto individual de duas mensagens, falha de IA,
entrada vazia, falha explícita da Meta e resultado desconhecido de transporte.
Exige `N8N_TEST_CLI`, banco exclusivo `todentro_n8n_test` em `127.0.0.1` e as
variáveis `DB_*` do n8n. Não execute com banco ou credenciais de produção.

Testes de navegador verificam ativar/pausar, erro/recuperação, nomes repetidos,
horário do grupo, teclado e celular. Evidências em `docs/screenshots/whatsapp-operacao-*`.
Resultado local: build e empacotamento aprovados; suíte de 111 testes Java e, após
os ajustes finais, 64 testes das áreas alteradas aprovados (15 específicos do agente).
Dez testes de agenda e cinco de WhatsApp passaram. Os dois fluxos foram executados
com sucesso no n8n 2.42.5 e serviços simulados. Docker não foi executado.
Validação com credenciais reais da Meta/OpenAI e os números do proprietário é
um passo posterior; estes testes não enviam mensagens para pessoas.

Referências: [n8n AI Agent](https://docs.n8n.io/integrations/builtin/cluster-nodes/root-nodes/n8n-nodes-langchain.agent/),
[n8n Docker](https://docs.n8n.io/hosting/installation/docker/),
[política WhatsApp](https://business.whatsapp.com/policy).
