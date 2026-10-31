/*
 * Noko Print — XQX job writer for the HP LaserJet M1005 MFP.
 *
 * The JBIG compression itself is Markus Kuhn's jbigkit (GPL-2+), included
 * beside this file. The record layout matches the stream produced by Rick
 * Richardson's foo2xqx, which is what actually drives this printer.
 */

#define _POSIX_C_SOURCE 200112L

#include "noko_xqx.h"


#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "jbig.h"

#define XQX_START_DOC 1u
#define XQX_END_DOC 2u
#define XQX_START_PAGE 3u
#define XQX_END_PAGE 4u
#define XQX_START_PLANE 5u
#define XQX_END_PLANE 6u
#define XQX_JBIG 7u

#define XQXI_DMDUPLEX 0x10000002u
#define XQXI_DMDEFAULTSOURCE 0x20000006u
#define XQXI_DMMEDIATYPE 0x20000000u
#define XQXI_RESOLUTION_X 0x20000008u
#define XQXI_RESOLUTION_Y 0x20000009u
#define XQXI_RASTER_X 0x2000000du
#define XQXI_RASTER_Y 0x2000000eu
#define XQXI_VIDEO_BPP 0x2000000au
#define XQXI_VIDEO_X 0x2000000fu
#define XQXI_VIDEO_Y 0x20000010u
#define XQXI_ECONOMODE 0x20000011u
#define XQXI_DMPAPER 0x20000001u
#define XQXI_BIH 0x40000002u
#define XQXI_END 0x80000001u

typedef struct {
	uint8_t *data;
	size_t len;
	size_t cap;
	int failed;
} Buf;

typedef struct Bie {
	uint8_t *data;
	size_t len;
	struct Bie *next;
} Bie;

static uint32_t be32(uint32_t v)
{
	return (v >> 24) | ((v >> 8) & 0xff00u) | ((v << 8) & 0xff0000u) | (v << 24);
}

static void buf_need(Buf *b, size_t extra)
{
	size_t need;
	uint8_t *next;
	size_t cap;

	if (b->failed)
		return;
	need = b->len + extra;
	if (need < b->len) {
		b->failed = 1;
		return;
	}
	if (need <= b->cap)
		return;
	cap = b->cap ? b->cap : 4096u;
	while (cap < need) {
		if (cap > (size_t)-1 / 2) {
			b->failed = 1;
			return;
		}
		cap *= 2;
	}
	next = (uint8_t *)realloc(b->data, cap);
	if (!next) {
		b->failed = 1;
		return;
	}
	b->data = next;
	b->cap = cap;
}

static void buf_raw(Buf *b, const void *p, size_t n)
{
	buf_need(b, n);
	if (b->failed || n == 0)
		return;
	memcpy(b->data + b->len, p, n);
	b->len += n;
}

static void buf_u32(Buf *b, uint32_t v)
{
	uint32_t be = be32(v);
	buf_raw(b, &be, 4);
}

static void buf_str(Buf *b, const char *s)
{
	buf_raw(b, s, strlen(s));
}

static void buf_printf(Buf *b, const char *fmt, int value)
{
	char tmp[64];
	int n = snprintf(tmp, sizeof(tmp), fmt, value);
	if (n < 0 || (size_t)n >= sizeof(tmp)) {
		b->failed = 1;
		return;
	}
	buf_raw(b, tmp, (size_t)n);
}

static void chunk(Buf *b, uint32_t type, uint32_t items)
{
	buf_u32(b, type);
	buf_u32(b, items);
}

static void item_u32(Buf *b, uint32_t type, uint32_t value)
{
	buf_u32(b, type);
	buf_u32(b, 4);
	buf_u32(b, value);
}

static void free_bie(Bie *chain)
{
	while (chain) {
		Bie *next = chain->next;
		free(chain->data);
		free(chain);
		chain = next;
	}
}

static void output_jbig(unsigned char *start, size_t len, void *cbarg)
{
	Bie **root = (Bie **)cbarg;
	Bie *current;
	int size = 65536;

	if (*root == NULL) {
		*root = (Bie *)calloc(1, sizeof(Bie));
		if (!*root)
			return;
		size = 20;
		if (len != 20)
			return;
	}
	current = *root;
	while (current->next)
		current = current->next;
	while (len > 0) {
		int left;
		int amt;
		if (!current->data) {
			current->data = (uint8_t *)malloc((size_t)size);
			if (!current->data)
				return;
		}
		left = size - (int)current->len;
		amt = (len > (size_t)left) ? left : (int)len;
		memcpy(current->data + current->len, start, (size_t)amt);
		current->len += (size_t)amt;
		len -= (size_t)amt;
		start += amt;
		if ((int)current->len == size) {
			current->next = (Bie *)calloc(1, sizeof(Bie));
			if (!current->next)
				return;
			current = current->next;
			size = 65536;
		}
	}
}

