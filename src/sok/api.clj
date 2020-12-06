(ns sok.api
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import (io.netty.bootstrap ServerBootstrap)
           (io.netty.channel ChannelHandlerContext
                             ChannelInitializer)
           (io.netty.channel SimpleChannelInboundHandler)
           (io.netty.channel.nio NioEventLoopGroup)
           (io.netty.channel.socket SocketChannel)
           (io.netty.channel.socket.nio NioServerSocketChannel)
           (java.net InetSocketAddress)
           (io.netty.handler.codec.http.websocketx WebSocketServerProtocolHandler
                                                   TextWebSocketFrame WebSocketFrameAggregator)
           (io.netty.handler.codec.http HttpServerCodec
                                        HttpObjectAggregator)
           (java.io Closeable)))

(defn ws-handler []
  (proxy [SimpleChannelInboundHandler] []
    (channelRead0 [^ChannelHandlerContext ctx
                   ^TextWebSocketFrame frame]
      (do (println "server received" ctx (count (.text frame)))
        (-> ctx .channel (.writeAndFlush (.retain frame)))))))

(defn pipeline [^String path {:keys [max-frame-size max-message-size]
                              :or {max-frame-size (* 64 1024) ; TODO actually plug in
                                   max-message-size (* 1024 1024)}
                              :as opts}]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel ch]
      (doto (.pipeline ch)
        (.addLast "http" (HttpServerCodec.))
        (.addLast "http-agg" (HttpObjectAggregator. (* 64 1024)))
        (.addLast "ws" (WebSocketServerProtocolHandler. path))
        (.addLast "ws-agg" (WebSocketFrameAggregator. max-message-size))
        (.addLast "ws-handler" (ws-handler))))))
        ; TODO per message deflate?)

(defn server!
  ([port] (server! port "/" nil))
  ([port path opts]
   (let [group (NioEventLoopGroup.)] ; TODO look at aleph for epoll, thread number specification
     (try (let [bs (doto (ServerBootstrap.)
                     (.group group) ; TODO any need for separate parent and child groups?
                     (.channel NioServerSocketChannel)
                     (.localAddress ^int (InetSocketAddress. port))
                     (.childHandler (pipeline path opts)))
                ch (-> bs .bind .sync .channel)]
            (reify
              Closeable
              (close [_]
                (do (some-> ch .close)
                    (-> group .shutdownGracefully)))
              Object
              (toString [_]
                (format "Sok[port:%d, path:%s]" port path))))
          (catch Exception e
            (-> group .shutdownGracefully .sync)
            (throw e))))))

#_ (def server (server! 8123))
#_ (.close server)