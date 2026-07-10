# Netty MPTCP 支持(Linux epoll + io_uring)实现计划 — Phase A

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Netty 的 Linux native transport(epoll + io_uring)共享层 `LinuxSocket` 增加 MPTCP socket 创建能力——`LinuxSocket.newSocketStream(SocketProtocolFamily, boolean mptcp)`、`LinuxSocket.isMptcpSupported()`、`LinuxSocket.IPPROTO_MPTCP`。

**Architecture:** 把 `IPPROTO_MPTCP`(262)作为 `socket()` 的 protocol 参数透传过共享 fd 创建链:`native _socket(domain, type, protocol)` → `newSocketStreamFd(ipv6, protocol)` → `Socket.newSocketStream0(ipv6, protocol)` → `LinuxSocket.newSocketStream(family, mptcp)`。能力探测与常量内聚在 `LinuxSocket`(epoll 与 io_uring 各一份 native 实现,共用同一 Java 类)。Java 公共/中间层 API 用**重载**(向后兼容);native 方法声明与 C 实现直接改现有签名。

**Tech Stack:** Java 8+、JNI、C、`hawtjni-maven-plugin`(native 编译,`linux` profile 自动激活,CFLAGS 含 `-Werror`)、JUnit 5 + AssertJ、Maven(`./mvnw`)。

**对应 spec:** `docs/superpowers/specs/2026-07-10-netty-mptcp-epoll-design.md`

---

## 前置条件(执行前确认)

- Linux 主机(本机:`5.14.0`,`CONFIG_MPTCP=y`)。真要验证"多路径生效"还需 `sudo sysctl -w net.mptcp.enabled=1`(本机默认 `0`,但 fd 创建/bind 测试不受影响)。
- Netty native build 工具链:`gcc`、`autoconf`、`automake`、`libtool`、`make`、JDK 8+。
- 工作目录:`/home/skyjiang/netty`,分支 `4.2`。所有 `./mvnw` 命令在该目录运行。
- 验证:`./mvnw -v` 能跑;`gcc --version` 存在。

## 文件结构

| 文件 | 责任 | 改动类型 |
|------|------|----------|
| `transport-native-unix-common/src/main/c/netty_unix_socket.c` | socket() syscall;让 protocol 可透传 | Modify |
| `transport-native-unix-common/src/main/java/io/netty/channel/unix/Socket.java` | fd 创建的 Java/JNI 桥 | Modify |
| `transport-native-epoll/src/main/c/netty_epoll_linuxsocket.c` | epoll 侧 LinuxSocket native 实现 | Modify |
| `transport-native-io_uring/src/main/c/netty_io_uring_linuxsocket.c` | io_uring 侧 LinuxSocket native 实现(同构) | Modify |
| `transport-classes-epoll/src/main/java/io/netty/channel/epoll/LinuxSocket.java` | 用户 API + 能力探测 + 常量(epoll/io_uring 共用) | Modify |
| `transport-native-epoll/src/test/java/io/netty/channel/epoll/EpollMptcpTest.java` | 能力探测 + 创建/bind/throw 测试 | Create |

---

## Task 1:让 protocol 透传过共享 socket 创建链(纯结构重构,不引入 MPTCP)

**目标:** 打开 protocol 通道——`_socket` 与 `newSocketStreamFd` 接收 protocol 参数,`Socket.newSocketStream0` 加重载。本任务**不改变任何现有行为**(`newSocketStream0(ipv6)` 仍传 `0`),验证手段是现有测试零回归。

**Files:**
- Modify: `transport-native-unix-common/src/main/c/netty_unix_socket.c`(`_socket` ~303、`newSocketStreamFd` ~752、`newSocketDgramFd` ~747、方法表 ~1280)
- Modify: `transport-native-unix-common/src/main/java/io/netty/channel/unix/Socket.java`(`newSocketStream0` ~616、native 声明 ~716)

- [ ] **Step 1: 改 `netty_unix_socket.c` 的 `_socket` 加 protocol 参数**

定位 `static jint _socket(JNIEnv* env, jclass clazz, int domain, int type)`(~line 303),改为:

