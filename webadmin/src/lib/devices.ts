import type { Client } from "./api/client";
import { t } from "./i18n/i18n.svelte";

/**
 * How [client] paired, in the interface language (spec web-admin, "Paired devices"). The inviting device's name is
 * looked up by ID in [clients], because names are free text; an ID that is not there is a removed device.
 */
export function originText(client: Client, clients: readonly Client[]): string {
  switch (client.pairedVia) {
    case "code":
      return t("devices.origin.code");
    case "qr":
      return t("devices.origin.qr");
    case "invite": {
      const inviter = client.invitedBy ? clients.find((c) => c.id === client.invitedBy) : undefined;
      return inviter
        ? t("devices.origin.invitedBy", { name: inviter.name })
        : t("devices.origin.invitedByRemoved");
    }
    default:
      return t("devices.origin.unknown");
  }
}
