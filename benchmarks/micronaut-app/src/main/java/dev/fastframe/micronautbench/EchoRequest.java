package dev.fastframe.micronautbench;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record EchoRequest(String message) { }
