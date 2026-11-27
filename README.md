# SFA-1.14.5-cn

sing-box for Android 的定制构建，内置中国 IP 段规则集，并修复了几个在 MIUI / Android 14 上导致「国内站点打不开」的问题。

APK 由 GitHub Actions 构建，产物在 [Releases](../../releases)。

## 和官方版的区别

### 1. 内置 China IP 规则集

`assets/geoip-cn.srs`（24 KB，6751 条 IPv4 CIDR）随 APK 打包。首次启动解压到 app 私有目录，profile 里的 inline `geoip-cn` 会被自动改写成指向这个文件，配置体积从 188 KB 降到 69 KB。

- 手动更新：设置 → China IP rule-set → Update now
- 自动更新：可选 24h / 72h / 168h，默认 72h
- 下载失败绝不删除现有文件；校验 SRS 魔数 `53 52 53`，HTML 错误页会被拒绝
- 「Reset to bundled」可恢复内置版本

规则集来源：[PHCSJC/sing-geoip-cn](https://github.com/PHCSJC/sing-geoip-cn)，聚合 alecthw/mmdb_china_ip_list 使用的同 5 个社区源，输出 sing-box 原生 `.srs`，仅 IPv4。已剔除 clang.cn 独有段（361 段 / 315612 地址）——该源贡献了全部 23 个误判 IP，其余 4 源零误报。

### 2. 修复 `sniff` 让 TCP 全部卡死

profile 里的 `{"action":"sniff"}` 在 TCP 上会走 `sniff.PeekStream`，它给连接设 `SetReadDeadline` 后阻塞读取。在某些设备的 tun TCP 上这个 deadline 不被遵守，读操作永不返回，规则链走不到下一条规则。

表现：DNS 正常、ICMP 正常，但所有 TCP 连接停在 `pre-match[0] => sniff`，`router: match` 计数为 0，页面打不开。

加 `timeout` 无效（被忽略的正是 deadline 本身）。实测对照：

| 配置 | sniff 次数 | match 次数 | 结果 |
|---|---|---|---|
| `{"action":"sniff"}` | 430 | **0** | 全网不通 |
| `{"action":"sniff","timeout":"300ms"}` | 496 | **0** | 全网不通 |
| `{"port":53,"action":"sniff"}` | 0（DNS 走 packet 路径） | **50** | 正常 |

修法：把 `sniff` 限定到 53 端口。`hijack-dns` 需要的嗅探走 UDP/packet 路径（`PeekPacket`，不碰 `PeekStream`），TCP 完全不受影响。

### 3. 修复 app 未排除出 VPN 导致的回环

官方 SFA 只在「分应用代理」模式下设置 `exclude_package`，默认配置下该列表为空，于是 sing-box 进程自身也在 VPN 内：

```
ip rule: 13000: from all fwmark 0x0/0x20000 iif lo uidrange 0-99999 lookup tun0
ip route get 123.129.244.14 uid 10562  →  dev tun0
```

`direct` 出站 dial 国内 IP 时，包被导回 tun0，核心收到自己发的连接，再次路由，形成回环。代理可达时回环最终经代理出去（只显得慢），代理一挂就无处可去，国内站点随之失效。

修法：无论是否开启分应用代理，都把 app 自身加入 `exclude_package`，走 `VpnService.Builder.addDisallowedApplication()`。验证：

```
VPNService: addDisallowedApplication: io.nekohasekai.sfa
ip route get ... uid 10562  →  via 10.63.200.117 dev ccmni1   ← 走物理网卡
ip rule: 13000: uidrange 0-10561 / 10563-20561 ...            ← 10562 被挖掉
```

### 4. 修复 `stack: system` 静默卡死

`stack: system` 用内核 TCP/IP 栈驱动 tun，sing-box 为 `direct` 出站写的包仍可能被同一张 tun 设备重新捕获。修完第 3 条后依然复现：`route(direct)` 判定正确，但没有出站连接、没有错误、没有关闭事件。

同 profile 对比：

| stack | 入站连接 | 出站连接 | 结果 |
|---|---|---|---|
| `system` | 89 次匹配 | **0** | 卡死 |
| `gvisor` | 22 | **22** | 正常 |

修法：加载时把 `stack: system` 改写成 `gvisor`（用户态 TCP/IP，出站是普通主机 socket，不会被 tun 重新捕获）。其他显式 stack 保留不动。磁盘上的 profile 不受影响。

### 5. 修复运行中切换配置导致断网

`BoxService.serviceReload0()` 重载前未关闭 `fileDescriptor`，泄漏 `/dev/tun` 文件描述符。切配置时旧 tun 未释放，新 tun 建立后路由混乱。

修法：重载前显式 `close()`，并让 `DashboardViewModel.selectProfile()` 强制完整重启服务。

### 6. 导入文件选择器适配 MIUI

`ActivityResultContracts.GetContent()` 换为 `OpenDocument()`，MIME 保持通配符。实测这台 ROM 的 MediaStore 把部分 `.json` 索引成 `text/plain`，按 `application/json` 过滤会让用户的 profile 全部不可见：

| MIME 过滤 | `.json` 是否可见 |
|---|---|
| `application/json` | 空 |
| `application/octet-stream` | 空 |
| `text/plain` | 可见 |
| 通配符 | 可见 |

## 构建

仓库不含 SFA 源码，构建时按固定版本拉取上游再打补丁：

| 组件 | 版本 |
|---|---|
| sing-box | `v1.14.5` |
| SFA | `ae93152`（Bump version 1.14.3） |
| Go | 1.26.8 |
| gomobile | `sagernet/gomobile v0.1.12` |
| JDK | 17 |
| ABI | arm64-v8a |

`libbox.aar` 由 `go run ./cmd/internal/build_libbox -target android` 从 sing-box 源码生成，产物注入 `sfa/app/libs/`。

gomobile 必须是 sagernet fork：stock 版本配 Go 1.26 会报 `invalid reference to os.checkPidfdOnce`；构建标签需要 `badlinkname,tfogo_checklinkname0`。

手动触发：Actions → Build SFA-1.14.5-cn → Run workflow，可指定 sing-box tag 和 SFA commit。

## 跟进上游

```bash
# 升级 sing-box 内核
#   Actions 里把 sing_box_ref 改成新 tag 即可

# 升级 SFA 基础版本
git -C sfa checkout <new-commit>
git -C sfa apply --check -v scripts/custom.patch   # 先检查冲突
```

补丁冲突时，`scripts/apply-patches.sh` 会报出具体文件并退出，不会留下半patch 的树。刷新补丁的方法：在打过补丁的树上 `git diff > scripts/custom.patch`。

## 非 root

geoip 相关代码只用 `Application.application.filesDir`（app 私有目录）和 `assets.open()`，更新走 `HttpURLConnection`，`exclude_package` 走标准 `addDisallowedApplication()`。无任何 `exec` / `Runtime` / `/system/` 调用。

## 签名

debug 签名。与官方版签名不同，装过官方版需先卸载，卸载会丢数据——先备份 `/data/data/io.nekohasekai.sfa`。

## 许可

跟随上游 GPL-3.0。

