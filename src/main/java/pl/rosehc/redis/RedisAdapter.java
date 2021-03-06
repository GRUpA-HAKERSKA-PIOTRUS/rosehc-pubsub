package pl.rosehc.redis;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import pl.rosehc.redis.callback.Callback;
import pl.rosehc.redis.callback.CallbackFactory;
import pl.rosehc.redis.callback.CallbackFuture;
import pl.rosehc.redis.callback.CallbackPacket;
import pl.rosehc.redis.packet.Packet;
import pl.rosehc.redis.packet.PacketCoderHelper;
import pl.rosehc.redis.packet.PacketHandler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

public final class RedisAdapter {

  private static final AtomicInteger REDIS_SUBSCRIBER_COUNT = new AtomicInteger();
  private static final ExecutorService REDIS_SUBSCRIBER_EXECUTOR = Executors.newCachedThreadPool(
      runnable -> {
        final Thread thread = new Thread(runnable);
        thread.setName("REDIS-SUBSCRIBER-" + REDIS_SUBSCRIBER_COUNT.incrementAndGet());
        thread.setDaemon(true);
        return thread;
      });

  private final CallbackFactory callbackFactory;
  private final JedisPool pool;
  private final Map<String, RedisPubSubAdapter> adapterMap;

  public RedisAdapter(CallbackFactory callbackFactory, JedisPool pool) {
    this.callbackFactory = callbackFactory;
    this.pool = pool;
    this.adapterMap = new ConcurrentHashMap<>();
  }

  public void disconnect() {
    try {
      this.pool.destroy();
      this.adapterMap.values().forEach(RedisPubSubAdapter::unsubscribe);
      this.adapterMap.clear();
      REDIS_SUBSCRIBER_EXECUTOR.shutdown();
      //noinspection ResultOfMethodCallIgnored
      REDIS_SUBSCRIBER_EXECUTOR.awaitTermination(1L, TimeUnit.MINUTES);
    } catch (final InterruptedException ex) {
      throw new RuntimeException("Cannot destroy the redis connection pool.", ex);
    }
  }

  public void subscribe(final PacketHandler packetHandler, final String topic,
      final List<Class<? extends Packet>> packetClasses) {
    this.subscribe(packetHandler, Collections.singletonList(topic), packetClasses);
  }

  public void subscribe(final String topic,
      final List<Class<? extends Packet>> packetClasses) {
    this.subscribe(null, Collections.singletonList(topic), packetClasses);
  }

  public void subscribe(final PacketHandler packetHandler, final List<String> topics,
      final List<Class<? extends Packet>> packetClasses) {
    for (final Class<? extends Packet> packetClass : packetClasses) {
      this.subscribe0(packetHandler, packetClass, topics);
    }
  }

  private void subscribe0(final PacketHandler packetHandler,
      final Class<? extends Packet> packetClass, final List<String> topics) {
    for (final String channel : topics) {
      RedisPubSubAdapter adapter = this.adapterMap.get(channel);
      if (Objects.isNull(adapter)) {
        adapter = new RedisPubSubAdapter();
        REDIS_SUBSCRIBER_EXECUTOR.execute(new RedisPubSubAdapterUpdateTask(this.pool, adapter,
            channel.getBytes(StandardCharsets.UTF_8)));
        this.adapterMap.put(channel, adapter);
      }

      adapter.registerHandler(packetClass, packet -> {
        if (packet instanceof final CallbackPacket callbackPacket) {
          if (!callbackPacket.isResponse()) {
            var handleResponse = packet.handle(packetHandler);
            if(handleResponse != null) {
              if(handleResponse instanceof CallbackPacket responseCallbackPacket) {
                responseCallbackPacket.setResponse(true);
                responseCallbackPacket.setCallbackId(callbackPacket.getCallbackId());
              }
              sendPacket(handleResponse, topics.toArray(String[]::new));
            }
            return;
          }

          final Callback callback = callbackFactory
              .findCallbackById(callbackPacket.getCallbackId());
          if (Objects.isNull(callback)) {
            return;
          }

          if (callbackPacket.hasSucceeded()) {
            callback.done(callbackPacket);
            return;
          }

          callback.error(callbackPacket.getResponseText());
          return;
        }

        var handleResponse = packet.handle(packetHandler);
        if(handleResponse != null)
          sendPacket(handleResponse, topics.toArray(String[]::new));
      });
    }
  }

  public void sendPacket(final Packet packet, final String... topics) {
    try (final Jedis jedis = this.pool.getResource()) {
      final Pipeline transaction = jedis.pipelined();

      final byte[] data = PacketCoderHelper.writeValue(packet);
      for (final String topic : topics) {
        transaction.publish(topic.getBytes(StandardCharsets.UTF_8), data);
      }

      transaction.sync();
    }
  }

  public void sendPacket(final CallbackPacket callbackPacket, final Callback callback,
      final String... topics) {
    sendPacket(callbackPacket, topics);
    callbackFactory.addCallback(callbackPacket.getCallbackId(), callback);
  }

  public <T extends CallbackPacket> CompletableFuture<T> sendFuturePacket(final CallbackPacket callbackPacket, final String... topics) {
    var future = new CompletableFuture<T>();

    sendPacket(callbackPacket, topics);
    callbackFactory.addCallback(callbackPacket.getCallbackId(), new CallbackFuture<>(future));

    return future;
  }

}
