# Netty MPTCP 支持(Linux epoll + io_uring)— 设计规格

- **日期**:2026-07-10
- **状态**:Draft(待审阅)
- **阶段**:Phase A — 自底向上 MVP(上边界 = `LinuxSocket` 层)
- **参考**:valkey MPTCP 实现(commit `#1811` Introduce MPTCP、`#2067` Support MPTCP for cli/benchmark)——同一机制(`IPPROTO_MPTCP` 作为 `socket()` 的 protocol 参数)

---

## 1. 背景与目标

MPTCP(Multipath TCP,RFC 8684)把一条 TCP 连接拆成多个 subflow,跨多接口/路径传输,带来带宽聚合、故障转移与更高可靠性。Linux 内核自 **5.6** 起原生支持,启用方式是 `socket(domain, SOCK_STREAM, IPPROTO_MPTCP=262)`。

**目标**:让 Netty 的 Linux native transport(epoll + io_uring)能创建 MPTCP socket,机制与 valkey 完全一致——把 `IPPROTO_MPTCP` 作为 `socket()` 的 protocol 参数传入。

## 2. 范围

### In scope(Phase A)
- native C:把 `protocol` 透传过 `netty_unix_socket.c` 的 socket 创建链
- JNI:`newSocketStreamFd(boolean ipv6, int protocol)`
- unix-common Java:`Socket.newSocketStream0(boolean ipv6, int protocol)`
- epoll Java(被 io_uring 共用):`LinuxSocket.newSocketStream(SocketProtocolFamily, boolean mptcp)`、`LinuxSocket.isMptcpSupported()`、`LinuxSocket.IPPROTO_MPTCP`
- 能力探测 + 单元测试

### Out of scope(留待 Phase B / 其它)
- `EpollServerSocketChannel` / `EpollSocketChannel` / `IoUringServerSocketChannel` / `IoUringSocketChannel` 的 `mptcp` 构造函数
- ChannelOption 形态的 API
- kqueue(macOS/BSD,MPTCP API 不同:`SO_MULTIPATH`,非 `IPPROTO_MPTCP`)
- NIO(JDK `SocketChannel` 不暴露 socket() 的 protocol 参数)

## 3. 关键约束(驱动整个设计)

MPTCP 是 `socket()` 的 **protocol(第 3 参数)**,在 fd 创建瞬间定型,**没有 setsockopt 能事后切换**。因此:
- 必须在 **fd 构造期** 决定,镜像 Netty 现有的 `SocketProtocolFamily`(同样是 socket() 构造期参数)——加构造/工厂参数,**而非** ChannelOption。
- 这与 `UDP_GRO`/`TCP_CORK`(setsockopt,事后可改,故用 ChannelOption)本质不同。

## 4. 架构(epoll + io_uring 共用 fd 创建链)

```
EpollServerSocketChannel / IoUringServerSocketChannel   (Phase B 接)
            │  共用
            ▼
LinuxSocket.newSocketStream(family, mptcp)   ← Phase A 上边界(新 API)
            │
            ▼
Socket.newSocketStream0(ipv6, protocol)      ← 改:加 protocol 参数
            │  (unix-common 共享 Java)
            ▼
native newSocketStreamFd(ipv6, protocol)     ← 改:JNI 签名 (Z)I → (ZI)I
            │
            ▼
_socket(domain, SOCK_STREAM, protocol)       ← 改:去掉硬编码 0
            │
            ▼
socket(domain, SOCK_STREAM|SOCK_NONBLOCK, protocol)   ← IPPROTO_MPTCP 落地点
```

**为何 epoll + io_uring 同时受益**:两个 transport 共用 `LinuxSocket`(`IoUringServerSocketChannel:39` → `LinuxSocket.newSocketStream()`),其底层又共用 unix-common 的 `Socket.newSocketStreamFd` → `netty_unix_socket.c:_socket`。fd 创建是单一共享链。

## 5. 详细设计

