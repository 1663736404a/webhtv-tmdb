package com.github.catvod.net;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KuaishouMediaFallbackInterceptorTest {

    @Test
    public void onlyKuaishouPngSegmentsAreEligible() {
        assertTrue(KuaishouMediaFallbackInterceptor.isEligible(get(
                "https://static.yximgs.com/udata/pkg/66f23107afd545efbfe0112c86d8c72e.png")));
        assertTrue(KuaishouMediaFallbackInterceptor.isEligible(get(
                "https://p1.a.kwimgs.com/udata/pkg/66f23107afd545efbfe0112c86d8c72e.png")));
        assertFalse(KuaishouMediaFallbackInterceptor.isEligible(get(
                "https://static.yximgs.com/udata/pkg/segment.png")));
        assertFalse(KuaishouMediaFallbackInterceptor.isEligible(get(
                "https://static.yximgs.com/udata/pkg/66f23107afd545efbfe0112c86d8c72e.ts")));
        assertFalse(KuaishouMediaFallbackInterceptor.isEligible(get(
                "https://static.yximgs.com/video/66f23107afd545efbfe0112c86d8c72e.png")));
        assertFalse(KuaishouMediaFallbackInterceptor.isEligible(get(
                "https://example.com/udata/pkg/66f23107afd545efbfe0112c86d8c72e.png")));
        assertFalse(KuaishouMediaFallbackInterceptor.isEligible(new Request.Builder()
                .url(HttpUrl.get("https://static.yximgs.com/udata/pkg/66f23107afd545efbfe0112c86d8c72e.png"))
                .post(RequestBody.create(new byte[0]))
                .build()));
    }

    private static Request get(String url) {
        return new Request.Builder().url(HttpUrl.get(url)).build();
    }
}
