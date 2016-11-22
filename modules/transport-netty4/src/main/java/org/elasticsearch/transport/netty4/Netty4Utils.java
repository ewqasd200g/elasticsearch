/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.transport.netty4;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefIterator;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.ESLoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class Netty4Utils {

    static {
        InternalLoggerFactory.setDefaultFactory(new InternalLoggerFactory() {

            @Override
            public InternalLogger newInstance(final String name) {
                return new Netty4InternalESLogger(name);
            }

        });
    }

    public static void setup() {

    }

    /**
     * Turns the given BytesReference into a ByteBuf. Note: the returned ByteBuf will reference the internal
     * pages of the BytesReference. Don't free the bytes of reference before the ByteBuf goes out of scope.
     */
    public static ByteBuf toByteBuf(final BytesReference reference) {
        if (reference.length() == 0) {
            return Unpooled.EMPTY_BUFFER;
        }
        if (reference instanceof ByteBufBytesReference) {
            return ((ByteBufBytesReference) reference).toByteBuf();
        } else {
            final BytesRefIterator iterator = reference.iterator();
            // usually we have one, two, or three components from the header, the message, and a buffer
            final List<ByteBuf> buffers = new ArrayList<>(3);
            try {
                BytesRef slice;
                while ((slice = iterator.next()) != null) {
                    buffers.add(Unpooled.wrappedBuffer(slice.bytes, slice.offset, slice.length));
                }
                final CompositeByteBuf composite = Unpooled.compositeBuffer(buffers.size());
                composite.addComponents(true, buffers);
                return composite;
            } catch (IOException ex) {
                throw new AssertionError("no IO happens here", ex);
            }
        }
    }

    /**
     * Wraps the given ChannelBuffer with a BytesReference
     */
    public static BytesReference toBytesReference(final ByteBuf buffer) {
        return toBytesReference(buffer, buffer.readableBytes());
    }

    /**
     * Wraps the given ChannelBuffer with a BytesReference of a given size
     */
    static BytesReference toBytesReference(final ByteBuf buffer, final int size) {
        return new ByteBufBytesReference(buffer, size);
    }

    public static void closeChannels(final Collection<Channel> channels) throws IOException {
        IOException closingExceptions = null;
        final List<ChannelFuture> futures = new ArrayList<>();
        for (final Channel channel : channels) {
            try {
                if (channel != null && channel.isOpen()) {
                    futures.add(channel.close());
                }
            } catch (Exception e) {
                if (closingExceptions == null) {
                    closingExceptions = new IOException("failed to close channels");
                }
                closingExceptions.addSuppressed(e);
            }
        }
        for (final ChannelFuture future : futures) {
            future.awaitUninterruptibly();
        }

        if (closingExceptions != null) {
            throw closingExceptions;
        }
    }

    public static void maybeDie(final Throwable cause) throws IOException {
        if (cause instanceof Error) {
            /*
             * Here be dragons. We want to rethrow this so that it bubbles up to the uncaught exception handler. Yet, Netty wraps too many
             * invocations of user-code in try/catch blocks that swallow all throwables. This means that a rethrow here will not bubble up
             * to where we want it to. So, we fork a thread and throw the exception from there where Netty can not get to it. We do not wrap
             * the exception so as to not lose the original cause during exit, so we give the thread a name based on the previous stack
             * frame so that at least we know where it came from (in case logging the current stack trace fails).
             */
            try (
                final StringWriter sw = new StringWriter();
                final PrintWriter pw = new PrintWriter(sw)) {
                // try to log the current stack trace
                Arrays.stream(Thread.currentThread().getStackTrace()).skip(1).map(e -> "\tat " + e).forEach(pw::println);
                ESLoggerFactory.getLogger(Netty4Utils.class).error("fatal error on the network layer\n{}", sw.toString());
            } finally {
                final StackTraceElement previous = Thread.currentThread().getStackTrace()[2];
                new Thread(
                    () -> {
                        throw (Error) cause;
                    },
                    previous.getClassName() + "#" + previous.getMethodName())
                    .start();
            }
        }
    }

}