export type Paper = {
  id: string;
  label: string;
  code: number;
  widthMm: number;
  heightMm: number;
};

export const DPI = 600;

export const PAPERS: Paper[] = [
  { id: "a4", label: "A4", code: 9, widthMm: 210, heightMm: 297 },
  { id: "letter", label: "Letter", code: 1, widthMm: 215.9, heightMm: 279.4 },
  { id: "legal", label: "Legal", code: 5, widthMm: 215.9, heightMm: 355.6 },
  { id: "a5", label: "A5", code: 11, widthMm: 148, heightMm: 210 },
];

export function paperPixels(paper: Paper, dpi = DPI) {
  return {
    width: Math.round((paper.widthMm / 25.4) * dpi),
    height: Math.round((paper.heightMm / 25.4) * dpi),
  };
}

export function rowStride(width: number) {
  const bpl = (width + 7) >> 3;
  return (bpl + 15) & ~15;
}
