import { test, expect, request, APIRequestContext } from '@playwright/test';
import path from 'node:path';

const baseURL = process.env['BASE_URL'] || 'http://127.0.0.1:8080';
const password = 'PeladaTeste123!';
let admin: APIRequestContext;
let participant: APIRequestContext;

async function mutate(ctx: APIRequestContext, url: string, data: unknown) {
  const csrf = await (await ctx.get('/api/auth/csrf')).json();
  return ctx.post('/api' + url, { data, headers: { [csrf.headerName]: csrf.token } });
}

test.beforeAll(async () => {
  admin = await request.newContext({ baseURL });
  const email = 'admin.e2e@example.com';
  const register = await mutate(admin, '/auth/register', {
    name: 'Administração de teste',
    email,
    password,
  });
  expect([200, 409]).toContain(register.status());
  const login = await mutate(admin, '/auth/login', { email, password });
  expect(login.ok()).toBeTruthy();
  expect((await login.json()).admin).toBe(true);
  participant = await request.newContext({ baseURL });
  const playerEmail = `admin-player-${crypto.randomUUID()}@example.com`;
  expect(
    (
      await mutate(participant, '/auth/register', {
        name: 'Participante de teste',
        email: playerEmail,
        password,
      })
    ).ok(),
  ).toBeTruthy();
  expect(
    (await mutate(participant, '/auth/login', { email: playerEmail, password })).ok(),
  ).toBeTruthy();
});
test.afterAll(async () => {
  await admin?.dispose();
  await participant?.dispose();
});

for (const mobile of [false, true])
  for (const dark of [false, true]) {
    test(`administração ${mobile ? 'celular' : 'desktop'} ${dark ? 'noturno' : 'claro'}`, async ({
      browser,
    }) => {
      const context = await browser.newContext({
        baseURL,
        viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 1000 },
      });
      // Each scenario has its own session so logout cannot revoke another scenario's access.
      expect(
        (
          await mutate(context.request, '/auth/login', {
            email: 'admin.e2e@example.com',
            password,
          })
        ).ok(),
      ).toBeTruthy();
      await context.addInitScript(
        (theme) => localStorage.setItem('pelada.theme', theme),
        dark ? 'dark' : 'light',
      );
      const page = await context.newPage();
      await page.goto('/#groups');
      const navigation = mobile ? page.locator('.drawer-nav') : page.locator('.top-nav');
      if (mobile) await page.getByRole('button', { name: 'Abrir menu', exact: true }).click();
      await navigation.getByRole('link', { name: 'Administração', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Administração.' })).toBeVisible();
      await expect(page.locator('.registration-history tbody tr')).toHaveCount(6);
      const before = await (await admin.get('/api/admin/summary')).json();
      await expect(page.getByTestId('admin-total')).toHaveText(
        new Intl.NumberFormat('pt-BR').format(before.totalAccounts),
      );
      await page.getByRole('button', { name: '12 meses', exact: true }).press('Enter');
      await expect(page.locator('.registration-history tbody tr')).toHaveCount(12);
      await expect(page.getByRole('button', { name: '12 meses', exact: true })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
      await page.getByRole('button', { name: '6 meses', exact: true }).click();
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      ).toBeTruthy();
      await expect(page.locator('html')).toHaveAttribute('data-theme', dark ? 'dark' : 'light');
      await page.screenshot({
        path: path.resolve(
          '..',
          'docs',
          'screenshots',
          `administracao-${mobile ? 'celular' : 'desktop'}-${dark ? 'noturno' : 'claro'}.png`,
        ),
        fullPage: true,
      });
      // Refresh must consult the server and reflect a newly registered account exactly once.
      const newAccount = await request.newContext({ baseURL });
      expect(
        (
          await mutate(newAccount, '/auth/register', {
            name: 'Cadastro de teste',
            email: `admin-new-${crypto.randomUUID()}@example.com`,
            password,
          })
        ).ok(),
      ).toBeTruthy();
      await page.getByRole('button', { name: 'Atualizar', exact: true }).click();
      await expect(page.getByTestId('admin-total')).toHaveText(
        new Intl.NumberFormat('pt-BR').format(before.totalAccounts + 1),
      );
      await page.reload();
      await expect(page.getByTestId('admin-total')).toHaveText(
        new Intl.NumberFormat('pt-BR').format(before.totalAccounts + 1),
      );
      await page.getByRole('button', { name: 'Sair da conta', exact: true }).click();
      await expect(page.locator('app-admin-page')).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Administração', exact: true })).toHaveCount(0);
      await newAccount.dispose();
      await context.close();
    });
  }

test('participante não vê o menu nem consulta totais; acesso anônimo exige login', async ({
  browser,
}) => {
  expect((await participant.get('/api/admin/summary')).status()).toBe(403);
  const context = await browser.newContext({
    baseURL,
    storageState: await participant.storageState(),
  });
  const page = await context.newPage();
  await page.goto('/#groups');
  await expect(page.getByRole('heading', { name: 'Seus grupos.' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Administração', exact: true })).toHaveCount(0);
  await page.goto('/#admin');
  await expect(page.getByRole('heading', { name: 'Acesso restrito.' })).toBeVisible();
  await expect(page.getByTestId('admin-total')).toHaveCount(0);
  await context.close();
  const anonymous = await browser.newContext({ baseURL });
  expect((await anonymous.request.get('/api/admin/summary')).status()).toBe(401);
  const login = await anonymous.newPage();
  await login.goto('/#admin');
  await expect(login.getByRole('dialog')).toBeVisible();
  await expect(login.locator('app-admin-page')).toHaveCount(0);
  await anonymous.close();
});

test('falha de consulta mostra tentativa novamente e não mantém números antigos', async ({
  browser,
}) => {
  const context = await browser.newContext({ baseURL, storageState: await admin.storageState() });
  const page = await context.newPage();
  await page.goto('/#admin');
  await expect(page.getByTestId('admin-total')).toBeVisible();
  await page.route('**/api/admin/summary', (route) =>
    route.fulfill({ status: 503, json: { message: 'Consulta indisponível no momento.' } }),
  );
  await page.getByRole('button', { name: 'Atualizar', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Consulta indisponível no momento.');
  await expect(page.getByTestId('admin-total')).toHaveCount(0);
  await page.unroute('**/api/admin/summary');
  await page.getByRole('button', { name: 'Tentar novamente', exact: true }).click();
  await expect(page.getByTestId('admin-total')).toBeVisible();
  await context.close();
});

test('administração cabe em celular estreito e tablet e mantém foco visível', async ({
  browser,
}) => {
  const context = await browser.newContext({ baseURL, storageState: await admin.storageState() });
  const page = await context.newPage();
  for (const width of [320, 768, 1024, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/#admin');
    await expect(page.getByTestId('admin-total')).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    ).toBeTruthy();
    await page.getByRole('button', { name: '12 meses', exact: true }).press('Enter');
    await expect(page.getByRole('button', { name: '12 meses', exact: true })).toBeFocused();
    expect(
      await page
        .getByRole('button', { name: '12 meses', exact: true })
        .evaluate((el) => getComputedStyle(el).outlineStyle),
    ).not.toBe('none');
    await page.getByRole('button', { name: '12 meses', exact: true }).press('Tab');
    await expect(page.getByRole('button', { name: '6 meses', exact: true })).not.toBeFocused();
  }
  await context.close();
});
