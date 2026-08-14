package com.fongmi.android.tv.player.engine;

import androidx.media3.common.C;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;

public class MpvAudioCapabilitiesTest {

    @Test
    public void mapsMedia3SurroundEncodingsToMpvSpdifNames() {
        Set<Integer> encodings = Set.of(
                C.ENCODING_AC3,
                C.ENCODING_E_AC3,
                C.ENCODING_DTS,
                C.ENCODING_DTS_HD,
                C.ENCODING_DOLBY_TRUEHD);

        assertEquals("ac3,eac3,dts,dts-hd,truehd",
                MpvAudioCapabilities.getAudioSpdifCodecs(encodings::contains));
    }

    @Test
    public void mapsCompatibleEnhancedEncodingsToTheirMpvFamilies() {
        Set<Integer> encodings = Set.of(C.ENCODING_E_AC3_JOC, C.ENCODING_DTS_HD_MA);

        assertEquals("eac3,dts,dts-hd",
                MpvAudioCapabilities.getAudioSpdifCodecs(encodings::contains));
    }

    @Test
    public void leavesSpdifDisabledWhenMedia3ReportsNoSurroundSupport() {
        assertEquals("", MpvAudioCapabilities.getAudioSpdifCodecs(encoding -> false));
    }
}