```c
static jint _socket(JNIEnv* env, jclass clazz, int domain, int type, int protocol) {
    int fd = netty_unix_socket_nonBlockingSocket(domain, type, protocol);
    if (fd == -1) {
        return -errno;
    } else if (domain == AF_INET6) {
        // Try to allow listen /connect ipv4 and ipv6
        int optval = 0;
        if (netty_unix_socket_setOption0(fd, IPPROTO_IPV6, IPV6_V6ONLY, &optval, sizeof(optval)) < 0) {
            if (errno != EAFNOSUPPORT) {
                netty_unix_socket_setOptionHandleError(env, errno);
                close(fd);
                return -1;
            }
        }
    }
    return fd;
}
```
(唯一实质变化:签名加 `int protocol`,第 4 行 `0` → `protocol`。函数体其余保持原样。)

- [ ] **Step 2: 改 `newSocketStreamFd` 透传 protocol,`newSocketDgramFd` 显式传 0**

定位(~line 747-755):

```c
static jint netty_unix_socket_newSocketDgramFd(JNIEnv* env, jclass clazz, jboolean ipv6) {
    int domain = ipv6 == JNI_TRUE ? AF_INET6 : AF_INET;
    return _socket(env, clazz, domain, SOCK_DGRAM, 0);
}

static jint netty_unix_socket_newSocketStreamFd(JNIEnv* env, jclass clazz, jboolean ipv6, jint protocol) {
    int domain = ipv6 == JNI_TRUE ? AF_INET6 : AF_INET;
    return _socket(env, clazz, domain, SOCK_STREAM, protocol);
}
```
(dgram 调 `_socket` 现在需补第 5 个参数 `0`;stream 加 `jint protocol` 参数并透传。`newSocketDomainFd` / `newSocketDomainDgramFd` 不走 `_socket`,保持原样不动。)

- [ ] **Step 3: 改方法表签名 `(Z)I` → `(ZI)I`**

定位方法表中 `{ "newSocketStreamFd", "(Z)I", ... }`(~line 1280 附近),改为:

```c
{ "newSocketStreamFd", "(ZI)I", (void *) netty_unix_socket_newSocketStreamFd },
```

- [ ] **Step 4: 改 `Socket.java` 的 native 声明**

定位 `private static native int newSocketStreamFd(boolean ipv6);`(~line 716),改为:

```java
private static native int newSocketStreamFd(boolean ipv6, int protocol);
```

- [ ] **Step 5: 改 `Socket.java` 的 `newSocketStream0` 并加重载**

定位 `protected static int newSocketStream0(boolean ipv6)`(~line 616),改为(原方法内部传 0,签名不变;新增带 protocol 的重载):

```java
protected static int newSocketStream0(boolean ipv6) {
    int res = newSocketStreamFd(ipv6, 0);
    if (res < 0) {
        throw new ChannelException(newIOException("newSocketStream", res));
    }
    return res;
}

protected static int newSocketStream0(boolean ipv6, int protocol) {
    int res = newSocketStreamFd(ipv6, protocol);
    if (res < 0) {
        throw new ChannelException(newIOException("newSocketStream", res));
    }
    return res;
}
```

- [ ] **Step 6: 编译 unix-common + epoll native,跑现有 epoll 测试确认零回归**

Run:
```bash
./mvnw -pl transport-native-epoll -am clean test -Dtest=EpollSocketChannelConfigTest
```
Expected: `BUILD SUCCESS`,`EpollSocketChannelConfigTest` 全部 PASS(行为未变,protocol 仍为 0)。`-am` 会重新编译被改动的 `transport-native-unix-common` native 库;`clean` 强制 hawtjni 重编。

> 若 native 编译报 `-Werror` 警告(如未用变量),按提示修掉——CFLAGS 不容忍警告。

- [ ] **Step 7: Commit**

```bash
git add transport-native-unix-common/src/main/c/netty_unix_socket.c \
        transport-native-unix-common/src/main/java/io/netty/channel/unix/Socket.java
git commit -m "Thread socket() protocol through unix-common fd creation

Prepare for MPTCP support: _socket/newSocketStreamFd accept a protocol
arg; Socket.newSocketStream0 adds an overload. Existing callers pass 0,
so behavior is unchanged."
```

