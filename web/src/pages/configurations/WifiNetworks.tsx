// The configuration editor's Wi-Fi networks (Network section): the networks every device on the configuration saves,
// one row each, in the order they are saved. Every edit hands the editor a whole new list for its draft; the rules a
// list must follow, and which rows break them, are data/wifiNetworks.ts.
import { useRef, useState } from 'react';
import type { WifiNetwork } from '../../api/configurations';
import {
  WIFI_NETWORKS_AFTER_AGENT,
  WIFI_NETWORKS_MAX,
  WIFI_SECURITY_OPTIONS,
  editWifiNetwork,
  newWifiNetwork,
  type WifiNetworkProblem,
  type WifiSecurityType,
} from '../../data/wifiNetworks';
import { Button, ChangedChip, Checkbox, Eyebrow, Field, IconButton, IconClose, IconPlus, Input, Select } from '../../ui';
import { AgentTag, FieldHelp, SecretInput } from './controls';

const HELP = 'Saved on every device with this configuration. Passwords are kept in plain text.';
const MORE =
  `A device joins whichever of these networks is in range by itself, so a shared tablet already knows the Wi-Fi at every location. Passwords are stored and sent to devices in plain text, like the setup Wi-Fi password. Hidden network is for one that doesn’t broadcast its name. Removing a network here makes devices forget it, except while it is a device’s only connection. Needs an agent newer than ${WIFI_NETWORKS_AFTER_AGENT}: a device on an older one ignores the list and shows as out of sync.`;

export interface WifiNetworksProps {
  /** The draft's list, in saved order. */
  networks: readonly WifiNetwork[];
  onChange: (next: WifiNetwork[]) => void;
  readOnly: boolean;
  /** The list differs from the saved one. */
  changed: boolean;
  /** What stops a save, by row; the editor leaves out what it does not show yet (wifiNetworkProblems `missing`). */
  problems: readonly WifiNetworkProblem[];
  /** The fields' ids are `<idBase>-<row>-ssid` and `<idBase>-<row>-password`: the editor focuses the one a save stops at. */
  idBase: string;
}

// Row keys: a list has no ids of its own, and a row keeps its state (password shown or not) when one above it is
// removed.
let lastKey = 0;
const freshKeys = (count: number) => Array.from({ length: count }, () => ++lastKey);

export function WifiNetworks({ networks, onChange, readOnly, changed, problems, idBase }: WifiNetworksProps) {
  const [keys, setKeys] = useState(() => freshKeys(networks.length));
  // A list replaced from outside (not by a row here) gets new rows.
  if (keys.length !== networks.length) setKeys(freshKeys(networks.length));
  // The row "Add network" made: its name field takes the focus.
  const [added, setAdded] = useState<number | null>(null);
  const addButton = useRef<HTMLButtonElement>(null);
  const full = networks.length >= WIFI_NETWORKS_MAX;

  const add = () => {
    const [key] = freshKeys(1);
    setKeys([...keys, key]);
    setAdded(key);
    onChange([...networks, newWifiNetwork()]);
  };
  const edit = (index: number, patch: Partial<WifiNetwork>) =>
    onChange(networks.map((n, i) => (i === index ? editWifiNetwork(n, patch) : n)));
  const remove = (index: number) => {
    setKeys(keys.filter((_, i) => i !== index));
    onChange(networks.filter((_, i) => i !== index));
    // The pressed button goes with its row: the focus moves on to Add network instead of being lost.
    addButton.current?.focus({ preventScroll: true });
  };

  return (
    <div className="v2-cfged-wifi">
      <div className="v2-cfged-eyebrow">
        <div className="v2-cfged-labelline">
          <Eyebrow as="h3">Wi-Fi networks</Eyebrow>
          <AgentTag version={WIFI_NETWORKS_AFTER_AGENT} after />
          {changed && !readOnly ? <ChangedChip /> : null}
        </div>
      </div>
      <p className="v2-cfged-wifi__help">
        <FieldHelp text={HELP} more={MORE} />
      </p>
      {networks.length === 0 ? <p className="v2-cfged-wifi__empty">{readOnly ? 'No networks.' : 'No networks yet.'}</p> : null}
      {networks.map((network, index) => (
        <NetworkRow
          key={keys[index]}
          network={network}
          index={index}
          problems={problems.filter((p) => p.index === index)}
          readOnly={readOnly}
          idBase={idBase}
          autoFocus={keys[index] === added}
          onEdit={(patch) => edit(index, patch)}
          onRemove={() => remove(index)}
        />
      ))}
      {readOnly ? null : (
        <div className="v2-cfged-wifi__add">
          <Button ref={addButton} size="sm" icon={<IconPlus />} onClick={add} disabled={full}>
            Add network
          </Button>
          {full ? <span className="v2-cfged-wifi__limit">A configuration holds at most {WIFI_NETWORKS_MAX} networks.</span> : null}
        </div>
      )}
    </div>
  );
}

