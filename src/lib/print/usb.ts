export const HP_VENDOR = 0x03f0;
export const M1005_PRODUCT = 0x3b17;

export type PrinterLink = {
  device: USBDevice;
  endpoint: number;
  interfaceNumber: number;
};

type OutEndpoint = { endpointNumber: number; direction: string; type: string };

function hasUsb(nav: Navigator): nav is Navigator & { usb: USB } {
  return "usb" in nav && Boolean(nav.usb);
}

export function usbAvailable() {
  return typeof navigator !== "undefined" && hasUsb(navigator);
}

function pickOutlet(device: USBDevice) {
  const config = device.configurations[0];
  if (!config) return null;
  let fallback: { iface: number; endpoint: number } | null = null;
  for (const iface of config.interfaces) {
    for (const alt of iface.alternates) {
      const out = alt.endpoints.find(
        (endpoint) => endpoint.direction === "out" && endpoint.type === "bulk",
      ) as OutEndpoint | undefined;
      if (!out) continue;
      const hit = { iface: iface.interfaceNumber, endpoint: out.endpointNumber };
      if (alt.interfaceClass === 7) return { ...hit, printerClass: true };
      if (!fallback) fallback = hit;
    }
  }
  return fallback ? { ...fallback, printerClass: false } : null;
}

export async function connectPrinter(): Promise<PrinterLink> {
  if (!hasUsb(navigator)) {
    throw new Error("This browser cannot talk to USB. Open Noko Print in Chrome on the phone.");
  }
  const device = await navigator.usb.requestDevice({
    filters: [{ vendorId: HP_VENDOR, productId: M1005_PRODUCT }],
  });
  if (!device.opened) await device.open();
  if (device.configuration === null && device.configurations[0]) {
    await device.selectConfiguration(device.configurations[0].configurationValue);
  }
  const outlet = pickOutlet(device);
  if (!outlet) throw new Error("The M1005 did not offer a page outlet to this phone.");
  await device.claimInterface(outlet.iface);
  if (outlet.printerClass) {
    try {
      await device.controlTransferOut({
        requestType: "class",
        recipient: "interface",
        request: 2,
        value: 0,
        index: outlet.iface,
      });
    } catch {
      // Some cables ignore the reset. The job can still print.
    }
  }
  return { device, endpoint: outlet.endpoint, interfaceNumber: outlet.iface };
}

export async function sendJob(link: PrinterLink, job: Uint8Array) {
  const chunk = 16 * 1024;
  for (let offset = 0; offset < job.length; offset += chunk) {
    const slice = job.slice(offset, Math.min(offset + chunk, job.length));
    const result = await link.device.transferOut(link.endpoint, slice);
    if (result.status !== "ok") {
      throw new Error("The printer stopped taking the page. Check paper and the toner door.");
    }
  }
}
