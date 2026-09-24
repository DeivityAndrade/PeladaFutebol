# Interface do Tô Dentro

A interface segue a direção **C3 “Society”** aprovada em setembro de 2026, sem itálico. O guia completo de marca, cores, tipografia, componentes e tom de voz está em [IDENTIDADE.md](IDENTIDADE.md).

- Barra superior escura com o selo “tô dentro” no computador; gaveta lateral no celular.
- Placar da pelada com data em bloco lima, título, horário e local, anel de vagas e a presença do jogador (“Tô dentro · Camisa 4”) antes das abas.
- Abas em cápsula com contadores; escalação com campo em grama sintética, camisas iguais às anteriores e banco logo abaixo; elenco à direita.
- Modo noturno e claro com os mesmos componentes; topo e placar ficam escuros nos dois modos.
- A página pública de demonstração tem faixa própria com a marca-botão; as telas de uso diário não têm cabeçalho promocional.

As capturas usam respostas fictícias de API somente para o teste visual e não mostram dados reais.

## Capturas

| Área | Claro | Noturno |
| --- | --- | --- |
| Escalação | [Computador](screenshots/desktop.png) · [Celular](screenshots/mobile.png) | [Computador](screenshots/desktop-noturno.png) · [Celular](screenshots/mobile-noturno.png) |
| Jogadores e espera | [Jogadores](screenshots/jogadores.png) · [Espera](screenshots/espera.png) | — |
| Grupos e agenda | [Grupos](screenshots/grupos.png) · [Agenda](screenshots/agenda.png) | — |
| Churrasco | [Computador](screenshots/churrasco.png) · [Formulário](screenshots/churrasco-formulario.png) | [Computador](screenshots/churrasco-noturno.png) · [Celular](screenshots/churrasco-mobile.png) |
| Convite | — | [Computador](screenshots/convite.png) · [Celular](screenshots/convite-mobile-noturno.png) |
| Acesso e criação | [Acesso](screenshots/acesso.png) · [Marcar pelada](screenshots/formulario.png) | [Acesso](screenshots/acesso-noturno.png) |
| Partida e notas | — | [Partida](screenshots/partida-noturno.png) · [Notas](screenshots/notas-noturno.png) |

## Verificação

- `pnpm exec playwright test e2e/appearance.spec.ts` confere topo e gaveta, tema persistido, cores dos modos, camisa e posição do goleiro, alinhamento entre campo e elenco, abas, formulários e convite.
- `pnpm exec playwright test e2e/pelada.spec.ts` cobre os fluxos integrados quando backend e banco estão em execução.
