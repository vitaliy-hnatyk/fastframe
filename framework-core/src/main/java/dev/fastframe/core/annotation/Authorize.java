package dev.fastframe.core.annotation;
import java.lang.annotation.*;
/** Permission string (or role:NAME); checked before invoking a controller. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface Authorize { String value(); }
