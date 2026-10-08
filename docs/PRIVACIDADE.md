# Privacidade e exclusão de dados

A política pública está em `frontend/public/privacidade.html`, com estilos locais
em `frontend/public/privacidade.css`. Ela é copiada pelo build Angular, abre sem
login e sem JavaScript e tem um link no rodapé da aplicação.

- Política: https://todentro-vercel-teste.vercel.app/privacidade.html
- Instruções de exclusão: https://todentro-vercel-teste.vercel.app/privacidade.html#exclusao
- Responsável: Deivity Rosa de Andrade Filho.
- Contato informado pelo responsável: deivity.andrade@hotmail.com.

Antes de ativar novos tratamentos de dados, atualizar o texto e a data conforme a
implementação real. A versão de 08/10/2026 cobre conta, grupos, recursos sociais,
financeiro, assistente OpenAI, cookies, vínculo WhatsApp e preferências. O envio
automático de mensagens ainda está em preparação. A exclusão de conta é solicitada
por e-mail e atendida pelo responsável; não há exclusão automática de conta no site.

No painel da Meta, usar a URL pública no campo **URL da Política de Privacidade**.
Se houver campo de exclusão, selecionar **URL de instruções de exclusão de dados**
e usar a URL com `#exclusao`. Essa página não é um callback de exclusão automática.
O preenchimento dessas URLs não prova que webhooks reais estejam funcionando.
Após cumprir os requisitos de publicação, verificar a assinatura da conta WhatsApp
e testar com uma mensagem real enviada pelo usuário.

Publicar a interface conforme `docs/VERCEL.md` e verificar a resposta da página,
CSS e fontes sem autenticação, além da leitura no celular e no computador.
