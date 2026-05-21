package dev.coachbot.plugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generic registry that indexes plugins by their {@link Identifiable#id()}.
 * <p>
 * Extend this class to create a typed Spring bean — Spring will inject all
 * matching beans as a {@code List<T>}:
 * <pre>
 * {@code @Component}
 * public class LlmBackendRegistry extends PluginRegistry<LlmBackend> {
 *     public LlmBackendRegistry(List<LlmBackend> backends) { super(backends); }
 * }
 * </pre>
 * Adding a new plugin = implement the interface + {@code @Component}. Zero other changes.
 */
public abstract class PluginRegistry<T extends Identifiable> {

    private final Map<String, T> plugins;

    protected PluginRegistry(List<T> all) {
        this.plugins = all.stream().collect(
                Collectors.toUnmodifiableMap(
                        Identifiable::id,
                        Function.identity(),
                        (a, b) -> { throw new IllegalStateException("Duplicate plugin id: " + a.id()); }
                )
        );
    }

    /**
     * Returns the plugin with the given id.
     *
     * @throws IllegalArgumentException if no plugin with that id is registered
     */
    public T get(String id) {
        T plugin = plugins.get(id);
        if (plugin == null) {
            throw new IllegalArgumentException(
                    "Plugin '%s' not found. Available: %s".formatted(id, plugins.keySet()));
        }
        return plugin;
    }

    public Optional<T> find(String id) {
        return Optional.ofNullable(plugins.get(id));
    }

    public boolean has(String id) {
        return plugins.containsKey(id);
    }

    public Set<String> available() {
        return plugins.keySet();
    }

    public Collection<T> all() {
        return plugins.values();
    }
}
