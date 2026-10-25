package dev.fastframe.application;

/** Registration occurs for all providers before any boot callback is invoked. */
public interface ServiceProvider {
    void register(AppContext context) throws Exception;
    default void boot(AppContext context) throws Exception { }
}
