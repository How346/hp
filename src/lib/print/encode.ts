import { previewPathPrefix } from "@/lib/preview-path-prefix";

type EncoderExports = {
  memory: WebAssembly.Memory;
  malloc: (size: number) => number;
  free: (ptr: number) => void;
  noko_encode_page: (
    bits: number,
    width: number,
    height: number,
    stride: number,
    resX: number,
    resY: number,
    paper: number,
    density: number,
    econo: number,
    copies: number,
    outPtr: number,
    outLen: number,
  ) => number;
};

function wasmUrl() {
  const path = "/noko_xqx.wasm";
  if (typeof window === "undefined") return path;
  const prefix = previewPathPrefix(window.location.hostname, window.location.pathname);
  return prefix ? `${prefix}${path}` : path;
}

function wasi(getMemory: () => WebAssembly.Memory) {
  const view = () => new DataView(getMemory().buffer);
  return {
    wasi_snapshot_preview1: {
      args_sizes_get(argcPtr: number, argvBufPtr: number) {
        view().setUint32(argcPtr, 0, true);
        view().setUint32(argvBufPtr, 0, true);
        return 0;
      },
      args_get() {
        return 0;
      },
      clock_time_get(_clock: number, _precision: bigint, out: number) {
        view().setBigUint64(out, BigInt(Date.now()) * 1_000_000n, true);
        return 0;
      },
      fd_write(fd: number, iovs: number, count: number, writtenPtr: number) {
        const memory = getMemory();
        const bytes = new Uint8Array(memory.buffer);
        const data = new DataView(memory.buffer);
        let written = 0;
        for (let i = 0; i < count; i++) {
          const base = iovs + i * 8;
          const ptr = data.getUint32(base, true);
          const len = data.getUint32(base + 4, true);
          written += len;
          if (fd === 1 || fd === 2) {
            const text = new TextDecoder().decode(bytes.subarray(ptr, ptr + len));
            if (text.trim()) console.warn(text);
          }
        }
        data.setUint32(writtenPtr, written, true);
        return 0;
      },
      fd_seek() {
        return 0;
      },
      fd_close() {
        return 0;
      },
      proc_exit(code: number) {
        throw new Error(`Printer encoder stopped (${code})`);
      },
    },
  };
}

let loading: Promise<EncoderExports> | null = null;

async function loadEncoder(): Promise<EncoderExports> {
  const response = await fetch(wasmUrl());
  if (!response.ok) throw new Error("The printer encoder did not load");
  const bytes = await response.arrayBuffer();
  let memory: WebAssembly.Memory | null = null;
  const { instance } = await WebAssembly.instantiate(bytes, wasi(() => {
    if (!memory) throw new Error("Encoder memory is not ready");
    return memory;
  }));
  const exports = instance.exports as EncoderExports;
  memory = exports.memory;
  return exports;
}

function encoder() {
  if (!loading) loading = loadEncoder();
  return loading;
}

export async function encodePage(options: {
  bits: Uint8Array;
  width: number;
  height: number;
  stride: number;
  paper: number;
  density: number;
  econo: boolean;
  copies: number;
}) {
  const enc = await encoder();
  const inPtr = enc.malloc(options.bits.length);
  const outPtrSlot = enc.malloc(4);
  const outLenSlot = enc.malloc(4);
  if (!inPtr || !outPtrSlot || !outLenSlot) {
    throw new Error("Not enough memory to build this page");
  }
  new Uint8Array(enc.memory.buffer, inPtr, options.bits.length).set(options.bits);
  const rc = enc.noko_encode_page(
    inPtr,
    options.width,
    options.height,
    options.stride,
    600,
    600,
    options.paper,
    options.density,
    options.econo ? 1 : 0,
    options.copies,
    outPtrSlot,
    outLenSlot,
  );
  const data = new DataView(enc.memory.buffer);
  const outPtr = data.getUint32(outPtrSlot, true);
  const outLen = data.getUint32(outLenSlot, true);
  enc.free(inPtr);
  enc.free(outPtrSlot);
  enc.free(outLenSlot);
  if (rc !== 0 || !outPtr || !outLen) {
    if (outPtr) enc.free(outPtr);
    throw new Error("This page could not be prepared for the LaserJet");
  }
  const copy = new Uint8Array(enc.memory.buffer, outPtr, outLen).slice();
  enc.free(outPtr);
  return copy;
}
