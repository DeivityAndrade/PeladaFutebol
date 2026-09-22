import { test, expect, request, APIRequestContext } from '@playwright/test';
import path from 'node:path';

const baseURL = process.env['BASE_URL'] || 'http://127.0.0.1:8080';
async function mutate(ctx: APIRequestContext, url: string, method = 'POST', data?: unknown) {
  const token = await (await ctx.get('/api/auth/csrf')).json();
  return ctx.fetch('/api' + url, { method, data, headers: { [token.headerName]: token.token } });
}
async function account(name: string, requestedEmail?: string) {
  const ctx = await request.newContext({ baseURL });
  const email = requestedEmail || `e2e-${crypto.randomUUID()}@example.com`,
    password = 'PeladaTeste123!';
  expect(
    (await mutate(ctx, '/auth/register', 'POST', { name, email, password })).ok(),
  ).toBeTruthy();
  const login = await mutate(ctx, '/auth/login', 'POST', { email, password });
  expect(login.ok()).toBeTruthy();
  return { ctx, email, password, user: await login.json() };
}

test('demonstração, navegação, teclado e layout desktop/mobile', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'O jogo começa aqui.' })).toBeVisible();
  await expect(page.locator('.sidebar')).toHaveCSS('position', 'fixed');
  await expect(page.locator('.field')).toHaveCSS('position', 'relative');
  await expect(page.locator('.field-player')).toHaveCount(5);
  await expect(page.locator('#formation')).toBeDisabled();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/desktop.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('tab', { name: 'Boleiros FC' }).click();
  await expect(page.locator('.team-identity')).toContainText('Boleiros FC');
  await page.getByRole('tab', { name: /Lista de espera/ }).click();
  await expect(page.locator('.person-row')).toHaveCount(2);
  await page.getByRole('tab', { name: 'Escalações', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator('.field')).toBeVisible();
  await expect
    .poll(() => page.locator('.sidebar').evaluate((el) => el.getBoundingClientRect().right))
    .toBeLessThanOrEqual(0);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/mobile.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('button', { name: 'Abrir menu' }).click();
  await expect(page.locator('.sidebar')).toHaveClass(/mobile-open/);
  await page.keyboard.press('Escape');
  await expect(page.locator('.sidebar')).not.toHaveClass(/mobile-open/);
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Tab');
  expect(
    await page.locator('.modal').evaluate((el) => el.contains(document.activeElement)),
  ).toBeTruthy();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(errors).toEqual([]);
});

test('segurança HTTP: CSRF, sessão, permissões, validação e OpenAPI', async () => {
  const anon = await request.newContext({ baseURL });
  expect((await anon.get('/api/groups')).status()).toBe(401);
  expect(
    (
      await anon.post('/api/auth/register', {
        data: { name: 'Sem CSRF', email: 'invalid@example.com', password: 'abc12345' },
      })
    ).status(),
  ).toBe(403);
  const a = await account('Organizador Segurança'),
    b = await account('Outro Grupo');
  const club = await (
    await mutate(a.ctx, '/groups', 'POST', { name: 'Grupo privado', description: '' })
  ).json();
  expect((await b.ctx.get(`/api/groups/${club.id}/games`)).status()).toBe(403);
  expect(
    (
      await mutate(a.ctx, `/groups/${club.id}/games`, 'POST', {
        title: 'Inválida',
        location: 'Quadra',
        startsAt: new Date(Date.now() + 86400000).toISOString(),
        teamCount: 1,
        teamSize: 1,
      })
    ).status(),
  ).toBe(400);
  expect((await a.ctx.get('/api/auth/me')).status()).toBe(200);
  const longEmail = await account(
    'E-mail longo',
    crypto.randomUUID() + '@' + 'd'.repeat(60) + '.example.com',
  );
  expect((await longEmail.ctx.get('/api/auth/me')).status()).toBe(200);
  await longEmail.ctx.dispose();
  const cookies = await a.ctx.storageState();
  expect(
    cookies.cookies.some((c) => c.name === 'SESSION' && c.httpOnly && c.sameSite === 'Lax'),
  ).toBeTruthy();
  expect((await a.ctx.get('/api/openapi')).ok()).toBeTruthy();
  expect((await mutate(a.ctx, '/auth/logout')).status()).toBe(204);
  expect((await a.ctx.get('/api/auth/me')).status()).toBe(401);
  await a.ctx.dispose();
  await b.ctx.dispose();
  await anon.dispose();
});