function NetworkRow({
  network,
  index,
  problems,
  readOnly,
  idBase,
  autoFocus,
  onEdit,
  onRemove,
}: {
  network: WifiNetwork;
  index: number;
  problems: readonly WifiNetworkProblem[];
  readOnly: boolean;
  idBase: string;
  autoFocus: boolean;
  onEdit: (patch: Partial<WifiNetwork>) => void;
  onRemove: () => void;
}) {
  const id = (part: string) => `${idBase}-${index}-${part}`;
  const errorId = problems.length > 0 ? id('error') : undefined;
  const wrong = (field: 'ssid' | 'password') => problems.some((p) => p.field === field);
  const type = network.securityType;
  // An open network shows no password field, unless one was stored with it: then it shows, to be cleared.
  const withPassword = type !== 'NONE' || !!network.password;
  return (
    <div role="group" aria-label={`Network ${index + 1}`} className="v2-cfged-wifi__row">
      <div className="v2-cfged-wifi__fields">
        <Field label="Network name (SSID)" size="sm" id={id('ssid')} className="v2-cfged-wifi__name">
          <Input
            size="sm"
            value={network.ssid}
            disabled={readOnly}
            tone={wrong('ssid') ? 'invalid' : undefined}
            aria-describedby={wrong('ssid') ? errorId : undefined}
            autoComplete="off"
            spellCheck={false}
            autoFocus={autoFocus}
            onChange={(e) => onEdit({ ssid: e.target.value })}
          />
        </Field>
        <Field label="Security" size="sm" id={id('security')} className="v2-cfged-wifi__security">
          <Select size="sm" value={type} disabled={readOnly} onChange={(e) => onEdit({ securityType: e.target.value as WifiSecurityType })}>
            {WIFI_SECURITY_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
            {/* A type a newer server stored stays as it is, instead of reading as the first option. */}
            {WIFI_SECURITY_OPTIONS.some((o) => o.value === type) ? null : <option value={type}>{type}</option>}
          </Select>
        </Field>
        {withPassword ? (
          <Field label="Password" size="sm" id={id('password')}>
            <SecretInput
              value={network.password}
              disabled={readOnly}
              tone={wrong('password') ? 'invalid' : undefined}
              helpId={wrong('password') ? errorId : undefined}
              onChange={(password) => onEdit({ password })}
            />
          </Field>
        ) : null}
        <div className="v2-cfged-wifi__more">
          <Checkbox
            label="Hidden network"
            checked={network.hidden === true}
            disabled={readOnly}
            onChange={(e) => onEdit({ hidden: e.target.checked })}
          />
        </div>
      </div>
      {readOnly ? null : (
        <IconButton size="sm" className="v2-cfged-wifi__rm" label={`Remove ${network.ssid.trim() || `network ${index + 1}`}`} onClick={onRemove}>
          <IconClose />
        </IconButton>
      )}
      {errorId ? (
        <p id={errorId} className="v2-cfged-wifi__error">
          {problems.map((p) => p.message).join(' ')}
        </p>
      ) : null}
    </div>
  );
}
