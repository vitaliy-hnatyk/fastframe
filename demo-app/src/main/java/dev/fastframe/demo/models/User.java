package dev.fastframe.demo.models;

import dev.fastframe.orm.annotation.Table;
import dev.fastframe.orm.annotation.Id;

@Table("users")
public record User(@Id Long id, String name) { }
