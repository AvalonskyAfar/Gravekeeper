package com.gravekeeper;

/** Single source of truth for tutorial cards, child-page copy and real actions. */
final class TutorialPageCatalog {
    static final int PAGE_COUNT = 3;

    static final class SectionSpec {
        final String heading;
        final String body;

        SectionSpec(String heading, String body) {
            this.heading = heading;
            this.body = body;
        }
    }

    static final class PageSpec {
        final String rootLabel;
        final String childTitle;
        final String subtitle;
        final String lead;
        final String groupTitle;
        final String groupDetail;
        final SectionSpec[] sections;
        final String noteTitle;
        final String noteBody;
        final String closingTitle;
        final String closingBody;
        final String body;
        final int illustrationRes;
        final boolean accessibilityAction;

        PageSpec(String rootLabel, String childTitle, String subtitle, String lead,
                String groupTitle, String groupDetail, SectionSpec[] sections,
                String noteTitle, String noteBody, String closingTitle, String closingBody,
                int illustrationRes, boolean accessibilityAction) {
            this.rootLabel = rootLabel;
            this.childTitle = childTitle;
            this.subtitle = subtitle;
            this.lead = lead;
            this.groupTitle = groupTitle;
            this.groupDetail = groupDetail;
            this.sections = sections;
            this.noteTitle = noteTitle;
            this.noteBody = noteBody;
            this.closingTitle = closingTitle;
            this.closingBody = closingBody;
            this.body = flattenBody(lead, groupDetail, sections, noteBody, closingBody);
            this.illustrationRes = illustrationRes;
            this.accessibilityAction = accessibilityAction;
        }

        private static String flattenBody(String lead, String groupDetail,
                SectionSpec[] sections, String noteBody, String closingBody) {
            StringBuilder result = new StringBuilder(lead);
            if (groupDetail != null && !groupDetail.isBlank()) {
                result.append("\n\n").append(groupDetail);
            }
            for (SectionSpec section : sections) {
                result.append("\n\n").append(section.heading)
                        .append("\n").append(section.body);
            }
            if (noteBody != null && !noteBody.isBlank()) {
                result.append("\n\n").append(noteBody);
            }
            if (closingBody != null && !closingBody.isBlank()) {
                result.append("\n\n").append(closingBody);
            }
            return result.toString();
        }
    }

