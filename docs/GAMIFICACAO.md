# Plano de implementação — conquistas de participação

Plano do Tô Dentro elaborado em 08/10/2026. As entregas 1 e 2 foram implementadas no projeto; publicação e piloto com uma turma real são etapas posteriores.

## Objetivo

Dar ao jogador uma história pessoal dentro de cada grupo: registrar as peladas de que participou, colecionar conquistas e personalizar sua figurinha. O sucesso é estimular o retorno à pelada e o pertencimento ao grupo.

Direção aprovada: recompensas principalmente simbólicas. Nenhuma conquista, título, moldura, progresso ou desafio depende de notas, gols, vitórias, posição ou desempenho esportivo.

Não incluir classificação entre jogadores, XP público, eleição de melhores, votação de popularidade, sequências obrigatórias, punição por falta, recompensa por pagamento ou benefício na fila de espera. As avaliações e o placar existentes seguem seus próprios fluxos e não alimentam este sistema.

## Evidências do projeto atual

- `Participation`, em `backend/src/main/java/br/com/pelada/domain/Domain.java`, registra confirmação ou espera, equipe e posição. Não comprova comparecimento.
- `Matches.finish`, em `backend/src/main/java/br/com/pelada/games/Matches.java`, encerra uma partida ao vivo. Não é uma base suficiente para grupos com três ou mais times ou jogos realizados sem usar o cronômetro.
- O perfil atual (`PlayerProfile` e o modal `profile`) consulta avaliações. A carreira de participação precisa de contratos e apresentação próprios, sem usar a média como destaque da figurinha.
- O projeto já possui autenticação, autorização por grupo, PostgreSQL, migrações Flyway, Angular standalone com signals e testes Java/Playwright. A proposta cabe nessa arquitetura, sem serviços ou dependências externas.
- A identidade atual usa Barlow, verde-noite, lima, formas arredondadas e ícones locais. Preservar esse sistema.

## Regras da primeira versão

### Progresso por grupo

Uma participação válida é um comparecimento verificado em uma pelada não cancelada, elegível para o programa daquele grupo. Cada jogador recebe no máximo um crédito por pelada, independentemente de quantas partidas ou times houver no encontro.

- Reservas que compareceram e goleiros convidados têm o mesmo valor de participação que os demais jogadores.
- Confirmação antecipada, lista de espera, gols, notas e pagamentos não geram créditos.
- Um registro de espera não pode virar comparecimento premiado sem antes existir uma vaga confirmada pelo fluxo normal. A conferência não altera vagas, elenco ou cobranças.
- O organizador pode verificar a própria presença pelas mesmas regras; não há prêmio extra por administrar o grupo.
- Cada grupo tem sua coleção e seu progresso. Participar de outro grupo não adianta conquistas neste grupo.
- Ausências não retiram conquistas nem zeram contadores. Não há prazo para atingir os marcos.
- Erros de conferência são corrigíveis. Créditos indevidos e conquistas que dependiam deles precisam ser recalculados; comunicar a correção de forma neutra ao jogador afetado.

### Ativação e histórico

O organizador ativa o programa nas configurações do grupo. Registrar a data da primeira ativação e contar apenas eventos cujo horário seja igual ou posterior a ela. Não converter confirmações antigas em comparecimentos nem inventar histórico retroativo.

Desativar o programa pausa a participação de novos encontros nas conquistas e conserva a coleção existente. Reativar não redefine a data inicial; encontros realizados durante a pausa não ganham créditos retroativos. Persistir a elegibilidade por evento para que a regra não dependa apenas do estado atual da configuração.

A elegibilidade é calculada pela data da pelada dentro dos períodos de ativação registrados. A conferência persiste esse resultado. Encontros realizados durante um período ativo continuam podendo ser conferidos ou corrigidos depois, mesmo se o programa estiver pausado no momento da revisão.

Sair do grupo não apaga a coleção pessoal. O acesso aos dados de outros participantes continua sujeito às permissões do grupo. Goleiros convidados podem consultar seus próprios créditos e sua figurinha, sem receber acesso aos perfis ou ao histórico privado dos demais membros.

O compartilhamento da figurinha fica restrito a membros atuais. A coleção de convidados e de quem saiu do grupo permanece privada; essas pessoas continuam podendo personalizar e consultar sua própria figurinha.

### Catálogo inicial

| Código estável | Conquista | Condição | Recompensa |
| --- | --- | --- | --- |
| FIRST_APPEARANCE | Tô dentro | 1 comparecimento | Selo de estreia |
| FIVE_APPEARANCES | Já é de casa | 5 comparecimentos | Título selecionável |
| TEN_APPEARANCES | Figurinha carimbada | 10 comparecimentos | Moldura selecionável |
| TWENTY_FIVE_APPEARANCES | Parte da história | 25 comparecimentos | Selo e título selecionável |

