package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DolbyVisionP81ExtractorsFactoryTest {

    @Test
    public void rewritesOnlyProfile7Codec() {
        assertEquals("dvhe.08.06", DolbyVisionP81ExtractorsFactory
                .rewriteProfile81("dvhe.07.06"));
        assertEquals("dvh1.08.09", DolbyVisionP81ExtractorsFactory
                .rewriteProfile81("dvh1.07.09"));
        assertEquals("dvhe.05.06", DolbyVisionP81ExtractorsFactory
                .rewriteProfile81("dvhe.05.06"));
    }

    @Test
    public void recognizesOnlyDolbyVisionProfile7() {
        assertTrue(DolbyVisionP81ExtractorsFactory.isProfile7(format("dvhe.07.06")));
        assertTrue(DolbyVisionP81ExtractorsFactory.isProfile7(format("dvh1.07.06")));
        assertFalse(DolbyVisionP81ExtractorsFactory.isProfile7(format("dvhe.08.06")));
        assertFalse(DolbyVisionP81ExtractorsFactory.isProfile7(
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
                        .setCodecs("dvhe.07.06").build()));
    }

    @Test
    public void stripsEnhancementLayerNalusAndKeepsBaseAndRpu() {
        byte[] sample = {
                0, 0, 0, 1, 0x26, 0x01, 0x11,
                0, 0, 1, 0x7E, 0x01, 0x22,
                0, 0, 0, 1, 0x7C, 0x01, 0x33
        };

        int length = DolbyVisionP81ExtractorsFactory
                .stripEnhancementLayerNalus(sample, sample.length);

        assertEquals(14, length);
        assertEquals(0x26, sample[4]);
        assertEquals(0x7C, sample[11]);
    }

    private static Format format(String codecs) {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs(codecs)
                .build();
    }
}
