package dev.fastframe.auth;
@FunctionalInterface
public interface Policy<T> { boolean allows(Identity identity, T resource); }