    private static final PageSpec[] PAGES = {
            new PageSpec(
                    "无障碍权限",
                    "开启“无障碍服务”权限",
                    "",
                    "“守目人”的核心功能依赖无障碍服务。为了实时识别短视频中的营销风险，软件需要通过该权限获取当前屏幕中的必要信息；触发高风险内容时，也需要依赖该权限执行“自动划走”手势。若未开启此权限，软件将无法进行核心识别与防护工作。软件仅在保护功能开启且进入受支持的目标应用时进行本地分析，处理结果不会上传至服务器。",
                    "",
                    "",
                    new SectionSpec[0],
                    null,
                    null,
                    null,
                    null,
                    R.drawable.tutorial_accessibility_permission,
                    true),
            new PageSpec(
                    "隐藏桌面图标",
                    "将 App 从桌面中彻底隐藏",
                    "",
                    "开启“无痕隐形”功能后，“守目人”不仅会从桌面消失，也不会出现在应用列表中。各主流品牌手机开启深度隐藏的具体路径及唤出方式如下：",
                    "",
                    "",
                    new SectionSpec[] {
                            new SectionSpec("小米／REDMI（MIUI／HyperOS）",
                                    "进入【手机管家】或【设置】→ 找到【隐藏应用】（或【隐私与安全】中的应用隐藏）。开启隐藏后，应用图标彻底消失；在桌面双指张开（向外划）并输入密码即可调出隐藏文件夹。"),
                            new SectionSpec("vivo／iQOO（OriginOS）",
                                    "进入【设置】→【安全与隐私／隐私】→【原子隐私系统】（或【隐私与应用加密】→【应用隐藏】）。开启后图标从桌面及搜索结果中清除，可通过双指上滑桌面或验证专属指纹／密码唤出。"),
                            new SectionSpec("荣耀／华为（MagicOS／HarmonyOS）",
                                    "进入【设置】→【隐私】→【隐私空间】（或进入【应用】→【应用锁／应用隐藏】）。创建隐私空间或隐藏应用后开启“隐藏入口”开关，只能通过锁屏界面输入专属隐私指纹／密码直接切换进入。"),
                            new SectionSpec("魅族（Flyme）／联想（ZUI）等",
                                    "进入【设置】→【安全与隐私】→【私密空间／应用隐藏】。开启后图标隐藏，通常需要通过拨号盘输入特定暗号（如 ##密码##）或在桌面特定区域双指划动开启。"),
                            new SectionSpec("其他厂商与原生 Android 系统",
                                    "如设备无上述独立隐形功能，通常可在【设置】→【隐私／安全】或【应用管理】中寻找“隐藏应用”或“隐私空间”选项；若系统仅支持从桌面移除，可结合系统的“应用锁”来保障隐私安全。")
                    },
                    "注：",
                    "OPPO 及三星等部分品牌系统未提供完全隐形的深度隐藏空间，通常仅支持停用或从主屏幕移除图标，可优先使用其“应用加密／私密保险箱”功能进行防护。",
                    null,
                    null,
                    R.drawable.tutorial_hide_launcher_icon,
                    false),
            new PageSpec(
                    "隐藏 App",
                    "任务中心隐藏与自启动保障",
                    "",
                    "“守目人”支持在多任务界面（卡片后台）中隐藏自身窗口，避免锁屏或滑出后台时被他人无意间看到。但需要注意的是，若已为软件开启“自启动”和“后台常驻”权限，它本就会在后台静默运行，是否从任务中心隐藏并不影响其正常的防护功能。",
                    "",
                    "为了确保系统不会在后台误杀服务，导致离开短视频 App 后防护失效，建议为“守目人”开启应用自启动。各主流品牌开启路径如下：",
                    new SectionSpec[] {
                            new SectionSpec("小米／REDMI（MIUI／HyperOS）",
                                    "进入【设置】→【应用设置】→【授权管理】→【自启动管理】，开启“守目人”开关；并在多任务卡片界面长按守目人窗口，点击“锁图标”加锁后台。"),
                            new SectionSpec("华为／荣耀（HarmonyOS／MagicOS）",
                                    "进入【设置】→【应用与服务】→【应用启动管理】，找到“守目人”，将默认的“自动管理”切换为“手动管理”，并勾选“允许自启动”、“允许关联启动”和“允许后台活动”。"),
                            new SectionSpec("vivo／iQOO（OriginOS／Funtouch OS）",
                                    "进入【设置】→【应用与权限】→【权限管理】→【自启动管理】开启开关（或通过【i管家】→【应用管理】→【权限管理】设置）。"),
                            new SectionSpec("OPPO／一加／realme（ColorOS）",
                                    "进入【设置】→【应用管理】→【权限】→【自启动】（或【应用】→【自启动和关联启动】），开启“守目人”的自启动开关。"),
                            new SectionSpec("三星（One UI）",
                                    "进入【设置】→【电池与设备维护】→【电池】→【后台活动限制】（或【内存】），确保“守目人”未被加入“深度休眠应用”列表中，并在【应用】选项中开启“允许后台活动”。")
                    },
                    null,
                    null,
                    "",
                    "开启自启动并加锁后台后，软件即可始终在后台平稳运行，无需频繁手动打开。",
                    R.drawable.tutorial_hide_recent_task,
                    false)
    };

    private TutorialPageCatalog() {}

    static PageSpec page(int index) {
        if (index < 0 || index >= PAGE_COUNT) {
            throw new IllegalArgumentException("Unknown tutorial page: " + index);
        }
        return PAGES[index];
    }

    static PageSpec find(String rootLabel) {
        if (rootLabel == null) return null;
        for (PageSpec page : PAGES) {
            if (rootLabel.equals(page.rootLabel)) return page;
        }
        return null;
    }
}
