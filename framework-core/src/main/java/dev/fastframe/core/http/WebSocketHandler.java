package dev.fastframe.core.http;
import java.util.concurrent.*;
@FunctionalInterface public interface WebSocketHandler {
 CompletionStage<String> onText(String text);
 default CompletionStage<byte[]> onBinary(byte[] data){return CompletableFuture.completedFuture(data);}
 default void onClose(){}
}
