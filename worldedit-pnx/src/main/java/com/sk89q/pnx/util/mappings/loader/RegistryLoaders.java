package com.sk89q.pnx.util.mappings.loader;

import com.sk89q.pnx.util.mappings.RegistryLoader;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

public final class RegistryLoaders {
    /**
     * Wraps the surrounding {@link Supplier} in a {@link RegistryLoader} which does
     * not take in any input value.
     *
     * @param supplier the supplier
     * @param <V> the value
     * @return a RegistryLoader wrapping the given Supplier
     */
    public static <V> RegistryLoader<Object, V> empty(@NotNull Supplier<V> supplier) {
        return input -> supplier.get();
    }

    /**
     * Returns a {@link RegistryLoader} which has not taken
     * in any input value.
     *
     * @param <I> the input
     * @param <V> the value
     * @return a RegistryLoader that is yet to contain a value.
     */
    public static <I, V> RegistryLoader<I, V> uninitialized() {
        return input -> null;
    }

    private RegistryLoaders() {
    }
}
