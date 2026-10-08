import { test, expect, APIRequestContext } from '@playwright/test';
import path from 'node:path';

async function mutate(ctx: APIRequestContext, url: string, method: string, data?: unknown) {
  const csrf = await (await ctx.get('/api/auth/csrf')).json();
  return ctx.fetch('/api' + url, { method, data, headers: { [csrf.headerName]: csrf.token } });
}
async function account(request: APIRequestContext) {
  const email = `wa-${crypto.randomUUID()}@example.com`,
    password = 'PeladaTeste123!';
  expect(
    (
      await mutate(request, '/auth/register', 'POST', { name: 'Ana da pelada', email, password })
    ).ok(),
  ).toBeTruthy();
  expect((await mutate(request, '/auth/login', 'POST', { email, password })).ok()).toBeTruthy();
  return (
    await (
      await mutate(request, '/groups', 'POST', { name: 'Turma do sábado', description: '' })
    ).json()
  ).id;
}

test('WhatsApp indisponível mantém avisos sem autorização e sem vínculo fictício', async ({
  page,
  request,
}) => {
  await account(request);
  await page.context().addCookies((await request.storageState()).cookies);
  await page.goto('/#career');
  const settings = page.locator('app-whatsapp-settings');
  await expect(settings.getByText('Nenhum número vinculado.')).toBeVisible();
  await expect(settings.getByText(/Estamos preparando a conexão/)).toBeVisible();
  await expect(settings.getByRole('button', { name: 'Vincular meu WhatsApp' })).toHaveCount(0);
  for (const choice of await settings.getByRole('checkbox').all()) {
    await expect(choice).toBeDisabled();
    await expect(choice).not.toBeChecked();
  }
  await expect(
    settings.getByRole('button', { name: 'Salvar avisos de Turma do sábado' }),
  ).toBeDisabled();
  await settings.getByText('Como usamos seu número e suas autorizações', { exact: true }).click();
  await expect(settings.getByText(/Não guardamos o texto/)).toBeVisible();
  expect((await mutate(request, '/whatsapp/verification', 'POST')).status()).toBe(503);
});

