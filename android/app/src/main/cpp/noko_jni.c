#include <jni.h>
#include <stdlib.h>

#include "noko_xqx.h"

JNIEXPORT jbyteArray JNICALL
Java_app_noko_print_XqxEncoder_nativeEncode(JNIEnv *env, jclass clazz, jbyteArray bits, jint width,
					    jint height, jint stride, jint res_x, jint res_y,
					    jint paper, jint density, jint econo, jint copies)
{
	jbyte *in;
	uint8_t *out = NULL;
	uint32_t out_len = 0;
	int rc;
	jbyteArray result;

	(void)clazz;
	if (!bits)
		return NULL;
	in = (*env)->GetByteArrayElements(env, bits, NULL);
	if (!in)
		return NULL;
	rc = noko_encode_page((const uint8_t *)in, width, height, stride, res_x, res_y, paper,
			      density, econo, copies, &out, &out_len);
	(*env)->ReleaseByteArrayElements(env, bits, in, JNI_ABORT);
	if (rc != 0 || !out) {
		jclass ex = (*env)->FindClass(env, "java/io/IOException");
		free(out);
		if (ex)
			(*env)->ThrowNew(env, ex, "Could not build a page for this printer");
		return NULL;
	}
	result = (*env)->NewByteArray(env, (jsize)out_len);
	if (result)
		(*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte *)out);
	free(out);
	return result;
}
