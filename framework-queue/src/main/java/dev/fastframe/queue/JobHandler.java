package dev.fastframe.queue;
@FunctionalInterface public interface JobHandler { void handle(String payload) throws Exception; }
