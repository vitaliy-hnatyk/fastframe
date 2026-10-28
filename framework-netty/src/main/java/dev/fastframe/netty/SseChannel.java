package dev.fastframe.netty;
import dev.fastframe.core.http.SseEmitter;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
final class SseChannel implements SseEmitter{
 private final Channel ch;private final AtomicBoolean closed=new AtomicBoolean();private volatile Runnable cleanup=()->{};
 SseChannel(Channel channel){ch=channel;ch.closeFuture().addListener(f->terminate());}
 public boolean send(String event,String data){
  if(event==null||data==null||event.indexOf('\n')>=0||event.indexOf('\r')>=0)throw new IllegalArgumentException("invalid SSE event");
  if(closed.get()||!ch.isActive()||!ch.isWritable()){close();return false;}
  StringBuilder payload=new StringBuilder();if(!event.isEmpty())payload.append("event: ").append(event).append('\n');
  for(String line:data.split("\\r?\\n",-1))payload.append("data: ").append(line).append('\n');
  payload.append('\n');
  Runnable action=()->{if(closed.get()||!ch.isWritable()){close();return;}
   ch.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(payload,StandardCharsets.UTF_8))).addListener(f->{if(!f.isSuccess())close();});};
  if(ch.eventLoop().inEventLoop())action.run();else ch.eventLoop().execute(action);return true;
 }
 public void onClose(Runnable callback){if(callback==null)throw new NullPointerException("callback");cleanup=callback;if(closed.get())callback.run();}
 private void terminate(){if(closed.compareAndSet(false,true))cleanup.run();}
 public void close(){if(closed.compareAndSet(false,true)){
  Runnable task=()->ch.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(f->ch.close());
  if(ch.eventLoop().inEventLoop())task.run();else ch.eventLoop().execute(task);cleanup.run();
 }}
}
