package com.fongmi.android.tv.utils;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class EpisodeTitleCompactTest {

    @Test
    public void garoCollectionUsesSemanticLabelsAndRealEpisodeTitles() {
        List<String> names = List.of(
                "[01.2005 牙狼 第一季] [光の影字幕組][牙狼GARO][01][绘本]2880x2160.mp4 [2.65GB]",
                "[01.2005 牙狼 第一季] [光の影字幕組][牙狼GARO][02][阴我]2880x2160.mp4 [7.36GB]",
                "[01.2005 牙狼 第一季] [光の影字幕組][牙狼GARO][04][晚餐]2880x2160.mp4 [2.52GB]",
                "[01.2005 牙狼 第一季] [光の影字幕組][牙狼GARO][17][水槽]2880x2160.mp4 [2.52GB]",
                "[02.2006 牙狼 特别篇 白夜的魔兽] [光の影字幕組][牙狼 白夜的魔兽][BDRip][720p][x264·AAC][Hi10p].mkv [4.34GB]",
                "[03.2010 牙狼 剧场版 红色镇魂曲] [DBD-Raws][牙狼：红色镇魂曲][1080P][BDRip][HEVC-10bit][FLAC].mkv [4.14GB]",
                "[03.2010 牙狼 剧场版 红色镇魂曲] 牙狼：红色镇魂曲.Garo.The.Movie.Red.Requiem.2010.BD1080P.X264.AAC.Japanese.CHS-JPN.CHD.mp4 [3.21GB]",
                "[04.2011 牙狼 OV 呀（KIBA）暗黑骑士铠传] [光の影字幕组][GARO][Kiba~暗黑骑士铠传][BDrip][1920x1080][X264xAC3][MKV].mkv [3.29GB]",
                "[05.2011 牙狼 第二季 魔戒闪骑] 01[光の影字幕组][牙狼GARO][魔戒闪骑][01][火花][繁日双语][X264][1920x1080][BDrip][MP4][2ACDEA60].mp4 [1.72GB]",
                "[05.2011 牙狼 第二季 魔戒闪骑] 02[光の影字幕组][牙狼GARO][魔戒闪骑][02][街灯][繁日双语][X264][1920x1080][BDrip][MP4][55BFC4B9].mp4 [1.70GB]",
                "[05.2011 牙狼 第二季 魔戒闪骑] 04[光の影字幕组][牙狼GARO][魔戒闪骑][04][王牌][繁日双语][X264][1920x1080][BDrip][MP4][4CE9F2E6].mp4 [1.72GB]"
        );

        assertEquals(List.of(
                "第一季 01 绘本 [2.65GB]",
                "第一季 02 阴我 [7.36GB]",
                "第一季 04 晚餐 [2.52GB]",
                "第一季 17 水槽 [2.52GB]",
                "特别篇 白夜的魔兽 [4.34GB]",
                "剧场版 红色镇魂曲 HEVC [4.14GB]",
                "剧场版 红色镇魂曲 AVC [3.21GB]",
                "OV 呀（KIBA）暗黑骑士铠传 [3.29GB]",
                "第二季 01 火花 [1.72GB]",
                "第二季 02 街灯 [1.70GB]",
                "第二季 04 王牌 [1.72GB]"
        ), EpisodeTitleCompact.compact(names));
    }

    @Test
    public void resolutionsDoNotOverrideStandaloneEpisodeNumbers() {
        List<String> names = List.of(
                "Long Show [01] Alpha 2880x2160 WEB-DL.mp4 [1.00GB]",
                "Long Show [02] Beta 1920x1080 BluRay.mkv [2.00GB]",
                "Long Show [03] Gamma 3840x2160 HEVC.mkv [3.00GB]",
                "Long Show [04] Delta 1280x720 AVC.mp4 [4.00GB]"
        );

        assertEquals(List.of(
                "01 [1.00GB]",
                "02 [2.00GB]",
                "03 [3.00GB]",
                "04 [4.00GB]"
        ), EpisodeTitleCompact.compact(names));
    }

    @Test
    public void realNxMEpisodeTokensRemainSupported() {
        List<String> names = List.of(
                "Long Show 1x02 Alpha 1920x1080 WEB-DL.mkv",
                "Long Show 01x003 Beta 3840x2160 BluRay.mkv"
        );

        assertEquals(List.of("1X02", "01X003"), EpisodeTitleCompact.compact(names));
    }

    @Test
    public void existingStrongEpisodeFormatsKeepTheirBehavior() {
        List<String> names = List.of(
                "Long Show S01E02 Alpha 2160p WEB-DL.mkv",
                "Long Show EP03 Beta 1080p BluRay.mkv",
                "Long Show 第04集 Gamma 720p AVC.mp4",
                "Long Show 2026-07-23 Daily Edition.mp4"
        );

        assertEquals(List.of("S01E02", "EP03", "第04集", "2026-07-23"), EpisodeTitleCompact.compact(names));
    }

    @Test
    public void unrecognizedCollectionHeadersFallBackToExistingRules() {
        List<String> names = List.of(
                "[01.2020 Space Documentary] Part Alpha 1080p WEB-DL.mkv",
                "[02.2021 A Movie Archive] Part Beta 720p BluRay.mkv"
        );

        assertEquals(List.of("01", "02"), EpisodeTitleCompact.compact(names));
    }
}
