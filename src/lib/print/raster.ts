import { DPI, paperPixels, rowStride, type Paper } from "@/lib/print/papers";
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

// The M1005 is a 1-bit monochrome engine. Floyd–Steinberg error diffusion
// preserves light-grey fills, shaded table cells and anti-aliased text as a
// printable halftone instead of dropping everything lighter than one cutoff.
type DitherState = { current: Float32Array; next: Float32Array };

function createDitherState(width: number): DitherState {
  return { current: new Float32Array(width + 2), next: new Float32Array(width + 2) };
}

export type PackedPage = {
  bits: Uint8Array;
  width: number;
  height: number;
  stride: number;
};

function packStrip(
  image: ImageData,
  bits: Uint8Array,
  y: number,
  width: number,
  stride: number,
  dither: DitherState,
) {
  const src = image.data;
  for (let row = 0; row < image.height; row++) {
    const base = (y + row) * stride;
    for (let x = 0; x < width; x++) {
      const i = (row * width + x) * 4;
      // Composite transparency onto white before converting to luminance.
      const alpha = src[i + 3] / 255;
      const r = src[i] * alpha + 255 * (1 - alpha);
      const g = src[i + 1] * alpha + 255 * (1 - alpha);
      const b = src[i + 2] * alpha + 255 * (1 - alpha);
      const luma = (r * 54 + g * 183 + b * 19) / 256;
      const adjusted = Math.max(0, Math.min(255, luma + dither.current[x + 1]));
      const black = adjusted < 128;
      if (black) bits[base + (x >> 3)] |= 0x80 >> (x & 7);
      const error = adjusted - (black ? 0 : 255);
      // Floyd–Steinberg: distribute tonal error to neighbouring pixels.
      dither.current[x + 2] += error * (7 / 16);
      dither.next[x] += error * (3 / 16);
      dither.next[x + 1] += error * (5 / 16);
      dither.next[x + 2] += error * (1 / 16);
    }
    dither.current.set(dither.next);
    dither.next.fill(0);
  }
}

async function yieldFrame() {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

export async function rasterizeDraw(
  width: number,
  height: number,
  draw: (ctx: CanvasRenderingContext2D, width: number, height: number) => void,
): Promise<PackedPage> {
  const stride = rowStride(width);
  const bits = new Uint8Array(stride * height);
  const dither = createDitherState(width);
  const stripH = 80;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = stripH;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) throw new Error("This browser cannot draw the page");
  for (let y = 0; y < height; y += stripH) {
    const rows = Math.min(stripH, height - y);
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, width, stripH);
    ctx.setTransform(1, 0, 0, 1, 0, -y);
    draw(ctx, width, height);
    packStrip(ctx.getImageData(0, 0, width, rows), bits, y, width, stride, dither);
    if (y % (stripH * 6) === 0) await yieldFrame();
  }
  return { bits, width, height, stride };
}

export function drawTestPage(ctx: CanvasRenderingContext2D, width: number, height: number) {
  const margin = width * 0.08;
  ctx.fillStyle = "#000000";
  ctx.strokeStyle = "#000000";
  ctx.lineWidth = Math.max(2, width * 0.0035);
  ctx.strokeRect(margin, margin, width - margin * 2, height - margin * 2);
  ctx.font = `650 ${Math.round(width * 0.075)}px Fraunces, Georgia, serif`;
  ctx.fillText("Noko Print", margin * 1.5, height * 0.22);
  ctx.font = `600 ${Math.round(width * 0.034)}px "Source Sans 3", sans-serif`;
  const x = margin * 1.5;
  ctx.fillText("HP LaserJet M1005 MFP", x, height * 0.3);
  ctx.fillText("Machine KJ73EDG", x, height * 0.35);
  ctx.fillText("600 dpi · plain paper", x, height * 0.4);
  ctx.beginPath();
  ctx.moveTo(x, height * 0.45);
  ctx.lineTo(width - margin * 1.5, height * 0.45);
  ctx.stroke();
  ctx.font = `400 ${Math.round(width * 0.032)}px "Source Sans 3", sans-serif`;
  ctx.fillText("If you can read this, the phone", x, height * 0.52);
  ctx.fillText("is printing through the USB cable.", x, height * 0.57);
  const box = width * 0.08;
  const y = height * 0.68;
  for (let i = 0; i < 8; i++) {
    if ((i & 1) === 0) ctx.fillRect(x + i * box, y, box, box);
    else ctx.strokeRect(x + i * box + 1, y + 1, box - 2, box - 2);
  }
}

