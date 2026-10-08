# Publicação principal na Vercel

Desde **08/10/2026**, por decisão do proprietário, a Vercel é o endereço principal para usar o site e publicar as próximas entregas da interface. O projeto existente é `deivity/todentro-vercel-teste`, plano Hobby.

- **Endereço principal:** https://todentro-vercel-teste.vercel.app
- **Servidor temporário da API:** Render, acessado pela interface através de `/api/*` na Vercel.
- **Painel da Vercel:** https://vercel.com/deivity/todentro-vercel-teste

## Como funciona

O Angular é compilado e servido pela Vercel. As chamadas `/api/*` são encaminhadas
ao backend Java/Spring Boot no Render, preservando `/api` no destino. O navegador
continua acessando interface e API pela mesma origem. O banco continua no Neon.

**Os dois endereços compartilham os mesmos dados.** A versão de teste não é uma
instância isolada: alterações feitas em grupos, presenças, escalações e pagamentos
aparecem nos dois endereços. É possível usar a conta existente; o login deve ser
feito novamente em cada domínio, porque os cookies são independentes.

A carreira, o perfil geral, as fotos e as demais funcionalidades usam esse mesmo backend. A escolha atual é manter o servidor no Render por enquanto; isso não constitui uma migração completa do servidor para a Vercel. O endereço do Render deixa de ser o link principal entregue ao usuário.

O arquivo `frontend/vercel.json` configura o build, o proxy da API, a política de
conteúdo, os cabeçalhos de segurança e a resposta Angular para as rotas da interface.
Os dados da API não entram no cache compartilhado da Vercel. O endereço de teste
recebe `X-Robots-Tag: noindex, nofollow` para evitar indexação durante a comparação.

Render, Docker e variáveis do backend foram preservados. Nenhuma credencial do Neon
ou do Brevo foi enviada à Vercel. A variável `PUBLIC_APP_URL` do Render também foi
preservada: a recuperação de senha continua usando o endereço já configurado no
backend. Mudar esse endereço faz parte de uma futura decisão sobre o domínio principal.

## Administração e contagem de visitas

A publicação de teste foi atualizada com o painel já integrado à branch principal.
Depois de entrar com uma conta autorizada, **Administração** aparece no menu;
o endereço direto é `https://todentro-vercel-teste.vercel.app/#admin`.
O painel consulta contas, grupos, novos cadastros, visitas e histórico mensal no
mesmo backend e banco usados pelo Render. Os números dos dois endereços são agregados.

A autorização depende de `ADMIN_EMAILS` no **Render**, não na Vercel.
Configure essa variável com o e-mail da conta administradora e publique novamente
o serviço; depois, saia e entre no site. A liberação para o e-mail solicitado pelo
proprietário ainda não foi aplicada: as ferramentas recusaram acesso ao Chrome
e ao painel do Render. Não foi inserido um e-mail padrão no código.

As visitas usam uma janela de 30 minutos por cookie anônimo; não representam
pessoas únicas nem recuperam acessos anteriores à coleta. Contas antigas sem data
continuam no total, mas não no histórico de novos cadastros.
Consulte [ADMINISTRACAO.md](ADMINISTRACAO.md) para configuração e limites das métricas.

## Publicar as próximas entregas

A primeira publicação foi feita diretamente pela CLI autenticada. O vínculo automático
com o GitHub não foi concluído porque a conta da Vercel precisa de uma conexão de login
com o GitHub. **Por enquanto, novos commits não publicam automaticamente este projeto.**

Para atualizar manualmente, use Node.js 24 e execute a partir de `frontend/`:

```sh
npx vercel@62.2.0 link --yes --project todentro-vercel-teste --scope deivity
npx vercel@62.2.0 deploy --prod --yes --scope deivity
```

O `--prod` atualiza o endereço principal estável na Vercel.
O servidor existente no Render continua atendendo a API. Para um preview adicional,
omita `--prod`; ele pode exigir autenticação da Vercel.

Os arquivos `.vercel/` e `.env.local`, criados pela CLI, são locais e ignorados
pelo Git. `frontend/.vercelignore` exclui dependências, builds, testes e arquivos
de ambiente do envio.

Se depois for habilitada a integração com o repositório, configure **Root Directory =
frontend** no painel. Essa configuração também muda a forma de usar a CLI: passe a
executá-la na raiz do repositório, com o projeto vinculado nessa raiz. Selecione a
branch desejada para produção somente depois que esta configuração estiver no Git.

## Verificação repetível

A partir de `frontend/`:

