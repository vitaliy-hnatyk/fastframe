package dev.fastframe.demo.dto;

import dev.fastframe.core.annotation.Max;
import dev.fastframe.core.annotation.Min;
import dev.fastframe.core.annotation.Required;
import dev.fastframe.core.annotation.Size;

public record CreateUserRequest(
        @Required @Size(min = 2, max = 80) String name,
        @Required @Size(min = 5, max = 254) String email,
        @Min(13) @Max(120) int age) { }
