/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.FutureMono;
import reactor.netty.NettyOutbound;
import reactor.netty.ReactorNetty;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.util.annotation.Nullable;

import java.net.URI;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 * @author Simon Baslé
 */
final class WebsocketClientOperations extends HttpClientOperations
        implements WebsocketInbound, WebsocketOutbound {

    final WebSocketClientHandshaker handshaker;

    volatile int closeSent;

    WebsocketClientOperations(URI currentURI,
                              String protocols,
                              int maxFramePayloadLength,
                              HttpClientOperations replaced) {
        super(replaced);
        Channel channel = channel();

        handshaker = WebSocketClientHandshakerFactory.newHandshaker(currentURI,
                WebSocketVersion.V13,
                protocols.isEmpty() ? null : protocols,
                true,
                replaced.requestHeaders()
                        .remove(HttpHeaderNames.HOST),
                maxFramePayloadLength);

        handshaker.handshake(channel)
                .addListener(f -> {
                    markPersistent(false);
                    channel.read();
                });
    }

    @Override
    public HttpHeaders headers() {
        return responseHeaders();
    }

    @Override
    public boolean isWebsocket() {
        return true;
    }

    @Override
    public String selectedSubprotocol() {
        return handshaker.actualSubprotocol();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onInboundNext(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpResponse) {
            started = true;
            channel().pipeline()
                    .remove(HttpObjectAggregator.class);
            FullHttpResponse response = (FullHttpResponse) msg;

            setNettyResponse(response);

            if (notRedirected(response)) {


                try {
                    handshaker.finishHandshake(channel(), response);
                    listener().onStateChange(this, HttpClientState.RESPONSE_RECEIVED);
                } catch (Exception e) {
                    onInboundError(e);
                } finally {
                    //Release unused content (101 status)
                    response.content()
                            .release();
                }

            }
            return;
        }
//        if (msg instanceof PingWebSocketFrame) {
//            ctx.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) msg).content()));
//            ctx.read();
//            return;
//        }

        if (log.isDebugEnabled()) {
            log.debug("WebsocketClientOperations Handler Msg Type => " + msg.getClass().getSimpleName());
        }

        //is used gateway?
        try {
            this.getClass().getClassLoader().loadClass("org.springframework.web.reactive.socket.adapter.ReactorNettyWebSocketSession");
        } catch (ClassNotFoundException e) {
            if (msg instanceof PingWebSocketFrame) {
                channel().writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) msg).content()));
                ctx.read();
                return;
            }
        }

        if (msg instanceof CloseWebSocketFrame &&
                ((CloseWebSocketFrame) msg).isFinalFragment()) {
            if (log.isDebugEnabled()) {
                log.debug(format(channel(), "CloseWebSocketFrame detected. Closing Websocket"));
            }
            CloseWebSocketFrame close = (CloseWebSocketFrame) msg;
            sendCloseNow(new CloseWebSocketFrame(true,
                    close.rsv(),
                    close.content()));
            onInboundComplete();
        } else if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
            super.onInboundNext(ctx, msg);
        }
    }

    @Override
    protected void onInboundCancel() {
        if (log.isDebugEnabled()) {
            log.debug(format(channel(), "Cancelling Websocket inbound. Closing Websocket"));
        }
        sendCloseNow(null);
    }

    @Override
    protected void onInboundClose() {
        terminate();
    }

    @Override
    protected void onOutboundComplete() {
    }

    @Override
    protected void onOutboundError(Throwable err) {
        if (channel().isActive()) {
            if (log.isDebugEnabled()) {
                log.debug(format(channel(), "Outbound error happened"), err);
            }
            sendCloseNow(new CloseWebSocketFrame(1002, "Client internal error"));
        }
    }

    @Override
    public NettyOutbound send(Publisher<? extends ByteBuf> dataStream) {
        return sendObject(Flux.from(dataStream).map(bytebufToWebsocketFrame));
    }

    @Override
    public Mono<Void> sendClose() {
        return sendClose(new CloseWebSocketFrame());
    }

    @Override
    public Mono<Void> sendClose(int rsv) {
        return sendClose(new CloseWebSocketFrame(true, rsv));
    }

    @Override
    public Mono<Void> sendClose(int statusCode, @javax.annotation.Nullable String reasonText) {
        return sendClose(new CloseWebSocketFrame(statusCode, reasonText));
    }

    @Override
    public Mono<Void> sendClose(int rsv, int statusCode, @javax.annotation.Nullable String reasonText) {
        return sendClose(new CloseWebSocketFrame(true, rsv, statusCode, reasonText));
    }

    Mono<Void> sendClose(CloseWebSocketFrame frame) {
        if (CLOSE_SENT.get(this) == 0) {
            //commented for now as we assume the close is always scheduled (deferFuture runs)
            //onTerminate().subscribe(null, null, () -> ReactorNetty.safeRelease(frame));
            return FutureMono.deferFuture(() -> {
                if (CLOSE_SENT.getAndSet(this, 1) == 0) {
                    discard();
                    return channel().writeAndFlush(frame)
                            .addListener(ChannelFutureListener.CLOSE);
                }
                frame.release();
                return channel().newSucceededFuture();
            }).doOnCancel(() -> ReactorNetty.safeRelease(frame));
        }
        frame.release();
        return Mono.empty();
    }

    void sendCloseNow(@Nullable CloseWebSocketFrame frame) {
        if (frame != null && !frame.isFinalFragment()) {
            channel().writeAndFlush(frame);
            return;
        }
        if (CLOSE_SENT.getAndSet(this, 1) == 0) {
            channel().writeAndFlush(frame == null ? new CloseWebSocketFrame() : frame)
                    .addListener(ChannelFutureListener.CLOSE);
        } else if (frame != null) {
            frame.release();
        }
    }

    static final AtomicIntegerFieldUpdater<WebsocketClientOperations> CLOSE_SENT =
            AtomicIntegerFieldUpdater.newUpdater(WebsocketClientOperations.class,
                    "closeSent");
}
