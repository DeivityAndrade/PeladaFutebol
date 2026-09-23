# Validação — 22/09/2026

## Resultado

**20 verificações passaram:** 16 cenários Java com PostgreSQL e 4 cenários de navegador com
Chromium. O Angular compilou na CI; a checagem Prettier passou localmente. Os dois checks do
pull request passaram.

Ambiente de CI: GitHub Actions, Java 21, PostgreSQL 18 e Chromium. A aplicação testada pelo
navegador é o pacote de produção do Spring Boot, que serve o Angular na mesma origem.

### Regras de negócio — JUnit

1. Lotação, fila e promoção do primeiro inscrito.
2. Duas confirmações simultâneas disputando a última vaga.
3. Confirmação e desistência repetidas sem duplicação.
4. Dois capitães escolhendo simultaneamente a mesma pessoa, com apenas um vencedor.
5. Escalações incompletas, trocas de posições e persistência das três formações.
6. Rejeição de posições duplicadas, jogadores de outro elenco e revisão antiga.
7. Rejeição de acesso de não membros e edições por pessoas sem permissão.
8. Saída de capitão, limpeza da escalação e indicação de substituto.
9. Liberação para outros times sem cancelar a presença.
10. Rejeição de escolha quando o elenco está cheio.
11. Bloqueio de eventos cancelados ou de presenças após o horário marcado.
12. Proteção da demonstração, inclusive contra seu próprio organizador.
13. Início após o horário, distribuição de todos os confirmados e bloqueio das escalações.
14. Proteção contra dois inícios simultâneos.
15. Gol contra, anulação, correção de placar e duração congelada após o fim.
16. Permissões, privacidade, prazo de 24 horas e média geral com peso igual por pelada.

### Navegador — Playwright

1. Demonstração, tela móvel, partida concluída, placar, linha do tempo, notas e modal de acesso
   que preserva o formulário ao clicar fora, com foco por teclado, Escape e retorno à demonstração.
2. CSRF, autenticação, acesso a grupo privado, validação, cookie de sessão, OpenAPI e logout.
3. Cadastro, grupo, convite, confirmação, capitães, elencos, três formações, trocas de posições,
   persistência, teclado, celular e permissão de edição.
4. Partida ao vivo em duas contas: início, cronômetro após recarregar, gol, placar sincronizado,
   encerramento, avaliação e rejeição de nota fora do time.

### Telas

As imagens base da aplicação, capturadas em **1440 px** e **390 px**, estão em:

- [Desktop](screenshots/desktop.png)
- [Celular](screenshots/mobile.png)

## Limites

- O fluxo completo passou na CI. A publicação da nova versão e a verificação pública do Render
  acontecem após integrar o pull request.
- Firefox, Safari, aparelhos físicos, teste de carga e auditoria formal de acessibilidade não foram
  executados.
- Os testes usam contas fictícias; nenhum dado de jogador real foi incluído.