---

## Task 2:能力探测 `isMptcpSupported` + `IPPROTO_MPTCP` 常量(TDD)

**目标:** `LinuxSocket.isMptcpSupported()` 与 `LinuxSocket.IPPROTO_MPTCP`(=262)可用,native 在 epoll 与 io_uring 各一份。

**Files:**
- Test: `transport-native-epoll/src/test/java/io/netty/channel/epoll/EpollMptcpTest.java`(Create)
- Modify: `transport-classes-epoll/src/main/java/io/netty/channel/epoll/LinuxSocket.java`
- Modify: `transport-native-epoll/src/main/c/netty_epoll_linuxsocket.c`
- Modify: `transport-native-io_uring/src/main/c/netty_io_uring_linuxsocket.c`

- [ ] **Step 1: 写失败测试**

创建 `transport-native-epoll/src/test/java/io/netty/channel/epoll/EpollMptcpTest.java`:

```java
package io.netty.channel.epoll;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EpollMptcpTest {

    @Test
    void ipprotoMptcpConstantIs262() {
        assertEquals(262, LinuxSocket.IPPROTO_MPTCP);
    }

    @Test
    void isMptcpSupportedReturnsBoolean() {
        // 内核 5.14 + CONFIG_MPTCP=y → true;老内核 → false。两种都合法,只断言可调用且为 boolean 语义。
        assertTrue(LinuxSocket.isMptcpSupported() == true || LinuxSocket.isMptcpSupported() == false);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
./mvnw -pl transport-native-epoll -am clean test -Dtest=EpollMptcpTest
```
Expected: 编译失败——`LinuxSocket.IPPROTO_MPTCP` / `isMptcpSupported()` 不存在。

- [ ] **Step 3: `LinuxSocket.java` 加 native 声明、常量、静态方法**

在 `LinuxSocket.java` 的 native 声明区(~line 506,`isUdpGro`/`setUdpGro` 附近)加:

```java
private static native int ipprotoMptcp0();
private static native boolean isMptcpSupported0();
```

在类的静态常量/字段区(与既有 `public static final` 放一起)加:

```java
public static final int IPPROTO_MPTCP = ipprotoMptcp0();
private static final boolean IS_MPTCP_SUPPORTED = isMptcpSupported0();

/**
 * Returns {@code true} if the kernel supports Multipath TCP, i.e. a socket can be
 * created with {@code IPPROTO_MPTCP}. This is a compile-time+runtime capability probe;
 * actual multipath behaviour additionally requires {@code net.mptcp.enabled=1}.
 */
public static boolean isMptcpSupported() {
    return IS_MPTCP_SUPPORTED;
}
```

- [ ] **Step 4: epoll native 加常量兜底 + 两个 C 函数 + 方法表**

在 `netty_epoll_linuxsocket.c` 顶部(既有 `#ifndef UDP_GRO #define UDP_GRO 104 #endif` 块 ~line 61-64 之后)加:

```c
#ifndef IPPROTO_MPTCP
#define IPPROTO_MPTCP 262
#endif
```

在 `isUdpGro`/`setUdpGro` 函数附近(~line 772-782)加:

```c
static jint netty_epoll_linuxsocket_ipprotoMptcp(JNIEnv* env, jclass clazz) {
    return IPPROTO_MPTCP;
}

static jboolean netty_epoll_linuxsocket_isMptcpSupported0(JNIEnv* env, jclass clazz) {
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_MPTCP);
    if (fd == -1) {
        return JNI_FALSE;
    }
    close(fd);
    return JNI_TRUE;
}
```

在方法表(`{ "isUdpGro", ... }` / `{ "setUdpGro", ... }` ~line 868-869 附近)加两行:

```c
{ "ipprotoMptcp0", "()I", (void *) netty_epoll_linuxsocket_ipprotoMptcp },
{ "isMptcpSupported0", "()Z", (void *) netty_epoll_linuxsocket_isMptcpSupported0 },
```

