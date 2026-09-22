# Publicar no Render com Neon

**Situação atual:** a aplicação funciona localmente. Nenhum serviço pago foi contratado e não existe endereço público criado. Para esta etapa, o proprietário precisa criar e acessar as contas no Render e no Neon.

## 1. Preparar o repositório

Publique o conteúdo desta pasta em um repositório Git no seu GitHub, GitLab ou Bitbucket. A raiz precisa conter `Dockerfile`, `render.yaml`, `backend/` e `frontend/`.

O `.gitignore` exclui dependências, builds, resultados temporários e arquivos `.env`. As imagens da documentação e o `pnpm-lock.yaml` devem ser incluídos. Não inclua senhas ou strings de conexão que contenham credenciais.

## 2. Criar o banco no Neon

1. Crie a conta no [Neon](https://neon.com/) e escolha o plano **Free**.
2. Crie um projeto para a aplicação. Prefira uma região próxima à do serviço Render; a configuração incluída usa Virginia.
3. Crie ou use um banco dedicado, por exemplo `neondb`.
4. Abra os detalhes de conexão. Para esta primeira instância com apenas cinco conexões, use o endpoint **direto**, sem `-pooler`, inclusive para as migrações Flyway.
5. Separe o host, banco, usuário e senha. A senha deve ser inserida diretamente no painel do Render.

## 3. Criar o serviço no Render

1. Entre no [Render](https://dashboard.render.com/) e conecte o repositório.
2. Use **New → Blueprint** e selecione o repositório com `render.yaml`.
3. Confira que o único recurso criado é um **Web Service**, com runtime **Docker** e plano **Free**.
4. Preencha as três variáveis solicitadas:

| Variável | Valor |
| --- | --- |
| `JDBC_DATABASE_URL` | `jdbc:postgresql://SEU-HOST.neon.tech:5432/SEU-BANCO?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` |
| `DB_USER` | Usuário mostrado pelo Neon |
| `DB_PASSWORD` | Senha do usuário do banco |

Não cole uma URL `postgresql://usuario:senha@host/...` diretamente em `JDBC_DATABASE_URL`. O aplicativo espera o formato JDBC e recebe as credenciais separadamente. `DefaultJavaSSLFactory` usa as autoridades certificadoras do Java e `verify-full` valida o certificado e o hostname.

O arquivo já configura `COOKIE_SECURE=true` e `DEMO_ENABLED=true`. Não defina uma porta fixa no painel: o aplicativo lê `PORT`, fornecida pelo Render.

5. Inicie o deploy e acompanhe os logs. O build compila primeiro o Angular, depois empacota o Spring Boot. O runtime usa Java 21 e usuário sem privilégios.
6. Na primeira inicialização, o Flyway cria o esquema; em seguida, a aplicação insere os dados fictícios de demonstração.
7. Use o endereço HTTPS `*.onrender.com` que aparecer no painel.

Também é possível usar **New → Web Service** e configurar os mesmos campos manualmente. Não crie um banco adicional no Render: o banco escolhido para este projeto é o Neon.

## 4. Conferir antes de divulgar

- A página inicial mostra a demonstração com campo e duas equipes.
- `/api/health` responde `{"status":"UP"}`.
- `/api/openapi` responde o documento da API.
- Cadastro e login funcionam pelo endereço HTTPS.
- Crie um grupo e um evento de teste; confirme a entrada de outra conta por convite.
- Defina um capitão, escolha jogadores, salve uma escalação e recarregue a página.
- Um participante comum consegue consultar, mas não editar a escalação.
- Confira no navegador o cookie `SESSION` com `Secure`, `HttpOnly` e `SameSite=Lax`.
- Reinicie o serviço pelo painel e confirme que grupo, escalação e sessão continuam no PostgreSQL.

## 5. Operação do portfólio

O plano Free do Render hiberna após inatividade. A primeira abertura pode exibir a página de inicialização do próprio Render enquanto o processo Java volta a funcionar. Os dados e sessões permanecem no Neon.

Não habilite pings artificiais para impedir a hibernação. Acompanhe os limites de uso nos painéis, mantenha os planos gratuitos e não habilite upgrades automáticos. Os limites dos provedores podem mudar.

O health check serve para confirmar que o processo web iniciou; não é monitoramento contínuo da disponibilidade do banco. Falhas e mensagens de inicialização ficam nos logs do serviço. O rollback de um deploy não deve desfazer migrações de banco; evolua o esquema com novas migrações compatíveis.

Referências: [Blueprints do Render](https://render.com/docs/blueprint-spec), [serviços gratuitos](https://render.com/docs/free), [JDBC PostgreSQL](https://jdbc.postgresql.org/documentation/use/).
