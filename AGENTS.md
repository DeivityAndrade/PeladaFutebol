# Instruções do projeto

## Publicação

- A Vercel é o endereço principal do site: `https://todentro-vercel-teste.vercel.app`.
- Publique as próximas entregas da interface no projeto existente `deivity/todentro-vercel-teste`. O guia e a verificação de entrega estão em `docs/VERCEL.md`.
- O servidor atual permanece temporariamente no Render, conforme decisão do proprietário em 08/10/2026. A configuração `/api/*` da Vercel encaminha as requisições a esse servidor; os dados permanecem no Neon.
- Migração completa do servidor é uma tarefa separada. Mantenha o servidor disponível enquanto essa dependência existir.

## Arquitetura e interface

- Preserve Angular standalone, signals, CSS próprio, recursos locais, português, acessibilidade por teclado e toque, foco visível, responsividade e movimento reduzido.
- Preserve a identidade do Tô Dentro e os fluxos de presença, escalação, grupos, carreira e partida ao vivo.
- Mantenha sessão e API na mesma origem vista pelo navegador e respeite a política de conteúdo restritiva.