test('cadastro, grupo, convite, pelada, escolha de elenco e escalação persistida', async ({
  page,
  browser,
}) => {
  const email = `organizador-${crypto.randomUUID()}@example.com`;
  await page.goto('/');
  await page.getByRole('button', { name: 'Criar minha pelada' }).click();
  await page.getByLabel('Seu nome').fill('Capitão do Teste');
  await page.getByLabel('E-mail', { exact: true }).fill(email);
  await page.getByLabel('Senha', { exact: true }).fill('PeladaTeste123!');
  await page.getByRole('button', { name: 'Criar minha conta', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Seus grupos.' })).toBeVisible();
  await page.getByRole('button', { name: 'Criar meu primeiro grupo' }).click();
  await page.getByLabel('Nome do grupo').fill('Pelada da integração');
  await page.getByLabel('Sobre o grupo').fill('Grupo criado pelo teste completo.');
  await page.getByRole('dialog').getByRole('button', { name: 'Criar grupo', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Pelada da integração.' })).toBeVisible();
  const clubs = await (await page.request.get('/api/groups')).json();
  const club = clubs.find((c: any) => c.name === 'Pelada da integração');
  await page.getByRole('button', { name: 'Marcar pelada', exact: true }).click();
  await page.getByLabel('Nome da pelada').fill('Jogo de integração');
  await page.getByLabel('Local e quadra').fill('Arena de Teste');
  await page.getByLabel('Data e horário').fill('2099-10-10T20:00');
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Marcar pelada', exact: true })
    .click();
  await expect(page.getByRole('heading', { name: 'Jogo de integração' })).toBeVisible();
  await page.getByRole('button', { name: 'Confirmar presença', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Estou dentro' })).toBeVisible();
  const gameId = page.url().split('/').at(-1)!;
  const teammate = await account('Lucas da Integração');
  const teammateContext = await browser.newContext({
    storageState: await teammate.ctx.storageState(),
  });
  const other = await teammateContext.newPage();
  await other.goto('/#invite/' + club.invite);
  await expect(other.getByRole('dialog')).toBeVisible();
  await other.getByRole('button', { name: 'Entrar no grupo', exact: true }).click();
  await expect(other.getByRole('heading', { name: 'Pelada da integração.' })).toBeVisible();
  await other.getByRole('button', { name: /Jogo de integração/ }).click();
  await other.getByRole('button', { name: 'Confirmar presença', exact: true }).click();
  await expect(other.getByRole('button', { name: 'Estou dentro' })).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'Configurar time e capitão' }).click();
  await page.getByLabel('Nome do time').fill('Os Testadores');
  await page.getByLabel('Capitão', { exact: true }).selectOption({ label: 'Capitão do Teste' });
  await page.getByRole('button', { name: 'Salvar time', exact: true }).click();
  await page.getByRole('button', { name: 'Escolher Lucas da Integração' }).click();
  await expect(page.locator('.roster-panel')).toContainText('Lucas da Integração');
  await page.getByRole('button', { name: /Escalar GOL/ }).click();
  await page.getByLabel(/GOL · escolher jogador/).selectOption({ label: 'Capitão do Teste' });
  await expect(page.getByRole('button', { name: 'Escalar GOL: Capitão do Teste' })).toBeVisible();
  for (const formation of ['1-2-1', '3-1', '2-2']) {
    await page.getByLabel('Formação', { exact: true }).selectOption(formation);
    await expect(page.locator('#formation')).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Escalar GOL: Capitão do Teste' })).toBeVisible();
  }
  // Keyboard-accessible position picker, then swap occupied positions.
  await page.getByRole('button', { name: /Escalar FIXO E/ }).click();
  await page.getByLabel(/FIXO E · escolher jogador/).selectOption({ label: 'Lucas da Integração' });
  await expect(
    page.getByRole('button', { name: 'Escalar FIXO E: Lucas da Integração' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Escalar GOL: Capitão do Teste' }).click();
  await page.getByLabel(/GOL · escolher jogador/).selectOption({ label: 'Lucas da Integração' });
  await expect(
    page.getByRole('button', { name: 'Escalar GOL: Lucas da Integração' }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole('button', { name: 'Escalar GOL: Lucas da Integração' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Escalar FIXO E: Capitão do Teste' }),
  ).toBeVisible();
  await page
    .getByRole('button', { name: 'Escalar GOL: Lucas da Integração' })
    .dragTo(page.locator('.bench'));
  await expect(page.getByRole('button', { name: 'Escalar GOL — posição vazia' })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: /Escalar GOL/ }).click();
  await page.getByLabel(/GOL · escolher jogador/).focus();
  await page.keyboard.press('l');
  await page.keyboard.press('Enter');
  await expect(
    page.getByRole('button', { name: 'Escalar GOL: Lucas da Integração' }),
  ).toBeVisible();
  await other.reload();
  await expect(other.locator('#formation')).toBeDisabled();
  await expect(
    other.getByRole('button', { name: 'Escalar GOL: Lucas da Integração' }),
  ).toBeDisabled();
  const detail = await (await teammate.ctx.get(`/api/games/${gameId}`)).json();
  expect(
    (
      await mutate(teammate.ctx, `/games/${gameId}/teams/${detail.teams[0].id}/lineup`, 'PUT', {
        formation: '2-2',
        slots: [null, null, null, null, null],
        version: detail.teams[0].version,
      })
    ).status(),
  ).toBe(403);
  await page.getByRole('button', { name: 'Estou dentro' }).click();
  await page.getByRole('button', { name: 'Confirmar desistência' }).click();
  await expect(page.locator('.captain-row')).toContainText('A definir');
  await teammateContext.close();
  await teammate.ctx.dispose();
});