- [ ] **Step 5: io_uring native 同构改动**

对 `netty_io_uring_linuxsocket.c` 做与 Step 4 完全相同的改动,仅函数名前缀换成 `netty_io_uring_linuxsocket_*`(参考该文件既有 `netty_io_uring_linuxsocket_setTcpCork` 等命名与方法表位置):

```c
/* 顶部 */
#ifndef IPPROTO_MPTCP
#define IPPROTO_MPTCP 262
#endif

/* 函数 */
static jint netty_io_uring_linuxsocket_ipprotoMptcp(JNIEnv* env, jclass clazz) {
    return IPPROTO_MPTCP;
}

static jboolean netty_io_uring_linuxsocket_isMptcpSupported0(JNIEnv* env, jclass clazz) {
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_MPTCP);
    if (fd == -1) {
        return JNI_FALSE;
    }
    close(fd);
    return JNI_TRUE;
}

/* 方法表(参照该文件既有 setTcpCork 条目格式) */
{ "ipprotoMptcp0", "()I", (void *) netty_io_uring_linuxsocket_ipprotoMptcp },
{ "isMptcpSupported0", "()Z", (void *) netty_io_uring_linuxsocket_isMptcpSupported0 },
```

- [ ] **Step 6: 编译 epoll native + 跑测试确认通过**

Run:
```bash
./mvnw -pl transport-native-epoll -am clean test -Dtest=EpollMptcpTest
```
Expected: `BUILD SUCCESS`,两个测试 PASS。本机应得 `isMptcpSupported()==true`、`IPPROTO_MPTCP==262`。

- [ ] **Step 7: 验证 io_uring native 也能编译通过(确保双份 native 无 `-Werror` 问题)**

Run:
```bash
./mvnw -pl transport-native-io_uring -am clean install -DskipTests
```
Expected: `BUILD SUCCESS`(只需确认 io_uring 的 `.so` 能编出,不需要跑 io_uring 测试)。

- [ ] **Step 8: Commit**

```bash
git add transport-classes-epoll/src/main/java/io/netty/channel/epoll/LinuxSocket.java \
        transport-native-epoll/src/main/c/netty_epoll_linuxsocket.c \
        transport-native-io_uring/src/main/c/netty_io_uring_linuxsocket.c \
        transport-native-epoll/src/test/java/io/netty/channel/epoll/EpollMptcpTest.java
git commit -m "Add MPTCP capability detection and IPPROTO_MPTCP constant

LinuxSocket.isMptcpSupported() probes the kernel by trying to create an
IPPROTO_MPTCP socket (mirrors isSupportingUdpSegment). IPPROTO_MPTCP is
returned from native (262) with an #ifndef fallback for older glibc
headers. Native impl provided in both epoll and io_uring linuxsocket.c
so both Linux transports share the capability via the common LinuxSocket
class."
```

---

## Task 3:`newSocketStream(family, mptcp)` 重载 + 端到端创建/bind/throw 测试(TDD)

**目标:** `LinuxSocket.newSocketStream(SocketProtocolFamily, boolean mptcp)` 可创建 MPTCP fd;`mptcp=true` 且内核不支持时显式 throw。

**Files:**
- Test: `transport-native-epoll/src/test/java/io/netty/channel/epoll/EpollMptcpTest.java`(扩展)
- Modify: `transport-classes-epoll/src/main/java/io/netty/channel/epoll/LinuxSocket.java`(`newSocketStream` 重载 ~412)

- [ ] **Step 1: 扩展测试(失败先行)**

在 `EpollMptcpTest.java` 顶部补 import,并追加三个测试方法:

```java
import io.netty.channel.ChannelException;
import io.netty.channel.socket.SocketProtocolFamily;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
```

追加方法:

