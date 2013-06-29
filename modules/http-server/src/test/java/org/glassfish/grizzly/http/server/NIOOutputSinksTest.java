/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.grizzly.http.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import junit.framework.TestCase;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.Writer;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.io.NIOWriter;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.DelayFilter;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.utils.Futures;

public class NIOOutputSinksTest extends TestCase {
    private static final Logger LOGGER = Grizzly.logger(NIOOutputSinksTest.class);
    private static final int PORT = 9339;

    public void testBinaryOutputSink() throws Exception {

        final HttpServer server = new HttpServer();
        final NetworkListener listener =
                new NetworkListener("Grizzly",
                                    NetworkListener.DEFAULT_NETWORK_HOST,
                                    PORT);
        final int LENGTH = 256000;
        final int MAX_LENGTH = LENGTH * 2;
        listener.setMaxPendingBytes(MAX_LENGTH);
        server.addListener(listener);
        final FutureImpl<Integer> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private int bytesRead;
            
            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                        .uri("/path").protocol(Protocol.HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = (HttpContent) ctx.getMessage();
                Buffer b = message.getContent();
                final int remaining = b.remaining();
                
                if (b.hasRemaining()) {
                    try {
                        check(b.toStringContent(), bytesRead % LENGTH, remaining, LENGTH);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }
                    
                    bytesRead += remaining;
                }
                
                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });


        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setFilterChain(filterChainBuilder.build());
        final AtomicInteger writeCounter = new AtomicInteger();
        final AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        final HttpHandler ga = new HttpHandler() {

            @Override
            public void service(final Request request, final Response response) throws Exception {
                
                clientTransport.pause();
                response.setContentType("text/plain");
                final NIOOutputStream out = response.getOutputStream();

                while (out.canWrite()) {
                    byte[] b = new byte[LENGTH];
                    fill(b);
                    writeCounter.addAndGet(b.length);
                    out.write(b);
                    out.flush();
                }
                response.suspend();

                final Connection c = request.getContext().getConnection();

                out.notifyWritePossible(new WriteHandler() {
                    @Override
                    public void onWritePossible() {
                        callbackInvoked.compareAndSet(false, true);
                        try {
                            clientTransport.pause();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }

                        assertTrue(c.canWrite());

                        try {
                            clientTransport.resume();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        try {
                            byte[] b = new byte[LENGTH];
                            fill(b);
                            writeCounter.addAndGet(b.length);
                            out.write(b);
                            out.flush();
                            out.close();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        response.resume();

                    }

                    @Override
                    public void onError(Throwable t) {
                        response.resume();
                        throw new RuntimeException(t);
                    }
                });

                clientTransport.resume();
            }

        };


        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                int length = parseResult.get(10, TimeUnit.SECONDS);
                assertEquals(writeCounter.get(), length);
                assertTrue(callbackInvoked.get());
            } finally {
                LOGGER.log(Level.INFO, "Written {0}", writeCounter);
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    public void testBlockingBinaryOutputSink() throws Exception {

        final HttpServer server = new HttpServer();
        final NetworkListener listener =
                new NetworkListener("Grizzly",
                                    NetworkListener.DEFAULT_NETWORK_HOST,
                                    PORT);
        final int bufferSize = 4096;
        final int maxPendingWriteQueueSize = bufferSize * 3 / 4;
        final int bytesToSend = bufferSize * 1024 * 4;
        
        listener.setMaxPendingBytes(maxPendingWriteQueueSize);
        server.addListener(listener);
        final FutureImpl<Integer> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new DelayFilter(5, 0));
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private int bytesRead;
            
            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                        .uri("/path").protocol(Protocol.HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = (HttpContent) ctx.getMessage();
                Buffer b = message.getContent();
                final int remaining = b.remaining();
                
                if (b.hasRemaining()) {
                    try {
                        check(b.toStringContent(), bytesRead % bufferSize, remaining, bufferSize);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }
                    
                    bytesRead += remaining;
                }
                
                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });


        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setFilterChain(filterChainBuilder.build());
        final AtomicInteger writeCounter = new AtomicInteger();
        final HttpHandler ga = new HttpHandler() {

            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.setContentType("text/plain");
                final NIOOutputStream out = response.getOutputStream();

                int sent = 0;
                
                byte[] b = new byte[bufferSize];
                fill(b);
                try {
                    while (sent < bytesToSend) {
                        out.write(b);
                        sent += bufferSize;
                        writeCounter.addAndGet(bufferSize);
                        assertTrue(request.getContext().getConnection().canWrite());
                    }
                } catch (Throwable e) {
                    LOGGER.log(Level.SEVERE, "Unexpected error", e);
                    parseResult.failure(new IllegalStateException("Error", e));
                }
            }
        };


        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                int length = parseResult.get(60, TimeUnit.SECONDS);
                assertEquals("Received " + length + " bytes", bytesToSend, length);
            } finally {
                LOGGER.log(Level.INFO, "Written {0}", writeCounter);
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    public void testCharacterOutputSink() throws Exception {

        final HttpServer server = new HttpServer();
        final NetworkListener listener =
                new NetworkListener("Grizzly",
                                    NetworkListener.DEFAULT_NETWORK_HOST,
                                    PORT);
        final int LENGTH = 256000;
        final int MAX_LENGTH = LENGTH * 2;
        listener.setMaxPendingBytes(MAX_LENGTH);
        server.addListener(listener);
        final FutureImpl<Integer> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private int bytesRead;

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                        .uri("/path").protocol(Protocol.HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = (HttpContent) ctx.getMessage();
                Buffer b = message.getContent();
                final int remaining = b.remaining();
                
                if (b.hasRemaining()) {
                    try {
                        check(b.toStringContent(), bytesRead % LENGTH, remaining, LENGTH);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }
                    
                    bytesRead += remaining;
                }
                
                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });


        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setFilterChain(filterChainBuilder.build());
        final AtomicInteger writeCounter = new AtomicInteger();
        final AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        final HttpHandler ga = new HttpHandler() {

            @Override
            public void service(final Request request, final Response response) throws Exception {
                clientTransport.pause();

                response.setContentType("text/plain");
                final NIOWriter out = response.getWriter();
                Connection c = request.getContext().getConnection();

                while (out.canWrite()) {
                    char[] data = new char[LENGTH];
                    fill(data);
                    writeCounter.addAndGet(data.length);
                    out.write(data);
                    out.flush();
                }                

                response.suspend();
                notifyCanWrite(c, out, response);
                
                clientTransport.resume();
            }

            private void notifyCanWrite(final Connection c,
                    final NIOWriter out,
                    final Response response) {

                out.notifyWritePossible(new WriteHandler() {

                    @Override
                    public void onWritePossible() {
                        callbackInvoked.compareAndSet(false, true);
                        try {
                            clientTransport.pause();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        assertTrue(c.canWrite());
                        try {
                            clientTransport.resume();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        try {
                            char[] c = new char[LENGTH];
                            fill(c);
                            writeCounter.addAndGet(c.length);
                            out.write(c);
                            out.flush();
                            out.close();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        response.resume();
                    }

                    @Override
                    public void onError(Throwable t) {
                        response.resume();
                        throw new RuntimeException(t);
                    }
                });
            }

        };


        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                int length = parseResult.get(10, TimeUnit.SECONDS);
                assertEquals(writeCounter.get(), length);
                assertTrue(callbackInvoked.get());
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.stop();
            server.stop();
        }

    }


    public void testBlockingCharacterOutputSink() throws Exception {

        final HttpServer server = new HttpServer();
        final NetworkListener listener =
                new NetworkListener("Grizzly",
                                    NetworkListener.DEFAULT_NETWORK_HOST,
                                    PORT);
        final int bufferSize = 4096;
        final int maxPendingWriteQueueSize = bufferSize * 3 / 4;
        final int bytesToSend = bufferSize * 1024 * 4;
        
        listener.setMaxPendingBytes(maxPendingWriteQueueSize);
        server.addListener(listener);
        final FutureImpl<Integer> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new DelayFilter(5, 0));
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private int bytesRead;
            
            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                        .uri("/path").protocol(Protocol.HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = (HttpContent) ctx.getMessage();
                Buffer b = message.getContent();
                final int remaining = b.remaining();
                
                if (b.hasRemaining()) {
                    try {
                        check(b.toStringContent(), bytesRead % bufferSize, remaining, bufferSize);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }
                    
                    bytesRead += remaining;
                }
                
                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });


        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setFilterChain(filterChainBuilder.build());
        final AtomicInteger writeCounter = new AtomicInteger();
        final HttpHandler ga = new HttpHandler() {

            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.setContentType("text/plain");
                final NIOWriter out = response.getWriter();

                int sent = 0;
                
                char[] b = new char[bufferSize];
                fill(b);
                while (sent < bytesToSend) {
                    out.write(b);
                    sent += bufferSize;
                    writeCounter.addAndGet(bufferSize);
                    assertTrue(request.getContext().getConnection().canWrite());
                }
            }
        };


        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                int length = parseResult.get(60, TimeUnit.SECONDS);
                assertEquals("Received " + length + " bytes", bytesToSend, length);
            } finally {
                LOGGER.log(Level.INFO, "Written {0}", writeCounter);
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    public void testWriteExceptionPropagation() throws Exception {
        final int LENGTH = 1024;
        
        final HttpServer server = new HttpServer();
        final NetworkListener listener =
                new NetworkListener("Grizzly",
                                    NetworkListener.DEFAULT_NETWORK_HOST,
                                    PORT);
        listener.registerAddOn(new AddOn() {

            @Override
            public void setup(NetworkListener networkListener, FilterChainBuilder builder) {
                final int idx = builder.indexOfType(TransportFilter.class);
                builder.add(idx + 1, new BaseFilter() {
                    final AtomicInteger counter = new AtomicInteger();
                    @Override
                    public NextAction handleWrite(FilterChainContext ctx)
                            throws IOException {
                        final Buffer buffer = ctx.getMessage();
                        if (counter.addAndGet(buffer.remaining()) > LENGTH * 8) {
                            throw new CustomException();
                        }
                        
                        return ctx.getInvokeAction();
                    }
                });
            }
            
        });
        
        server.addListener(listener);
        final FutureImpl<Boolean> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                        .uri("/path").protocol(Protocol.HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                return ctx.getSuspendAction();
            }
        });

        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setFilterChain(filterChainBuilder.build());
        final HttpHandler ga = new HttpHandler() {

            @Override
            public void service(final Request request, final Response response) throws Exception {

                //clientTransport.pause();
                response.setContentType("text/plain");
                final NIOWriter out = response.getWriter();

                char[] c = new char[LENGTH];
                Arrays.fill(c, 'a');
                
                for(;;) {
                    try {
                        out.write(c);
                        out.flush();
                    } catch (IOException e) {
                        if (e.getCause() instanceof CustomException) {
                            parseResult.result(Boolean.TRUE);
                        } else {
                            System.out.println("NOT CUSTOM");
                            parseResult.failure(e);
                        }
                        break;
                    } catch (Exception e) {
                        System.out.println("NOT CUSTOM");
                        parseResult.failure(e);
                        break;
                    }
                }

            }

        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                boolean exceptionThrown = parseResult.get(10, TimeUnit.SECONDS);
                assertTrue("Unexpected Exception thrown.", exceptionThrown);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }

    public void testOutputBufferDirectWrite() throws Exception {

        final HttpServer server = new HttpServer();
        final NetworkListener listener =
                new NetworkListener("Grizzly",
                                    NetworkListener.DEFAULT_NETWORK_HOST,
                                    PORT);
        final int LENGTH = 65536;
        final int MAX_LENGTH = LENGTH * 10;
        listener.setMaxPendingBytes(MAX_LENGTH);
        server.addListener(listener);
        final FutureImpl<String> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private final StringBuilder sb = new StringBuilder();
            
            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                        .uri("/path").protocol(Protocol.HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {

                HttpContent message = (HttpContent) ctx.getMessage();
                Buffer b = message.getContent();
                if (b.hasRemaining()) {
                    sb.append(b.toStringContent());
                }

                if (message.isLast()) {
                    parseResult.result(sb.toString());
                }
                return ctx.getStopAction();
            }
        });


        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setFilterChain(filterChainBuilder.build());
        final AtomicInteger writeCounter = new AtomicInteger();
        final HttpHandler ga = new HttpHandler() {

            @Override
            public void service(final Request request, final Response response) throws Exception {
                
                clientTransport.pause();
                response.setContentType("text/plain");
                final NIOOutputStream out = response.getOutputStream();
                
                // in order to enable direct writes - set the buffer size less than byte[] length
                response.setBufferSize(LENGTH / 8);

                final byte[] b = new byte[LENGTH];
                
                int i = 0;
                while (out.canWrite()) {
                    Arrays.fill(b, (byte) ('a' + (i++ % ('z' - 'a'))));
                    writeCounter.addAndGet(b.length);
                    out.write(b);
                }

                clientTransport.resume();
            }
        };


        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                String resultStr = parseResult.get(10, TimeUnit.SECONDS);
                assertEquals(writeCounter.get(), resultStr.length());
                check1(resultStr, LENGTH);
                
            } finally {
                LOGGER.log(Level.INFO, "Written {0}", writeCounter);
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    public void testWritePossibleReentrants() throws Exception {

        final HttpServer server = new HttpServer();
        final NetworkListener listener =
                new NetworkListener("Grizzly",
                                    NetworkListener.DEFAULT_NETWORK_HOST,
                                    PORT);
        server.addListener(listener);
        
        final FutureImpl<HttpHeader> parseResult = SafeFutureImpl.<HttpHeader>create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                        .uri("/path").protocol(Protocol.HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                final HttpPacket message = ctx.getMessage();
                final HttpHeader header = message.isHeader() ?
                        (HttpHeader) message :
                        ((HttpContent) message).getHttpHeader();
                
                parseResult.result(header);
                
                return ctx.getStopAction();
            }
        });
        
        final int maxAllowedReentrants = Writer.Reentrant.getMaxReentrants();
        final AtomicInteger maxReentrantsNoticed = new AtomicInteger();

        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setFilterChain(filterChainBuilder.build());
        final HttpHandler ga = new HttpHandler() {

            int reentrants = maxAllowedReentrants * 3;
            final ThreadLocal<Integer> reentrantsCounter = new ThreadLocal<Integer>() {

                @Override
                protected Integer initialValue() {
                    return -1;
                }
            };
            
            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.suspend();
                
                //clientTransport.pause();
                final NIOOutputStream outputStream = response.getOutputStream();
                reentrantsCounter.set(0);
                
                try {
                    outputStream.notifyWritePossible(new WriteHandler() {

                        @Override
                        public void onWritePossible() throws Exception {
                            if (reentrants-- >= 0) {
                                final int reentrantNum = reentrantsCounter.get() + 1;
                                
                                try {
                                    reentrantsCounter.set(reentrantNum);

                                    if (reentrantNum > maxReentrantsNoticed.get()) {
                                        maxReentrantsNoticed.set(reentrantNum);
                                    }

                                    outputStream.notifyWritePossible(this);
                                } finally {
                                    reentrantsCounter.set(reentrantNum - 1);
                                }
                            } else {
                                finish(200);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            finish(500);
                        }

                        private void finish(int code) {
                            response.setStatus(code);
                            response.resume();
                        }
                    });
                } finally {
                    reentrantsCounter.remove();
                }
            }
        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                final HttpHeader header = parseResult.get(10, TimeUnit.SECONDS);
                assertEquals(200, ((HttpResponsePacket) header).getStatus());

                assertTrue("maxReentrantNoticed=" + maxReentrantsNoticed + " maxAllowed=" + maxAllowedReentrants,
                        maxReentrantsNoticed.get() <= maxAllowedReentrants);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    public void testWritePossibleNotification() throws Exception {
        final int NOTIFICATIONS_NUM = 5;
        final int LENGTH = 8192;
        
        final AtomicInteger sentBytesCount = new AtomicInteger();
        final AtomicInteger notificationsCount = new AtomicInteger();
        
        final HttpServer server = new HttpServer();
        final NetworkListener listener =
                new NetworkListener("Grizzly",
                                    NetworkListener.DEFAULT_NETWORK_HOST,
                                    PORT);
        server.addListener(listener);
        
        final FutureImpl<Integer> parseResult = SafeFutureImpl.<Integer>create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {
            private int bytesRead;

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                final HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET")
                        .uri("/path").protocol(Protocol.HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = (HttpContent) ctx.getMessage();
                Buffer b = message.getContent();
                final int remaining = b.remaining();
                
                if (b.hasRemaining()) {
                    try {
                        check(b.toStringContent(), bytesRead % LENGTH, remaining, LENGTH);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }
                    
                    bytesRead += remaining;
                }
                
                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });
        
        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setFilterChain(filterChainBuilder.build());
        final HttpHandler ga = new HttpHandler() {

            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.suspend();
                
                final NIOOutputStream outputStream = response.getOutputStream();
                outputStream.notifyWritePossible(new WriteHandler() {

                    @Override
                    public void onWritePossible() throws Exception {
                        clientTransport.pause();
                        
                        try {
                            while (outputStream.canWrite()) {
                                byte[] b = new byte[LENGTH];
                                fill(b);
                                outputStream.write(b);
                                outputStream.flush();
                                sentBytesCount.addAndGet(LENGTH);
                            }

                            if (notificationsCount.incrementAndGet() < NOTIFICATIONS_NUM) {
                                outputStream.notifyWritePossible(this);
                            } else {
                                finish(200);
                            }
                        } finally {
                            clientTransport.resume();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        finish(500);
                    }
                    
                    private void finish(int code) {
                        response.setStatus(code);
                        response.resume();
                    }
                });
            }
        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                final int responseContentLength =
                        parseResult.get(10, TimeUnit.SECONDS);
                
                assertEquals(NOTIFICATIONS_NUM, notificationsCount.get());
                assertEquals(sentBytesCount.get(), responseContentLength);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }

    /**
     * Make sure postponed async write failure from one request will not impact
     * other request, that reuses the same OutputBuffer.
     * 
     * https://java.net/jira/browse/GRIZZLY-1536
     */
    @SuppressWarnings("unchecked")
    public void testPostponedAsyncFailure() throws Exception {
        final HttpServer server = new HttpServer();
        final NetworkListener listener =
                new NetworkListener("Grizzly",
                                    NetworkListener.DEFAULT_NETWORK_HOST,
                                    PORT);
        final TCPNIOTransport transport = listener.getTransport();
        transport.setIOStrategy(WorkerThreadIOStrategy.getInstance());
        transport.setWorkerThreadPoolConfig(ThreadPoolConfig
                .newConfig().setCorePoolSize(1).setMaxPoolSize(1));
        server.addListener(listener);
        
        final AtomicReference<Connection> connectionToClose =
                new AtomicReference<Connection>();
        final FutureImpl<Boolean> floodReached = Futures.<Boolean>createSafeFuture();
        final FutureImpl<HttpContent> result = Futures.<HttpContent>createSafeFuture();
        
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {
            final AtomicBoolean isFirstConnectionInputBlocked = new AtomicBoolean();
            @Override
            public NextAction handleRead(final FilterChainContext ctx)
                    throws IOException {
                
                if (isFirstConnectionInputBlocked.compareAndSet(false, true)) {
                    return ctx.getSuspendAction();
                }
                
                final HttpContent httpContent = ctx.getMessage();
                if (httpContent.isLast()) {
                    result.result(httpContent);
                    return ctx.getStopAction();
                }
                
                return ctx.getStopAction(httpContent);
            }
        });
        
        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setFilterChain(filterChainBuilder.build());
        final HttpHandler floodHttpHandler = new HttpHandler() {

            @Override
            public void service(final Request request, final Response response) throws Exception {
                connectionToClose.set(request.getContext().getConnection());
                floodReached.result(Boolean.TRUE);
                
                final NIOOutputStream outputStream = response.getOutputStream();
                
                try {
                    while (outputStream.canWrite()) {
                        byte[] b = new byte[4096];
                        outputStream.write(b);
                        outputStream.flush();
                        Thread.sleep(20);
                    }
                } catch (Exception e) {
                    result.failure(e);
                }
            }
        };

        final String checkString = "Check#";
        String checkPattern = "";
        for (int i = 0; i < 10; i++) {
            checkPattern += (checkString + i);
        }
        
        final HttpHandler controlHttpHandler = new HttpHandler() {

            @Override
            public void service(final Request request, final Response response) throws Exception {
                connectionToClose.get().closeSilently();
                Thread.sleep(20); // give some time to close the connection
                try {
                    final NIOWriter writer = response.getWriter();
                    for (int i = 0; i < 10; i++) {
                        writer.write(checkString + i);
                        writer.flush();
                        Thread.sleep(20);
                    }
                } catch (Exception e) {
                    result.failure(e);
                }
            }
        };

        server.getServerConfiguration().addHttpHandler(floodHttpHandler, "/flood");
        server.getServerConfiguration().addHttpHandler(controlHttpHandler, "/control");

        try {
            server.start();
            clientTransport.start();

            final Future<Connection> connect1Future = clientTransport.connect(
                    "localhost", PORT);
            final Connection connection1 = connect1Future.get(10, TimeUnit.SECONDS);
            // Build the HttpRequestPacket, which will be sent to a server
            // We construct HTTP request version 1.1 and specifying the URL
            final HttpRequestPacket httpRequest1 = HttpRequestPacket.builder().method("GET")
                    .uri("/flood").protocol(Protocol.HTTP_1_1)
                    .header("Host", "localhost:" + PORT).build();

            // Write the request asynchronously
            connection1.write(httpRequest1);

            assertTrue(floodReached.get(10, TimeUnit.SECONDS));
            
            final Future<Connection> connect2Future = clientTransport.connect(
                    "localhost", PORT);
            final Connection connection2 = connect2Future.get(10, TimeUnit.SECONDS);
            // Build the HttpRequestPacket, which will be sent to a server
            // We construct HTTP request version 1.1 and specifying the URL
            final HttpRequestPacket httpRequest2 = HttpRequestPacket.builder().method("GET")
                    .uri("/control").protocol(Protocol.HTTP_1_1)
                    .header("Host", "localhost:" + PORT).build();

            // Write the request asynchronously
            connection2.write(httpRequest2);
            
            final HttpContent httpContent = result.get(30, TimeUnit.SECONDS);
            
            assertEquals(checkPattern, httpContent.getContent().toStringContent());
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    private static void fill(byte[] array) {
        for (int i=0; i<array.length; i++) {
            array[i] = (byte) ('a' + i % ('z' - 'a'));
        }
    }

    private static void fill(char[] array) {
        for (int i=0; i<array.length; i++) {
            array[i] = (char) ('a' + i % ('z' - 'a'));
        }
    }

//    private static void check(String s, int lastCameSize, int bufferSize) {
//        check(s, 0, lastCameSize, bufferSize);
//    }

    private static void check(String s, int offset, int lastCameSize, int bufferSize) {
        final int start = s.length() - lastCameSize;

        for (int i=0; i<lastCameSize; i++) {
            final char c = s.charAt(start + i);
            final char expect = (char) ('a' + ((i + start + offset) % bufferSize) % ('z' - 'a'));
            if (c != expect) {
                throw new IllegalStateException("Result at [" + (i + start) + "] don't match. Expected=" + expect + " got=" + c);
            }
        }
    }

    private void check1(final String resultStr, final int LENGTH) {
        for (int i = 0; i < resultStr.length() / LENGTH; i++) {
            final char expect = (char) ('a' + (i % ('z' - 'a')));
            for (int j = 0; j < LENGTH; j++) {
                final char charAt = resultStr.charAt(i * LENGTH + j);
                if (charAt != expect) {
                    throw new IllegalStateException("Result at [" + (i * LENGTH + j) + "] don't match. Expected=" + expect + " got=" + charAt);
                }
            }
        }
    }
    
    private static final class CustomException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
