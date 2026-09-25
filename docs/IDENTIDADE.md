# Tô Dentro — guia de identidade

O Organizador de Pelada passa a se chamar **Tô Dentro**. O nome vem da frase de quem confirma presença e vira, ao mesmo tempo, marca, ação e ícone. A direção visual é a **C3 “Society”**: gramado sintético, formas arredondadas, Barlow Condensed reta e lima sobre verde-noite.

## Marca

| Versão | Uso | Componente |
| --- | --- | --- |
| **Selo de confirmação** (principal) | Topo, menu, login, rodapé, aba do navegador | `<app-brand />` |
| **Marca-botão** (destaque) | Página pública, convites, abertura, redes | `<app-brand variant="button" />` |

- A palavra é sempre minúscula: “tô dentro”. Em frases, “Tô Dentro”.
- O check do selo é lima (`#D4F25A`) sobre fundos escuros e verde (`#1E7A3C`) com check branco no modo claro.
- A marca-botão é uma cápsula lima com o check em círculo escuro. Sobre gramado ou fundo claro, a cápsula pode ficar escura.
- A marca é decorativa (`aria-hidden`); o link ou botão que a contém recebe o nome acessível.
- Favicon: `frontend/public/favicon.svg` (check escuro em círculo lima).

## Cores

Os tokens ficam no topo de `frontend/src/styles.css`. Topo, gaveta e placar da pelada usam as superfícies “noite” nos dois modos.

| Papel | Noturno | Claro |
| --- | --- | --- |
| Fundo | `#0B1510` | `#F4F6F0` |
| Superfície | `#14231B` | `#FFFFFF` |
| Superfície 2 | `#1B2E23` | `#EDF1E7` |
| Linha | `#2C4437` | `#CFD7C5` |
| Texto | `#EEF2EA` | `#0F1C15` |
| Texto secundário | `#A9B8AE` | `#4B5850` |
| Ação principal | lima `#D4F25A` (texto `#0E0F0C`) | verde `#1E7A3C` (texto branco) |
| Acento | lima `#D4F25A` | lima `#D4F25A` |
| Perigo | `#FF9E8F` sobre `#3A1E1B` | `#A3352A` sobre `#FCECE9` |
| Gramado | `#1E6B3D` / `#237545` | `#1E6B3D` / `#237545` |

Todos os pares de texto usados passam de 4,5:1 (conferido na validação). O modo inicial segue a preferência do sistema; a escolha do usuário fica salva no navegador.

## Tipografia

- **Barlow Condensed 600–800, reta** (sem itálico): títulos, abas, números de camisa, datas, placar e valores. Títulos em caixa alta via CSS.
- **Barlow 400–700**: textos, rótulos, botões e formulários.
- Arquivos locais: Barlow Condensed via `@fontsource` (já no projeto) e Barlow em `frontend/public/fonts/barlow/` com licença OFL (`OFL.txt`). A Inter deixou de ser carregada; a dependência pode ser removida do `package.json` depois de atualizar o lockfile.

## Formas e elementos

- Cartões com cantos de 24 px; campos de formulário 14 px; botões, abas e etiquetas em cápsula.
- Abas em trilho segmentado: a aba ativa fica em cápsula escura (ou clara no noturno) com contador lima.
- Gramado: faixas verticais largas, textura fina de grama sintética e leve vinheta. Marcações brancas com cantos arredondados.
- A camisa do campo é a mesma do produto anterior; o jogador logado ganha brilho lima e etiqueta lima.
- Ícones de traço (componente `app-icon`), sem emoji.
- Sem fotos de banco de imagens, métricas inventadas ou depoimentos.

## Tom de voz

Curto, de parceiro de grupo, sem gíria forçada.

- Presença: “Tô dentro · Camisa 4 · Boleiros FC”
- Espera: “Na espera · 2º da fila”
- Vazio: “A quadra está esperando.”
- Erro: “Não foi possível carregar a pelada. Tentar novamente”

## Componentes reutilizáveis

| Componente | Onde |
| --- | --- |
| `app-brand` (`brand.ts`) | Selo e marca-botão |
| `app-icon` (`icon.ts`) | Ícones de traço |
| `app-pitch` (`pitch.ts`) | Campo, camisas, esquema e banco |
| `.button.primary / .secondary / .danger-button / .ghost-on-dark`, `.text-button`, `.icon-button` | Ações |
| `.panel`, `.panel-heading`, `.compact-heading`, `.tip-card` | Cartões |
| `.content-tabs`, `.group-tabs`, `.team-tabs` | Abas |
| `.match-banner`, `.presence-state`, `.spots-ring` | Placar da pelada e presença |
| `.person-row`, `.roster-row`, `.timeline-row`, `.rating-row`, `.finance-charge` | Listas |
| `.modal`, `.form-row`, `.form-hint`, `.check-label` | Modais e formulários |
| `.alert`, `.toast`, `.empty-state`, `.loading-state`, `.spinner` | Avisos e estados |

## Estrutura das telas

- **Página inicial (`#inicio`, “Visão geral”):** componente `app-home-page`. Faixa noturna com a chamada, os botões “Criar minha pelada” e “Ver demonstração” e a prévia do campo real com dados fictícios identificados; funcionalidades em Antes do jogo / No campo (sobre o gramado) / Depois do jogo; três passos; acesso à demonstração; dúvidas comuns confirmadas no produto; fechamento com a marca-botão. O topo troca a navegação do app pelos links de seção (`#inicio/<seção>`) e ganha o botão lima “Criar minha pelada”.
- **Pública (demonstração, `#demo`):** faixa de apresentação com “O jogo começa aqui.” e a marca-botão “Criar minha pelada”, seguida da pelada de exemplo.
- **Operacionais:** sem cabeçalho promocional. O nome da pelada é o título; a presença fica no placar, antes das abas.
- **Navegação:** barra superior no computador; gaveta lateral abaixo de 960 px.