```java
@Test
void newSocketStreamMptcpCreatesAndBindsFdWhenSupported() throws Exception {
    Assumptions.assumeTrue(LinuxSocket.isMptcpSupported(),
            "MPTCP not supported on this kernel");
    LinuxSocket socket = LinuxSocket.newSocketStream(SocketProtocolFamily.INET, true);
    try {
        assertTrue(socket.intValue() > 0);
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        assertNotNull(socket.localAddress());
    } finally {
        socket.close();
    }
}

@Test
void newSocketStreamWithMptcpFalseFallsBackToTcp() throws Exception {
    LinuxSocket socket = LinuxSocket.newSocketStream(SocketProtocolFamily.INET, false);
    try {
        assertTrue(socket.intValue() > 0);
    } finally {
        socket.close();
    }
}

@Test
void newSocketStreamMptcpThrowsWhenUnsupported() {
    Assumptions.assumeTrue(!LinuxSocket.isMptcpSupported(),
            "only runs on kernels WITHOUT MPTCP support");
    assertThrows(ChannelException.class,
            () -> LinuxSocket.newSocketStream(SocketProtocolFamily.INET, true));
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
./mvnw -pl transport-native-epoll -am clean test -Dtest=EpollMptcpTest
```
Expected: 编译失败——`LinuxSocket.newSocketStream(SocketProtocolFamily, boolean)` 不存在。

- [ ] **Step 3: `LinuxSocket.java` 加重载**

在 `public static LinuxSocket newSocketStream(SocketProtocolFamily protocol)`(~line 412)之后加:

```java
/**
 * Creates a new stream socket, optionally using Multipath TCP.
 *
 * @param protocol the address family
 * @param mptcp    {@code true} to create the socket with {@code IPPROTO_MPTCP}; {@code false}
 *                 for a regular TCP socket (identical to {@link #newSocketStream(SocketProtocolFamily)})
 * @throws ChannelException if {@code mptcp} is {@code true} but the kernel does not support MPTCP
 */
public static LinuxSocket newSocketStream(SocketProtocolFamily protocol, boolean mptcp) {
    if (mptcp && !IS_MPTCP_SUPPORTED) {
        throw new ChannelException("MPTCP is not supported by the kernel");
    }
    int fd = newSocketStream0(shouldUseIpv6(protocol), mptcp ? IPPROTO_MPTCP : 0);
    return new LinuxSocket(fd);
}
```
(`shouldUseIpv6(SocketProtocolFamily)` 与 `newSocketStream0(boolean, int)` 均继承自 `io.netty.channel.unix.Socket`,可直接调用。)

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
./mvnw -pl transport-native-epoll -am clean test -Dtest=EpollMptcpTest
```
Expected: `BUILD SUCCESS`。本机(`isMptcpSupported()==true`):`newSocketStreamMptcpCreatesAndBindsFdWhenSupported` 与 `...FalseFallsBackToTcp` PASS,`...ThrowsWhenUnsupported` 被 `Assumptions` 跳过(本机支持 MPTCP)。

- [ ] **Step 5: (可选,需 root)手动验证真正多路径**

```bash
sudo sysctl -w net.mptcp.enabled=1
# 用一个临时 main 或 valkey-cli --mptcp 连本机 epoll server,另开终端:
ss -Mt | grep <port>     # 应能看到 mptcp 连接及其 subflow
cat /proc/net/mptcp      # 有计数
sudo sysctl -w net.mptcp.enabled=0   # 还原
```
Expected(若 `net.mptcp.enabled=1`):`ss -Mt` 显示 `mptcp` 标记的连接。本任务不写成自动化测试(需要 root + 多路径环境)。

- [ ] **Step 6: Commit**

```bash
git add transport-classes-epoll/src/main/java/io/netty/channel/epoll/LinuxSocket.java \
        transport-native-epoll/src/test/java/io/netty/channel/epoll/EpollMptcpTest.java
git commit -m "Add LinuxSocket.newSocketStream(family, mptcp) for MPTCP

