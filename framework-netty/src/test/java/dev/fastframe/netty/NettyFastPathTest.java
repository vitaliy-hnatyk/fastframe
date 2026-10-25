package dev.fastframe.netty;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NettyFastPathTest {
    @Test void classifiesBinaryContentTypeWithoutSplitting() {
        assertTrue(NettyRequestHandler.isBinaryContentType("application/octet-stream"));
        assertTrue(NettyRequestHandler.isBinaryContentType("APPLICATION/OCTET-STREAM; charset=binary"));
        assertTrue(NettyRequestHandler.isBinaryContentType(" application/octet-stream  ;charset=binary"));
        assertFalse(NettyRequestHandler.isBinaryContentType("application/octet-stream-extra"));
        assertFalse(NettyRequestHandler.isBinaryContentType("application/json"));
        assertFalse(NettyRequestHandler.isBinaryContentType(null));
    }
}
