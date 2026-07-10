/*
 * Copyright 2024 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.epoll;

import io.netty.channel.ChannelException;
import io.netty.channel.socket.SocketProtocolFamily;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EpollMptcpTest {

    @BeforeAll
    static void loadNativeLibrary() {
        // isMptcpSupported() is a native method on LinuxSocket, registered as a side-effect of
        // loading the epoll native library (Native.<clinit>). Ensure that library is loaded
        // before we call into native.
        Epoll.ensureAvailability();
    }

    @Test
    void ipprotoMptcpConstantIs262() {
        assertEquals(262, LinuxSocket.IPPROTO_MPTCP);
    }

    @Test
    void isMptcpSupportedIsStableAndTrueOnMptcpKernel() {
        // The probe tries socket(AF_INET, SOCK_STREAM, IPPROTO_MPTCP); it succeeds when the
        // kernel was built with CONFIG_MPTCP (independent of net.mptcp.enabled). This host's
        // kernel (5.14, CONFIG_MPTCP=y) supports it, so the probe must return true AND the
        // cached value must be stable across calls.
        boolean first = LinuxSocket.isMptcpSupported();
        assertEquals(first, LinuxSocket.isMptcpSupported());
        assertTrue(first);
    }

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
}

