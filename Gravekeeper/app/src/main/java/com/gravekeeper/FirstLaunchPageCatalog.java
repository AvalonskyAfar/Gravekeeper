package com.gravekeeper;

/** Single source of truth for the eight approved first-launch presentation pages. */
final class FirstLaunchPageCatalog {
    static final int INTRO_PAGE_COUNT = 8;
    static final int DISCLOSURE_PAGE_INDEX = INTRO_PAGE_COUNT;
    static final int PAGE_COUNT = INTRO_PAGE_COUNT + 1;

    static final class PageSpec {
        final String title;
        final String subtitle;
        final String body;
        final int illustrationRes;

        PageSpec(String title, String subtitle, String body, int illustrationRes) {
            this.title = title;
            this.subtitle = subtitle;
            this.body = body;
            this.illustrationRes = illustrationRes;
        }

        boolean hasIllustration() { return illustrationRes != 0; }
    }

    private static final PageSpec[] INTRO_PAGES = {
            new PageSpec(
                    "守目人",
                    "端侧 AI 驱动的短视频健康防线",
                    "内置专门训练的本地视觉与文本深度学习模型，无需联网即可对短视频及直播画面进行毫秒级实时感知。精准识别夸大功效、制造健康焦虑等营销套路，并按你的指令自动预警或划走风险内容，用前沿端侧 AI 为你守住眼前清朗。",
                    0),
            new PageSpec(
                    "自由掌控保护强度",
                    null,
                    "支持多档识别强度与个性化策略调节。强度越高，对疑似营销内容的判定越敏感；你可以随心选择仅作提醒或自动划走，把防护的主导权完全握在自己手里。",
                    R.drawable.first_launch_page_02_protection_strength),
            new PageSpec(
                    "全维度的个性化掌控",
                    null,
                    "支持分平台与短视频／直播独立设定“提醒”或“划走”。无论是精细的保护强度、全球购风险增强，还是调节低功耗模式与特定风险权重，丰富的精细化选项，让你定义专属的防护形态。",
                    R.drawable.first_launch_page_03_customization),
            new PageSpec(
                    "智能白名单",
                    null,
                    "支持自定义信任的直播账户。对于确定有质量保障的直播内容，一键加入白名单即可豁免检测并保留观看。",
                    R.drawable.first_launch_page_04_live_whitelist),
            new PageSpec(
                    "极简无痕隐形",
                    null,
                    "支持隐藏桌面图标与最近任务列表。无需保留桌面入口，让 App 仿佛从手机中隐形一般，默默在后台为你守护。",
                    R.drawable.first_launch_page_05_hidden_entry),
            new PageSpec(
                    "场景化智能响应",
                    null,
                    "仅在进入抖音、快手等短视频或直播应用时自动开启检测，其余时间静默休眠，不过多占用系统资源，兼顾高效防护与日常续航。",
                    R.drawable.first_launch_page_06_target_app_rest),
            new PageSpec(
                    "零联网，本地安全",
                    null,
                    "完全离线运行，无需网络权限。所有分析均在手机本地内存中完成，不存储、不上传任何屏幕截图与个人隐私数据，守护体验，更守护你的数据安全。",
                    R.drawable.first_launch_page_07_local_offline),
            new PageSpec(
                    "关于能力的诚恳说明",
                    null,
                    "作为基于端侧 AI 模型的辅助工具，“守目人”仍在不断进化中，当前版本可能存在以下局限：复杂语境下模型可能出现识别误差，造成漏判或误伤正常内容；受短视频或直播平台界面结构动态更新影响，白名单识别偶有失效；开启自动划走功能时，误判可能打断正常浏览。我们会持续迭代模型与算法，改进防护体验。",
                    0)
    };

    private FirstLaunchPageCatalog() {}

    static PageSpec introPage(int index) {
        if (index < 0 || index >= INTRO_PAGE_COUNT) {
            throw new IllegalArgumentException("Not an introduction page: " + index);
        }
        return INTRO_PAGES[index];
    }

    static boolean isDisclosure(int index) {
        return index == DISCLOSURE_PAGE_INDEX;
    }
}
