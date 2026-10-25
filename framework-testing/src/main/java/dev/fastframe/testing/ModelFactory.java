package dev.fastframe.testing;

import java.util.ArrayList;
import java.util.List;

/** Lightweight typed factory with no reflection and no implicit persistence. */
@FunctionalInterface
public interface ModelFactory<T> {
    T make();
    default List<T> makeMany(int count) {
        if (count < 0 || count > 10000) throw new IllegalArgumentException("Invalid factory count");
        var values = new ArrayList<T>(count);
        for (int i = 0; i < count; i++) values.add(make());
        return List.copyOf(values);
    }
}
