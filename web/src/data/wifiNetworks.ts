// The Wi-Fi networks a configuration saves on its devices (Configuration.wifiNetworks): the rules the server checks on
// save, checked here first so a save stops at the row with a sentence instead of a refused request, and what the
// editor's list needs to add, edit and compare rows. Pure.
import type { WifiNetwork } from '../api/configurations';

export type WifiSecurityType = WifiNetwork['securityType'];

/** The most networks one configuration holds. */
export const WIFI_NETWORKS_MAX = 32;
/** The longest network name, in bytes of UTF-8. */
const WIFI_SSID_MAX_BYTES = 32;
/** The last agent release without the list: devices need a newer one. */
export const WIFI_NETWORKS_AFTER_AGENT = '0.5.0';

/** The security types, in the order the editor lists them. */
export const WIFI_SECURITY_OPTIONS: ReadonlyArray<{ value: WifiSecurityType; label: string }> = [
  { value: 'NONE', label: 'Open' },
  { value: 'WPA', label: 'WPA/WPA2' },
  { value: 'SAE', label: 'WPA3' },
  { value: 'WEP', label: 'WEP' },
];

/** The row "Add network" starts: WPA/WPA2, to be filled in. */
export const newWifiNetwork = (): WifiNetwork => ({ ssid: '', securityType: 'WPA', password: '', hidden: false });

/**
 * A row with an edit applied. Name and password stay exactly as typed. An open network has no password: choosing
 * Open drops the one typed, and an emptied one is null, as the server takes it.
 */
export function editWifiNetwork(network: WifiNetwork, patch: Partial<WifiNetwork>): WifiNetwork {
  const next = { ...network, ...patch };
  if (next.securityType !== 'NONE') return next;
  return patch.securityType === 'NONE' || !next.password ? { ...next, password: null } : next;
}

/**
 * The list as one string, to tell an edited list from the saved one. The order counts (it is the saved order). No
 * list, null and `[]` are the same; so are no password and an empty one, and no `hidden` and false.
 */
export function wifiNetworksSignature(list: readonly WifiNetwork[] | null | undefined): string {
  return JSON.stringify((list ?? []).map((n) => [n.ssid, n.securityType, n.password ?? '', n.hidden === true]));
}

export interface WifiNetworkProblem {
  /** The row: its place in the list, from 0. */
  index: number;
  /** The field the sentence is about; none when it is about the row itself (one more than a configuration holds). */
  field?: 'ssid' | 'password';
  message: string;
  /** Nothing entered yet: the editor says so once a save was tried, not while a new row is being filled in. */
  missing?: true;
}

const HEX = /^[0-9a-fA-F]+$/;
/** Blank as the server reads it (Java's trim): nothing but spaces and control characters. */
const BLANK = /^[\x00-\x20]*$/;
/** Tabs, line breaks and the like: they can't be typed on a device. */
const CONTROL = /[\x00-\x1f\x7f-\x9f]/;
const PRINTABLE_ASCII = /^[\x20-\x7e]*$/;
const utf8 = new TextEncoder();

function passwordProblem({ securityType, password }: WifiNetwork): Pick<WifiNetworkProblem, 'message' | 'missing'> | null {
  const p = password ?? '';
  if (securityType === 'NONE') return p ? { message: 'An open network has no password. Clear this field.' } : null;
  // A type a newer server added: its rules are the server's to check.
  if (securityType !== 'WPA' && securityType !== 'SAE' && securityType !== 'WEP') return null;
  if (!p) return { message: 'Enter the password, or set security to Open.', missing: true };
  if (!PRINTABLE_ASCII.test(p)) {
    return { message: 'A Wi-Fi password takes plain (ASCII) characters only: no accented letters, emoji or tabs.' };
  }
  const hex = HEX.test(p);
  if (securityType === 'WPA') {
    return (p.length >= 8 && p.length <= 63) || (p.length === 64 && hex)
      ? null
      : { message: 'A WPA password has 8 to 63 characters, or is a 64-digit hex key.' };
  }
  if (securityType === 'SAE') return p.length <= 63 ? null : { message: 'A WPA3 password has at most 63 characters.' };
  return [5, 13, 16].includes(p.length) || (hex && [10, 26, 32].includes(p.length))
    ? null
    : { message: 'A WEP key has 5, 13 or 16 characters, or 10, 26 or 32 hex digits.' };
}

/**
 * What the server would refuse in the list (com.hmdm.util.WifiNetworkRules), row by row, in list order; empty when it
 * can be saved. At most 32 networks; a name that is not blank, at most 32 bytes of UTF-8, without control characters
 * and in the list once (compared as typed, so by case); a password in plain ASCII that fits the security type.
 */
export function wifiNetworkProblems(list: readonly WifiNetwork[]): WifiNetworkProblem[] {
  const out: WifiNetworkProblem[] = [];
  const names = new Set<string>();
  list.forEach((network, index) => {
    if (index >= WIFI_NETWORKS_MAX) {
      out.push({ index, message: `A configuration holds at most ${WIFI_NETWORKS_MAX} Wi-Fi networks. Remove this one.` });
    }
    const ssid = network.ssid;
    const bytes = utf8.encode(ssid).length;
    if (BLANK.test(ssid)) {
      out.push({ index, field: 'ssid', message: 'Enter the network name.', missing: true });
    } else if (bytes > WIFI_SSID_MAX_BYTES) {
      out.push({
        index,
        field: 'ssid',
        message: `A network name is at most ${WIFI_SSID_MAX_BYTES} bytes long, and this one is ${bytes}. Accented letters and emoji take more than one.`,
      });
    } else if (CONTROL.test(ssid)) {
      out.push({ index, field: 'ssid', message: 'A network name can’t have tabs or other control characters in it.' });
    } else if (names.has(ssid)) {
      out.push({ index, field: 'ssid', message: 'This network is already in the list.' });
    }
    if (!BLANK.test(ssid)) names.add(ssid);
    const password = passwordProblem(network);
    if (password) out.push({ index, field: 'password', ...password });
  });
  return out;
}