### 5.1 native C — `transport-native-unix-common/.../netty_unix_socket.c`
- `_socket(env, clazz, domain, type)` → 增加 `int protocol` 参数;`nonBlockingSocket(domain, type, protocol)`(`:64`)本来就接受 protocol,直接透传。
- `newSocketStreamFd(jboolean ipv6)`(`:752`)→ `newSocketStreamFd(jboolean ipv6, jint protocol)`。
- dgram / domain 变体(`:747/757/765`)**保持传 0**,不受影响。

### 5.2 native C — `*_linuxsocket.c`(epoll + io_uring 各一份)
对齐 Netty 既有"LinuxSocket 双份 native"模式(两文件各有同名 `setTcpCork`/`isUdpGro` 等),在以下两文件各加:
- `transport-native-epoll/.../netty_epoll_linuxsocket.c`
- `transport-native-io_uring/.../netty_io_uring_linuxsocket.c`

新增:
```c
#ifndef IPPROTO_MPTCP
#define IPPROTO_MPTCP 262   /* 兼容老头文件,对齐 UDP_GRO define 写法 (:63) */
#endif

static jint netty_*_linuxsocket_ipprotoMptcp(JNIEnv* env, jclass clazz) {
    return IPPROTO_MPTCP;
}
static jboolean netty_*_linuxsocket_isMptcpSupported0(JNIEnv* env, jclass clazz) {
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_MPTCP);   /* 对齐 isSupportingUdpSegment (:696) */
    if (fd == -1) return JNI_FALSE;                          /* EPROTONOSUPPORT/EINVAL */
    close(fd);
    return JNI_TRUE;
}
```
两文件各自的方法表 + `netty_io_uring_native.c:899` 的 `*_linuxsocket_JNI_OnLoad` 链路保持既有注册机制。

### 5.3 JNI / Java(unix-common)
`transport-native-unix-common/.../Socket.java`:
- native 方法声明(属 native 绑定层,直接改现有):`newSocketStreamFd(boolean ipv6)` → `newSocketStreamFd(boolean ipv6, int protocol)`;方法表(`netty_unix_socket.c:1280` 附近)签名 `(Z)I` → `(ZI)I`。
- `newSocketStream0(boolean ipv6)` **保留不改签名**(内部改为调 `newSocketStreamFd(ipv6, 0)`,行为不变);**新增重载** `newSocketStream0(boolean ipv6, int protocol)` 供 mptcp 路径使用。

### 5.4 Java — `LinuxSocket.java`(epoll 模块,被 io_uring 共用)
`transport-classes-epoll/.../LinuxSocket.java`,新增(native 声明与既有 `isUdpGro`/`setUdpGro` 同区,`:506` 附近):
```java
private static native int ipprotoMptcp0();
private static native boolean isMptcpSupported0();

public static final int IPPROTO_MPTCP = ipprotoMptcp0();          // 常量由 native 返回,不硬编码
private static final boolean IS_MPTCP_SUPPORTED = isMptcpSupported0();
public static boolean isMptcpSupported() { return IS_MPTCP_SUPPORTED; }

public static LinuxSocket newSocketStream(SocketProtocolFamily family, boolean mptcp) {
    if (mptcp && !IS_MPTCP_SUPPORTED) {
        throw new ChannelException("MPTCP is not supported by the kernel");
    }
    int protocol = mptcp ? IPPROTO_MPTCP : 0;
    return new LinuxSocket(newSocketStream0(shouldUseIpv6(family), protocol));
}
```
旧的 `newSocketStream(family)` / `newSocketStream()` 不变(走 protocol=0),**完全向后兼容**。

## 6. 数据流

```
LinuxSocket.newSocketStream(INET6, mptcp=true)
  → IS_MPTCP_SUPPORTED 校验通过
  → protocol = 262 (IPPROTO_MPTCP)
  → Socket.newSocketStream0(ipv6=true, 262)
  → native newSocketStreamFd(true, 262)
  → _socket(AF_INET6, SOCK_STREAM, 262)
  → socket(AF_INET6, SOCK_STREAM|SOCK_NONBLOCK, 262)
  → fd 返回
```
accepted 子连接经 `accept4` 自动继承监听 fd 的 MPTCP,**无需额外处理**(Phase B 接 channel 层后同样适用)。

