package dev.fastframe.netty;
import io.netty.handler.ssl.*;
import java.nio.file.Path;
import java.util.Objects;
public record TlsSettings(Path certificateChainPem, Path privateKeyPem, boolean http2) {
 public TlsSettings {Objects.requireNonNull(certificateChainPem);Objects.requireNonNull(privateKeyPem);}
 SslContext build() throws javax.net.ssl.SSLException {
  var builder=SslContextBuilder.forServer(certificateChainPem.toFile(),privateKeyPem.toFile()).protocols(SslProtocols.TLS_v1_3);
  if(http2) builder.applicationProtocolConfig(new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT, ApplicationProtocolNames.HTTP_2,ApplicationProtocolNames.HTTP_1_1));
  return builder.build();
 }
}
