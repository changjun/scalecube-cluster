package io.scalecube.transport.netty.websocket;

import io.netty.channel.ChannelOption;
import io.netty.util.ReferenceCountUtil;
import io.scalecube.cluster.transport.api.TransportConfig;
import io.scalecube.transport.netty.Receiver;
import io.scalecube.transport.netty.TransportImpl.ReceiverContext;
import java.net.InetSocketAddress;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;

final class WebsocketReceiver implements Receiver {

  private final TransportConfig config;

  WebsocketReceiver(TransportConfig config) {
    this.config = config;
  }

  @Override
  public Mono<DisposableServer> bind() {
    return Mono.deferWithContext(context -> Mono.just(context.get(ReceiverContext.class)))
        .flatMap(
            context ->
                newHttpServer(context)
                    .handle((request, response) -> onMessage(context, request, response))
                    .bind()
                    .cast(DisposableServer.class));
  }

  private HttpServer newHttpServer(ReceiverContext context) {
    return HttpServer.create()
        .tcpConfiguration(
            tcpServer ->
                tcpServer
                    .runOn(context.loopResources())
                    .bindAddress(() -> new InetSocketAddress(config.port()))
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.SO_REUSEADDR, true));
  }

  private Mono<Void> onMessage(
      ReceiverContext context,
      @SuppressWarnings("unused") HttpServerRequest request,
      HttpServerResponse response) {
    return response.sendWebsocket(
        (WebsocketInbound inbound, WebsocketOutbound outbound) ->
            inbound
                .receive()
                .retain()
                .doOnNext(
                    byteBuf -> {
                      if (!byteBuf.isReadable()) {
                        ReferenceCountUtil.safeRelease(byteBuf);
                        return;
                      }
                      context.onMessage(context.messageDecoder().apply(byteBuf));
                    })
                .then());
  }
}