## 7. 错误处理与回退

- `socket(..., IPPROTO_MPTCP)` 只要头文件有常量就返回 fd 成功;**真正是否多路径由内核 `net.mptcp.enabled` 决定**,对端不支持 MPTCP 时内核**自动回退普通 TCP**(与 valkey 一致)。
- 内核完全未编 MPTCP(老内核):`socket()` 返回 `EPROTONOSUPPORT` → `isMptcpSupported0()` 返回 false。
- `mptcp=true && !isMptcpSupported()` → **显式 throw**(`ChannelException`),不静默降级(决策③)。
- `mptcp=false`:行为与改动前完全一致。

## 8. 决策记录(locked)

| # | 决策 | 结论 |
|---|------|------|
| ① | IPPROTO_MPTCP 常量来源 | `LinuxSocket` 静态 native 返回(两份 C),Java 不硬编码 262 |
| ② | `isMptcpSupported` 位置 | `LinuxSocket` 静态 native(两份 `*_linuxsocket.c`,epoll+io_uring 共用) |
| ③ | `mptcp=true` 但内核不支持 | 显式 throw,不静默回退 |
| ④ | Phase A 上边界 | `LinuxSocket.newSocketStream(family, mptcp)` + 能力探测 + 单测;不含 channel 构造层 |
| ⑤ | native 改动方式 | Java 公共/中间层 API 用**重载**(向后兼容);native 方法声明与 C 实现直接改现有签名(`newSocketStreamFd` `(Z)I`→`(ZI)I`、`_socket` 加 `protocol` 参数) |

## 9. 测试

新增 `transport-native-epoll/src/test/java/io/netty/channel/epoll/EpollMptcpTest.java`(结构对齐 `EpollSocketChannelConfigTest`):
- `LinuxSocket.isMptcpSupported()` 在当前机器返回符合预期;
- `LinuxSocket.newSocketStream(INET/INET6, true)` 能创建 fd、能 bind、回环能 connect;
- `mptcp=true` 且 `isMptcpSupported()==false` 时抛 `ChannelException`(在不支持 MPTCP 的 CI 环境验证此分支;支持的环境用 `Assumptions.assumeTrue` 跳过该用例)。
- 平台不支持时用 `Assumptions.assumeTrue(LinuxSocket.isMptcpSupported())` 跳过,对齐 valkey #3089 范式。

> 构建机:内核 5.14、`CONFIG_MPTCP=y`,但 `net.mptcp.enabled=0`。fd 创建/bind/connect 测试不受影响;验证"真多路径"需测试前 `sysctl net.mptcp.enabled=1`(root),用 `ss -Mt` 或 `/proc/net/mptcp` 确认 subflow。

## 10. 内核/构建前置

- 内核 ≥ 5.6;IPv6 场景需 `CONFIG_MPTCP_IPV6=y`(本机已满足)。
- 运行时 `net.mptcp.enabled=1` 才真正多路径(本机默认 0,需手动开)。
- 编译:`IPPROTO_MPTCP` 经 `#ifndef ... #define 262` 兜底——旧版 glibc 的 `<netinet/in.h>` 未定义该常量时仍可编译(常量值在内核 uapi 中固定为 262)。

## 11. 改动文件清单

**native C**
- `transport-native-unix-common/src/main/c/netty_unix_socket.c`(`_socket` / `newSocketStreamFd` + 方法表)
- `transport-native-epoll/src/main/c/netty_epoll_linuxsocket.c`(`ipprotoMptcp0` / `isMptcpSupported0` + 常量 define + 方法表)
- `transport-native-io_uring/src/main/c/netty_io_uring_linuxsocket.c`(同上,io_uring 份)

**JNI / Java**
- `transport-native-unix-common/src/main/java/io/netty/channel/unix/Socket.java`(`newSocketStreamFd` / `newSocketStream0`)
- `transport-classes-epoll/src/main/java/io/netty/channel/epoll/LinuxSocket.java`(`IPPROTO_MPTCP` / `isMptcpSupported()` / `newSocketStream(family, mptcp)`)

