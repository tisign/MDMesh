import { describe, expect, it } from 'vitest';
import type { WifiNetwork } from '../api/configurations';
import {
  WIFI_NETWORKS_MAX,
  editWifiNetwork,
  newWifiNetwork,
  wifiNetworkProblems,
  wifiNetworksSignature,
} from './wifiNetworks';

const wpa = (ssid: string, password = 'correct horse'): WifiNetwork => ({ ssid, securityType: 'WPA', password });
const open = (ssid: string): WifiNetwork => ({ ssid, securityType: 'NONE', password: null });
/** The password problem of one network of this type, or undefined. */
const passwordProblem = (securityType: WifiNetwork['securityType'], password: string | null | undefined) =>
  wifiNetworkProblems([{ ssid: 'Depot', securityType, password }]).find((p) => p.field === 'password');

describe('wifiNetworkProblems (the rules the server checks: com.hmdm.util.WifiNetworkRules)', () => {
  it('finds nothing in a list the server takes', () => {
    expect(wifiNetworkProblems([])).toEqual([]);
    expect(
      wifiNetworkProblems([
        wpa('Depot'),
        open('Guest'),
        { ssid: 'Lab', securityType: 'SAE', password: 'x', hidden: true },
        { ssid: 'Scanner', securityType: 'WEP', password: 'abcde' },
        // An open network may come without the field at all.
        { ssid: 'Yard', securityType: 'NONE' },
      ]),
    ).toEqual([]);
  });

  it('asks for a name, and marks it as not entered yet', () => {
    for (const ssid of ['', '   ', '\t', ' \n ']) {
      expect(wifiNetworkProblems([open(ssid)])).toEqual([
        { index: 0, field: 'ssid', message: 'Enter the network name.', missing: true },
      ]);
    }
    // Blank is what the server's trim leaves empty: a no-break space is a name to it.
    expect(wifiNetworkProblems([open('\u00a0')])).toEqual([]);
  });

  it('takes no tab or other control character in a name', () => {
    for (const ssid of ['De\tpot', 'Depot\n', 'De\u0000pot', 'De\u007fpot', 'De\u0085pot']) {
      expect(wifiNetworkProblems([open(ssid)])).toEqual([
        { index: 0, field: 'ssid', message: 'A network name can’t have tabs or other control characters in it.' },
      ]);
    }
    expect(wifiNetworkProblems([open('Dépôt 2.4 GHz (Süd) 📶')])).toEqual([]);
  });

  it('counts a name in bytes of UTF-8: 32 at most', () => {
    expect(wifiNetworkProblems([open('a'.repeat(32))])).toEqual([]);
    expect(wifiNetworkProblems([open('ü'.repeat(16))])).toEqual([]);
    const tooLong = wifiNetworkProblems([open('a'.repeat(33)), open('ü'.repeat(17)), open('📶'.repeat(9))]);
    expect(tooLong.map((p) => [p.index, p.field])).toEqual([[0, 'ssid'], [1, 'ssid'], [2, 'ssid']]);
    expect(tooLong[1].message).toContain('at most 32 bytes long, and this one is 34');
    expect(tooLong[2].message).toContain('this one is 36');
    expect(tooLong[0].missing).toBeUndefined();
  });

  it('takes a name once, compared as typed: another case or a space makes another network', () => {
    const problems = wifiNetworkProblems([wpa('Depot'), open('depot'), open('Depot '), open('Depot'), wpa('Depot')]);
    expect(problems).toEqual([
      { index: 3, field: 'ssid', message: 'This network is already in the list.' },
      { index: 4, field: 'ssid', message: 'This network is already in the list.' },
    ]);
    // Two rows not named yet are not each other’s duplicate.
    expect(wifiNetworkProblems([open(''), open('')]).map((p) => p.message)).toEqual([
      'Enter the network name.',
      'Enter the network name.',
    ]);
  });

  it('takes a WPA password of 8 to 63 characters, or a 64-digit hex key', () => {
    for (const ok of ['12345678', 'x'.repeat(63), 'a1B2'.repeat(16), ' spaces count ']) expect(passwordProblem('WPA', ok)).toBeUndefined();
    for (const bad of ['1234567', 'x'.repeat(64), 'g'.repeat(64), 'a'.repeat(65)]) {
      expect(passwordProblem('WPA', bad)?.message).toBe('A WPA password has 8 to 63 characters, or is a 64-digit hex key.');
    }
  });

  it('takes a password in plain ASCII only, whatever the security', () => {
    const message = 'A Wi-Fi password takes plain (ASCII) characters only: no accented letters, emoji or tabs.';
    for (const type of ['WPA', 'SAE', 'WEP'] as const) {
      for (const bad of ['pässword1', 'pass\tword', '🔒🔒🔒🔒🔒🔒🔒🔒', 'ｐａｓｓｗｏｒｄ', 'abcd\u007f']) {
        expect(passwordProblem(type, bad)?.message).toBe(message);
      }
    }
    expect(passwordProblem('WPA', ' !"#$%&\'()*+,-./:;<=>?@[\\]^_`{|}~')).toBeUndefined();
  });

  it('takes a WPA3 password of 1 to 63 characters', () => {
    for (const ok of ['x', 'x'.repeat(63)]) expect(passwordProblem('SAE', ok)).toBeUndefined();
    expect(passwordProblem('SAE', 'x'.repeat(64))?.message).toBe('A WPA3 password has at most 63 characters.');
  });

  it('takes a WEP key of 5, 13 or 16 characters, or 10, 26 or 32 hex digits', () => {
    for (const ok of ['abcde', 'x'.repeat(13), 'x'.repeat(16), '0123456789', 'aB'.repeat(13), 'f'.repeat(32)]) {
      expect(passwordProblem('WEP', ok)).toBeUndefined();
    }
    for (const bad of ['abcd', 'abcdef', 'x'.repeat(10), 'x'.repeat(26), 'x'.repeat(32), 'f'.repeat(31), 'f'.repeat(33)]) {
      expect(passwordProblem('WEP', bad)?.message).toBe('A WEP key has 5, 13 or 16 characters, or 10, 26 or 32 hex digits.');
    }
  });

  it('asks for the password of a secured network, and marks it as not entered yet', () => {
    for (const type of ['WPA', 'SAE', 'WEP'] as const) {
      for (const none of ['', null, undefined]) {
        expect(passwordProblem(type, none)).toEqual({
          index: 0,
          field: 'password',
          message: 'Enter the password, or set security to Open.',
          missing: true,
        });
      }
    }
  });

  it('takes no password for an open network', () => {
    for (const none of ['', null, undefined]) expect(passwordProblem('NONE', none)).toBeUndefined();
    expect(passwordProblem('NONE', 'left over')?.message).toBe('An open network has no password. Clear this field.');
  });

  it('leaves the password of a security type it does not know to the server', () => {
    for (const password of ['x', 'pässword', '', null]) {
      expect(wifiNetworkProblems([{ ssid: 'Campus', securityType: 'EAP' as never, password }])).toEqual([]);
    }
  });

  it('holds 32 networks: each one after that is to be removed', () => {
    const list = Array.from({ length: WIFI_NETWORKS_MAX + 2 }, (_, i) => open(`Site ${i + 1}`));
    expect(wifiNetworkProblems(list.slice(0, WIFI_NETWORKS_MAX))).toEqual([]);
    expect(wifiNetworkProblems(list)).toEqual([
      { index: 32, message: 'A configuration holds at most 32 Wi-Fi networks. Remove this one.' },
      { index: 33, message: 'A configuration holds at most 32 Wi-Fi networks. Remove this one.' },
    ]);
  });

  it('reports a row’s name and password together, rows in list order', () => {
    expect(wifiNetworkProblems([wpa('Depot'), wpa('', 'short')]).map((p) => [p.index, p.field])).toEqual([
      [1, 'ssid'],
      [1, 'password'],
    ]);
  });
});

