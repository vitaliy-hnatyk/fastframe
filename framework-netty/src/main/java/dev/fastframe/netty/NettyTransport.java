package dev.fastframe.netty;
public record NettyTransport(TlsSettings tls,boolean h2cPriorKnowledge){
 public NettyTransport{if(tls!=null&&h2cPriorKnowledge)throw new IllegalArgumentException("h2c requires separate cleartext listener");}
 public static NettyTransport http11(){return new NettyTransport(null,false);}
 public static NettyTransport https(TlsSettings tls){return new NettyTransport(tls,false);}
 public static NettyTransport h2c(){return new NettyTransport(null,true);}
}