When mptcp=true and the kernel supports it, the socket is created with
IPPROTO_MPTCP; otherwise (mptcp=true && unsupported) throws
ChannelException rather than silently falling back. mptcp=false is
identical to the existing single-arg overload. Phase A boundary:
LinuxSocket layer only (channel constructors deferred to Phase B)."
```

---

## Self-Review

**1. Spec coverage** — 对照 spec 各节:
- §2 范围(native C 透传 / JNI / LinuxSocket API / 能力探测 / 单测):Task 1(C 透传+JNI)、Task 2(能力+常量)、Task 3(LinuxSocket API+测试)全覆盖。✓
- §5.1 native unix-common(`_socket`/`newSocketStreamFd`/方法表):Task 1 Step 1-3。✓
- §5.2 `*_linuxsocket.c` 双份(define + ipprotoMptcp0 + isMptcpSupported0 + 方法表):Task 2 Step 4-5。✓
- §5.3 Socket.java(native 声明改签名 + newSocketStream0 重载):Task 1 Step 4-5。✓
- §5.4 LinuxSocket(常量 + isMptcpSupported + newSocketStream 重载 + throw):Task 2 Step 3、Task 3 Step 3。✓
- §7 错误处理(mptcp=true && !supported → throw):Task 3 Step 3 + 测试 Step 1。✓
- §9 测试(能力探测 + fd 创建 + bind + throw + assume 跳过):Task 2 Step 1、Task 3 Step 1。✓
- 决策⑤(Java 重载 + native 直接改现有签名):Task 1 体现。✓

**2. Placeholder scan** — 无 TBD/TODO/"add error handling" 等;每个 code step 都有完整代码;每条命令有 expected。✓

**3. Type consistency** — `newSocketStreamFd(boolean, int)`(Task1 C + Java + 方法表 `(ZI)I`)、`newSocketStream0(boolean, int)`(Task1 Java)、`ipprotoMptcp0()`/`isMptcpSupported0()`(Task2 C `(I)I`/`()Z` 与 Java native 声明一致)、`IPPROTO_MPTCP`/`IS_MPTCP_SUPPORTED`/`isMptcpSupported()`(Task2 定义,Task3 使用)——跨任务命名/签名一致。✓

**4. 已知限制(非占位符,已在计划中显式标注)**:
- io_uring 测试不跑(只验证编译,Task 2 Step 7)——本机 io_uring 测试需要 io_uring 内核支持且非本 Phase 必需。
- "真多路径"验证为可选手动步骤(Task 3 Step 5,需 root)。

---

## 修订记录(实现阶段,2026-07-10)

### Task 4(实现阶段追加):io_uring 镜像 + 注册测试(C1 修复)
**背景**:最终整体 review 发现 Critical(C1)——spec 假设 epoll/io_uring 共享一个 `LinuxSocket`,实际是两个独立类;io_uring `.c` 注册了 `isMptcpSupported0` 但 io_uring Java 侧未声明 → `RegisterNatives` 失败 → io_uring native 库加载崩溃。

**改动**(纯 Java,native 已就绪):
- 把 epoll `LinuxSocket` 的 MPTCP Java API(`isMptcpSupported0` 声明、`IPPROTO_MPTCP=262`、`MptcpHolder`、`isMptcpSupported()`、`newSocketStream(SocketProtocolFamily, boolean)`)镜像到 `transport-classes-io_uring/.../uring/LinuxSocket.java`。
- 新增 `transport-native-io_uring/src/test/.../IoUringMptcpNativeRegistrationTest.java`:用 `IoUring.unavailabilityCause()` 触发库加载,验证 `isMptcpSupported0` 注册成功(绕开本机 `io_uring_setup` EPERM)。

**验证**:
- io_uring native `-Werror` 编译 + `IoUringMptcpNativeRegistrationTest` 通过(1 test)。
- epoll `EpollMptcpTest` 回归绿(5 tests,1 skipped)。

### 决策①偏离
常量来源由"native 返回"改为字面量 `262` + holder(类初始化竞态;详见 spec §13.2)。

### 验证命令环境注记
本机构建需:`JAVA_HOME` 显式设置;`test` 前必须先 `install`(MDEP-98 unpack-dependencies reactor 顺序);`-Dsurefire.failIfNoSpecifiedTests=false` 避免 reactor 其它模块无匹配测试失败。io_uring 端到端功能受 `io_uring_setup` EPERM 限制(Rocky 9),由 `IoUringMptcpNativeRegistrationTest` 覆盖 native 注册层面。
