# Interface do Pelada

A interface segue o [mockup aprovado](screenshots/mockup-aprovado.png), fornecido pelo proprietário do projeto. A navegação verde escura, o card da pelada, as abas discretas e o campo em destaque formam a linguagem visual usada em grupos, agenda, churrascos, convites, partida, notas e formulários. O conteúdo continua vindo da aplicação; os nomes e números do mockup não são dados reais.

No campo, cada jogador aparece em uma camisa com a cor do time. O número é a posição visual do jogador no elenco daquela pelada, não um número de uniforme cadastrado. O goleiro aparece junto à área superior; as três formações reais e os controles por arraste, toque e teclado continuam disponíveis. O modo noturno mantém superfícies em grafite e o campo verde. O menu recolhe de 252 px para 72 px no computador e vira uma gaveta no celular. Preferências de tema e menu ficam neste navegador.

## Capturas

As capturas usam respostas fictícias de API somente para o teste visual e não mostram dados de usuários reais.

| Área               | Claro                                                                                                                                | Noturno                                                                                       |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------- |
| Escalação          | [Computador](screenshots/desktop.png) · [Menu recolhido](screenshots/desktop-menu-recolhido.png) · [Celular](screenshots/mobile.png) | [Computador](screenshots/desktop-noturno.png) · [Celular](screenshots/mobile-noturno.png)     |
| Jogadores e espera | [Jogadores](screenshots/jogadores.png) · [Espera](screenshots/espera.png)                                                            | —                                                                                             |
| Grupos e agenda    | [Grupos](screenshots/grupos.png) · [Agenda](screenshots/agenda.png)                                                                  | —                                                                                             |
| Churrasco          | [Computador](screenshots/churrasco.png) · [Formulário](screenshots/churrasco-formulario.png)                                         | [Computador](screenshots/churrasco-noturno.png) · [Celular](screenshots/churrasco-mobile.png) |
| Convite            | [Computador](screenshots/convite.png)                                                                                                | [Celular](screenshots/convite-mobile-noturno.png)                                             |
| Acesso e criação   | [Acesso](screenshots/acesso.png) · [Marcar pelada](screenshots/formulario.png)                                                       | [Acesso](screenshots/acesso-noturno.png)                                                      |
| Partida e notas    | —                                                                                                                                    | [Partida](screenshots/partida-noturno.png) · [Notas](screenshots/notas-noturno.png)           |

## Verificação

- `pnpm build` compila o Angular para produção.
- `pnpm exec playwright test e2e/appearance.spec.ts` confere as principais telas, o menu e o tema persistidos, a camisa e a posição do goleiro, o alinhamento entre campo e elenco, a navegação por abas, formulários e convite.
- `pnpm exec playwright test e2e/pelada.spec.ts` cobre os fluxos integrados quando backend e banco estão em execução.
- As capturas foram inspecionadas em desktop e celular, nos temas claro e escuro.
