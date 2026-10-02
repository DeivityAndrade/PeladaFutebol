# Administração do site

A rota `/#admin` mostra o total de contas reais, grupos não demonstrativos, novos cadastros
nas últimas 168 horas e no mês de Brasília, além do histórico dos últimos 6 ou 12 meses.
Uma conta é contada uma vez, independentemente do número de grupos a que pertence.
O endpoint `GET /api/admin/summary` retorna apenas agregados e não permite cache.
Não lista nomes, e-mails, senhas, comprovantes ou dados financeiros.

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
| `mvn -B -ntp -f backend/pom.xml verify` | 59 testes aprovados; pacote executável gerado |
| `BASE_URL=http://127.0.0.1:8083 pnpm test:e2e` (frontend) | 35 testes aprovados, incluindo os 7 novos cenários |
| `pnpm format:check` (frontend) | Formatação aprovada |
| `git diff --check` | Sem erros de espaços ou conflitos |

Além dos temas e capturas, o navegador verificou larguras de 320, 390, 768, 1024, 1280 e 1440 px,
foco por teclado e ausência de rolagem horizontal. A configuração de produção continua sendo
uma etapa separada: publique a versão e configure o e-mail de uma conta real em `ADMIN_EMAILS`.