static int write_plane(Buf *b, Bie *root)
{
	Bie *current;
	int first = 1;
	uint8_t *bih;

	if (!root || !root->next || root->len != 20)
		return -1;
	bih = root->data;
	for (current = root->next; current && current->len; current = current->next) {
		chunk(b, XQX_START_PLANE, 4);
		item_u32(b, 0x80000000u, first ? 64u : 48u);
		item_u32(b, 0x40000000u, 0);
		if (first) {
			buf_u32(b, XQXI_BIH);
			buf_u32(b, 20);
			buf_raw(b, bih, 20);
		} else {
			item_u32(b, 0x40000003u, 1);
		}
		item_u32(b, XQXI_END, 0xdeadbeefu);
		chunk(b, XQX_JBIG, (uint32_t)current->len);
		buf_raw(b, current->data, current->len);
		chunk(b, XQX_END_PLANE, 0);
		first = 0;
	}
	return b->failed ? -1 : 0;
}

static void start_doc(Buf *b, int density, int econo)
{
	time_t now = time(NULL);
	struct tm tm_buf;
	struct tm *tmp = gmtime_r(&now, &tm_buf);
	char datetime[16];

	if (!tmp)
		memcpy(datetime, "20260101000000", 15);
	else
		strftime(datetime, sizeof(datetime), "%Y%m%d%H%M%S", tmp);

	buf_str(b, "\033%-12345X@PJL JOB\n");
	buf_str(b, "@PJL SET JAMRECOVERY=OFF\n");
	buf_printf(b, "@PJL SET DENSITY=%d\n", density);
	buf_str(b, econo ? "@PJL SET ECONOMODE=ON\n" : "@PJL SET ECONOMODE=OFF\n");
	buf_str(b, "@PJL SET RET=MEDIUM\n");
	buf_str(b, "@PJL INFO STATUS\n");
	buf_str(b, "@PJL USTATUS DEVICE = ON\n");
	buf_str(b, "@PJL USTATUS JOB = ON\n");
	buf_str(b, "@PJL USTATUS PAGE = ON\n");
	buf_str(b, "@PJL USTATUS TIMED = 30\n");
	buf_str(b, "@PJL SET JOBATTR=\"JobAttr4=");
	buf_str(b, datetime);
	buf_str(b, "\"");
	buf_raw(b, "\0", 1);
	buf_str(b, "\033%-12345X");
	buf_str(b, ",XQX");

	chunk(b, XQX_START_DOC, 7);
	item_u32(b, 0x80000000u, 84);
	item_u32(b, 0x10000005u, 1);
	item_u32(b, 0x10000001u, 0);
	item_u32(b, XQXI_DMDUPLEX, 0);
	item_u32(b, 0x10000000u, 0);
	item_u32(b, 0x10000003u, 1);
	item_u32(b, XQXI_END, 0xdeadbeefu);
}

static void end_doc(Buf *b)
{
	chunk(b, XQX_END_DOC, 0);
	buf_str(b, "\033%-12345X@PJL EOJ\n");
	buf_str(b, "\033%-12345X");
}

static int write_page(Buf *b, Bie *chain, int real_width, int res_x, int res_y,
		      int paper, int econo)
{
	unsigned long w, h;
	uint8_t *bih;

	if (!chain || chain->len != 20 || !chain->data)
		return -1;
	bih = chain->data;
	w = ((unsigned long)bih[4] << 24) | ((unsigned long)bih[5] << 16) |
	    ((unsigned long)bih[6] << 8) | (unsigned long)bih[7];
	h = ((unsigned long)bih[8] << 24) | ((unsigned long)bih[9] << 16) |
	    ((unsigned long)bih[10] << 8) | (unsigned long)bih[11];

	chunk(b, XQX_START_PAGE, 15);
	item_u32(b, 0x80000000u, 180);
	item_u32(b, 0x20000005u, 1);
	item_u32(b, XQXI_DMDEFAULTSOURCE, 7);
	item_u32(b, XQXI_DMMEDIATYPE, 1);
	item_u32(b, 0x20000007u, 1);
	item_u32(b, XQXI_RESOLUTION_X, (uint32_t)res_x);
	item_u32(b, XQXI_RESOLUTION_Y, (uint32_t)res_y);
	item_u32(b, XQXI_RASTER_X, (uint32_t)w);
	item_u32(b, XQXI_RASTER_Y, (uint32_t)h);
	item_u32(b, XQXI_VIDEO_BPP, 1);
	item_u32(b, XQXI_VIDEO_X, (uint32_t)real_width);
	item_u32(b, XQXI_VIDEO_Y, (uint32_t)h);
	item_u32(b, XQXI_ECONOMODE, econo ? 1u : 0u);
	item_u32(b, XQXI_DMPAPER, (uint32_t)paper);
	item_u32(b, XQXI_END, 0xdeadbeefu);

	if (write_plane(b, chain) != 0)
		return -1;
	chunk(b, XQX_END_PAGE, 0);
	return b->failed ? -1 : 0;
}

