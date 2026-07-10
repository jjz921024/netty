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
package io.netty.channel.uring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the io_uring native library's {@code RegisterNatives} succeeds for
 * {@code LinuxSocket.isMptcpSupported0}. Before the matching Java declaration was added,
 * registering that native failed the entire io_uring native-library load at {@code JNI_OnLoad}.
 *
 * <p>This test deliberately does not require a working io_uring ring. It only needs the native
 * library to load (which is what runs {@code RegisterNatives}); on hosts where the kernel
 * disables {@code io_uring_setup} the ring is unavailable but the library still loads.
 */
class IoUringMptcpNativeRegistrationTest {

    @Test
    void isMptcpSupported0NativeIsRegistered() {
        // Force IoUring.<clinit>, which loads the native library via Native.<clinit>
        // and runs RegisterNatives for the LinuxSocket method table (incl. isMptcpSupported0).
        // On a kernel with io_uring_setup disabled this returns a non-null cause (ring setup
        // EPERM), but the library itself still loaded successfully — which is all this test needs.
        IoUring.unavailabilityCause();

        // Force MptcpHolder.<clinit> -> isMptcpSupported0() native call. If RegisterNatives
        // failed for isMptcpSupported0, this throws UnsatisfiedLinkError (the regression guarded).
        boolean supported = LinuxSocket.isMptcpSupported();
        // Cached probe: repeated calls must agree. On this CONFIG_MPTCP=y kernel the probe
        // (socket(AF_INET, SOCK_STREAM, IPPROTO_MPTCP)) must succeed, independent of io_uring.
        assertEquals(supported, LinuxSocket.isMptcpSupported());
        assertTrue(supported);
        assertEquals(262, LinuxSocket.IPPROTO_MPTCP);
    }
}