export function drawTextPage(ctx: CanvasRenderingContext2D, text: string, width: number, height: number) {
  const margin = width * 0.07;
  const size = Math.max(22, width / 42);
  ctx.fillStyle = "#000000";
  ctx.font = `400 ${size}px "Source Sans 3", sans-serif`;
  const maxW = width - margin * 2;
  const lines: string[] = [];
  for (const paragraph of text.slice(0, 20000).split(/\n/)) {
    const words = paragraph.split(/\s+/);
    let line = "";
    for (const word of words) {
      const next = line ? `${line} ${word}` : word;
      if (ctx.measureText(next).width > maxW && line) {
        lines.push(line);
        line = word;
      } else {
        line = next;
      }
    }
    lines.push(line);
  }
  let y = margin + size;
  const leading = size * 1.35;
  for (const line of lines) {
    if (y > height - margin) break;
    ctx.fillText(line, margin, y);
    y += leading;
  }
}

export function drawImagePage(
  ctx: CanvasRenderingContext2D,
  image: CanvasImageSource,
  sourceWidth: number,
  sourceHeight: number,
  width: number,
  height: number,
) {
  const margin = width * 0.05;
  const maxW = width - margin * 2;
  const maxH = height - margin * 2;
  const scale = Math.min(maxW / sourceWidth, maxH / sourceHeight);
  const dw = sourceWidth * scale;
  const dh = sourceHeight * scale;
  ctx.drawImage(image, margin + (maxW - dw) / 2, margin + (maxH - dh) / 2, dw, dh);
}

type PdfDoc = {
  numPages: number;
  getPage: (n: number) => Promise<{
    getViewport: (params: { scale: number; offsetX?: number; offsetY?: number }) => {
      width: number;
      height: number;
    };
    render: (params: {
      canvasContext: CanvasRenderingContext2D;
      viewport: unknown;
      intent?: string;
      background?: string;
    }) => { promise: Promise<void> };
  }>;
  destroy: () => Promise<void> | void;
};

let workerReady = false;

async function openPdf(data: ArrayBuffer): Promise<PdfDoc> {
  const pdfjs = await import("pdfjs-dist");
  if (!workerReady) {
    pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;
    workerReady = true;
  }
  const task = pdfjs.getDocument({ data: new Uint8Array(data) });
  return (await task.promise) as unknown as PdfDoc;
}

export async function pdfPageCount(data: ArrayBuffer) {
  const doc = await openPdf(data.slice(0));
  const count = doc.numPages;
  await doc.destroy();
  return count;
}

export async function rasterizePdfPage(data: ArrayBuffer, pageIndex: number, paper: Paper): Promise<PackedPage> {
  const doc = await openPdf(data.slice(0));
  try {
    const page = await doc.getPage(pageIndex + 1);
    const { width, height } = paperPixels(paper);
    const natural = page.getViewport({ scale: 1 });
    const fit = Math.min(width / natural.width, height / natural.height);
    const dw = natural.width * fit;
    const dh = natural.height * fit;
    const ox = (width - dw) / 2;
    const oy = (height - dh) / 2;
    const stride = rowStride(width);
    const bits = new Uint8Array(stride * height);
    const dither = createDitherState(width);
    const stripH = 80;
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = stripH;
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    if (!ctx) throw new Error("This browser cannot draw the page");
    for (let y = 0; y < height; y += stripH) {
      const rows = Math.min(stripH, height - y);
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, width, stripH);
      const viewport = page.getViewport({ scale: fit, offsetX: ox, offsetY: oy - y });
      await page.render({
        canvasContext: ctx,
        viewport,
        intent: "print",
        background: "#ffffff",
      }).promise;
      packStrip(ctx.getImageData(0, 0, width, rows), bits, y, width, stride, dither);
      if (y % (stripH * 6) === 0) await yieldFrame();
    }
    return { bits, width, height, stride };
  } finally {
    await doc.destroy();
  }
}

export async function paintPdfPreview(
  canvas: HTMLCanvasElement,
  data: ArrayBuffer,
  pageIndex: number,
  paper: Paper,
) {
  const doc = await openPdf(data.slice(0));
  try {
    const page = await doc.getPage(pageIndex + 1);
    const width = 720;
    const height = Math.round(width * (paper.heightMm / paper.widthMm));
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, width, height);
    const natural = page.getViewport({ scale: 1 });
    const fit = Math.min(width / natural.width, height / natural.height);
    const dw = natural.width * fit;
    const dh = natural.height * fit;
    const viewport = page.getViewport({
      scale: fit,
      offsetX: (width - dw) / 2,
      offsetY: (height - dh) / 2,
    });
    await page.render({
      canvasContext: ctx,
      viewport,
      intent: "print",
      background: "#ffffff",
    }).promise;
  } finally {
    await doc.destroy();
  }
}

export async function rasterizePaper(
  paper: Paper,
  draw: (ctx: CanvasRenderingContext2D, width: number, height: number) => void,
) {
  const { width, height } = paperPixels(paper, DPI);
  return rasterizeDraw(width, height, draw);
}

export function paintPreview(
  canvas: HTMLCanvasElement,
  paper: Paper,
  draw: (ctx: CanvasRenderingContext2D, width: number, height: number) => void,
) {
  const target = 720;
  const ratio = paper.heightMm / paper.widthMm;
  canvas.width = target;
  canvas.height = Math.round(target * ratio);
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  draw(ctx, canvas.width, canvas.height);
}
