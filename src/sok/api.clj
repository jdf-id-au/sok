(ns sok.api
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import (io.netty.bootstrap ServerBootstrap)
           (io.netty.channel ChannelHandlerContext
                             ChannelInitializer)
           (io.netty.channel SimpleChannelInboundHandler)
           (io.netty.channel.nio NioEventLoopGroup)
           (io.netty.channel.group ChannelGroup)
           (io.netty.channel.socket SocketChannel)
           (io.netty.channel.socket.nio NioServerSocketChannel)
           (java.net InetSocketAddress)
           (io.netty.handler.codec.http.websocketx WebSocketServerProtocolHandler
                                                   TextWebSocketFrame WebSocketFrameAggregator)
           (io.netty.handler.codec.http HttpServerCodec
                                        HttpObjectAggregator)
           (java.io Closeable)
           (io.netty.channel.group DefaultChannelGroup)
           (io.netty.util.concurrent GlobalEventExecutor)))

(defn ws-handler [clients]
  (proxy [SimpleChannelInboundHandler] []
    (channelActive [^ChannelHandlerContext ctx] ; NB confused about @Skip annotation in superclass
      (let [ch (.channel ctx)]
        (.add clients ch)
        (proxy-super channelActive ctx)))
    (channelRead0 [^ChannelHandlerContext ctx
                   ^TextWebSocketFrame frame]
      (let [ch (.channel ctx)
            msg (str "server received" (count (.text frame)) "bytes from"
                  (.remoteAddress ch) "on channel id" (.id ch))]
        (.writeAndFlush ch (TextWebSocketFrame. msg))))))

(defn pipeline [^String path {:keys [max-frame-size max-message-size]
                              :or {max-frame-size (* 64 1024) ; TODO actually plug in
                                   max-message-size (* 1024 1024)}
                              :as opts}
                ^ChannelGroup clients]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel ch]
      ; TODO add channel to client registry, allow gathering metadata (e.g. logged in user)
      ; TODO add listener to (.closeFuture ch) to  remove channel from client registry
      (doto (.pipeline ch)
        (.addLast "http" (HttpServerCodec.))
        (.addLast "http-agg" (HttpObjectAggregator. (* 64 1024))) ; probably not necessary?
        (.addLast "ws" (WebSocketServerProtocolHandler. path))
        (.addLast "ws-agg" (WebSocketFrameAggregator. max-message-size))
        (.addLast "ws-handler" (ws-handler clients))))))
        ; TODO per message deflate?)

(defprotocol SokServer "Connect Netty websocket server with core.async channels."
  (in [this] "Channel for incoming messages.")
  (out [this] "Channel for outgoing messages.")
  (clients [this] "Client registry."))

(defn server!
  ([port] (server! port "/" nil))
  ([port path opts]
   (let [loop-group (NioEventLoopGroup.) ; TODO look at aleph for epoll, thread number specification
         clients (DefaultChannelGroup. GlobalEventExecutor/INSTANCE)] ; single threaded executor for group actions
     (try (let [bootstrap (doto (ServerBootstrap.)
                            (.group loop-group) ; TODO any need for separate parent and child groups?
                            (.channel NioServerSocketChannel)
                            (.localAddress ^int (InetSocketAddress. port))
                            (.childHandler (pipeline path opts clients)))
                server-channel (-> bootstrap .bind .sync)]
            (reify
              Closeable
              (close [_]
                (do (some-> server-channel .close)
                    ; TODO should shut down client channels? see `ChannelGroup`
                    (-> loop-group .shutdownGracefully)))
              Object
              (toString [_]
                (format "Sok[port:%d, path:%s]" port path))
              SokServer
              (in [_])
              (out [_])
              (clients [_])))
          (catch Exception e
            (-> loop-group .shutdownGracefully .sync)
            (throw e))))))

#_ (def server (server! 8123))
#_ (.close server)