test('vinculação mostra prova recebida, exige confirmação e salva autorizações por grupo', async ({
  page,
  request,
}) => {
  const club = await account(request);
  let state = {
    available: true,
    phone: null as string | null,
    verified: false,
    stopped: false,
    challengeId: null as string | null,
    pendingPhone: null as string | null,
    expiresAt: null as string | null,
    textVersion: 'whatsapp-v1',
    groups: [{ clubId: club, name: 'Turma do sábado', invitations: false, reminders: false }],
  };
  const code = 'A'.repeat(24),
    id = crypto.randomUUID(),
    expiresAt = new Date(Date.now() + 600000).toISOString();
  let received = false,
    failSave = true,
    confirmations = 0;
  await page.route('**/api/whatsapp**', async (route) => {
    const req = route.request(),
      url = new URL(req.url());
    if (url.pathname.endsWith('/verification/confirm')) {
      expect(received).toBeTruthy();
      expect(req.postDataJSON()).toEqual({ challengeId: id });
      confirmations++;
      state = {
        ...state,
        phone: '+55 •••• 0000',
        verified: true,
        pendingPhone: null,
        challengeId: null,
      };
    } else if (url.pathname.endsWith('/verification')) {
      state = { ...state, challengeId: id, expiresAt };
      return route.fulfill({
        json: { id, code, expiresAt, url: 'https://wa.me/15550000000?text=VINCULAR+' + code },
      });
    } else if (req.method() === 'PUT') {
      if (failSave) {
        failSave = false;
        return route.fulfill({ status: 503, json: { message: 'Tente novamente em instantes.' } });
      }
      expect(req.postDataJSON()).toEqual({
        invitations: true,
        reminders: false,
        textVersion: 'whatsapp-v1',
      });
      state = { ...state, groups: [{ ...state.groups[0], invitations: true }] };
    } else if (req.method() === 'DELETE') {
      state = {
        ...state,
        phone: null,
        verified: false,
        groups: [{ ...state.groups[0], invitations: false, reminders: false }],
      };
    } else if (received && !state.verified) {
      state = { ...state, pendingPhone: '+55 •••• 0000' };
    }
    return route.fulfill({ json: state });
  });
  await page.context().addCookies((await request.storageState()).cookies);
  await page.goto('/#career');
  const settings = page.locator('app-whatsapp-settings');
  const begin = settings.getByRole('button', { name: 'Vincular meu WhatsApp' });
  await begin.focus();
  await page.keyboard.press('Enter');
  await expect(settings.getByText('VINCULAR ' + code, { exact: true })).toBeVisible();
  await expect(settings.getByRole('link', { name: 'Abrir conversa no WhatsApp' })).toHaveAttribute(
    'href',
    'https://wa.me/15550000000?text=VINCULAR+' + code,
  );
  await expect(settings.getByRole('button', { name: 'Confirmar este número' })).toHaveCount(0);
  await settings.getByRole('button', { name: 'Já enviei, conferir' }).click();
  await expect(settings.getByRole('status')).toContainText('Ainda não recebemos');
  received = true;
  await settings.getByRole('button', { name: 'Já enviei, conferir' }).click();
  await expect(settings.getByRole('heading', { name: 'Confira seu número' })).toBeVisible();
  const invitations = settings.getByRole('checkbox', {
    name: 'Autorizo convites para novos jogos deste grupo.',
  });
  await expect(invitations).toBeDisabled();
  await settings.getByRole('button', { name: 'Confirmar este número' }).click();
  await expect(invitations).toBeEnabled();
  await expect(invitations).not.toBeChecked();
  expect(confirmations).toBe(1);
  await invitations.check();
  const save = settings.getByRole('button', { name: 'Salvar avisos de Turma do sábado' });
  await save.click();
  await expect(settings.getByRole('alert')).toContainText('Tente novamente');
  expect(state.groups[0].invitations).toBeFalsy();
  await save.click();
  await expect(settings.getByRole('status')).toContainText(
    'Preferências de Turma do sábado salvas.',
  );
  await page.reload();
  await expect(invitations).toBeChecked();
  await expect(
    settings.getByRole('checkbox', { name: 'Autorizo lembretes de jogos deste grupo.' }),
  ).not.toBeChecked();
  await page.setViewportSize({ width: 1440, height: 1080 });
  await settings.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/whatsapp-desktop.png'),
    fullPage: false,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: 'Modo noturno' }).click();
  await settings.scrollIntoViewIfNeeded();
  await page.evaluate(() => (document.activeElement as HTMLElement)?.blur());
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBeTruthy();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/whatsapp-celular.png'),
    fullPage: false,
  });
  await settings.getByRole('button', { name: 'Desconectar WhatsApp', exact: true }).click();
  await expect(settings.getByRole('group', { name: 'Confirmar desconexão' })).toBeVisible();
  await settings.getByRole('button', { name: 'Manter vínculo' }).click();
  expect(state.verified).toBeTruthy();
  await settings.getByRole('button', { name: 'Desconectar WhatsApp', exact: true }).click();
  await settings.getByRole('button', { name: 'Sim, desconectar' }).click();
  await expect(invitations).toBeDisabled();
  await expect(invitations).not.toBeChecked();
  await expect(settings.getByRole('status')).toContainText(
    'autorizações de avisos foram canceladas',
  );
});

test('estado SAIR impede novo consentimento e falha de carregamento oferece recuperação', async ({
  page,
  request,
}) => {
  const club = await account(request);
  let fail = true;
  await page.route('**/api/whatsapp', (route) => {
    if (fail) {
      fail = false;
      return route.fulfill({ status: 503, json: { message: 'Servidor indisponível.' } });
    }
    return route.fulfill({
      json: {
        available: true,
        verified: true,
        phone: '+55 •••• 0000',
        stopped: true,
        challengeId: null,
        pendingPhone: null,
        expiresAt: null,
        textVersion: 'whatsapp-v1',
        groups: [{ clubId: club, name: 'Turma', invitations: false, reminders: false }],
      },
    });
  });
  await page.context().addCookies((await request.storageState()).cookies);
  await page.goto('/#career');
  const settings = page.locator('app-whatsapp-settings');
  await expect(settings.getByRole('alert')).toContainText('Servidor indisponível');
  await settings.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(settings.getByText(/Você cancelou os avisos com SAIR/)).toBeVisible();
  for (const choice of await settings.getByRole('checkbox').all())
    await expect(choice).toBeDisabled();
  await expect(settings.getByRole('button', { name: 'Confirmar ou trocar número' })).toBeEnabled();
});