Os números são marcos pessoais, sem classificação por quantidade. A primeira conquista deve chegar logo para que um recém-chegado tenha uma experiência completa. Não usar bronze/prata/ouro, estrelas de habilidade ou uma nota geral na figurinha.

## Fluxos e telas

### Conferir presença — organizador

1. Depois do horário da pelada, mostrar a ação **Conferir quem participou**. Não permitir conferência enquanto a partida estiver ao vivo.
2. Exibir os jogadores com vaga confirmada, incluindo reservas e goleiros convidados. Cada nome começa como **Ainda não conferido**, sem presumir comparecimento.
3. Permitir marcar **Participou** ou **Não participou**, com seleção em lote explícita para reduzir o trabalho. Nenhuma seleção em lote é aplicada automaticamente.
4. Exigir a revisão de todos os nomes e a declaração **Esta pelada aconteceu** antes de salvar. Cancelados não podem receber créditos.
5. Mostrar um resumo antes do envio: quantidade de participantes e nomes que receberão crédito.
6. Salvar a conferência e conceder as conquistas na mesma transação. Uma repetição do envio não duplica nada.
7. Disponibilizar **Corrigir presenças**, com versão da conferência e registro de quem alterou, quando e quais registros foram modificados.

Esse fluxo funciona sem placar ao vivo e não altera `matchEndedAt`, abre avaliações, muda o financeiro ou encerra artificialmente o cronômetro. Encerrar uma partida pode oferecer um atalho para a conferência, mas não concede conquistas sozinho.

A lista de ausências fica restrita ao organizador. Os demais jogadores consultam seus próprios registros; não há mural de faltas.

### Minha carreira — jogador

Na página do grupo, adicionar **Minha carreira**, com:

- figurinha pessoal;
- conquistas obtidas, com data e explicação;
- progresso privado para a próxima conquista;
- histórico dos próprios comparecimentos verificados;
- controles para escolher título, moldura e até três selos desbloqueados.

Exemplo: **Você participou de 4 peladas neste grupo. Falta uma para conquistar “Já é de casa”.**

Começar com nome existente e avatar de iniciais. Upload de foto, apelido público e número favorito ficam para uma evolução posterior, evitando ampliar o primeiro trabalho com armazenamento e edição de identidade.

O jogador controla se sua figurinha aparece aos colegas do grupo. A exibição é opcional e inicialmente privada. Mesmo quando compartilhada, mostrar apenas a figurinha e os elementos escolhidos; contadores, progresso e histórico permanecem pessoais. Não criar uma página ordenada por conquistas.

A apresentação da figurinha é independente do modal atual de avaliações. Não colocar a nota ou gols na frente, no verso, em atributos da figurinha ou em sua descrição acessível. Remover o sistema de avaliações existente seria outra tarefa.

### Nova conquista

Apresentar um aviso dentro do aplicativo na próxima consulta: **Você conquistou “Figurinha carimbada” por participar de 10 peladas neste grupo.** A ação é **Ver conquista**.

Registrar o recebimento do aviso por jogador para que ele não reapareça a cada atualização. O aviso pode ser fechado e a conquista continua disponível na coleção. Usar uma transição curta, sem interromper a confirmação de presença ou o uso da escalação; respeitar movimento reduzido.

### Estados necessários

Programa desativado, coleção vazia, presença aguardando conferência, conquista bloqueada, conquista obtida, correção de crédito, carregamento, erro com nova tentativa e figurinha privada. Texto em português, foco visível, navegação por teclado e toque, temas claro/noturno e layout de celular.

## Estrutura técnica proposta

Criar um serviço de participação e conquistas separado de `Matches`, com estas responsabilidades:

| Registro | Responsabilidade |
| --- | --- |
| Configuração do programa por grupo | Ativação, data inicial e elegibilidade dos eventos |
| Conferência por pelada | Estado, versão, organizador e data da revisão |
| Comparecimento por pelada/jogador | Resultado da conferência, com unicidade e histórico de correções |
| Conquista por grupo/jogador/código | Data de concessão, vínculo com o marco e confirmação de leitura |
| Figurinha por grupo/jogador | Preferências visuais e visibilidade |

O catálogo com quatro regras pode ficar versionado no código. Não criar editor de regras, moeda virtual, loja ou sistema genérico de missões na primeira entrega.

As consultas de progresso derivam dos comparecimentos válidos. Não manter um contador independente que possa se desalinhar. Concessões têm restrição de unicidade no banco, e submissões concorrentes usam bloqueio/versão compatível com o padrão atual.

