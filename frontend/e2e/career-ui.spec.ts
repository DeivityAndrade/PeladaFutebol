import { test, expect } from '@playwright/test';

test('carreira recupera falha de leitura e mantém recompensas bloqueadas acessíveis', async ({
  page,
}) => {
  await page.route('**/api/auth/me', (route) =>
    route.fulfill({
      json: {
        id: 'jogador-teste',
        name: 'Pessoa de teste',
        email: 'teste@example.invalid',
        admin: false,
      },
    }),
  );
  await page.route('**/api/career/groups', (route) =>
    route.fulfill({ json: [{ clubId: 'grupo-teste', name: 'Grupo de teste', member: true }] }),
  );
  let failed = false;
  await page.route('**/api/groups/grupo-teste/career/me', (route) => {
    if (!failed) {
      failed = true;
      return route.fulfill({
        status: 503,
        json: { message: 'Não foi possível carregar sua coleção.' },
      });
    }
    return route.fulfill({
      json: {
        clubId: 'grupo-teste',
        clubName: 'Grupo de teste',
        timeZone: 'America/Sao_Paulo',
        program: { active: true, startedAt: '2026-10-08T12:00:00Z', canManage: false },
        canShare: true,
        card: {
          playerId: 'jogador-teste',
          name: 'Pessoa de teste',
          title: null,
          frame: null,
          badges: [],
          shared: false,
        },
        appearances: 0,
        history: [],
        pending: [],
        correctionUnseen: false,
        achievements: [
          {
            code: 'FIRST_APPEARANCE',
            name: 'Tô dentro',
            threshold: 1,
            reward: 'Selo de estreia',
            awardedAt: null,
            unseen: false,
          },
          {
            code: 'FIVE_APPEARANCES',
            name: 'Já é de casa',
            threshold: 5,
            reward: 'Título',
            awardedAt: null,
            unseen: false,
          },
          {
            code: 'TEN_APPEARANCES',
            name: 'Figurinha carimbada',
            threshold: 10,
            reward: 'Moldura',
            awardedAt: null,
            unseen: false,
          },
          {
            code: 'TWENTY_FIVE_APPEARANCES',
            name: 'Parte da história',
            threshold: 25,
            reward: 'Selo e título',
            awardedAt: null,
            unseen: false,
          },
        ],
      },
    });
  });
  await page.goto('/#career');
  await expect(page.getByRole('alert')).toContainText('Não foi possível carregar sua coleção.');
  await page.getByRole('button', { name: 'Tentar novamente', exact: true }).click();
  await expect(page.getByText('Sua figurinha está privada.')).toBeVisible();
  await page.getByText('Personalizar figurinha', { exact: true }).click();
  await expect(
    page.getByRole('checkbox', { name: 'Tô dentro · Ainda não conquistado', exact: true }),
  ).toBeDisabled();
  await expect(page.getByLabel('Título', { exact: true }).locator('option')).toHaveCount(1);
  await expect(page.getByLabel('Moldura', { exact: true }).locator('option')).toHaveCount(1);
  await expect(page.getByRole('progressbar')).toHaveAttribute('value', '0');
  await expect(page.getByRole('button', { name: 'Ativar conquistas', exact: true })).toHaveCount(0);
});