int noko_encode_page(const uint8_t *bits, int width, int height, int stride,
		     int res_x, int res_y, int paper, int density, int econo,
		     int copies, uint8_t **out_ptr, uint32_t *out_len)
{
	int bpl;
	int bpl16;
	int jbig_w;
	int y;
	int copy;
	uint8_t *plane = NULL;
	unsigned char *bitmaps[1];
	struct jbg_enc_state se;
	Bie *chain = NULL;
	Buf page;
	Buf job;
	const long order = JBG_ILEAVE | JBG_SMID;
	const long options = JBG_DELAY_AT | JBG_LRLTWO | JBG_TPDON | JBG_TPBON | JBG_DPON;

	if (out_ptr)
		*out_ptr = NULL;
	if (out_len)
		*out_len = 0;
	if (!bits || !out_ptr || !out_len || width < 16 || height < 16 || width > 20000 ||
	    height > 30000)
		return 1;
	if (copies < 1)
		copies = 1;
	if (copies > 99)
		copies = 99;
	if (density < 1)
		density = 1;
	if (density > 5)
		density = 5;
	if (res_x < 75)
		res_x = 600;
	if (res_y < 75)
		res_y = 600;
	if (paper <= 0)
		paper = 9;

	bpl = (width + 7) / 8;
	if (stride <= 0)
		stride = bpl;
	if (stride < bpl)
		return 2;
	bpl16 = (bpl + 15) & ~15;
	jbig_w = bpl16 * 8;

	plane = (uint8_t *)calloc((size_t)bpl16, (size_t)height);
	if (!plane)
		return 3;
	for (y = 0; y < height; y++)
		memcpy(plane + (size_t)y * (size_t)bpl16, bits + (size_t)y * (size_t)stride,
		       (size_t)bpl);

	memset(&page, 0, sizeof(page));
	*bitmaps = plane;
	jbg_enc_init(&se, (unsigned long)jbig_w, (unsigned long)height, 1, bitmaps, output_jbig,
		     &chain);
	jbg_enc_options(&se, (int)order, (int)options, 128, 16, 0);
	jbg_enc_out(&se);
	jbg_enc_free(&se);
	free(plane);
	plane = NULL;

	if (!chain || chain->len != 20) {
		free_bie(chain);
		return 4;
	}
	if (write_page(&page, chain, width, res_x, res_y, paper, econo ? 1 : 0) != 0) {
		free(page.data);
		free_bie(chain);
		return 5;
	}
	free_bie(chain);

	memset(&job, 0, sizeof(job));
	start_doc(&job, density, econo ? 1 : 0);
	for (copy = 0; copy < copies; copy++)
		buf_raw(&job, page.data, page.len);
	free(page.data);
	end_doc(&job);
	if (job.failed || job.len == 0 || job.len > 0xffffffffu) {
		free(job.data);
		return 6;
	}
	*out_ptr = job.data;
	*out_len = (uint32_t)job.len;
	return 0;
}

#ifdef NOKO_TEST
int main(void)
{
	enum { W = 128, H = 64 };
	uint8_t bits[(W / 8) * H];
	uint8_t *out = NULL;
	uint32_t len = 0;
	int rc;
	int x, y;
	FILE *fp;

	memset(bits, 0, sizeof(bits));
	for (y = 16; y < 48; y++) {
		for (x = 16; x < 112; x++)
			bits[y * (W / 8) + (x >> 3)] |= (uint8_t)(0x80u >> (x & 7));
	}
	rc = noko_encode_page(bits, W, H, 0, 600, 600, 9, 3, 0, 1, &out, &len);
	if (rc != 0) {
		fprintf(stderr, "encode failed %d\n", rc);
		return 1;
	}
	fp = fopen("noko-test.xqx", "wb");
	if (!fp)
		return 1;
	if (fwrite(out, 1, len, fp) != len)
		return 1;
	fclose(fp);
	fprintf(stderr, "wrote %u bytes\n", len);
	free(out);
	return 0;
}
#endif
