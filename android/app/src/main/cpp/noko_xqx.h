#ifndef NOKO_XQX_H
#define NOKO_XQX_H

#include <stddef.h>
#include <stdint.h>

/*
 * Encode one monochrome page as an HP XQX job for the LaserJet M1005 MFP
 * (and the other foo2xqx printers). Bits are MSB-first, 1 = toner (black).
 *
 * stride 0 means a tight row of (width + 7) / 8 bytes.
 * paper uses Windows DEVMODE codes: 1 letter, 5 legal, 9 A4, 11 A5.
 * density is 1..5. econo 0/1 is PJL ECONOMODE. copies is at least 1.
 *
 * On success returns 0 and a malloc'd buffer in *out_ptr / *out_len.
 * Caller frees *out_ptr.
 */
int noko_encode_page(const uint8_t *bits, int width, int height, int stride,
		     int res_x, int res_y, int paper, int density, int econo,
		     int copies, uint8_t **out_ptr, uint32_t *out_len);

#endif