Prever operações autenticadas para configurar o programa, consultar e salvar/corrigir a conferência, consultar a própria carreira, salvar preferências da figurinha, marcar avisos como vistos e consultar uma figurinha compartilhada dentro do grupo. Somente o organizador escreve conferências; somente o próprio jogador edita sua figurinha. O servidor valida itens desbloqueados e todas as permissões.

Separar contratos de carreira dos contratos de avaliação. No frontend, extrair componentes próprios para figurinha, coleção e conferência, usando Angular standalone, signals, CSS e os elementos locais existentes.

## Ordem de implementação e critérios de aceite

### Entrega 1 — base confiável de comparecimento

Migração, configuração por grupo, conferência e correções, permissões e auditoria. Implementar a tela do organizador e o estado **Aguardando conferência** para o jogador.

Aceite: uma confirmação sem conferência não gera crédito; funciona com dois ou mais times e sem placar; cancelados não contam; reenvio não duplica; autorização e correções estão testadas. Não alterar confirmação, espera, escalação, convite de goleiro ou financeiro.

### Entrega 2 — coleção pessoal completa

Implementar as quatro conquistas, concessão/recalculo, Minha carreira, figurinha com iniciais, personalização e avisos. Esta entrega conclui a primeira versão utilizável do programa.

Aceite: marcos 1/5/10/25 corretos; avanços restritos ao grupo; nenhuma leitura de gols ou avaliações para calcular recompensas; escolhas de itens bloqueados rejeitadas no servidor; progresso privado; acesso do convidado limitado aos próprios registros.

### Entrega 3 — piloto

Ativar em um grupo voluntário e acompanhar quatro a seis encontros. Conferir se o organizador consegue revisar os participantes sem esforço excessivo e se as pessoas entendem e gostam das conquistas.

Observar comparecimentos verificados, retorno de participantes e proporção de eventos conferidos. Comparar com dados válidos disponíveis e ouvir a turma; confirmações anteriores não são uma medida confiável de comparecimento. Não prometer aumento de participação nem usar notas, placar ou gols como critério de sucesso.

### Evolução posterior — desafio pessoal opcional

Somente depois do piloto, experimentar **Neste mês, quero participar de 2 peladas**, com objetivo escolhido pelo jogador, progresso privado e selo mensal. O cálculo usa a data da pelada no fuso do grupo, não a data em que o organizador conferiu a presença.

Não exigir sequência, retirar selos por meses sem presença ou usar mensagens de cobrança. Considerar um desafio coletivo apenas se a turma demonstrar interesse; ele não expõe quem ficou abaixo de uma meta.

## Verificação prevista

- Testes Java com PostgreSQL real: autorização; elegibilidade; ativação/pausa; cancelamento; unicidade; concorrência; correções e recalculo; separação entre grupos; convidados; marcos e validação de personalização.
- Testes de independência: mudar gols, notas, resultado, posição ou pagamento não muda conquistas nem progresso.
- Testes de regressão nos fluxos de presença, espera, times, partida ao vivo e goleiros convidados.
- Playwright: ativar programa, conferir, consultar conquista, personalizar e corrigir; cobrir celular, teclado, claro/noturno e privacidade.
- Inspeção visual: figurinha e coleção preservam os estilos do produto, sem informações esportivas de desempenho, sem ordenação comparativa e sem efeito que atrapalhe a operação.

## Escopo da proposta

A primeira implementação cobre as entregas 1 e 2. O desafio mensal e outros elementos ficam para depois da experiência real com o grupo.

## Implementação entregue

- Migração `V12__participation_career.sql` com períodos ativos, conferências, comparecimentos, auditoria, conquistas e preferências da figurinha.
- Serviço `Career` independente de placar, avaliações, posição e financeiro. Bloqueios seguem a ordem grupo → pelada; concessões e correções são transacionais.
- Reenvio idêntico da conferência não cria novo crédito ou auditoria. Uma edição diferente com versão desatualizada exige recarregar os dados.
- Cancelamentos deixam de contribuir imediatamente nas consultas de progresso. A coleção e as preferências são reconciliadas ao consultar ou personalizar, removendo recompensas que perderam sua condição e oferecendo um aviso privado de correção.
- Catálogo fixo de quatro conquistas. O progresso deriva dos comparecimentos válidos; `observed_count` na figurinha serve apenas para detectar correções e não é a fonte do contador.
- Angular standalone: `CareerPage`, `AttendanceReviewPage` e `Figurinha`, com CSS próprio e recursos locais. Acesso pelo menu, aba do grupo e aba da pelada; compartilhamento consultável na lista de jogadores.
- Conferência de presença restrita ao organizador, resumo antes de salvar, seleção em lote explícita e histórico de revisões. Não altera escalação, vagas, cobranças ou estado da partida.
- Figurinhas compartilhadas retornam somente nome, identificação, foto opcional, título, moldura, selos escolhidos e visibilidade. Não retornam contadores, progresso, histórico, gols, notas ou datas das conquistas.
- Conquistas inéditas e correções aparecem em Minha carreira; fechar o aviso fica salvo no servidor.

