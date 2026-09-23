# Redesenho da interface

A interface mantém a identidade verde do Pelada com uma leitura mais simples para agenda, escalação, partida, notas, grupos e churrascos. O campo e as ações reais continuam os mesmos. O menu recolhe de 232 px para 72 px em telas grandes; em telas menores que 960 px funciona como gaveta. Claro é o padrão, e a escolha do tema e do menu fica apenas neste navegador.

## Capturas

As capturas foram feitas com o Angular executando no navegador e respostas de API fictícias usadas somente pelo teste visual. Elas não mostram dados de usuários reais.

| Tela | Claro | Noturno |
| --- | --- | --- |
| Escalação no computador | [Expandido](screenshots/desktop.png) · [Menu recolhido](screenshots/desktop-menu-recolhido.png) | [Computador](screenshots/desktop-noturno.png) |
| Escalação no celular | [Celular](screenshots/mobile.png) | [Celular](screenshots/mobile-noturno.png) |
| Churrasco | [Computador](screenshots/churrasco.png) · [Celular](screenshots/churrasco-mobile.png) | [Computador](screenshots/churrasco-noturno.png) |
| Acesso, partida e notas | — | [Acesso](screenshots/acesso-noturno.png) · [Partida](screenshots/partida-noturno.png) · [Notas](screenshots/notas-noturno.png) |

## Verificação local

- `pnpm build`: compilação de produção do Angular.
- `pnpm exec playwright test e2e/appearance.spec.ts`: tema e menu persistidos após recarregar, foco e rótulo do menu recolhido, gaveta no celular, abas da pelada, modal de acesso, entrada em Meus grupos pela raiz e aba Churrasco.
- Capturas inspecionadas em 1440 px e 390 px, nos dois temas.

Os testes integrados com Java e PostgreSQL continuam na CI e cobrem as ações reais. O teste visual usa dados fictícios para ser repetível sem uma conta ou banco local. A publicação no Render será conferida após a integração na `main`.
