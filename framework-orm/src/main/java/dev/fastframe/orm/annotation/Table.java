package dev.fastframe.orm.annotation;
import java.lang.annotation.*;
/** Annotate a public top-level Java record. Mapper is generated at compile time. */
@Target(ElementType.TYPE) @Retention(RetentionPolicy.SOURCE)
public @interface Table { String value(); }