**测试**
- `transport-native-epoll/src/test/java/io/netty/channel/epoll/EpollMptcpTest.java`(新增)

## 12. 后续(Phase B,本次不做)

- `EpollServerSocketChannel` / `EpollSocketChannel` 加 `mptcp` 构造重载,镜像 `SocketProtocolFamily` 构造;`IoUringServerSocketChannel` / `IoUringSocketChannel` 同步。
- 评估是否补一个只读 `EpollChannelOption.MPTCP` 用于查询(仅 getter)。
- 可选:kqueue/macOS 的 `SO_MULTIPATH` 路线(独立 API,另起设计)。

## 13. 实现修订记录(2026-07-10,实现阶段补充)

实现过程中发现两处与原文档的偏差,记录如下(原文保留以反映设计意图):

### 13.1 架构前提修正:epoll 与 io_uring 是两个独立的 `LinuxSocket` 类
- 原文 §4/§5.4 假设 epoll 与 io_uring "共用一个 `LinuxSocket`"。**实际**:`io.netty.channel.epoll.LinuxSocket` 与 `io.netty.channel.uring.LinuxSocket` 是两个独立类(同名不同包),都继承 `io.netty.channel.unix.Socket`。
- 因此 MPTCP 的 Java API(`IPPROTO_MPTCP` / `isMptcpSupported()` / `newSocketStream(family, mptcp)`)需在**两个** `LinuxSocket` 各加一份。
- Task 1 的 protocol 透传(`unix-common` 的 `_socket` / `newSocketStreamFd`)是**真共享**的——epoll 与 io_uring 都受益。
- io_uring 的 native 探测(`netty_io_uring_linuxsocket.c` 的 `isMptcpSupported0`)注册到 `io/netty/channel/uring/LinuxSocket`,必须有对应 Java 声明,否则 `RegisterNatives` 失败 → io_uring native 库整体加载失败(即 C1)。

### 13.2 决策①偏离:常量改为字面量 + holder
- 原决策①:`IPPROTO_MPTCP` 由 native(`ipprotoMptcp0()`)返回,不硬编码。
- 实际:改为字面量 `262`,`isMptcpSupported()` 用 initialization-on-demand holder 延迟 native 调用。
- 原因:**类初始化竞态**。`LinuxSocket` 的 `static final` 字段在 `<clinit>` 求值;而 native 库加载(`JNI_OnLoad` → `RegisterNatives`)过程中 `FindClass` 会触发 `LinuxSocket.<clinit>`——若 `<clinit>` 调 native(尚未注册)→ `UnsatisfiedLinkError` → 类永久不可用。Netty 自身的 `NativeStaticallyReferencedJniMethods`(其 javadoc 明确描述此循环)印证该问题。
- `262` 是冻结的内核 UAPI ABI(Linux 5.6 起),字面量 + javadoc 说明是安全且符合 Netty 惯例的妥协。C 侧仍保留 `#ifndef IPPROTO_MPTCP #define 262` 兜底。

### 13.3 io_uring 验证策略
- io_uring 端到端功能测试需内核 `io_uring_setup`;本机(Rocky/RHEL 9)该系统调用被禁用(`Operation not permitted`),无法跑 io_uring 功能测试。
- 改用 `IoUringMptcpNativeRegistrationTest`:通过 `IoUring.unavailabilityCause()` 触发 native 库加载(不要求 ring 可用),再调 `LinuxSocket.isMptcpSupported()` 验证 `isMptcpSupported0` 注册成功——**绕开 EPERM 直接验证 C1**。该测试已通过。

### 13.4 C1 教训
- "native 编译通过" ≠ "运行时 RegisterNatives 成功"。C 编译器不检查 Java 方法存在性;`RegisterNatives` 的 Java↔C 方法匹配只在运行时加载时验证。后续 native 方法新增必须有对应的运行时加载测试(如本注册测试)覆盖。
