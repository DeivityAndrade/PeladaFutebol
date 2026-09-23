# Validação — 22/09/2026

## Resultado

O Angular compilou e a checagem Prettier passou localmente. A nova suíte Java com PostgreSQL e os
cenários Playwright desta atualização aguardam execução na integração contínua.

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
17. Sorteio equilibrado, capitães fixos, espera excluída e limpeza das posições antigas.
18. Confirmações ou desistências após o sorteio sem reorganização automática; bloqueio após o início.
19. Sorteios simultâneos serializados no banco.
20. Recorrência mensal em meses curtos, janela de seis edições e reposição após cancelamento.
21. Recorrências a cada dois e três meses calculadas pela data inicial.
22. Pausa sem geração de novas edições e recomposição da janela ao retomar.
23. Presença de churrasco independente da pelada, edição isolada, convite de convidado e acesso restrito.

### Navegador — Playwright

1. Demonstração, tela móvel, partida concluída, placar, linha do tempo, notas e modal de acesso
   que preserva o formulário ao clicar fora, com foco por teclado, Escape e retorno à demonstração.
2. CSRF, autenticação, acesso a grupo privado, validação, cookie de sessão, OpenAPI e logout.
3. Cadastro, grupo, convite, confirmação, capitães, elencos, três formações, trocas de posições,
   persistência, teclado, celular e permissão de edição.
4. Partida ao vivo em duas contas: início, cronômetro após recarregar, gol, placar sincronizado,
   encerramento, avaliação e rejeição de nota fora do time.
5. Sorteio e novo sorteio; recorrência de churrasco; confirmação de membro que não vai jogar;
   convite de edição para convidado, confirmação no celular e verificação de que ele não entrou no grupo.

### Telas

As imagens base da aplicação, capturadas em **1440 px** e **390 px**, estão em:

- [Desktop](screenshots/desktop.png)
- [Celular](screenshots/mobile.png)

## Limites

- Os fluxos novos aguardam a CI. Depois que passarem, a publicação e a verificação pública do Render
  acontecem após integrar o pull request.
- Firefox, Safari, aparelhos físicos, teste de carga e auditoria formal de acessibilidade não foram
  executados.
- Os testes usam contas fictícias; nenhum dado de jogador real foi incluído.
