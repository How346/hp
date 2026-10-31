import { useEffect, useRef, useState } from "react";
import { FileUp, Printer, Usb } from "lucide-react";
import { encodePage } from "@/lib/print/encode";
import { PAPERS, type Paper } from "@/lib/print/papers";
import {
  drawImagePage,
  drawTestPage,
  drawTextPage,
  paintPreview,
  pdfPageCount,
  rasterizePaper,
  rasterizePdfPage,
  paintPdfPreview,
} from "@/lib/print/raster";
import { connectPrinter, sendJob, usbAvailable, type PrinterLink } from "@/lib/print/usb";

type Source =
  | { kind: "test" }
  | { kind: "image"; name: string; image: HTMLImageElement; width: number; height: number }
  | { kind: "text"; name: string; text: string }
  | { kind: "pdf"; name: string; data: ArrayBuffer; pages: number };

export function PrintDesk() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const linkRef = useRef<PrinterLink | null>(null);
  const [source, setSource] = useState<Source>({ kind: "test" });
  const [paper, setPaper] = useState<Paper>(PAPERS[0]);
  const [copies, setCopies] = useState(1);
  const [density, setDensity] = useState(3);
  const [toner, setToner] = useState(false);
  const [previewPage, setPreviewPage] = useState(0);
  const [connected, setConnected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("Turn the printer on and put paper in the tray.");

  useEffect(() => {
    if (!usbAvailable()) return;
    const usb = (navigator as Navigator & { usb: USB }).usb;
    const onDisconnect = (rawEvent: Event) => {
      const event = rawEvent as Event & { device: USBDevice };
      if (linkRef.current?.device === event.device) {
        linkRef.current = null;
        setConnected(false);
        setStatus("Printer disconnected. Check the OTG cable and reconnect.");
      }
    };
    usb.addEventListener("disconnect", onDisconnect);
    return () => usb.removeEventListener("disconnect", onDisconnect);
  }, []);

  useEffect(() => {
    let cancel = false;
    const paint = async () => {
      if (document.fonts?.ready) await document.fonts.ready;
      if (cancel || !canvasRef.current) return;
      const canvas = canvasRef.current;
      if (source.kind === "pdf") {
        await paintPdfPreview(canvas, source.data, previewPage, paper);
        return;
      }
      paintPreview(canvas, paper, (ctx, w, h) => {
        if (source.kind === "image") drawImagePage(ctx, source.image, source.width, source.height, w, h);
        else if (source.kind === "text") drawTextPage(ctx, source.text, w, h);
        else drawTestPage(ctx, w, h);
      });
    };
    void paint();
    return () => {
      cancel = true;
    };
  }, [source, paper, previewPage]);

  async function onFile(file: File) {
    setStatus("Reading the document…");
    setPreviewPage(0);
    const lower = file.name.toLowerCase();
    try {
      if (file.type === "application/pdf" || lower.endsWith(".pdf")) {
        const data = await file.arrayBuffer();
        const pages = await pdfPageCount(data);
        setSource({ kind: "pdf", name: file.name, data, pages });
        setStatus(`${pages} page${pages === 1 ? "" : "s"} ready.`);
        return;
      }
      if (file.type.startsWith("image/")) {
        const url = URL.createObjectURL(file);
        const image = new Image();
        await new Promise<void>((resolve, reject) => {
          image.onload = () => resolve();
          image.onerror = () => reject(new Error("That picture could not be read"));
          image.src = url;
        });
        setSource({ kind: "image", name: file.name, image, width: image.naturalWidth, height: image.naturalHeight });
        setStatus("Picture ready.");
        return;
      }
      const text = await file.text();
      setSource({ kind: "text", name: file.name, text });
      setStatus("Text ready.");
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "That file could not be opened");
    }
  }

  async function ensureLink() {
    if (linkRef.current?.device.opened) return linkRef.current;
    const link = await connectPrinter();
    linkRef.current = link;
    setConnected(true);
    return link;
  }

  async function onConnect() {
    try {
      setStatus("Choose the LaserJet M1005 in the USB list.");
      await ensureLink();
      setStatus("Printer connected. You can print.");
    } catch (error) {
      setConnected(false);
      linkRef.current = null;
      setStatus(error instanceof Error ? error.message : "Could not connect");
    }
  }

  async function onPrint() {
    if (busy) return;
    setBusy(true);
    try {
      const link = await ensureLink();
      setConnected(true);
      const jobs = source.kind === "pdf" ? source.pages : 1;
      for (let copy = 0; copy < copies; copy++) {
        for (let page = 0; page < jobs; page++) {
          setStatus(`Preparing sheet ${copy * jobs + page + 1} of ${copies * jobs}…`);
          const packed =
            source.kind === "pdf"
              ? await rasterizePdfPage(source.data, page, paper)
              : await rasterizePaper(paper, (ctx, w, h) => {
                  if (source.kind === "image") drawImagePage(ctx, source.image, source.width, source.height, w, h);
                  else if (source.kind === "text") drawTextPage(ctx, source.text, w, h);
                  else drawTestPage(ctx, w, h);
                });
          const singleCopy = jobs === 1;
          const job = await encodePage({
            bits: packed.bits,
            width: packed.width,
            height: packed.height,
            stride: packed.stride,
            paper: paper.code,
            density,
            econo: toner,
            copies: singleCopy ? copies : 1,
          });
          setStatus("Sending to the printer…");
          await sendJob(link, job);
          if (singleCopy) {
            setStatus("Sent. The M1005 should pull a sheet now.");
            return;
          }
        }
      }
      setStatus("Sent. The M1005 should pull a sheet now.");
    } catch (error) {
      if (linkRef.current && !linkRef.current.device.opened) {
        linkRef.current = null;
        setConnected(false);
      }
      setStatus(error instanceof Error ? error.message : "Print failed");
    } finally {
      setBusy(false);
    }
  }

  const fileLabel =
    source.kind === "test" ? "Built-in test page" : source.name;

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-3xl flex-col px-4 pt-6 pb-36">
      <header className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-semibold tracking-widest text-ready">NOKO PRINT</p>
          <h1 className="font-display text-4xl leading-none text-ink">LaserJet M1005</h1>
          <p className="mt-2 text-muted">Machine KJ73EDG · black and white</p>
        </div>
        <p className="rounded-full bg-graphite px-3 py-2 text-sm font-semibold text-paper">USB OTG</p>
      </header>

      <section className="mt-6 rounded-card border border-line bg-paper p-4">
        <div className="overflow-hidden rounded-xl border border-line bg-paper">
          <canvas ref={canvasRef} className="h-auto w-full" />
        </div>
        <p className="mt-3 text-sm text-muted">
          {fileLabel}
          {source.kind === "pdf" && source.pages > 1
            ? ` · preview ${previewPage + 1} of ${source.pages}`
            : ""}
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          <button
            type="button"
            className="inline-flex h-12 items-center gap-2 rounded-full bg-graphite px-4 font-semibold text-paper"
            onClick={() => fileRef.current?.click()}
          >
            <FileUp className="size-4" aria-hidden="true" />
            PDF, photo, or text
          </button>
          <button
            type="button"
            className="h-12 rounded-full border border-line bg-bg px-4 font-semibold"
            onClick={() => {
              setSource({ kind: "test" });
              setStatus("Test page ready.");
            }}
          >
            Test page
          </button>
          {source.kind === "pdf" && source.pages > 1 ? (
            <>
              <button
                type="button"
                className="h-12 rounded-full border border-line bg-bg px-4 font-semibold"
                onClick={() => setPreviewPage((page) => Math.max(0, page - 1))}
              >
                Previous
              </button>
              <button
                type="button"
                className="h-12 rounded-full border border-line bg-bg px-4 font-semibold"
                onClick={() => setPreviewPage((page) => Math.min(source.pages - 1, page + 1))}
              >
                Next
              </button>
            </>
          ) : null}
          <input
            ref={fileRef}
            className="sr-only"
            type="file"
            accept="application/pdf,image/*,text/plain,.pdf,.txt"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) void onFile(file);
              event.target.value = "";
            }}
          />
        </div>
      </section>

      <section className="mt-4 grid gap-4 rounded-card border border-line bg-paper p-4">
        <label className="grid gap-1 text-sm font-semibold">
          Paper
          <select
            className="h-12 rounded-xl border border-line bg-bg px-3 text-base font-normal"
            value={paper.id}
            onChange={(event) => {
              const next = PAPERS.find((item) => item.id === event.target.value);
              if (next) setPaper(next);
            }}
          >
            {PAPERS.map((item) => (
              <option key={item.id} value={item.id}>
                {item.label}
              </option>
            ))}
          </select>
        </label>
        <label className="grid gap-1 text-sm font-semibold">
          Toner {density} of 5
          <input
            className="accent-ready"
            type="range"
            min={1}
            max={5}
            value={density}
            onChange={(event) => setDensity(Number(event.target.value))}
          />
        </label>
        <div className="flex items-center justify-between gap-3">
          <span className="font-semibold">Copies</span>
          <div className="flex items-center gap-2">
            <button type="button" className="h-11 w-11 rounded-full border border-line text-lg" onClick={() => setCopies((n) => Math.max(1, n - 1))}>
              −
            </button>
            <span className="w-6 text-center text-lg">{copies}</span>
            <button type="button" className="h-11 w-11 rounded-full border border-line text-lg" onClick={() => setCopies((n) => Math.min(20, n + 1))}>
              +
            </button>
          </div>
        </div>
        <label className="flex items-center gap-3 font-semibold">
          <input type="checkbox" className="size-5 accent-ready" checked={toner} onChange={(event) => setToner(event.target.checked)} />
          Save toner
        </label>
      </section>

      <ol className="mt-4 grid gap-2 text-sm text-muted">
        <li>1. Printer on, paper loaded, toner door shut.</li>
        <li>2. OTG adapter in the phone. Square USB plug in the M1005.</li>
        <li>3. Connect, then print. Chrome on the phone can do this from this page.</li>
      </ol>

      <p className="mt-4 text-sm leading-relaxed text-muted" role="status">
        {status}
        {usbAvailable() ? "" : " USB from a computer browser will not see the printer. Use the phone."}
      </p>

      <div className="fixed inset-x-0 bottom-0 border-t border-line bg-bg px-4 py-3">
        <div className="mx-auto flex max-w-3xl gap-2">
          <button
            type="button"
            className="inline-flex h-12 flex-1 items-center justify-center gap-2 rounded-full border border-line bg-paper font-semibold"
            onClick={() => void onConnect()}
            disabled={busy}
          >
            <Usb className="size-4" aria-hidden="true" />
            {connected ? "Connected" : "Connect"}
          </button>
          <button
            type="button"
            className="inline-flex h-12 flex-[1.4] items-center justify-center gap-2 rounded-full bg-ready font-semibold text-ready-ink disabled:opacity-60"
            onClick={() => void onPrint()}
            disabled={busy}
          >
            <Printer className="size-4" aria-hidden="true" />
            {busy ? "Sending…" : "Print"}
          </button>
        </div>
      </div>
    </main>
  );
}
