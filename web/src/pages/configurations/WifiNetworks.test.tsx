// The configuration editor's Wi-Fi networks: the rows and what they edit, the changed count and Discard, what a save
// sends, and the save stopped at a row the server would refuse. Rendered through the real routes against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import axe from 'axe-core';
import { describe, expect, it, vi } from 'vitest';
import type { WifiNetwork } from '../../api/configurations';
import { AppRoutes } from '../../App';
import { WIFI_NETWORKS_MAX } from '../../data/wifiNetworks';
import { renderInConsole } from '../../test/console';
import { chooseOption, selectValue } from '../../test/select';

const DEPOT: WifiNetwork = { ssid: 'Depot', securityType: 'WPA', password: 'correct horse', hidden: false };
const GUEST: WifiNetwork = { ssid: 'Guest', securityType: 'NONE', password: null, hidden: false };
const LAB: WifiNetwork = { ssid: 'Lab', securityType: 'SAE', password: 'staple', hidden: true };

/** Configuration `id` ("Warehouse", 7, unless given) as the server answers it; `wifiNetworks: undefined` is a server from before the list. */
function open(configuration: Record<string, unknown> = {}) {
  const stored = { id: 7, name: 'Warehouse', kioskMode: false, extensions: {}, ...configuration };
  const puts: Record<string, unknown>[] = [];
  const answer = (path: string, init?: RequestInit): unknown => {
    const p = new URL(path, 'http://console').pathname.replace('/rest', '');
    if (p === '/private/configurations' && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>;
      puts.push(body);
      return body;
    }
    if (p === `/private/configurations/${stored.id}`) return stored;
    // A save leads back to the list, which loads after the test's last line.
    if (p === '/private/configurations/search') return [stored];
    if (p === `/private/configurations/applications/${stored.id}`) return [];
    if (p.startsWith('/private/applications/search')) return [];
    if (p === '/private/agent/v1/configurations/syncSummary') {
      return [{ configurationId: stored.id, total: 0, inSync: 0, outOfSync: 0, unsupported: 0, neverSeen: 0 }];
    }
    return null;
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const out = answer(String(input), init);
      return new Response(JSON.stringify({ status: 'OK', message: null, data: out ?? null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );
  renderInConsole(<AppRoutes />, { path: `/configurations/${stored.id}` });
  return { puts };
}

const card = () => document.getElementById('network')!;
const loaded = () => screen.findByRole('heading', { name: 'Wi-Fi networks' });
const rows = () => within(card()).queryAllByRole('group', { name: /^Network \d+$/ });
const row = (n: number) => within(within(card()).getByRole('group', { name: `Network ${n}` }));
const nameOf = (n: number) => row(n).getByLabelText<HTMLInputElement>('Network name (SSID)');
const securityOf = (n: number) => row(n).getByRole('combobox', { name: 'Security' });
const passwordOf = (n: number) => row(n).queryByLabelText<HTMLInputElement>('Password');
const hiddenOf = (n: number) => row(n).getByRole<HTMLInputElement>('checkbox', { name: 'Hidden network' });
const names = () => rows().map((r) => within(r).getByLabelText<HTMLInputElement>('Network name (SSID)').value);
const add = () => fireEvent.click(within(card()).getByRole('button', { name: 'Add network' }));
const type = (field: HTMLElement | null, value: string) => fireEvent.change(field!, { target: { value } });
const saveBar = () => screen.queryByRole('region', { name: 'Unsaved changes' });
const navEntry = () => within(screen.getByRole('navigation', { name: 'Sections' })).getByRole('link', { name: /^Network/ });
const toastText = () =>
  Array.from(screen.getByRole('region', { name: 'Notifications' }).querySelectorAll('.toast'))
    .map((t) => t.textContent)
    .join(' | ');

async function save() {
  const button = within(saveBar()!).getByRole<HTMLButtonElement>('button', { name: 'Save and apply' });
  await waitFor(() => expect(button.disabled).toBe(false));
  fireEvent.click(button);
}
async function editDescription(text: string) {
  const area = (await screen.findByText('Description')).closest('.v2-field')!.querySelector('textarea')!;
  fireEvent.change(area, { target: { value: text } });
}

describe('configuration editor: Wi-Fi networks', () => {
  it('shows the saved networks in their saved order, an open one without a password field', async () => {
    open({ wifiNetworks: [DEPOT, GUEST, LAB] });
    await loaded();
    expect(names()).toEqual(['Depot', 'Guest', 'Lab']);
    expect(selectValue(securityOf(1))).toBe('WPA');
    expect(securityOf(1).textContent).toContain('WPA/WPA2');
    expect(securityOf(3).textContent).toContain('WPA3');
    expect(passwordOf(1)!.value).toBe('correct horse');
    expect(passwordOf(2)).toBeNull();
    expect(hiddenOf(1).checked).toBe(false);
    expect(hiddenOf(3).checked).toBe(true);
    expect(saveBar()).toBeNull();
    expect(within(card()).queryByText('No networks yet.')).toBeNull();
  });

  it('masks a password until Show is pressed, each row by itself', async () => {
    open({ wifiNetworks: [DEPOT, LAB] });
    await loaded();
    expect(passwordOf(1)!.type).toBe('password');
    fireEvent.click(row(1).getByRole('button', { name: 'Show' }));
    expect(passwordOf(1)!.type).toBe('text');
    expect(passwordOf(2)!.type).toBe('password');
    expect(row(1).getByRole('button', { name: 'Hide' }).getAttribute('aria-pressed')).toBe('true');
  });

  it('says what the list is for, and that the enrollment QR code carries only the setup network', async () => {
    open({ wifiNetworks: [] });
    await loaded();
    expect(within(card()).getByText(/Saved on every device with this configuration\. Passwords are kept in plain text\./)).toBeTruthy();
    expect(within(card()).getByText('No networks yet.')).toBeTruthy();
    expect(within(card()).getByText('After agent 0.5.0')).toBeTruthy();
    fireEvent.click(within(card().querySelector('.v2-cfged-wifi')!).getByRole('button', { name: 'Learn more' }));
    expect(within(card()).getByText(/sent to devices in plain text, like the setup Wi-Fi password\..*Removing a network here makes devices forget it.*Needs an agent newer than 0\.5\.0/)).toBeTruthy();
    const enrollment = document.getElementById('enrollment')!;
    expect(within(enrollment).getByText(/The QR code can carry only this one network\..*Network → Wi-Fi networks/)).toBeTruthy();
    // The setup Wi-Fi fields are as they were.
    expect(within(enrollment).getByText('Setup Wi-Fi name (SSID)')).toBeTruthy();
    expect(within(enrollment).getByText('Setup Wi-Fi password')).toBeTruthy();
    expect(within(enrollment).getByText('Setup Wi-Fi security')).toBeTruthy();
  });

  it('adds a network: its name field takes the focus, the change counts once, and the save sends it as typed', async () => {
    const { puts } = open({ wifiNetworks: [DEPOT] });
    await loaded();
    add();
    expect(names()).toEqual(['Depot', '']);
    expect(document.activeElement).toBe(nameOf(2));
    expect(selectValue(securityOf(2))).toBe('WPA');
    // Nothing is trimmed, in the name or the password.
    type(nameOf(2), ' Yard 5 ');
    chooseOption(securityOf(2), 'WPA3');
    type(passwordOf(2), ' pass word ');
    fireEvent.click(hiddenOf(2));
    expect(saveBar()?.textContent).toContain('1 unsaved change');
    expect(navEntry().textContent).toContain('1 changed');
    expect(within(card().querySelector('.v2-cfged-wifi')!).getByText('Changed')).toBeTruthy();
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].wifiNetworks).toEqual([DEPOT, { ssid: ' Yard 5 ', securityType: 'SAE', password: ' pass word ', hidden: true }]);
  });

  it('edits a network in place and keeps the order', async () => {
    const { puts } = open({ wifiNetworks: [DEPOT, GUEST, LAB] });
    await loaded();
    type(nameOf(2), 'Visitors');
    type(passwordOf(3), 'stapler');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].wifiNetworks).toEqual([DEPOT, { ...GUEST, ssid: 'Visitors' }, { ...LAB, password: 'stapler' }]);
  });

  it('drops the password of a network set to Open, and sends null for it', async () => {
    const { puts } = open({ wifiNetworks: [DEPOT] });
    await loaded();
    chooseOption(securityOf(1), 'Open');
    expect(passwordOf(1)).toBeNull();
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].wifiNetworks).toEqual([{ ssid: 'Depot', securityType: 'NONE', password: null, hidden: false }]);
  });

  it('removes a network: the rows after it keep their state, and the focus moves to Add network', async () => {
    const { puts } = open({ wifiNetworks: [DEPOT, GUEST, LAB] });
    await loaded();
    fireEvent.click(row(3).getByRole('button', { name: 'Show' }));
    fireEvent.click(row(1).getByRole('button', { name: 'Remove Depot' }));
    expect(names()).toEqual(['Guest', 'Lab']);
    // Lab is the second row now, its password still shown.
    expect(passwordOf(2)!.type).toBe('text');
    expect(document.activeElement).toBe(within(card()).getByRole('button', { name: 'Add network' }));
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].wifiNetworks).toEqual([GUEST, LAB]);
  });

  it('sends an empty list when the last network is removed, so devices forget it', async () => {
    const { puts } = open({ wifiNetworks: [GUEST] });
    await loaded();
    fireEvent.click(row(1).getByRole('button', { name: 'Remove Guest' }));
    expect(within(card()).getByText('No networks yet.')).toBeTruthy();
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].wifiNetworks).toEqual([]);
  });

  it('counts a list put back as it was as no change, and another order as one', async () => {
    open({ wifiNetworks: [DEPOT, GUEST] });
    await loaded();
    type(nameOf(1), 'depot');
    expect(saveBar()?.textContent).toContain('1 unsaved change');
    type(nameOf(1), 'Depot');
    await waitFor(() => expect(saveBar()).toBeNull());
    // The same two networks the other way round: the order is what is saved.
    type(nameOf(1), 'Guest');
    chooseOption(securityOf(1), 'Open');
    type(nameOf(2), 'Depot');
    chooseOption(securityOf(2), 'WPA/WPA2');
    type(passwordOf(2), 'correct horse');
    expect(names()).toEqual(['Guest', 'Depot']);
    expect(saveBar()?.textContent).toContain('1 unsaved change');
  });

  it('Discard puts the saved list back', async () => {
    open({ wifiNetworks: [DEPOT, GUEST] });
    await loaded();
    fireEvent.click(row(2).getByRole('button', { name: 'Remove Guest' }));
    add();
    type(nameOf(2), 'Yard');
    fireEvent.click(within(saveBar()!).getByRole('button', { name: 'Discard' }));
    await waitFor(() => expect(saveBar()).toBeNull());
    expect(names()).toEqual(['Depot', 'Guest']);
    expect(passwordOf(1)!.value).toBe('correct horse');
    expect(navEntry().textContent).not.toContain('changed');
  });

  it('stops the save at a network that is not filled in, and says what it lacks at the row and in the toast', async () => {
    const { puts } = open({ wifiNetworks: [DEPOT] });
    await loaded();
    add();
    // A row just added is not told off yet.
    expect(row(2).queryByText(/Enter the/)).toBeNull();
    expect(navEntry().textContent).toContain('1 changed');
    await save();
    await waitFor(() => expect(toastText()).toContain('Check the Wi-Fi networks'));
    expect(toastText()).toContain('Network 2: Enter the network name.');
    expect(puts).toHaveLength(0);
    expect(document.activeElement).toBe(nameOf(2));
    expect(row(2).getByText('Enter the network name. Enter the password, or set security to Open.')).toBeTruthy();
    expect(nameOf(2).getAttribute('aria-invalid')).toBe('true');
    expect(nameOf(2).getAttribute('aria-describedby')).toBe(row(2).getByText(/Enter the network name/).id);
    expect(passwordOf(2)!.getAttribute('aria-invalid')).toBe('true');
    expect(navEntry().textContent).toContain('Check Wi-Fi');
    // The row that is fine says nothing.
    expect(nameOf(1).getAttribute('aria-invalid')).toBeNull();

    type(nameOf(2), 'Yard');
    expect(row(2).getByText('Enter the password, or set security to Open.')).toBeTruthy();
    await save();
    await waitFor(() => expect(document.activeElement).toBe(passwordOf(2)));
    expect(puts).toHaveLength(0);

    type(passwordOf(2), 'long enough');
    expect(row(2).queryByText(/Enter the/)).toBeNull();
    expect(navEntry().textContent).toContain('1 changed');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].wifiNetworks).toEqual([DEPOT, { ssid: 'Yard', securityType: 'WPA', password: 'long enough', hidden: false }]);
  });

  it('says a password that does not fit the security while it is typed, and takes it once it fits', async () => {
    const { puts } = open({ wifiNetworks: [DEPOT] });
    await loaded();
    type(passwordOf(1), 'short');
    expect(row(1).getByText('A WPA password has 8 to 63 characters, or is a 64-digit hex key.')).toBeTruthy();
    expect(passwordOf(1)!.getAttribute('aria-invalid')).toBe('true');
    expect(navEntry().textContent).toContain('Check Wi-Fi');
    await save();
    await waitFor(() => expect(toastText()).toContain('Network 1: A WPA password has 8 to 63 characters'));
    expect(puts).toHaveLength(0);
    // Five characters are a WEP key, and one is enough for WPA3.
    chooseOption(securityOf(1), 'WEP');
    expect(row(1).queryByText(/A WPA password/)).toBeNull();
    type(passwordOf(1), 'shor');
    expect(row(1).getByText('A WEP key has 5, 13 or 16 characters, or 10, 26 or 32 hex digits.')).toBeTruthy();
    chooseOption(securityOf(1), 'WPA3');
    expect(row(1).queryByText(/A WEP key/)).toBeNull();
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].wifiNetworks).toEqual([{ ...DEPOT, securityType: 'SAE', password: 'shor' }]);
  });

  it('takes a network name once, and no longer than 32 bytes', async () => {
    const { puts } = open({ wifiNetworks: [DEPOT, GUEST] });
    await loaded();
    type(nameOf(2), 'Depot');
    expect(row(2).getByText('This network is already in the list.')).toBeTruthy();
    expect(nameOf(1).getAttribute('aria-invalid')).toBeNull();
    // Another case is another network.
    type(nameOf(2), 'depot');
    expect(row(2).queryByText('This network is already in the list.')).toBeNull();
    type(nameOf(2), 'Lagerhalle Süd, Gästezugang 2. OG');
    expect(row(2).getByText(/at most 32 bytes long, and this one is 35/)).toBeTruthy();
    await save();
    await waitFor(() => expect(toastText()).toContain('Check the Wi-Fi networks'));
    expect(puts).toHaveLength(0);
  });

  it('never checks a saved list that a save leaves as it is', async () => {
    // Stored before a rule the console knows: it goes back as it came, and the server decides.
    const stored = [{ ssid: 'Depot', securityType: 'WPA', password: 'short' }];
    const { puts } = open({ wifiNetworks: stored });
    await loaded();
    expect(row(1).queryByText(/A WPA password/)).toBeNull();
    await editDescription('Scanners');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].wifiNetworks).toEqual(stored);
  });

  it('holds 32 networks: Add network is off at the limit, and on again below it', async () => {
    const full = Array.from({ length: WIFI_NETWORKS_MAX }, (_, i) => ({ ...GUEST, ssid: `Site ${i + 1}` }));
    open({ wifiNetworks: full });
    await loaded();
    const button = within(card()).getByRole<HTMLButtonElement>('button', { name: 'Add network' });
    expect(button.disabled).toBe(true);
    expect(within(card()).getByText('A configuration holds at most 32 networks.')).toBeTruthy();
    fireEvent.click(row(32).getByRole('button', { name: 'Remove Site 32' }));
    expect(button.disabled).toBe(false);
    expect(within(card()).queryByText('A configuration holds at most 32 networks.')).toBeNull();
  });

  it('keeps a security type it does not know as it is', async () => {
    const { puts } = open({ wifiNetworks: [{ ssid: 'Campus', securityType: 'EAP', password: 'x' }] });
    await loaded();
    expect(selectValue(securityOf(1))).toBe('EAP');
    expect(securityOf(1).textContent).toContain('EAP');
    fireEvent.click(hiddenOf(1));
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].wifiNetworks).toEqual([{ ssid: 'Campus', securityType: 'EAP', password: 'x', hidden: true }]);
  });

  it('has no WCAG A/AA violations, with rows, an open network and a row that stops the save', async () => {
    open({ wifiNetworks: [DEPOT, GUEST, LAB] });
    await loaded();
    add();
    type(passwordOf(1), 'short');
    await save();
    await waitFor(() => expect(row(4).getByText(/Enter the network name/)).toBeTruthy());
    await waitFor(() => expect(document.querySelector('[aria-busy="true"]')).toBeNull());
    // As a11y/routes.a11y.test.tsx scans every route: jsdom has no layout, so contrast is the token test's.
    const res = await axe.run(card(), {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
      rules: { 'color-contrast': { enabled: false } },
    });
    expect(res.violations.map((v) => `${v.id}: ${v.help} — ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
  });

  it('is view only on a built-in template', async () => {
    open({ id: 1, name: 'Managed Launcher', wifiNetworks: [DEPOT, GUEST] });
    await loaded();
    expect(names()).toEqual(['Depot', 'Guest']);
    expect(nameOf(1).disabled).toBe(true);
    expect(passwordOf(1)!.disabled).toBe(true);
    expect(hiddenOf(2).disabled).toBe(true);
    expect((securityOf(1) as HTMLButtonElement).disabled).toBe(true);
    expect(within(card()).queryByRole('button', { name: 'Add network' })).toBeNull();
    expect(within(card()).queryByRole('button', { name: /^Remove/ })).toBeNull();
    expect(saveBar()).toBeNull();
  });
});

describe('configuration editor: Wi-Fi networks on a server from before the list', () => {
  it('shows an empty list, and a save that leaves it alone sends none', async () => {
    const { puts } = open();
    await loaded();
    expect(within(card()).getByText('No networks yet.')).toBeTruthy();
    expect(within(card()).getByRole('button', { name: 'Add network' })).toBeTruthy();
    await editDescription('Scanners');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('wifiNetworks');
  });

  it('sends none either after a network was added and removed again', async () => {
    const { puts } = open();
    await loaded();
    add();
    expect(saveBar()?.textContent).toContain('1 unsaved change');
    fireEvent.click(row(1).getByRole('button', { name: 'Remove network 1' }));
    await waitFor(() => expect(saveBar()).toBeNull());
    await editDescription('Scanners');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('wifiNetworks');
  });

  it('sends the networks entered', async () => {
    const { puts } = open();
    await loaded();
    add();
    type(nameOf(1), 'Depot');
    type(passwordOf(1), 'correct horse');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].wifiNetworks).toEqual([DEPOT]);
  });
});