### Executar a verificação de navegador com datas de teste

`e2e/career-ui.spec.ts` cobre falha de leitura, nova tentativa e recompensas bloqueadas sem precisar manipular datas do servidor. Integra a suíte normal.

`e2e/career.spec.ts` cobre o fluxo completo com backend e banco reais. Como não existem rotas de teste em produção, o cenário altera exclusivamente horários de registros fictícios pelo cliente PostgreSQL. Executar somente em uma instância local ligada ao banco descartável **pelada_career_dev**, separado do banco dos testes Java.

Configurar `BASE_URL` para essa instância em `127.0.0.1`, `CAREER_E2E_DATABASE=pelada_career_dev`, `CAREER_E2E_PSQL` com o caminho do executável `psql` e, se necessário, `CAREER_E2E_PORT` (padrão 55434). Os cenários reais são ignorados se esse contexto não estiver configurado. O cliente PostgreSQL confirma o nome do banco antes de alterar as datas e opera somente sobre os identificadores criados pelo próprio teste.

Os testes Java usam `TEST_DATABASE_URL` e preservam a orientação do projeto: apontar exclusivamente para um banco descartável de testes. As capturas em `docs/screenshots/carreira-*` e `presencas-celular.png` foram geradas pela aplicação com dados fictícios, sem mídia externa.

### Validação local — 08/10/2026

- Compilação Angular de produção concluída, sem novas dependências; pacote inicial de aproximadamente 568 kB dentro do orçamento atual.
- Checagem de formatação Prettier e `git diff --check` aprovadas.
- 73 testes Java aprovados em PostgreSQL 18, incluindo 10 cenários de carreira e os fluxos existentes.
- 40 cenários Chromium aprovados: 32 cenários na execução geral e 8 de administração após configurar o acesso e a coleta de visitas exigidos por essa suíte na instância local.
- A verificação inclui confirmação versus comparecimento, quatro marcos, reenvio/concorrência, correções, cancelamentos, ativação/pausa, ausência de histórico retroativo, privacidade, convidados, separação entre grupos, cosméticos bloqueados, teclado, temas, celular e recuperação de erro.
- Inspeção das capturas reais em desktop e celular, incluindo modo noturno. As imagens utilizam dados fictícios.

Para executar a suíte completa do navegador, configurar também a instância descartável com `ADMIN_EMAILS=admin.e2e@example.com` e `VISIT_STATS_ENABLED=true`, conforme os cenários já existentes de administração. Essas configurações pertencem ao ambiente de testes e não foram adicionadas à configuração de produção.

A entrega foi verificada localmente e não foi publicada em um serviço externo. O piloto depende de uma turma real e não é substituído pelos testes automatizados.

### Perfil geral da conta

O nome ou avatar no topo abre Minha carreira geral, com edição do nome, foto opcional e coleções por grupo. O e-mail de acesso fica visível apenas para a própria pessoa e não muda neste formulário. As alterações de nome e foto são independentes dos títulos e selos de cada grupo; não usam avaliações, gols ou rankings.

A migração V13 adiciona a versão da foto à conta e guarda os bytes em uma tabela separada, com exclusão em cascata. O servidor verifica o conteúdo real JPG/PNG, limita o arquivo a 2 MB e a imagem a 16 milhões de pixels, recorta no centro e gera uma imagem PNG de até 512 × 512 sem metadados originais. Não há mídia externa nem novas dependências.

Alterações exigem sessão e proteção CSRF. A imagem exige autenticação: a própria pessoa pode consultá-la; outros jogadores só têm acesso se forem membros atuais de um grupo em que a figurinha esteja compartilhada e o titular também seja membro. A resposta usa `Cache-Control: no-store`; trocar ou remover a foto invalida a URL anterior. As figurinhas privadas e as coleções de ex-membros continuam privadas.

`AccountProfileTest` verifica persistência, nome e identidade de acesso, normalização JPG/PNG, substituição, remoção, arquivos inválidos, dimensões excessivas e privacidade. `e2e/account.spec.ts` cobre acesso por teclado ao perfil, gravação real, recarregamento, prévia/cancelamento, remoção, falha de gravação, CSRF e layout no celular. As capturas `perfil-geral-*` usam uma imagem de teste criada no próprio cenário.

Validação do perfil em 08/10/2026: compilação Angular de produção e pacote Java concluídos; 78 testes Java e 42 cenários Chromium aprovados na execução final, incluindo os fluxos existentes. Formatação e `git diff --check` aprovados. Capturas revisadas em desktop claro e celular noturno, com o avatar acessível no topo. Implementação local, sem publicação externa.
