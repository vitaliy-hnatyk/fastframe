package dev.fastframe.netty;
import dev.fastframe.core.http.WebSocketHandler;
import io.netty.channel.*;
import io.netty.buffer.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.concurrent.*;
final class WebSocketFrames extends SimpleChannelInboundHandler<WebSocketFrame>{
 private final WebSocketHandler application;
 WebSocketFrames(WebSocketHandler app){application=app;}
 @Override protected void channelRead0(ChannelHandlerContext ctx,WebSocketFrame frame){
  if(frame instanceof CloseWebSocketFrame){application.onClose();ctx.writeAndFlush(frame.retain()).addListener(ChannelFutureListener.CLOSE);}
  else if(frame instanceof PingWebSocketFrame ping)ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
  else if(frame instanceof PongWebSocketFrame){}
  else if(frame instanceof TextWebSocketFrame txt){
   try{CompletionStage<String> stage=application.onText(txt.text());if(stage==null)throw new IllegalStateException("null callback");
    stage.whenComplete((msg,error)->send(ctx,msg,error,false));
   }catch(Throwable ex){ctx.close();}
  }else if(frame instanceof BinaryWebSocketFrame bin){
   try{CompletionStage<byte[]> stage=application.onBinary(ByteBufUtil.getBytes(bin.content()));if(stage==null)throw new IllegalStateException("null callback");
    stage.whenComplete((bytes,error)->{
     Runnable action=()->{if(error!=null||bytes==null||!ctx.channel().isWritable())ctx.close();
       else ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)));};
     if(ctx.executor().inEventLoop())action.run();else ctx.executor().execute(action);
    });
   }catch(Throwable ex){ctx.close();}
  }else ctx.writeAndFlush(new CloseWebSocketFrame(1003,"Unsupported frame")).addListener(ChannelFutureListener.CLOSE);
 }
 private void send(ChannelHandlerContext ctx,String value,Throwable failure,boolean unused){
   Runnable action=()->{if(!ctx.channel().isActive())return;
    if(failure!=null||value==null||!ctx.channel().isWritable())ctx.close();
    else ctx.writeAndFlush(new TextWebSocketFrame(value));};
   if(ctx.executor().inEventLoop())action.run();else ctx.executor().execute(action);
 }
 @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable e){ctx.close();}
}
