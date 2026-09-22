# Validação — 22/09/2026

## Resultado executado

**15 verificações automatizadas passaram: 12 cenários Java e 3 cenários de navegador.**

Ambiente: Windows, Java 21.0.12.1, Maven 3.9.11, Node.js 24, Angular 21.2.23, Spring Boot 4.1.1 e PostgreSQL 18.4. Os testes HTTP e visuais foram executados no Chrome, contra o pacote de produção do Spring Boot contendo o Angular compilado.

### Regras de negócio — JUnit com banco real

1. Lotação, fila e promoção do primeiro inscrito.
2. Duas confirmações simultâneas disputando a última vaga.
3. Confirmação e desistência repetidas sem duplicação.
4. Dois capitães escolhendo simultaneamente o mesmo jogador, com somente um vencedor.
5. Escalações incompletas, trocas de posições e persistência das três formações.
6. Rejeição de posições duplicadas, jogadores de outro elenco e revisão antiga.
7. Rejeição de acesso de não membros e edições por pessoas sem permissão.
8. Saída de capitão, limpeza da escalação e indicação de substituto.
9. Liberação para outros times sem cancelar a presença.
10. Rejeição de escolha quando o elenco está cheio.
11. Bloqueio de eventos cancelados ou já iniciados.
12. Proteção da demonstração, inclusive contra seu próprio organizador.

### Navegador — Playwright

1. Demonstração, troca de equipes, fila, navegação móvel, foco no modal, Escape e ausência de rolagem horizontal em 390 px. Verificação de estilos aplicados e ausência de erros JavaScript.
2. CSRF obrigatório, autenticação, acesso a grupo privado, entrada inválida, cookie de sessão, OpenAPI e logout.
3. Cadastro pela interface → criação de grupo → convite aberto por outra conta → criação da pelada → confirmação de ambas → indicação de capitão → escolha do elenco → três formações → troca de titulares → persistência após recarregar → arrastar ao banco → seleção pelo teclado em tela de celular → consulta sem permissão de edição → desistência do capitão.

## Revisão visual

Imagens capturadas da aplicação real em **1440 px** e **390 px**, revisadas após corrigir o carregamento de CSS sob a política de segurança:

- [Desktop](screenshots/desktop.png)
- [Celular](screenshots/mobile.png)

## Limites desta validação

- Publicação e HTTPS externo pendentes das contas Render/Neon e da configuração do repositório remoto.
- Docker e GitHub Actions preparados, mas não executados neste ambiente.
- Firefox, Safari, dispositivos físicos, teste de carga e auditoria formal de acessibilidade não foram executados.
- Não foram usados dados pessoais reais; as contas dos testes usam endereços fictícios.
