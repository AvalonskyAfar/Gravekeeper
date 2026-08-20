# 国内系统实时状态通知调查

调查日期：2026-08-10。

## 当前实现

- `vendor_live_activity_enabled` 仍默认关闭。
- 开启后，Android 16 及以上使用公开的 Promoted Ongoing / Live Update
  通知接口；不满足系统或厂商条件时自动保留为普通持续通知。
- 不使用反射隐藏 API，也不写入未经厂商文档确认的私有 extras。

## 厂商结论

### Android 16 / OPPO

Android 官方实时更新要求持续通知、标准通知样式、
`POST_PROMOTED_NOTIFICATIONS` 和 promoted-ongoing 请求。OEM 仍可附加资格条件。
OPPO ColorOS 16 已公开表示兼容 Android 16 Live Updates，因此本项目采用这条
标准路径作为 OPPO 的优先兼容方案。

- Android 官方：<https://developer.android.com/develop/ui/views/notifications/live-update>
- OPPO 流体云开放平台：<https://open.oppomobile.com/new/developmentDoc/info?id=12965>

### 小米超级岛

小米公开了客户端通知接入：在原生通知上加入 `miui.focus.param` 等参数。
但是正式展示并不是仅靠客户端代码即可获得。官方流程要求开发者认证、在架应用、
App ID、签名指纹、场景预审、正式方案审核、白名单设备联调和上线验证。
本项目目前没有这些授权资料，也没有获批的业务模板，因此本版不发送无法通过鉴权的
伪超级岛参数。完成厂商审批后，可在独立适配器中加入获批模板。

- 开发指南：<https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131>
- 接入流程：<https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132>

### 荣耀灵动胶囊

荣耀用户文档确认灵动胶囊支持进行中任务和第三方服务，但截至调查日，荣耀公开开发者
资料中未找到可由普通本地通知直接启用胶囊的客户端 API。HONOR Push 的消息分类权益
需要平台申请，不能等同于本地通知自动上胶囊。

- 用户能力说明：<https://www.honor.com/cn/support/content/zh-cn15872644/>
- HONOR Push：<https://developer.honor.com/cn/kitdoc?category=%E5%9F%BA%E7%A1%80%E6%9C%8D%E5%8A%A1&kitId=11002&navigation=guides&docId=introduction.md>

### vivo 原子岛

vivo 用户文档确认原子岛能力，但公开检索到的第三方接入主要是推送服务公测链路；
截至调查日，没有找到无需 vivo 平台凭据和审核、仅靠普通本地通知即可稳定上岛的官方
客户端接口。因此当前只保留标准持续通知，不加入来源不明的私有参数。

- vivo 原子岛说明：<https://www.vivo.com.cn/service/questions/all?categoryId=170&questionId=1751>
- vivo 开放平台文档中心：<https://developers.vivo.com/doc/>

## 后续接入条件

厂商专有适配只应在拿到应用标识、签名授权、获批业务场景、模板版本和真实设备后加入，
并继续保持可配置、默认关闭和普通通知降级路径。
