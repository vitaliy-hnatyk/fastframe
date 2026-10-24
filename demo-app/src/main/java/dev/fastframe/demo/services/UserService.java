package dev.fastframe.demo.services;

import dev.fastframe.core.annotation.Singleton;

@Singleton
public final class UserService {
    public String nameFor(long id) { return "user-" + id; }
}
