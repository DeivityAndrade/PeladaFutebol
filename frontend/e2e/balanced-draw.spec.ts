import { test, expect, request, APIRequestContext } from '@playwright/test';
import path from 'node:path';

const baseURL = process.env['BASE_URL'] || 'http://127.0.0.1:8080';
async function mutate(ctx: APIRequestContext, url: string, method = 'POST', data?: unknown) {
  const csrf = await (await ctx.get('/api/auth/csrf')).json();
  return ctx.fetch('/api' + url, { method, data, headers: { [csrf.headerName]: csrf.token } });
}
async function account(name: string) {
  const ctx = await request.newContext({ baseURL });
  const email = `draw-${crypto.randomUUID()}@example.com`,
    password = 'PeladaTeste123!';
  expect(
    (await mutate(ctx, '/auth/register', 'POST', { name, email, password })).ok(),
  ).toBeTruthy();
  const response = await mutate(ctx, '/auth/login', 'POST', { email, password });
  expect(response.ok()).toBeTruthy();
  return { ctx, user: await response.json() };
}

for (const mobile of [false, true])
  for (const dark of [false, true]) {
    test(`classificação e prévia do sorteio ${mobile ? 'celular' : 'desktop'} ${dark ? 'noturno' : 'claro'}`, async ({
      browser,
    }) => {
      const owner = await account('Rafael Organizador');
      const club = await (
        await mutate(owner.ctx, '/groups', 'POST', {
          name: 'Resenha de quinta',
          description: 'Grupo de teste do sorteio',
        })
      ).json();
      const game = await (
        await mutate(owner.ctx, `/groups/${club.id}/games`, 'POST', {
          title: 'Pelada de quinta',
          location: 'Arena da Vila · Quadra 02',
          startsAt: new Date(Date.now() + 86400000).toISOString(),
          teamCount: 2,
          teamSize: 7,
        })
      ).json();
      const accounts = [owner];
      for (const name of ['Diego', 'Caio', 'Bruno', 'Lucas', 'Matheus']) {
        const player = await account(name);
        accounts.push(player);
        expect((await mutate(player.ctx, `/invites/${club.invite}/join`)).ok()).toBeTruthy();
      }
      for (const player of accounts)
        expect((await mutate(player.ctx, `/games/${game.game.id}/attendance`)).ok()).toBeTruthy();
      const context = await browser.newContext({
        baseURL,
        storageState: await owner.ctx.storageState(),
        viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 1000 },
      });
      await context.addInitScript(
        (theme) => localStorage.setItem('pelada.theme', theme),
        dark ? 'dark' : 'light',
      );
      const page = await context.newPage();
      await page.goto('/#group/' + club.id);
      await page.getByRole('tab', { name: 'Jogadores', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Conheça o jogo de cada um.' })).toBeVisible();
      await page.getByRole('button', { name: 'Classificar Rafael Organizador' }).click();
      await page.getByLabel('Posição principal').selectOption('GOALKEEPER');
      await page.getByLabel('Posição secundária').selectOption('DEFENSE');
      await page.getByLabel('Nível no grupo').selectOption('4');
      await page.getByRole('button', { name: 'Salvar classificação' }).press('Enter');
      await expect(page.getByRole('status')).toHaveText('Classificação salva neste grupo.');
      await page.reload();
      await page.getByRole('tab', { name: 'Jogadores', exact: true }).click();
      await expect(page.getByText('Goleiro · Defesa', { exact: true })).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('data-theme', dark ? 'dark' : 'light');
      await page.screenshot({
        path: path.resolve(
          '..',
          'docs',
          'screenshots',
          `classificacao-${mobile ? 'celular' : 'desktop'}-${dark ? 'noturno' : 'claro'}.png`,
        ),
        fullPage: true,
      });
      for (let i = 1; i < accounts.length - 1; i++) {
        expect(
          (
            await mutate(
              owner.ctx,
              `/groups/${club.id}/players/${accounts[i].user.id}/classification`,
              'PUT',
              {
                primaryPosition: i % 2 ? 'DEFENSE' : 'ATTACK',
                secondaryPosition: null,
                skillLevel: i % 2 ? 2 : 4,
              },
            )
          ).ok(),
        ).toBeTruthy();
      }
      await page.goto('/#game/' + game.game.id);
      await page.getByRole('button', { name: 'Sortear times', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('button', { name: 'Gerar prévia' }).click();
      await expect(dialog.getByText('Prévia dos elencos', { exact: true })).toBeVisible();
      await expect(
        dialog.getByText('1 sem classificação: usamos nível estimado 3 apenas no cálculo.'),
      ).toBeVisible();
      const before = await (await owner.ctx.get(`/api/games/${game.game.id}`)).json();
      expect(before.attendees.every((p: any) => p.teamId === null)).toBeTruthy();
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      ).toBeTruthy();
      expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBeTruthy();
      await page.screenshot({
        path: path.resolve(
          '..',
          'docs',
          'screenshots',
          `sorteio-previa-${mobile ? 'celular' : 'desktop'}-${dark ? 'noturno' : 'claro'}.png`,
        ),
      });
      // A classification changed after the preview must prevent applying stale teams.
      expect(
        (
          await mutate(
            owner.ctx,
            `/groups/${club.id}/players/${owner.user.id}/classification`,
            'PUT',
            { primaryPosition: 'GOALKEEPER', secondaryPosition: 'DEFENSE', skillLevel: 5 },
          )
        ).ok(),
      ).toBeTruthy();
      await dialog.getByRole('button', { name: 'Aplicar times' }).click();
      await expect(dialog.getByRole('alert')).toContainText('Gere uma nova combinação');
      await expect(dialog.getByRole('button', { name: 'Aplicar times' })).toHaveCount(0);
      await dialog.getByRole('button', { name: 'Gerar prévia' }).click();
      await dialog.getByRole('button', { name: 'Gerar outra combinação' }).click();
      await expect(dialog.getByRole('button', { name: 'Aplicar times' })).toBeEnabled();
      await dialog.getByRole('button', { name: 'Aplicar times' }).click();
      await expect(dialog).toHaveCount(0);
      const after = await (await owner.ctx.get(`/api/games/${game.game.id}`)).json();
      expect(after.attendees.every((p: any) => p.teamId !== null)).toBeTruthy();
      for (const team of after.teams)
        expect(after.attendees.filter((p: any) => p.teamId === team.id)).toHaveLength(3);
      await page.reload();
      await page.getByRole('button', { name: 'Sortear times', exact: true }).click();
      await dialog.getByRole('button', { name: 'Histórico de sorteios aplicados' }).click();
      await expect(dialog.locator('details')).toHaveCount(1);
      await dialog.locator('summary').click();
      await expect(dialog.getByText('Rafael Organizador · Nível 5', { exact: true })).toBeVisible();
      // Random mode remains available and does not change the real teams until Apply.
      await dialog.getByRole('radio', { name: 'Aleatório', exact: false }).check();
      await dialog.getByRole('button', { name: 'Gerar prévia' }).click();
      await dialog.getByRole('button', { name: 'Voltar', exact: true }).click();
      expect((await (await owner.ctx.get(`/api/games/${game.game.id}`)).json()).attendees).toEqual(
        after.attendees,
      );
      const participant = await browser.newContext({
        baseURL,
        storageState: await accounts[1].ctx.storageState(),
      });
      const participantPage = await participant.newPage();
      await participantPage.goto('/#group/' + club.id);
      await participantPage.getByRole('tab', { name: 'Jogadores', exact: true }).click();
      await expect(
        participantPage.getByText('A classificação é definida pelo organizador do grupo.'),
      ).toBeVisible();
      await expect(
        participantPage.getByRole('button', { name: 'Classificar', exact: true }),
      ).toHaveCount(0);
      expect(
        (
          await mutate(accounts[1].ctx, `/games/${game.game.id}/teams/draw/preview`, 'POST', {
            mode: 'BALANCED',
          })
        ).status(),
      ).toBe(403);
      for (const player of accounts) await player.ctx.dispose();
      await context.close();
      await participant.close();
    });
  }
