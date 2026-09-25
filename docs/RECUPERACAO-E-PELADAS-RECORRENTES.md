# Recuperação de senha e peladas semanais

## Recuperação de senha

O fluxo pede o e-mail da conta, envia um link com token aleatório de uso único e permite cadastrar a senha nova duas vezes. A API responde da mesma forma para e-mails existentes e inexistentes. O token expira em 30 minutos; somente seu SHA-256 é salvo no PostgreSQL, um novo pedido invalida o anterior e a troca da senha encerra todas as sessões da conta. Senhas e tokens não são escritos nos logs da aplicação.

Os pedidos são limitados a três por e-mail e dez por endereço IP em uma janela de uma hora. O endereço é resolvido pelo Spring a partir dos cabeçalhos encaminhados pelo proxy configurado; o e-mail e o IP não são gravados na tabela de limites, que guarda apenas hashes dos identificadores.

Configure estas variáveis no ambiente do backend:

| Variável | Uso |
| --- | --- |
| `MAIL_HOST` | Servidor SMTP |
| `MAIL_PORT` | Porta SMTP, normalmente `587` |
| `MAIL_USERNAME` | Usuário SMTP |
| `MAIL_PASSWORD` | Senha ou chave SMTP |
| `MAIL_FROM` | Remetente autorizado pelo provedor |
| `PUBLIC_APP_URL` | Endereço público da aplicação, sem barra final |

O SMTP usa autenticação e STARTTLS obrigatório. Se qualquer configuração necessária estiver ausente, a solicitação retorna HTTP 503 e não é apresentada como envio bem-sucedido. O arquivo `.env.example` contém os nomes e valores de exemplo; nunca coloque credenciais reais no Git. Em desenvolvimento local, use uma caixa SMTP de teste. Na publicação, cadastre os valores como variáveis privadas no serviço do backend.

## Peladas semanais

Ao marcar uma pelada, o organizador pode habilitar a repetição semanal e, opcionalmente, informar a última data. O dia da semana e o horário são definidos pela primeira ocorrência, no fuso horário salvo no grupo. Grupos antigos usam `America/Sao_Paulo`; grupos novos começam com o fuso escolhido na criação.

O sistema persiste no máximo oito ocorrências futuras da série por vez. A primeira é criada junto com a série. Quando um membro abre a agenda do grupo, o backend, dentro de uma transação e com bloqueio do grupo, completa a janela até oito futuras ou até atingir a data final. A restrição única no banco impede duplicação se a geração for repetida. Não há cron ou geração ilimitada em segundo plano: enquanto o grupo não consultar a agenda, não são criadas ocorrências adicionais.

Cada ocorrência é uma pelada independente, com suas próprias presenças, fila de espera, times, capitães, escalação, cobranças e partida ao vivo. Uma alteração somente daquela data registra uma exceção e não desloca as demais. Uma alteração desta e das próximas move a âncora da série a partir da ocorrência escolhida, mantendo ocorrências anteriores e exceções individuais. Os cancelamentos seguem a mesma escolha; cancelar somente uma data também a registra como exceção. Ocorrências passadas permanecem preservadas.

A migração `V7__password_recovery_and_weekly_games.sql` adiciona o fuso do grupo, tokens e limites de recuperação, séries e vínculo das ocorrências. A aplicação executa a migração automaticamente pelo Flyway ao iniciar.
