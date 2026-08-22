package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
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
    public void rewritesProfile81CodecAndCsdTogether() {
        Format output = DolbyVisionP81ExtractorsFactory.asProfile81(
                formatWithInitializationData("dvhe.07.06", List.of(new byte[]{1})));

        assertEquals("dvhe.08.06", output.codecs);
        assertEquals(3, output.initializationData.size());
        byte[] csd = output.initializationData.get(2);
        assertEquals(24, csd.length);
        assertEquals(8, (csd[2] & 0xFF) >> 1);
        assertEquals(6, ((csd[2] & 0x01) << 5) | ((csd[3] & 0xF8) >> 3));
        assertEquals(1, (csd[3] >> 2) & 0x01);
        assertEquals(0, (csd[3] >> 1) & 0x01);
        assertEquals(1, csd[3] & 0x01);
        assertEquals(1, (csd[4] >> 4) & 0x0F);
    }

    @Test
    public void preservesNonDolbyVisionCsdAtIndexTwo() {
        byte[] otherCsd = {9, 8, 7};
        List<byte[]> rewritten = DolbyVisionP81ExtractorsFactory.rewriteDolbyVisionCsd(
                Arrays.asList(new byte[]{1}, new byte[]{2}, otherCsd),
                new byte[]{1, 0, 16, 52, 16});

        assertEquals(4, rewritten.size());
        assertTrue(rewritten.get(2) != otherCsd);
        assertArrayEquals(otherCsd, rewritten.get(3));
    }

    @Test
    public void replacesExistingDolbyVisionCsdAndPadsMissingEntries() {
        byte[] oldCsd = {1, 0, 14, 52, 0};
        byte[] newCsd = {1, 0, 16, 52, 16};
        List<byte[]> replaced = DolbyVisionP81ExtractorsFactory.rewriteDolbyVisionCsd(
                List.of(new byte[]{1}, new byte[]{2}, oldCsd), newCsd);
        List<byte[]> padded = DolbyVisionP81ExtractorsFactory.rewriteDolbyVisionCsd(
                null, newCsd);

        assertEquals(3, replaced.size());
        assertArrayEquals(newCsd, replaced.get(2));
        assertEquals(3, padded.size());
        assertEquals(0, padded.get(0).length);
        assertEquals(0, padded.get(1).length);
        assertArrayEquals(newCsd, padded.get(2));
    }

    @Test
    public void doesNotModifyNonProfile7Format() {
        byte[] csd = {1, 2, 3};
        Format source = formatWithInitializationData("dvhe.08.06", List.of(csd));
        Format output = DolbyVisionP81ExtractorsFactory.asProfile81(source);

        assertEquals(source, output);
        assertArrayEquals(csd, output.initializationData.get(0));
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

    @Test
    public void stripsLateHdr10PlusMetadataFromProfile81() {
        byte[] sample = {
                0, 0, 0, 1, 0x26, 0x01, 0x11,
                0, 0, 1, 0x7C, 0x01, 0x22,
                0, 0, 0, 1, 0x4E, 0x01, (byte) 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04,
                0x01,
                0, 0, 0, 1, 0x4E, 0x01, (byte) 0x99
        };

        int length = DolbyVisionP81ExtractorsFactory
                .stripProfile81Nalus(sample, sample.length);

        assertEquals(7 + 6 + 7, length);
        assertEquals(0x7C, sample[7 + 3]);
        assertEquals(0x4E, sample[7 + 6 + 4]);
        assertEquals((byte) 0x99, sample[length - 1]);
    }

    @Test
    public void stripsDolbyVisionRpuAndHdr10PlusForHdr10Fallback() {
        byte[] sample = {
                0, 0, 0, 1, 0x26, 0x01, 0x11,
                0, 0, 1, 0x7C, 0x01, 0x22,
                0, 0, 0, 1, 0x4E, 0x01, (byte) 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04,
                0x01,
                0, 0, 0, 1, 0x4E, 0x01, (byte) 0x99
        };

        int length = DolbyVisionP81ExtractorsFactory
                .stripDolbyVisionNalus(sample, sample.length);

        assertEquals(7 + 7, length);
        assertEquals(0x26, sample[4]);
        assertEquals(0x4E, sample[7 + 4]);
        assertEquals((byte) 0x99, sample[7 + 7 - 1]);
    }

    @Test
    public void keepsOnlyLastProfile81RpuPerAccessUnit() {
        byte[] sample = {
                0, 0, 0, 1, 0x26, 0x01, 0x11,
                0, 0, 1, 0x7C, 0x01, 0x22,
                0, 0, 0, 1, 0x7C, 0x01, 0x33
        };

        int length = DolbyVisionP81ExtractorsFactory
                .stripProfile81Nalus(sample, sample.length);

        assertEquals(7 + 7, length);
        assertEquals(0x26, sample[4]);
        assertEquals(0x7C, sample[7 + 4]);
        assertEquals(0x33, sample[length - 1]);
    }

    @Test
    public void detectsDecodedPictureAccessUnits() {
        byte[] picture = {0, 0, 0, 1, 0x26, 0x01, 0x11};
        byte[] metadataOnly = {0, 0, 0, 1, 0x7C, 0x01, 0x22};

        assertTrue(DolbyVisionP81ExtractorsFactory.containsVclNal(
                picture, picture.length));
        assertFalse(DolbyVisionP81ExtractorsFactory.containsVclNal(
                metadataOnly, metadataOnly.length));
    }

    private static Format format(String codecs) {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs(codecs)
                .build();
    }

    private static Format formatWithInitializationData(
            String codecs, List<byte[]> initializationData) {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs(codecs)
                .setInitializationData(new ArrayList<>(initializationData))
                .build();
    }
}