describe('editWifiNetwork', () => {
  it('keeps the name and the password exactly as typed', () => {
    const row = editWifiNetwork(editWifiNetwork(newWifiNetwork(), { ssid: ' Depot ' }), { password: ' pass word ' });
    expect(row).toEqual({ ssid: ' Depot ', securityType: 'WPA', password: ' pass word ', hidden: false });
  });

  it('drops the password when Open is chosen, and does not bring it back', () => {
    const opened = editWifiNetwork(wpa('Depot'), { securityType: 'NONE' });
    expect(opened).toEqual({ ssid: 'Depot', securityType: 'NONE', password: null });
    expect(editWifiNetwork(opened, { securityType: 'SAE' })).toEqual({ ssid: 'Depot', securityType: 'SAE', password: null });
  });

  it('keeps the password across the secured types', () => {
    expect(editWifiNetwork(wpa('Depot'), { securityType: 'SAE' }).password).toBe('correct horse');
  });

  it('clears an open network’s left-over password to null, not to an empty string', () => {
    const stray: WifiNetwork = { ssid: 'Guest', securityType: 'NONE', password: 'left over' };
    expect(editWifiNetwork(stray, { hidden: true }).password).toBe('left over');
    expect(editWifiNetwork(stray, { password: '' }).password).toBeNull();
  });

  it('never changes the row it is given', () => {
    const row = wpa('Depot');
    editWifiNetwork(row, { securityType: 'NONE' });
    expect(row).toEqual(wpa('Depot'));
  });
});

describe('wifiNetworksSignature', () => {
  it('reads no list, null and an empty list as the same', () => {
    expect(wifiNetworksSignature(undefined)).toBe(wifiNetworksSignature([]));
    expect(wifiNetworksSignature(null)).toBe(wifiNetworksSignature([]));
  });

  it('reads a left-out password or hidden flag as empty and false', () => {
    expect(wifiNetworksSignature([{ ssid: 'Guest', securityType: 'NONE' }])).toBe(
      wifiNetworksSignature([{ ssid: 'Guest', securityType: 'NONE', password: null, hidden: false }]),
    );
  });

  it('tells every field’s change, by case too', () => {
    const base = wifiNetworksSignature([wpa('Depot')]);
    expect(wifiNetworksSignature([wpa('depot')])).not.toBe(base);
    expect(wifiNetworksSignature([wpa('Depot', 'correct horse ')])).not.toBe(base);
    expect(wifiNetworksSignature([{ ...wpa('Depot'), securityType: 'SAE' }])).not.toBe(base);
    expect(wifiNetworksSignature([{ ...wpa('Depot'), hidden: true }])).not.toBe(base);
  });

  it('counts the order: it is the saved order', () => {
    expect(wifiNetworksSignature([wpa('Depot'), open('Guest')])).not.toBe(wifiNetworksSignature([open('Guest'), wpa('Depot')]));
  });
});