```sh
pnpm check:hosting https://todentro-vercel-teste.vercel.app
```

A verificação consulta a página, JavaScript, CSS, health check, demonstrações,
token CSRF e rotas que exigem login. Confere JSON, cache desabilitado na API,
política de conteúdo e o cookie `SESSION` com `Secure`, `HttpOnly`, `SameSite=Lax`
e `Path=/`, sem um domínio fixo. Não cria contas, grupos, jogos nem pagamentos.
Os valores de sessão são mantidos somente na memória e não aparecem no relatório.

### Resultado inicial

- Build local e publicação da Vercel concluídos.
- Verificação de entrega e API aprovada, incluindo cabeçalhos na raiz `/`.
- API privada respondeu `401` para visitante e as demonstrações responderam `200`.
- Cookie seguro e token CSRF foram encaminhados pelo proxy.
- Login sem token CSRF respondeu `403`; com token e uma credencial inexistente,
  respondeu `401` com a mensagem esperada. Nenhuma conta foi criada.
- Testes locais, com API simulada: navegação por teclado na agenda e fluxo de
  recuperação de senha aprovados. O teste simulado não envia e-mail real.
- A abertura automática do site publicado no navegador foi bloqueada pela política
  de permissão do navegador. A inspeção visual do endereço publicado ficou pendente.

### Atualização com administração — 08/10/2026

- Última interface da branch principal publicada na Vercel; API do Render já contém
  administração e coleta de visitas.
- Nove testes Java de administração e visitas aprovados em PostgreSQL local isolado.
- Oito testes de navegador aprovados na instância local: computador e celular,
  temas claro e noturno, teclado, atualização, saída, restrição de acesso,
  tratamento de erro, deduplicação de visitas e respeito a `DNT`.
- Inspeção visual da captura local em celular concluída.
- Verificação HTTP da Vercel aprovada novamente; `/api/admin/summary` respondeu
  `401` sem login e `no-store`, como esperado.
- A configuração `ADMIN_EMAILS` da conta proprietária permanece pendente no Render.

Login com uma conta real, upload de comprovantes, escalações e alterações na partida
ao vivo ainda precisam de conferência durante o uso. Não execute a suíte completa
Playwright neste endereço: ela cria dados e o banco é compartilhado com a aplicação atual.

### Tempos observados

Três rodadas sequenciais de requisições HTTP, com o backend já ativo, na mesma máquina:

| Consulta | Vercel (ms) | Render (ms) |
| --- | --- | --- |
| HTML inicial | 138 / 47 / 30 | 595 / 176 / 172 |
| `/api/health` | 577 / 194 / 212 | 456 / 183 / 189 |

São tempos de resposta HTTP, sem renderização da tela. Esta amostra curta mostra
entrega mais rápida do HTML pela Vercel, mas não demonstra melhora da experiência
completa. O backend continua sendo o mesmo. Antes dessas rodadas, a primeira
consulta ao Render levou aproximadamente 41 segundos; a causa não foi confirmada.

## Migração completa do servidor

A migração do backend é uma tarefa separada da publicação da interface. A Vercel documenta containers em beta em todos os planos, o que permite estudar a execução do servidor Java mantendo o Neon. Essa etapa precisa verificar inicialização, banco, Flyway, sessões e limites do ambiente. O proprietário escolheu manter o backend atual por enquanto.

Não desligue o servidor enquanto `/api/*` depender dele. A publicação da interface deve ser verificada pelo endereço da Vercel; use `pnpm check:hosting` para conferir entrega, API e sessão sem criar dados em produção.

## Carreira e perfil publicados na Vercel — 08/10/2026

A interface integrada no PR #18 foi publicada no endereço principal pelo deployment `dpl_HuLHFgV8bpp9Ny3GzSdf85zTtFja`, concluído como `READY`. Minha carreira geral abre pelo nome/avatar e também por `/#career`, com edição do nome e foto e coleções dos grupos. As abas de carreira e conferência de presenças estão disponíveis nos grupos e peladas.

A verificação HTTP no endereço principal aprovou HTML, JavaScript, CSS, API, demonstrações, sessão segura, cache privado e proteção das rotas que exigem login. O pacote publicado é `main-4TQLJV2N.js`. Nenhuma conta ou grupo de teste foi criado em produção.

Referências oficiais: [rewrites da Vercel](https://vercel.com/docs/routing/rewrites),
[containers da Vercel](https://vercel.com/docs/functions/container-images) e
[serviços gratuitos do Render](https://render.com/docs/free).
