# Administração do site

A rota `/#admin` mostra o total de contas reais, grupos não demonstrativos, novos cadastros
nas últimas 168 horas e no mês de Brasília, além do histórico dos últimos 6 ou 12 meses.
Uma conta é contada uma vez, independentemente do número de grupos a que pertence.
O endpoint `GET /api/admin/summary` retorna apenas agregados e não permite cache.
Não lista nomes, e-mails, senhas, comprovantes ou dados financeiros.

## Visitas ao site

O painel mostra visitas totais, hoje, hoje e os seis dias anteriores, mês corrente e histórico
mensal. Use **Visitas** no histórico para trocar a métrica; **Cadastros** continua disponível.
Os dias usam `America/Sao_Paulo`. A contagem começa no primeiro acesso após a publicação da V11;
não recupera visitas anteriores nem transforma cadastros em visitas.

O Angular envia `POST /api/visits` ao carregar a aplicação, sem bloquear os fluxos se houver erro.
A rota aceita acesso anônimo, exige o CSRF da mesma origem e não retorna estatísticas públicas.
Navegação entre abas e atualizações automáticas não registram uma nova visita.
Um cookie próprio `pelada_visit`, aleatório, HttpOnly, SameSite=Lax, com Secure conforme a configuração
existente, evita duplicar recargas na mesma janela de 30 minutos. O servidor é a autoridade dessa
janela; requisições simultâneas com o mesmo identificador contam uma vez. Depois dos 30 minutos,
o próximo carregamento registra outra visita. É uma janela fixa, não um contador de tempo ativo.

No banco ficam somente totais por dia e o hash do identificador anônimo de curta duração.
Hashes vencidos são apagados na próxima coleta; não são relacionados a contas ou sessões de login.
Não são armazenados IP, URL, referência de origem, agente do navegador ou localização.
Solicitações com `DNT: 1` ou `Sec-GPC: 1` são ignoradas, sem emitir cookie de visitas.
`VISIT_STATS_ENABLED=false` desativa a coleta sem remover os totais já registrados.

São números aproximados de visitas, **não pessoas únicas**: incluem demonstração, administração
e possíveis robôs; cookies bloqueados/apagados, outros navegadores ou duas primeiras abas abertas
simultaneamente sem cookie podem produzir novas visitas. Falhas de rede ou controles de privacidade
podem reduzir a contagem. Não se trata de uma medida de audiência auditada.

Não há serviços externos, novas dependências ou configuração paga para essa funcionalidade.

## Liberar acesso

O acesso depende da sessão autenticada e de `ADMIN_EMAILS`, configurada **no servidor**.
O valor é uma lista de e-mails separados por vírgula. Vazio bloqueia toda a área.
Cadastre apenas e-mails de contas existentes, de confiança e sob seu controle: esta configuração
concede acesso a quem consegue entrar nessas contas. Não existe opção de conceder privilégios
pelo cadastro, pela interface ou por ser organizador de um grupo.

No Render, abra o **serviço web pelada → Environment → Edit**, adicione `ADMIN_EMAILS` com
o e-mail usado para entrar no site e escolha **Save, rebuild, and deploy**. Não crie um serviço novo
nem altere o banco manualmente. O Blueprint não sobrescreve essa configuração.
Depois da publicação, saia e entre novamente: **Administração** aparecerá no menu principal.
Remover um e-mail da configuração e reiniciar o serviço revoga também a consulta de sessões existentes.

Para desenvolvimento, defina a variável no processo que inicia o backend.
A suíte de navegador exige `ADMIN_EMAILS=admin.e2e@example.com` na instância **de testes**;
a CI já configura esse valor. Não use essa conta de teste na produção.

## Datas e demonstração

A migração V10 acrescenta `players.created_at`, sem preencher datas de contas já existentes.
A partir desta versão, o cadastro grava o instante no servidor. Contas antigas entram no total,
mas aparecem como sem data e não entram no histórico nem nos novos cadastros. Não é possível
recuperar uma data que não foi armazenada nas versões anteriores.

Contas com senha desabilitada (`!disabled`) e os endereços fictícios `demo-…@example.invalid`
não entram nos agregados. Grupos marcados como demonstração também ficam de fora.
Os meses sem cadastros aparecem com zero; o período corrente vai até o instante da consulta.
As consultas usam uma transação com leitura consistente e não geram alterações no banco.

Os números apresentados nas capturas de tela são da instância local de testes, não da produção.

- [Computador, tema claro](screenshots/administracao-desktop-claro.png)
- [Computador, tema noturno](screenshots/administracao-desktop-noturno.png)
- [Celular, tema claro](screenshots/administracao-celular-claro.png)
- [Celular, tema noturno](screenshots/administracao-celular-noturno.png)

## Verificações

Os testes Java verificam autenticação, autorização, configuração vazia, ausência de dados pessoais,
proteção contra concessão de privilégios no cadastro, datas antigas, exclusão da demonstração,
limites do mês em Brasília e janela de sete dias. Os testes Playwright verificam navegação,
atualização dos totais, recarga, saída, erros e bloqueios, nos dois temas em computador e celular.

Validação local concluída em 01/10/2026, com bancos separados para testes Java e navegador:

| Comando | Resultado |
| --- | --- |
| `pnpm build` (frontend) | Build de produção aprovada |
| `mvn -B -ntp -f backend/pom.xml verify` | 63 testes aprovados; pacote executável gerado |
| `BASE_URL=http://127.0.0.1:8083 pnpm test:e2e` (frontend) | 36 testes aprovados, incluindo os 8 cenários de administração e visitas |
| `pnpm format:check` (frontend) | Formatação aprovada |
| `git diff --check` | Sem erros de espaços ou conflitos |

Além dos temas e capturas, o navegador verificou larguras de 320, 390, 768, 1024, 1280 e 1440 px,
foco por teclado e ausência de rolagem horizontal. A configuração de produção continua sendo
uma etapa separada: publique a versão e configure o e-mail de uma conta real em `ADMIN_EMAILS`.
