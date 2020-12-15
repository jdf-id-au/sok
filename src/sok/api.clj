(ns sok.api
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go-loop chan <!! <! >! put! close!]]
            [hato.websocket :as hws])
  (:import (io.netty.bootstrap ServerBootstrap)
           (io.netty.channel ChannelHandlerContext
                             ChannelInitializer)
           (io.netty.channel SimpleChannelInboundHandler ChannelFutureListener ChannelHandler)
           (io.netty.channel.nio NioEventLoopGroup)
           (io.netty.channel.group ChannelGroup)
           (io.netty.channel.socket SocketChannel)
           (io.netty.channel.socket.nio NioServerSocketChannel)
           (java.net InetSocketAddress)
           (io.netty.handler.codec.http.websocketx WebSocketServerProtocolHandler
                                                   WebSocketServerProtocolConfig
                                                   TextWebSocketFrame WebSocketFrameAggregator
                                                   CorruptedWebSocketFrameException)
           (io.netty.handler.codec.http HttpServerCodec
                                        HttpObjectAggregator)
           (io.netty.channel.group DefaultChannelGroup)
           (io.netty.util.concurrent GlobalEventExecutor)
           (io.netty.handler.codec TooLongFrameException)))

(defn ^ChannelHandler ws-handler
  "Register websocket channel opening, and forward incoming text messages to `in` core.async chan.
   Server returns `clients` map atom which can be watched and enriched with additional metadata in application."
  [channel-group clients in]
  (proxy [SimpleChannelInboundHandler] []
    (channelActive [^ChannelHandlerContext ctx]
      (let [ch (.channel ctx)
            id (.id ch)
            cf (.closeFuture ch)]
        (try (.add channel-group ch)
             (swap! clients assoc id {:addr (.remoteAddress ch)})
             (.addListener cf (proxy [ChannelFutureListener] []
                                (operationComplete [_]
                                  (swap! clients dissoc id))))
             (catch Exception e
               (log/error e "Unable to register channel" ch)))
        (.fireChannelActive ctx))) ; from ChannelInboundHandlerAdapter, although annotated @Skip ..?
    (channelRead0 [^ChannelHandlerContext ctx
                   ^TextWebSocketFrame frame]
      (let [ch (.channel ctx)
            id (.id ch)
            text (.text frame)]
        #_(log/debug "received" (count (.text frame)) "characters from"
            (.remoteAddress ch) "on channel id" (.id ch))
        (if-not (put! in [id text])
          (log/error "Dropped incoming message because in chan is closed" text))))
    (exceptionCaught [^ChannelHandlerContext ctx
                      ^Throwable cause]
      (condp instance? cause
        ; Actually when max *message* length is exceeded:
        TooLongFrameException (log/warn (type cause) (.getMessage cause))
        ; Max frame length exceeded:
        CorruptedWebSocketFrameException (log/warn (type cause) (.getMessage cause))
        (log/error cause)))))

(defn pipeline
  [^String path {:keys [^int max-frame-size max-message-size]
                 :or {max-frame-size (* 64 1024)
                      max-message-size (* 1024 1024)}
                 :as opts}
   ^ChannelGroup channel-group clients in]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel ch]
      (doto (.pipeline ch)
        (.addLast "http" (HttpServerCodec.))
        ; probably not necessary?
        (.addLast "http-agg" (HttpObjectAggregator. (* 64 1024)))
        (.addLast "ws" (WebSocketServerProtocolHandler.
                         path nil false max-frame-size 10000 ; compiler can't find static field??:
                         #_WebSocketServerProtocolConfig/DEFAULT_HANDSHAKE_TIMEOUT_MILLIS))
        (.addLast "ws-agg" (WebSocketFrameAggregator. max-message-size))
        (.addLast "ws-handler" (ws-handler channel-group clients in))))))
        ; TODO per message deflate?)))

(defn server!
  ([port] (server! port "/" nil))
  ([port path opts]
   (let [; TODO look at aleph for epoll, thread number specification
         loop-group (NioEventLoopGroup.)
         ; single threaded executor is for group actions
         channel-group (DefaultChannelGroup. GlobalEventExecutor/INSTANCE)
         ; channel-group tracks channels but is not flexible enough for client metadata
         ; therefore store metadata in parallel atom:
         clients (atom {})
         in (chan)
         out (chan)
         _ (go-loop []
             (if-let [[id ^String msg] (<! out)]
               (let [ch (.find channel-group id)]
                 #_(log/debug "about to write" (count msg) "characters to"
                     (.remoteAddress ch) "on channel id" (.id ch))
                 (if ch (.writeAndFlush ch (TextWebSocketFrame. msg))
                        (log/info "Dropped outgoing message because websocket is closed"
                          id (get @clients id) msg))
                 (recur))
               (log/info "Stopped sending messages")))
         evict (fn [id] (some-> channel-group (.find id) .close))]
     (try (let [bootstrap (doto (ServerBootstrap.)
                            ; TODO any need for separate parent and child groups?
                            (.group loop-group)
                            (.channel NioServerSocketChannel)
                            (.localAddress ^int (InetSocketAddress. port))
                            (.childHandler (pipeline path opts channel-group clients in)))
                server-channel (-> bootstrap .bind .sync)]
            {:close (fn [] (close! out)
                           (some-> server-channel .channel .close .sync)
                           (-> channel-group .close .awaitUninterruptibly)
                           (close! in)
                           (-> loop-group .shutdownGracefully))
             :port port :path path :in in :out out :clients clients :evict evict})
          (catch Exception e
            (close! out)
            (close! in)
            (-> loop-group .shutdownGracefully .sync)
            (throw e))))))

(defn client!
  [uri]
  (let [raw-in (chan)
        in (chan)
        _ (go-loop [agg ""]
            (if-let [[frame last?] (<! raw-in)]
              (let [ret (str agg frame)]
                (if last?
                  (if (>! in ret)
                    (recur "")
                    (log/error "Dropped incoming message because in chan is closed"))
                  (recur ret)))
              (log/warn "Aggregator chan is closed")))
        out (chan)
        ws @(hws/websocket uri
              {:on-message
               (fn [ws frame last?]
                 (if-not (put! raw-in [frame last?])
                   (log/error "Dropped incoming message because aggregator chan is closed" frame)))
               :on-close
               (fn [ws status reason]
                 ; Status codes https://tools.ietf.org/html/rfc6455#section-7.4.1
                 (log/info "Websocket closed" status (case reason "" "" (str "because " reason))))})
              ;:on-error ; TODO
        _ (go-loop []
            (if-let [msg (<! out)]
              (do #_(log/debug "about to send" (count msg) "characters from client")
                (hws/send! ws msg)
                (recur))
              (log/info "Stopped sending messages")))]
    {:ws ws :in in :out out}))