/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.base.Charsets;
import com.google.common.io.BaseEncoding;
import java.util.Arrays;
import org.junit.Test;

public class LockDescriptorTest {

    private static final String HELLO_WORLD_LOCK_ID = "Hello world!";
    private static final String OPPENHEIMER_LOCK_ID = "Now, I am become Death, the destroyer of worlds.";
    private static final String MEANING_OF_LIFE_LOCK_ID = "~[42]";

    @Test
    public void testSimpleStringDescriptor() {
        testAsciiLockDescriptors("abc123");
        testAsciiLockDescriptors(HELLO_WORLD_LOCK_ID);
        testAsciiLockDescriptors(OPPENHEIMER_LOCK_ID);
        testAsciiLockDescriptors(MEANING_OF_LIFE_LOCK_ID);
        testAsciiLockDescriptors(HELLO_WORLD_LOCK_ID + "/" + OPPENHEIMER_LOCK_ID);
    }

    @Test
    public void testInvalidSimpleStringDescriptor() {
        assertThatThrownBy(() -> testAsciiLockDescriptors("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testNullSimpleStringDescriptor() {
        assertThatThrownBy(() -> testAsciiLockDescriptors(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void testEncodedStringDescriptor() {
        testEncodedLockDescriptors("a\tb\nc\rd");
        testEncodedLockDescriptors(HELLO_WORLD_LOCK_ID + "\n");
        testEncodedLockDescriptors("\t" + OPPENHEIMER_LOCK_ID);
        testEncodedLockId(new byte[0]);
        testEncodedLockId(new byte[] {0x00});
        testEncodedLockId(new byte[] {'h', 0x00, 0x10, 'i'});
    }

    @Test
    public void testInvalidEncodedStringDescriptor() {
        assertThatThrownBy(() -> testEncodedLockDescriptors("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testNullEncodedStringDescriptor() {
        assertThatThrownBy(() -> testEncodedLockDescriptors(null)).isInstanceOf(NullPointerException.class);
    }

    // If this test fails, the implementation of Arrays.hashCode(byte[]), LockDescriptor::hashCode, or
    // LockDescriptor::deterministicHashCode has changed.
    // Either way, we should revisit LockDescriptor::deterministicHashCode and ensure that it is still deterministic
    // and there are no compatibility issues in mixed-version deployments.
    @Test
    public void hashCodeIsDeterministic() {
        // 40 random bytes
        byte[] bytes = new byte[] {
            59, -6, -24, 61, -48, -126, -4, 81, -9, -38, -107, 93, 101, -95, 78, -60, -105, 23, 72, 96, -5, 74, -121,
            59, 40, -87, 1, -6, 43, -20, -70, 101, 63, 108, -51, 117, 113, 116, -87, -73
        };
        int expectedByteArrayHashCode = 2030170880;
        assertThat(Arrays.hashCode(bytes)).isEqualTo(expectedByteArrayHashCode);
        LockDescriptor lockDescriptor = new LockDescriptor(bytes);
        assertThat(lockDescriptor.hashCode()).isEqualTo(expectedByteArrayHashCode + 31);
        assertThat(lockDescriptor.deterministicHashCode()).isEqualTo(expectedByteArrayHashCode + 31);
    }

    private void testAsciiLockDescriptors(String lockId) {
        assertThat(StringLockDescriptor.of(lockId).toString()).isEqualTo(expectedLockDescriptorToString(lockId));

        assertThat(ByteArrayLockDescriptor.of(stringToBytes(lockId)).toString())
                .isEqualTo(expectedLockDescriptorToString(lockId));
    }

    private void testEncodedLockDescriptors(String lockId) {
        assertThat(StringLockDescriptor.of(lockId).toString()).isEqualTo(expectedEncodedLockDescriptorToString(lockId));

        testEncodedLockId(stringToBytes(lockId));
    }

    private void testEncodedLockId(byte[] bytes) {
        assertThat(ByteArrayLockDescriptor.of(bytes).toString())
                .isEqualTo(expectedEncodedLockDescriptorToString(bytes));
    }

    private static String expectedLockDescriptorToString(String lockId) {
        assertThat(lockId).isNotNull();
        return "LockDescriptor [" + lockId + "]";
    }

    private static String expectedEncodedLockDescriptorToString(String lockId) {
        return expectedEncodedLockDescriptorToString(stringToBytes(lockId));
    }

    private static String expectedEncodedLockDescriptorToString(byte[] lockId) {
        assertThat(lockId).isNotNull();
        return "LockDescriptor [" + BaseEncoding.base16().encode(lockId) + "]";
    }

    @SuppressWarnings("checkstyle:jdkStandardCharsets") // StandardCharsets only in JDK 1.7+
    private static byte[] stringToBytes(String lockId) {
        return lockId.getBytes(Charsets.UTF_8);
    }
}
